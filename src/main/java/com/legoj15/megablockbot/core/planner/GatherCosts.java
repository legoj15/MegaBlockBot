package com.legoj15.megablockbot.core.planner;

import com.legoj15.megablockbot.core.ItemKey;
import com.legoj15.megablockbot.core.ToolTier;
import com.legoj15.megablockbot.core.acquire.AcquisitionIndex;
import com.legoj15.megablockbot.core.acquire.Source;
import com.legoj15.megablockbot.core.plan.Exclusions;
import com.legoj15.megablockbot.core.snapshot.Snapshot;
import com.legoj15.solver.CostModel;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.ToLongFunction;

/**
 * The solver's cost model, fed by the acquisition index (brief P12; design 4.1 "How the cost model works", 4.3).
 * {@link #gatherCost(ItemKey)} is the cheapest usable source's cost per unit, or {@code UNOBTAINABLE} when no
 * source is usable; {@link #best(ItemKey)} returns that source and {@link #ranked(ItemKey)} every usable source in
 * the order the planner tries them. A per-request object: exclusions and the bot's best tools come from one
 * request, so the planner builds one per plan call and shares it with its solver.
 *
 * <p>Costs are abstract (think ticks). The attempt cost of a source divided by its {@code yield} is the per-unit
 * cost; a source whose attempts may come up empty expresses that in its {@code yield} already, so chance is not
 * divided out a second time. A trade is one attempt for one item: its cost per unit is the trade base divided by
 * its chance, plus the unit cost of what it charges (see the {@code paymentPrice} parameter).
 *
 * <p>Not planned yet, so a source needing them is unusable here and the plan says so in its notes: silk touch,
 * shears (a drop that needs shears), and chest loot (finding structures).
 */
public final class GatherCosts implements CostModel<String, ItemKey> {
    private final AcquisitionIndex index;
    private final PlannerConfig config;
    private final Exclusions exclusions;
    private final Snapshot snapshot;
    private final ToLongFunction<ItemKey> paymentPrice;

    /** A cost model that charges nothing for what a trade takes in payment. */
    public GatherCosts(AcquisitionIndex index, PlannerConfig config, Exclusions exclusions, Snapshot snapshot) {
        this(index, config, exclusions, snapshot, null);
    }

    /**
     * @param paymentPrice the unit cost of an item a trade charges, or null to charge nothing for payments. It is
     *                     a separate function (the planner passes a second solver's {@code unitCost}, built over a
     *                     model that charges nothing) because the solver that owns this model must not be asked
     *                     for a unit cost while it derives one.
     */
    public GatherCosts(AcquisitionIndex index, PlannerConfig config, Exclusions exclusions, Snapshot snapshot,
                       ToLongFunction<ItemKey> paymentPrice) {
        this.index = index;
        this.config = config;
        this.exclusions = exclusions;
        this.snapshot = snapshot;
        this.paymentPrice = paymentPrice;
    }

    /**
     * Every usable source of the item, cheapest first, ties on source id so a plan is deterministic. A source whose
     * cost is {@code UNOBTAINABLE} (a trade whose payment cannot be had) is usable and sorts last, as the solver
     * does with such a cost.
     */
    public List<Source> ranked(ItemKey item) {
        record Priced(Source source, long cost) {}
        List<Priced> priced = new ArrayList<>();
        for (Source source : index.sources(item)) {
            if (usable(source)) priced.add(new Priced(source, costOf(source)));
        }
        priced.sort(Comparator.comparingLong(Priced::cost).thenComparing(p -> p.source().id()));
        List<Source> ranked = new ArrayList<>(priced.size());
        for (Priced p : priced) ranked.add(p.source());
        return ranked;
    }

    /** The cheapest usable source, or null when none is usable. */
    public Source best(ItemKey item) {
        List<Source> ranked = ranked(item);
        return ranked.isEmpty() ? null : ranked.get(0);
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
     * Why the planner has no task for this source yet, or null when it is usable. The unplanned kinds go into the
     * plan's notes so the owner can read what a plan could not consider (brief P12).
     */
    public String reasonUnusable(Source source) {
        if (source instanceof Source.ChestLoot) return "chest loot: finding structures is not planned yet";
        if (source instanceof Source.MineBlock mine) {
            if (mine.needsSilkTouch()) return "needs silk touch, not planned yet";
            if (mine.needsShears()) return "needs shears, not planned yet";
        }
        if (source instanceof Source.Trade trade && !(trade.chance() > 0)) return "the offer never comes up";
        if (source.yield() <= 0) return "yields nothing";
        return null;
    }

    /** Cost of one unit through this source, or {@code UNOBTAINABLE} when the planner has no task for it. */
    public long costOf(Source source) {
        if (reasonUnusable(source) != null) return UNOBTAINABLE;
        if (source instanceof Source.MineBlock mine) {
            // Per-unit = the breaking attempt's cost spread over the drop, plus the depth and tool-tier charges.
            // The brief reads "mineBase / yield; plus deepOre; plus toolStep per tier below the required one".
            return saturatingAdd(breakingCost(mine), config.toolStep() * tiersBelow(mine));
        }
        if (source instanceof Source.Harvest) return ceilDiv(config.harvest(), source.yield());
        if (source instanceof Source.KillMob) return ceilDiv(config.kill(), source.yield());
        if (source instanceof Source.Fishing) return ceilDiv(config.fish(), source.yield());
        if (source instanceof Source.Trade trade) {
            // One trade gives one item; a trade that comes up with chance p is tried 1/p times per success.
            return saturatingAdd(ceilDiv(config.trade(), trade.chance()), paymentCost(trade));
        }
        return UNOBTAINABLE;
    }

    /**
     * Cost of one unit through this source once the tool it requires is carried: {@link #costOf} without the
     * tool-tier charge. The planner prices a gather as this for every unit plus the tool's own price once;
     * {@code costOf} per unit would charge the missing tier on every unit and the tool as well.
     */
    public long costOfCarrying(Source source) {
        if (reasonUnusable(source) != null) return UNOBTAINABLE;
        if (source instanceof Source.MineBlock mine && mine.toolRequired()) return breakingCost(mine);
        return costOf(source);
    }

    /** The breaking attempt's cost spread over the drop, plus the depth charge of an ore. */
    private long breakingCost(Source.MineBlock mine) {
        long cost = ceilDiv(config.mineBase(), mine.yield());
        return mine.ore() != null ? saturatingAdd(cost, config.deepOre()) : cost;
    }

    /** What one trade charges, priced at the unit cost of each item; {@code UNOBTAINABLE} when one cannot be had. */
    private long paymentCost(Source.Trade trade) {
        if (paymentPrice == null) return 0;
        long total = 0;
        for (Map.Entry<ItemKey, Integer> charge : trade.cost().entrySet()) {
            long unit = paymentPrice.applyAsLong(charge.getKey());
            if (unit >= UNOBTAINABLE) return UNOBTAINABLE;
            long count = charge.getValue();
            if (count <= 0) continue;
            total = saturatingAdd(total, unit > UNOBTAINABLE / count ? UNOBTAINABLE : unit * count);
        }
        return total;
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

    private static long saturatingAdd(long a, long b) {
        long sum = a + b;
        return sum < 0 || a >= UNOBTAINABLE || b >= UNOBTAINABLE ? UNOBTAINABLE : sum;
    }

    @Override
    public long gatherCost(ItemKey item) {
        return bestCost(item);
    }
}
