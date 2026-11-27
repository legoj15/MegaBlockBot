package com.legoj15.megablockbot.core.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.legoj15.megablockbot.core.EquipSlot;
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
import com.legoj15.megablockbot.core.snapshot.StackFacts;
import com.legoj15.megablockbot.core.task.Failure;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/**
 * The planner's behaviour tests (brief P12 and its fix round): a plan is executable in order, the cheapest usable
 * source wins, and an impossible goal fails with the reason an owner can act on. Assertions are on what the plan
 * runner reports, not on node order, wherever order is not the behaviour under test; failures are asserted by kind
 * and item, never by wording.
 */
final class SolverPlannerTest {
    private static final ItemKey OAK_LOG = ItemKey.of("oak_log");
    private static final ItemKey OAK_PLANKS = ItemKey.of("oak_planks");
    private static final ItemKey STICK = ItemKey.of("stick");
    private static final ItemKey CRAFTING_TABLE = ItemKey.of("crafting_table");
    private static final ItemKey WOODEN_PICKAXE = ItemKey.of("wooden_pickaxe");
    private static final ItemKey STONE_PICKAXE = ItemKey.of("stone_pickaxe");
    private static final ItemKey DIAMOND_PICKAXE = ItemKey.of("diamond_pickaxe");
    private static final ItemKey COBBLESTONE = ItemKey.of("cobblestone");
    private static final ItemKey DIAMOND = ItemKey.of("diamond");
    private static final ItemKey FURNACE = ItemKey.of("furnace");
    private static final ItemKey CHEST = ItemKey.of("chest");
    private static final ItemKey TORCH = ItemKey.of("torch");
    private static final ItemKey COAL = ItemKey.of("coal");
    private static final ItemKey CHARCOAL = ItemKey.of("charcoal");
    private static final ItemKey STRING = ItemKey.of("string");
    private static final ItemKey SADDLE = ItemKey.of("saddle");
    private static final ItemKey EMERALD = ItemKey.of("emerald");
    private static final ItemKey ENCHANTED_BOOK = ItemKey.of("enchanted_book");
    private static final ItemKey COBBLE_AXE = ItemKey.of("cobble_axe");
    private static final ItemKey GADGET = ItemKey.of("gadget");
    private static final ItemKey GOLD_ORE = ItemKey.of("gold_ore");
    private static final ItemKey GOLD_INGOT = ItemKey.of("gold_ingot");
    private static final ItemKey AMBER = ItemKey.of("amber");
    private static final ItemKey SAND = ItemKey.of("sand");
    private static final ItemKey STONE_SHOVEL = ItemKey.of("stone_shovel");
    private static final ItemKey NETHERITE_PICKAXE = ItemKey.of("netherite_pickaxe");
    private static final ItemKey UNOBTAINIUM = ItemKey.of("unobtainium");
    private static final ItemKey WIDGET = ItemKey.of("widget");
    private static final ItemKey GIZMO = ItemKey.of("gizmo");
    private static final ItemKey MOUNT_KIT = ItemKey.of("mount_kit");
    private static final ItemKey LEAD = ItemKey.of("lead");
    private static final ItemKey SPRUCE_LOG = ItemKey.of("spruce_log");
    private static final ItemKey SPRUCE_PLANKS = ItemKey.of("spruce_planks");
    private static final ItemKey SHELF = ItemKey.of("shelf");
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

    private static List<RecipeDef> baseRecipes() {
        return List.of(
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
    }

    private static SimpleRecipeBook book() {
        return new SimpleRecipeBook(baseRecipes(), Map.of());
    }

    private static SimpleRecipeBook bookWith(RecipeDef... extra) {
        List<RecipeDef> recipes = new ArrayList<>(baseRecipes());
        recipes.addAll(List.of(extra));
        return new SimpleRecipeBook(recipes, Map.of());
    }

    /** The base index's sources; a test adds its own before {@code build()}. Diamond needs a pickaxe of the given tier. */
    private static AcquisitionIndex.Builder indexBuilder(ToolTier diamondTier) {
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
        builder.addSource(new Source.MineBlock("mine:minecraft:diamond_ore", DIAMOND,
                "minecraft:diamond_ore", ToolClass.PICKAXE, diamondTier, true, false, false, 1.0, false,
                new Source.OreRange(-64, 16, -54)));
        builder.addSource(new Source.KillMob("kill:minecraft:spider", STRING, "minecraft:spider", true, 1.0, true));
        builder.addSource(new Source.Fishing("fish:minecraft:gameplay/fishing", STRING,
                "minecraft:gameplay/fishing", 0.1));
        builder.addSource(new Source.MineBlock("mine:minecraft:cobweb#shears", STRING, "minecraft:cobweb",
                ToolClass.SHEARS, ToolTier.HAND, true, false, true, 1.0, false, null));
        return builder;
    }

    private static AcquisitionIndex index() {
        return indexBuilder(ToolTier.IRON).build();
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

    private static SolverPlanner planner(RecipeBook book, AcquisitionIndex index, Strategy... strategies) {
        return new SolverPlanner(book, index, PlannerConfig.defaults(), List.of(strategies));
    }

    private static SolverPlanner planner(PlannerConfig config, Strategy... strategies) {
        return new SolverPlanner(book(), index(), config, List.of(strategies));
    }

    /** Mining costs 1000 a block and tool tiers nothing, so a price is easy to read and independent of the defaults. */
    private static PlannerConfig dearMining() {
        return dearMining(0);
    }

    private static PlannerConfig dearMining(long toolStep) {
        PlannerConfig defaults = PlannerConfig.defaults();
        return new PlannerConfig(1000, defaults.deepOre(), toolStep, defaults.harvest(), defaults.kill(), defaults.fish(),
                defaults.trade(), defaults.sightStep(), defaults.unseenPenalty(), defaults.maxTotalReplans(),
                defaults.toolItems());
    }

    private static Exclusions without(String... sources) {
        return new Exclusions(Set.of(sources), Set.of(), List.of());
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

    /** A strategy whose answer is a function of the goal and the context, for the tests that look at the context. */
    private static final class ScriptedStrategy implements Strategy {
        private final String id;
        private final BiFunction<Goal, PlanContext, Optional<Proposal>> script;

        ScriptedStrategy(String id, BiFunction<Goal, PlanContext, Optional<Proposal>> script) {
            this.id = id;
            this.script = script;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public Optional<Proposal> propose(Goal goal, PlanContext context) {
            return script.apply(goal, context);
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
        return run(plan, book(), start, startTools);
    }

    /** What each container the run may take from still holds; null outside {@link #runWithContainers}. */
    private static Map<Pos, Map<ItemKey, Integer>> shelves;

    /** A run's end state and what its containers hold afterwards. */
    private record Shelved(Executed executed, Map<Pos, Map<ItemKey, Integer>> left) {
    }

    /** Runs the plan with containers whose contents the withdraw leaves take from, as a body's menu clicks would. */
    private static Shelved runWithContainers(Plan plan, Map<ItemKey, Integer> start,
                                             Map<Pos, Map<ItemKey, Integer>> containers) {
        Map<Pos, Map<ItemKey, Integer>> held = new LinkedHashMap<>();
        containers.forEach((at, contents) -> held.put(at, new TreeMap<>(contents)));
        shelves = held;
        try {
            Executed executed = run(plan, start, Map.of());
            return new Shelved(executed, held);
        } finally {
            shelves = null;
        }
    }

    private static Executed run(Plan plan, RecipeBook book, Map<ItemKey, Integer> start,
                                Map<ToolClass, ToolTier> startTools) {
        Map<ItemKey, Integer> stock = new TreeMap<>(start);
        Map<ToolClass, ToolTier> tools = new TreeMap<>(startTools);
        List<String> ran = new ArrayList<>();
        Deque<NodeSpec> pending = new ArrayDeque<>();
        List<NodeSpec> roots = plan.roots();
        for (int i = roots.size() - 1; i >= 0; i--) pending.push(roots.get(i));
        String failure = walk(pending, book, stock, tools, ran);
        return new Executed(stock, tools, ran, failure);
    }

    private static String walk(Deque<NodeSpec> pending, RecipeBook book, Map<ItemKey, Integer> stock,
                               Map<ToolClass, ToolTier> tools, List<String> ran) {
        while (!pending.isEmpty()) {
            NodeSpec node = pending.pop();
            if (!node.isLeaf()) {
                List<NodeSpec> children = node.children();
                for (int i = children.size() - 1; i >= 0; i--) pending.push(children.get(i));
                continue;
            }
            String failure = runLeaf(node, book, stock, tools, ran);
            if (failure == null) failure = checkOwnGoal(node, stock);
            if (failure != null) return failure;
        }
        return null;
    }

    /** A leaf's own goal is what the executor verifies when the leaf ends: it must hold against the stock then. */
    private static String checkOwnGoal(NodeSpec node, Map<ItemKey, Integer> stock) {
        if (!(node.goal() instanceof Goal.HaveItem have)) return null;
        int held = stock.getOrDefault(have.key(), 0);
        if (held >= have.count()) return null;
        return "leaf " + node.taskKind() + ":" + node.params() + " ended with " + held + " of " + have.key().id()
                + " but its own goal is " + have.count();
    }

    private static String runLeaf(NodeSpec node, RecipeBook book, Map<ItemKey, Integer> stock,
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
            case "kill", "fish" -> gain(stock, tools, ItemKey.of(params.get("item")), Long.parseLong(params.get("count")));
            case "trade" -> {
                long trades = Long.parseLong(params.get("count"));
                // A planned trade says what it charges; a strategy's own trade leaf may not.
                for (Map.Entry<ItemKey, Integer> charge : parseCost(params.get("cost")).entrySet()) {
                    long owed = charge.getValue() * trades;
                    if (stock.getOrDefault(charge.getKey(), 0) < owed) {
                        return "trade " + node.source() + " charges " + owed + " of " + charge.getKey().id()
                                + " but the stock holds " + stock.getOrDefault(charge.getKey(), 0);
                    }
                    lose(stock, charge.getKey(), (int) owed);
                }
                gain(stock, tools, ItemKey.of(params.get("item")), trades);
            }
            case "craft" -> {
                RecipeDef recipe = book.byId(params.get("recipe")).orElse(null);
                if (recipe == null) return "leaf plans unknown recipe " + params.get("recipe");
                long times = Long.parseLong(params.get("times"));
                if (recipe.station() != null && stock.getOrDefault(recipe.station(), 0) < 1) {
                    return "leaf " + params.get("recipe") + " needs a " + recipe.station().id() + " in stock";
                }
                String failure = craftByLayout(recipe, params, times, stock);
                if (failure != null) return failure;
                gain(stock, tools, recipe.result(), times * recipe.resultCount());
            }
            case "deposit" -> {
                ItemKey item = ItemKey.of(params.get("item"));
                int moved = Integer.parseInt(params.get("count"));
                if (stock.getOrDefault(item, 0) < moved) {
                    return "deposit moves " + moved + " of " + item.id() + " but the stock holds "
                            + stock.getOrDefault(item, 0);
                }
                lose(stock, item, moved); // the container's contents are not tracked: the items leave the body
            }
            case "withdraw" -> {
                // What a container holds is only tracked in a run that was given containers (runWithContainers).
                if (shelves == null) return "a withdraw leaf ran with no containers to take from";
                Pos at = Pos.parse(params.get("container"));
                ItemKey item = ItemKey.of(params.get("item"));
                int count = Integer.parseInt(params.get("count"));
                Map<ItemKey, Integer> shelf = shelves.get(at);
                if (shelf == null) return "withdraw names a container the run has none of: " + at;
                int held = shelf.getOrDefault(item, 0);
                if (held < count) {
                    return "withdraw takes " + count + " of " + item.id() + " from " + at + ", which holds " + held;
                }
                lose(shelf, item, count);
                gain(stock, tools, item, count);
            }
            case "equip", "navigate" -> { /* recorded only; a worn item is still counted in the stock */ }
            default -> { /* a strategy's own task kind: recorded, not simulated */ }
        }
        ran.add(node.taskKind() + ":" + label(node, book));
        return null;
    }

    /**
     * Takes exactly the items the leaf's layout names, slot by slot, craft by craft: what an executor that follows
     * it does. A leaf with no layout is run the way an executor without one would, taking the first alternative in
     * stock for each slot; the layout tests check that every craft leaf has one.
     */
    private static String craftByLayout(RecipeDef recipe, Map<String, String> params, long times,
                                        Map<ItemKey, Integer> stock) {
        if (params.get("layout") == null) return craftGreedily(recipe, times, stock);
        List<CraftLayout.Run> runs = CraftLayout.decode(params.get("layout"));
        long covered = 0;
        for (CraftLayout.Run run : runs) covered += run.crafts();
        if (runs.isEmpty() || covered != times) {
            return "craft " + recipe.id() + " x" + times + " carries a layout covering " + covered + " crafts: "
                    + params.get("layout");
        }
        int slotCopies = 0;
        for (RecipeDef.Slot slot : recipe.slots()) slotCopies += slot.count();
        for (CraftLayout.Run run : runs) {
            if (run.items().size() != slotCopies) {
                return "craft " + recipe.id() + " has " + slotCopies + " slots but its layout names " + run.items();
            }
            for (long craft = 0; craft < run.crafts(); craft++) {
                int at = 0;
                for (RecipeDef.Slot slot : recipe.slots()) {
                    for (int copy = 0; copy < slot.count(); copy++, at++) {
                        ItemKey named = run.items().get(at);
                        if (!slot.alternatives().contains(named)) {
                            return "craft " + recipe.id() + " names " + named.id() + " for a slot that takes "
                                    + slot.alternatives();
                        }
                        if (stock.getOrDefault(named, 0) < 1) {
                            return "craft " + recipe.id() + " is short of " + named.id() + ", the item its layout names";
                        }
                        lose(stock, named, 1);
                    }
                }
            }
        }
        return null;
    }

    private static String craftGreedily(RecipeDef recipe, long times, Map<ItemKey, Integer> stock) {
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
            if (need > 0) return "leaf " + recipe.id() + " is short " + need + " of a slot item";
        }
        return null;
    }

    /** {@code item=count,item=count} as a trade leaf's {@code cost} param carries it; empty for none. */
    private static Map<ItemKey, Integer> parseCost(String text) {
        Map<ItemKey, Integer> cost = new TreeMap<>();
        if (text == null || text.isEmpty()) return cost;
        for (String entry : text.split(",")) {
            String[] parts = entry.split("=");
            cost.put(new ItemKey(parts[0]), Integer.parseInt(parts[1]));
        }
        return cost;
    }

    private static String label(NodeSpec node, RecipeBook book) {
        if (node.params().containsKey("item")) return node.params().get("item");
        if (node.params().containsKey("recipe")) {
            return book.byId(node.params().get("recipe")).map(r -> r.result().id()).orElse("?");
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

    /** How many of the item the plan's gather leaves bring in, summed over leaves of the given task kinds. */
    private static long gathered(Plan plan, ItemKey item, String... kinds) {
        long total = 0;
        for (NodeSpec leaf : leaves(plan)) {
            if (List.of(kinds).contains(leaf.taskKind()) && item.id().equals(leaf.params().get("item"))) {
                total += Long.parseLong(leaf.params().get("count"));
            }
        }
        return total;
    }

    /** The count of each gather leaf of the item, in plan order. */
    private static List<Long> gatherCounts(Plan plan, ItemKey item) {
        List<Long> counts = new ArrayList<>();
        for (NodeSpec leaf : leaves(plan)) {
            if (!leaf.taskKind().equals("craft") && item.id().equals(leaf.params().get("item"))) {
                counts.add(Long.parseLong(leaf.params().get("count")));
            }
        }
        return counts;
    }

    private static long gathered(Plan plan, ItemKey item) {
        return gathered(plan, item, "mine", "harvest", "kill", "fish", "trade");
    }

    private static List<String> sources(Plan plan) {
        return leaves(plan).stream().map(NodeSpec::source).toList();
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

    /** A plan that runs to its end from the start state, ending with at least the goal count. */
    private static Executed assertRunsToTheGoal(Plan plan, RecipeBook book, Map<ItemKey, Integer> start,
                                                Map<ToolClass, ToolTier> tools, ItemKey item, int count) {
        Executed executed = run(plan, book, start, tools);
        assertNull(executed.failure(), "the plan must run to the end: " + executed.failure() + " after " + executed.ran());
        assertTrue(executed.stock().getOrDefault(item, 0) >= count,
                "the plan ends with " + count + " of " + item.id() + ": " + executed.stock());
        return executed;
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

        // No more is gathered than the pickaxe needs: a table (4 planks), the wooden pickaxe (3 planks) and two
        // stick crafts' worth (2 planks) are nine planks, so three logs; the stone pickaxe takes three cobblestone.
        assertEquals(3, gathered(plan, OAK_LOG), "three logs suffice");
        assertEquals(3, gathered(plan, COBBLESTONE), "the stone pickaxe takes three cobblestone");
        assertEquals(planner().inputFingerprint(new Goal.HaveItem(STONE_PICKAXE, 1), EMPTY), plan.inputFingerprint());
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

    @Test
    void aBetterToolInStockThanTheSourceNeedsIsEnough() {
        // The snapshot reports no tool, but a stone pickaxe is in stock: mining cobblestone (wood tier) needs no
        // prerequisite, and in particular no wooden pickaxe is made.
        Map<ItemKey, Integer> stock = Map.of(STONE_PICKAXE, 1);
        Plan plan = planned(planner(), request(new Goal.HaveItem(COBBLESTONE, 3), snapshot(stock, Map.of()),
                Exclusions.NONE));
        assertEquals(List.of("mine:minecraft:stone"), sources(plan), "nothing but the mining: " + leaves(plan));
        assertRunsToTheGoal(plan, book(), stock, Map.of(ToolClass.PICKAXE, ToolTier.STONE), COBBLESTONE, 3);
    }

    // ─── Stock accounting around tool prerequisites (brief P12b items 1, 12) ─────────────────────

    @Test
    void aToolPrerequisiteThatEatsTheTargetsOwnDemandIsPlannedAgainstTheStockItLeaves() {
        // A gadget takes four cobblestone and a diamond; the diamond needs a stone pickaxe, which takes three
        // cobblestone of its own. The cobblestone the gadget counts must be what is left after the pickaxe.
        RecipeBook gadgetBook = bookWith(recipe("gadget_from_cobblestone_and_diamond", GADGET, 1, null,
                of(COBBLESTONE, 4), one(DIAMOND)));
        AcquisitionIndex gadgetIndex = indexBuilder(ToolTier.STONE).build();
        SolverPlanner planner = planner(gadgetBook, gadgetIndex);

        // The cobblestone gathers of the plan, in order, by cobblestone in stock at the start: the pickaxe's three
        // first when it must be mined for, then what the gadget still lacks, each in one leaf (a leaf per missing
        // unit would mean the plan only found the shortfall out when the solve ran short).
        Map<Integer, List<Long>> expectedGather =
                new TreeMap<>(Map.of(0, List.of(3L, 4L), 4, List.of(3L), 7, List.<Long>of()));
        for (Map.Entry<Integer, List<Long>> expected : expectedGather.entrySet()) {
            int cobbleInStock = expected.getKey();
            Map<ItemKey, Integer> stock = cobbleInStock == 0 ? Map.of() : Map.of(COBBLESTONE, cobbleInStock);
            Plan plan = planned(planner, request(new Goal.HaveItem(GADGET, 1), snapshot(stock, Map.of()),
                    Exclusions.NONE));
            assertRunsToTheGoal(plan, gadgetBook, stock, Map.of(), GADGET, 1);
            assertEquals(expected.getValue(), gatherCounts(plan, COBBLESTONE),
                    "cobblestone gathered with " + cobbleInStock + " in stock");
        }
    }

    // ─── Impossible goals ────────────────────────────────────────────────────────────────────────

    @Test
    void aLeafWithNoSourceIsUnplannableNamingTheItem() {
        Failure failure = unplannable(planner(), request(new Goal.HaveItem(SADDLE, 1), EMPTY, Exclusions.NONE));
        assertEquals(Failure.Kind.NO_SOURCE, failure.kind());
        assertEquals(SADDLE, ((Failure.NoSource) failure).key());
    }

    @Test
    void aLeafWhoseEverySourceIsExcludedIsUnplannableNamingTheItem() {
        Failure failure = unplannable(planner(),
                request(new Goal.HaveItem(COBBLESTONE, 1), EMPTY, without("mine:minecraft:stone")));
        assertEquals(Failure.Kind.NO_SOURCE, failure.kind());
        assertEquals(COBBLESTONE, ((Failure.NoSource) failure).key());
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
        Failure.MissingTool missing = (Failure.MissingTool) failure;
        assertEquals(ToolClass.AXE, missing.toolClass());
        assertEquals(ToolTier.WOOD, missing.tier());
        assertTrue(missing.detail().contains(COBBLE_AXE.id()), "the failure names the tool item: " + missing.detail());
    }

    // ─── Source choice ───────────────────────────────────────────────────────────────────────────

    @Test
    void theCheaperOfTwoSourcesIsChosenAndExcludingItSwitchesToTheOther() {
        Plan plan = planned(planner(), request(new Goal.HaveItem(STRING, 1), EMPTY, Exclusions.NONE));
        List<String> sources = sources(plan);
        assertTrue(sources.contains("kill:minecraft:spider"), "the cheaper kill source is chosen: " + sources);
        assertFalse(sources.contains("fish:minecraft:gameplay/fishing"), "fishing is dearer: " + sources);

        Plan switched = planned(planner(), request(new Goal.HaveItem(STRING, 1), EMPTY,
                without("kill:minecraft:spider")));
        List<String> switchedSources = sources(switched);
        assertTrue(switchedSources.contains("fish:minecraft:gameplay/fishing"),
                "excluding the kill source switches to fishing: " + switchedSources);
        assertFalse(switchedSources.contains("kill:minecraft:spider"), switchedSources.toString());
    }

    @Test
    void aSourceWhoseToolCannotBePlannedFallsThroughToTheNextOne() {
        // Amber is mined with a diamond pickaxe (cheaper per unit than fishing for it) but the book has no way to
        // make one: the cheapest source cannot be used, the next can.
        AcquisitionIndex.Builder builder = indexBuilder(ToolTier.IRON);
        builder.addItem(AMBER);
        builder.addSource(new Source.MineBlock("mine:minecraft:amber_ore", AMBER, "minecraft:amber_ore",
                ToolClass.PICKAXE, ToolTier.DIAMOND, true, false, false, 1.0, false, null));
        builder.addSource(new Source.Fishing("fish:minecraft:gameplay/amber", AMBER, "minecraft:gameplay/amber", 0.5));
        SolverPlanner planner = planner(book(), builder.build());

        Plan plan = planned(planner, request(new Goal.HaveItem(AMBER, 2), EMPTY, Exclusions.NONE));
        assertEquals(List.of("fish:minecraft:gameplay/amber"), sources(plan), "the fallback source is used");
        assertRunsToTheGoal(plan, book(), Map.of(), Map.of(), AMBER, 2);

        // With the fallback gone the failure is the cheapest source's: the diamond pickaxe has no source.
        Failure failure = unplannable(planner, request(new Goal.HaveItem(AMBER, 2), EMPTY,
                without("fish:minecraft:gameplay/amber")));
        assertEquals(Failure.Kind.NO_SOURCE, failure.kind());
        assertEquals(DIAMOND_PICKAXE, ((Failure.NoSource) failure).key(),
                "the failure reported is the cheapest source's, not a generic 'no source for amber'");
    }

    @Test
    void aSlotUsesTheCheaperAlternativeToGather() {
        Plan plan = planned(planner(), request(new Goal.HaveItem(TORCH, 4), EMPTY, Exclusions.NONE));
        List<String> gatheredItems = leaves(plan).stream()
                .filter(leaf -> leaf.params().containsKey("item"))
                .map(leaf -> leaf.params().get("item"))
                .toList();
        assertTrue(gatheredItems.contains("minecraft:coal"), "coal is cheaper to gather: " + gatheredItems);
        assertFalse(gatheredItems.contains("minecraft:charcoal"), "charcoal has no source: " + gatheredItems);
        Executed executed = run(plan, Map.of(), Map.of());
        assertNull(executed.failure(), executed.failure());
        assertEquals(4, executed.stock().getOrDefault(TORCH, 0));
    }

    @Test
    void aSlotUsesTheAlternativeInStock() {
        Plan plan = planned(planner(), request(new Goal.HaveItem(TORCH, 4),
                snapshot(Map.of(CHARCOAL, 1), Map.of()), Exclusions.NONE));
        List<String> gatheredItems = leaves(plan).stream()
                .filter(leaf -> leaf.params().containsKey("item"))
                .map(leaf -> leaf.params().get("item"))
                .toList();
        assertFalse(gatheredItems.contains("minecraft:coal"), "the stocked charcoal must not force a coal gather: " + gatheredItems);
        Executed executed = run(plan, Map.of(CHARCOAL, 1), Map.of());
        assertNull(executed.failure(), executed.failure());
        assertEquals(4, executed.stock().getOrDefault(TORCH, 0));
    }

    // ─── The goal item itself may be gathered (item 3) ───────────────────────────────────────────

    @Test
    void aRootWithARecipeIsGatheredWhenCraftingItIsImpossible() {
        // A gold ingot smelts from gold ore, which nothing gives; a piglin drops the ingot.
        RecipeBook goldBook = bookWith(recipe("gold_ingot_from_ore", GOLD_INGOT, 1, null, one(GOLD_ORE)));
        AcquisitionIndex.Builder builder = indexBuilder(ToolTier.IRON);
        builder.addItem(GOLD_INGOT).addItem(GOLD_ORE);
        builder.addSource(new Source.KillMob("kill:minecraft:piglin", GOLD_INGOT, "minecraft:piglin", false, 1.0, true));
        SolverPlanner planner = planner(goldBook, builder.build());

        Plan plan = planned(planner, request(new Goal.HaveItem(GOLD_INGOT, 2), EMPTY, Exclusions.NONE));
        assertEquals(List.of("kill:minecraft:piglin"), sources(plan), "the ingot is gathered, not crafted");
        assertRunsToTheGoal(plan, goldBook, Map.of(), Map.of(), GOLD_INGOT, 2);
    }

    @Test
    void aRootIsCraftedWhenThatIsCheaperAndGatheredWhenItIsNoDearer() {
        // Sticks cost about six to craft from logs. A source costing less is gathered; one costing more is not.
        for (boolean cheapSource : new boolean[] {true, false}) {
            AcquisitionIndex.Builder builder = indexBuilder(ToolTier.IRON);
            builder.addSource(new Source.Harvest("harvest:minecraft:dead_bush", STICK, "minecraft:dead_bush", null,
                    false, cheapSource ? 100.0 : 1.0, false));
            SolverPlanner planner = planner(book(), builder.build());
            Plan plan = planned(planner, request(new Goal.HaveItem(STICK, 4), EMPTY, Exclusions.NONE));
            assertRunsToTheGoal(plan, book(), Map.of(), Map.of(), STICK, 4);
            assertEquals(cheapSource, sources(plan).contains("harvest:minecraft:dead_bush"),
                    "the harvest is used exactly when it is cheaper than crafting: " + sources(plan));
        }
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
        // The goal is what the executor reads the target from; how its position is spelled in the params is not
        // behaviour.
        assertEquals(goal, leaf.goal());
    }

    @Test
    void equipPlansMissingItemsThenOneEquipLeaf() {
        Goal.Equip goal = new Goal.Equip(Map.of(EquipSlot.MAINHAND, WOODEN_PICKAXE));
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
        NodeSpec blindDeposit = leaves(blind).get(leaves(blind).size() - 1);
        assertEquals("deposit", blindDeposit.taskKind());
        assertEquals(goal, blindDeposit.goal(), "the goal carries the total the container should hold");
        assertEquals("8", blindDeposit.params().get("count"), "an unknown container is given the whole count");
        assertEquals("minecraft:overworld", blindDeposit.params().get("dimension"));
        assertTrue(leaves(blind).size() > 1, "an unknown container is planned for in full");
        Executed executed = run(blind, Map.of(), Map.of());
        assertNull(executed.failure(), executed.failure());

        Snapshot.Builder knownFullBuilder = Snapshot.builder().gameTime(0);
        knownFullBuilder.container(new ContainerSight(CONTAINER, "minecraft:chest", true, false, Map.of(OAK_PLANKS, 8)));
        Plan knownFull = planned(planner(), request(goal, knownFullBuilder.build(), Exclusions.NONE));
        assertEquals(1, leaves(knownFull).size(), "the container is known to hold the stock already");
        assertEquals("deposit", leaves(knownFull).get(0).taskKind());
        assertEquals("0", leaves(knownFull).get(0).params().get("count"), "nothing is left to move");

        // Known to hold three: five are moved, and the goal still says eight.
        Snapshot.Builder partialBuilder = Snapshot.builder().gameTime(0).item(OAK_PLANKS, 5);
        partialBuilder.container(new ContainerSight(CONTAINER, "minecraft:chest", true, false, Map.of(OAK_PLANKS, 3)));
        Plan partial = planned(planner(), request(goal, partialBuilder.build(), Exclusions.NONE));
        assertEquals(1, leaves(partial).size(), "the five to move are in hand: " + leaves(partial));
        NodeSpec deposit = leaves(partial).get(0);
        assertEquals("5", deposit.params().get("count"), "count is how many to move");
        assertEquals(goal, deposit.goal(), "the goal is the total the container should hold");
    }

    @Test
    void aDepositTakesItsItemsOutOfTheWorkingStockSoALaterPlanGathersThemAgain() {
        // A strategy stockpiles three logs and then asks for three logs again: the first three went into the
        // container, so the second ask is a second gather, not already satisfied.
        Goal.Stockpile stockpile = new Goal.Stockpile(OAK_LOG, 3, "minecraft:overworld", CONTAINER);
        List<Long> logsAfterEachStep = new ArrayList<>();
        ScriptedStrategy stocker = new ScriptedStrategy("stocker", (asked, context) -> {
            if (!(asked instanceof Goal.Custom)) return Optional.empty();
            List<NodeSpec> nodes = new ArrayList<>();
            for (Goal step : List.of(stockpile, new Goal.HaveItem(OAK_LOG, 3))) {
                if (!(context.subPlan(step) instanceof PlanOutcome.Planned planned)) return Optional.empty();
                nodes.addAll(planned.plan().roots());
                logsAfterEachStep.add(context.stock().getOrDefault(OAK_LOG, 0L));
            }
            return Optional.of(new Strategy.Proposal(nodes, 1, List.of()));
        });
        Plan plan = planned(planner(stocker), request(new Goal.Custom("stock_then_carry", Map.of()), EMPTY,
                Exclusions.NONE));

        assertEquals(List.of(0L, 3L), logsAfterEachStep, "the deposit empties the working stock of its items");
        assertEquals(6, gathered(plan, OAK_LOG), "three logs for the container and three to carry");
        assertRunsToTheGoal(plan, book(), Map.of(), Map.of(), OAK_LOG, 3);
    }

    @Test
    void aBetterToolThePlanCraftedEarlierCountsForALaterTierGoalAndMiningLeaf() {
        // The working stock, not only the snapshot, says what the body carries. A diamond pickaxe made by an earlier
        // step (here from planks, with no wooden pickaxe along the way) is held by the time a wood-tier goal and a
        // mining leaf are planned after it: neither plans a wooden pickaxe of its own.
        RecipeBook diamondBook = bookWith(recipe("diamond_pickaxe_from_planks", DIAMOND_PICKAXE, 1, CRAFTING_TABLE,
                of(OAK_PLANKS, 3), of(STICK, 2)));
        List<Plan> tierPlans = new ArrayList<>();
        ScriptedStrategy sequence = new ScriptedStrategy("pickaxe-then-mining", (asked, context) -> {
            if (!(asked instanceof Goal.Custom)) return Optional.empty();
            List<NodeSpec> nodes = new ArrayList<>();
            for (Goal step : List.of(new Goal.HaveItem(DIAMOND_PICKAXE, 1),
                    new Goal.HaveToolTier(ToolClass.PICKAXE, ToolTier.WOOD), new Goal.HaveItem(COBBLESTONE, 2))) {
                if (!(context.subPlan(step) instanceof PlanOutcome.Planned planned)) return Optional.empty();
                if (step instanceof Goal.HaveToolTier) tierPlans.add(planned.plan());
                nodes.addAll(planned.plan().roots());
            }
            return Optional.of(new Strategy.Proposal(nodes, 1, List.of()));
        });
        Plan plan = planned(planner(diamondBook, index(), sequence),
                request(new Goal.Custom("tool_then_mine", Map.of()), EMPTY, Exclusions.NONE));

        assertEquals(1, tierPlans.size());
        assertTrue(tierPlans.get(0).roots().isEmpty(), "the tier is carried already: " + tierPlans.get(0).roots());
        List<String> recipesRun = leaves(plan).stream().filter(l -> l.taskKind().equals("craft"))
                .map(l -> l.params().get("recipe")).toList();
        assertFalse(recipesRun.contains("wooden_pickaxe_from_planks"),
                "the diamond pickaxe is the tool for every later leaf: " + recipesRun);
        assertEquals(List.of("mine:minecraft:stone"), sources(plan).stream()
                .filter(source -> source.startsWith("mine:minecraft:stone")).toList());
        Executed executed = assertRunsToTheGoal(plan, diamondBook, Map.of(), Map.of(), COBBLESTONE, 2);
        assertEquals(1, executed.stock().getOrDefault(DIAMOND_PICKAXE, 0));
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
        assertEquals(ENCHANTED_BOOK, ((Failure.NoSource) without).key());
    }

    @Test
    void aBuildOrCustomGoalNoStrategyPlansIsAnInternalFailureNotAMadeUpSource() {
        Goal.Custom custom = new Goal.Custom("fish_until", Map.of("item", "minecraft:string"));
        assertEquals(Failure.Kind.INTERNAL, unplannable(planner(), request(custom, EMPTY, Exclusions.NONE)).kind());
        assertEquals(Failure.Kind.INTERNAL,
                unplannable(planner(), request(new Goal.Build("hut", "minecraft:overworld", Pos.parse("1,64,1")),
                        EMPTY, Exclusions.NONE)).kind());

        FakeStrategy fisher = new FakeStrategy("manual-fishing", goal -> goal instanceof Goal.Custom
                && ((Goal.Custom) goal).kind().equals("fish_until"), 500, tradeLeaf(STRING, 1));
        Plan plan = planned(planner(fisher), request(custom, EMPTY, Exclusions.NONE));
        assertEquals(1, leaves(plan).size(), leaves(plan).toString());
        assertEquals("trade", leaves(plan).get(0).taskKind());
    }

    // ─── Strategies against gather costs (items 4 and 14) ────────────────────────────────────────

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
        List<String> dearSources = sources(dearPlan);
        assertTrue(dearSources.contains("mine:minecraft:stone"), "the dearer strategy must not replace the gather: " + dearSources);
        assertFalse(dearSources.contains("trade:fake"), dearSources.toString());
    }

    @Test
    void aStrategyIsComparedWithTheGatherOfTheWholeDemand() {
        // With mining at 1000 a block and a wooden pickaxe in hand, three cobblestone cost 3000 in all. A strategy
        // asking 2000 for the three is the cheaper plan even though 2000 is more than the 1000 one unit costs.
        Map<ItemKey, Integer> stock = Map.of(WOODEN_PICKAXE, 1);
        Snapshot withPickaxe = snapshot(stock, Map.of(ToolClass.PICKAXE, ToolTier.WOOD));
        Predicate<Goal> forCobblestone = goal -> goal instanceof Goal.HaveItem have && have.key().equals(COBBLESTONE);
        NodeSpec fake = tradeLeaf(COBBLESTONE, 3);

        Plan wins = planned(planner(dearMining(), new FakeStrategy("two-thousand", forCobblestone, 2000, fake)),
                request(new Goal.HaveItem(COBBLESTONE, 3), withPickaxe, Exclusions.NONE));
        assertEquals(List.of("trade:fake"), sources(wins), "2000 for three beats 3000 for three");

        Plan loses = planned(planner(dearMining(), new FakeStrategy("four-thousand", forCobblestone, 4000, fake)),
                request(new Goal.HaveItem(COBBLESTONE, 3), withPickaxe, Exclusions.NONE));
        assertEquals(List.of("mine:minecraft:stone"), sources(loses), "4000 for three loses to 3000 for three");
    }

    @Test
    void aStrategyIsComparedWithTheGatherToolPrerequisiteIncluded() {
        // With mining at 1000 a block, one cobblestone costs 1000 to mine, and from empty hands the gather also needs
        // a wooden pickaxe (logs, planks, a table: several thousand). A strategy asking 2000 is the cheaper plan only
        // if the pickaxe is part of what the gather costs.
        Predicate<Goal> forCobblestone = goal -> goal instanceof Goal.HaveItem have && have.key().equals(COBBLESTONE);
        Plan plan = planned(planner(dearMining(), new FakeStrategy("two-thousand", forCobblestone, 2000,
                        tradeLeaf(COBBLESTONE, 1))),
                request(new Goal.HaveItem(COBBLESTONE, 1), EMPTY, Exclusions.NONE));
        assertEquals(List.of("trade:fake"), sources(plan),
                "the pickaxe the gather needs is part of its price, and so is not made when a strategy undercuts it");

        // The tool is priced once, not also as a charge on every unit: with a tier charged at 5000 a unit the
        // gather is still about 3000 (1000 to mine, the pickaxe's few thousand once), and a strategy at 4000 loses.
        Plan dearer = planned(planner(dearMining(5000), new FakeStrategy("four-thousand", forCobblestone, 4000,
                        tradeLeaf(COBBLESTONE, 1))),
                request(new Goal.HaveItem(COBBLESTONE, 1), EMPTY, Exclusions.NONE));
        assertTrue(sources(dearer).contains("mine:minecraft:stone"), sources(dearer).toString());
        assertFalse(sources(dearer).contains("trade:fake"), sources(dearer).toString());
    }

    @Test
    void aStrategySuppliedLeafIsCreditedToTheStockSoALaterCraftFindsIt() {
        // The strategy hands over the cobblestone and touches no stock of its own: the planner credits it, and the
        // stone pickaxe is crafted from it.
        Map<ItemKey, Integer> stock = Map.of(STICK, 2, CRAFTING_TABLE, 1);
        FakeStrategy supplier = new FakeStrategy("supplier", goal -> goal instanceof Goal.HaveItem have
                && have.key().equals(COBBLESTONE), 10, tradeLeaf(COBBLESTONE, 3));
        Plan plan = planned(planner(supplier), request(new Goal.HaveItem(STONE_PICKAXE, 1),
                snapshot(stock, Map.of()), Exclusions.NONE));
        assertRunsToTheGoal(plan, book(), stock, Map.of(), STONE_PICKAXE, 1);
        assertTrue(sources(plan).contains("trade:fake"), sources(plan).toString());
    }

    @Test
    void eachStrategyIsOfferedALeafAgainstTheStockAsItWasBeforeAnyOther() {
        // The first strategy records the stock, plans sticks as a sub-plan and then loses on price; the second only
        // looks. Asked about the same leaf in the same round, it must see the stock the first one saw.
        List<Map<ItemKey, Long>> before = new ArrayList<>();
        List<Map<ItemKey, Long>> seen = new ArrayList<>();
        ScriptedStrategy greedy = new ScriptedStrategy("a-greedy", (goal, context) -> {
            if (!(goal instanceof Goal.HaveItem have) || !have.key().equals(COBBLESTONE)) return Optional.empty();
            before.add(new TreeMap<>(context.stock()));
            context.subPlan(new Goal.HaveItem(STICK, 2));
            return Optional.of(new Strategy.Proposal(List.of(tradeLeaf(COBBLESTONE, 3)), 1_000_000, List.of()));
        });
        ScriptedStrategy watcher = new ScriptedStrategy("b-watcher", (goal, context) -> {
            if (goal instanceof Goal.HaveItem have && have.key().equals(COBBLESTONE)) {
                seen.add(new TreeMap<>(context.stock()));
            }
            return Optional.empty();
        });
        Plan plan = planned(planner(greedy, watcher), request(new Goal.HaveItem(STONE_PICKAXE, 1), EMPTY,
                Exclusions.NONE));

        assertFalse(seen.isEmpty(), "the watcher was asked about the cobblestone leaf");
        assertEquals(before, seen, "the loser's sub-plan left nothing in the stock the next strategy saw");
        assertRunsToTheGoal(plan, book(), Map.of(), Map.of(), STONE_PICKAXE, 1);
    }

    @Test
    void aSubPlanForAWholeGoalIsCreditedToTheStockTheStrategyThatAskedSees() {
        // The outer strategy plans the checked book as a sub-plan (which the inner strategy supplies with a leaf
        // that touches no stock) and then reads the stock: the book is there, credited by the planner.
        Goal.HaveItem book = new Goal.HaveItem(ENCHANTED_BOOK, 1, ComponentCheck.enchantment("minecraft:mending", 1));
        List<Long> seen = new ArrayList<>();
        ScriptedStrategy inner = new ScriptedStrategy("inner", (asked, context) -> asked.equals(book)
                ? Optional.of(new Strategy.Proposal(List.of(tradeLeaf(ENCHANTED_BOOK, 1)), 10, List.of()))
                : Optional.empty());
        ScriptedStrategy outer = new ScriptedStrategy("outer", (asked, context) -> {
            if (!(asked instanceof Goal.Custom)) return Optional.empty();
            PlanOutcome outcome = context.subPlan(book);
            seen.add(context.stock().getOrDefault(ENCHANTED_BOOK, 0L));
            if (!(outcome instanceof PlanOutcome.Planned planned)) return Optional.empty();
            return Optional.of(new Strategy.Proposal(planned.plan().roots(), 100, List.of()));
        });
        Plan plan = planned(planner(inner, outer), request(new Goal.Custom("get_book", Map.of()), EMPTY, Exclusions.NONE));
        assertEquals(List.of(1L), seen);
        assertEquals(List.of("trade:fake"), sources(plan));
    }

    @Test
    void eachStrategyIsOfferedAWholeGoalAgainstTheStockAsItWasBeforeAnyOther() {
        Goal.HaveItem goal = new Goal.HaveItem(ENCHANTED_BOOK, 1, ComponentCheck.enchantment("minecraft:mending", 1));
        List<Map<ItemKey, Long>> seen = new ArrayList<>();
        // Only the checked goal is theirs: the sub-plan's own leaves are asked about too, and are not their business.
        Predicate<Goal> checked = asked -> asked instanceof Goal.HaveItem have && have.check() != null;
        ScriptedStrategy greedy = new ScriptedStrategy("a-greedy", (asked, context) -> {
            if (!checked.test(asked)) return Optional.empty();
            context.subPlan(new Goal.HaveItem(STICK, 2));
            return Optional.of(new Strategy.Proposal(List.of(tradeLeaf(ENCHANTED_BOOK, 1)), 1_000_000, List.of()));
        });
        ScriptedStrategy cheap = new ScriptedStrategy("b-cheap", (asked, context) -> checked.test(asked)
                ? Optional.of(new Strategy.Proposal(List.of(tradeLeaf(ENCHANTED_BOOK, 1)), 10, List.of()))
                : Optional.empty());
        ScriptedStrategy watcher = new ScriptedStrategy("c-watcher", (asked, context) -> {
            if (checked.test(asked)) seen.add(new TreeMap<>(context.stock()));
            return Optional.empty();
        });
        Plan plan = planned(planner(greedy, cheap, watcher), request(goal, EMPTY, Exclusions.NONE));

        assertEquals(1, seen.size());
        assertTrue(seen.get(0).isEmpty(), "the loser's sub-plan left nothing in the stock the next strategy saw: " + seen);
        assertEquals(List.of("trade:fake"), sources(plan), "the cheaper proposal's nodes alone are in the plan");
        assertEquals(1, leaves(plan).size(), "the greedy strategy's sub-plan nodes are not: " + leaves(plan));
    }

    @Test
    void aStrategySeesTheRequestAndCannotWriteTheStock() {
        Map<String, Map<String, String>> checkpoints = Map.of("node-7", Map.of("shaft", "10,40,10"));
        List<Object> seen = new ArrayList<>();
        ScriptedStrategy looker = new ScriptedStrategy("looker", (goal, context) -> {
            seen.add(context.profile());
            seen.add(context.checkpoints());
            seen.add(assertThrows(UnsupportedOperationException.class, () -> context.stock().put(STICK, 5L)));
            return Optional.empty();
        });
        Goal.Custom custom = new Goal.Custom("anything", Map.of());
        unplannable(planner(looker), new PlanRequest(custom, EMPTY, Exclusions.NONE, Profile.STRICT_SURVIVAL, checkpoints));
        assertEquals(Profile.STRICT_SURVIVAL, seen.get(0));
        assertEquals(checkpoints, seen.get(1));
    }

    // ─── Trades are paid for (item 6) ────────────────────────────────────────────────────────────

    private static Source.Trade saddleTrade(Map<ItemKey, Integer> cost, double chance) {
        return new Source.Trade("trade:minecraft:leatherworker#saddle", SADDLE, "minecraft:leatherworker", 2, cost,
                Map.of(), chance);
    }

    @Test
    void aTradeIsPrecededByThePlanForWhatItCharges() {
        AcquisitionIndex.Builder builder = indexBuilder(ToolTier.IRON);
        builder.addItem(EMERALD);
        builder.addSource(new Source.KillMob("kill:minecraft:witch", EMERALD, "minecraft:witch", false, 1.0, true));
        builder.addSource(saddleTrade(Map.of(EMERALD, 6), 0.5));
        SolverPlanner planner = planner(book(), builder.build());

        Plan plan = planned(planner, request(new Goal.HaveItem(SADDLE, 2), EMPTY, Exclusions.NONE));
        List<NodeSpec> planLeaves = leaves(plan);
        NodeSpec trade = planLeaves.get(planLeaves.size() - 1);
        assertEquals("trade", trade.taskKind());
        assertEquals("trade:minecraft:leatherworker#saddle", trade.source());
        assertEquals("2", trade.params().get("count"), "two trades for two saddles");
        assertEquals("2", trade.params().get("level"));
        assertEquals("minecraft:emerald=6", trade.params().get("cost"));
        assertEquals(0.5, Double.parseDouble(trade.params().get("chance")));
        // Two trades at six emeralds each: twelve are gathered before the trade runs.
        assertEquals(12, gathered(plan, EMERALD, "kill"), "the payment is planned like any other goal");
        assertTrue(planLeaves.indexOf(planLeaves.stream().filter(l -> l.taskKind().equals("kill")).findFirst().orElseThrow())
                < planLeaves.size() - 1, "the payment comes before the trade");
        Executed executed = assertRunsToTheGoal(plan, book(), Map.of(), Map.of(), SADDLE, 2);
        assertEquals(0, executed.stock().getOrDefault(EMERALD, 0), "the trade used the payment up");
    }

    @Test
    void aTradeWhosePaymentCannotBePlannedIsNotUsedAndTheNextSourceIs() {
        // Emeralds are mined with a diamond pickaxe. In the first variant the book has no way to make one, which the
        // cost model sees (the trade is priced out); in the second it has a recipe whose ingredient nothing gives,
        // which only planning the payment finds out: the trade is the cheapest source (ahead of fishing at 24000)
        // and is given up when its payment cannot be planned.
        for (boolean recipeForThePickaxe : new boolean[] {false, true}) {
            RecipeBook pickaxeBook = recipeForThePickaxe
                    ? bookWith(recipe("diamond_pickaxe_from_unobtainium", DIAMOND_PICKAXE, 1, CRAFTING_TABLE,
                            one(UNOBTAINIUM)))
                    : book();
            AcquisitionIndex.Builder builder = indexBuilder(ToolTier.IRON);
            builder.addItem(EMERALD).addItem(UNOBTAINIUM);
            builder.addSource(new Source.MineBlock("mine:minecraft:emerald_ore", EMERALD, "minecraft:emerald_ore",
                    ToolClass.PICKAXE, ToolTier.DIAMOND, true, false, false, 1.0, false, null));
            builder.addSource(saddleTrade(Map.of(EMERALD, 6), 1.0));
            builder.addSource(new Source.Fishing("fish:minecraft:gameplay/saddle", SADDLE, "minecraft:gameplay/saddle", 0.05));
            SolverPlanner planner = planner(pickaxeBook, builder.build());

            Plan plan = planned(planner, request(new Goal.HaveItem(SADDLE, 1), EMPTY, Exclusions.NONE));
            assertEquals(List.of("fish:minecraft:gameplay/saddle"), sources(plan),
                    "the trade cannot be paid for, so the next source is used (recipe for the pickaxe: "
                            + recipeForThePickaxe + ")");

            Failure failure = unplannable(planner, request(new Goal.HaveItem(SADDLE, 1), EMPTY,
                    without("fish:minecraft:gameplay/saddle")));
            assertEquals(Failure.Kind.NO_SOURCE, failure.kind());
            assertEquals(recipeForThePickaxe ? UNOBTAINIUM : DIAMOND_PICKAXE, ((Failure.NoSource) failure).key(),
                    "the failure is the trade's: its payment needs a pickaxe that nothing can make");
        }
    }

    @Test
    void aTradesCostPerUnitIncludesWhatItCharges() {
        // A trade for a saddle costs 3000 a try before payment; ten emeralds (600 each to kill for) in payment make it
        // dearer than fishing (6000 a saddle), though without the payment it would be the cheaper source. The
        // payment can be planned, so a plan that took the trade anyway would run: only its price keeps it out.
        AcquisitionIndex.Builder builder = indexBuilder(ToolTier.IRON);
        builder.addSource(new Source.KillMob("kill:minecraft:witch", EMERALD, "minecraft:witch", false, 1.0, true));
        builder.addSource(saddleTrade(Map.of(EMERALD, 10), 1.0));
        builder.addSource(new Source.Fishing("fish:minecraft:gameplay/saddle", SADDLE, "minecraft:gameplay/saddle", 0.2));
        SolverPlanner planner = planner(book(), builder.build());
        Plan plan = planned(planner, request(new Goal.HaveItem(SADDLE, 1), EMPTY, Exclusions.NONE));
        assertEquals(List.of("fish:minecraft:gameplay/saddle"), sources(plan));
    }

    @Test
    void aTradeThatComesUpRarelyCostsMorePerUnitThanOneThatAlwaysDoes() {
        // A free trade is 3000 a try; at one offer in ten it is 30000 a saddle, dearer than fishing at 6000.
        for (double chance : new double[] {1.0, 0.1}) {
            AcquisitionIndex.Builder builder = indexBuilder(ToolTier.IRON);
            builder.addSource(saddleTrade(Map.of(), chance));
            builder.addSource(new Source.Fishing("fish:minecraft:gameplay/saddle", SADDLE, "minecraft:gameplay/saddle", 0.2));
            Plan plan = planned(planner(book(), builder.build()),
                    request(new Goal.HaveItem(SADDLE, 1), EMPTY, Exclusions.NONE));
            assertEquals(List.of(chance == 1.0 ? "trade:minecraft:leatherworker#saddle" : "fish:minecraft:gameplay/saddle"),
                    sources(plan), "chance " + chance);
        }
    }

    @Test
    void aTradeSpendsWhatItChargesFromTheStockSoALaterCraftGathersItsOwn() {
        // The six emeralds in hand are the trade's payment; the fancy saddle also needs one that the trade does not
        // leave, so one more is gathered after it.
        ItemKey fancy = ItemKey.of("fancy_saddle");
        RecipeBook fancyBook = bookWith(recipe("fancy_saddle_from_saddle", fancy, 1, null, one(SADDLE), one(EMERALD)));
        AcquisitionIndex.Builder builder = indexBuilder(ToolTier.IRON);
        builder.addSource(new Source.KillMob("kill:minecraft:witch", EMERALD, "minecraft:witch", false, 1.0, true));
        builder.addSource(saddleTrade(Map.of(EMERALD, 6), 1.0));
        Map<ItemKey, Integer> stock = Map.of(EMERALD, 6);
        Plan plan = planned(planner(fancyBook, builder.build()),
                request(new Goal.HaveItem(fancy, 1), snapshot(stock, Map.of()), Exclusions.NONE));
        assertRunsToTheGoal(plan, fancyBook, stock, Map.of(), fancy, 1);
        assertEquals(1, gathered(plan, EMERALD, "kill"), "one emerald beyond the payment is gathered");
    }

    @Test
    void tradesThatPayInEachOthersGoodsEndInAFailureNotARecursion() {
        // A saddle costs an emerald and an emerald costs a saddle, and nothing else gives either.
        AcquisitionIndex.Builder builder = indexBuilder(ToolTier.IRON);
        builder.addSource(saddleTrade(Map.of(EMERALD, 1), 1.0));
        builder.addSource(new Source.Trade("trade:minecraft:cleric#emerald", EMERALD, "minecraft:cleric", 1,
                Map.of(SADDLE, 1), Map.of(), 1.0));
        SolverPlanner planner = planner(book(), builder.build());
        Failure failure = unplannable(planner, request(new Goal.HaveItem(SADDLE, 1), EMPTY, Exclusions.NONE));
        assertEquals(Failure.Kind.NO_SOURCE, failure.kind());
    }

    @Test
    void aTradesPaymentIsCommittedBeforeTheShortfallIsTrustedSoTheCraftsStillFindTheirOwnStock() {
        // A widget takes ten emeralds and a saddle; the only saddle is a trade charging twelve emeralds, and emeralds
        // come from kills. The payment must not eat the ten the widget itself needs: the plan gathers twenty-two in
        // all, less what the body holds, however far the payment overshoots what a one-unit repair could cover.
        RecipeBook widgetBook = bookWith(recipe("widget_from_emeralds_and_saddle", WIDGET, 1, null,
                of(EMERALD, 10), one(SADDLE)));
        AcquisitionIndex.Builder builder = indexBuilder(ToolTier.IRON);
        builder.addItem(EMERALD).addItem(WIDGET);
        builder.addSource(new Source.KillMob("kill:minecraft:witch", EMERALD, "minecraft:witch", false, 1.0, true));
        builder.addSource(saddleTrade(Map.of(EMERALD, 12), 1.0));
        SolverPlanner planner = planner(widgetBook, builder.build());

        // The emerald gathers in plan order, by emeralds in hand at the start: the payment's first (twelve less what is
        // held), then the widget's ten less whatever the trade left. Each is one leaf: a plan that found the payment
        // out only when the widget's craft ran short would gather in pieces.
        Map<Integer, List<Long>> expected = new TreeMap<>(Map.of(
                0, List.of(12L, 10L), 4, List.of(8L, 10L), 10, List.of(2L, 10L), 12, List.of(10L), 25, List.<Long>of()));
        for (Map.Entry<Integer, List<Long>> entry : expected.entrySet()) {
            int held = entry.getKey();
            Map<ItemKey, Integer> stock = held == 0 ? Map.of() : Map.of(EMERALD, held);
            Plan plan = planned(planner, request(new Goal.HaveItem(WIDGET, 1), snapshot(stock, Map.of()),
                    Exclusions.NONE));
            assertRunsToTheGoal(plan, widgetBook, stock, Map.of(), WIDGET, 1);
            assertEquals(entry.getValue(), gatherCounts(plan, EMERALD), "emerald gathers with " + held + " in hand");
        }
    }

    @Test
    void everyGoalOverTradedItemsFromAnySeededStockRunsToItsEndWithEveryLeafGoalMet() {
        // Trades whose payments overlap what the recipes need: emeralds for a saddle and for a lead, sticks (crafted
        // from logs) for the lead, and recipes that want the same emeralds and sticks as well. Seeded, so a failure
        // reproduces; every plan must run in order with each leaf's own goal met when it ends.
        RecipeBook traded = bookWith(
                recipe("widget_from_emeralds_and_saddle", WIDGET, 1, null, of(EMERALD, 10), one(SADDLE)),
                recipe("gizmo_from_lead_and_emeralds", GIZMO, 1, null, one(LEAD), of(EMERALD, 3), of(STICK, 2)),
                recipe("mount_kit_from_saddles_and_chest", MOUNT_KIT, 1, CRAFTING_TABLE, of(SADDLE, 2), one(CHEST)));
        AcquisitionIndex.Builder builder = indexBuilder(ToolTier.IRON);
        builder.addItem(EMERALD).addItem(LEAD).addItem(WIDGET).addItem(GIZMO).addItem(MOUNT_KIT);
        builder.addSource(new Source.KillMob("kill:minecraft:witch", EMERALD, "minecraft:witch", false, 1.0, true));
        builder.addSource(saddleTrade(Map.of(EMERALD, 12), 0.5));
        builder.addSource(new Source.Trade("trade:minecraft:leatherworker#lead", LEAD, "minecraft:leatherworker", 1,
                Map.of(STICK, 4, EMERALD, 2), Map.of(), 1.0));
        SolverPlanner planner = planner(traded, builder.build());

        List<ItemKey> goals = List.of(WIDGET, GIZMO, MOUNT_KIT, SADDLE, LEAD, EMERALD, STICK);
        List<ItemKey> stocked = List.of(EMERALD, STICK, OAK_PLANKS, OAK_LOG, SADDLE, LEAD, CHEST, CRAFTING_TABLE);
        java.util.Random random = new java.util.Random(7);
        for (int round = 0; round < 300; round++) {
            Map<ItemKey, Integer> stock = new TreeMap<>();
            for (ItemKey item : stocked) {
                if (random.nextInt(3) == 0) stock.put(item, 1 + random.nextInt(item.equals(EMERALD) ? 30 : 6));
            }
            ItemKey goalItem = goals.get(random.nextInt(goals.size()));
            int count = 1 + random.nextInt(goalItem.equals(EMERALD) ? 40 : 3);
            String context = "round " + round + ": " + count + " " + goalItem.id() + " from " + stock;

            PlanOutcome outcome = planner.plan(request(new Goal.HaveItem(goalItem, count), snapshot(stock, Map.of()),
                    Exclusions.NONE));
            assertTrue(outcome instanceof PlanOutcome.Planned, context + ": " + outcome);
            Executed executed = run(((PlanOutcome.Planned) outcome).plan(), traded, stock, Map.of());
            assertNull(executed.failure(), context + ": " + executed.failure());
            assertTrue(executed.stock().getOrDefault(goalItem, 0) >= count, context + " ended with " + executed.stock());
        }
    }

    @Test
    void aTradeThatChargesTheItemItSellsIsNeverAWayToGainIt() {
        // One saddle for one saddle gains nothing: the trade is unusable, however cheap it prices (6000 with its
        // payment against 12000 a saddle by fishing). A plan that took it anyway would end its trade leaf with fewer
        // saddles than that leaf's own goal says.
        AcquisitionIndex.Builder builder = indexBuilder(ToolTier.IRON);
        builder.addSource(new Source.Fishing("fish:minecraft:gameplay/saddle", SADDLE, "minecraft:gameplay/saddle", 0.1));
        builder.addSource(saddleTrade(Map.of(SADDLE, 1), 1.0));
        Plan plan = planned(planner(book(), builder.build()), request(new Goal.HaveItem(SADDLE, 2), EMPTY, Exclusions.NONE));
        assertEquals(List.of("fish:minecraft:gameplay/saddle"), sources(plan));
        assertRunsToTheGoal(plan, book(), Map.of(), Map.of(), SADDLE, 2);

        AcquisitionIndex.Builder tradeOnly = indexBuilder(ToolTier.IRON);
        tradeOnly.addSource(saddleTrade(Map.of(SADDLE, 1), 1.0));
        Failure failure = unplannable(planner(book(), tradeOnly.build()),
                request(new Goal.HaveItem(SADDLE, 1), EMPTY, Exclusions.NONE));
        assertEquals(Failure.Kind.NO_SOURCE, failure.kind());
        assertEquals(SADDLE, ((Failure.NoSource) failure).key());
    }

    // ─── A gather that cannot be planned is not the cheapest way (brief P12c item 2) ─────────────

    /** Gathering and mining cost next to nothing, so a mining source beats any recipe unless it is priced out. */
    private static PlannerConfig freeMining() {
        PlannerConfig defaults = PlannerConfig.defaults();
        return new PlannerConfig(1, defaults.deepOre(), 0, defaults.harvest(), defaults.kill(), defaults.fish(),
                defaults.trade(), defaults.sightStep(), defaults.unseenPenalty(), defaults.maxTotalReplans(),
                defaults.toolItems());
    }

    @Test
    void anItemWithARecipeIsCraftedWhenTheToolOfItsOnlyMiningSourceCannotBeHad() {
        // A stone shovel is made from cobblestone and sticks. The block that drops one for free needs a netherite
        // pickaxe, which nothing in the index or the book gives: priced as the cheaper way it would be listed as a
        // gather whose every tool chain fails; priced out, the solver's own rule crafts the shovel.
        AcquisitionIndex.Builder builder = indexBuilder(ToolTier.IRON);
        builder.addSource(new Source.MineBlock("mine:minecraft:shovel_ore", STONE_SHOVEL, "minecraft:shovel_ore",
                ToolClass.PICKAXE, ToolTier.NETHERITE, true, false, false, 1.0, false, null));
        FakeStrategy watcher = new FakeStrategy("watcher", goal -> false, 1);
        SolverPlanner planner = new SolverPlanner(book(), builder.build(), freeMining(), List.of(watcher));

        Plan plan = planned(planner, request(new Goal.HaveItem(STONE_SHOVEL, 1), EMPTY, Exclusions.NONE));
        // Every leaf the planner decides to gather is offered to the strategies: the shovel never was, so gathering
        // it was never the plan, not a plan that was tried and given up.
        assertFalse(watcher.asked.stream().anyMatch(goal -> goal instanceof Goal.HaveItem have
                && have.key().equals(STONE_SHOVEL)), "offered as a gather: " + watcher.asked);
        assertFalse(sources(plan).contains("mine:minecraft:shovel_ore"), sources(plan).toString());
        assertTrue(leaves(plan).stream().anyMatch(l -> l.taskKind().equals("craft")
                && l.params().get("recipe").equals("stone_shovel_from_cobblestone")), "the shovel is crafted: " + leaves(plan));
        assertRunsToTheGoal(plan, book(), Map.of(), Map.of(), STONE_SHOVEL, 1);
    }

    @Test
    void aGatherThatFailsAtPlanningTimeForAReasonThePricesCouldNotSeeIsSetAsideAndTheItemIsCrafted() {
        // The diamond axe that the free shovel block needs has a recipe, so it looks obtainable and the shovel is
        // priced as a gather; but the recipe's ingredient has no source, which only planning the axe finds out. The
        // goal is then planned again with that block set aside, and the shovel is crafted.
        RecipeBook axeBook = bookWith(recipe("diamond_axe_from_unobtainium", ItemKey.of("diamond_axe"), 1,
                CRAFTING_TABLE, one(UNOBTAINIUM)));
        AcquisitionIndex.Builder builder = indexBuilder(ToolTier.IRON);
        builder.addItem(UNOBTAINIUM);
        builder.addSource(new Source.MineBlock("mine:minecraft:shovel_ore", STONE_SHOVEL, "minecraft:shovel_ore",
                ToolClass.AXE, ToolTier.DIAMOND, true, false, false, 1.0, false, null));
        SolverPlanner planner = new SolverPlanner(axeBook, builder.build(), freeMining(), List.of());

        Plan plan = planned(planner, request(new Goal.HaveItem(STONE_SHOVEL, 1), EMPTY, Exclusions.NONE));
        assertFalse(sources(plan).contains("mine:minecraft:shovel_ore"), sources(plan).toString());
        assertRunsToTheGoal(plan, axeBook, Map.of(), Map.of(), STONE_SHOVEL, 1);

        // The set-aside does not outlive the request: a stocked diamond axe makes the block usable again.
        Map<ItemKey, Integer> stock = Map.of(ItemKey.of("diamond_axe"), 1);
        Plan again = planned(planner, request(new Goal.HaveItem(STONE_SHOVEL, 1),
                snapshot(stock, Map.of(ToolClass.AXE, ToolTier.DIAMOND)), Exclusions.NONE));
        assertEquals(List.of("mine:minecraft:shovel_ore"), sources(again));
    }

    // ─── The item loop converges and does not eat its own goal (item 11) ─────────────────────────

    private static final ItemKey T = ItemKey.of("t");
    private static final ItemKey W = ItemKey.of("w");

    private static AcquisitionIndex tAndWIndex() {
        AcquisitionIndex.Builder builder = AcquisitionIndex.builder();
        builder.addItem(T).addItem(W);
        // Mined from a block of another name: a block that only drops itself and has a recipe is not a source.
        builder.addSource(new Source.MineBlock("mine:minecraft:w_rock", W, "minecraft:w_rock", null, ToolTier.HAND,
                false, false, false, 1.0, false, null));
        return builder.build();
    }

    @Test
    void aSolveThatWouldSpendTheOnlyUnitOfTheGoalItemIsKeptOffIt() {
        // T is made from one W; W is made from one T and can also be gathered. With one T in hand and two wanted, a
        // solve that makes W from the T in hand makes no progress.
        RecipeBook cycle = new SimpleRecipeBook(List.of(
                recipe("t_from_w", T, 1, null, one(W)),
                recipe("w_from_t", W, 1, null, one(T))), Map.of());
        SolverPlanner planner = planner(cycle, tAndWIndex());
        Map<ItemKey, Integer> stock = Map.of(T, 1);
        Plan plan = planned(planner, request(new Goal.HaveItem(T, 2), snapshot(stock, Map.of()), Exclusions.NONE));
        assertRunsToTheGoal(plan, cycle, stock, Map.of(), T, 2);
        assertEquals(1, gathered(plan, W), "one W is gathered to make the second T");
    }

    @Test
    void aThirdCraftingTableIsNotMadeByTakingTheFirstTwoApart() {
        // Planks can be made from a log or by taking a table apart. Two tables are in hand and three are wanted.
        RecipeBook takingApart = bookWith(recipe("planks_from_table", OAK_PLANKS, 4, null, one(CRAFTING_TABLE)));
        SolverPlanner planner = planner(takingApart, index());
        Map<ItemKey, Integer> stock = Map.of(CRAFTING_TABLE, 2);
        Plan plan = planned(planner, request(new Goal.HaveItem(CRAFTING_TABLE, 3), snapshot(stock, Map.of()),
                Exclusions.NONE));
        Executed executed = assertRunsToTheGoal(plan, takingApart, stock, Map.of(), CRAFTING_TABLE, 3);
        assertEquals(3, executed.stock().getOrDefault(CRAFTING_TABLE, 0));
    }

    @Test
    void aSolveThatStillReportsMissingItemsAfterTheGatherGathersThemAndSolvesAgain() {
        // A made-up graph (found by a seeded search over random ones) on which the solver's own shortfall for n6 comes
        // back flagged approximate and lists an n1 less than the plan then draws. After the gather the first solve
        // reports n1 missing; the planner gathers it and solves again instead of giving up.
        ItemKey[] n = new ItemKey[8];
        for (int i = 0; i < n.length; i++) n[i] = ItemKey.of("n" + i);
        RecipeBook graph = new SimpleRecipeBook(List.of(
                recipe("r3", n[3], 2, null, one(n[2])),
                recipe("r4", n[4], 3, null, one(n[3]), one(n[1]), either(n[1], n[3])),
                recipe("r5a", n[5], 3, null, new RecipeDef.Slot(List.of(n[3], n[0]), 2), either(n[1], n[0]), one(n[2])),
                recipe("r5b", n[5], 1, n[3], either(n[1], n[3]), either(n[0], n[3])),
                recipe("r6", n[6], 1, n[4], one(n[1])),
                recipe("r7a", n[7], 3, null, either(n[0], n[1]), either(n[0], n[1])),
                recipe("r7b", n[7], 1, null, either(n[0], n[3]), one(n[4]), one(n[1]))), Map.of());
        AcquisitionIndex.Builder builder = AcquisitionIndex.builder();
        // Yields 1, 0.75 and 0.46 make the gather costs 40, 54 and 87: n0 is the cheapest to gather, n2 the dearest.
        double[] yields = {1.0, 0.75, 0.46};
        for (int i = 0; i < yields.length; i++) {
            builder.addItem(n[i]).addSource(new Source.MineBlock("mine:minecraft:n" + i, n[i], "minecraft:n" + i,
                    null, ToolTier.HAND, false, false, false, yields[i], false, null));
        }
        SolverPlanner planner = planner(graph, builder.build());

        Plan plan = planned(planner, request(new Goal.HaveItem(n[6], 1), EMPTY, Exclusions.NONE));
        assertRunsToTheGoal(plan, graph, Map.of(), Map.of(), n[6], 1);
    }

    @Test
    void aSolveThatIsStillShortAfterTheGatherTakesTheShortfallAgainInsteadOfGatheringOneUnitAtATime() {
        // A made-up graph (found by the same seeded search) on which the first shortfall lists less than the plan
        // draws, and the gap is far past what six single units would cover: the shortfall taken again against the
        // stock after the first gather names the amounts, and the second gather closes it.
        ItemKey[] n = new ItemKey[5];
        for (int i = 0; i < n.length; i++) n[i] = ItemKey.of("n" + i);
        RecipeBook graph = new SimpleRecipeBook(List.of(
                recipe("r3a", n[3], 1, null, new RecipeDef.Slot(List.of(n[2], n[1]), 2)),
                recipe("r3b", n[3], 3, null, new RecipeDef.Slot(List.of(n[0], n[1]), 3)),
                recipe("r4a", n[4], 1, n[2], new RecipeDef.Slot(List.of(n[0], n[1]), 3), of(n[0], 1),
                        new RecipeDef.Slot(List.of(n[1], n[2]), 1)),
                recipe("r4b", n[4], 1, null, one(n[2]), of(n[3], 3), of(n[0], 3))), Map.of());
        AcquisitionIndex.Builder builder = AcquisitionIndex.builder();
        double[] yields = {0.46, 0.75, 0.46};
        for (int i = 0; i < yields.length; i++) {
            builder.addItem(n[i]).addSource(new Source.MineBlock("mine:minecraft:n" + i, n[i], "minecraft:n" + i,
                    null, ToolTier.HAND, false, false, false, yields[i], false, null));
        }
        SolverPlanner planner = planner(graph, builder.build());

        Plan plan = planned(planner, request(new Goal.HaveItem(n[4], 4), EMPTY, Exclusions.NONE));
        assertRunsToTheGoal(plan, graph, Map.of(), Map.of(), n[4], 4);
    }

    @Test
    void aSubPlanLeavesTheRemaindersOfWhatItConsumedInTheStock() {
        // Three milk buckets make a cake and come back as three empty buckets: a strategy that plans the cake as a
        // sub-plan sees the buckets in the working stock afterwards.
        ItemKey milk = ItemKey.of("milk_bucket");
        ItemKey bucket = ItemKey.of("bucket");
        ItemKey cake = ItemKey.of("cake");
        RecipeBook cakes = new SimpleRecipeBook(List.of(recipe("cake_from_milk", cake, 1, null, of(milk, 3))),
                Map.of(milk, bucket));
        AcquisitionIndex.Builder builder = AcquisitionIndex.builder();
        builder.addSource(new Source.KillMob("kill:minecraft:cow", milk, "minecraft:cow", false, 1.0, false));
        List<Map<ItemKey, Long>> seen = new ArrayList<>();
        ScriptedStrategy baker = new ScriptedStrategy("baker", (asked, context) -> {
            if (!(asked instanceof Goal.Custom)) return Optional.empty();
            PlanOutcome outcome = context.subPlan(new Goal.HaveItem(cake, 1));
            seen.add(new TreeMap<>(context.stock()));
            return outcome instanceof PlanOutcome.Planned planned
                    ? Optional.of(new Strategy.Proposal(planned.plan().roots(), 1, List.of())) : Optional.empty();
        });
        planned(planner(cakes, builder.build(), baker), request(new Goal.Custom("bake", Map.of()), EMPTY, Exclusions.NONE));
        assertEquals(List.of(Map.of(cake, 1L, bucket, 3L)), seen);
    }

    // ─── Craft leaves carry the solver's slot choice (item 5) ────────────────────────────────────

    @Test
    void aCraftLeafNamesTheItemsTheStockAdvanceUsed() {
        // Every craft leaf of a whole plan carries a layout that covers exactly its crafts.
        Plan whole = planned(planner(), request(new Goal.HaveItem(STONE_PICKAXE, 1), EMPTY, Exclusions.NONE));
        for (NodeSpec leaf : leaves(whole)) {
            if (!leaf.taskKind().equals("craft")) continue;
            long covered = 0;
            for (CraftLayout.Run run : CraftLayout.decode(leaf.params().get("layout"))) covered += run.crafts();
            assertEquals(Long.parseLong(leaf.params().get("times")), covered, "layout of " + leaf.params());
        }

        // Torches take coal or charcoal. With one of each and sticks in hand, two crafts take the coal and then
        // the charcoal: the layout says so, and following it leaves the stock the plan expected.
        Map<ItemKey, Integer> stock = Map.of(COAL, 1, CHARCOAL, 1, STICK, 2);
        Plan plan = planned(planner(), request(new Goal.HaveItem(TORCH, 8), snapshot(stock, Map.of()), Exclusions.NONE));
        List<NodeSpec> planLeaves = leaves(plan);
        assertEquals(1, planLeaves.size(), planLeaves.toString());
        List<CraftLayout.Run> runs = CraftLayout.decode(planLeaves.get(0).params().get("layout"));
        assertEquals(List.of(new CraftLayout.Run(1, List.of(COAL, STICK)), new CraftLayout.Run(1, List.of(CHARCOAL, STICK))),
                runs);
        Executed executed = assertRunsToTheGoal(plan, book(), stock, Map.of(), TORCH, 8);
        assertEquals(0, executed.stock().getOrDefault(COAL, 0));
        assertEquals(0, executed.stock().getOrDefault(CHARCOAL, 0));

        // Only charcoal in hand: both crafts take it.
        Map<ItemKey, Integer> charcoalOnly = Map.of(CHARCOAL, 2, STICK, 2);
        Plan second = planned(planner(), request(new Goal.HaveItem(TORCH, 8), snapshot(charcoalOnly, Map.of()),
                Exclusions.NONE));
        assertEquals(List.of(new CraftLayout.Run(2, List.of(CHARCOAL, STICK))),
                CraftLayout.decode(leaves(second).get(0).params().get("layout")));
        assertRunsToTheGoal(second, book(), charcoalOnly, Map.of(), TORCH, 8);
    }

    // ─── Leaf params (items 13 and 15) ───────────────────────────────────────────────────────────

    @Test
    void aMiningLeafSaysItNeedsAToolOnlyWhenThePlanProvidesOne() {
        // Sand drops with a bare hand; a shovel only digs it faster. The leaf must not claim a requirement that no
        // prerequisite was planned for.
        AcquisitionIndex.Builder builder = indexBuilder(ToolTier.IRON);
        builder.addItem(SAND);
        builder.addSource(new Source.MineBlock("mine:minecraft:sand", SAND, "minecraft:sand", ToolClass.SHOVEL,
                ToolTier.WOOD, false, false, false, 1.0, false, null));
        SolverPlanner planner = planner(book(), builder.build());
        Plan plan = planned(planner, request(new Goal.HaveItem(SAND, 3), EMPTY, Exclusions.NONE));
        assertEquals(1, leaves(plan).size(), "no tool is planned for a block that needs none: " + leaves(plan));
        Map<String, String> params = leaves(plan).get(0).params();
        assertFalse(params.containsKey("toolClass"), params.toString());
        assertFalse(params.containsKey("minTier"), params.toString());
        assertRunsToTheGoal(plan, book(), Map.of(), Map.of(), SAND, 3);

        // And a required tool is written, with its tier.
        Plan stone = planned(planner(), request(new Goal.HaveItem(COBBLESTONE, 1), EMPTY, Exclusions.NONE));
        NodeSpec mine = leaves(stone).stream().filter(l -> l.taskKind().equals("mine")
                && "minecraft:cobblestone".equals(l.params().get("item"))).findFirst().orElseThrow();
        assertEquals("PICKAXE", mine.params().get("toolClass"));
        assertEquals("WOOD", mine.params().get("minTier"));
    }

    @Test
    void aKillLeafSaysWhetherThePlayerMustDealTheKill() {
        Plan plan = planned(planner(), request(new Goal.HaveItem(STRING, 1), EMPTY, Exclusions.NONE));
        NodeSpec kill = leaves(plan).get(0);
        assertEquals("kill", kill.taskKind());
        assertEquals("true", kill.params().get("playerKillOnly"));
    }

    // ─── Any goal from any stock runs to its end ─────────────────────────────────────────────────

    @Test
    void everyFixtureGoalFromAnySeededStockRunsToItsEndWithEveryLeafGoalMet() {
        // The behaviour the planner exists for, over many shapes of stock: a plan is executable in order, every leaf
        // meets its own goal when it ends, and the goal count is held at the end. Seeded, so a failure reproduces.
        List<ItemKey> goals = List.of(OAK_PLANKS, STICK, CRAFTING_TABLE, WOODEN_PICKAXE, STONE_PICKAXE, FURNACE, CHEST,
                TORCH, COBBLESTONE, COAL, STRING, ItemKey.of("wooden_axe"), ItemKey.of("stone_shovel"));
        List<ItemKey> stocked = List.of(OAK_LOG, OAK_PLANKS, STICK, COBBLESTONE, CRAFTING_TABLE, WOODEN_PICKAXE, COAL,
                CHARCOAL, STONE_PICKAXE);
        java.util.Random random = new java.util.Random(12);
        SolverPlanner planner = planner();
        for (int round = 0; round < 250; round++) {
            Map<ItemKey, Integer> stock = new TreeMap<>();
            for (ItemKey item : stocked) {
                if (random.nextInt(3) == 0) stock.put(item, 1 + random.nextInt(12));
            }
            Map<ToolClass, ToolTier> tools = new TreeMap<>();
            if (stock.containsKey(STONE_PICKAXE)) tools.put(ToolClass.PICKAXE, ToolTier.STONE);
            else if (stock.containsKey(WOODEN_PICKAXE)) tools.put(ToolClass.PICKAXE, ToolTier.WOOD);
            ItemKey goalItem = goals.get(random.nextInt(goals.size()));
            int count = 1 + random.nextInt(20);
            String context = "round " + round + ": " + count + " " + goalItem.id() + " from " + stock;

            PlanOutcome outcome = planner.plan(request(new Goal.HaveItem(goalItem, count), snapshot(stock, tools),
                    Exclusions.NONE));
            assertTrue(outcome instanceof PlanOutcome.Planned, context + ": " + outcome);
            Plan plan = ((PlanOutcome.Planned) outcome).plan();
            Executed executed = run(plan, stock, tools);
            assertNull(executed.failure(), context + ": " + executed.failure());
            assertTrue(executed.stock().getOrDefault(goalItem, 0) >= count, context + " ended with " + executed.stock());
        }
    }

    // ─── Containers the body may take from (design 3.5, supply containers) ───────────────────────

    private static final Pos SHELF_A = new Pos(10, 64, 4);
    private static final Pos SHELF_B = new Pos(-3, 64, 9);

    /** A container its owner named, out of sight: the contents are what the body remembers of it. */
    private static ContainerSight supplyBox(Pos at, Map<ItemKey, Integer> contents) {
        return new ContainerSight(at, "minecraft:chest", false, false, true, true, contents);
    }

    /** A container the body did not place and was not told to use, as a snapshot shows one it has seen. */
    private static ContainerSight strangersBox(Pos at, Map<ItemKey, Integer> contents) {
        return new ContainerSight(at, "minecraft:chest", true, false, false, false, contents);
    }

    private static Snapshot snapshotWith(Map<ItemKey, Integer> items, ContainerSight... boxes) {
        Snapshot.Builder builder = Snapshot.builder().gameTime(0).dimension("minecraft:overworld").pos(new Pos(0, 64, 0));
        items.forEach(builder::item);
        for (ContainerSight box : boxes) builder.container(box);
        return builder.build();
    }

    private static List<NodeSpec> withdrawals(Plan plan) {
        return leaves(plan).stream().filter(leaf -> leaf.taskKind().equals("withdraw")).toList();
    }

    private static long withdrawn(Plan plan, ItemKey item) {
        long total = 0;
        for (NodeSpec leaf : withdrawals(plan)) {
            if (leaf.params().get("item").equals(item.id())) total += Long.parseLong(leaf.params().get("count"));
        }
        return total;
    }

    @Test
    void partOfACraftInAContainerShrinksTheGatherByThatMuchAndIsWithdrawnBeforeTheCrafts() {
        // A chest takes eight planks and stands at a crafting table of four: twelve planks, three logs, from nothing.
        Goal goal = new Goal.HaveItem(CHEST, 1);
        Plan bare = planned(planner(), request(goal, EMPTY, Exclusions.NONE));
        long logsFromNothing = gathered(bare, OAK_LOG, "mine");
        assertEquals(3, logsFromNothing, leaves(bare).toString());
        assertTrue(withdrawals(bare).isEmpty(), "no container, nothing to take from");

        Snapshot snapshot = snapshotWith(Map.of(), supplyBox(SHELF_A, Map.of(OAK_PLANKS, 4)));
        Plan plan = planned(planner(), request(goal, snapshot, Exclusions.NONE));
        assertEquals(logsFromNothing - 1, gathered(plan, OAK_LOG, "mine"), "four planks in the container are one log fewer");

        assertEquals(1, withdrawals(plan).size(), leaves(plan).toString());
        NodeSpec take = withdrawals(plan).get(0);
        assertEquals("4", take.params().get("count"));
        assertEquals(OAK_PLANKS.id(), take.params().get("item"));
        assertEquals(SHELF_A.encode(), take.params().get("container"));
        assertEquals("minecraft:overworld", take.params().get("dimension"));
        assertEquals(new Goal.HaveItem(OAK_PLANKS, 4), take.goal(), "the goal is the count held once the leaf is done");
        List<NodeSpec> order = leaves(plan);
        for (int i = 0; i < order.size(); i++) {
            if (order.get(i).taskKind().equals("craft")) {
                assertTrue(i > order.indexOf(take), "the planks are taken before any craft that uses them: " + order);
            }
        }

        Shelved shelved = runWithContainers(plan, Map.of(), Map.of(SHELF_A, Map.of(OAK_PLANKS, 4)));
        assertNull(shelved.executed().failure(), shelved.executed().failure() + " after " + shelved.executed().ran());
        assertEquals(1, shelved.executed().stock().getOrDefault(CHEST, 0));
        assertEquals(0, shelved.left().get(SHELF_A).getOrDefault(OAK_PLANKS, 0), "the container gave what it was asked for");
    }

    @Test
    void aContainerHoldingAllOfTheNeedLeavesNothingToGather() {
        Goal goal = new Goal.HaveItem(CHEST, 1);
        Snapshot snapshot = snapshotWith(Map.of(), supplyBox(SHELF_A, Map.of(OAK_PLANKS, 12)));
        Plan plan = planned(planner(), request(goal, snapshot, Exclusions.NONE));
        assertTrue(leaves(plan).stream().noneMatch(leaf -> leaf.taskKind().equals("mine")), leaves(plan).toString());
        assertEquals(12, withdrawn(plan, OAK_PLANKS));
        assertEquals("withdraw", leaves(plan).get(0).taskKind(), "the planks come first");

        Shelved shelved = runWithContainers(plan, Map.of(), Map.of(SHELF_A, Map.of(OAK_PLANKS, 12)));
        assertNull(shelved.executed().failure(), shelved.executed().failure());
        assertEquals(1, shelved.executed().stock().getOrDefault(CHEST, 0));
    }

    @Test
    void thePlanTakesOnlyWhatItNeedsAfterWhatTheBodyCarries() {
        // Twelve planks are needed, five are carried, and the container holds forty: seven are taken, not forty.
        Goal goal = new Goal.HaveItem(CHEST, 1);
        Snapshot snapshot = snapshotWith(Map.of(OAK_PLANKS, 5), supplyBox(SHELF_A, Map.of(OAK_PLANKS, 40)));
        Plan plan = planned(planner(), request(goal, snapshot, Exclusions.NONE));
        assertEquals(7, withdrawn(plan, OAK_PLANKS), leaves(plan).toString());
        assertEquals(new Goal.HaveItem(OAK_PLANKS, 12), withdrawals(plan).get(0).goal(), "five carried and seven taken");
        assertTrue(leaves(plan).stream().noneMatch(leaf -> leaf.taskKind().equals("mine")), leaves(plan).toString());

        Shelved shelved = runWithContainers(plan, Map.of(OAK_PLANKS, 5), Map.of(SHELF_A, Map.of(OAK_PLANKS, 40)));
        assertNull(shelved.executed().failure(), shelved.executed().failure());
        assertEquals(33, shelved.left().get(SHELF_A).get(OAK_PLANKS));
    }

    @Test
    void aPlanNeverTakesMoreThanTheContainersAreKnownToHoldAndGathersTheRest() {
        // Twelve planks are needed: three in the far container, four in the near one, five from two logs.
        Goal goal = new Goal.HaveItem(CHEST, 1);
        Snapshot snapshot = snapshotWith(Map.of(),
                supplyBox(SHELF_A, Map.of(OAK_PLANKS, 3)), supplyBox(SHELF_B, Map.of(OAK_PLANKS, 4)));
        Plan plan = planned(planner(), request(goal, snapshot, Exclusions.NONE));
        assertEquals(7, withdrawn(plan, OAK_PLANKS), leaves(plan).toString());
        assertEquals(2, gathered(plan, OAK_LOG, "mine"), "five more planks are two logs");
        for (NodeSpec take : withdrawals(plan)) {
            Pos at = Pos.parse(take.params().get("container"));
            int holds = at.equals(SHELF_A) ? 3 : 4;
            assertTrue(Integer.parseInt(take.params().get("count")) <= holds, "more than " + at + " holds: " + take.params());
        }
        assertEquals(SHELF_B.encode(), withdrawals(plan).get(0).params().get("container"), "the nearer container first");

        Shelved shelved = runWithContainers(plan, Map.of(),
                Map.of(SHELF_A, Map.of(OAK_PLANKS, 3), SHELF_B, Map.of(OAK_PLANKS, 4)));
        assertNull(shelved.executed().failure(), shelved.executed().failure() + " after " + shelved.executed().ran());
        assertEquals(1, shelved.executed().stock().getOrDefault(CHEST, 0));
    }

    @Test
    void aContainerTheBodyMayNotTakeFromChangesNothing() {
        Goal goal = new Goal.HaveItem(CHEST, 1);
        Plan bare = planned(planner(), request(goal, snapshotWith(Map.of()), Exclusions.NONE));
        Snapshot strangers = snapshotWith(Map.of(), strangersBox(SHELF_A, Map.of(OAK_PLANKS, 12)));
        assertEquals(bare, planned(planner(), request(goal, strangers, Exclusions.NONE)),
                "a chest the owner did not name is not a supply, whatever it holds");

        // The body's own container is one it may take from.
        Snapshot own = snapshotWith(Map.of(), new ContainerSight(SHELF_A, "minecraft:chest", true, true, Map.of(OAK_PLANKS, 12)));
        assertEquals(12, withdrawn(planned(planner(), request(goal, own, Exclusions.NONE)), OAK_PLANKS));
    }

    @Test
    void aContainerAFailureRuledOutIsNotCounted() {
        Goal goal = new Goal.HaveItem(CHEST, 1);
        Snapshot snapshot = snapshotWith(Map.of(), supplyBox(SHELF_A, Map.of(OAK_PLANKS, 12)));
        Exclusions protectedSpot = new Exclusions(Set.of(), Set.of(SHELF_A), List.of());
        Plan plan = planned(planner(), request(goal, snapshot, protectedSpot));
        assertTrue(withdrawals(plan).isEmpty(), "the replan after a PROTECTED must not walk back to the same container");
        assertEquals(3, gathered(plan, OAK_LOG, "mine"));
    }

    @Test
    void aContainerWhoseWithdrawSourceAFailureRuledOutIsNotCounted() {
        // A container that is there and may be used but would not open fails its leaf with NO_SOURCE, which excludes the
        // leaf's own source id: the next plan has to gather or craft instead of walking back to the same lid.
        Goal goal = new Goal.HaveItem(CHEST, 1);
        Snapshot snapshot = snapshotWith(Map.of(), supplyBox(SHELF_A, Map.of(OAK_PLANKS, 12)));
        Plan first = planned(planner(), request(goal, snapshot, Exclusions.NONE));
        assertEquals(1, withdrawals(first).size(), leaves(first).toString());
        String source = withdrawals(first).get(0).source();
        assertFalse(source.isEmpty(), "the leaf says which source it is, or no failure could rule it out");

        Exclusions noSource = new Exclusions(Set.of(source), Set.of(), List.of());
        Plan plan = planned(planner(), request(goal, snapshot, noSource));
        assertTrue(withdrawals(plan).isEmpty(), "the replan after a NO_SOURCE must not take from the same container");
        assertEquals(3, gathered(plan, OAK_LOG, "mine"));

        // Only that container: another one that holds planks is still a supply.
        Snapshot two = snapshotWith(Map.of(), supplyBox(SHELF_A, Map.of(OAK_PLANKS, 12)), supplyBox(SHELF_B, Map.of(OAK_PLANKS, 12)));
        Plan other = planned(planner(), request(goal, two, noSource));
        assertEquals(List.of(SHELF_B.encode()), withdrawals(other).stream().map(leaf -> leaf.params().get("container")).toList());
    }

    @Test
    void aStockpileGoalsOwnContainerIsNotItsSupplyButAnotherOneIs() {
        Goal.Stockpile goal = new Goal.Stockpile(OAK_PLANKS, 8, "minecraft:overworld", SHELF_A);
        ContainerSight target = new ContainerSight(SHELF_A, "minecraft:chest", true, false, true, false, Map.of(OAK_PLANKS, 3));
        Snapshot snapshot = snapshotWith(Map.of(), target, supplyBox(SHELF_B, Map.of(OAK_PLANKS, 2)));
        Plan plan = planned(planner(), request(goal, snapshot, Exclusions.NONE));
        for (NodeSpec take : withdrawals(plan)) {
            assertEquals(SHELF_B.encode(), take.params().get("container"), "the target is never taken from for itself");
        }
        assertEquals(2, withdrawn(plan, OAK_PLANKS));
        NodeSpec deposit = leaves(plan).get(leaves(plan).size() - 1);
        assertEquals("deposit", deposit.taskKind());
        assertEquals("5", deposit.params().get("count"), "three are in it already");

        Shelved shelved = runWithContainers(plan, Map.of(), Map.of(SHELF_B, Map.of(OAK_PLANKS, 2)));
        assertNull(shelved.executed().failure(), shelved.executed().failure() + " after " + shelved.executed().ran());
    }

    @Test
    void aToolInAContainerIsWithdrawnInsteadOfCraftedOrMined() {
        // The wooden pickaxe is what the cobblestone needs, and it lies in a container.
        Snapshot snapshot = snapshotWith(Map.of(), supplyBox(SHELF_A, Map.of(WOODEN_PICKAXE, 1)));
        Plan plan = planned(planner(), request(new Goal.HaveItem(COBBLESTONE, 3), snapshot, Exclusions.NONE));
        assertEquals(1, withdrawn(plan, WOODEN_PICKAXE), leaves(plan).toString());
        assertTrue(leaves(plan).stream().noneMatch(leaf -> leaf.taskKind().equals("craft")), leaves(plan).toString());
        Shelved shelved = runWithContainers(plan, Map.of(), Map.of(SHELF_A, Map.of(WOODEN_PICKAXE, 1)));
        assertNull(shelved.executed().failure(), shelved.executed().failure() + " after " + shelved.executed().ran());
        assertEquals(3, shelved.executed().stock().getOrDefault(COBBLESTONE, 0));

        Plan tier = planned(planner(), request(new Goal.HaveToolTier(ToolClass.PICKAXE, ToolTier.WOOD), snapshot, Exclusions.NONE));
        assertEquals(List.of("withdraw"), leaves(tier).stream().map(NodeSpec::taskKind).toList());
    }

    @Test
    void aStationInAContainerIsWithdrawnForTheCraftsThatNeedItInsteadOfCrafted() {
        // The table is never drawn by a recipe, only stood at: the plan still has to hold it.
        Goal goal = new Goal.HaveItem(WOODEN_PICKAXE, 1);
        long logsFromNothing = gathered(planned(planner(), request(goal, EMPTY, Exclusions.NONE)), OAK_LOG, "mine");
        Snapshot snapshot = snapshotWith(Map.of(), supplyBox(SHELF_A, Map.of(CRAFTING_TABLE, 1)));
        Plan plan = planned(planner(), request(goal, snapshot, Exclusions.NONE));
        assertEquals(1, withdrawn(plan, CRAFTING_TABLE), leaves(plan).toString());
        assertTrue(gathered(plan, OAK_LOG, "mine") < logsFromNothing, "four planks of table are not gathered: " + leaves(plan));
        assertTrue(leaves(plan).stream().noneMatch(leaf -> "crafting_table_from_planks".equals(leaf.params().get("recipe"))),
                "the table in the container is crafted again: " + leaves(plan));
        Shelved shelved = runWithContainers(plan, Map.of(), Map.of(SHELF_A, Map.of(CRAFTING_TABLE, 1)));
        assertNull(shelved.executed().failure(), shelved.executed().failure() + " after " + shelved.executed().ran());
        assertEquals(1, shelved.executed().stock().getOrDefault(WOODEN_PICKAXE, 0));
    }

    @Test
    void anItemTheBodyCouldGatherIsWithdrawnWhenAContainerHoldsIt() {
        Snapshot snapshot = snapshotWith(Map.of(), supplyBox(SHELF_A, Map.of(OAK_LOG, 2)));
        Plan plan = planned(planner(), request(new Goal.HaveItem(OAK_LOG, 2), snapshot, Exclusions.NONE));
        assertEquals(List.of("withdraw"), leaves(plan).stream().map(NodeSpec::taskKind).toList());
        assertEquals(2, withdrawn(plan, OAK_LOG));

        // One short: the container's two come first and a third is gathered.
        Plan more = planned(planner(), request(new Goal.HaveItem(OAK_LOG, 3), snapshot, Exclusions.NONE));
        assertEquals(2, withdrawn(more, OAK_LOG));
        assertEquals(1, gathered(more, OAK_LOG, "mine"));
        assertEquals(new Goal.HaveItem(OAK_LOG, 3), leaves(more).get(leaves(more).size() - 1).goal());
        Shelved shelved = runWithContainers(more, Map.of(), Map.of(SHELF_A, Map.of(OAK_LOG, 2)));
        assertNull(shelved.executed().failure(), shelved.executed().failure() + " after " + shelved.executed().ran());
        assertEquals(3, shelved.executed().stock().getOrDefault(OAK_LOG, 0));
    }

    @Test
    void theFingerprintFollowsWhatTheBodyMayTakeFromContainersInTheGoalsClosureOnly() {
        SolverPlanner instance = planner();
        Goal goal = new Goal.HaveItem(STONE_PICKAXE, 1);
        long base = instance.inputFingerprint(goal, EMPTY);

        long twoPlanks = instance.inputFingerprint(goal, snapshotWith(Map.of(), supplyBox(SHELF_A, Map.of(OAK_PLANKS, 2))));
        assertNotEquals(base, twoPlanks, "planks are in the recipe closure of the pickaxe");
        assertNotEquals(twoPlanks, instance.inputFingerprint(goal, snapshotWith(Map.of(), supplyBox(SHELF_A, Map.of(OAK_PLANKS, 3)))),
                "how many it may take is read");
        assertNotEquals(twoPlanks, instance.inputFingerprint(goal, snapshotWith(Map.of(), supplyBox(SHELF_B, Map.of(OAK_PLANKS, 2)))),
                "which container it takes them from is read");
        assertEquals(twoPlanks, instance.inputFingerprint(goal, snapshotWith(Map.of(),
                new ContainerSight(SHELF_A, "minecraft:chest", true, false, true, false, Map.of(OAK_PLANKS, 2)))),
                "whether the contents are remembered or in sight is not what the plan reads");

        assertEquals(base, instance.inputFingerprint(goal, snapshotWith(Map.of(), supplyBox(SHELF_A, Map.of(DIAMOND, 9, EMERALD, 4)))),
                "what a container holds outside the closure is not read");
        assertEquals(base, instance.inputFingerprint(goal, snapshotWith(Map.of(), strangersBox(SHELF_A, Map.of(OAK_PLANKS, 12)))),
                "a container the body may not take from is not read");
        assertEquals(base, instance.inputFingerprint(goal, snapshotWith(Map.of(), supplyBox(SHELF_A, Map.of()))),
                "an empty container adds nothing");
    }

    @Test
    void theFingerprintReadsATakeableContainersToolThatThePlanMayRelyOn() {
        // Cobblestone needs a wooden pickaxe; the stone pickaxe in the container serves, so the plan takes it, though no
        // recipe on the way to cobblestone names a stone pickaxe. Its removal has to move the fingerprint.
        SolverPlanner instance = planner();
        Goal goal = new Goal.HaveItem(COBBLESTONE, 3);
        Snapshot withTool = snapshotWith(Map.of(), supplyBox(SHELF_A, Map.of(STONE_PICKAXE, 1)));
        Plan plan = planned(instance, request(goal, withTool, Exclusions.NONE));
        assertEquals(1, withdrawn(plan, STONE_PICKAXE), "setup: the plan relies on the container's better pickaxe: " + leaves(plan));

        long base = instance.inputFingerprint(goal, EMPTY);
        assertNotEquals(base, instance.inputFingerprint(goal, withTool), "the pickaxe the plan relies on is not read");
        assertEquals(plan.inputFingerprint(), instance.inputFingerprint(goal, withTool));
        assertNotEquals(instance.inputFingerprint(goal, withTool),
                instance.inputFingerprint(goal, snapshotWith(Map.of(), supplyBox(SHELF_A, Map.of(DIAMOND_PICKAXE, 1)))),
                "which tool it may take is read, not only that there is one");

        // A tool of a class nothing on the way needs, or one the body may not take, is not read.
        assertEquals(base, instance.inputFingerprint(goal,
                snapshotWith(Map.of(), supplyBox(SHELF_A, Map.of(ItemKey.of("diamond_shovel"), 1)))));
        assertEquals(base, instance.inputFingerprint(goal,
                snapshotWith(Map.of(), strangersBox(SHELF_A, Map.of(STONE_PICKAXE, 1)))));
    }

    @Test
    void aStockpileTargetInAnotherDimensionIsNotExemptedFromTheFingerprintsTake() {
        // The target is where a deposit goes, in that dimension. A container at the same coordinates in the body's own
        // dimension is an ordinary supply, which the plan counts, so the fingerprint has to read it too.
        SolverPlanner instance = planner();
        Goal.Stockpile goal = new Goal.Stockpile(OAK_PLANKS, 8, "minecraft:the_nether", SHELF_A);
        Snapshot supplied = snapshotWith(Map.of(), supplyBox(SHELF_A, Map.of(OAK_PLANKS, 5)));
        assertEquals(5, withdrawn(planned(instance, request(goal, supplied, Exclusions.NONE)), OAK_PLANKS), "setup: the plan counts it");
        assertNotEquals(instance.inputFingerprint(goal, supplied),
                instance.inputFingerprint(goal, snapshotWith(Map.of(), supplyBox(SHELF_A, Map.of(OAK_PLANKS, 6)))),
                "how many it may take is read");
    }

    @Test
    void theLookInsideGoalIsOneLeafOfItsOwnAndOnlyForItsOwnKind() {
        // The body's look at a container its owner named (design 3.5): walk there, open it, note what is in it.
        Goal.Custom goal = LookInside.goal("minecraft:overworld", SHELF_A);
        Plan plan = planned(planner(new LookInside()), request(goal, snapshotWith(Map.of()), Exclusions.NONE));
        assertEquals(1, leaves(plan).size(), leaves(plan).toString());
        NodeSpec leaf = leaves(plan).get(0);
        assertEquals(LookInside.TASK, leaf.taskKind());
        assertEquals(SHELF_A.encode(), leaf.params().get(LookInside.PARAM_CONTAINER));
        assertEquals("minecraft:overworld", leaf.params().get(LookInside.PARAM_DIMENSION));
        assertEquals(goal, leaf.goal());
        assertTrue(plan.inputFingerprint() == planner(new LookInside()).inputFingerprint(goal, snapshotWith(Map.of())), "the plan carries the fingerprint of its inputs");

        // Another custom goal is not this strategy's: the planner finds no way to it.
        PlanOutcome other = planner(new LookInside()).plan(request(new Goal.Custom("something_else", Map.of()), snapshotWith(Map.of()), Exclusions.NONE));
        assertTrue(other instanceof PlanOutcome.Unplannable, "a strategy planned a goal that is not its own: " + other);
    }

    @Test
    void theLookInsideGoalIsMetOnlyWhenTheBodyHasANoteOfTheContainer() {
        Goal.Custom goal = LookInside.goal("minecraft:overworld", SHELF_A);
        var met = LookInside.postcondition(goal);
        // Named and never opened: nothing noted, so nothing is known of it, in range or not.
        assertFalse(met.evaluate(snapshotWith(Map.of(), new ContainerSight(SHELF_A, "minecraft:chest", false, false, true, false, Map.of()))).isSatisfied());
        // Opened: whatever the note says, even that it is empty, is known; in sight or out of it.
        assertTrue(met.evaluate(snapshotWith(Map.of(), new ContainerSight(SHELF_A, "minecraft:chest", false, false, true, true, Map.of()))).isSatisfied());
        assertTrue(met.evaluate(snapshotWith(Map.of(), new ContainerSight(SHELF_A, "minecraft:chest", true, false, true, true, Map.of(OAK_PLANKS, 3)))).isSatisfied());
        // Nobody names a container there any more (taken back, or replaced): nothing is left to look at.
        assertTrue(met.evaluate(snapshotWith(Map.of())).isSatisfied());
        // Another container's note is not this one's.
        assertTrue(met.evaluate(snapshotWith(Map.of(), new ContainerSight(SHELF_B, "minecraft:chest", true, false, true, true, Map.of()))).isSatisfied(),
                "an unrelated container is no reason to look at this one");
        // In another dimension the body cannot look, whatever it has noted.
        Snapshot nether = Snapshot.builder().gameTime(0).dimension("minecraft:the_nether").pos(new Pos(0, 64, 0))
                .container(new ContainerSight(SHELF_A, "minecraft:chest", true, false, true, true, Map.of())).build();
        assertFalse(met.evaluate(nether).isSatisfied());
    }

    // ─── What the body can see (issue #27; design 3.4 Snapshot, 4.1) ─────────────────────────────

    private static final String OAK_LOG_BLOCK = "minecraft:oak_log";
    private static final String SPRUCE_LOG_BLOCK = "minecraft:spruce_log";

    /** The base recipes plus a second wood: its own planks, and a shelf whose one slot takes a plank of either kind. */
    private static SimpleRecipeBook twoWoodsBook() {
        return bookWith(
                recipe("planks_from_spruce_log", SPRUCE_PLANKS, 4, null, one(SPRUCE_LOG)),
                recipe("shelf_from_plank", SHELF, 1, null, either(OAK_PLANKS, SPRUCE_PLANKS)));
    }

    /** The base index plus spruce logs, mined by hand like oak's, unless the caller gives them a source of its own. */
    private static AcquisitionIndex.Builder twoWoodsIndex(Source spruceSource) {
        AcquisitionIndex.Builder builder = indexBuilder(ToolTier.IRON);
        builder.addItem(SPRUCE_LOG).addItem(SPRUCE_PLANKS).addItem(SHELF);
        builder.addSource(spruceSource != null ? spruceSource
                : new Source.MineBlock("mine:minecraft:spruce_log", SPRUCE_LOG, SPRUCE_LOG_BLOCK,
                        null, ToolTier.HAND, false, false, false, 1.0, false, null));
        return builder;
    }

    private static SolverPlanner twoWoodsPlanner() {
        return planner(twoWoodsBook(), twoWoodsIndex(null).build());
    }

    /** A snapshot whose sight holds exactly these blocks at these horizontal distances. */
    private static Snapshot sighted(Map<String, Integer> nearby) {
        Snapshot.Builder builder = Snapshot.builder().gameTime(0).dimension("minecraft:overworld").pos(new Pos(0, 64, 0));
        nearby.forEach(builder::nearby);
        return builder.build();
    }

    private static List<String> minedSources(Plan plan) {
        return sources(plan).stream().filter(source -> source.startsWith("mine:")).toList();
    }

    private static List<String> craftedRecipes(Plan plan) {
        return leaves(plan).stream().filter(leaf -> leaf.taskKind().equals("craft"))
                .map(leaf -> leaf.params().get("recipe")).toList();
    }

    @Test
    void theLogKindTheBodyCanSeeIsTheOneMinedAndItsPlankTheOneCrafted() {
        // A shelf takes a plank of either wood. Nothing in the recipes says which log is at hand: without a sight
        // the tie falls to the first listed (oak), which is the plan that walked a bot 215 blocks past other trees.
        SolverPlanner planner = twoWoodsPlanner();
        Goal goal = new Goal.HaveItem(SHELF, 1);

        Plan blind = planned(planner, request(goal, EMPTY, Exclusions.NONE));
        assertEquals(List.of("mine:minecraft:oak_log"), minedSources(blind), "the tie falls to oak: " + leaves(blind));

        Plan spruceOnly = planned(planner, request(goal, sighted(Map.of(SPRUCE_LOG_BLOCK, 12)), Exclusions.NONE));
        assertEquals(List.of("mine:minecraft:spruce_log"), minedSources(spruceOnly), leaves(spruceOnly).toString());
        assertEquals(List.of("planks_from_spruce_log", "shelf_from_plank"), craftedRecipes(spruceOnly));
        assertRunsToTheGoal(spruceOnly, twoWoodsBook(), Map.of(), Map.of(), SHELF, 1);

        Plan oakOnly = planned(planner, request(goal, sighted(Map.of(OAK_LOG_BLOCK, 12)), Exclusions.NONE));
        assertEquals(List.of("mine:minecraft:oak_log"), minedSources(oakOnly), leaves(oakOnly).toString());
        assertEquals(List.of("planks_from_log", "shelf_from_plank"), craftedRecipes(oakOnly));
        assertRunsToTheGoal(oakOnly, twoWoodsBook(), Map.of(), Map.of(), SHELF, 1);

        // A sight that holds neither log is the plan there always was, node for node: both kinds are charged alike.
        Plan neither = planned(planner, request(goal, sighted(Map.of("minecraft:stone", 5)), Exclusions.NONE));
        assertEquals(blind.roots(), neither.roots());
    }

    @Test
    void whenBothLogKindsAreSeenTheNearerOneIsMined() {
        SolverPlanner planner = twoWoodsPlanner();
        Goal goal = new Goal.HaveItem(SHELF, 1);

        Plan nearSpruce = planned(planner, request(goal,
                sighted(Map.of(OAK_LOG_BLOCK, 40, SPRUCE_LOG_BLOCK, 12)), Exclusions.NONE));
        assertEquals(List.of("mine:minecraft:spruce_log"), minedSources(nearSpruce), leaves(nearSpruce).toString());

        Plan nearOak = planned(planner, request(goal,
                sighted(Map.of(OAK_LOG_BLOCK, 12, SPRUCE_LOG_BLOCK, 40)), Exclusions.NONE));
        assertEquals(List.of("mine:minecraft:oak_log"), minedSources(nearOak), leaves(nearOak).toString());
    }

    @Test
    void aSeenSourceThatCannotBeUsedIsStillNotChosen() {
        // The spruce logs in sight need a netherite pickaxe, which nothing in the index or the book gives. They are
        // the only logs in sight, and still the plan mines the oak that is not. Tool tiers cost nothing in this
        // config, so only the source being unusable keeps the spruce from being the cheaper one.
        Source locked = new Source.MineBlock("mine:minecraft:spruce_log", SPRUCE_LOG, SPRUCE_LOG_BLOCK,
                ToolClass.PICKAXE, ToolTier.NETHERITE, true, false, false, 1.0, false, null);
        AcquisitionIndex lockedIndex = twoWoodsIndex(locked).build();
        SolverPlanner planner = new SolverPlanner(twoWoodsBook(), lockedIndex, dearMining(0), List.of());
        Snapshot spruceSeen = sighted(Map.of(SPRUCE_LOG_BLOCK, 3));

        Plan plan = planned(planner, request(new Goal.HaveItem(SHELF, 1), spruceSeen, Exclusions.NONE));
        assertEquals(List.of("mine:minecraft:oak_log"), minedSources(plan), leaves(plan).toString());
        assertRunsToTheGoal(plan, twoWoodsBook(), Map.of(), Map.of(), SHELF, 1);

        GatherCosts costs = new GatherCosts(twoWoodsBook(), lockedIndex, dearMining(0), Exclusions.NONE, spruceSeen);
        assertEquals(com.legoj15.solver.CostModel.UNOBTAINABLE, costs.costOf(locked), "a seen source stays unusable");
    }

    @Test
    void aBlockInSightCostsLessThanTheSameBlockOutOfItHoweverFarAndOnlyBlockSourcesAreCharged() {
        Source oak = new Source.MineBlock("mine:minecraft:oak_log", OAK_LOG, OAK_LOG_BLOCK,
                null, ToolTier.HAND, false, false, false, 1.0, false, null);
        Source spruce = new Source.MineBlock("mine:minecraft:spruce_log", SPRUCE_LOG, SPRUCE_LOG_BLOCK,
                null, ToolTier.HAND, false, false, false, 1.0, false, null);
        Source wheat = new Source.Harvest("harvest:minecraft:wheat", ItemKey.of("wheat"), "minecraft:wheat", null,
                false, 1.0, false);
        Source spider = new Source.KillMob("kill:minecraft:spider", STRING, "minecraft:spider", true, 1.0, true);
        AcquisitionIndex indexOf = AcquisitionIndex.builder().addSource(oak).addSource(spruce).addSource(wheat)
                .addSource(spider).build();
        PlannerConfig config = PlannerConfig.defaults();
        // What the collector looks for: the blocks of the mining and harvesting sources, and no mob.
        assertEquals(Set.of(OAK_LOG_BLOCK, SPRUCE_LOG_BLOCK, "minecraft:wheat"), indexOf.sourceBlocks());

        GatherCosts blind = new GatherCosts(book(), indexOf, config, Exclusions.NONE, EMPTY);
        // Oak a million blocks away is in sight; spruce and wheat are not.
        GatherCosts oakOnly = new GatherCosts(book(), indexOf, config, Exclusions.NONE,
                sighted(Map.of(OAK_LOG_BLOCK, 1_000_000)));
        assertTrue(oakOnly.costOf(oak) < oakOnly.costOf(spruce), "a block in sight, however far, beats one out of it");
        assertEquals(oakOnly.costOf(spruce) - blind.costOf(spruce), oakOnly.costOf(wheat) - blind.costOf(wheat),
                "an unseen block is charged the same penalty whether it is mined or harvested");
        assertEquals(blind.costOf(spider), oakOnly.costOf(spider), "a mob has no block to see");
        assertEquals(config.mineBase(), blind.costOf(oak), "no sight, no charge: a log costs what it always did");
        assertEquals(config.mineBase(), blind.costOf(spruce));
        assertEquals(config.harvest(), blind.costOf(wheat));

        // Nearer is cheaper than farther, and seen at all is cheaper than not.
        GatherCosts near = new GatherCosts(book(), indexOf, config, Exclusions.NONE,
                sighted(Map.of(OAK_LOG_BLOCK, 10, SPRUCE_LOG_BLOCK, 30, "minecraft:wheat", 30)));
        assertTrue(near.costOf(oak) < near.costOf(spruce));
        assertTrue(near.costOf(spruce) < oakOnly.costOf(spruce), "spruce seen at 30 is cheaper than spruce not seen");
        assertTrue(near.costOf(wheat) < oakOnly.costOf(wheat), "a harvest source is charged by sight too");
    }

    @Test
    void theFingerprintCoversWhichBlocksOfTheClosureAreSeenAndNeverHowFar() {
        SolverPlanner instance = twoWoodsPlanner();
        Goal shelf = new Goal.HaveItem(SHELF, 1);
        long oakNear = instance.inputFingerprint(shelf, sighted(Map.of(OAK_LOG_BLOCK, 5)));
        assertEquals(oakNear, instance.inputFingerprint(shelf, sighted(Map.of(OAK_LOG_BLOCK, 70))),
                "a body that walks away from the log has not changed what the plan reads");
        assertNotEquals(oakNear, instance.inputFingerprint(shelf, sighted(Map.of(OAK_LOG_BLOCK, 5, SPRUCE_LOG_BLOCK, 5))),
                "a log kind that came into sight can change the choice");
        assertNotEquals(oakNear, instance.inputFingerprint(shelf, sighted(Map.of(SPRUCE_LOG_BLOCK, 5))),
                "a log kind that went out of sight can change the choice");

        // Coal is no part of a shelf's recipe closure: seeing it or not changes nothing the plan reads.
        assertEquals(oakNear, instance.inputFingerprint(shelf, sighted(Map.of(OAK_LOG_BLOCK, 5, "minecraft:coal_ore", 9))));
        // ...and it is part of a coal goal's.
        Goal coal = new Goal.HaveItem(COAL, 1);
        assertNotEquals(instance.inputFingerprint(coal, sighted(Map.of("minecraft:coal_ore", 9, OAK_LOG_BLOCK, 9))),
                instance.inputFingerprint(coal, sighted(Map.of(OAK_LOG_BLOCK, 9))), "coal in sight is read by a coal goal");
    }

    @Test
    void aSightThatHoldsNothingTheGoalNeedsIsNotAnEmptySightToTheFingerprint() {
        // A dead bush gives sticks at a cost far below crafting them, so the plan gathers it while the snapshot has no
        // sight. Once the sight says only that coal is near, the bush is out of sight and the sticks are crafted: two
        // different plans for two snapshots, so the plan made for one must not be adopted for the other.
        AcquisitionIndex.Builder builder = indexBuilder(ToolTier.IRON);
        builder.addSource(new Source.Harvest("harvest:minecraft:dead_bush", STICK, "minecraft:dead_bush", null,
                false, 100.0, false));
        SolverPlanner planner = planner(book(), builder.build());
        Goal sticks = new Goal.HaveItem(STICK, 4);
        Snapshot elsewhere = sighted(Map.of("minecraft:coal_ore", 9));

        assertTrue(sources(planned(planner, request(sticks, EMPTY, Exclusions.NONE))).contains("harvest:minecraft:dead_bush"));
        assertFalse(sources(planned(planner, request(sticks, elsewhere, Exclusions.NONE))).contains("harvest:minecraft:dead_bush"));
        assertNotEquals(planner.inputFingerprint(sticks, EMPTY), planner.inputFingerprint(sticks, elsewhere));
    }

    // ─── The fingerprint and determinism (item 8) ────────────────────────────────────────────────

    @Test
    void theFingerprintTracksWhatThePlanReadsAndNothingElse() {
        SolverPlanner instance = planner();
        Goal goal = new Goal.HaveItem(STONE_PICKAXE, 1);
        long base = instance.inputFingerprint(goal, EMPTY);
        assertEquals(base, instance.inputFingerprint(goal, snapshot(Map.of(), Map.of())),
                "position, health, hunger and game time are not part of it");
        assertNotEquals(base, instance.inputFingerprint(goal, snapshot(Map.of(OAK_LOG, 1), Map.of())),
                "a log is in the recipe closure of the pickaxe");
        assertNotEquals(base, instance.inputFingerprint(goal, snapshot(Map.of(STICK, 1), Map.of())),
                "a stick is an ingredient of the pickaxe");
        assertNotEquals(base, instance.inputFingerprint(goal, snapshot(Map.of(), Map.of(ToolClass.PICKAXE, ToolTier.WOOD))),
                "a best-tool change changes it");
        assertNotEquals(base, instance.inputFingerprint(new Goal.HaveItem(STONE_PICKAXE, 2), EMPTY),
                "a goal change changes it");
        assertNotEquals(base, instance.inputFingerprint(goal,
                Snapshot.builder().dimension("minecraft:the_nether").build()), "the dimension is part of it");

        // Items outside the goal's recipe closure and its leaves are not.
        assertEquals(base, instance.inputFingerprint(goal, snapshot(Map.of(DIAMOND, 5, EMERALD, 2, SADDLE, 1), Map.of())),
                "an unrelated pickup does not change it");
    }

    @Test
    void theFingerprintCoversWhatStockpileAndEquipRead() {
        SolverPlanner instance = planner();
        Goal.Stockpile stockpile = new Goal.Stockpile(OAK_PLANKS, 8, "minecraft:overworld", CONTAINER);
        long unseen = instance.inputFingerprint(stockpile, EMPTY);
        Snapshot knownEmpty = Snapshot.builder().container(
                new ContainerSight(CONTAINER, "minecraft:chest", true, false, Map.of())).build();
        Snapshot knownThree = Snapshot.builder().container(
                new ContainerSight(CONTAINER, "minecraft:chest", true, false, Map.of(OAK_PLANKS, 3))).build();
        assertNotEquals(unseen, instance.inputFingerprint(stockpile, knownThree), "what the container holds is read");
        assertNotEquals(instance.inputFingerprint(stockpile, knownEmpty), instance.inputFingerprint(stockpile, knownThree));

        Goal.Equip equip = new Goal.Equip(Map.of(EquipSlot.MAINHAND, WOODEN_PICKAXE));
        long bare = instance.inputFingerprint(equip, EMPTY);
        assertNotEquals(bare, instance.inputFingerprint(equip,
                Snapshot.builder().equip(EquipSlot.MAINHAND, WOODEN_PICKAXE).build()), "what is worn is read");
    }

    @Test
    void theFingerprintOfAGoalWithAComponentCheckCoversTheFactsOfTheStacksHeld() {
        // A mending book and a plain one are the same count of the same item; the goal is decided on which one the
        // body holds, so the fingerprint must tell them apart.
        SolverPlanner instance = planner();
        Goal.HaveItem goal = new Goal.HaveItem(ENCHANTED_BOOK, 1, ComponentCheck.enchantment("minecraft:mending", 1));
        Snapshot plain = Snapshot.builder().item(ENCHANTED_BOOK, 1).facts(ENCHANTED_BOOK, StackFacts.plain(1)).build();
        Snapshot mending = Snapshot.builder().item(ENCHANTED_BOOK, 1)
                .facts(ENCHANTED_BOOK, new StackFacts(1, Map.of("minecraft:mending", 1), "", Map.of())).build();
        Snapshot unbreaking = Snapshot.builder().item(ENCHANTED_BOOK, 1)
                .facts(ENCHANTED_BOOK, new StackFacts(1, Map.of("minecraft:unbreaking", 3), "", Map.of())).build();
        assertNotEquals(instance.inputFingerprint(goal, plain), instance.inputFingerprint(goal, mending));
        assertNotEquals(instance.inputFingerprint(goal, mending), instance.inputFingerprint(goal, unbreaking));
        assertEquals(instance.inputFingerprint(goal, mending), instance.inputFingerprint(goal, mending));

        // The facts of an item the goal does not name are not read.
        Snapshot unrelated = Snapshot.builder().item(ENCHANTED_BOOK, 1).facts(ENCHANTED_BOOK, StackFacts.plain(1))
                .facts(DIAMOND, new StackFacts(1, Map.of("minecraft:fortune", 3), "", Map.of())).build();
        assertEquals(instance.inputFingerprint(goal, plain), instance.inputFingerprint(goal, unrelated));
    }

    @Test
    void theToolTableIteratesInKeyOrderWhateverOrderItWasBuiltIn() {
        // Two keys naming one item make the later one win in the planner's reverse table, so the order the table is
        // read in must not depend on the JVM.
        Map<String, ItemKey> scrambled = new java.util.HashMap<>(PlannerConfig.vanillaToolItems());
        PlannerConfig config = PlannerConfig.defaults().withToolItems(scrambled);
        assertEquals(new ArrayList<>(new java.util.TreeSet<>(scrambled.keySet())),
                new ArrayList<>(config.toolItems().keySet()));
        assertThrows(UnsupportedOperationException.class, () -> config.toolItems().put("x", STICK));
    }

    @Test
    void planningTheSameRequestTwiceGivesEqualPlans() {
        SolverPlanner instance = planner();
        PlanRequest request = request(new Goal.HaveItem(STONE_PICKAXE, 1), EMPTY, Exclusions.NONE);
        assertEquals(instance.plan(request), instance.plan(request));
        Plan plan = planned(instance, request);
        assertFalse(plan.notes().isEmpty(), "what the planner could not consider is said out loud: " + plan.notes());

        Plan stringPlan = planned(instance, request(new Goal.HaveItem(STRING, 1), EMPTY, Exclusions.NONE));
        assertFalse(stringPlan.notes().isEmpty(), "the cobweb source needs shears, which is not planned yet");
    }
}
