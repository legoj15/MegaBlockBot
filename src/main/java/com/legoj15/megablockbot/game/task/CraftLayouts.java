package com.legoj15.megablockbot.game.task;

import com.legoj15.megablockbot.core.ItemKey;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the {@code layout} param of a craft leaf: the item the planner chose for each ingredient slot of each craft.
 * The solver decides which alternative fills a slot so that one craft never eats the only copy a later craft counted
 * on, and a task that follows the layout never re-decides.
 *
 * <p>Form: runs separated by {@code ;}. A run is {@code crafts*item,item,...}: the next {@code crafts} crafts put the
 * listed items into the recipe's ingredient slots, one item per slot in recipe slot order (a slot that consumes
 * several is listed that many times). The runs' crafts add up to the leaf's {@code times}. Item ids contain none of
 * {@code ; * ,}, so there is no escaping. Example, two torch crafts, coal for the first and charcoal for the second:
 * {@code 1*minecraft:coal,minecraft:stick;1*minecraft:charcoal,minecraft:stick}.
 */
final class CraftLayouts {
    private CraftLayouts() {}

    /**
     * One entry per craft, each the items for the recipe's slots in slot order. Empty for a blank layout (the planner
     * gave none).
     *
     * @throws IllegalArgumentException when the text is not in the form above
     */
    static List<List<ItemKey>> parse(String text) {
        List<List<ItemKey>> crafts = new ArrayList<>();
        if (text == null || text.isBlank()) return crafts;
        for (String run : text.split(";", -1)) {
            int star = run.indexOf('*');
            if (star < 1) throw new IllegalArgumentException("a layout run is 'crafts*item,item': " + run);
            long count;
            try {
                count = Long.parseLong(run.substring(0, star));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("a layout run starts with its craft count: " + run, e);
            }
            if (count < 1 || count > 100_000) throw new IllegalArgumentException("a layout run covers " + count + " crafts: " + run);
            List<ItemKey> items = new ArrayList<>();
            String list = run.substring(star + 1);
            if (!list.isEmpty()) {
                for (String id : list.split(",", -1)) {
                    if (id.isBlank()) throw new IllegalArgumentException("a layout run names an empty item: " + run);
                    items.add(ItemKey.of(id.strip()));
                }
            }
            for (long i = 0; i < count; i++) crafts.add(items);
        }
        return crafts;
    }
}
