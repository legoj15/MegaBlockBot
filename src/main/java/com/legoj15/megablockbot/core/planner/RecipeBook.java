package com.legoj15.megablockbot.core.planner;

import com.legoj15.megablockbot.core.ItemKey;
import java.util.List;
import java.util.Optional;

/**
 * Every recipe the bot may plan with (design 4.2), immutable and safe to read from the planner's thread. Built on
 * the game side at server start and on every data reload.
 */
public interface RecipeBook {
    /** The recipes that make the item, in a stable order; empty when nothing makes it. */
    List<RecipeDef> producers(ItemKey item);

    Optional<RecipeDef> byId(String id);

    /** What using one of the item up in a recipe leaves behind (an empty bucket for a water bucket), or null. */
    ItemKey remainder(ItemKey item);

    /** Every recipe, in a stable order. */
    List<RecipeDef> all();
}
