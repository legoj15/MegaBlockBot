package com.legoj15.megablockbot.core.planner;

import com.legoj15.megablockbot.core.EquipSlot;
import com.legoj15.megablockbot.core.ItemKey;
import com.legoj15.megablockbot.core.ToolClass;
import com.legoj15.megablockbot.core.ToolTier;
import com.legoj15.megablockbot.core.acquire.AcquisitionIndex;
import com.legoj15.megablockbot.core.acquire.Source;
import com.legoj15.megablockbot.core.goal.ComponentCheck;
import com.legoj15.megablockbot.core.goal.Goal;
import com.legoj15.megablockbot.core.plan.Exclusions;
import com.legoj15.megablockbot.core.plan.NodeSpec;
import com.legoj15.megablockbot.core.plan.Plan;
import com.legoj15.megablockbot.core.plan.PlanOutcome;
import com.legoj15.megablockbot.core.plan.PlanRequest;
import com.legoj15.megablockbot.core.plan.Planner;
import com.legoj15.megablockbot.core.snapshot.ContainerSight;
import com.legoj15.megablockbot.core.snapshot.Snapshot;
import com.legoj15.megablockbot.core.task.Failure;
import com.legoj15.solver.CostModel;
import com.legoj15.solver.PlannedStep;
import com.legoj15.solver.RecipeGraphSolver;
import com.legoj15.solver.SlotLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The planner of brief P12 (design 3.4 "Replanning from live state" for the fingerprint, 4.1 for the solver,
 * 4.3 for the sources, 4.4 for the strategies): turns a typed goal and a snapshot into a goal tree of gather
 * and craft leaves, Minecraft-free, safe to call from a worker thread.
 *
 * <p>It holds no mutable state: every {@link #plan} call works on its own {@link Run} — a working copy of the
 * snapshot's inventory, a fresh solver over the recipe book with {@link GatherCosts} as the cost model, and the
 * notes the plan carries back.
 */
public final class SolverPlanner implements Planner {
    /** Solver budgets, SprawlCrafting's pins (docs/handoff/solver-core.md): depth and attempt caps per solve. */
    static final int SOLVER_DEPTH = 32;
    static final int SOLVER_ATTEMPTS = 4096;
    /** A tool prerequisite chain deeper than this is unplannable (brief P12). */
    static final int MAX_TOOL_DEPTH = 6;
    /** A strategy that re-enters {@code subPlan} past this depth is looping; the plan stops instead of recursing. */
    static final int MAX_EXPAND_DEPTH = 16;

    private final RecipeBook recipes;
    private final AcquisitionIndex index;
    private final PlannerConfig config;
    private final List<Strategy> strategies;
    /** The config's tool table read backwards: what class and tier a tool item in stock stands for. */
    private final Map<ItemKey, ToolClass> toolClasses = new TreeMap<>();
    private final Map<ItemKey, ToolTier> toolTiers = new TreeMap<>();

    /**
     * @param strategies asked in list order when the planner cannot expand a goal itself; the cheapest proposal
     *                   wins, ties on {@link Strategy#id()}
     */
    public SolverPlanner(RecipeBook recipes, AcquisitionIndex index, PlannerConfig config, List<Strategy> strategies) {
        this.recipes = recipes;
        this.index = index;
        this.config = config;
        this.strategies = List.copyOf(strategies);
        for (Map.Entry<String, ItemKey> entry : config.toolItems().entrySet()) {
            String[] key = entry.getKey().split(":");
            toolClasses.put(entry.getValue(), ToolClass.valueOf(key[0]));
            toolTiers.put(entry.getValue(), ToolTier.valueOf(key[1]));
        }
    }

    @Override
    public PlanOutcome plan(PlanRequest request) {
        Run run = new Run(request.snapshot(), request.exclusions());
        Step step = run.expand(request.goal());
        if (step.failure() != null) return new PlanOutcome.Unplannable(step.failure());
        return new PlanOutcome.Planned(finish(request.goal(), step.nodes(), run));
    }

    private Plan finish(Goal goal, List<NodeSpec> roots, Run run) {
        List<NodeSpec> wrapped = roots.isEmpty() ? List.of() : List.of(NodeSpec.group(goal, roots));
        return new Plan(wrapped, inputFingerprint(goal, run.snapshot), config.maxTotalReplans(), run.notes);
    }

    // ─── One planning run ───────────────────────────────────────────────────────────────────────

    /** What an expansion produced: nodes in execution order, or the failure that makes the goal unplannable. */
    private record Step(List<NodeSpec> nodes, Failure failure) {
        static Step ok(List<NodeSpec> nodes) { return new Step(nodes, null); }

        static Step fail(Failure failure) { return new Step(List.of(), failure); }
    }

    private final class Run implements PlanContext {
        private final Snapshot snapshot;
        private final Exclusions exclusions;
        private final GatherCosts costs;
        private final RecipeGraphSolver<String, ItemKey> solver;
        /** What the body will hold once the nodes planned so far have run; starts as the snapshot's inventory. */
        private final TreeMap<ItemKey, Long> stock = new TreeMap<>();
        private final List<String> notes = new ArrayList<>();
        private final TreeSet<String> toolPath = new TreeSet<>();
        private int toolDepth;
        private int expandDepth;

        Run(Snapshot snapshot, Exclusions exclusions) {
            this.snapshot = snapshot;
            this.exclusions = exclusions;
            this.costs = new GatherCosts(index, config, exclusions, snapshot);
            // Fresh per run: the solver keeps derived unit costs for its lifetime, and this run's model carries
            // this request's exclusions and best tools.
            this.solver = new RecipeGraphSolver<>(
                    item -> recipes.producers(item).stream().map(SolverRecipe::of).toList(),
                    recipes::remainder, SOLVER_DEPTH, SOLVER_ATTEMPTS, RecipeGraphSolver.DEFAULT_MAX_STEPS, costs);
            for (Map.Entry<ItemKey, Integer> item : snapshot.items().entrySet()) {
                stock.put(item.getKey(), (long) item.getValue());
            }
        }

        Step expand(Goal goal) {
            if (++expandDepth > MAX_EXPAND_DEPTH) {
                expandDepth--;
                return Step.fail(internal("planning re-entered itself more than " + MAX_EXPAND_DEPTH + " deep"));
            }
            try {
                return switch (goal) {
                    case Goal.HaveItem g -> g.check() == null ? itemPlan(g.key(), g.count()) : strategiesFor(g);
                    case Goal.HaveToolTier g -> haveToolTier(g);
                    case Goal.Equip g -> equipPlan(g);
                    case Goal.Stockpile g -> stockpilePlan(g);
                    case Goal.Reach g -> Step.ok(List.of(navigateLeaf(g)));
                    case Goal.Build g -> strategiesFor(g);
                    case Goal.Custom g -> strategiesFor(g);
                };
            } finally {
                expandDepth--;
            }
        }

        /** Satisfied tiers plan nothing; otherwise the tier's tool item and its item plan (brief P12). */
        Step haveToolTier(Goal.HaveToolTier goal) {
            if (carriedTier(goal.toolClass()).atLeast(goal.tier())) return Step.ok(List.of());
            ItemKey toolItem = config.toolItem(goal.toolClass(), goal.tier());
            if (toolItem == null) {
                return Step.fail(new Failure.MissingTool(goal.toolClass(), goal.tier(),
                        "no tool item is mapped for " + goal.toolClass() + " at " + goal.tier()));
            }
            return itemPlan(toolItem, 1);
        }

        /**
         * The best tier of the class the body holds now, or will hold once the nodes planned so far have run —
         * a prerequisite planned "against the working stock" must not replan a tool an earlier node crafts.
         */
        ToolTier carriedTier(ToolClass toolClass) {
            ToolTier best = snapshot.bestTool(toolClass);
            for (Map.Entry<ItemKey, Long> held : stock.entrySet()) {
                if (held.getValue() <= 0 || toolClasses.get(held.getKey()) != toolClass) continue;
                ToolTier tier = toolTiers.get(held.getKey());
                if (tier.ordinal() > best.ordinal()) best = tier;
            }
            return best;
        }

        /**
         * The item plan for (key, count) against the working stock (brief P12): the shortfall's leaves gathered
         * from their best sources, then the crafts that assemble the item. An item no recipe makes is one gather
         * leaf; a recipe-produced item is gathered and crafted run by run until the stock reaches the count.
         */
        Step itemPlan(ItemKey key, int count) {
            if (stock.getOrDefault(key, 0L) >= count) return Step.ok(List.of());
            List<RecipeDef> producers = recipes.producers(key);
            if (producers.isEmpty()) return leaf(key, count - stock.getOrDefault(key, 0L));

            List<NodeSpec> nodes = new ArrayList<>();
            RecipeDef target = cheapestProducer(key, producers);
            int runs = 0;
            while (stock.getOrDefault(key, 0L) < count) {
                // Each run adds at least one of the item (a gather or a craft), so `count` bounds the loop.
                if (++runs > count) return Step.fail(internal("planning did not converge on " + key.id()));
                Step gatherStep = gatherLeaves(key, target);
                if (gatherStep.failure() != null) return gatherStep;
                nodes.addAll(gatherStep.nodes());
                Step craftStep = craftPlan(key, target);
                if (craftStep.failure() != null) return craftStep;
                nodes.addAll(craftStep.nodes());
            }
            return Step.ok(nodes);
        }

        /** The shortfall's leaves gathered from their best sources, in item-id order (brief P12, steps 2–3). */
        Step gatherLeaves(ItemKey key, RecipeDef target) {
            RecipeGraphSolver.ShortfallResult<ItemKey> shortfall = solver.shortfallLong(SolverRecipe.of(target), stock);
            if (shortfall.saturated()) {
                return Step.fail(internal("the shortfall of " + key.id() + " overflows what a plan can count"));
            }
            if (shortfall.approximate()) {
                note("the gather list for " + key.id() + " may be incomplete: the shortfall walk hit its budget");
            }
            List<NodeSpec> nodes = new ArrayList<>();
            for (Map.Entry<ItemKey, Long> demand : new TreeMap<>(shortfall.demandsLong()).entrySet()) {
                if (demand.getValue() <= 0) continue;
                Step leafStep = leaf(demand.getKey(), demand.getValue());
                if (leafStep.failure() != null) return leafStep;
                nodes.addAll(leafStep.nodes());
            }
            return Step.ok(nodes);
        }

        /**
         * The producer the shortfall walks and the solve targets: the one whose derived cost is lowest, priced
         * with the solver's own unit costs, ties on recipe id. The solver's derivation prices a producer from the
         * per-level costs of its own relaxation round; this reads the settled {@code unitCost} of each input,
         * which agrees with it once the graph has converged, and is only ever used to pick between producers.
         */
        RecipeDef cheapestProducer(ItemKey item, List<RecipeDef> producers) {
            RecipeDef best = null;
            long bestCost = 0;
            for (RecipeDef recipe : producers) {
                long cost = producerCost(recipe);
                if (best == null || cost < bestCost
                        || (cost == bestCost && recipe.id().compareTo(best.id()) < 0)) {
                    best = recipe;
                    bestCost = cost;
                }
            }
            return best;
        }

        /** One craft of the recipe through its inputs' settled unit costs: the solver's unit-cost formula. */
        private long producerCost(RecipeDef recipe) {
            SolverRecipe info = SolverRecipe.of(recipe);
            List<List<ItemKey>> slots = info.ingredientSlots();
            long cost = 1; // recipeCost: GatherCosts keeps the solver's default of one per craft
            for (int slot = 0; slot < slots.size(); slot++) {
                long cheapest = CostModel.UNOBTAINABLE;
                for (ItemKey alternative : slots.get(slot)) {
                    cheapest = Math.min(cheapest, solver.unitCost(alternative));
                }
                if (cheapest >= CostModel.UNOBTAINABLE) return CostModel.UNOBTAINABLE;
                cost += cheapest * info.ingredientSlotCount(slot);
            }
            if (recipe.station() != null) {
                long station = solver.unitCost(recipe.station());
                if (station >= CostModel.UNOBTAINABLE) return CostModel.UNOBTAINABLE;
                cost += station;
            }
            return (cost + recipe.resultCount() - 1) / recipe.resultCount();
        }

        /**
         * The nodes that obtain one leaf item: a tool prerequisite when the best source needs one, then the
         * gather itself — unless a strategy undercuts the best source's cost (brief P12; design 4.4). The goal
         * of the gather is absolute (working stock plus demand) so the executor can verify it from a snapshot.
         */
        Step leaf(ItemKey item, long demand) {
            long absolute = stock.getOrDefault(item, 0L) + demand;
            if (absolute > Integer.MAX_VALUE) {
                return Step.fail(internal("the demand for " + item.id() + " exceeds what a goal can count"));
            }
            Source best = costs.best(item);
            long bestCost = costs.bestCost(item);

            // A strategy may plan the leaf through sub-plans that advance the stock; if its proposal loses to
            // the gather, those side effects are undone before the gather's own arithmetic runs.
            TreeMap<ItemKey, Long> stockBeforeStrategies = new TreeMap<>(stock);
            Strategy.Proposal winner = null;
            String winnerId = null;
            for (Strategy strategy : strategies) {
                Optional<Strategy.Proposal> proposal =
                        strategy.propose(new Goal.HaveItem(item, (int) absolute), this);
                if (proposal.isEmpty() || proposal.get().nodes().isEmpty()) continue;
                Strategy.Proposal candidate = proposal.get();
                if (winner == null || candidate.cost() < winner.cost()
                        || (candidate.cost() == winner.cost() && strategy.id().compareTo(winnerId) < 0)) {
                    winner = candidate;
                    winnerId = strategy.id();
                }
            }
            if (winner != null && winner.cost() < bestCost) {
                notes.addAll(winner.notes());
                return Step.ok(winner.nodes());
            }
            stock.clear();
            stock.putAll(stockBeforeStrategies);

            if (best == null) {
                String detail = noSourceDetail(item);
                note("no usable source for " + item.id() + ": " + detail);
                return Step.fail(new Failure.NoSource(item, "", detail));
            }
            noteSkippedSources(item, best);

            List<NodeSpec> nodes = new ArrayList<>();
            if (best instanceof Source.MineBlock mine && mine.toolRequired()) {
                if (mine.toolClass() == null) {
                    return Step.fail(new Failure.MissingTool(null, mine.minTier(),
                            mine.id() + " needs a tool of no known class"));
                }
                if (!carriedTier(mine.toolClass()).atLeast(mine.minTier())) {
                    Step tool = toolPrerequisite(mine);
                    if (tool.failure() != null) return tool;
                    nodes.addAll(tool.nodes());
                }
            }
            nodes.add(NodeSpec.leaf(new Goal.HaveItem(item, (int) absolute), taskKind(best),
                    gatherParams(best, demand), best.id()));
            addStock(item, demand);
            return Step.ok(nodes);
        }

        /**
         * The {@code HaveToolTier} plan as a group node placed before the gather that needs the tool (brief P12).
         * Cycles (a tool whose own chain needs the same tool) and chains deeper than {@link #MAX_TOOL_DEPTH} are
         * unplannable: the first would recurse forever, the second exceeds what a plan can hold.
         */
        Step toolPrerequisite(Source.MineBlock mine) {
            ToolClass toolClass = mine.toolClass();
            ToolTier required = mine.minTier();
            String key = PlannerConfig.toolKey(toolClass, required);
            if (toolPath.contains(key)) {
                return Step.fail(new Failure.MissingTool(toolClass, required,
                        "planning the " + key + " tool needs itself: the tool chain is cyclic"));
            }
            if (toolDepth >= MAX_TOOL_DEPTH) {
                return Step.fail(new Failure.MissingTool(toolClass, required,
                        "the tool prerequisite chain is deeper than " + MAX_TOOL_DEPTH));
            }
            toolPath.add(key);
            toolDepth++;
            try {
                Step step = expand(new Goal.HaveToolTier(toolClass, required));
                if (step.failure() != null) return step;
                if (step.nodes().isEmpty()) return Step.ok(List.of());
                return Step.ok(List.of(NodeSpec.group(new Goal.HaveToolTier(toolClass, required), step.nodes())));
            } finally {
                toolPath.remove(key);
                toolDepth--;
            }
        }

        /** The crafts that assemble the item from the working stock (brief P12, step 4), one solve per run. */
        Step craftPlan(ItemKey key, RecipeDef target) {
            RecipeGraphSolver.Result<String, ItemKey> result = solver.solveLong(SolverRecipe.of(target), stock);
            if (!(result instanceof RecipeGraphSolver.Result.Success<String, ItemKey> success)) {
                String detail = failureDetail(key, result);
                return Step.fail(new Failure.Internal(new IllegalStateException("planner: " + detail), detail));
            }
            List<NodeSpec> nodes = new ArrayList<>();
            List<PlannedStep<String>> steps = success.steps();
            List<List<SlotLayout<ItemKey>>> layouts = success.layouts();
            for (int i = 0; i < steps.size(); i++) {
                PlannedStep<String> step = steps.get(i);
                RecipeDef recipe = recipes.byId(step.recipeId()).orElse(null);
                if (recipe == null) {
                    return Step.fail(internal("the solver planned unknown recipe " + step.recipeId()));
                }
                List<SlotLayout<ItemKey>> stepLayouts =
                        layouts == null || i >= layouts.size() ? List.of() : layouts.get(i);
                advance(recipe, step, stepLayouts);
                if (!RecipeDef.CRAFT.equals(recipe.kind())) {
                    note("recipe " + recipe.id() + " runs as task kind '" + recipe.kind()
                            + "', which no task implements yet");
                }
                long after = stock.getOrDefault(recipe.result(), 0L);
                if (after > Integer.MAX_VALUE) {
                    return Step.fail(internal("the stock of " + recipe.result().id() + " exceeds what a goal can count"));
                }
                Map<String, String> params = new TreeMap<>();
                params.put("recipe", recipe.id());
                params.put("times", Long.toString(step.crafts()));
                params.put("station", recipe.station() == null ? "" : recipe.station().id());
                nodes.add(NodeSpec.leaf(new Goal.HaveItem(recipe.result(), (int) after), recipe.kind(), params, ""));
            }
            return Step.ok(nodes);
        }

        /** The solver's answer as one detail line: the missing items it named, and which budget ran out. */
        private String failureDetail(ItemKey key, RecipeGraphSolver.Result<String, ItemKey> result) {
            if (result instanceof RecipeGraphSolver.Result.Failure<String, ItemKey> failure) {
                StringBuilder missing = new StringBuilder();
                for (ItemKey item : new TreeSet<>(failure.missing())) missing.append(item.id()).append(' ');
                return "the solver could not craft " + key.id() + " from the gathered stock (missing: "
                        + missing.toString().trim() + (failure.budgetExceeded() ? "; attempt budget exceeded" : "")
                        + (failure.tooLarge() ? "; plan too large" : "") + ")";
            }
            return "the solver answered " + result + " for " + key.id();
        }

        /**
         * Moves the working stock through one planned step: the plan's own layout names what each slot copy
         * draws, the results come in, and consumed items leave their remainders behind. The station item is
         * drawn by no slot and consumed by nothing, so it stays in stock for the next step at the same station.
         */
        private void advance(RecipeDef recipe, PlannedStep<String> step, List<SlotLayout<ItemKey>> layouts) {
            if (layouts.isEmpty()) {
                throw new IllegalStateException("planner: the solver returned step " + step.recipeId()
                        + " without a layout");
            }
            TreeMap<ItemKey, Long> draws = new TreeMap<>();
            for (SlotLayout<ItemKey> run : layouts) {
                for (ItemKey item : run.items()) draws.merge(item, run.crafts(), Long::sum);
            }
            for (Map.Entry<ItemKey, Long> draw : draws.entrySet()) {
                removeStock(draw.getKey(), draw.getValue());
                ItemKey remainder = recipes.remainder(draw.getKey());
                if (remainder != null) addStock(remainder, draw.getValue());
            }
            addStock(recipe.result(), step.crafts() * recipe.resultCount());
        }

        /** Asks every strategy; the cheapest proposal wins, ties on the strategy id (design 4.4). */
        Step strategiesFor(Goal goal) {
            Strategy.Proposal winner = null;
            String winnerId = null;
            for (Strategy strategy : strategies) {
                Optional<Strategy.Proposal> proposal = strategy.propose(goal, this);
                if (proposal.isEmpty() || proposal.get().nodes().isEmpty()) continue;
                Strategy.Proposal candidate = proposal.get();
                if (winner == null || candidate.cost() < winner.cost()
                        || (candidate.cost() == winner.cost() && strategy.id().compareTo(winnerId) < 0)) {
                    winner = candidate;
                    winnerId = strategy.id();
                }
            }
            if (winner == null) {
                ItemKey named = goal instanceof Goal.HaveItem have ? have.key()
                        : new ItemKey("megablockbot:" + goal.getClass().getSimpleName().toLowerCase());
                return Step.fail(new Failure.NoSource(named, "",
                        "no strategy applies to " + describe(goal) + "; a strategy is needed for it"));
            }
            notes.addAll(winner.notes());
            return Step.ok(winner.nodes());
        }

        /** An item plan for every slot's item not worn or held, then one equip leaf (brief P12). */
        Step equipPlan(Goal.Equip goal) {
            List<NodeSpec> nodes = new ArrayList<>();
            for (Map.Entry<EquipSlot, ItemKey> slot : new TreeMap<>(goal.slots()).entrySet()) {
                if (slot.getValue().equals(snapshot.equipped().get(slot.getKey()))) continue;
                Step step = itemPlan(slot.getValue(), 1);
                if (step.failure() != null) return step;
                nodes.addAll(step.nodes());
            }
            nodes.add(NodeSpec.leaf(goal, "equip", Map.of(), ""));
            return Step.ok(nodes);
        }

        /** An item plan for what the container is not known to hold already, then one deposit leaf (brief P12). */
        Step stockpilePlan(Goal.Stockpile goal) {
            ContainerSight sight = snapshot.containerAt(goal.container());
            long already = sight != null && sight.contentsKnown() ? sight.count(goal.key()) : 0;
            long need = Math.max(0, goal.count() - already);
            List<NodeSpec> nodes = new ArrayList<>();
            if (need > 0) {
                Step step = itemPlan(goal.key(), (int) need);
                if (step.failure() != null) return step;
                nodes.addAll(step.nodes());
            }
            Map<String, String> params = new TreeMap<>();
            params.put("item", goal.key().id());
            params.put("count", Integer.toString(goal.count()));
            params.put("container", goal.container().encode());
            nodes.add(NodeSpec.leaf(goal, "deposit", params, ""));
            return Step.ok(nodes);
        }

        private static NodeSpec navigateLeaf(Goal.Reach goal) {
            Map<String, String> params = new TreeMap<>();
            params.put("dimension", goal.dimension());
            params.put("pos", goal.pos().encode());
            params.put("radius", Integer.toString(goal.radius()));
            return NodeSpec.leaf(goal, "navigate", params, "");
        }

        /** What the task for the source's kind needs, as strings (brief P12). */
        private Map<String, String> gatherParams(Source source, long demand) {
            Map<String, String> params = new TreeMap<>();
            params.put("item", source.item().id());
            params.put("count", Long.toString(demand));
            if (source instanceof Source.MineBlock mine) {
                params.put("block", mine.block());
                if (mine.toolClass() != null) {
                    params.put("toolClass", mine.toolClass().name());
                    params.put("minTier", mine.minTier().name());
                }
                if (mine.ore() != null) {
                    params.put("minY", Integer.toString(mine.ore().minY()));
                    params.put("maxY", Integer.toString(mine.ore().maxY()));
                    params.put("bestY", Integer.toString(mine.ore().bestY()));
                }
            } else if (source instanceof Source.Harvest harvest) {
                params.put("block", harvest.block());
            } else if (source instanceof Source.KillMob kill) {
                params.put("entity", kill.entity());
            } else if (source instanceof Source.Fishing fishing) {
                params.put("table", fishing.table());
            } else if (source instanceof Source.Trade trade) {
                params.put("profession", trade.profession());
            } else if (source instanceof Source.ChestLoot chest) {
                params.put("table", chest.table());
            }
            return params;
        }

        private static String taskKind(Source source) {
            return switch (source) {
                case Source.MineBlock mine -> "mine";
                case Source.Harvest harvest -> "harvest";
                case Source.KillMob kill -> "kill";
                case Source.Fishing fishing -> "fish";
                case Source.Trade trade -> "trade";
                case Source.ChestLoot chest -> "chest"; // unreachable: chest loot is unusable and never chosen
            };
        }

        /** Why the leaf has no usable source, saying so when sources exist but are all excluded or unusable. */
        private String noSourceDetail(ItemKey item) {
            List<Source> sources = index.sources(item);
            if (sources.isEmpty()) return "no acquisition source is known for it";
            StringBuilder detail = new StringBuilder("every source is excluded or unusable:");
            for (Source source : sources) {
                detail.append(' ').append(source.id());
                if (exclusions.sources().contains(source.id())) detail.append(" excluded");
                else detail.append(' ').append(costs.reasonUnusable(source));
            }
            return detail.toString();
        }

        /** Notes what this plan could not consider: the usable-looking sources it had to skip (brief P12). */
        private void noteSkippedSources(ItemKey item, Source chosen) {
            for (Source source : index.sources(item)) {
                if (source.equals(chosen) || exclusions.sources().contains(source.id())) continue;
                String reason = costs.reasonUnusable(source);
                if (reason != null) note(item.id() + ": " + reason);
            }
        }

        private void note(String line) {
            if (!notes.contains(line)) notes.add(line);
        }

        private void addStock(ItemKey item, long count) {
            stock.merge(item, count, Long::sum);
        }

        private void removeStock(ItemKey item, long count) {
            long have = stock.getOrDefault(item, 0L) - count;
            if (have > 0) stock.put(item, have);
            else stock.remove(item);
        }

        private static Failure.Internal internal(String detail) {
            return new Failure.Internal(new IllegalStateException("planner: " + detail), detail);
        }

        // ─── PlanContext: what a strategy sees ───────────────────────────────────────────────────────

        @Override
        public Snapshot snapshot() {
            return snapshot;
        }

        @Override
        public Map<ItemKey, Long> stock() {
            return stock;
        }

        @Override
        public RecipeBook recipes() {
            return recipes;
        }

        @Override
        public AcquisitionIndex sources() {
            return index;
        }

        @Override
        public Exclusions exclusions() {
            return exclusions;
        }

        @Override
        public PlannerConfig config() {
            return config;
        }

        @Override
        public PlanOutcome subPlan(Goal goal) {
            Step step = expand(goal);
            if (step.failure() != null) return new PlanOutcome.Unplannable(step.failure());
            return new PlanOutcome.Planned(finish(goal, step.nodes(), this));
        }
    }

    // ─── Input fingerprint (brief P12; design 3.4) ──────────────────────────────────────────────

    /**
     * A stable 64-bit hash of everything the planner read: the goal, the inventory counts, the best tools and the
     * dimension. Position, health, hunger and game time are excluded, so a snapshot that changed none of the
     * planning inputs keeps the plan current. Stable across JVMs: FNV-1a over a canonical text, no
     * {@code hashCode} (whose distribution is specified but whose 32 bits collide sooner than needed here).
     */
    @Override
    public long inputFingerprint(Goal goal, Snapshot snapshot) {
        StringBuilder text = new StringBuilder();
        text.append("goal=").append(describe(goal)).append(";dim=").append(snapshot.dimension()).append(';');
        text.append("tools=");
        for (ToolClass toolClass : ToolClass.values()) {
            text.append(toolClass.name()).append('=').append(snapshot.bestTool(toolClass).name()).append(',');
        }
        text.append(";items=");
        for (Map.Entry<ItemKey, Integer> item : new TreeMap<>(snapshot.items()).entrySet()) {
            text.append(item.getKey().id()).append(':').append(item.getValue()).append(',');
        }
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < text.length(); i++) {
            hash ^= text.charAt(i);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    /** The goal as one canonical string; every field the planner would read differently is in it. */
    private static String describe(Goal goal) {
        StringBuilder text = new StringBuilder(switch (goal) {
            case Goal.HaveItem g -> "item:" + g.key().id() + ":" + g.count();
            case Goal.HaveToolTier g -> "tool:" + g.toolClass().name() + ":" + g.tier().name();
            case Goal.Equip g -> "equip:" + sorted(g.slots().entrySet(), e -> e.getKey().name() + "=" + e.getValue().id());
            case Goal.Stockpile g -> "stock:" + g.key().id() + ":" + g.count() + ":" + g.dimension() + ":"
                    + g.container().encode();
            case Goal.Build g -> "build:" + g.blueprint() + ":" + g.dimension() + ":" + g.anchor().encode();
            case Goal.Reach g -> "reach:" + g.dimension() + ":" + g.pos().encode() + ":" + g.radius();
            case Goal.Custom g -> "custom:" + g.kind() + ":" + sorted(g.params().entrySet(), e -> e.getKey() + "=" + e.getValue());
        });
        if (goal instanceof Goal.HaveItem have && have.check() != null) {
            ComponentCheck check = have.check();
            text.append(":check:");
            for (Map.Entry<String, Integer> e : new TreeMap<>(check.minEnchantments()).entrySet()) {
                text.append(e.getKey()).append('>').append(e.getValue()).append(',');
            }
            if (!check.potion().isEmpty()) text.append("potion=").append(check.potion());
        }
        return text.toString();
    }

    private interface Named<E> {
        String name(E entry);
    }

    private static <E> String sorted(Iterable<E> entries, Named<E> name) {
        TreeSet<String> names = new TreeSet<>();
        for (E entry : entries) names.add(name.name(entry));
        return names.toString();
    }
}
