package com.legoj15.megablockbot.game.build;

import com.legoj15.megablockbot.game.gametest.BuildGameTests;
import com.legoj15.megablockbot.platform.Platform;

/**
 * Building a blueprint (design 4.4, issue 17): where blueprints come from ({@link Blueprints}) and the
 * {@code /aiplayer blueprint} sub-commands. Called once from {@code GameBootstrap}, like the other features.
 */
public final class BuildFeature {
    private BuildFeature() {}

    public static void init() {
        // A datapack reload can change the server's structure templates, and a stop ends the server the cache is keyed
        // by: either way what was loaded is stale.
        Platform.loader().onServerDataLoaded(server -> Blueprints.clear());
        Platform.loader().onServerStopping(server -> Blueprints.clear());

        Platform.loader().registerGameTests(BuildGameTests.class);
    }
}
