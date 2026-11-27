package com.legoj15.megablockbot.core.lang.model;

import com.legoj15.megablockbot.core.lang.Intent;
import com.legoj15.megablockbot.core.lang.LanguageContext;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Evidence, not a check: sends about twenty loosely phrased messages to a real OpenAI-compatible server and prints
 * what came back, how many round trips each took and how long. Skipped unless the system property
 * {@code mbb.liveProbe} holds the server's base URL, so the default test run never touches the network:
 *
 * <pre>./gradlew :1.21.1:test --tests '*LiveModelProbe*' -Dmbb.liveProbe=http://host:8000/v1 [-Dmbb.liveProbe.model=name]</pre>
 *
 * The table is also written to {@code build/live-probe/table.md} under the node's project directory. It sends
 * nothing to the server but these messages, and stops at the first one if the server cannot be reached.
 */
@EnabledIfSystemProperty(named = "mbb.liveProbe", matches = ".+")
class LiveModelProbe {

    private static final List<String> MESSAGES = List.of(
            "make 3 stone pickaxe",                                          // the grammar's own form: no request expected
            "could you grab me a couple of stone picks",                     // get, loosely
            "I need three iron axes please",                                 // craft with a count
            "I'd love a diamond pickaxe with efficiency five",               // an enchanted item
            "get me a book with mending on it",                              // an enchanted book
            "put on a full set of iron armor and take a shield too",         // equip
            "I need at least an iron pickaxe",                               // a tool tier
            "stash 64 cobblestone in the chest at 100 64 -190",              // stockpile
            "build the watch tower over at 90 64 -210",                      // a listed blueprint
            "walk over to 250 70 -100",                                      // go somewhere
            "hey, come over here",                                           // come here
            "forget it, stop what you're doing",                             // cancel
            "what are you up to?",                                           // status
            "hold on a sec, wait up",                                        // pause
            "ok you can carry on now",                                       // resume
            "how many kinds of pickaxe are there?",                          // a question
            "asdf qwerty blorp",                                             // nonsense
            "get me a stack of bedrock",                                     // something impossible: no such item here
            "build me a castle at 0 64 0",                                   // something impossible: no such blueprint
            "get 5 stone picks and then go to 10 64 10");                    // two goals in one breath

    /** Counts round trips, notes which tools the model called, and keeps the last failure's message. */
    private static final class Counting implements ChatBackend {
        private final ChatBackend delegate;
        final AtomicInteger sends = new AtomicInteger();
        final List<String> tools = new ArrayList<>();
        volatile String lastError = "";

        Counting(ChatBackend delegate) {
            this.delegate = delegate;
        }

        @Override
        public CompletableFuture<Reply> send(Request request) {
            sends.incrementAndGet();
            return delegate.send(request).whenComplete((reply, error) -> {
                if (error != null) {
                    lastError = String.valueOf(error.getCause() == null ? error.getMessage() : error.getCause().getMessage());
                } else {
                    synchronized (tools) {
                        reply.toolCalls().forEach(call -> tools.add(call.name()));
                        if (reply.toolCalls().isEmpty()) tools.add("(text)");
                    }
                }
            });
        }

        void reset() {
            sends.set(0);
            synchronized (tools) {
                tools.clear();
            }
            lastError = "";
        }
    }

    @Test
    void probe() throws Exception {
        String base = System.getProperty("mbb.liveProbe");
        String model = System.getProperty("mbb.liveProbe.model", "qwen3.8-flash");
        ModelSettings settings = new ModelSettings(ModelSettings.Provider.OPENAI, base, model, "",
                Duration.ofSeconds(180), ModelSettings.DEFAULT_MAX_ROUND_TRIPS, ModelSettings.DEFAULT_MAX_OUTPUT_TOKENS);
        Counting backend = new Counting(ChatBackend.of(settings));
        ModelLanguageLayer layer = new ModelLanguageLayer(settings, backend);
        LanguageContext context = ModelFixtures.context();

        StringBuilder table = new StringBuilder();
        table.append("Live probe: ").append(base).append(", model ").append(model).append("\n\n");
        table.append("| # | message | tools called | intents | trips | ms |\n|---|---|---|---|---|---|\n");
        int row = 0;
        for (String message : MESSAGES) {
            backend.reset();
            long start = System.nanoTime();
            List<Intent> intents = layer.interpret(message, context).get(10, TimeUnit.MINUTES);
            long millis = Duration.ofNanos(System.nanoTime() - start).toMillis();
            row++;
            boolean unreachable = intents.stream().anyMatch(i -> i instanceof Intent.Unknown u
                    && u.reason().contains(ModelLanguageLayer.UNREACHABLE_NOTE));
            String tools;
            synchronized (backend.tools) {
                tools = backend.tools.isEmpty() ? "none (grammar)" : String.join(", ", backend.tools);
            }
            table.append("| ").append(row).append(" | ").append(cell(message)).append(" | ").append(cell(tools))
                    .append(" | ").append(cell(render(intents))).append(" | ").append(backend.sends.get())
                    .append(" | ").append(millis).append(" |\n");
            if (unreachable) {
                table.append("\nThe model could not be reached (").append(backend.lastError)
                        .append("). Stopped at the first message that failed.\n");
                break;
            }
        }

        System.out.println(table);
        Path out = Path.of("build", "live-probe", "table.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, table.toString(), StandardCharsets.UTF_8);
    }

    private static String render(List<Intent> intents) {
        List<String> parts = new ArrayList<>();
        for (Intent intent : intents) {
            if (intent instanceof Intent.SetGoal set) parts.add("SetGoal: " + ResultText.describe(set.goal(), true));
            else if (intent instanceof Intent.Say say) parts.add("Say: " + clip(say.text()));
            else if (intent instanceof Intent.Unknown unknown) parts.add("Unknown: " + clip(unknown.reason()));
            else parts.add(intent.toString());
        }
        return String.join("; ", parts);
    }

    private static String clip(String text) {
        String flat = text.replaceAll("\\s+", " ").strip();
        return flat.length() <= 110 ? flat : flat.substring(0, 110) + "...";
    }

    private static String cell(String text) {
        return text.replace("|", "\\|");
    }
}
