package com.legoj15.megablockbot.core.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.legoj15.megablockbot.core.ItemKey;
import com.legoj15.megablockbot.core.Pos;
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
import com.legoj15.megablockbot.core.plan.Profile;
import com.legoj15.megablockbot.core.snapshot.ContainerSight;
import com.legoj15.megablockbot.core.snapshot.Snapshot;
import com.legoj15.megablockbot.core.task.Failure;
import java.util.ArrayList;
import java.util.Deque;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/**
 * The planner's behaviour tests (brief P12): a plan is executable in order, the cheapest usable source wins,
 * and an impossible goal fails with the reason an owner can act on. Assertions are on what the plan runner
 * reports, not on node order, wherever order is not the behaviour under test.
 */
final class SolverPlannerTest {
    private static final ItemKey OAK_LOG = ItemKey.of("oak_log");
    private static final ItemKey OAK_PLANKS = ItemKey.of("oak_planks");
    private static final ItemKey STICK = ItemKey.of("stick");
    private static final ItemKey CRAFTING_TABLE = ItemKey.of("crafting_table");
    private static final ItemKey WOODEN_PICKAXE = ItemKey.of("wooden_pickaxe");
    private static final ItemKey STONE_PICKAXE = ItemKey.of("stone_pickaxe");
    private static final ItemKey COBBLESTONE = ItemKey.of("cobblestone");
    private static final ItemKey FURNACE = ItemKey.of("furnace");
    private static final ItemKey CHEST = ItemKey.of("chest");
    private static final ItemKey TORCH = ItemKey.of("torch");
    private static final ItemKey COAL = ItemKey.of("coal");
    private static final ItemKey CHARCOAL = ItemKey.of("charcoal");
    private static final ItemKey STRING = ItemKey.of("string");
    private static final ItemKey SADDLE = ItemKey.of("saddle");
    private static final ItemKey ENCHANTED_BOOK = ItemKey.of("enchanted_book");
    private static final ItemKey COBBLE_AXE = ItemKey.of("cobble_axe");
    private static final Pos CONTAINER = new Pos(16, 64, -4);

    // ─── The fixture: a dozen vanilla-like recipes and a small acquisition index ─────────────────

    private static RecipeDef recipe(String id, ItemKey result, int resultCount, ItemKey station, RecipeDef.Slot... slots) {
        return new RecipeDef(id, RecipeDef.CRAFT, List.of(slots), station, result, resultCount);
    }

    private static RecipeDef.Slot one(ItemKey item) {
        return RecipeDef.Slot.of(item);
    }

    private static RecipeDef.Slot of(ItemKey item, int count) {
        return new RecipeDef.Slot(List.of(item), count);
    }

    private static RecipeDef.Slot either(ItemKey first, ItemKey second) {
        return new RecipeDef.Slot(List.of(first, second), 1);
    }

    private static SimpleRecipeBook book() {
        List<RecipeDef> recipes = List.of(
                recipe("planks_from_log", OAK_PLANKS, 4, null, one(OAK_LOG)),
                recipe("sticks_from_planks", STICK, 4, null, of(OAK_PLANKS, 2)),
                recipe("crafting_table_from_planks", CRAFTING_TABLE, 1, null, of(OAK_PLANKS, 4)),
                recipe("wooden_pickaxe_from_planks", WOODEN_PICKAXE, 1, CRAFTING_TABLE, of(OAK_PLANKS, 3), of(STICK, 2)),
                recipe("stone_pickaxe_from_cobblestone", STONE_PICKAXE, 1, CRAFTING_TABLE, of(COBBLESTONE, 3), of(STICK, 2)),
                recipe("furnace_from_cobblestone", FURNACE, 1, CRAFTING_TABLE, of(COBBLESTONE, 8)),
                recipe("chest_from_planks", CHEST, 1, CRAFTING_TABLE, of(OAK_PLANKS, 8)),
                recipe("torch_from_coal", TORCH, 4, null, either(COAL, CHARCOAL), one(STICK)),
                recipe("wooden_axe_from_planks", ItemKey.of("wooden_axe"), 1, CRAFTING_TABLE, of(OAK_PLANKS, 3), of(STICK, 2)),
                recipe("wooden_shovel_from_planks", ItemKey.of("wooden_shovel"), 1, CRAFTING_TABLE, one(OAK_PLANKS), of(STICK, 2)),
                recipe("wooden_sword_from_planks", ItemKey.of("wooden_sword"), 1, CRAFTING_TABLE, of(OAK_PLANKS, 2), one(STICK)),
                recipe("stone_shovel_from_cobblestone", ItemKey.of("stone_shovel"), 1, CRAFTING_TABLE, one(COBBLESTONE), of(STICK, 2)));
        return new SimpleRecipeBook(recipes, Map.of());
    }

    private static AcquisitionIndex index() {
        AcquisitionIndex.Builder builder = AcquisitionIndex.builder();
        for (ItemKey item : List.of(OAK_LOG, OAK_PLANKS, STICK, CRAFTING_TABLE, WOODEN_PICKAXE, STONE_PICKAXE,
                COBBLESTONE, FURNACE, CHEST, TORCH, COAL, CHARCOAL, STRING, SADDLE, ENCHANTED_BOOK,
                ItemKey.of("wooden_axe"), ItemKey.of("wooden_shovel"), ItemKey.of("wooden_sword"),
                ItemKey.of("stone_shovel"))) {
            builder.addItem(item);
        }
        // Logs by hand: no tool class, so the gather leaf carries no tool requirement at all.
        builder.addSource(new Source.MineBlock("mine:minecraft:oak_log", OAK_LOG, "minecraft:oak_log",
                null, ToolTier.HAND, false, false, false, 1.0, false, null));
        builder.addSource(new Source.MineBlock("mine:minecraft:stone", COBBLESTONE, "minecraft:stone",
                ToolClass.PICKAXE, ToolTier.WOOD, true, false, false, 1.0, false, null));
        builder.addSource(new Source.MineBlock("mine:minecraft:stone#silk_touch", COBBLESTONE, "minecraft:stone",
                ToolClass.PICKAXE, ToolTier.IRON, true, true, false, 1.0, false, null));
        builder.addSource(new Source.ChestLoot("chest:minecraft:simple_dungeon", COBBLESTONE,
                "minecraft:chests/simple_dungeon", 0.5));
        builder.addSource(new Source.MineBlock("mine:minecraft:coal_ore", COAL, "minecraft:coal_ore",
                ToolClass.PICKAXE, ToolTier.WOOD, true, false, false, 1.0, false,
                new Source.OreRange(0, 96, 48)));
        builder.addSource(new Source.MineBlock("mine:minecraft:diamond_ore", ItemKey.of("diamond"),
                "minecraft:diamond_ore", ToolClass.PICKAXE, ToolTier.IRON, true, false, false, 1.0, false,
                new Source.OreRange(-64, 16, -54)));
        builder.addSource(new Source.KillMob("kill:minecraft:spider", STRING, "minecraft:spider", true, 1.0, true));
        builder.addSource(new Source.Fishing("fish:minecraft:gameplay/fishing", STRING,
                "minecraft:gameplay/fishing", 0.1));
        builder.addSource(new Source.MineBlock("mine:minecraft:cobweb#shears", STRING, "minecraft:cobweb",
                ToolClass.SHEARS, ToolTier.HAND, true, false, true, 1.0, false, null));
        return builder.build();
    }

    private static Snapshot snapshot(Map<ItemKey, Integer> items, Map<ToolClass, ToolTier> tools) {
        Snapshot.Builder builder = Snapshot.builder().gameTime(0)
                .dimension("minecraft:overworld").pos(new Pos(0, 64, 0));
        items.forEach(builder::item);
        tools.forEach(builder::tool);
        return builder.build();
    }

    private static final Snapshot EMPTY = snapshot(Map.of(), Map.of());

    private static PlanRequest request(Goal goal, Snapshot snapshot, Exclusions exclusions) {
        return new PlanRequest(goal, snapshot, exclusions, Profile.ASSISTED, Map.of());
    }

    private static SolverPlanner planner(Strategy... strategies) {
        return new SolverPlanner(book(), index(), PlannerConfig.defaults(), List.of(strategies));
    }

    /** A strategy the planner should consult; records every goal it was asked about. */
    private static final class FakeStrategy implements Strategy {
        private final String id;
        private final Predicate<Goal> applies;
        private final long cost;
        private final List<NodeSpec> nodes;
        private final List<Goal> asked = new ArrayList<>();

        FakeStrategy(String id, Predicate<Goal> applies, long cost, NodeSpec... nodes) {
            this.id = id;
            this.applies = applies;
            this.cost = cost;
            this.nodes = List.of(nodes);
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public Optional<Proposal> propose(Goal goal, PlanContext context) {
            asked.add(goal);
            return applies.test(goal) ? Optional.of(new Proposal(nodes, cost, List.of())) : Optional.empty();
        }
    }

    private static NodeSpec tradeLeaf(ItemKey item, int count) {
        return NodeSpec.leaf(new Goal.HaveItem(item, count), "trade",
                Map.of("item", item.id(), "count", Integer.toString(count)), "trade:fake");
    }

    // ─── The plan runner: walks the leaves in order against a stock map ──────────────────────────

    private record Executed(Map<ItemKey, Integer> stock, Map<ToolClass, ToolTier> tools, List<String> ran,
                            String failure) {
    }

    private static final Map<ItemKey, ToolClass> TOOL_CLASSES = new LinkedHashMap<>();
    private static final Map<ItemKey, ToolTier> TOOL_TIERS = new LinkedHashMap<>();

    static {
        PlannerConfig.vanillaToolItems().forEach((key, item) -> {
            String[] parts = key.split(":");
            TOOL_CLASSES.put(item, ToolClass.valueOf(parts[0]));
            TOOL_TIERS.put(item, ToolTier.valueOf(parts[1]));
        });
    }

    private static Executed run(Plan plan, Map<ItemKey, Integer> start, Map<ToolClass, ToolTier> startTools) {
        Map<ItemKey, Integer> stock = new TreeMap<>(start);
        Map<ToolClass, ToolTier> tools = new TreeMap<>(startTools);
        List<String> ran = new ArrayList<>();
        Deque<NodeSpec> pending = new ArrayDeque<>();
        List<NodeSpec> roots = plan.roots();
        for (int i = roots.size() - 1; i >= 0; i--) pending.push(roots.get(i));
        String failure = walk(pending, stock, tools, ran);
        return new Executed(stock, tools, ran, failure);
    }

    private static String walk(Deque<NodeSpec> pending, Map<ItemKey, Integer> stock,
                               Map<ToolClass, ToolTier> tools, List<String> ran) {
        while (!pending.isEmpty()) {
            NodeSpec node = pending.pop();
            if (!node.isLeaf()) {
                List<NodeSpec> children = node.children();
                for (int i = children.size() - 1; i >= 0; i--) pending.push(children.get(i));
                continue;
            }
            String failure = runLeaf(node, stock, tools, ran);
            if (failure != null) return failure;
        }
        return null;
    }

    private static String runLeaf(NodeSpec node, Map<ItemKey, Integer> stock,
                                  Map<ToolClass, ToolTier> tools, List<String> ran) {
        Map<String, String> params = node.params();
        switch (node.taskKind()) {
            case "mine", "harvest" -> {
                String toolClass = params.get("toolClass");
                if (toolClass != null) {
                    ToolTier required = ToolTier.valueOf(params.get("minTier"));
                    ToolTier carried = tools.getOrDefault(ToolClass.valueOf(toolClass), ToolTier.HAND);
                    if (!carried.atLeast(required)) {
                        return "leaf " + node.source() + " needs a " + required + " " + toolClass
                                + " but the stock carries " + carried;
                    }
                }
                gain(stock, tools, ItemKey.of(params.get("item")), Long.parseLong(params.get("count")));
            }
            case "kill", "fish", "trade" -> gain(stock, tools, ItemKey.of(params.get("item")),
                    Long.parseLong(params.get("count")));
            case "craft" -> {
                RecipeDef recipe = book().byId(params.get("recipe")).orElse(null);
                if (recipe == null) return "leaf plans unknown recipe " + params.get("recipe");
                long times = Long.parseLong(params.get("times"));
                if (recipe.station() != null && stock.getOrDefault(recipe.station(), 0) < 1) {
                    return "leaf " + node.params().get("recipe") + " needs a "
                            + recipe.station().id() + " in stock";
                }
                for (RecipeDef.Slot slot : recipe.slots()) {
                    int need = slot.count() * (int) times;
                    for (ItemKey alternative : slot.alternatives()) {
                        int take = Math.min(need, stock.getOrDefault(alternative, 0));
                        if (take > 0) {
                            lose(stock, alternative, take);
                            need -= take;
                        }
                        if (need == 0) break;
                    }
                    if (need > 0) return "leaf " + node.params().get("recipe") + " is short " + need + " of a slot item";
                }
                gain(stock, tools, recipe.result(), times * recipe.resultCount());
            }
            case "equip", "deposit", "navigate" -> { /* recorded only; the goal carries the target */ }
            default -> { /* a strategy's own task kind: recorded, not simulated */ }
        }
        ran.add(node.taskKind() + ":" + label(node));
        return null;
    }

    private static String label(NodeSpec node) {
        if (node.params().containsKey("item")) return node.params().get("item");
        if (node.params().containsKey("recipe")) {
            return book().byId(node.params().get("recipe")).map(r -> r.result().id()).orElse("?");
        }
        return node.goal() instanceof Goal.HaveItem have ? have.key().id() : node.taskKind();
    }

    private static void gain(Map<ItemKey, Integer> stock, Map<ToolClass, ToolTier> tools, ItemKey item, long count) {
        stock.merge(item, (int) Math.min(count, Integer.MAX_VALUE), Integer::sum);
        ToolClass toolClass = TOOL_CLASSES.get(item);
        if (toolClass != null) tools.merge(toolClass, TOOL_TIERS.get(item), ToolTier::max);
    }

    private static void lose(Map<ItemKey, Integer> stock, ItemKey item, int count) {
        int have = stock.getOrDefault(item, 0) - count;
        if (have > 0) stock.put(item, have);
        else stock.remove(item);
    }

    // ─── Assertions' view of a plan ──────────────────────────────────────────────────────────────

    private static List<NodeSpec> leaves(Plan plan) {
        List<NodeSpec> leaves = new ArrayList<>();
        collect(List.copyOf(plan.roots()), leaves);
        return leaves;
    }

    private static void collect(List<NodeSpec> nodes, List<NodeSpec> leaves) {
        for (NodeSpec node : nodes) {
            if (node.isLeaf()) leaves.add(node);
            else collect(node.children(), leaves);
        }
    }

    private static List<NodeSpec> allNodes(Plan plan) {
        List<NodeSpec> nodes = new ArrayList<>();
        collectAll(plan.roots(), nodes);
        return nodes;
    }

    private static void collectAll(List<NodeSpec> nodes, List<NodeSpec> all) {
        for (NodeSpec node : nodes) {
            all.add(node);
            collectAll(node.children(), all);
        }
    }

    private static PlanOutcome outcome(SolverPlanner planner, PlanRequest request) {
        PlanOutcome outcome = planner.plan(request);
        assertNotNull(outcome);
        return outcome;
    }

    private static Plan planned(SolverPlanner planner, PlanRequest request) {
        PlanOutcome outcome = outcome(planner, request);
        assertTrue(outcome instanceof PlanOutcome.Planned, "expected a plan, got " + outcome);
        return ((PlanOutcome.Planned) outcome).plan();
    }

    private static Failure unplannable(SolverPlanner planner, PlanRequest request) {
        PlanOutcome outcome = outcome(planner, request);
        assertTrue(outcome instanceof PlanOutcome.Unplannable, "expected Unplannable, got " + outcome);
        return ((PlanOutcome.Unplannable) outcome).failure();
    }

    // ─── The benchmark: a stone pickaxe from an empty inventory ──────────────────────────────────

    @Test
    void stonePickaxeFromAnEmptyInventoryRunsToEndAndEndsWithThePickaxe() {
        Plan plan = planned(planner(), request(new Goal.HaveItem(STONE_PICKAXE, 1), EMPTY, Exclusions.NONE));

        Executed executed = run(plan, Map.of(), Map.of());
        assertNull(executed.failure(), "the plan must run to the end: " + executed.failure());
        assertEquals(1, executed.stock().getOrDefault(STONE_PICKAXE, 0), "the plan ends with the pickaxe");
        assertTrue(executed.tools().get(ToolClass.PICKAXE).atLeast(ToolTier.WOOD),
                "the plan carried a pickaxe to mine with: " + executed.tools());

        // The wooden pickaxe is made before any cobblestone is mined.
        int pickaxeCraft = executed.ran().indexOf("craft:minecraft:wooden_pickaxe");
        int cobbleGather = executed.ran().indexOf("mine:minecraft:cobblestone");
        assertTrue(pickaxeCraft >= 0, "the plan crafts the wooden pickaxe: " + executed.ran());
        assertTrue(cobbleGather >= 0, "the plan mines cobblestone: " + executed.ran());
        assertTrue(pickaxeCraft < cobbleGather,
                "the wooden pickaxe must be crafted before cobblestone is mined: " + executed.ran());

        // Logs are gathered with no tool.
        List<NodeSpec> gathers = leaves(plan).stream()
                .filter(leaf -> leaf.params().containsKey("item") && leaf.params().get("item").equals("minecraft:oak_log"))
                .toList();
        assertFalse(gathers.isEmpty(), "the plan gathers logs");
        for (NodeSpec gather : gathers) {
            assertFalse(gather.params().containsKey("toolClass"), "logs are gathered with no tool: " + gather.params());
        }
        assertEquals(configuredReplans(), plan.maxTotalReplans());
        assertEquals(planner().inputFingerprint(new Goal.HaveItem(STONE_PICKAXE, 1), EMPTY), plan.inputFingerprint());
    }

    private int configuredReplans() {
        return PlannerConfig.defaults().maxTotalReplans();
    }

    @Test
    void stockedIngredientsGiveOneCraftLeafAndNoGather() {
        Map<ItemKey, Integer> stock = Map.of(COBBLESTONE, 3, STICK, 2, CRAFTING_TABLE, 1);
        Plan plan = planned(planner(), request(new Goal.HaveItem(STONE_PICKAXE, 1),
                snapshot(stock, Map.of()), Exclusions.NONE));

        List<NodeSpec> planLeaves = leaves(plan);
        assertEquals(1, planLeaves.size(), "one leaf, no gather: " + planLeaves);
        assertEquals("craft", planLeaves.get(0).taskKind());
        Executed executed = run(plan, stock, Map.of());
        assertNull(executed.failure(), executed.failure());
        assertEquals(1, executed.stock().getOrDefault(STONE_PICKAXE, 0));
    }

    @Test
    void aStockedWoodenPickaxeNeedsNoToolPrerequisite() {
        Map<ItemKey, Integer> stock = Map.of(WOODEN_PICKAXE, 1, STICK, 2, CRAFTING_TABLE, 1);
        Plan plan = planned(planner(), request(new Goal.HaveItem(STONE_PICKAXE, 1),
                snapshot(stock, Map.of()), Exclusions.NONE));

        List<String> toolGoals = allNodes(plan).stream()
                .filter(node -> node.goal() instanceof Goal.HaveToolTier)
                .map(node -> node.goal().toString())
                .toList();
        assertTrue(toolGoals.isEmpty(), "no tool prerequisite when the pickaxe is in stock: " + toolGoals);
        Executed executed = run(plan, stock, Map.of(ToolClass.PICKAXE, ToolTier.WOOD));
        assertNull(executed.failure(), executed.failure());
        assertEquals(1, executed.stock().getOrDefault(STONE_PICKAXE, 0));
    }

    // ─── Impossible goals ────────────────────────────────────────────────────────────────────────

    @Test
    void aLeafWithNoSourceIsUnplannableNamingTheItem() {
        Failure failure = unplannable(planner(), request(new Goal.HaveItem(SADDLE, 1), EMPTY, Exclusions.NONE));
        assertEquals(Failure.Kind.NO_SOURCE, failure.kind());
        assertTrue(failure instanceof Failure.NoSource, failure.toString());
        assertEquals(SADDLE, ((Failure.NoSource) failure).key());
    }

    @Test
    void aLeafWhoseEverySourceIsExcludedIsUnplannableSayingSo() {
        Failure failure = unplannable(planner(),
                request(new Goal.HaveItem(COBBLESTONE, 1), EMPTY,
                        new Exclusions(java.util.Set.of("mine:minecraft:stone"), java.util.Set.of(), List.of())));
        assertEquals(Failure.Kind.NO_SOURCE, failure.kind());
        Failure.NoSource noSource = (Failure.NoSource) failure;
        assertEquals(COBBLESTONE, noSource.key());
        assertTrue(noSource.detail().contains("excluded"), noSource.detail());
    }

    @Test
    void aToolCycleIsUnplannableWithMissingTool() {
        // A config whose WOOD axe is an axe crafted from cobblestone, which itself needs an axe to mine.
        PlannerConfig config = PlannerConfig.defaults().withToolItems(Map.of(
                PlannerConfig.toolKey(ToolClass.AXE, ToolTier.WOOD), COBBLE_AXE));
        SimpleRecipeBook cyclic = new SimpleRecipeBook(
                List.of(recipe("cobble_axe_from_cobblestone", COBBLE_AXE, 1, CRAFTING_TABLE, of(COBBLESTONE, 3), of(STICK, 2)),
                        recipe("sticks_from_planks", STICK, 4, null, of(OAK_PLANKS, 2)),
                        recipe("planks_from_log", OAK_PLANKS, 4, null, one(OAK_LOG))),
                Map.of());
        AcquisitionIndex.Builder builder = AcquisitionIndex.builder();
        builder.addItem(COBBLE_AXE).addItem(COBBLESTONE).addItem(STICK).addItem(OAK_PLANKS).addItem(OAK_LOG);
        builder.addSource(new Source.MineBlock("mine:minecraft:stone", COBBLESTONE, "minecraft:stone",
                ToolClass.AXE, ToolTier.WOOD, true, false, false, 1.0, false, null));
        builder.addSource(new Source.MineBlock("mine:minecraft:oak_log", OAK_LOG, "minecraft:oak_log",
                null, ToolTier.HAND, false, false, false, 1.0, false, null));
        SolverPlanner cyclicPlanner = new SolverPlanner(cyclic, builder.build(), config, List.of());

        Failure failure = unplannable(cyclicPlanner,
                request(new Goal.HaveItem(COBBLESTONE, 1), EMPTY, Exclusions.NONE));
        assertEquals(Failure.Kind.MISSING_TOOL, failure.kind(), failure.toString());
        assertTrue(failure.detail().toLowerCase().contains("cyclic"), failure.detail());
    }

    // ─── Source choice ───────────────────────────────────────────────────────────────────────────

    @Test
    void theCheaperOfTwoSourcesIsChosenAndExcludingItSwitchesToTheOther() {
        Plan plan = planned(planner(), request(new Goal.HaveItem(STRING, 1), EMPTY, Exclusions.NONE));
        List<String> sources = leaves(plan).stream().map(NodeSpec::source).toList();
        assertTrue(sources.contains("kill:minecraft:spider"), "the cheaper kill source is chosen: " + sources);
        assertFalse(sources.contains("fish:minecraft:gameplay/fishing"), "fishing is dearer: " + sources);

        Plan switched = planned(planner(), request(new Goal.HaveItem(STRING, 1), EMPTY,
                new Exclusions(java.util.Set.of("kill:minecraft:spider"), java.util.Set.of(), List.of())));
        List<String> switchedSources = leaves(switched).stream().map(NodeSpec::source).toList();
        assertTrue(switchedSources.contains("fish:minecraft:gameplay/fishing"),
                "excluding the kill source switches to fishing: " + switchedSources);
        assertFalse(switchedSources.contains("kill:minecraft:spider"), switchedSources.toString());
    }

    @Test
    void aSlotUsesTheCheaperAlternativeToGather() {
        Plan plan = planned(planner(), request(new Goal.HaveItem(TORCH, 4), EMPTY, Exclusions.NONE));
        List<String> gathered = leaves(plan).stream()
                .filter(leaf -> leaf.params().containsKey("item"))
                .map(leaf -> leaf.params().get("item"))
                .toList();
        assertTrue(gathered.contains("minecraft:coal"), "coal is cheaper to gather: " + gathered);
        assertFalse(gathered.contains("minecraft:charcoal"), "charcoal has no source: " + gathered);
        Executed executed = run(plan, Map.of(), Map.of());
        assertNull(executed.failure(), executed.failure());
        assertEquals(4, executed.stock().getOrDefault(TORCH, 0));
    }

    @Test
    void aSlotUsesTheAlternativeInStock() {
        Plan plan = planned(planner(), request(new Goal.HaveItem(TORCH, 4),
                snapshot(Map.of(CHARCOAL, 1), Map.of()), Exclusions.NONE));
        List<String> gathered = leaves(plan).stream()
                .filter(leaf -> leaf.params().containsKey("item"))
                .map(leaf -> leaf.params().get("item"))
                .toList();
        assertFalse(gathered.contains("minecraft:coal"), "the stocked charcoal must not force a coal gather: " + gathered);
        Executed executed = run(plan, Map.of(CHARCOAL, 1), Map.of());
        assertNull(executed.failure(), executed.failure());
        assertEquals(4, executed.stock().getOrDefault(TORCH, 0));
    }

    // ─── The other goal kinds ────────────────────────────────────────────────────────────────────

    @Test
    void aSatisfiedHaveToolTierPlansNothing() {
        Plan plan = planned(planner(), request(new Goal.HaveToolTier(ToolClass.PICKAXE, ToolTier.WOOD),
                snapshot(Map.of(WOODEN_PICKAXE, 1), Map.of()), Exclusions.NONE));
        assertTrue(plan.roots().isEmpty(), "a satisfied tier plans nothing: " + plan.roots());
    }

    @Test
    void reachIsOneNavigateLeaf() {
        Goal.Reach goal = new Goal.Reach("minecraft:overworld", new Pos(10, 64, 20), 8);
        Plan plan = planned(planner(), request(goal, EMPTY, Exclusions.NONE));
        List<NodeSpec> planLeaves = leaves(plan);
        assertEquals(1, planLeaves.size(), planLeaves.toString());
        NodeSpec leaf = planLeaves.get(0);
        assertEquals("navigate", leaf.taskKind());
        assertEquals(goal, leaf.goal());
        assertEquals("minecraft:overworld", leaf.params().get("dimension"));
        assertEquals("10,64,20", leaf.params().get("pos"));
        assertEquals("8", leaf.params().get("radius"));
    }

    @Test
    void equipPlansMissingItemsThenOneEquipLeaf() {
        Goal.Equip goal = new Goal.Equip(Map.of(com.legoj15.megablockbot.core.EquipSlot.MAINHAND, WOODEN_PICKAXE));
        Plan fromEmpty = planned(planner(), request(goal, EMPTY, Exclusions.NONE));
        List<NodeSpec> planLeaves = leaves(fromEmpty);
        assertEquals("equip", planLeaves.get(planLeaves.size() - 1).taskKind(), planLeaves.toString());
        assertTrue(planLeaves.size() > 1, "the item is not held, so the plan first obtains it: " + planLeaves);
        Executed executed = run(fromEmpty, Map.of(), Map.of());
        assertNull(executed.failure(), executed.failure());

        Plan whenHeld = planned(planner(), request(goal, snapshot(Map.of(WOODEN_PICKAXE, 1), Map.of()), Exclusions.NONE));
        assertEquals(1, leaves(whenHeld).size(), "the item is in stock: only the equip leaf remains");
        assertEquals("equip", leaves(whenHeld).get(0).taskKind());
    }

    @Test
    void stockpilePlansWhatTheContainerLacksThenOneDepositLeaf() {
        Goal.Stockpile goal = new Goal.Stockpile(OAK_PLANKS, 8, "minecraft:overworld", CONTAINER);
        Plan blind = planned(planner(), request(goal, EMPTY, Exclusions.NONE));
        assertEquals("deposit", leaves(blind).get(leaves(blind).size() - 1).taskKind());
        assertTrue(leaves(blind).size() > 1, "an unknown container is planned for in full");
        Executed executed = run(blind, Map.of(), Map.of());
        assertNull(executed.failure(), executed.failure());

        Snapshot.Builder builder = Snapshot.builder().gameTime(0);
        builder.container(new ContainerSight(CONTAINER, "minecraft:chest", true, false, Map.of(OAK_PLANKS, 8)));
        Plan knownFull = planned(planner(), request(goal, builder.build(), Exclusions.NONE));
        assertEquals(1, leaves(knownFull).size(), "the container is known to hold the stock already");
        assertEquals("deposit", leaves(knownFull).get(0).taskKind());
    }

    @Test
    void aComponentCheckedItemGoesToAStrategyAndItsNodesAreUsed() {
        FakeStrategy reroll = new FakeStrategy("librarian-reroll",
                goal -> goal instanceof Goal.HaveItem have && have.key().equals(ENCHANTED_BOOK) && have.check() != null,
                4000, tradeLeaf(ENCHANTED_BOOK, 1));
        Goal.HaveItem goal = new Goal.HaveItem(ENCHANTED_BOOK, 1,
                ComponentCheck.enchantment("minecraft:mending", 1));
        Plan plan = planned(planner(reroll), request(goal, EMPTY, Exclusions.NONE));

        assertEquals(1, reroll.asked.size(), "the strategy is asked once for the goal");
        assertEquals(goal, reroll.asked.get(0), "the strategy is asked with the goal, check included");
        List<NodeSpec> planLeaves = leaves(plan);
        assertEquals(1, planLeaves.size(), planLeaves.toString());
        assertEquals("trade", planLeaves.get(0).taskKind());
        assertEquals("trade:fake", planLeaves.get(0).source());

        Failure without = unplannable(planner(), request(goal, EMPTY, Exclusions.NONE));
        assertEquals(Failure.Kind.NO_SOURCE, without.kind());
        assertTrue(without.detail().contains("strategy"), without.detail());
    }

    @Test
    void customAndBuildGoalsGoToStrategiesToo() {
        Goal.Custom custom = new Goal.Custom("fish_until", Map.of("item", "minecraft:string"));
        assertEquals(Failure.Kind.NO_SOURCE, unplannable(planner(), request(custom, EMPTY, Exclusions.NONE)).kind());
        assertEquals(Failure.Kind.NO_SOURCE,
                unplannable(planner(), request(new Goal.Build("hut", "minecraft:overworld", Pos.parse("1,64,1")),
                        EMPTY, Exclusions.NONE)).kind());

        FakeStrategy fisher = new FakeStrategy("manual-fishing", goal -> goal instanceof Goal.Custom
                && ((Goal.Custom) goal).kind().equals("fish_until"), 500, tradeLeaf(STRING, 1));
        Plan plan = planned(planner(fisher), request(custom, EMPTY, Exclusions.NONE));
        assertEquals(1, leaves(plan).size(), leaves(plan).toString());
        assertEquals("trade", leaves(plan).get(0).taskKind());
    }

    // ─── Strategies against gather costs ─────────────────────────────────────────────────────────

    @Test
    void aCheaperStrategyReplacesTheGatherAndADearerOneDoesNot() {
        NodeSpec fake = tradeLeaf(COBBLESTONE, 3);
        FakeStrategy cheap = new FakeStrategy("cheap", goal -> goal instanceof Goal.HaveItem have
                && have.key().equals(COBBLESTONE), 10, fake);
        Map<ItemKey, Integer> stock = Map.of(STICK, 2, CRAFTING_TABLE, 1);
        Plan cheapPlan = planned(planner(cheap), request(new Goal.HaveItem(COBBLESTONE, 3),
                snapshot(stock, Map.of()), Exclusions.NONE));
        List<NodeSpec> planLeaves = leaves(cheapPlan);
        assertEquals(1, planLeaves.size(), "the proposal replaces the gather: " + planLeaves);
        assertEquals("trade:fake", planLeaves.get(0).source(), planLeaves.toString());
        Executed executed = run(cheapPlan, stock, Map.of());
        assertNull(executed.failure(), executed.failure());
        assertEquals(3, executed.stock().getOrDefault(COBBLESTONE, 0));

        FakeStrategy dear = new FakeStrategy("dear", goal -> goal instanceof Goal.HaveItem have
                && have.key().equals(COBBLESTONE), 100_000, fake);
        Plan dearPlan = planned(planner(dear), request(new Goal.HaveItem(COBBLESTONE, 3),
                snapshot(stock, Map.of()), Exclusions.NONE));
        List<String> sources = leaves(dearPlan).stream().map(NodeSpec::source).toList();
        assertTrue(sources.contains("mine:minecraft:stone"), "the dearer strategy must not replace the gather: " + sources);
        assertFalse(sources.contains("trade:fake"), sources.toString());
    }

    // ─── The fingerprint and determinism ─────────────────────────────────────────────────────────

    @Test
    void theFingerprintTracksInventoryAndToolsAndNotPosition() {
        SolverPlanner instance = planner();
        Goal goal = new Goal.HaveItem(STONE_PICKAXE, 1);
        long base = instance.inputFingerprint(goal, EMPTY);
        assertEquals(base, instance.inputFingerprint(goal, snapshot(Map.of(), Map.of())),
                "position, health, hunger and game time are not part of it");
        assertNotEquals(base, instance.inputFingerprint(goal, snapshot(Map.of(OAK_LOG, 1), Map.of())),
                "an inventory change changes it");
        assertNotEquals(base, instance.inputFingerprint(goal, snapshot(Map.of(), Map.of(ToolClass.PICKAXE, ToolTier.WOOD))),
                "a best-tool change changes it");
        assertNotEquals(base, instance.inputFingerprint(new Goal.HaveItem(STONE_PICKAXE, 2), EMPTY),
                "a goal change changes it");
    }

    @Test
    void planningTheSameRequestTwiceGivesEqualPlans() {
        SolverPlanner instance = planner();
        PlanRequest request = request(new Goal.HaveItem(STONE_PICKAXE, 1), EMPTY, Exclusions.NONE);
        assertEquals(instance.plan(request), instance.plan(request));
        Plan plan = planned(instance, request);
        assertFalse(plan.notes().isEmpty(), "what the planner could not consider is said out loud: " + plan.notes());
        assertTrue(plan.notes().stream().anyMatch(note -> note.contains("silk touch")), plan.notes().toString());
        assertTrue(plan.notes().stream().anyMatch(note -> note.contains("chest loot")), plan.notes().toString());

        Plan stringPlan = planned(instance, request(new Goal.HaveItem(STRING, 1), EMPTY, Exclusions.NONE));
        assertTrue(stringPlan.notes().stream().anyMatch(note -> note.contains("shears")),
                stringPlan.notes().toString());
    }
}
