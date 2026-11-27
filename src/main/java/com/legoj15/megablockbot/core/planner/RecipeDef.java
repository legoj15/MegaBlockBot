package com.legoj15.megablockbot.core.planner;

import com.legoj15.megablockbot.core.ItemKey;
import java.util.List;
import java.util.Objects;

/**
 * One recipe the bot knows how to carry out, with no Minecraft type in it (design 4.2). A recipe source on the game
 * side turns one recipe family into these; the planner hands them to the solver.
 *
 * @param id          unique across the book, stable across restarts (the game recipe's id)
 * @param kind        the task that runs it: {@code "craft"} for the crafting grid; other kinds pass through the
 *                    planner untouched and name the task that carries them out
 * @param slots       what one run consumes; never empty
 * @param station     the item that must be in stock while the recipe runs and is not used up
 *                    ({@code minecraft:crafting_table}), or null when it needs none (it fits the inventory's 2x2 grid)
 * @param result      what one run makes
 * @param resultCount how many, at least 1
 */
public record RecipeDef(String id, String kind, List<Slot> slots, ItemKey station, ItemKey result, int resultCount) {
    /** The kind of a recipe run in a crafting grid, the inventory's or a table's. */
    public static final String CRAFT = "craft";

    /** The kind of a recipe run in a furnace, a blast furnace or a smoker, with its fuel beside the ingredient. */
    public static final String SMELT = "smelt";

    /** The kind of a recipe run at a stonecutter: one ingredient in, the recipe chosen from the list the menu offers. */
    public static final String STONECUT = "stonecut";

    /** The kind of a recipe run at a smithing table: a template, a base and an addition in, the result out. */
    public static final String SMITH = "smith";

    public RecipeDef {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(result, "result");
        slots = List.copyOf(slots);
        if (slots.isEmpty()) throw new IllegalArgumentException("recipe " + id + " consumes nothing");
        if (resultCount < 1) throw new IllegalArgumentException("recipe " + id + " makes " + resultCount);
    }

    /**
     * One ingredient position.
     *
     * @param alternatives the items that can fill it, in the source's order; never empty
     * @param count        how many of one of them a run consumes, at least 1
     */
    public record Slot(List<ItemKey> alternatives, int count) {
        public Slot {
            alternatives = List.copyOf(alternatives);
            if (alternatives.isEmpty()) throw new IllegalArgumentException("a slot needs at least one alternative");
            if (count < 1) throw new IllegalArgumentException("a slot consumes at least one item, not " + count);
        }

        public static Slot of(ItemKey item) {
            return new Slot(List.of(item), 1);
        }
    }
}
