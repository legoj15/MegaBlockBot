package com.legoj15.megablockbot.game.resolver;

import com.legoj15.megablockbot.MegaBlockBot;
import com.legoj15.megablockbot.game.gametest.ResolverGameTests;
import com.legoj15.megablockbot.platform.Platform;

/**
 * The acquisition resolver and obtainability lint (design 4.3): registers its hooks, GameTests and
 * {@code /aiplayer} sub-commands. Called once from {@code GameBootstrap}.
 */
public final class ResolverFeature {
    private ResolverFeature() {}

    public static void init() {
        // Once at server start and after every /reload, after the loader's own data hooks (villager trades included).
        Platform.loader().onServerDataLoaded(server -> {
            try {
                AcquisitionIndexes.publish(AcquisitionIndexBuilder.build(server));
            } catch (RuntimeException e) {
                // The build isolates bad data itself; this is for a bug in the build. Keep the previous index.
                MegaBlockBot.LOGGER.error("Acquisition index build failed; keeping the previous index", e);
            }
        });
        // An index from one world must not answer for the next one on the same JVM.
        Platform.loader().onServerStopping(server -> AcquisitionIndexes.clear());

        LintCommand.init();
        Platform.loader().registerGameTests(ResolverGameTests.class);
    }
}
