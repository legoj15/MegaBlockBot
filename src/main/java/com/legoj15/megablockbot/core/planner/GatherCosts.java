package com.legoj15.megablockbot.core.planner;

import com.legoj15.megablockbot.core.ItemKey;
import com.legoj15.megablockbot.core.ToolTier;
import com.legoj15.megablockbot.core.acquire.AcquisitionIndex;
import com.legoj15.megablockbot.core.acquire.Source;
import com.legoj15.megablockbot.core.plan.Exclusions;
import com.legoj15.megablockbot.core.snapshot.Snapshot;
import com.legoj15.solver.CostModel;
import java.util.List;

/**
 * The solver's cost model, fed by the acquisition index (brief P12; design 4.1 "How the cost model works", 4.3).
 * {@link #gatherCost(ItemKey)} is the cheapest usable source's cost per unit, or {@code UNOBTAINABLE} when no
 * source is usable; {@link #best(ItemKey)} returns that source. A per-request object: exclusions and the bot's
 * best tools come from one request, so the planner builds one per plan call and shares it with its solver.
 *
 * <p>Costs are abstract (think ticks). The attempt cost of a source divided by its {@code yield} is the per-unit
 * cost; a source whose attempts may come up empty expresses that in its {@code yield} already, so chance is not
 * divided out a second time. A trade is one attempt for one item and is not divided.
 *
 * <p>Not planned yet, so a source needing them is unusable here and the plan says so in its notes: silk touch,
 * shears (a drop that needs shears), and chest loot (finding structures).
 */
public final class GatherCosts implements CostModel<String, ItemKey> {
    private final AcquisitionIndex index;
    private final PlannerConfig config;
    private final Exclusions exclusions;
    private final Snapshot snapshot;

    public GatherCosts(AcquisitionIndex index, PlannerConfig config, Exclusions exclusions, Snapshot snapshot) {
        this.index = index;
        this.config = config;
        this.exclusions = exclusions;
        this.snapshot = snapshot;
    }

    /** Cheapest usable source, ties on source id so a plan is deterministic; null when none is usable. */
    public Source best(ItemKey item) {
        List<Source> sources = index.sources(item);
        Source best = null;
        long bestCost = 0;
        for (Source source : sources) {
            if (!usable(source)) continue;
            long cost = costOf(source);
            // Sources arrive ordered by id, so a strict keep keeps the lowest id on a tie.
            if (best == null || cost < bestCost) {
                best = source;
                bestCost = cost;
            }
        }
        return best;
    }

    /** {@link #best(ItemKey)}'s cost, or {@code UNOBTAINABLE} when the item has no usable source. */
    public long bestCost(ItemKey item) {
        Source best = best(item);
        return best == null ? UNOBTAINABLE : costOf(best);
    }

    /** Whether this request may use the source at all: not excluded, and of a kind the planner can task. */
    public boolean usable(Source source) {
        if (exclusions.sources().contains(source.id())) return false;
        return reasonUnusable(source) == null;
    }

    /**
     * Why the planner has no task for this source yet, or null when it is usable. The three unplanned kinds go
     * into the plan's notes so the owner can read what a plan could not consider (brief P12).
     */
    public String reasonUnusable(Source source) {
        if (source instanceof Source.ChestLoot) return "chest loot: finding structures is not planned yet";
        if (source instanceof Source.MineBlock mine) {
            if (mine.needsSilkTouch()) return "needs silk touch, not planned yet";
            if (mine.needsShears()) return "needs shears, not planned yet";
        }
        if (source.yield() <= 0) return "yields nothing";
        return null;
    }

    /** Cost of one unit through this source, or {@code UNOBTAINABLE} when the planner has no task for it. */
    public long costOf(Source source) {
        if (reasonUnusable(source) != null) return UNOBTAINABLE;
        if (source instanceof Source.MineBlock mine) {
            // Per-unit = the breaking attempt's cost spread over the drop, plus the depth and tool-tier charges.
            // The brief reads "mineBase / yield; plus deepOre; plus toolStep per tier below the required one".
            long cost = ceilDiv(config.mineBase(), mine.yield());
            if (mine.ore() != null) cost += config.deepOre();
            cost += config.toolStep() * tiersBelow(mine);
            return cost;
        }
        if (source instanceof Source.Harvest) return ceilDiv(config.harvest(), source.yield());
        if (source instanceof Source.KillMob) return ceilDiv(config.kill(), source.yield());
        if (source instanceof Source.Fishing) return ceilDiv(config.fish(), source.yield());
        if (source instanceof Source.Trade) return config.trade();
        return UNOBTAINABLE;
    }

    /** How many tool tiers the bot's best tool of the source's class is below the source's required tier. */
    private int tiersBelow(Source.MineBlock mine) {
        if (mine.toolClass() == null) return 0;
        ToolTier best = snapshot.bestTool(mine.toolClass());
        return Math.max(0, mine.minTier().ordinal() - best.ordinal());
    }

    private static long ceilDiv(long cost, double yield) {
        return (long) Math.ceil(cost / yield);
    }

    @Override
    public long gatherCost(ItemKey item) {
        return bestCost(item);
    }
}
