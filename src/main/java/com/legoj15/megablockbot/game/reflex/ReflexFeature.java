package com.legoj15.megablockbot.game.reflex;

import com.legoj15.megablockbot.game.bot.ReflexSources;
import com.legoj15.megablockbot.game.gametest.ReflexGameTests;
import com.legoj15.megablockbot.game.gametest.ReflexRuntimeGameTests;
import com.legoj15.megablockbot.platform.Platform;

/**
 * The reflex layer (design 3.2): hazards, self-defence, eating and the night policy, which preempt the executor.
 * Registers its dev command and GameTests, and makes itself the reflex source every body's runtime asks. Called once
 * from {@code GameBootstrap}. The layer's own tick is not a server tick hook: it has to run inside the body's tick, before
 * the executor, so the runtime calls it through {@link ReflexSources}. The one server tick hook sweeps the layers of bodies
 * that are gone.
 */
public final class ReflexFeature {
    /** Ticks between sweeps for layers of bodies that are gone (the same cadence as the navigators'). */
    static final int SWEEP_INTERVAL_TICKS = 100;

    private ReflexFeature() {}

    public static void init() {
        ReflexCommands.register();
        // Every runtime asks this once per body tick and passes the answer to its executor.
        ReflexSources.set(body -> ReflexLayer.of(body).tick(body));
        // Layers hold bodies; a server that stops takes its bodies with it.
        Platform.loader().onServerStopping(server -> ReflexLayer.clearAll());
        // A body despawned for good leaves its layer behind until a sweep finds it (a respawn keeps the layer).
        Platform.loader().onServerTick(server -> {
            if (server.getTickCount() % SWEEP_INTERVAL_TICKS == 0) ReflexLayer.sweep(server);
        });
        // GameTests: a no-op unless a GameTest run enabled them.
        Platform.loader().registerGameTests(ReflexGameTests.class, ReflexRuntimeGameTests.class);
    }
}
