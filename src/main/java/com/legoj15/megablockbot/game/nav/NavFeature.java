package com.legoj15.megablockbot.game.nav;

import com.legoj15.megablockbot.game.gametest.NavEngineGameTests;
import com.legoj15.megablockbot.platform.Platform;

/**
 * The main mod's side of navigation (design 2.2): what drives the navigation module ({@code megablockbot_nav}) for a
 * bot, and its GameTests. Called once from {@code GameBootstrap}.
 */
public final class NavFeature {
    private NavFeature() {}

    public static void init() {
        // GameTests: a no-op unless a GameTest run enabled them.
        Platform.loader().registerGameTests(NavEngineGameTests.class);
    }
}
