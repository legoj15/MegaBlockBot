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
     * @param ore       where the block generates as an ore, or null when no ore placed feature produces it
     * @param generated true when the world generator places the block (a feature or the terrain names it); false for
     *                  one that stands only in a structure or where someone placed it
     */
    record MineBlock(String id, ItemKey item, String block, ToolClass toolClass, ToolTier minTier, boolean toolRequired,
                     boolean needsSilkTouch, boolean needsShears, double yield, boolean random, OreRange ore,
                     boolean generated)
        implements Source {
        public MineBlock {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(item, "item");
            Objects.requireNonNull(block, "block");
            Objects.requireNonNull(minTier, "minTier");
        }

        /** A block the world generates: what a fixture that is not about where blocks come from needs. */
        public MineBlock(String id, ItemKey item, String block, ToolClass toolClass, ToolTier minTier, boolean toolRequired,
                         boolean needsSilkTouch, boolean needsShears, double yield, boolean random, OreRange ore) {
            this(id, item, block, toolClass, minTier, toolRequired, needsSilkTouch, needsShears, yield, random, ore, true);
        }
    }

    /**
     * The Y levels an ore block generates at: inclusive bounds covering every feature that generates it, and the level
     * a branch miner should aim for, which is the peak of the best feature that peaks at or below the sea level (the
     * lowest peak when none does), never that of a mountain-only feature while a lower one exists.
     */
    record OreRange(int minY, int maxY, int bestY) {}

    /**
     * Killing a mob. {@code playerKillOnly} is true when the drop needs a kill by a player.
     *
     * <p>The fight is part of the price, so the source carries what the game says of the mob: {@code health} is its
     * full health and {@code attack} the damage of its blow (0 for one that does not fight back), both the type's own
     * defaults. {@code spawnsNaturally} is false for a kind the world does not spawn on its own account (an iron
     * golem, a villager): one stands only where a village or a player made it, and finding one is not planned.
     * {@code aquatic} is true for a kind that lives in water (a fish, a squid, a dolphin, an axolotl).
     */
    record KillMob(String id, ItemKey item, String entity, boolean playerKillOnly, double yield, boolean random,
                   double health, double attack, boolean spawnsNaturally, boolean aquatic)
        implements Source {
        /** The health of the mob the planner's base price of a kill is for: a cow's. */
        public static final double BASE_HEALTH = 10.0;

        public KillMob {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(item, "item");
            Objects.requireNonNull(entity, "entity");
        }

        /** A harmless mob of a cow's health that spawns on its own: what a fixture that is not about the fight needs. */
        public KillMob(String id, ItemKey item, String entity, boolean playerKillOnly, double yield, boolean random) {
            this(id, item, entity, playerKillOnly, yield, random, BASE_HEALTH, 0.0, true, false);
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
     *
     * <p>{@code jobSite} is the item of the block that gives a villager this profession (a lectern for a librarian), or
     * null when the profession names no job site (the wandering trader) or its block has no item. It is what a plan
     * holds to make one of these villagers where none stands: put it beside an unemployed villager and the villager
     * takes the profession.
     */
    record Trade(String id, ItemKey item, String profession, int level, Map<ItemKey, Integer> cost,
                 Map<String, Integer> enchantments, double chance, ItemKey jobSite) implements Source {
        public Trade {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(item, "item");
            Objects.requireNonNull(profession, "profession");
            cost = java.util.Collections.unmodifiableMap(new TreeMap<>(cost));
            enchantments = java.util.Collections.unmodifiableMap(new TreeMap<>(enchantments));
        }

        /** The form without a job site: a wandering trader's trade, or a fixture that is not about rerolling. */
        public Trade(String id, ItemKey item, String profession, int level, Map<ItemKey, Integer> cost,
                     Map<String, Integer> enchantments, double chance) {
            this(id, item, profession, level, cost, enchantments, chance, null);
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

    /**
     * Holding a tool and using it on a fluid, a block or a mob (issue #28): a bucket scooped into a lava source, a
     * water bucket closed on a fish, an empty bucket on a cow, shears on a sheep. {@code tool} is what is held and
     * used; {@code toolSpent} is true when the tool becomes the result (a bucket: one tool per unit of the item) and
     * false when it is kept for the next use (shears, which only lose durability). {@code targetKind} is
     * {@link #FLUID}, {@link #BLOCK} or {@link #ENTITY}, {@code target} the registry id of the fluid, the block or the
     * entity type, and {@code variant} a further name of the target (a sheep's dye colour) or null.
     *
     * <p>One use is one attempt. A kept tool's result may be dropped beside the target and walked to, so the yield is
     * what the game drops on the average; a spent tool's result lands in the hand.
     */
    record UseOn(String id, ItemKey item, ItemKey tool, boolean toolSpent, String targetKind, String target,
                 String variant, double yield, boolean random) implements Source {
        /** The target is a fluid source the tool is dipped into. */
        public static final String FLUID = "fluid";
        /** The target is a block the tool is used on. */
        public static final String BLOCK = "block";
        /** The target is a living entity the tool is used on. */
        public static final String ENTITY = "entity";

        public UseOn {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(item, "item");
            Objects.requireNonNull(tool, "tool");
            Objects.requireNonNull(targetKind, "targetKind");
            Objects.requireNonNull(target, "target");
        }
    }
}
