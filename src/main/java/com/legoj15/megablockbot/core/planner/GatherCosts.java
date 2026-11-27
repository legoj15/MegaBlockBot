package com.legoj15.megablockbot.core.planner;

import com.legoj15.megablockbot.core.ItemKey;
import com.legoj15.megablockbot.core.ToolClass;
import com.legoj15.megablockbot.core.ToolTier;
import com.legoj15.megablockbot.core.acquire.AcquisitionIndex;
import com.legoj15.megablockbot.core.acquire.Source;
import com.legoj15.megablockbot.core.plan.Exclusions;
import com.legoj15.megablockbot.core.snapshot.Snapshot;
import com.legoj15.solver.CostModel;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * shears (a drop that needs shears), and chest loot (finding structures). A trade that charges the item it sells
 * gains nothing and is unusable too.
 *
 * <p>What the body can see (the snapshot's sight, design 4.1): once the snapshot holds any sight, a mining or
 * harvesting source whose block is in it costs {@link PlannerConfig#sightCharge} on top, growing with the block's
 * horizontal distance, and one whose block is not costs {@link PlannerConfig#unseenPenalty()} on top, which is more
 * than any seen block's charge. Every source whose block is out of sight is charged alike, so a snapshot that holds
 * none of the choices' blocks orders them as it always did. A snapshot with no sight at all adds nothing: charging
 * only block sources would move them against kills, fishing, trades, crafts and a strategy's proposal, and today's
 * choices would no longer be today's.
 *
 * <p>A mining source whose required tool cannot be had at all (see {@link #toolObtainable}) costs {@code UNOBTAINABLE}:
 * the solver then never lists a craftable item as a gather through it, while the planner still tries the source last,
 * so the failure reported for an item with no other way is that source's own.
 */
public final class GatherCosts implements CostModel<String, ItemKey> {
    /**
     * What each point of a mob's blow adds to the price of killing it, as a share of the price: a quarter, so a
     * zombie's three points come to three quarters more than a harmless mob of its health.
     */
    private static final double BLOW_SHARE = 0.25;

    private final RecipeBook recipes;
    private final AcquisitionIndex index;
    private final PlannerConfig config;
    private final Exclusions exclusions;
    private final Snapshot snapshot;
    private final ToLongFunction<ItemKey> paymentPrice;
    /** Whether each tool item could be had, by item, decided once per model: a model answers the same every time. */
    private final Map<ItemKey, Boolean> toolMemo = new HashMap<>();

    /** A cost model that charges nothing for what a trade takes in payment. */
    public GatherCosts(RecipeBook recipes, AcquisitionIndex index, PlannerConfig config, Exclusions exclusions,
                       Snapshot snapshot) {
        this(recipes, index, config, exclusions, snapshot, null);
    }

    /**
     * @param paymentPrice the unit cost of an item a trade charges, or null to charge nothing for payments. It is
     *                     a separate function (the planner passes a second solver's {@code unitCost}, built over a
     *                     model that charges nothing) because the solver that owns this model must not be asked
     *                     for a unit cost while it derives one.
     * @param recipes      what makes an item, for telling whether a mining source's tool can be had at all
     */
    public GatherCosts(RecipeBook recipes, AcquisitionIndex index, PlannerConfig config, Exclusions exclusions,
                       Snapshot snapshot, ToLongFunction<ItemKey> paymentPrice) {
        this.recipes = recipes;
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
            // A block the world does not generate (a crafting table, planks, a campfire, a bookshelf, redstone wire)
            // stands only in a structure or where someone built it: the index lists it because breaking one gives
            // something, not because the world has any for the bot to find. Found by live runs that went looking
            // for a campfire to get charcoal from and for redstone wire to get dust from. What the generator
            // places stays, recipe or not (diorite, clay, a melon).
            if (!mine.generated()) {
                return "the world does not generate this block: it stands only where someone placed one";
            }
        }
        if (source instanceof Source.KillMob kill && !kill.spawnsNaturally()) {
            // An iron golem is four ingots a kill, cheaper on paper than a mine and a furnace; one stands only in a
            // village or where a player built it. As with a generated chest, the finding is what is not planned.
            return "a mob the world does not spawn on its own: finding one is not planned yet";
        }
        if (source instanceof Source.KillMob kill && kill.aquatic()) {
            // The kill task closes on its quarry on foot and the body is kept at the surface for its air: it does
            // not fight under water. Until it does, a fish is something to catch with a rod, not to hunt.
            return "a mob that lives in water: hunting under water is not built yet";
        }
        if (source instanceof Source.Trade trade && !(trade.chance() > 0)) return "the offer never comes up";
        // One item per trade: a trade that takes one or more of that item back leaves the stock no higher.
        if (source instanceof Source.Trade trade && trade.cost().getOrDefault(trade.item(), 0) >= 1) {
            return "the trade charges the item it sells";
        }
        if (source.yield() <= 0) return "yields nothing";
        return null;
    }

    /**
     * Whether the tool a mining source requires can be had: carried (the snapshot's best tool, or a tool item it
     * holds), or an item that a recipe makes or a usable source yields, followed through the tools its own sources
     * need (a chain that loops back on itself is no way in). A source that requires no tool needs none. This only
     * rules out what can never be planned; it does not say a plan for the tool will succeed (a recipe's ingredients
     * may have no source), which only planning finds out.
     */
    public boolean toolObtainable(Source.MineBlock mine) {
        if (!mine.toolRequired()) return true;
        if (mine.toolClass() == null) return false;
        if (carries(mine.toolClass(), mine.minTier())) return true;
        ItemKey tool = config.toolItem(mine.toolClass(), mine.minTier());
        if (tool == null) return false;
        Boolean known = toolMemo.get(tool);
        if (known == null) {
            known = obtainable(tool, new HashSet<>());
            toolMemo.put(tool, known);
        }
        return known;
    }

    /** Whether the snapshot holds a tool of the class at the tier or better, as the best tool or as an item. */
    private boolean carries(ToolClass toolClass, ToolTier tier) {
        if (snapshot.bestTool(toolClass).atLeast(tier)) return true;
        for (ToolTier held : ToolTier.values()) {
            ItemKey item = held.atLeast(tier) ? config.toolItem(toolClass, held) : null;
            if (item != null && snapshot.count(item) > 0) return true;
        }
        return false;
    }

    /** Held, made by a recipe, or yielded by a usable source whose own tool can be had; {@code chain} guards cycles. */
    private boolean obtainable(ItemKey item, Set<ItemKey> chain) {
        if (snapshot.count(item) > 0 || !recipes.producers(item).isEmpty()) return true;
        if (!chain.add(item)) return false;
        try {
            for (Source source : index.sources(item)) {
                if (!usable(source)) continue;
                if (!(source instanceof Source.MineBlock mine) || !mine.toolRequired()) return true;
                if (mine.toolClass() == null) continue;
                if (carries(mine.toolClass(), mine.minTier())) return true;
                ItemKey tool = config.toolItem(mine.toolClass(), mine.minTier());
                if (tool != null && obtainable(tool, chain)) return true;
            }
            return false;
        } finally {
            chain.remove(item);
        }
    }

    /** Cost of one unit through this source, or {@code UNOBTAINABLE} when the planner has no task for it. */
    public long costOf(Source source) {
        if (reasonUnusable(source) != null) return UNOBTAINABLE;
        if (source instanceof Source.MineBlock mine) {
            if (!toolObtainable(mine)) return UNOBTAINABLE;
            // Per-unit = the breaking attempt's cost spread over the drop, plus the depth, sight and tool-tier charges.
            // The brief reads "mineBase / yield; plus deepOre; plus toolStep per tier below the required one".
            return saturatingAdd(breakingCost(mine), config.toolStep() * tiersBelow(mine));
        }
        if (source instanceof Source.Harvest harvest) {
            return sighted(ceilDiv(config.harvest(), source.yield()), harvest.block());
        }
        if (source instanceof Source.KillMob kill) return ceilDiv(fightCost(kill), source.yield());
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
        if (source instanceof Source.MineBlock mine && mine.toolRequired()) {
            return toolObtainable(mine) ? breakingCost(mine) : UNOBTAINABLE;
        }
        return costOf(source);
    }

    /**
     * The breaking attempt's cost spread over the drop, plus the depth charge of an ore and what the sight says of the
     * block. A tool the bot has to make first is charged by the caller.
     */
    /**
     * One kill's price: the base price is a cow's (ten health, no blow of its own), a mob with more health takes that
     * many times the swings, and each point of its blow adds {@link #BLOW_SHARE} of the price for the health and the
     * time the fight costs the body. A zombie (20 health, a blow of 3) comes to three and a half cows.
     */
    private long fightCost(Source.KillMob kill) {
        double swings = Math.max(1.0, kill.health() / Source.KillMob.BASE_HEALTH);
        double danger = 1.0 + Math.max(0.0, kill.attack()) * BLOW_SHARE;
        return (long) Math.min((double) UNOBTAINABLE - 1, Math.ceil(config.kill() * swings * danger));
    }

    private long breakingCost(Source.MineBlock mine) {
        long cost = ceilDiv(config.mineBase(), mine.yield());
        return sighted(mine.ore() != null ? saturatingAdd(cost, config.deepOre()) : cost, mine.block());
    }

    /**
     * {@code cost} plus what the snapshot's sight says of the block: nothing when the snapshot holds no sight, the
     * distance charge when the block is in it, the unseen penalty when it is not. {@code UNOBTAINABLE} stays so.
     */
    private long sighted(long cost, String block) {
        if (snapshot.nearbyBlocks().isEmpty()) return cost;
        Integer distance = snapshot.nearbyBlocks().get(block);
        return saturatingAdd(cost, distance == null ? config.unseenPenalty() : config.sightCharge(distance));
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
