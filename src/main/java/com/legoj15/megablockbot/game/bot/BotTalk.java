package com.legoj15.megablockbot.game.bot;

import com.legoj15.megablockbot.MegaBlockBot;
import com.legoj15.megablockbot.core.Pos;
import com.legoj15.megablockbot.core.lang.Intent;
import com.legoj15.megablockbot.core.lang.LanguageContext;
import com.legoj15.megablockbot.game.body.BodyManager;
import com.legoj15.megablockbot.game.body.BotPlayer;
import com.legoj15.megablockbot.game.build.Blueprints;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nullable;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * The owner's words to a body, and what the body does with the language layer's answer (design 3.3). The path is the
 * same for a command ({@code /aiplayer goal}) and for a chat line addressed to the bot: build a context, ask the layer
 * what the text means, and carry out the intents on the server thread.
 *
 * <p>The layer may take as long as a model call needs and may complete its future on any thread, so the intents are
 * carried out only after a hop back to the server thread. A layer that has already answered (the fixed grammar always
 * has) is carried out at once, so a command's own feedback is complete when the command returns.
 */
public final class BotTalk {
    private BotTalk() {}

    /**
     * A chat line from {@code sender}: when it is the owner of a body and starts with that body's name
     * ({@code Bot1, make 4 planks} or {@code Bot1 make 4 planks}) the rest is the owner's words to that body. A line from
     * anyone else is not, however privileged they are: chat is the owner's voice, commands are where ops speak.
     */
    public static void onChat(ServerPlayer sender, String message) {
        MinecraftServer server = sender.getServer();
        if (server == null) return;
        if (!server.isSameThread()) {
            server.execute(() -> onChat(sender, message));
            return;
        }
        for (BotPlayer body : BodyManager.of(server).all()) {
            if (body.ownerId() == null || !body.ownerId().equals(sender.getUUID())) continue;
            String words = addressedText(body.getGameProfile().getName(), message);
            if (words == null) continue;
            BotRuntime runtime = BotRuntime.of(body);
            if (runtime == null) continue;
            interpret(body, runtime, words, null, sender);
        }
    }

    /**
     * What follows the bot's name when {@code message} is addressed to it, or null when it is not (or says nothing more).
     * The name is matched ignoring case and must end at a word edge, so {@code Bot12 hello} is not for {@code Bot1}; one
     * comma or colon after it is allowed.
     */
    static String addressedText(String name, String message) {
        String text = message.strip();
        if (text.length() <= name.length() || !text.regionMatches(true, 0, name, 0, name.length())) return null;
        char next = text.charAt(name.length());
        if (Character.isLetterOrDigit(next) || next == '_') return null;
        String rest = text.substring(name.length());
        int start = 0;
        while (start < rest.length() && (Character.isWhitespace(rest.charAt(start)) || rest.charAt(start) == ','
                || rest.charAt(start) == ':')) {
            start++;
        }
        String words = rest.substring(start).strip();
        return words.isEmpty() ? null : words;
    }

    /**
     * Interprets {@code text} for this body and carries out what it means. {@code source} is the command it came from,
     * or null for chat: the answer goes to it as well as to the owner.
     */
    public static void interpret(BotPlayer body, BotRuntime runtime, String text, @Nullable CommandSourceStack source) {
        ServerPlayer speaker = source != null && source.getEntity() instanceof ServerPlayer player ? player : null;
        interpret(body, runtime, text, source, speaker);
    }

    /** As above, with the player behind the words named: chat has no command source to read it from. */
    public static void interpret(BotPlayer body, BotRuntime runtime, String text, @Nullable CommandSourceStack source,
                                 @Nullable ServerPlayer speaker) {
        runtime.attach(body);
        MinecraftServer server = body.getServer();
        if (server == null) return;
        CompletableFuture<List<Intent>> answer;
        try {
            answer = LanguageLayers.current().interpret(text, context(body, runtime, speaker));
        } catch (RuntimeException e) {
            // The contract says a layer never throws; if it does, the owner still gets an answer and the log the trace.
            MegaBlockBot.LOGGER.error("The language layer threw while reading \"{}\"", text, e);
            answer = CompletableFuture.completedFuture(List.of(new Intent.Unknown("I could not read that.")));
        }
        if (answer.isDone() && server.isSameThread()) {
            apply(body, runtime, intentsOf(answer), source);
        } else {
            answer.whenComplete((intents, error) -> server.execute(() ->
                    apply(body, runtime, error != null ? failed(error) : intents, source)));
        }
    }

    private static List<Intent> intentsOf(CompletableFuture<List<Intent>> done) {
        try {
            return done.join();
        } catch (RuntimeException e) {
            return failed(e);
        }
    }

    private static List<Intent> failed(Throwable error) {
        MegaBlockBot.LOGGER.error("The language layer failed", error);
        return List.of(new Intent.Unknown("I could not read that."));
    }

    /** Carries out the intents in order, on the server thread. */
    public static void apply(BotPlayer body, BotRuntime runtime, List<Intent> intents, @Nullable CommandSourceStack source) {
        if (body.hasDisconnected()) {
            MegaBlockBot.LOGGER.info("{} left before its owner's words were read; they are dropped", body.getGameProfile().getName());
            return;
        }
        runtime.attach(body);
        runtime.withFeedback(source, () -> {
            for (Intent intent : intents) carryOut(runtime, intent);
        });
    }

    private static void carryOut(BotRuntime runtime, Intent intent) {
        switch (intent) {
            case Intent.SetGoal set -> {
                boolean busy = runtime.view().goal() != null;
                runtime.submit(set.goal());
                runtime.say((busy ? "Queued: " : "On it: ") + BotSpeech.goal(set.goal()) + ".");
            }
            case Intent.Cancel cancel -> {
                if (runtime.view().goal() == null && runtime.view().queue().isEmpty()) {
                    runtime.say("Nothing to cancel.");
                } else {
                    // The CANCELLED result is published, and said, by the executor.
                    runtime.cancel(cancel.all());
                }
            }
            case Intent.Pause pause -> {
                if (runtime.hasGoal()) {
                    runtime.pause();
                    runtime.say("Paused.");
                } else {
                    runtime.say("Nothing to pause.");
                }
            }
            case Intent.Resume resume -> {
                if (runtime.hasGoal()) {
                    runtime.resume();
                    runtime.say("Resumed.");
                } else {
                    runtime.say("Nothing to resume.");
                }
            }
            // The supply list belongs to the body: the answer needs the body the runtime was attached to (apply did that).
            // Naming a chest also queues the body's look inside it, ahead of whatever the owner says next.
            case Intent.UseChest use -> runtime.say(BotSupply.use(runtime, use.dimension(), use.pos()));
            case Intent.ForgetChest forget -> runtime.say(BotSupply.forget(runtime.body(), forget.dimension(), forget.pos()));
            case Intent.Chests chests -> runtime.say(BotSupply.list(runtime.body()));
            case Intent.Status status -> runtime.say(runtime.statusText());
            case Intent.Say say -> runtime.say(say.text());
            case Intent.Unknown unknown -> runtime.say(unknown.reason());
        }
    }

    /** The language layer's sentence for a goal that did not work out, or empty when it has none (no model). */
    static CompletableFuture<java.util.Optional<String>> explain(BotPlayer body, BotRuntime runtime,
                                                                  com.legoj15.megablockbot.core.exec.GoalResult result) {
        return LanguageLayers.current().explain(result, context(body, runtime, null));
    }

    /**
     * What the layer may read for one message (design 3.3): where everyone is, the status, the game's enchantments,
     * the player behind the words (null from the console) and whether the owner stands in the body's dimension
     * (issue #27: an owner elsewhere has no position the body can walk to, and an absent one is no barrier).
     */
    private static LanguageContext context(BotPlayer body, BotRuntime runtime, @Nullable ServerPlayer speaker) {
        ServerPlayer owner = runtime.ownerOnline();
        Pos botPos = pos(body.blockPosition());
        Pos ownerPos = owner != null && owner.level() == body.level() ? pos(owner.blockPosition()) : botPos;
        Set<String> enchantments = new TreeSet<>();
        body.registryAccess().registryOrThrow(Registries.ENCHANTMENT).keySet()
                .forEach(id -> enchantments.add(id.toString()));
        return new LanguageContext(new RegistryItemSearch(), body.level().dimension().location().toString(), ownerPos,
                botPos, runtime.statusText(), Blueprints.names(), enchantments,
                speaker == null ? null : speaker.getUUID().toString(), owner == null || owner.level() == body.level());
    }

    private static Pos pos(BlockPos pos) {
        return new Pos(pos.getX(), pos.getY(), pos.getZ());
    }
}
