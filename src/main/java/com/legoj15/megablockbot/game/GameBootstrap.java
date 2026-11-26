package com.legoj15.megablockbot.game;

import com.legoj15.megablockbot.MegaBlockBot;
import com.legoj15.megablockbot.game.body.BodyFeature;
import com.legoj15.megablockbot.game.command.AiPlayerCommand;
import com.legoj15.megablockbot.game.gametest.SolverGameTests;
import com.legoj15.megablockbot.game.nav.NavFeature;
import com.legoj15.megablockbot.game.resolver.ResolverFeature;
import com.legoj15.megablockbot.nav.NavModule;
import com.legoj15.megablockbot.platform.Platform;

/**
 * Where the bot's Minecraft-facing features register, called once from {@link MegaBlockBot#init()} on a bot node
 * ({@code //? if bot}). This package, and everything under it, is compiled only where {@code bot_enabled} is true.
 */
public final class GameBootstrap {
    private GameBootstrap() {}

    public static void init() {
        MegaBlockBot.LOGGER.debug("Bot enabled; navigation module {}", NavModule.MOD_ID);

        // One line per feature. Each registers its own hooks, GameTests and /aiplayer sub-commands, so features
        // developed side by side never meet in this file.
        AiPlayerCommand.init();
        BodyFeature.init();
        ResolverFeature.init();
        NavFeature.init();

        // GameTests: a no-op unless a GameTest run enabled them.
        Platform.loader().registerGameTests(SolverGameTests.class);
    }
}
