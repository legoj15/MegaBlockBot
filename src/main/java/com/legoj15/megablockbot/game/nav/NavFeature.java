package com.legoj15.megablockbot.game.nav;

import com.legoj15.megablockbot.game.gametest.BotNavGameTests;
import com.legoj15.megablockbot.game.gametest.NavEngineGameTests;
import com.legoj15.megablockbot.platform.Platform;

/**
 * The main mod's side of navigation (design 2.2): what drives the navigation module ({@code megablockbot_nav}) for a
 * bot, its dev command and its GameTests. Called once from {@code GameBootstrap}.
 */
public final class NavFeature {
    private NavFeature() {}

    public static void init() {
        NavCommands.register();
        // Navigators hold bodies; a server that stops takes its bodies with it.
        Platform.loader().onServerStopping(server -> BotNavigator.clearAll());
        // The dig test checks that a break went through the game the way a player's does: this hook sees it.
        // Only on a GameTest run (the run sets this property): the record is test evidence and is never cleared.
        if (System.getProperty("neoforge.enabledGameTestNamespaces") != null) {
            Platform.loader().onBlockBroken(BotNavGameTests::recordBreak);
        }

        // GameTests: a no-op unless a GameTest run enabled them.
        Platform.loader().registerGameTests(NavEngineGameTests.class, BotNavGameTests.class);
    }
}
