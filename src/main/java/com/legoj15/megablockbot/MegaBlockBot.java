package com.legoj15.megablockbot;

import com.legoj15.megablockbot.platform.Platform;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

/** Loader-neutral entry point. Each loader's entrypoint calls {@link #init()} once during mod construction. */
public final class MegaBlockBot {
    public static final String MOD_ID = "megablockbot";
    public static final Logger LOGGER = LogUtils.getLogger();

    private MegaBlockBot() {}

    public static void init() {
        // The smoke script (scripts/smoke-server.ps1) waits for this exact line to prove the mod loaded.
        Platform.loader().onServerStarted(server ->
            LOGGER.info("MegaBlockBot ready ({} on Minecraft {})", Platform.loader().loaderName(), server.getServerVersion()));

        // Everything that touches Minecraft for the bot lives in the game package, which only a bot node compiles.
        //? if bot {
        /*com.legoj15.megablockbot.game.GameBootstrap.init();
        *///?}
    }
}
