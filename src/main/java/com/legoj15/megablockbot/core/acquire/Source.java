package com.legoj15.megablockbot.core.acquire;

import com.legoj15.megablockbot.core.ItemKey;
import com.legoj15.megablockbot.core.ToolClass;
import com.legoj15.megablockbot.core.ToolTier;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * One way a survival player can obtain an item (design 4.3). Immutable and Minecraft-free: the planner reads these
 * off the server thread.
 *
 * <p>{@link #id()} is stable across restarts (it is built from registry ids, never from iteration order) and unique
 * within one item's source list; the same block or mob yields several items, so {@code (item(), id())} is the key
 * that is unique across the whole index. A route that needs a special tool carries a suffix
 * ({@code mine:minecraft:stone#silk_touch}), so a block's plain drop and its silk-touch drop never share an id.
 */
public sealed interface Source {
    /** Stable id such as {@code mine:minecraft:diamond_ore}, {@code kill:minecraft:zombie}, {@code fish:minecraft:gameplay/fishing}. */
    String id();

    /** What it yields. */
    ItemKey item();

    /** Expected units per attempt: one block broken, one kill, one cast, one trade, one chest opened. */
    double yield();

    /** True when a single attempt may yield nothing. */
    boolean random();

    /**
     * Breaking a block. {@code toolRequired} false means the block drops the item with any tool or a bare hand;
     * {@code toolClass} and {@code minTier} then still say what mines it fastest ({@code toolClass} may be null, and a
     * block no tool speeds up has tier {@link ToolTier#HAND}). A route that needs shears reports {@code toolClass}
     * SHEARS and tier HAND.
     *
     * @param ore where the block generates as an ore, or null when no ore placed feature produces it
     */
    record MineBlock(String id, ItemKey item, String block, ToolClass toolClass, ToolTier minTier, boolean toolRequired,
                     boolean needsSilkTouch, boolean needsShears, double yield, boolean random, OreRange ore)
        implements Source {
        public MineBlock {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(item, "item");
            Objects.requireNonNull(block, "block");
            Objects.requireNonNull(minTier, "minTier");
        }
    }

    /** The Y levels an ore block generates at: inclusive bounds, and the level a branch miner should aim for. */
    record OreRange(int minY, int maxY, int bestY) {}

    /** Killing a mob. {@code playerKillOnly} is true when the drop needs a kill by a player. */
    record KillMob(String id, ItemKey item, String entity, boolean playerKillOnly, double yield, boolean random)
        implements Source {
        public KillMob {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(item, "item");
            Objects.requireNonNull(entity, "entity");
        }
    }

    /** Casting a fishing rod. {@code yield} is the expected items of this kind per cast (the per-cast chance). */
    record Fishing(String id, ItemKey item, String table, double yield) implements Source {
        public Fishing {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(item, "item");
            Objects.requireNonNull(table, "table");
        }

        @Override
        public boolean random() {
            return true;
        }
    }

    /**
     * A villager trade, measured by sampling the profession's offer listings. {@code chance} is the share of samples of
     * the listing that produced this result; it ignores which of a level's listings a given villager rolled.
     * {@code enchantments} is the stored enchantments of an enchanted book (empty for everything else).
     */
    record Trade(String id, ItemKey item, String profession, int level, Map<ItemKey, Integer> cost,
                 Map<String, Integer> enchantments, double chance) implements Source {
        public Trade {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(item, "item");
            Objects.requireNonNull(profession, "profession");
            cost = java.util.Collections.unmodifiableMap(new TreeMap<>(cost));
            enchantments = java.util.Collections.unmodifiableMap(new TreeMap<>(enchantments));
        }

        @Override
        public double yield() {
            return 1.0;
        }

        @Override
        public boolean random() {
            return chance < 1.0;
        }
    }

    /** Opening a generated chest of the given loot table. */
    record ChestLoot(String id, ItemKey item, String table, double yield) implements Source {
        public ChestLoot {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(item, "item");
            Objects.requireNonNull(table, "table");
        }

        @Override
        public boolean random() {
            return true;
        }
    }

    /**
     * Harvesting a growable (crop, berry bush, cane, ...). {@code plant} is the item that is planted to grow it, or
     * null when none could be found.
     */
    record Harvest(String id, ItemKey item, String block, ItemKey plant, boolean renewable, double yield, boolean random)
        implements Source {
        public Harvest {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(item, "item");
            Objects.requireNonNull(block, "block");
        }
    }
}
