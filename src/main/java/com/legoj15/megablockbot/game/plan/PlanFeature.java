package com.legoj15.megablockbot.game.plan;

import com.legoj15.megablockbot.game.gametest.PlanInputGameTests;
import com.legoj15.megablockbot.platform.Platform;

/**
 * Planning inputs read from the game (design 4.2, 3.4): the recipe book and the snapshot collector, and the planner
 * that runs on them. Registers its hooks and GameTests. Called once from {@code GameBootstrap}.
 */
public final class PlanFeature {
    private PlanFeature() {}

    public static void init() {
        // The crafting source is this feature's own. Other features add theirs (a mod's machines) with
        // RecipeSources.register during their init; the book is built after all of them ran, at server start.
        RecipeSources.register(new CraftingRecipeSource());
        RecipeSources.register(new CookingRecipeSource());

        // Once at server start and after every /reload, after the loader's own data hooks, like the acquisition index.
        Platform.loader().onServerDataLoaded(RecipeBookBuilder::rebuild);
        // A book or a memory from one world must not answer for the next one on the same JVM.
        Platform.loader().onServerStopping(server -> {
            RecipeBooks.clear();
            BotMemory.clearAll();
        });

        SightCommand.init();
        Platform.loader().registerGameTests(PlanInputGameTests.class, SightGameTests.class);
    }
}
