package com.legoj15.megablockbot.core.planner;

import com.legoj15.megablockbot.core.ItemKey;
import com.legoj15.solver.SlotLayout;
import java.util.ArrayList;
import java.util.List;

/**
 * The solver's per-slot item choice for one crafting step, as the string a craft leaf carries in its
 * {@code layout} param (brief P12b, item 5; design 4.1). The solver decides which alternative fills each slot so that
 * a step never eats the only copy a later step counted on; an executor that follows this layout never re-decides.
 *
 * <p>Form: runs separated by {@code ;}. A run is {@code crafts*item,item,...}: the next {@code crafts} crafts of the
 * step put the listed items into the recipe's slots, one item per slot in recipe slot order, a slot of count q listed
 * q times (the order of the solver's {@code SlotLayout}). The runs' crafts add up to the leaf's {@code times}. Item
 * ids never contain {@code ;}, {@code *} or {@code ,}, so the form needs no escaping. Example, two torch crafts that
 * take coal first and charcoal second: {@code 1*minecraft:coal,minecraft:stick;1*minecraft:charcoal,minecraft:stick}.
 */
public final class CraftLayout {
    private CraftLayout() {}

    /** One run of identical crafts: {@code crafts} crafts that each fill the slots with {@code items}, in slot order. */
    public record Run(long crafts, List<ItemKey> items) {
        public Run {
            if (crafts < 1) throw new IllegalArgumentException("a run covers at least one craft, not " + crafts);
            items = List.copyOf(items);
        }
    }

    /** The solver's runs for one step as the param string; empty when the solver gave no layout. */
    static String encode(List<SlotLayout<ItemKey>> runs) {
        StringBuilder text = new StringBuilder();
        for (SlotLayout<ItemKey> run : runs) {
            if (text.length() > 0) text.append(';');
            text.append(run.crafts()).append('*');
            for (int i = 0; i < run.items().size(); i++) {
                if (i > 0) text.append(',');
                text.append(run.items().get(i).id());
            }
        }
        return text.toString();
    }

    /**
     * Reads a {@code layout} param back.
     *
     * @throws IllegalArgumentException when the text is not in the form above
     */
    public static List<Run> decode(String text) {
        List<Run> runs = new ArrayList<>();
        if (text == null || text.isEmpty()) return runs;
        for (String run : text.split(";", -1)) {
            int star = run.indexOf('*');
            if (star < 1) throw new IllegalArgumentException("a layout run is 'crafts*item,item': " + run);
            long crafts;
            try {
                crafts = Long.parseLong(run.substring(0, star));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("a layout run starts with its craft count: " + run, e);
            }
            List<ItemKey> items = new ArrayList<>();
            String list = run.substring(star + 1);
            if (!list.isEmpty()) {
                for (String id : list.split(",", -1)) {
                    if (id.isEmpty()) throw new IllegalArgumentException("a layout run names an empty item: " + run);
                    items.add(new ItemKey(id));
                }
            }
            runs.add(new Run(crafts, items));
        }
        return runs;
    }
}
