package com.legoj15.megablockbot.game.safety;

import com.legoj15.megablockbot.game.gametest.BlockSafetyGameTests;
import com.legoj15.megablockbot.platform.LoaderServices;
import com.legoj15.megablockbot.game.task.BreakRules;
import com.legoj15.megablockbot.platform.Platform;

/**
 * Block safety (design 3.5): what the bot must never break or take from. Registers its hooks, GameTests and
 * {@code /aiplayer} sub-commands. Called once from {@code GameBootstrap}.
 */
public final class SafetyFeature {
    private SafetyFeature() {}

    public static void init() {
        LoaderServices loader = Platform.loader();
        // The loader reports player placements and breaks only (docs/platform-layer.md); see BlockSafety for what that misses.
        loader.onBlockPlaced((level, pos, state, cause) -> BlockSafety.of(level.getServer()).blockPlaced(level, pos, cause));
        loader.onBlockBroken((level, pos, state, cause) -> BlockSafety.of(level.getServer()).blockBroken(level, pos, state));
        loader.onServerStarted(server -> BlockSafety.of(server).onServerStarted());
        loader.onServerTick(server -> BlockSafety.of(server).tick());
        loader.onServerStopping(BlockSafety::discard);
        // Tags and POI data can change on /reload, and with them which blocks are beds and workstations.
        loader.onServerDataLoaded(server -> BlockKinds.rebuild());

        // Every task hands its body's navigator a filter that ends here: asked when a path is costed and again before
        // each break, on search threads as well as the server thread.
        BreakRules.setBodyRules((body, level, pos, state) ->
            BlockSafety.of(level.getServer()).mayBreak(body, level, pos, state).allowed());
        SafetyCommands.init();
        loader.registerGameTests(BlockSafetyGameTests.class);
    }
}
