package com.legoj15.megablockbot.core.planner;

import com.legoj15.megablockbot.core.ItemKey;
import com.legoj15.megablockbot.core.ToolClass;
import com.legoj15.megablockbot.core.ToolTier;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The planner's numbers and tables (brief P12): abstract gather costs (think ticks), the replan budget a plan
 * carries, and the tool-tier-to-item table. Immutable; one instance is shared by every plan call.
 *
 * <p>Costs only need to be ordered sensibly: the cost model reorders choices (solver contract, "cost only
 * reorders"), it never decides feasibility on its own.
 *
 * @param mineBase        cost of breaking one plain block, before yield
 * @param deepOre         extra cost of reaching an ore's depth, when the source has an ore range
 * @param toolStep        cost charged once per tool tier the bot's best tool of the class is below the required one
 * @param harvest         cost of one harvest attempt, before yield
 * @param kill            cost of one kill attempt, before yield
 * @param useOn           cost of one use of a held tool on a fluid, a block or a mob, before yield — a kill's by
 *                        default, since one use is one walk and one click the way one kill is one fight
 * @param fish            cost of one cast, before yield
 * @param trade           cost of one villager trade attempt: divided by the offer's chance, and added to the
 *                        unit cost of what the trade charges (a trade gives one item, so there is no yield)
 * @param sightStep       cost per block of horizontal distance charged to a mining or harvesting source whose block
 *                        is in the snapshot's sight (design 4.1): a nearer block is cheaper than a farther one
 * @param unseenPenalty   cost added to a mining or harvesting source whose block is not in the sight, once the sight
 *                        holds anything. Larger than any seen block's charge, which {@link #sightCharge} caps at
 *                        {@code unseenPenalty - 1}, so a block in sight is always cheaper than the same one out of it
 * @param maxTotalReplans the replan total a plan carries when no strategy declares a higher one (design 3.4)
 * @param provisionBelowY the level a plan is said to go underground below it (brief K19): a plan with a mining step
 *                        whose ore's best level is below it is a plan that digs in the dark, where a body needs
 *                        something to fight with and nothing on the surface to eat. 0 is the top of the deepslate:
 *                        gold, redstone, lapis and diamond are found most often below it, in the widest and darkest
 *                        caves, while iron, copper and coal (best levels 16 and up) are not, so the short plans of
 *                        the iron age stay as they were and do not go hunting for eight meals before three ingots.
 *                        (The brief said 40; the first live plans it changed were those.)
 *                        {@link Integer#MIN_VALUE} switches provisioning off.
 * @param provisionWeapons
 *                        what a plan that goes underground brings as a weapon, best first: the first of them the plan can
 *                        make, or find where it may take from, is the one it brings. None worse than stone, since a
 *                        wooden sword does not outlast the fight it is meant for.
 * @param provisionFoodCount
 *                        how much food a plan that goes underground sees the pack holding; the units it is short of
 *                        that are the count it plans to bring.
 * @param provisionFoods  what it may bring as food, in the order it prefers them: the cheapest by the cost model is
 *                        the one it brings, so the order only decides a tie and says what counts as food to a plan.
 * @param toolItems       tool item per class and tier, keyed by {@link #toolKey}; see {@link #toolItem}
 */
public record PlannerConfig(long mineBase, long deepOre, long toolStep, long harvest, long kill, long useOn, long fish,
                            long trade, long sightStep, long unseenPenalty, int maxTotalReplans, int provisionBelowY,
                            List<ItemKey> provisionWeapons, int provisionFoodCount, List<ItemKey> provisionFoods,
                            Map<String, ItemKey> toolItems) {
    /**
     * Sorted, defensively copied, read-only: table iteration must not depend on how it was built. ({@code Map.copyOf}
     * would drop the order, and its own iteration order differs from one JVM to the next.) The provision lists keep
     * the order they were given, which is their preference order.
     */
    public PlannerConfig {
        if (sightStep < 0 || unseenPenalty < 1) {
            throw new IllegalArgumentException("sightStep must be at least 0 and unseenPenalty at least 1, got "
                    + sightStep + " and " + unseenPenalty);
        }
        if (provisionFoodCount < 0) {
            throw new IllegalArgumentException("provisionFoodCount must be at least 0, got " + provisionFoodCount);
        }
        provisionWeapons = List.copyOf(provisionWeapons);
        provisionFoods = List.copyOf(provisionFoods);
        toolItems = Collections.unmodifiableMap(new TreeMap<>(toolItems));
    }

    /**
     * The documented defaults of the table above: vanilla costs, the executor's replan budget. A block in sight
     * costs one per block of distance, at most 112 away with the collector's radius of 4 chunks; a block out of
     * sight costs 250 more, over twice that, and still below a tool step (400) so that what the body can see
     * reorders choices between alternatives without outweighing a missing tool.
     */
    public static PlannerConfig defaults() {
        return new PlannerConfig(40, 600, 400, 200, 600, 600, 1200, 3000, 1, 250, 12, 0, vanillaProvisionWeapons(), 8,
                vanillaProvisionFoods(), vanillaToolItems());
    }

    /**
     * What a mining or harvesting source whose block is {@code distance} blocks away (horizontally) costs on top of
     * its breaking cost: {@code sightStep} per block, never reaching {@code unseenPenalty}.
     */
    public long sightCharge(long distance) {
        long cap = unseenPenalty - 1;
        if (sightStep == 0) return 0;
        long blocks = Math.max(0, distance);
        return blocks > cap / sightStep ? cap : blocks * sightStep;
    }

    /** The vanilla tool ids. Shears are one item at every tier, so they sit under one key. */
    public static Map<String, ItemKey> vanillaToolItems() {
        Map<String, ItemKey> table = new TreeMap<>();
        String[] tiers = {"wooden", "stone", "iron", "diamond", "netherite"};
        ToolTier[] tierValues = {ToolTier.WOOD, ToolTier.STONE, ToolTier.IRON, ToolTier.DIAMOND, ToolTier.NETHERITE};
        String[] families = {"pickaxe", "axe", "shovel", "hoe", "sword"};
        ToolClass[] classes = {ToolClass.PICKAXE, ToolClass.AXE, ToolClass.SHOVEL, ToolClass.HOE, ToolClass.SWORD};
        for (int t = 0; t < tierValues.length; t++) {
            for (int c = 0; c < classes.length; c++) {
                table.put(toolKey(classes[c], tierValues[t]), ItemKey.of(tiers[t] + "_" + families[c]));
            }
        }
        table.put(toolKey(ToolClass.SHEARS, ToolTier.HAND), ItemKey.of("shears"));
        return table;
    }

    /** The tool table's key. Shears are tier-less ("at any tier"), so their key carries {@link ToolTier#HAND}. */
    public static String toolKey(ToolClass toolClass, ToolTier tier) {
        return toolClass.name() + ":" + (toolClass == ToolClass.SHEARS ? ToolTier.HAND : tier).name();
    }

    /**
     * The item that is the tool of that class at that tier, or null when the table has no entry (an unmapped modded
     * class or tier is unplannable and reported as {@code MissingTool}, not guessed at).
     */
    public ItemKey toolItem(ToolClass toolClass, ToolTier tier) {
        return toolItems.get(toolKey(toolClass, tier));
    }

    /** The vanilla swords a plan that goes underground may bring, best first (brief K19). */
    public static List<ItemKey> vanillaProvisionWeapons() {
        return List.of(ItemKey.of("iron_sword"), ItemKey.of("stone_sword"));
    }

    /** The vanilla foods a plan that goes underground may bring, in the order the design prefers them. */
    public static List<ItemKey> vanillaProvisionFoods() {
        return List.of(ItemKey.of("cooked_beef"), ItemKey.of("cooked_porkchop"), ItemKey.of("cooked_mutton"),
                ItemKey.of("cooked_chicken"), ItemKey.of("bread"), ItemKey.of("baked_potato"),
                ItemKey.of("cooked_cod"), ItemKey.of("cooked_salmon"));
    }

    /** A copy of this config whose tool table is replaced, for a modded or test table. */
    public PlannerConfig withToolItems(Map<String, ItemKey> replacement) {
        return new PlannerConfig(mineBase, deepOre, toolStep, harvest, kill, useOn, fish, trade, sightStep,
                unseenPenalty, maxTotalReplans, provisionBelowY, provisionWeapons, provisionFoodCount, provisionFoods,
                replacement);
    }
}
