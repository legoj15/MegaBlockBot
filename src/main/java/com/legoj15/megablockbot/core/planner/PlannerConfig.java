package com.legoj15.megablockbot.core.planner;

import com.legoj15.megablockbot.core.ItemKey;
import com.legoj15.megablockbot.core.ToolClass;
import com.legoj15.megablockbot.core.ToolTier;
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
 * @param fish            cost of one cast, before yield
 * @param trade           cost of one villager trade (a trade has no yield to divide by)
 * @param maxTotalReplans the replan total a plan carries when no strategy declares a higher one (design 3.4)
 * @param toolItems       tool item per class and tier, keyed by {@link #toolKey}; see {@link #toolItem}
 */
public record PlannerConfig(long mineBase, long deepOre, long toolStep, long harvest, long kill, long fish,
                            long trade, int maxTotalReplans, Map<String, ItemKey> toolItems) {
    /** Sorted, defensively copied: table iteration must not depend on how it was built. */
    public PlannerConfig {
        toolItems = Map.copyOf(new TreeMap<>(toolItems));
    }

    /** The documented defaults of the table above: vanilla costs, the executor's replan budget. */
    public static PlannerConfig defaults() {
        return new PlannerConfig(40, 600, 400, 200, 600, 1200, 3000, 12, vanillaToolItems());
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

    /** A copy of this config whose tool table is replaced, for a modded or test table. */
    public PlannerConfig withToolItems(Map<String, ItemKey> replacement) {
        return new PlannerConfig(mineBase, deepOre, toolStep, harvest, kill, fish, trade, maxTotalReplans, replacement);
    }
}
