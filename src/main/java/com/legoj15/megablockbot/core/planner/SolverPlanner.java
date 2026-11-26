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
import com.legoj15.megablockbot.core.plan.Profile;
import com.legoj15.megablockbot.core.snapshot.ContainerSight;
import com.legoj15.megablockbot.core.snapshot.Snapshot;
import com.legoj15.megablockbot.core.snapshot.StackFacts;
import com.legoj15.megablockbot.core.task.Failure;
import com.legoj15.solver.CostModel;
import com.legoj15.solver.PlannedStep;
import com.legoj15.solver.RecipeGraphSolver;
import com.legoj15.solver.SlotLayout;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
 *
 * <h2>How an item is planned</h2>
 * The goal "carry {@code count} of {@code key}" is handed to the solver as a demand: a recipe that consumes
 * {@code count} of {@code key}. The solver's shortfall then lists what must come from outside, crediting the stock
 * first, so units already in stock are never spent to make more of the goal item, and an item with a recipe is
 * gathered when its cheapest source is no dearer than crafting it (brief P12b items 3 and 11). The planner then
 * <ol>
 *   <li>commits everything that consumes stock before the shortfall is trusted: the tool prerequisite of a mining
 *       source, the whole of a trade (its payment is planned, the trade leaf emitted, the payment spent), and a
 *       strategy's nodes. After a round that committed anything the shortfall is taken again, until a round commits
 *       none (P12b item 1, P12c item 1: the tool chain and the payments eat stock the first list counted);</li>
 *   <li>emits one gather leaf per remaining listed item, the goal of each computed from the working stock at that
 *       moment;</li>
 *   <li>solves the demand from the stock and emits its crafts; a solve that still reports missing items takes the
 *       shortfall again against the stock as it stands (it knows the amounts and can craft intermediates), gathers
 *       that and solves again, a bounded number of times.</li>
 * </ol>
 * A gather that cannot be planned although the cost model priced it (its tool's recipe needs an ingredient nothing
 * yields, say) is set aside, for this plan only, and the shortfall is taken again: the solver then crafts an item
 * that has a recipe. Every loop is bounded and ends in an {@code Unplannable(Internal)} that says which bound it hit.
 *
 * <h2>Leaf params</h2>
 * Every value is a string; {@code taskKind} says which task reads them.
 * <ul>
 *   <li>{@code mine}: {@code item}, {@code count} (how many to bring in), {@code block}; {@code toolClass} and
 *       {@code minTier} only when the source requires the tool (the plan then provides it); {@code minY},
 *       {@code maxY}, {@code bestY} for an ore.</li>
 *   <li>{@code harvest}: {@code item}, {@code count}, {@code block}.</li>
 *   <li>{@code kill}: {@code item}, {@code count}, {@code entity}, {@code playerKillOnly} ({@code true} or
 *       {@code false}).</li>
 *   <li>{@code fish}: {@code item}, {@code count}, {@code table}.</li>
 *   <li>{@code trade}: {@code item}, {@code count} (the trades: one item each), {@code profession},
 *       {@code level}, {@code cost} (what one trade charges, {@code item=count,item=count} in id order) and
 *       {@code chance} (the share of offers that are this one). The plan for the payment, cost times count,
 *       comes before the leaf.</li>
 *   <li>craft (the recipe's {@code kind}, {@code craft} for the grid): {@code recipe} (id), {@code times},
 *       {@code station} (the station item or empty) and {@code layout}, the item the solver chose for each slot of
 *       each craft, in the form {@link CraftLayout} documents, so a task follows it instead of re-deciding.</li>
 *   <li>{@code equip}: none; the goal names the slots. Equipping leaves the working stock as it is: the snapshot
 *       counts worn items.</li>
 *   <li>{@code deposit}: {@code item}, {@code container}, {@code dimension}, and {@code count}, which is how many
 *       to move now: the goal asked for, minus what the container is known to hold. The node's goal is the
 *       {@link Goal.Stockpile} itself and carries the total the container should hold. The moved items leave the
 *       working stock.</li>
 *   <li>{@code navigate}: {@code dimension}, {@code pos}, {@code radius}.</li>
 * </ul>
 */
public final class SolverPlanner implements Planner {
    /** Solver budgets, SprawlCrafting's pins (docs/handoff/solver-core.md): depth and attempt caps per solve. */
    static final int SOLVER_DEPTH = 32;
    static final int SOLVER_ATTEMPTS = 4096;
    /** A tool prerequisite chain deeper than this is unplannable (brief P12). */
    static final int MAX_TOOL_DEPTH = 6;
    /** A strategy that re-enters {@code subPlan} past this depth is looping; the plan stops instead of recursing. */
    static final int MAX_EXPAND_DEPTH = 16;
    /** Rounds of "plan the tool prerequisites, take the shortfall again" before the gather list is called unsettled. */
    static final int MAX_SETTLE_ROUNDS = 12;
    /** Times a solve that reports missing items is repeated after gathering them. */
    static final int MAX_RESOLVE_ROUNDS = 6;
    /** Gather-and-solve passes one item plan may need before it is called not converging. */
    static final int MAX_PASSES = 8;
    /** Rounds a trade's payment may need when planning one charged item eats another's stock. */
    static final int MAX_PAYMENT_ROUNDS = 4;
    /** The recipe closure a fingerprint walks stops growing here, so a hostile recipe set cannot make it slow. */
    static final int MAX_CLOSURE_ITEMS = 2048;

    /** The solver's id for the demand recipe; no such recipe is in any book, and no step of it is ever emitted. */
    private static final String DEMAND_ID = "megablockbot:planner/demand";
    private static final ItemKey DEMAND_RESULT = new ItemKey("megablockbot:demand");

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
        Run run = new Run(request);
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

    /**
     * How one leaf item is to be obtained: a strategy's nodes (done), or the source to gather from (emitted later).
     *
     * @param committed   whether deciding put nodes in the plan (a tool prerequisite, a whole trade, a strategy's
     *                    result) and so changed the working stock
     * @param sourceIndex the position in cost order of the source whose gather is still to be emitted, or -1 when
     *                    nothing is left to emit (a strategy's nodes, or a trade committed whole)
     * @param skipped     the failure of the first source that could not be used, so that a gather that fails later
     *                    still reports the cheapest source's failure; null when the cheapest source was usable
     */
    private record Decision(List<NodeSpec> nodes, boolean committed, int sourceIndex, Failure skipped,
                            Failure failure) {
        static Decision fail(Failure failure) { return new Decision(List.of(), false, -1, null, failure); }
    }

    /**
     * The working stock, the notes and the sources set aside at one moment, so an attempt that is not kept can be
     * undone: a source set aside while a losing strategy's sub-plan was tried must not be missing from the real plan.
     */
    private record State(TreeMap<ItemKey, Long> stock, List<String> notes, TreeSet<String> setAside) {}

    /** The demand "carry {@code count} of {@code key}", as the one recipe the solver is asked to make (item 11). */
    private record DemandRecipe(ItemKey key, int count) implements RecipeGraphSolver.RecipeInfo<String, ItemKey> {
        @Override
        public String id() {
            return DEMAND_ID;
        }

        @Override
        public List<List<ItemKey>> ingredientSlots() {
            return List.of(List.of(key));
        }

        @Override
        public int ingredientSlotCount(int slot) {
            return count;
        }

        @Override
        public ItemKey result() {
            return DEMAND_RESULT;
        }

        @Override
        public int resultCount() {
            return 1;
        }
    }

    private final class Run implements PlanContext {
        private final PlanRequest request;
        private final Snapshot snapshot;
        private final Exclusions exclusions;
        /**
         * Sources this plan gave up on after pricing them: a gather that could not be planned for a reason the cost
         * model cannot see. Part of {@link State}; the cost model and the solver are rebuilt when it changes.
         */
        private final TreeSet<String> setAside = new TreeSet<>();
        /** The cost model and the solver over it; rebuilt, never mutated, because a solver keeps its derived costs. */
        private GatherCosts costs;
        private RecipeGraphSolver<String, ItemKey> solver;
        /** What the body will hold once the nodes planned so far have run; starts as the snapshot's inventory. */
        private final TreeMap<ItemKey, Long> stock = new TreeMap<>();
        private final Map<ItemKey, Long> stockView = Collections.unmodifiableMap(stock);
        private final List<String> notes = new ArrayList<>();
        private final TreeSet<String> toolPath = new TreeSet<>();
        /** Items whose trade payment is being planned: a payment that needs the item itself is a cycle. */
        private final TreeSet<ItemKey> paying = new TreeSet<>();
        /**
         * One solver-side wrapper per recipe for the whole run: the solver's quantity path matches recipes by
         * identity, so a producers function that built new objects on every call would defeat it.
         */
        private final Map<String, SolverRecipe> wrapped = new HashMap<>();
        private int toolDepth;
        private int expandDepth;

        Run(PlanRequest request) {
            this.request = request;
            this.snapshot = request.snapshot();
            this.exclusions = request.exclusions();
            buildModels();
            for (Map.Entry<ItemKey, Integer> item : snapshot.items().entrySet()) {
                stock.put(item.getKey(), (long) item.getValue());
            }
        }

        /**
         * A fresh cost model and solver over the request's exclusions and the sources set aside: the solver keeps
         * derived unit costs for its lifetime, and this run's model carries this request's best tools.
         */
        private void buildModels() {
            TreeSet<String> excluded = new TreeSet<>(exclusions.sources());
            excluded.addAll(setAside);
            Exclusions effective = new Exclusions(excluded, exclusions.blocks(), exclusions.areas());
            // A trade's price includes what it charges, priced by a second solver whose model charges nothing for
            // payments: the solver that owns a cost model must not be asked for a unit cost while it derives one.
            RecipeGraphSolver<String, ItemKey> pricer =
                    newSolver(new GatherCosts(recipes, index, config, effective, snapshot));
            this.costs = new GatherCosts(recipes, index, config, effective, snapshot, pricer::unitCost);
            this.solver = newSolver(costs);
        }

        private RecipeGraphSolver<String, ItemKey> newSolver(CostModel<String, ItemKey> model) {
            return new RecipeGraphSolver<>(
                    item -> recipes.producers(item).stream()
                            .map(def -> wrapped.computeIfAbsent(def.id(), id -> SolverRecipe.of(def))).toList(),
                    recipes::remainder, SOLVER_DEPTH, SOLVER_ATTEMPTS, RecipeGraphSolver.DEFAULT_MAX_STEPS, model);
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

        // ─── The item plan ───────────────────────────────────────────────────────────────────────

        /**
         * The item plan for (key, count) against the working stock (brief P12): the gather leaves of the demand's
         * shortfall, then the crafts that assemble the item. An item no recipe makes is one gather. A pass that does
         * not raise the stock of the goal item ends the plan with a reason rather than looping (item 11).
         */
        Step itemPlan(ItemKey key, int count) {
            if (held(key) >= count) return Step.ok(List.of());
            if (recipes.producers(key).isEmpty()) return leaf(key, count - held(key));

            List<NodeSpec> nodes = new ArrayList<>();
            for (int pass = 0; pass < MAX_PASSES && held(key) < count; pass++) {
                long before = held(key);
                DemandRecipe demand = new DemandRecipe(key, count);
                Step gathers = gatherShortfall(key, demand);
                if (gathers.failure() != null) return gathers;
                nodes.addAll(gathers.nodes());
                Step crafts = craftDemand(key, demand);
                if (crafts.failure() != null) return crafts;
                nodes.addAll(crafts.nodes());
                if (held(key) < count && held(key) <= before) {
                    return Step.fail(internal("planning did not converge on " + key.id()
                            + ": a pass left the stock at " + held(key) + " of " + count));
                }
            }
            if (held(key) < count) {
                return Step.fail(internal("planning did not converge on " + key.id() + " in " + MAX_PASSES + " passes"));
            }
            return Step.ok(nodes);
        }

        /**
         * The gather leaves of the demand's shortfall (brief P12b item 1, P12c item 1). Each round takes the shortfall
         * from the working stock and decides how every listed item is obtained; deciding commits a tool
         * prerequisite, a whole trade (payment included) or a strategy's result, and a round that committed anything
         * is followed by a fresh shortfall, because those eat and leave stock the list did not count. A round that
         * committed nothing emits the gathers, each with a goal computed from the stock at that moment.
         *
         * <p>An item that has a recipe but whose every source fails to plan is not lost yet: its sources are set
         * aside and the shortfall is taken again, so the solver crafts it (P12c item 2). Should that not get
         * anywhere either, the failure reported is the gather's. The rounds are bounded.
         */
        Step gatherShortfall(ItemKey key, DemandRecipe demand) {
            List<NodeSpec> nodes = new ArrayList<>();
            Failure gatherFailure = null;
            for (int round = 0; round <= MAX_SETTLE_ROUNDS; round++) {
                RecipeGraphSolver.ShortfallResult<ItemKey> shortfall = solver.shortfallLong(demand, stock);
                if (shortfall.saturated()) {
                    return Step.fail(internal("the shortfall of " + key.id() + " overflows what a plan can count"));
                }
                if (shortfall.approximate()) {
                    note("the gather list for " + key.id() + " may be incomplete: the solver flagged its shortfall approximate");
                }
                TreeMap<ItemKey, Long> demands = new TreeMap<>();
                for (Map.Entry<ItemKey, Long> entry : shortfall.demandsLong().entrySet()) {
                    if (entry.getValue() > 0) demands.put(entry.getKey(), entry.getValue());
                }
                if (demands.isEmpty()) return Step.ok(nodes);
                if (round == MAX_SETTLE_ROUNDS) {
                    return Step.fail(internal("the tool prerequisites of " + key.id() + " did not settle in "
                            + MAX_SETTLE_ROUNDS + " rounds"));
                }

                boolean committed = false;
                boolean setAsideAny = false;
                Map<ItemKey, Decision> gathers = new TreeMap<>();
                for (Map.Entry<ItemKey, Long> entry : demands.entrySet()) {
                    Decision decision = decide(entry.getKey(), entry.getValue());
                    if (decision.failure() != null) {
                        if (!giveUp(entry.getKey(), decision.failure())) {
                            return Step.fail(gatherFailure != null ? gatherFailure : decision.failure());
                        }
                        if (gatherFailure == null) gatherFailure = decision.failure();
                        setAsideAny = true;
                        break;
                    }
                    nodes.addAll(decision.nodes());
                    committed |= decision.committed();
                    if (decision.sourceIndex() >= 0) gathers.put(entry.getKey(), decision);
                }
                if (setAsideAny || committed) continue;
                for (Map.Entry<ItemKey, Decision> gather : gathers.entrySet()) {
                    Step emitted = emitGather(gather.getKey(), demands.get(gather.getKey()), gather.getValue());
                    if (emitted.failure() != null) {
                        if (!giveUp(gather.getKey(), emitted.failure())) {
                            return gatherFailure != null ? Step.fail(gatherFailure) : emitted;
                        }
                        if (gatherFailure == null) gatherFailure = emitted.failure();
                        setAsideAny = true;
                        break;
                    }
                    nodes.addAll(emitted.nodes());
                }
                if (setAsideAny) continue;
                return Step.ok(nodes);
            }
            // Not reached: the last round either finds nothing to gather or fails. Said as a failure, not a throw,
            // so that a planner bug still comes back as an answer.
            return Step.fail(internal("the gather rounds of " + key.id() + " ended without an answer"));
        }

        /**
         * The crafts that assemble the demand from the working stock (brief P12 step 4). A solve that reports missing
         * items after the gather (the shortfall may list too little, or a payment planned late ate stock; brief P12b
         * item 11c, P12c item 1) takes the proper shortfall again against the stock as it stands, which knows the
         * amounts and can craft intermediates, gathers that and solves again, a bounded number of times. Only when the
         * shortfall lists nothing (the solver flagged it approximate) are the solve's own missing items gathered, a
         * unit each; a solve that names nothing with a source ends the plan.
         */
        Step craftDemand(ItemKey key, DemandRecipe demand) {
            List<NodeSpec> nodes = new ArrayList<>();
            for (int attempt = 0; ; attempt++) {
                RecipeGraphSolver.Result<String, ItemKey> result = solver.solveLong(demand, stock);
                if (result instanceof RecipeGraphSolver.Result.Success<String, ItemKey> success) {
                    Step crafts = craftNodes(success);
                    if (crafts.failure() != null) return crafts;
                    nodes.addAll(crafts.nodes());
                    return Step.ok(nodes);
                }
                String detail = failureDetail(key, result);
                if (!(result instanceof RecipeGraphSolver.Result.Failure<String, ItemKey> failure)
                        || failure.budgetExceeded() || failure.tooLarge() || attempt >= MAX_RESOLVE_ROUNDS) {
                    return Step.fail(internal(detail));
                }
                Step gathered = gatherShortfall(key, demand);
                if (gathered.failure() != null) return gathered;
                if (gathered.nodes().isEmpty()) {
                    // The shortfall lists nothing although the solve is short (the solver flags such a list
                    // approximate): the solve's own missing items, with no amounts, are all there is to go on.
                    gathered = gatherMissing(failure.missing());
                    if (gathered.failure() != null) return gathered;
                    if (gathered.nodes().isEmpty()) return Step.fail(internal(detail));
                }
                nodes.addAll(gathered.nodes());
            }
        }

        /** One unit of each missing item that has a source, through the same leaf rules as any gather. */
        private Step gatherMissing(Set<ItemKey> missing) {
            List<NodeSpec> nodes = new ArrayList<>();
            for (ItemKey item : new TreeSet<>(missing)) {
                if (costs.ranked(item).isEmpty()) continue;
                Step gathered = leaf(item, 1);
                if (gathered.failure() != null) return gathered;
                nodes.addAll(gathered.nodes());
            }
            return Step.ok(nodes);
        }

        /** The solver's steps as craft leaves, advancing the working stock through each (the demand step is not one). */
        private Step craftNodes(RecipeGraphSolver.Result.Success<String, ItemKey> success) {
            List<NodeSpec> nodes = new ArrayList<>();
            List<PlannedStep<String>> steps = success.steps();
            List<List<SlotLayout<ItemKey>>> layouts = success.layouts();
            for (int i = 0; i < steps.size(); i++) {
                PlannedStep<String> step = steps.get(i);
                if (DEMAND_ID.equals(step.recipeId())) continue; // states the demand; nothing to run
                RecipeDef recipe = recipes.byId(step.recipeId()).orElse(null);
                if (recipe == null) {
                    return Step.fail(internal("the solver planned unknown recipe " + step.recipeId()));
                }
                List<SlotLayout<ItemKey>> stepLayouts =
                        layouts == null || i >= layouts.size() ? List.of() : layouts.get(i);
                Failure broken = advance(recipe, step, stepLayouts);
                if (broken != null) return Step.fail(broken);
                if (!RecipeDef.CRAFT.equals(recipe.kind())) {
                    note("recipe " + recipe.id() + " runs as task kind '" + recipe.kind()
                            + "', which no task implements yet");
                }
                long after = held(recipe.result());
                if (after > Integer.MAX_VALUE) {
                    return Step.fail(internal("the stock of " + recipe.result().id() + " exceeds what a goal can count"));
                }
                Map<String, String> params = new TreeMap<>();
                params.put("recipe", recipe.id());
                params.put("times", Long.toString(step.crafts()));
                params.put("station", recipe.station() == null ? "" : recipe.station().id());
                params.put("layout", CraftLayout.encode(stepLayouts));
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
         *
         * @return the failure when the step does not fit the stock (a planner or solver bug), else null
         */
        private Failure advance(RecipeDef recipe, PlannedStep<String> step, List<SlotLayout<ItemKey>> layouts) {
            if (layouts.isEmpty()) {
                return internal("the solver returned step " + step.recipeId() + " without a layout");
            }
            TreeMap<ItemKey, Long> draws = new TreeMap<>();
            for (SlotLayout<ItemKey> run : layouts) {
                for (ItemKey item : run.items()) draws.merge(item, run.crafts(), Long::sum);
            }
            for (Map.Entry<ItemKey, Long> draw : draws.entrySet()) {
                if (held(draw.getKey()) < draw.getValue()) {
                    return internal("step " + step.recipeId() + " draws " + draw.getValue() + " of "
                            + draw.getKey().id() + " but the working stock holds " + held(draw.getKey()));
                }
            }
            for (Map.Entry<ItemKey, Long> draw : draws.entrySet()) {
                removeStock(draw.getKey(), draw.getValue());
                ItemKey remainder = recipes.remainder(draw.getKey());
                if (remainder != null) addStock(remainder, draw.getValue());
            }
            addStock(recipe.result(), step.crafts() * recipe.resultCount());
            return null;
        }

        // ─── Obtaining one leaf item ─────────────────────────────────────────────────────────────

        /** Obtains one leaf item now (an item no recipe makes): decides how, then emits the gather if that is how. */
        Step leaf(ItemKey item, long demand) {
            Decision decision = decide(item, demand);
            if (decision.failure() != null) return Step.fail(decision.failure());
            List<NodeSpec> nodes = new ArrayList<>(decision.nodes());
            if (decision.sourceIndex() >= 0) {
                Step emitted = emitGather(item, demand, decision);
                if (emitted.failure() != null) return emitted;
                nodes.addAll(emitted.nodes());
            }
            return Step.ok(nodes);
        }

        /**
         * How a leaf item is obtained: a strategy's proposal, or a gather from the first source (in cost order)
         * that can be prepared. A source that cannot be used falls through to the next; when none can be, the failure
         * reported is the cheapest source's. The proposals and the gather are compared by what the whole demand
         * costs, the gather's preparation included, and each is tried from the stock as it was before any of them
         * (brief P12b items 2 and 4); only the winner's effect on the working stock stays.
         *
         * <p>Preparing a mining source is planning its tool prerequisite; the gather itself is emitted later. Preparing
         * a trade is the whole of it: its payment is planned and spent and the trade leaf emitted, so that what the
         * trade eats is out of the stock before the shortfall is trusted (P12c item 1). Committing nodes (a tool
         * prerequisite, a whole trade, a strategy's nodes) is what makes {@link Decision#committed()} true.
         */
        private Decision decide(ItemKey item, long demand) {
            long absolute = held(item) + demand;
            if (absolute > Integer.MAX_VALUE) {
                return Decision.fail(internal("the demand for " + item.id() + " exceeds what a goal can count"));
            }
            State base = mark();
            List<Source> ranked = costs.ranked(item);

            Failure gatherFailure = ranked.isEmpty() ? noSource(item) : null;
            int chosen = -1;
            boolean emitted = false;
            List<NodeSpec> prerequisite = List.of();
            State afterPrerequisite = null;
            long gatherCost = CostModel.UNOBTAINABLE;
            for (int i = 0; i < ranked.size() && chosen < 0; i++) {
                Source source = ranked.get(i);
                boolean trade = source instanceof Source.Trade;
                Step ready = trade ? planSource(item, demand, source) : prerequisite(source);
                if (ready.failure() == null) {
                    chosen = i;
                    emitted = trade;
                    prerequisite = ready.nodes();
                    afterPrerequisite = mark();
                    if (!strategies.isEmpty()) gatherCost = gatherTotal(source, demand, !prerequisite.isEmpty());
                } else if (gatherFailure == null) {
                    gatherFailure = ready.failure();
                }
                restore(base);
            }

            Candidate winner = null;
            Goal.HaveItem offered = new Goal.HaveItem(item, (int) absolute);
            for (Strategy strategy : strategies) {
                Optional<Strategy.Proposal> proposal = strategy.propose(offered, this);
                if (proposal.isPresent() && !proposal.get().nodes().isEmpty()
                        && (winner == null || better(proposal.get(), strategy.id(), winner))) {
                    winner = new Candidate(strategy.id(), proposal.get(), mark());
                }
                restore(base);
            }

            if (winner != null && (chosen < 0 || winner.proposal().cost() < gatherCost)) {
                restore(winner.state());
                winner.proposal().notes().forEach(this::note);
                creditTo(item, absolute);
                return new Decision(winner.proposal().nodes(), true, -1, null, null);
            }
            if (chosen < 0) {
                if (ranked.isEmpty()) note("no usable source for " + item.id() + ": " + noSourceDetail(item));
                return Decision.fail(gatherFailure);
            }
            restore(afterPrerequisite);
            noteSkippedSources(item, ranked.get(chosen));
            return new Decision(prerequisite, !prerequisite.isEmpty(), emitted ? -1 : chosen, gatherFailure, null);
        }

        /**
         * What gathering the whole demand costs through the source, its tool prerequisite included: every unit at the
         * price with the tool in hand, and the tool once. (The per-unit price the cost model orders sources by also
         * charges a missing tier on every unit; counting that as well as the tool would price the tool twice.)
         */
        private long gatherTotal(Source source, long demand, boolean needsTool) {
            long total = satMul(costs.costOfCarrying(source), demand);
            if (needsTool && source instanceof Source.MineBlock mine && mine.toolClass() != null) {
                ItemKey tool = config.toolItem(mine.toolClass(), mine.minTier());
                if (tool != null) {
                    long price = solver.unitCost(tool);
                    if (price < CostModel.UNOBTAINABLE) total = satAdd(total, price);
                }
            }
            return total;
        }

        private boolean better(Strategy.Proposal candidate, String id, Candidate winner) {
            return candidate.cost() < winner.proposal().cost()
                    || (candidate.cost() == winner.proposal().cost() && id.compareTo(winner.id()) < 0);
        }

        /** A proposal that may win, with the working stock its sub-plans left behind. */
        private record Candidate(String id, Strategy.Proposal proposal, State state) {}

        /**
         * Emits the gather of {@code demand} of the item from the source the decision chose or, when that one cannot
         * be planned after all (its trade cannot be paid for), the next in cost order that can. The goal of the leaf
         * is computed from the working stock at this moment. When none can be planned the failure is the cheapest
         * source's.
         */
        private Step emitGather(ItemKey item, long demand, Decision decision) {
            List<Source> ranked = costs.ranked(item);
            Failure first = decision.skipped();
            for (int i = decision.sourceIndex(); i < ranked.size(); i++) {
                State before = mark();
                Step planned = planSource(item, demand, ranked.get(i));
                if (planned.failure() == null) return planned;
                if (first == null) first = planned.failure();
                restore(before);
            }
            return Step.fail(first != null ? first : noSource(item));
        }

        /**
         * The nodes that obtain {@code demand} of the item from one source, and their effect on the working stock.
         * The leaf's goal is the stock after the leaf's own effect (a trade spends its payment), which is what the
         * executor verifies when the leaf ends. On a failure the stock may be part way: the caller restores it.
         */
        private Step planSource(ItemKey item, long demand, Source source) {
            List<NodeSpec> nodes = new ArrayList<>();
            Step tool = prerequisite(source);
            if (tool.failure() != null) return tool;
            nodes.addAll(tool.nodes());
            if (source instanceof Source.Trade trade) {
                Step payment = payment(item, trade, demand);
                if (payment.failure() != null) return payment;
                nodes.addAll(payment.nodes());
                for (Map.Entry<ItemKey, Integer> charge : trade.cost().entrySet()) {
                    removeStock(charge.getKey(), (long) charge.getValue() * demand);
                }
            }
            addStock(item, demand);
            long absolute = held(item);
            if (absolute > Integer.MAX_VALUE) {
                return Step.fail(internal("the demand for " + item.id() + " exceeds what a goal can count"));
            }
            nodes.add(NodeSpec.leaf(new Goal.HaveItem(item, (int) absolute), taskKind(source),
                    gatherParams(source, demand), source.id()));
            return Step.ok(nodes);
        }

        /**
         * Sets aside every source of the item after they all failed to plan, when a recipe could still make it, and
         * rebuilds the cost model so the solver crafts it (P12c item 2: the prices could not see why they failed).
         *
         * @return whether anything was set aside, so that taking the shortfall again can lead somewhere new
         */
        private boolean giveUp(ItemKey item, Failure failure) {
            // Only a source that cannot be had is given up on; an internal bound a plan hit is not about the source.
            if (!(failure instanceof Failure.NoSource || failure instanceof Failure.MissingTool)) return false;
            if (recipes.producers(item).isEmpty()) return false;
            boolean added = false;
            for (Source source : costs.ranked(item)) added |= setAside.add(source.id());
            if (!added) return false;
            note(item.id() + ": gathering it could not be planned (" + failure.detail() + "), so it is crafted instead");
            buildModels();
            return true;
        }

        /**
         * The tool prerequisite of a source, as a group node placed before the gather, or nothing when the source
         * needs no tool or the body carries one (brief P12). A mining source that requires a tool of no known class
         * cannot be planned.
         */
        private Step prerequisite(Source source) {
            if (!(source instanceof Source.MineBlock mine) || !mine.toolRequired()) return Step.ok(List.of());
            if (mine.toolClass() == null) {
                return Step.fail(new Failure.MissingTool(null, mine.minTier(),
                        mine.id() + " needs a tool of no known class"));
            }
            if (carriedTier(mine.toolClass()).atLeast(mine.minTier())) return Step.ok(List.of());
            return toolPrerequisite(mine);
        }

        /**
         * The {@code HaveToolTier} plan as a group node placed before the gather that needs the tool (brief P12).
         * Cycles (a tool whose own chain needs the same tool) and chains deeper than {@link #MAX_TOOL_DEPTH} are
         * unplannable: the first would recurse forever, the second exceeds what a plan can hold.
         */
        Step toolPrerequisite(Source.MineBlock mine) {
            ToolClass toolClass = mine.toolClass();
            ToolTier required = mine.minTier();
            ItemKey toolItem = config.toolItem(toolClass, required);
            String named = toolItem == null ? toolClass + " at " + required : toolItem.id();
            String key = PlannerConfig.toolKey(toolClass, required);
            if (toolPath.contains(key)) {
                return Step.fail(new Failure.MissingTool(toolClass, required,
                        "planning the tool " + named + " needs itself: the tool chain is cyclic"));
            }
            if (toolDepth >= MAX_TOOL_DEPTH) {
                return Step.fail(new Failure.MissingTool(toolClass, required,
                        "the tool prerequisite chain for " + named + " is deeper than " + MAX_TOOL_DEPTH));
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

        /**
         * The item plan for what a trade charges, cost times the trades needed, planned like any other goal (brief
         * P12b item 6). Planning one charged item can eat another's stock, so the check repeats, bounded. A payment
         * that needs the traded item itself is a cycle and makes the trade unusable.
         */
        private Step payment(ItemKey item, Source.Trade trade, long trades) {
            if (trade.cost().isEmpty()) return Step.ok(List.of());
            if (!paying.add(item)) {
                return Step.fail(new Failure.NoSource(item, trade.id(), "the payment for " + trade.id()
                        + " needs " + item.id() + " itself"));
            }
            try {
                TreeMap<ItemKey, Long> owed = new TreeMap<>();
                for (Map.Entry<ItemKey, Integer> charge : trade.cost().entrySet()) {
                    long total = (long) charge.getValue() * trades;
                    if (total > Integer.MAX_VALUE) {
                        return Step.fail(internal("the payment for " + trade.id() + " exceeds what a goal can count"));
                    }
                    if (total > 0) owed.put(charge.getKey(), total);
                }
                List<NodeSpec> nodes = new ArrayList<>();
                for (int round = 0; round < MAX_PAYMENT_ROUNDS; round++) {
                    boolean planned = false;
                    for (Map.Entry<ItemKey, Long> charge : owed.entrySet()) {
                        if (held(charge.getKey()) >= charge.getValue()) continue;
                        Step step = expand(new Goal.HaveItem(charge.getKey(), (int) (long) charge.getValue()));
                        if (step.failure() != null) return step;
                        nodes.addAll(step.nodes());
                        planned = true;
                    }
                    if (!planned) return Step.ok(nodes);
                }
                return Step.fail(internal("the payment for " + trade.id() + " did not settle in "
                        + MAX_PAYMENT_ROUNDS + " rounds"));
            } finally {
                paying.remove(item);
            }
        }

        // ─── Goals that go to strategies ─────────────────────────────────────────────────────────

        /**
         * Asks every strategy, each from the working stock as it was before any of them; the cheapest proposal wins,
         * ties on the strategy id (design 4.4), and only the winner's effect on the stock stays. A winning proposal
         * for an item goal is credited to the stock like a gather is.
         */
        Step strategiesFor(Goal goal) {
            State base = mark();
            Candidate winner = null;
            for (Strategy strategy : strategies) {
                Optional<Strategy.Proposal> proposal = strategy.propose(goal, this);
                if (proposal.isPresent() && !proposal.get().nodes().isEmpty()
                        && (winner == null || better(proposal.get(), strategy.id(), winner))) {
                    winner = new Candidate(strategy.id(), proposal.get(), mark());
                }
                restore(base);
            }
            if (winner == null) {
                if (goal instanceof Goal.HaveItem have) {
                    return Step.fail(new Failure.NoSource(have.key(), "",
                            "no strategy applies to " + describe(goal) + "; a strategy is needed for it"));
                }
                return Step.fail(internal("no strategy plans " + describe(goal)));
            }
            restore(winner.state());
            winner.proposal().notes().forEach(this::note);
            if (goal instanceof Goal.HaveItem have) creditTo(have.key(), have.count());
            return Step.ok(winner.proposal().nodes());
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

        /**
         * An item plan for what the container is not known to hold already, then one deposit leaf (brief P12). The
         * leaf's {@code count} is that same number, how many to move; its goal is the whole stockpile goal.
         */
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
            params.put("count", Long.toString(need));
            params.put("container", goal.container().encode());
            params.put("dimension", goal.dimension());
            nodes.add(NodeSpec.leaf(goal, "deposit", params, ""));
            // The moved items are in the container, not on the body: a plan that asks for the item again (a
            // strategy's next step) must gather it again.
            removeStock(goal.key(), need);
            return Step.ok(nodes);
        }

        private static NodeSpec navigateLeaf(Goal.Reach goal) {
            Map<String, String> params = new TreeMap<>();
            params.put("dimension", goal.dimension());
            params.put("pos", goal.pos().encode());
            params.put("radius", Integer.toString(goal.radius()));
            return NodeSpec.leaf(goal, "navigate", params, "");
        }

        // ─── Gather leaves ───────────────────────────────────────────────────────────────────────

        /** What the task for the source's kind needs, as strings (see the class javadoc). */
        private Map<String, String> gatherParams(Source source, long demand) {
            Map<String, String> params = new TreeMap<>();
            params.put("item", source.item().id());
            params.put("count", Long.toString(demand));
            if (source instanceof Source.MineBlock mine) {
                params.put("block", mine.block());
                // The tool is written only when the plan provides it: the cost table charges a missing tier on a
                // source that merely mines faster with one, but a leaf that says it needs a tool must get one.
                if (mine.toolRequired() && mine.toolClass() != null) {
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
                params.put("playerKillOnly", Boolean.toString(kill.playerKillOnly()));
            } else if (source instanceof Source.Fishing fishing) {
                params.put("table", fishing.table());
            } else if (source instanceof Source.Trade trade) {
                params.put("profession", trade.profession());
                params.put("level", Integer.toString(trade.level()));
                params.put("cost", encodeCost(trade.cost()));
                params.put("chance", Double.toString(trade.chance()));
            } else if (source instanceof Source.ChestLoot chest) {
                params.put("table", chest.table());
            }
            return params;
        }

        private static String encodeCost(Map<ItemKey, Integer> cost) {
            StringBuilder text = new StringBuilder();
            for (Map.Entry<ItemKey, Integer> charge : new TreeMap<>(cost).entrySet()) {
                if (text.length() > 0) text.append(',');
                text.append(charge.getKey().id()).append('=').append(charge.getValue());
            }
            return text.toString();
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

        private Failure noSource(ItemKey item) {
            return new Failure.NoSource(item, "", noSourceDetail(item));
        }

        /** Why the leaf has no usable source, saying so when sources exist but are all excluded or unusable. */
        private String noSourceDetail(ItemKey item) {
            List<Source> sources = index.sources(item);
            if (sources.isEmpty()) return "no acquisition source is known for it";
            StringBuilder detail = new StringBuilder("every source is excluded or unusable:");
            for (Source source : sources) {
                detail.append(' ').append(source.id());
                if (exclusions.sources().contains(source.id())) detail.append(" excluded");
                else if (setAside.contains(source.id())) detail.append(" set aside, it could not be planned");
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

        // ─── Stock and notes ─────────────────────────────────────────────────────────────────────

        private void note(String line) {
            if (!notes.contains(line)) notes.add(line);
        }

        private long held(ItemKey item) {
            return stock.getOrDefault(item, 0L);
        }

        private void addStock(ItemKey item, long count) {
            stock.merge(item, count, Long::sum);
        }

        private void removeStock(ItemKey item, long count) {
            long have = held(item) - count;
            if (have > 0) stock.put(item, have);
            else stock.remove(item);
        }

        /** Brings the stock of an item up to {@code count} when a strategy's nodes did not account for it already. */
        private void creditTo(ItemKey item, long count) {
            long have = held(item);
            if (have < count) addStock(item, count - have);
        }

        private State mark() {
            return new State(new TreeMap<>(stock), new ArrayList<>(notes), new TreeSet<>(setAside));
        }

        private void restore(State state) {
            stock.clear();
            stock.putAll(state.stock());
            notes.clear();
            notes.addAll(state.notes());
            if (!setAside.equals(state.setAside())) {
                setAside.clear();
                setAside.addAll(state.setAside());
                buildModels();
            }
        }

        private static Failure.Internal internal(String detail) {
            return new Failure.Internal(new IllegalStateException("planner: " + detail), detail);
        }

        // ─── PlanContext: what a strategy sees ───────────────────────────────────────────────────

        @Override
        public Snapshot snapshot() {
            return snapshot;
        }

        @Override
        public Profile profile() {
            return request.profile();
        }

        @Override
        public Map<String, Map<String, String>> checkpoints() {
            return request.checkpoints();
        }

        @Override
        public Map<ItemKey, Long> stock() {
            return stockView;
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
            State before = mark();
            Step step = expand(goal);
            if (step.failure() != null) {
                restore(before);
                return new PlanOutcome.Unplannable(step.failure());
            }
            return new PlanOutcome.Planned(finish(goal, step.nodes(), this));
        }
    }

    private static long satMul(long a, long b) {
        if (a == 0 || b == 0) return 0;
        return a > Long.MAX_VALUE / b ? Long.MAX_VALUE : a * b;
    }

    private static long satAdd(long a, long b) {
        long sum = a + b;
        return sum < 0 ? Long.MAX_VALUE : sum;
    }

    // ─── Input fingerprint (brief P12 and P12b item 8; design 3.4) ──────────────────────────────

    /**
     * A stable 64-bit hash of what the plan depends on (design 3.4): the goal, the dimension, the best tools, the
     * counts of the items in the goal's recipe closure (every item reachable through recipe ingredients and
     * stations, the tools the sources it can be gathered from require, and what a trade charges) and, for a goal
     * the planner cannot see into (a build or a custom goal), of the whole inventory; and what {@code Stockpile}
     * reads (the container's known contents), {@code Equip} reads (what is worn) and a goal with a component check reads
 * (the facts of the stacks of its item that the body holds). Position, health, hunger, game
     * time and the counts of items the plan never reads are not part of it, so an unrelated pickup keeps the plan
     * current. Stable across JVMs: FNV-1a over a canonical text, no {@code hashCode} (whose 32 bits collide sooner
     * than needed here).
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
        boolean everything = goal instanceof Goal.Build || goal instanceof Goal.Custom;
        TreeSet<ItemKey> read = everything ? new TreeSet<>(snapshot.items().keySet()) : relevantItems(goal);
        for (ItemKey item : read) {
            int count = snapshot.count(item);
            if (count > 0) text.append(item.id()).append(':').append(count).append(',');
        }
        if (goal instanceof Goal.HaveItem have && have.check() != null) {
            // A checked goal is decided on the facts of the stacks held (which enchantments the book carries), which
            // the item counts above do not say.
            List<String> facts = new ArrayList<>();
            for (StackFacts stack : snapshot.stackFacts().getOrDefault(have.key(), List.of())) {
                facts.add(stack.count() + "|" + new TreeMap<>(stack.enchantments()) + "|" + stack.potion() + "|"
                        + new TreeMap<>(stack.extra()));
            }
            Collections.sort(facts);
            text.append(";facts=").append(facts);
        }
        if (goal instanceof Goal.Equip equip) {
            text.append(";worn=");
            for (Map.Entry<EquipSlot, ItemKey> slot : new TreeMap<>(equip.slots()).entrySet()) {
                ItemKey worn = snapshot.equipped().get(slot.getKey());
                text.append(slot.getKey().name()).append('=').append(worn == null ? "" : worn.id()).append(',');
            }
        }
        if (goal instanceof Goal.Stockpile stockpile) {
            ContainerSight sight = snapshot.containerAt(stockpile.container());
            text.append(";box=");
            if (sight == null) text.append("unseen");
            else if (!sight.contentsKnown()) text.append("unknown");
            else text.append(sight.count(stockpile.key()));
        }
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < text.length(); i++) {
            hash ^= text.charAt(i);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    /**
     * The items a plan for the goal reads the stock of: the goal's items and everything their recipes, stations,
     * gather sources' tools and trade payments lead to. Walked over the recipe book and the index whole (not the
     * request's exclusions, which the fingerprint does not see), up to {@link #MAX_CLOSURE_ITEMS} items.
     */
    private TreeSet<ItemKey> relevantItems(Goal goal) {
        ArrayDeque<ItemKey> queue = new ArrayDeque<>();
        switch (goal) {
            case Goal.HaveItem g -> queue.add(g.key());
            case Goal.HaveToolTier g -> {
                ItemKey tool = config.toolItem(g.toolClass(), g.tier());
                if (tool != null) queue.add(tool);
            }
            case Goal.Equip g -> queue.addAll(g.slots().values());
            case Goal.Stockpile g -> queue.add(g.key());
            case Goal.Reach g -> { /* a navigate leaf reads no inventory */ }
            case Goal.Build g -> { /* the caller reads the whole inventory */ }
            case Goal.Custom g -> { /* the caller reads the whole inventory */ }
        }
        TreeSet<ItemKey> seen = new TreeSet<>();
        while (!queue.isEmpty() && seen.size() < MAX_CLOSURE_ITEMS) {
            ItemKey item = queue.poll();
            if (!seen.add(item)) continue;
            for (RecipeDef recipe : recipes.producers(item)) {
                for (RecipeDef.Slot slot : recipe.slots()) queue.addAll(slot.alternatives());
                if (recipe.station() != null) queue.add(recipe.station());
            }
            for (Source source : index.sources(item)) {
                if (source instanceof Source.MineBlock mine && mine.toolRequired() && mine.toolClass() != null) {
                    ItemKey tool = config.toolItem(mine.toolClass(), mine.minTier());
                    if (tool != null) queue.add(tool);
                } else if (source instanceof Source.Trade trade) {
                    queue.addAll(trade.cost().keySet());
                }
            }
        }
        return seen;
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
