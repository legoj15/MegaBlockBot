package com.legoj15.megablockbot.game.reflex;

import com.legoj15.megablockbot.core.exec.ReflexHold;
import com.legoj15.megablockbot.game.body.BotPlayer;
import com.legoj15.megablockbot.game.bot.ReflexSource;
import com.legoj15.megablockbot.game.bot.ReflexSources;
import com.legoj15.megablockbot.game.gametest.ReflexGameTests;
import com.legoj15.megablockbot.game.gametest.ReflexRuntimeGameTests;
import com.legoj15.megablockbot.platform.Platform;
import net.minecraft.nbt.CompoundTag;

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
        // Every runtime asks this once per body tick and passes the answer to its executor; the same source carries the
        // moments (a death ends the hold at once) and the state (shelter blocks) between the brain and the layer.
        ReflexSources.set(new ReflexSource() {
            @Override
            public ReflexHold holdFor(BotPlayer body) {
                return ReflexLayer.of(body).tick(body);
            }

            @Override
            public void onDeath(BotPlayer body) {
                ReflexLayer.of(body).bodyDied(body);
            }

            @Override
            public void saveShelterBlocks(BotPlayer body, CompoundTag out) {
                ReflexLayer.of(body).saveShelterBlocks(out);
            }

            @Override
            public void loadShelterBlocks(BotPlayer body, CompoundTag in) {
                ReflexLayer.of(body).loadShelterBlocks(body, in);
            }
        });
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
