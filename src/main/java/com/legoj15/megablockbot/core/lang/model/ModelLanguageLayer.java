package com.legoj15.megablockbot.core.lang.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.legoj15.megablockbot.core.exec.GoalResult;
import com.legoj15.megablockbot.core.goal.Goal;
import com.legoj15.megablockbot.core.lang.FixedGrammar;
import com.legoj15.megablockbot.core.lang.Intent;
import com.legoj15.megablockbot.core.lang.ItemSearch;
import com.legoj15.megablockbot.core.lang.LanguageContext;
import com.legoj15.megablockbot.core.lang.LanguageLayer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.regex.Pattern;

/**
 * The model-backed {@link LanguageLayer} (design 3.3): lets the owner phrase things loosely by asking a language
 * model, behind the same contract as {@link FixedGrammar}, so the body cannot tell which is in use.
 *
 * <p><strong>Decision points.</strong> The model is consulted only at two of them. {@link #interpret} tries the fixed
 * grammar first and, when it understands the whole message, returns its intents without a request. Otherwise the model
 * is asked, with a short system prompt (see {@link ModelPrompts}) and the tools of {@link ToolSchemas}.
 * {@link #explain} puts a failed, partial or cancelled goal into a sentence. Everything else is the body's.
 *
 * <p><strong>What the grammar understood is never lost.</strong> A message may be half understood ("get 2 stone; sing me
 * a song"). The grammar's intents that are not {@code Unknown} are always the start of the answer, in their order, and
 * the model is told they are already queued so that it deals with the rest only. The model's intents follow, and
 * nothing appears twice: a goal the model sets that the grammar (or it) already set is acknowledged to the model and not
 * queued again, and so is a repeated {@code status}, {@code pause}, {@code resume} or {@code cancel}. The grammar's
 * {@code Unknown} is dropped once the model has answered for it, and kept, with a note, when the model contributed
 * nothing.
 *
 * <p><strong>The model proposes, the checks decide.</strong> {@code search_items} is answered here from the context's
 * item registry and the answer goes back to the model, up to {@link ModelSettings#maxRoundTrips()} requests per
 * message. {@code set_goal} goes through {@link GoalArguments}: a call that fails a check is sent back once, as a
 * tool error, so the model can search and correct itself; if the corrected call (or any later one) fails too, the owner
 * gets an {@link Intent.Unknown} saying what was wrong. A refusal that was sent back and that the model then never
 * settled (the conversation ended on the round-trip cap, a plain answer or a failed round trip) reaches the owner the
 * same way. A goal the checks did not pass never becomes an intent. Text that comes with tool calls is dropped (the
 * model is told to use {@code say}); text with no calls is an {@link Intent.Say}. Whichever way the model's words
 * reach the owner (a plain answer, {@code say}, an explanation) they lose their thinking blocks and are cut at
 * {@link #MAX_OWNER_TEXT}.
 *
 * <p><strong>A bad or missing model costs the owner nothing.</strong> {@code interpret} never throws and never
 * blocks the caller: the future completes on the HTTP client's threads. A timeout, a refused connection, a non-2xx
 * answer, a body over the size limit or an unparseable body completes with what the grammar said (its
 * {@code Unknown}) plus one line saying the model could not be reached; goals the model had already given and the
 * checks passed are kept. {@code explain} never throws either: it falls back to a built-in sentence per failure kind,
 * and to one generic sentence for a result the built-in text cannot describe. With provider {@code none} the layer is
 * the grammar and makes no request. The API key reaches only the request headers: it is in no log line and no
 * returned text.
 */
public final class ModelLanguageLayer implements LanguageLayer {

    private static final System.Logger LOG = System.getLogger(ModelLanguageLayer.class.getName());

    /** Appended to the grammar's {@code Unknown} when the model could not be asked. */
    static final String UNREACHABLE_NOTE = "(I could not reach my language model just now, so only the fixed commands work.)";

    /** Appended when the model answered but never settled on anything the checks allow. */
    static final String UNDECIDED_NOTE = "(My language model did not come up with something I can do for that.)";

    /** What a refused call's reason is told to the owner as, once the model has had its one chance to correct. */
    private static final String REFUSED_PREFIX = "I could not turn that into something I can do: ";

    /** The explanation of a result the built-in text cannot describe (a null where it expected a position, say). */
    static final String UNEXPLAINABLE = "That goal ended without being completed, and I cannot say more about why.";

    /** Longest text from the model passed on to the owner, whichever way it arrives (an explanation, a say, an answer). */
    static final int MAX_OWNER_TEXT = 600;

    private static final int SEARCH_DEFAULT_LIMIT = 8, SEARCH_MAX_LIMIT = 15;

    /** A reasoning model may leave its thinking inside the text; the owner is not shown it. */
    private static final Pattern THINK_BLOCK = Pattern.compile("(?s)<think>.*?(?:</think>|$)");

    private final ModelSettings settings;
    /** Null when the provider is {@code none}: nothing to ask. */
    private final ChatBackend backend;
    private final FixedGrammar grammar = new FixedGrammar();

    /** The layer for these settings, with the backend their provider names. */
    public ModelLanguageLayer(ModelSettings settings) {
        this(settings, settings.enabled() ? ChatBackend.of(settings) : null);
    }

    /** The layer over a given backend (a wrapper that counts round trips, say). Ignored for provider none. */
    public ModelLanguageLayer(ModelSettings settings, ChatBackend backend) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.backend = settings.enabled() ? Objects.requireNonNull(backend, "backend") : null;
    }

    // ── interpret ─────────────────────────────────────────────────────────────────────────────────

    @Override
    public CompletableFuture<List<Intent>> interpret(String message, LanguageContext context) {
        CompletableFuture<List<Intent>> understood = grammar.interpret(message, context);
        // Nothing to ask the model about: no model, or nothing in the message, or nothing to look items up with.
        if (backend == null || message == null || message.isBlank() || context == null) return understood;
        return understood.thenCompose(fromGrammar -> {
            if (fromGrammar.stream().noneMatch(intent -> intent instanceof Intent.Unknown)) {
                return CompletableFuture.completedFuture(fromGrammar);
            }
            try {
                return new Conversation(message, context, fromGrammar).run()
                        .handle((intents, error) -> error == null ? intents : fallback(fromGrammar, UNREACHABLE_NOTE, error));
            } catch (RuntimeException e) {
                return CompletableFuture.completedFuture(fallback(fromGrammar, UNREACHABLE_NOTE, e));
            }
        });
    }

    /** What the grammar said, with {@code note} added to its first {@code Unknown}. Logs why. */
    private List<Intent> fallback(List<Intent> fromGrammar, String note, Throwable cause) {
        if (cause != null) LOG.log(System.Logger.Level.WARNING, "language model unavailable: " + describe(cause));
        List<Intent> intents = new ArrayList<>(fromGrammar);
        for (int i = 0; i < intents.size(); i++) {
            if (intents.get(i) instanceof Intent.Unknown unknown) {
                intents.set(i, new Intent.Unknown(unknown.reason() + " " + note));
                return intents;
            }
        }
        intents.add(new Intent.Unknown(note));
        return intents;
    }

    /** One owner message's talk with the model: the conversation so far, what has been accepted, the budgets. */
    private final class Conversation {
        private final String text;
        private final LanguageContext context;
        private final List<Intent> fromGrammar;
        /** What the grammar understood of the message (all but its {@code Unknown}s): always the start of the answer. */
        private final List<Intent> understood;
        private final String system;
        private final List<ChatBackend.Message> messages = new ArrayList<>();
        /** What the model contributed that the owner is to get, in the order it came. */
        private final List<Intent> intents = new ArrayList<>();
        private final Set<Goal> accepted = new HashSet<>();
        /** Refusals sent back to the model that no later call of the same tool settled. */
        private final List<Refusal> unsettled = new ArrayList<>();
        private int roundTrips;
        /** Whether the model has already had its one chance to correct a refused call. */
        private boolean corrected;

        Conversation(String text, LanguageContext context, List<Intent> fromGrammar) {
            this.text = text;
            this.context = context;
            this.fromGrammar = fromGrammar;
            this.understood = fromGrammar.stream().filter(intent -> !(intent instanceof Intent.Unknown)).toList();
            // The grammar's goals are already queued, so the model setting one again is a repeat, not a second goal.
            for (Intent intent : understood) {
                if (intent instanceof Intent.SetGoal set) accepted.add(set.goal());
            }
            this.system = ModelPrompts.interpret(context, understood);
        }

        CompletableFuture<List<Intent>> run() {
            messages.add(new ChatBackend.Message.User(text));
            return step();
        }

        private CompletableFuture<List<Intent>> step() {
            if (roundTrips >= settings.maxRoundTrips()) return CompletableFuture.completedFuture(finish(UNDECIDED_NOTE));
            roundTrips++;
            CompletableFuture<ChatBackend.Reply> sent;
            try {
                sent = backend.send(new ChatBackend.Request(system, messages, ToolSchemas.INTERPRET));
            } catch (RuntimeException e) {
                // A backend that throws instead of failing its future is a bug; it still must not cost the owner
                // the goals this conversation already accepted.
                return onFailure(e);
            }
            return sent.handle((reply, error) -> error == null ? onReply(reply) : onFailure(error))
                    .thenCompose(next -> next);
        }

        /**
         * The round trip failed: keep what the model already gave and what it was refused for, else the grammar's
         * answer plus the note.
         */
        private CompletableFuture<List<Intent>> onFailure(Throwable error) {
            if (!intents.isEmpty() || !owed().isEmpty()) {
                LOG.log(System.Logger.Level.WARNING, "language model failed after it had answered: " + describe(error));
                return CompletableFuture.completedFuture(answer());
            }
            return CompletableFuture.completedFuture(fallback(fromGrammar, UNREACHABLE_NOTE, error));
        }

        private CompletableFuture<List<Intent>> onReply(ChatBackend.Reply reply) {
            messages.add(reply.asMessage());
            if (reply.toolCalls().isEmpty()) {
                String said = ownerText(reply.text());
                // A plain answer is for the owner, unless the model already queued something: then it is chatter
                // after the fact. What the grammar understood does not count: that is no answer of the model's.
                if (!said.isEmpty() && intents.isEmpty()) intents.add(new Intent.Say(said));
                return CompletableFuture.completedFuture(finish(UNDECIDED_NOTE));
            }
            boolean mayCorrect = !corrected;
            boolean needsAnswer = false;
            boolean sentBack = false;
            List<ChatBackend.Message> results = new ArrayList<>();
            for (ChatBackend.ToolCall call : reply.toolCalls()) {
                Handled handled = handle(call, mayCorrect);
                results.add(new ChatBackend.Message.ToolResult(call.id(), handled.result(), handled.refused()));
                note(call, handled);
                needsAnswer |= handled.needsAnswer();
                sentBack |= handled.refused() && handled.needsAnswer();
            }
            if (sentBack) corrected = true;
            if (!needsAnswer) return CompletableFuture.completedFuture(finish(UNDECIDED_NOTE));
            messages.addAll(results);
            return step();
        }

        /**
         * Books what a call came to: its intent joins the answer, and the refusals still owed to the owner are kept
         * up to date. A refusal sent back is owed until a later call of the same tool gets somewhere (a goal
         * accepted, a say said, a search answered); one that became an {@code Unknown} is paid by that intent.
         */
        private void note(ChatBackend.ToolCall call, Handled handled) {
            if (handled.refused() && handled.needsAnswer()) {
                unsettled.add(new Refusal(call.name(), handled.result()));
            } else if (handled.refused() || handled.intent() != null || handled.needsAnswer()) {
                // Either the call got somewhere or its refusal is final: an earlier refusal of this tool is paid.
                unsettled.removeIf(earlier -> earlier.tool().equals(call.name()));
            }
            if (handled.intent() != null) intents.add(handled.intent());
        }

        /** What the layer does with one call. {@code intent} may be null; {@code needsAnswer} asks for another round trip. */
        private record Handled(String result, boolean refused, Intent intent, boolean needsAnswer) {
            static Handled done(String result, Intent intent) {
                return new Handled(result, false, intent, false);
            }

            static Handled answered(String result) {
                return new Handled(result, false, null, true);
            }
        }

        private Handled handle(ChatBackend.ToolCall call, boolean mayCorrect) {
            try {
                JsonObject arguments = arguments(call);
                return switch (call.name()) {
                    case ToolSchemas.SEARCH_ITEMS -> search(arguments);
                    case ToolSchemas.SET_GOAL -> setGoal(arguments, mayCorrect);
                    case ToolSchemas.CANCEL -> control("Cancelled.", new Intent.Cancel(GoalArguments.flagged(arguments, "all")));
                    case ToolSchemas.STATUS -> control("The owner will be told the status.", new Intent.Status());
                    case ToolSchemas.PAUSE -> control("Paused.", new Intent.Pause());
                    case ToolSchemas.RESUME -> control("Resumed.", new Intent.Resume());
                    case ToolSchemas.SAY -> say(arguments, mayCorrect);
                    default -> refuse("there is no tool called '" + call.name() + "'; use only the tools you were given",
                            mayCorrect);
                };
            } catch (ArgumentsRefused bad) {
                return refuse(bad.getMessage(), mayCorrect);
            } catch (RuntimeException e) {
                // A bug or a registry hiccup in one call must not take the others, or the message, down with it.
                LOG.log(System.Logger.Level.WARNING, "tool call '" + call.name() + "' failed: " + describe(e));
                return refuse("that tool call failed on the bot's side", mayCorrect);
            }
        }

        private Handled search(JsonObject arguments) {
            JsonElement query = arguments.get("query");
            if (query == null || !query.isJsonPrimitive() || query.getAsString().isBlank()) {
                throw new ArgumentsRefused("search_items needs a non-empty 'query'");
            }
            int limit = SEARCH_DEFAULT_LIMIT;
            JsonElement asked = arguments.get("limit");
            if (asked != null && asked.isJsonPrimitive() && asked.getAsJsonPrimitive().isNumber()) {
                limit = Math.max(1, Math.min(SEARCH_MAX_LIMIT, asked.getAsInt()));
            }
            JsonArray found = new JsonArray();
            for (ItemSearch.ItemMatch match : context.items().search(query.getAsString().strip(), limit)) {
                JsonObject entry = new JsonObject();
                entry.addProperty("id", match.key().id());
                entry.addProperty("name", match.displayName());
                found.add(entry);
            }
            return Handled.answered(found.toString());
        }

        private Handled setGoal(JsonObject arguments, boolean mayCorrect) {
            GoalArguments.Outcome outcome = GoalArguments.check(arguments, context);
            if (outcome.goal() == null) return refuse(outcome.problem(), mayCorrect);
            if (!accepted.add(outcome.goal())) return Handled.done("That goal is already set; do not repeat it.", null);
            return Handled.done("Goal accepted: " + ResultText.describe(outcome.goal(), true)
                    + ". Do not set it again.", new Intent.SetGoal(outcome.goal()));
        }

        /**
         * A control (status, pause, resume, cancel). One the grammar already gave is not queued again when the model
         * restates it, and neither is the model's own immediate repeat; but pause, resume, pause from the model are
         * three things the owner asked for in that order, and all three stay. The model is answered either way.
         */
        private Handled control(String result, Intent intent) {
            boolean queued = understood.contains(intent)
                    || (!intents.isEmpty() && intents.get(intents.size() - 1).equals(intent));
            return Handled.done(queued ? "That is already queued; do not repeat it." : result, queued ? null : intent);
        }

        private Handled say(JsonObject arguments, boolean mayCorrect) {
            JsonElement said = arguments.get("text");
            // A say that is nothing but thinking says nothing: refused like an empty one.
            String text = said == null || !said.isJsonPrimitive() ? "" : ownerText(said.getAsString());
            if (text.isEmpty()) return refuse("say needs non-empty 'text'", mayCorrect);
            return Handled.done("Said.", new Intent.Say(text));
        }

        /**
         * A refused call: sent back to the model as an error while it may still correct itself, else it becomes an
         * {@link Intent.Unknown} the owner can read.
         */
        private Handled refuse(String problem, boolean mayCorrect) {
            if (mayCorrect) return new Handled(problem, true, null, true);
            return new Handled(problem, true, new Intent.Unknown(refusedText(problem)), false);
        }

        /**
         * The end of the conversation: what the grammar understood, then what the model gave, then the reasons of
         * any refusal that was sent back and never settled. The grammar's own {@code Unknown} stays, with the note,
         * only when the model contributed nothing at all.
         */
        private List<Intent> finish(String note) {
            if (intents.isEmpty() && owed().isEmpty()) return fallback(fromGrammar, note, null);
            return answer();
        }

        private List<Intent> answer() {
            List<Intent> all = new ArrayList<>(understood);
            all.addAll(intents);
            for (Refusal refusal : owed()) all.add(new Intent.Unknown(refusedText(refusal.reason())));
            return List.copyOf(all);
        }

        /**
         * The refusals the owner is told about. A fumbled say is never one of them: it is about how the model used
         * its own tool, not about anything the owner asked for, so with nothing else to tell the owner gets the
         * grammar's answer and the note.
         */
        private List<Refusal> owed() {
            return unsettled.stream().filter(refusal -> !ToolSchemas.SAY.equals(refusal.tool())).toList();
        }
    }

    /** A call the model made that was refused and sent back to it: which tool, and why. */
    private record Refusal(String tool, String reason) {}

    /** What the owner reads of a refused call: the reason, and where to find what the bot does understand. */
    private static String refusedText(String problem) {
        // The reason can quote what the model sent (an item id, a tool name): cut like everything the owner reads.
        return REFUSED_PREFIX + ownerText(problem) + " (say 'help' for the commands I understand)";
    }

    /** The arguments of a call were not usable at all (not JSON, not an object): refused like any failed check. */
    private static final class ArgumentsRefused extends RuntimeException {
        ArgumentsRefused(String message) {
            super(message, null, false, false);
        }
    }

    private static JsonObject arguments(ChatBackend.ToolCall call) {
        String json = call.argumentsJson() == null || call.argumentsJson().isBlank() ? "{}" : call.argumentsJson();
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (parsed.isJsonObject()) return parsed.getAsJsonObject();
        } catch (JsonParseException notJson) {
            throw new ArgumentsRefused("the arguments of " + call.name() + " were not valid JSON; send one JSON object");
        }
        throw new ArgumentsRefused("the arguments of " + call.name() + " must be one JSON object");
    }

    // ── explain ───────────────────────────────────────────────────────────────────────────────────

    @Override
    public CompletableFuture<Optional<String>> explain(GoalResult result, LanguageContext context) {
        if (backend == null) return grammar.explain(result, context);
        // Everything is inside the try: a result with a null in it must cost the owner a sentence, not an exception.
        try {
            // A satisfied goal has nothing to explain, and a model has nothing to add to it.
            if (result.status() == GoalResult.Status.SATISFIED) return CompletableFuture.completedFuture(Optional.empty());
            String builtIn = builtInSentence(result);
            ChatBackend.Request request = new ChatBackend.Request(ModelPrompts.EXPLAIN,
                    List.of(new ChatBackend.Message.User(ResultText.facts(result))), List.of());
            return backend.send(request).handle((reply, error) -> explained(reply, error, builtIn));
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "language model could not explain a goal: " + describe(e));
            return CompletableFuture.completedFuture(Optional.of(builtInSentence(result)));
        }
    }

    /** The model's sentence, else the built-in one. Never throws, so the future {@code explain} returns never fails. */
    private Optional<String> explained(ChatBackend.Reply reply, Throwable error, String builtIn) {
        if (error != null) {
            LOG.log(System.Logger.Level.WARNING, "language model could not explain a goal: " + describe(error));
            return Optional.of(builtIn);
        }
        try {
            String sentence = ownerText(reply.text());
            return Optional.of(sentence.isEmpty() ? builtIn : sentence);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "language model could not explain a goal: " + describe(e));
            return Optional.of(builtIn);
        }
    }

    /** The built-in sentence for a result, or {@link #UNEXPLAINABLE} when the result holds what it cannot describe. */
    private String builtInSentence(GoalResult result) {
        try {
            return ResultText.builtIn(result);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "a goal result could not be described: " + describe(e));
            return UNEXPLAINABLE;
        }
    }

    // ── small pieces ──────────────────────────────────────────────────────────────────────────────

    /**
     * The model's words as the owner may read them: without any thinking it left in, trimmed, and cut at
     * {@link #MAX_OWNER_TEXT}. One rule for a plain answer, a {@code say} and an explanation.
     */
    static String ownerText(String text) {
        String visible = THINK_BLOCK.matcher(text == null ? "" : text).replaceAll("").strip();
        if (visible.length() <= MAX_OWNER_TEXT) return visible;
        int end = MAX_OWNER_TEXT;
        // Never cut a surrogate pair in half: the half left over would print as a broken character.
        if (Character.isHighSurrogate(visible.charAt(end - 1))) end--;
        return visible.substring(0, end).stripTrailing();
    }

    /**
     * A failure as one log-safe line: the exception's class and message. The backends have already scrubbed the key
     * and the server's address from their messages; they are scrubbed again here because this is the last stop before
     * a log, and the stack trace is left out (it is the same wherever a secret might hide).
     */
    private String describe(Throwable error) {
        Throwable cause = error;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        String line = cause.getClass().getSimpleName() + (message == null ? "" : ": " + message);
        return settings.scrub(line);
    }
}
