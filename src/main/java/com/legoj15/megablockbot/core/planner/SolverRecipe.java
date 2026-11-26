package com.legoj15.megablockbot.core.planner;

import com.legoj15.megablockbot.core.ItemKey;
import com.legoj15.solver.RecipeGraphSolver;
import java.util.List;

/**
 * A {@link RecipeDef} as the solver sees it: {@code RecipeInfo<String, ItemKey>} with the recipe id as the
 * solver's recipe type (brief P12, "adapt RecipeDef to the solver's RecipeInfo inside this package"). The
 * station is the def's station item, present while the step runs and never consumed (solver 2.2.0 contract);
 * a slot's {@code count} is the solver's per-slot quantity, so {@code ingredientSlots()} stays one list per
 * slot and the solver expands the copies itself.
 */
record SolverRecipe(RecipeDef def) implements RecipeGraphSolver.RecipeInfo<String, ItemKey> {
    @Override
    public String id() {
        return def.id();
    }

    @Override
    public List<List<ItemKey>> ingredientSlots() {
        return def.slots().stream().map(RecipeDef.Slot::alternatives).toList();
    }

    @Override
    public int ingredientSlotCount(int slot) {
        return def.slots().get(slot).count();
    }

    @Override
    public ItemKey station() {
        return def.station();
    }

    @Override
    public ItemKey result() {
        return def.result();
    }

    @Override
    public int resultCount() {
        return def.resultCount();
    }

    static SolverRecipe of(RecipeDef def) {
        return new SolverRecipe(def);
    }
}
