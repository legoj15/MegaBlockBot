package com.legoj15.megablockbot.game.gametest;

import com.legoj15.megablockbot.MegaBlockBot;
import com.legoj15.megablockbot.core.ItemKey;
import com.legoj15.solver.PlannedStep;
import com.legoj15.solver.RecipeGraphSolver;
import java.util.List;
import java.util.Map;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/**
 * Proves the nested solver-core library loads from mod code inside the running game. Dropping the library from the
 * dev runtime classpath or from the jar-in-jar wiring (build.gradle.kts) turns this into a NoClassDefFoundError.
 */
public final class SolverGameTests {
    private SolverGameTests() {}

    /** The least a recipe must say for the solver to plan with it. */
    private record Recipe(String id, List<List<ItemKey>> ingredientSlots, ItemKey result, int resultCount)
        implements RecipeGraphSolver.RecipeInfo<String, ItemKey> {}

    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 20)
    public static void solverLoadsInGame(GameTestHelper helper) {
        ItemKey log = ItemKey.of("oak_log");
        ItemKey planks = ItemKey.of("oak_planks");
        ItemKey stick = ItemKey.of("stick");
        Recipe planksFromLog = new Recipe("planks_from_log", List.of(List.of(log)), planks, 4);
        Recipe sticksFromPlanks = new Recipe("sticks_from_planks", List.of(List.of(planks), List.of(planks)), stick, 4);
        Map<ItemKey, List<Recipe>> producers = Map.of(planks, List.of(planksFromLog), stick, List.of(sticksFromPlanks));

        RecipeGraphSolver<String, ItemKey> solver =
            new RecipeGraphSolver<>(item -> producers.getOrDefault(item, List.of()), item -> null, 8, 256);
        RecipeGraphSolver.Result<String, ItemKey> result = solver.solve(sticksFromPlanks, Map.of(log, 1));

        if (!(result instanceof RecipeGraphSolver.Result.Success<String, ItemKey> success)) {
            helper.fail("solver found no plan from one log: " + result);
            return;
        }
        // One log makes four planks, two of which make four sticks.
        List<PlannedStep<String>> expected = List.of(new PlannedStep<>("planks_from_log", 1), new PlannedStep<>("sticks_from_planks", 1));
        if (!success.steps().equals(expected)) {
            helper.fail("expected plan " + expected + " but got " + success.steps());
            return;
        }
        helper.succeed();
    }
}
