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
 *
 * <p>Beside the sources it holds the {@link KnownWay known ways}: ways a player has that no {@link Source} records.
 * They are for the obtainability lint, never for the planner.
 */
public final class AcquisitionIndex {
    public static final AcquisitionIndex EMPTY = new Builder().build();

    // Sources by item, each list ordered by id. Items without a source are absent.
    private final Map<ItemKey, List<Source>> sources;
    // Ways a player has that no Source records, by item and ordered by id: recorded for the lint, never planned.
    private final Map<ItemKey, List<KnownWay>> knownWays;
    private final Set<ItemKey> items;
    private final Set<ItemKey> craftable;
    private final Set<String> sourceBlocks;
    private final Map<String, Integer> unplannableRecipeTypes;
    private final List<String> notes;

    private AcquisitionIndex(Map<ItemKey, List<Source>> sources, Map<ItemKey, List<KnownWay>> knownWays,
                             Set<ItemKey> items, Set<ItemKey> craftable, Set<String> sourceBlocks,
                             Map<String, Integer> unplannableRecipeTypes, List<String> notes) {
        this.sources = sources;
        this.knownWays = knownWays;
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

    /**
     * Every way a player has of obtaining the item that no {@link Source} records, ordered deterministically by id;
     * empty when there is none. The planner does not read these; the obtainability lint does, to tell an item that is
     * only unplanned from one that is unobtainable.
     */
    public List<KnownWay> knownWays(ItemKey item) {
        return knownWays.getOrDefault(item, List.of());
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
     * The ids of the blocks some mining, harvesting or use-on source names (a use-on source's fluid or block target; a
     * lava source is the block {@code minecraft:lava}): what a body has to look for in the world to know which of its
     * ways to gather are at hand (the snapshot's sight, design 3.4). Sorted; the same instance on every call.
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
        private final Map<ItemKey, List<KnownWay>> knownWays = new HashMap<>();
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

        public Builder addKnownWay(KnownWay way) {
            knownWays.computeIfAbsent(way.item(), k -> new ArrayList<>()).add(way);
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
                    // A use-on source's fluid or block target stands in the world like a mined block does (a lava
                    // source is the block minecraft:lava), so the sight looks for it the same way. A mob is nowhere
                    // to be seen.
                    else if (source instanceof Source.UseOn use && !Source.UseOn.ENTITY.equals(use.targetKind())) {
                        blocks.add(use.target());
                    }
                }
            }
            // Ways are ordered the same way (the way and the from text break a tie).
            Comparator<KnownWay> wayOrder = Comparator.comparing(KnownWay::id)
                .thenComparing(KnownWay::way)
                .thenComparing(KnownWay::from);
            Map<ItemKey, List<KnownWay>> frozenWays = new HashMap<>();
            for (Map.Entry<ItemKey, List<KnownWay>> e : knownWays.entrySet()) {
                List<KnownWay> list = new ArrayList<>(e.getValue());
                list.sort(wayOrder);
                frozenWays.put(e.getKey(), List.copyOf(list));
            }
            return new AcquisitionIndex(
                Collections.unmodifiableMap(frozen),
                Collections.unmodifiableMap(frozenWays),
                Collections.unmodifiableSet(new TreeSet<>(items)),
                Collections.unmodifiableSet(new TreeSet<>(craftable)),
                Collections.unmodifiableSet(blocks),
                Collections.unmodifiableMap(new TreeMap<>(unplannable)),
                List.copyOf(notes));
        }
    }
}
