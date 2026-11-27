package com.legoj15.megablockbot.core.acquire;

import com.legoj15.megablockbot.core.ItemKey;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The reverse index "item to how a survival player can obtain it" (design 4.3). Immutable, so the planner may read it
 * from any thread while the server thread builds the next one after a reload; the holder swaps the reference.
 */
public final class AcquisitionIndex {
    public static final AcquisitionIndex EMPTY = new Builder().build();

    // Sources by item, each list ordered by id. Items without a source are absent.
    private final Map<ItemKey, List<Source>> sources;
    private final Set<ItemKey> items;
    private final Set<ItemKey> craftable;
    private final Set<String> sourceBlocks;
    private final Map<String, Integer> unplannableRecipeTypes;
    private final List<String> notes;

    private AcquisitionIndex(Map<ItemKey, List<Source>> sources, Set<ItemKey> items, Set<ItemKey> craftable,
                             Set<String> sourceBlocks, Map<String, Integer> unplannableRecipeTypes, List<String> notes) {
        this.sources = sources;
        this.items = items;
        this.craftable = craftable;
        this.sourceBlocks = sourceBlocks;
        this.unplannableRecipeTypes = unplannableRecipeTypes;
        this.notes = notes;
    }

    /** Every way to obtain the item, ordered deterministically by id; empty when there is none. */
    public List<Source> sources(ItemKey item) {
        return sources.getOrDefault(item, List.of());
    }

    /** Every item the server has enabled: the universe the lint judges. Sorted by id. */
    public Set<ItemKey> items() {
        return items;
    }

    /** The items that have at least one source. Sorted by id. */
    public Set<ItemKey> sourced() {
        return Collections.unmodifiableSet(new TreeSet<>(sources.keySet()));
    }

    /** The items some loaded recipe produces. Sorted by id. */
    public Set<ItemKey> craftable() {
        return craftable;
    }

    /**
     * The ids of the blocks some mining or harvesting source names: what a body has to look for in the world to know
     * which of its ways to gather are at hand (the snapshot's sight, design 3.4). Sorted; the same instance on every call.
     */
    public Set<String> sourceBlocks() {
        return sourceBlocks;
    }

    /** Recipe type id to recipe count, for the types no producer adapter reads yet. Their outputs still count as craftable. */
    public Map<String, Integer> unplannableRecipeTypes() {
        return unplannableRecipeTypes;
    }

    /** What the build could not cover, one line each, for the lint output. */
    public List<String> notes() {
        return notes;
    }

    /** Source counts by kind (the record's simple name), for the log line. */
    public Map<String, Integer> sourceCountsByKind() {
        Map<String, Integer> counts = new TreeMap<>();
        for (List<Source> list : sources.values()) {
            for (Source source : list) counts.merge(source.getClass().getSimpleName(), 1, Integer::sum);
        }
        return counts;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final Map<ItemKey, List<Source>> sources = new HashMap<>();
        private final Set<ItemKey> items = new TreeSet<>();
        private final Set<ItemKey> craftable = new TreeSet<>();
        private final Map<String, Integer> unplannable = new TreeMap<>();
        private final Set<String> notes = new LinkedHashSet<>();

        private Builder() {}

        public Builder addItem(ItemKey item) {
            items.add(item);
            return this;
        }

        public Builder addSource(Source source) {
            sources.computeIfAbsent(source.item(), k -> new ArrayList<>()).add(source);
            return this;
        }

        public Builder addCraftable(ItemKey item) {
            craftable.add(item);
            return this;
        }

        public Builder countUnplannableRecipe(String recipeTypeId) {
            unplannable.merge(recipeTypeId, 1, Integer::sum);
            return this;
        }

        public Builder note(String line) {
            notes.add(line);
            return this;
        }

        public AcquisitionIndex build() {
            // Ordered by id; the kind and the full text break a tie so the order never depends on insertion order.
            Comparator<Source> order = Comparator.comparing(Source::id)
                .thenComparing(s -> s.getClass().getSimpleName())
                .thenComparing(Source::toString);
            Map<ItemKey, List<Source>> frozen = new HashMap<>();
            Set<String> blocks = new TreeSet<>();
            for (Map.Entry<ItemKey, List<Source>> e : sources.entrySet()) {
                List<Source> list = new ArrayList<>(e.getValue());
                list.sort(order);
                frozen.put(e.getKey(), List.copyOf(list));
                for (Source source : list) {
                    if (source instanceof Source.MineBlock mine) blocks.add(mine.block());
                    else if (source instanceof Source.Harvest harvest) blocks.add(harvest.block());
                }
            }
            return new AcquisitionIndex(
                Collections.unmodifiableMap(frozen),
                Collections.unmodifiableSet(new TreeSet<>(items)),
                Collections.unmodifiableSet(new TreeSet<>(craftable)),
                Collections.unmodifiableSet(blocks),
                Collections.unmodifiableMap(new TreeMap<>(unplannable)),
                List.copyOf(notes));
        }
    }
}
