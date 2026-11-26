package com.legoj15.megablockbot.core.snapshot;

import com.legoj15.megablockbot.core.EquipSlot;
import com.legoj15.megablockbot.core.ItemKey;
import com.legoj15.megablockbot.core.Pos;
import com.legoj15.megablockbot.core.ToolClass;
import com.legoj15.megablockbot.core.ToolTier;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What the body knows at one instant (design 3.4, Snapshot). Immutable and Minecraft-free. Collected on the
 * server thread; collecting it never changes state. Postconditions and the planner read nothing else.
 *
 * @param items       inventory, armour and offhand counts by item key
 * @param equipped    what is worn or held right now
 * @param bestTools   best tier carried per tool class; a missing class means none ({@link ToolTier#HAND})
 * @param foodUnits   hunger points the carried food would restore
 * @param stations    stations (crafting table, furnace, modded machines) within the snapshot radius
 * @param containers  containers within the snapshot radius, or named by the active goal
 * @param stackFacts  per-stack component facts, only for the item keys the active goal's check names
 * @param structures  one report per blueprint a build goal names, keyed by {@code blueprint@x,y,z}
 * @param observations extra facts a strategy declared it needs, keyed by the strategy's own key
 */
public record Snapshot(
        long gameTime,
        String dimension,
        Pos pos,
        float health,
        int hunger,
        int foodUnits,
        Map<ItemKey, Integer> items,
        Map<EquipSlot, ItemKey> equipped,
        Map<ToolClass, ToolTier> bestTools,
        List<StationSight> stations,
        List<ContainerSight> containers,
        Map<ItemKey, List<StackFacts>> stackFacts,
        Map<String, StructureReport> structures,
        Map<String, Observation> observations) {

    public Snapshot {
        items = Map.copyOf(items);
        equipped = Map.copyOf(equipped);
        bestTools = Map.copyOf(bestTools);
        stations = List.copyOf(stations);
        containers = List.copyOf(containers);
        stackFacts = Map.copyOf(stackFacts);
        structures = Map.copyOf(structures);
        observations = Map.copyOf(observations);
    }

    public int count(ItemKey key) {
        return items.getOrDefault(key, 0);
    }

    public ToolTier bestTool(ToolClass toolClass) {
        return bestTools.getOrDefault(toolClass, ToolTier.HAND);
    }

    /** The container at that position, or null when the snapshot holds nothing about it. */
    public ContainerSight containerAt(Pos at) {
        for (ContainerSight c : containers) {
            if (c.pos().equals(at)) return c;
        }
        return null;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Mutable builder; the collector and the tests both use it. */
    public static final class Builder {
        private long gameTime;
        private String dimension = "minecraft:overworld";
        private Pos pos = new Pos(0, 64, 0);
        private float health = 20f;
        private int hunger = 20;
        private int foodUnits;
        private final Map<ItemKey, Integer> items = new HashMap<>();
        private final Map<EquipSlot, ItemKey> equipped = new EnumMap<>(EquipSlot.class);
        private final Map<ToolClass, ToolTier> bestTools = new EnumMap<>(ToolClass.class);
        private final List<StationSight> stations = new ArrayList<>();
        private final List<ContainerSight> containers = new ArrayList<>();
        private final Map<ItemKey, List<StackFacts>> stackFacts = new HashMap<>();
        private final Map<String, StructureReport> structures = new HashMap<>();
        private final Map<String, Observation> observations = new HashMap<>();

        public Builder gameTime(long t) { gameTime = t; return this; }
        public Builder dimension(String d) { dimension = d; return this; }
        public Builder pos(Pos p) { pos = p; return this; }
        public Builder health(float h) { health = h; return this; }
        public Builder hunger(int h) { hunger = h; return this; }
        public Builder foodUnits(int f) { foodUnits = f; return this; }

        /** Adds to the count already recorded for that key. */
        public Builder item(ItemKey key, int count) { items.merge(key, count, Integer::sum); return this; }
        public Builder equip(EquipSlot slot, ItemKey key) { equipped.put(slot, key); return this; }

        /** Keeps the better tier when called twice for one class. */
        public Builder tool(ToolClass c, ToolTier t) { bestTools.merge(c, t, ToolTier::max); return this; }
        public Builder station(StationSight s) { stations.add(s); return this; }
        public Builder container(ContainerSight c) { containers.add(c); return this; }
        public Builder facts(ItemKey key, StackFacts f) {
            stackFacts.computeIfAbsent(key, k -> new ArrayList<>()).add(f);
            return this;
        }
        public Builder structure(String key, StructureReport r) { structures.put(key, r); return this; }
        public Builder observation(String key, Observation o) { observations.put(key, o); return this; }

        public Snapshot build() {
            Map<ItemKey, List<StackFacts>> facts = new HashMap<>();
            stackFacts.forEach((k, v) -> facts.put(k, List.copyOf(v)));
            return new Snapshot(gameTime, dimension, pos, health, hunger, foodUnits, items, equipped, bestTools,
                    stations, containers, facts, structures, observations);
        }
    }
}
