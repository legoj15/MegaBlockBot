package com.legoj15.megablockbot.game.body;

import com.legoj15.megablockbot.game.gametest.BodyGameTests;
import com.legoj15.megablockbot.platform.Platform;

/**
 * The bot's body (design 3.1): registers its hooks, GameTests and {@code /aiplayer} sub-commands. Called once from
 * {@code GameBootstrap}.
 */
public final class BodyFeature {
    private BodyFeature() {}

    public static void init() {
        Platform.loader().onServerStarted(server -> BodyManager.of(server).onServerStarted());
        Platform.loader().onServerTick(server -> BodyManager.of(server).tick());
        Platform.loader().onServerStopping(server -> BodyManager.of(server).onServerStopping());
        BodyCommands.register();
        Platform.loader().registerGameTests(BodyGameTests.class);
    }
}
