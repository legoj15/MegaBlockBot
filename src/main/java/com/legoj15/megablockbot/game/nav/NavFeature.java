package com.legoj15.megablockbot.game.nav;

import com.legoj15.megablockbot.game.gametest.BotNavGameTests;
import com.legoj15.megablockbot.game.gametest.FishGameTests;
import com.legoj15.megablockbot.game.gametest.MoveAndMineGameTests;
import com.legoj15.megablockbot.game.gametest.NavEngineGameTests;
import com.legoj15.megablockbot.game.task.TaskFeature;
import com.legoj15.megablockbot.platform.Platform;

/**
 * The main mod's side of navigation (design 2.2): what drives the navigation module ({@code megablockbot_nav}) for a
 * bot, its dev command and its GameTests. Called once from {@code GameBootstrap}.
 */
public final class NavFeature {
    /** Ticks between sweeps for navigators of bodies that are gone (5 seconds: nothing needs it sooner). */
    static final int SWEEP_INTERVAL_TICKS = 100;

    private NavFeature() {}

    public static void init() {
        NavCommands.register();
        // The tasks that move and mine are built on the navigator and registered here for now; the bot runtime
        // (BotFeature) will take this call over once it exists.
        TaskFeature.init();
        // Navigators hold bodies; a server that stops takes its bodies with it, and a body despawned for good leaves
        // its navigator behind until a sweep finds it.
        Platform.loader().onServerStopping(server -> BotNavigator.clearAll());
        Platform.loader().onServerTick(server -> {
            if (server.getTickCount() % SWEEP_INTERVAL_TICKS == 0) BotNavigator.sweep(server);
        });
        // The dig test checks that a break went through the game the way a player's does: this hook sees it.
        // Only on a GameTest run (the run sets this property): the record is test evidence and is never cleared.
        if (System.getProperty("neoforge.enabledGameTestNamespaces") != null) {
            Platform.loader().onBlockBroken(BotNavGameTests::recordBreak);
        }

        // GameTests: a no-op unless a GameTest run enabled them.
        Platform.loader().registerGameTests(NavEngineGameTests.class, BotNavGameTests.class, MoveAndMineGameTests.class,
                FishGameTests.class);
    }
}
