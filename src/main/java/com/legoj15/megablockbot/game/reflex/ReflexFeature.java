package com.legoj15.megablockbot.game.reflex;

import com.legoj15.megablockbot.game.bot.ReflexSources;
import com.legoj15.megablockbot.game.gametest.ReflexGameTests;
import com.legoj15.megablockbot.platform.Platform;

/**
 * The reflex layer (design 3.2): hazards, self-defence, eating and the night policy, which preempt the executor.
 * Registers its dev command and GameTests, and makes itself the reflex source every body's runtime asks. Called once
 * from {@code GameBootstrap}. No tick hook is registered here: the layer's tick has to run inside the body's own, before
 * the executor, so the runtime calls it through {@link ReflexSources}.
 */
public final class ReflexFeature {
    private ReflexFeature() {}

    public static void init() {
        ReflexCommands.register();
        // Every runtime asks this once per body tick and passes the answer to its executor.
        ReflexSources.set(body -> ReflexLayer.of(body).tick(body));
        // Layers hold bodies; a server that stops takes its bodies with it.
        Platform.loader().onServerStopping(server -> ReflexLayer.clearAll());
        // GameTests: a no-op unless a GameTest run enabled them.
        Platform.loader().registerGameTests(ReflexGameTests.class);
    }
}
