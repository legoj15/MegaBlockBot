package com.legoj15.megablockbot.game.resolver;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Reads a loot table as the JSON the codec writes (the datapack format) and says which items it can produce and how
 * many per attempt. It never opens the loot classes, whose fields are private.
 *
 * <p>A table is evaluated once per <em>scenario</em>: which tool the player holds (none that matters, a silk-touch
 * tool, shears), whether a player made the kill, which <em>block state</em> the block is in, and whether conditions
 * this class cannot judge (entity and location predicates, other mods' conditions) are assumed to hold. In a scenario
 * every condition is a number between 0 (never) and 1 (always), so {@code alternatives} works as the game does: a
 * later child applies only when the earlier ones' conditions fail, and the silk-touch child of an ore does not hide
 * the plain one. The per-scenario results are then folded into routes, keeping for each item only its least demanding
 * way out.
 *
 * <p>A block has one state, so conditions that test one property of one block for different values (a sweet berry
 * bush's age 3 pool and its age 2 pool) can never hold together. The states a table names, and those of the tables it
 * references, are enumerated: one scenario for each combination of the values its {@code block_state_property}
 * conditions mention for each property (plus one for all the other values the block can have, if it has any). Which
 * of them a yield is read in depends on the kind of source ({@link StateRule}): a block that is mined is placed in
 * one state and read in its <em>default state</em> when that yields the item at all (one slab, one candle) and in the
 * state that gives the most only when it yields none (glow lichen on every face), because a figure too high makes a
 * plan fall short where one too low only collects a little too much; a block that is harvested is left to grow and
 * read in the state that yields the most, the ripe one (a carrot crop's 2, not the unripe plant's 1). Without the
 * default state (an environment that cannot say) the best state stands in for it. Beyond {@link #MAX_STATES}
 * combinations the table is read in the default state alone when that is known, noted because the best state could
 * not be chosen, and its state conditions are read as unjudgeable when it is not.
 *
 * <p>Simplifications, all on the side of reporting an item as obtainable: a condition this class cannot judge is
 * tried both ways, and an item both readings yield keeps the larger expected count and the larger chance of each;
 * luck, looting and fortune are at level 0; {@code apply_bonus}, {@code explosion_decay}, {@code limit_count} and
 * every function that edits components are ignored; a function whose condition is neither certain nor impossible is
 * ignored; global loot modifiers are not run; one roll picks among the entries that can run by weight, and the
 * chance that an entry is picked when others are uncertain is the average of its share of the weight over which of
 * them are there, exact up to {@link #MAX_PICK_SUPPORT} different totals of weight and, beyond it, the entry's weight
 * over the expected weight of the pool, noted.
 *
 * <p>Functions run in the game's order, the entry's first and the pool's after. A {@code set_count} on the pool is
 * applied to item and tag entries; over a nested {@code loot_table} entry, and at table level, it is noted and not
 * applied, as are the pool-level functions that change counts in ways this class does not model.
 */
final class LootEval {
    /** What the player holds when the table is rolled. */
    enum Tool { PLAIN, SILK, SHEARS }

    /**
     * Which state a yield is read in, by the kind of source the table feeds. A mined block keeps the state it is
     * placed in: the default state when that yields the item at all, and the state that yields the most only when it
     * yields none, because the planner prices a source by the figure and a figure above what the block holds when
     * placed makes a plan fall short. A harvested growable is picked when it is grown, so its yield is the ripe
     * state's, whatever an unripe plant would give.
     */
    enum StateRule { MINED, HARVESTED }

    /**
     * {@code state} maps "block|property" to the value the block has in this scenario ({@link #OTHER_STATE} for a
     * value no condition names); a property it lacks is unjudged, like any other condition this class cannot read.
     */
    record Scenario(Tool tool, boolean byPlayer, boolean unknownHolds, Map<String, String> state) {
        Scenario {
            state = Map.copyOf(state);
        }

        Scenario withUnknown(boolean holds) {
            return new Scenario(tool, byPlayer, holds, state);
        }
    }

    /** One item out of one attempt: the expected count, and the chance of at least one. */
    record Drop(double expected, double hit) {}

    /** The least demanding way a table yields an item. */
    record Route(String item, Tool tool, boolean byPlayer, double expected, double hit) {
        /** True when a single attempt may yield nothing of this item. */
        boolean random() {
            return hit < 1.0 - 1e-9;
        }
    }

    /** What the evaluator needs from the running game. */
    interface Environment {
        /** The table as codec-written JSON, or null when there is no such table or it cannot be encoded. */
        JsonObject table(String id);

        /** The ids of the items in an item tag, or empty when the tag is unknown. */
        List<String> tagItems(String tagId);

        /**
         * Every value the block's property can have, by name, or empty when the block or property is not known. With
         * it a table is judged in the states the block can really be in; without it, in the states the table names
         * and one more for everything else, which a block may not have.
         */
        default List<String> stateValues(String block, String property) {
            return List.of();
        }

        /**
         * The value the property has in the block's default state, by name, or null when the block or property is not
         * known. A table is read in the default state first; without it, in the best state only.
         */
        default String defaultStateValue(String block, String property) {
            return null;
        }
    }

    // A leaf is what one pick from a pool yields: its weight, and the items that come out when it is picked.
    private record Leaf(int weight, Map<String, Drop> drops) {}

    // One possible expansion of an entry; the outcomes of one Expansion are mutually exclusive.
    private record Outcome(double prob, List<Leaf> leaves) {
        int totalWeight() {
            int total = 0;
            for (Leaf leaf : leaves) total += Math.max(leaf.weight, 0);
            return total;
        }
    }

    private record Expansion(List<Outcome> outcomes) {}

    private static final double EPS = 1e-9;

    // Most block-state combinations a table is enumerated over (vanilla's most is 64: glow lichen and sculk vein, one
    // property per face); a table past it is read in the default state alone when that is known and its state
    // conditions are read as unjudgeable when it is not, noted either way (the best-state rule could not be applied).
    static final int MAX_STATES = 256;
    // A value that no condition of the table names, so it matches none of them.
    static final String OTHER_STATE = "?";

    // Most different totals of weight the uncertain entries of a pool can add up to before the pick chances of the pool
    // stop being averaged over them and fall back to the expected weight; vanilla has no pool with an uncertain entry
    // beside another.
    static final int MAX_PICK_SUPPORT = 4096;

    // Deepest chain of loot_table references (or inline tables) that is followed. Each level is a nested evaluation on
    // the Java stack, so an unbounded chain ends in a StackOverflowError that no RuntimeException guard sees; about a
    // dozen frames per level at 128 is a fraction of the default stack. The bound is for the stack, not for the work:
    // that is bounded by keeping each result by table, scenario and depth left (see evalTableId), however many paths
    // lead to a table. The longest chain in vanilla is three tables.
    static final int MAX_TABLE_DEPTH = 128;

    // The budget of one top-level routes() call: at most this many table evaluations (counted where the memo misses;
    // hits cost nothing) before the rest of the table is left unevaluated and the table is noted as cut for size. The
    // work multiplies tables by states by tools, which no real pack does: the widest vanilla table names 64 states
    // (glow lichen, sculk vein), read two ways for the conditions this class cannot judge, with three tools, over the
    // deepest chain there is, three tables, which is 64 * 2 * 3 * 3 = 1152 evaluations; this budget is an order of
    // magnitude over that. A ladder of 200 tables whose root names eight boolean properties (256 states) was measured
    // at 44 seconds unbounded by a reviewer, and run without the budget here it exhausted the heap before it ended;
    // at this budget that ladder evaluates in about two hundred milliseconds.
    static final int MAX_TABLE_EVALUATIONS = 16384;

    // Pool-level functions that change a count in a way this class does not model. The others are either modeled
    // (set_count), ignored on purpose as at entry level (apply_bonus at level 0, explosion_decay), or leave counts
    // alone. The last three wrap other functions, any of which may be a set_count. Table level models nothing.
    private static final Set<String> UNMODELED_POOL_FUNCTIONS =
        Set.of("limit_count", "enchanted_count_increase", "sequence", "filtered", "reference");
    private static final Set<String> UNMODELED_TABLE_FUNCTIONS =
        Set.of("set_count", "limit_count", "enchanted_count_increase", "sequence", "filtered", "reference");
    private static final Set<String> SET_COUNT = Set.of("set_count");

    // What a table yields depends on the scenario and on how much depth is left below it: the chain of references is
    // cut where the depth runs out, so the same table nearer the end of a chain yields less than at its start.
    private record MemoKey(Scenario scenario, int remaining) {}

    // The block states of a table (see states()), and what each table says by itself about block states.
    private record StateSet(List<Map<String, String>> all, Map<String, String> byDefault) {}

    private record StateTests(Map<String, Set<String>> named, Set<String> ranged, List<String> references) {}

    private final Environment env;
    private final BuildNotes notes;
    private final Map<String, Map<MemoKey, Map<String, Drop>>> memo = new HashMap<>();
    private final Set<String> visiting = new HashSet<>();
    private final Map<String, StateSet> stateCache = new HashMap<>();
    private final Map<String, StateTests> testCache = new HashMap<>();
    // How many tables are being evaluated one inside another.
    private int depth;
    // The table being evaluated, so a note can name it. An inline table belongs to the table that holds it.
    private String current = "?";
    // The budget of the routes() call in progress: how many evaluations it has spent (memo misses) and whether it ran
    // out. Reset per call, so one expensive table does not starve the next.
    private int evaluations;
    private boolean starved;

    LootEval(Environment env, BuildNotes notes) {
        this.env = env;
        this.notes = notes;
    }

    // ---- routes ------------------------------------------------------------------------------------------------

    /**
     * The routes by which the table yields items. {@code tools} are the tool scenarios to try (PLAIN first),
     * {@code playerKills} whether to try a player kill as well, and {@code stateRule} which state a yield is read in
     * (see {@link StateRule}). An item that a less demanding scenario already yields gets no route for a more
     * demanding one. Within a scenario the two readings of the conditions this class cannot judge are merged per item,
     * each figure taking the more permissive of the two; of the states the block can be in, an item keeps the figures
     * the rule picks. A table that cannot be read costs that table: it is named in the notes and has no routes. The
     * call spends at most {@link #MAX_TABLE_EVALUATIONS} table evaluations; past it what was computed stands and the
     * table is noted as cut for size.
     */
    List<Route> routes(String tableId, Set<Tool> tools, boolean playerKills, StateRule stateRule) {
        evaluations = 0;
        starved = false;
        try {
            return routesOf(tableId, tools, playerKills, stateRule);
        } catch (RuntimeException e) {
            // The block states of the table are looked up here, outside the guard of evalTableId; a registry that throws
            // for one table must not take the rest of the section it is built in.
            notes.skipped("loot tables that threw while evaluated", tableId + " (" + e.getClass().getSimpleName() + ")");
            return List.of();
        } finally {
            if (starved) notes.skipped("loot tables cut for size (past " + MAX_TABLE_EVALUATIONS
                + " table evaluations in one read; what was computed so far stands)", tableId);
        }
    }

    private List<Route> routesOf(String tableId, Set<Tool> tools, boolean playerKills, StateRule stateRule) {
        StateSet states = states(tableId);
        List<Route> routes = new ArrayList<>();
        // item id -> routes already emitted for it, to find the dominated ones.
        Map<String, List<Route>> emitted = new HashMap<>();
        for (Tool tool : EnumSet.copyOf(tools)) {
            for (boolean byPlayer : playerKills ? new boolean[] {false, true} : new boolean[] {false}) {
                for (Map.Entry<String, Drop> e : yields(tableId, states, tool, byPlayer, stateRule).entrySet()) {
                    Drop drop = e.getValue();
                    if (!yields(drop)) continue;
                    List<Route> prior = emitted.computeIfAbsent(e.getKey(), k -> new ArrayList<>());
                    boolean dominated = false;
                    for (Route p : prior) {
                        if ((p.tool() == Tool.PLAIN || p.tool() == tool) && (!p.byPlayer() || byPlayer)) {
                            dominated = true;
                            break;
                        }
                    }
                    if (dominated) continue;
                    Route route = new Route(e.getKey(), tool, byPlayer, drop.expected(), Math.min(1.0, drop.hit()));
                    prior.add(route);
                    routes.add(route);
                }
            }
        }
        return routes;
    }

    private static boolean yields(Drop drop) {
        return drop.expected() > EPS && drop.hit() > EPS;
    }

    // ---- block states ------------------------------------------------------------------------------------------

    /**
     * What the table yields in one scenario: for a harvested block, for each item the figures of the state that yields
     * it the most (the ripe one, since harvesting waits for it); for a mined block, the figures of the default state
     * when it yields the item at all, and otherwise those of the state that yields the item the most. The unjudgeable
     * conditions are read both ways within each state. A block is only ever in one state, so the figures of two states
     * are never added.
     */
    private Map<String, Drop> yields(String tableId, StateSet states, Tool tool, boolean byPlayer, StateRule rule) {
        Map<String, Drop> out = bestState(tableId, states.all(), tool, byPlayer);
        if (rule == StateRule.HARVESTED || states.byDefault() == null) return out;
        out = new TreeMap<>(out);
        for (Map.Entry<String, Drop> e : bothReadings(tableId, tool, byPlayer, states.byDefault()).entrySet()) {
            if (yields(e.getValue())) out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    // The figures of the state that yields each item the most: the larger expected count, the larger chance on a tie.
    private Map<String, Drop> bestState(String tableId, List<Map<String, String>> states, Tool tool, boolean byPlayer) {
        Map<String, Drop> best = new TreeMap<>();
        boolean first = true;
        for (Map<String, String> state : states) {
            Map<String, Drop> merged = bothReadings(tableId, tool, byPlayer, state);
            if (first) {
                best.putAll(merged);
                first = false;
                continue;
            }
            for (Map.Entry<String, Drop> e : merged.entrySet()) {
                best.merge(e.getKey(), e.getValue(), (a, b) -> {
                    if (Math.abs(a.expected() - b.expected()) <= EPS) return a.hit() >= b.hit() ? a : b;
                    return a.expected() > b.expected() ? a : b;
                });
            }
        }
        return best;
    }

    // One state, the conditions this class cannot judge tried both ways and merged, each figure the more permissive.
    private Map<String, Drop> bothReadings(String tableId, Tool tool, boolean byPlayer, Map<String, String> state) {
        Map<String, Drop> merged = new TreeMap<>(evalTableId(tableId, new Scenario(tool, byPlayer, false, state)));
        for (Map.Entry<String, Drop> e : evalTableId(tableId, new Scenario(tool, byPlayer, true, state)).entrySet()) {
            merged.merge(e.getKey(), e.getValue(), (a, b) -> new Drop(Math.max(a.expected(), b.expected()), Math.max(a.hit(), b.hit())));
        }
        return merged;
    }

    /**
     * The block states to evaluate the table in: one map for each combination of the values its
     * {@code block_state_property} conditions name, for each property, and a value none of them names; the conditions of
     * the tables it references count as its own, since the block is the same. A table with no such condition has the one
     * state with nothing assigned. A property tested by a range instead of a value cannot be enumerated and is left
     * unassigned. A table past {@link #MAX_STATES} combinations is read in the default state alone when that is known
     * (noted: the best state could not be chosen) and has every property left unassigned when it is not.
     * {@code byDefault} is the state the block is placed in, from the environment, or null when the environment cannot
     * say for every property.
     */
    private StateSet states(String tableId) {
        StateSet cached = stateCache.get(tableId);
        if (cached != null) return cached;
        Map<String, Set<String>> named = new TreeMap<>();
        Set<String> ranged = new HashSet<>();
        // Which table first named each property, so a lookup that throws is noted against the table that named it.
        Map<String, String> sources = new TreeMap<>();
        collectStateTests(tableId, named, ranged, sources);
        named.keySet().removeAll(ranged);
        StateSet states = new StateSet(List.of(Map.of()), Map.of());
        if (!named.isEmpty()) {
            // The values to try for each property: those the table names, and one for all the rest, when there are rest.
            Map<String, List<String>> values = new TreeMap<>();
            Map<String, String> defaults = new TreeMap<>();
            boolean defaultKnown = true;
            long combinations = 1;
            for (Map.Entry<String, Set<String>> property : named.entrySet()) {
                int separator = property.getKey().lastIndexOf('|');
                String block = property.getKey().substring(0, separator);
                String name = property.getKey().substring(separator + 1);
                List<String> tried = new ArrayList<>();
                List<String> all;
                String has;
                try {
                    all = env.stateValues(block, name);
                    has = env.defaultStateValue(block, name);
                } catch (RuntimeException e) {
                    // The property may have been named by a table this one references; the throw costs that table,
                    // named by itself, and not the one being read. The property is left unassigned, so the conditions on
                    // it are read both ways like any other this class cannot judge, and what is known of the default
                    // state of the table being read stands: its own yields are still read as placed. A property the
                    // table being read named itself still costs it, as any other throw out of states() does.
                    String owner = sources.getOrDefault(property.getKey(), tableId);
                    if (owner.equals(tableId)) throw e;
                    notes.skipped("loot tables whose block states could not be looked up", owner + " (" + e.getClass().getSimpleName() + ")");
                    continue;
                }
                if (all.isEmpty()) {
                    tried.addAll(property.getValue());
                    tried.add(OTHER_STATE);
                } else {
                    for (String value : all) if (property.getValue().contains(value)) tried.add(value);
                    if (tried.size() < all.size()) tried.add(OTHER_STATE);
                }
                values.put(property.getKey(), tried);
                combinations = Math.min(combinations * tried.size(), MAX_STATES + 1L);
                // A default value no condition names behaves as every other value no condition names.
                if (has == null) defaultKnown = false;
                else defaults.put(property.getKey(), property.getValue().contains(has) ? has : OTHER_STATE);
            }
            if (combinations > MAX_STATES) {
                notes.skipped("loot tables with too many block-state combinations (read in the default state alone when it is known;"
                    + " the best-state rule could not be applied)", tableId);
                states = new StateSet(defaultKnown ? List.of(defaults) : List.of(Map.of()), null);
            } else {
                List<Map<String, String>> all = List.of(Map.of());
                for (Map.Entry<String, List<String>> property : values.entrySet()) {
                    List<Map<String, String>> next = new ArrayList<>();
                    for (Map<String, String> partial : all) {
                        for (String value : property.getValue()) next.add(with(partial, property.getKey(), value));
                    }
                    all = next;
                }
                states = new StateSet(all, defaultKnown ? defaults : null);
            }
        }
        stateCache.put(tableId, states);
        return states;
    }

    private static Map<String, String> with(Map<String, String> state, String key, String value) {
        Map<String, String> next = new HashMap<>(state);
        next.put(key, value);
        return next;
    }

    // Every property a block_state_property condition tests in the table or in the tables it references, up to the depth
    // the evaluation follows them, with the values it tests it for, and which table first named each property. Breadth
    // first, so that a table reached by two paths is taken the first time at its shortest, and each table once however
    // many paths lead to it.
    private void collectStateTests(String tableId, Map<String, Set<String>> named, Set<String> ranged, Map<String, String> sources) {
        Set<String> seen = new HashSet<>();
        List<String> level = List.of(tableId);
        for (int d = 0; d < MAX_TABLE_DEPTH && !level.isEmpty(); d++) {
            List<String> next = new ArrayList<>();
            for (String id : level) {
                if (!seen.add(id)) continue;
                StateTests tests;
                try {
                    tests = stateTestsOf(id);
                } catch (RuntimeException e) {
                    // A table whose tests cannot be collected while a referencing table is read costs that table, named
                    // by itself; the table being read keeps its own pools. A throw out of the table being read itself
                    // still costs it, as any other throw out of states() does.
                    if (id.equals(tableId)) throw e;
                    notes.skipped("loot tables whose state tests could not be collected", id + " (" + e.getClass().getSimpleName() + ")");
                    continue;
                }
                tests.named().forEach((key, values) -> {
                    named.computeIfAbsent(key, k -> new TreeSet<>()).addAll(values);
                    sources.putIfAbsent(key, id);
                });
                ranged.addAll(tests.ranged());
                next.addAll(tests.references());
            }
            level = next;
        }
    }

    // What one table says by itself: the state tests anywhere in its JSON (inline nested tables included) and the tables
    // it references by id. Kept, so that a table which many others reference is read once.
    private StateTests stateTestsOf(String tableId) {
        StateTests cached = testCache.get(tableId);
        if (cached != null) return cached;
        Map<String, Set<String>> named = new TreeMap<>();
        Set<String> ranged = new HashSet<>();
        List<String> references = new ArrayList<>();
        JsonObject table = env.table(tableId);
        if (table != null) collectStateTests(table, named, ranged, references);
        StateTests tests = new StateTests(named, ranged, references);
        testCache.put(tableId, tests);
        return tests;
    }

    private static void collectStateTests(JsonElement e, Map<String, Set<String>> values, Set<String> ranged, List<String> references) {
        if (e.isJsonArray()) {
            for (JsonElement child : e.getAsJsonArray()) collectStateTests(child, values, ranged, references);
        } else if (e.isJsonObject()) {
            JsonObject o = e.getAsJsonObject();
            if (strip(string(o, "condition")).equals("block_state_property") && o.get("properties") != null && o.get("properties").isJsonObject()) {
                String block = string(o, "block");
                for (Map.Entry<String, JsonElement> p : o.getAsJsonObject("properties").entrySet()) {
                    String key = stateKey(block, p.getKey());
                    if (p.getValue().isJsonPrimitive()) values.computeIfAbsent(key, k -> new TreeSet<>()).add(p.getValue().getAsString());
                    else ranged.add(key);
                }
            }
            if (strip(string(o, "type")).equals("loot_table") && o.get("value") != null && o.get("value").isJsonPrimitive()) {
                references.add(o.get("value").getAsString());
            }
            for (Map.Entry<String, JsonElement> child : o.entrySet()) collectStateTests(child.getValue(), values, ranged, references);
        }
    }

    private static String stateKey(String block, String property) {
        return block + "|" + property;
    }

    // ---- tables ------------------------------------------------------------------------------------------------

    // Each table is evaluated once for each scenario and each depth it is met at, however many references lead to it: a
    // web of tables that all reference the next, twice over, is as many evaluations as it has tables, not as many as it
    // has paths. The depth is part of the key because the result under a depth cut is not the same as without one. Each
    // miss spends one unit of the budget of the routes() call in progress; past it the table yields nothing more and
    // routes() notes the table it was asked for. Nothing that ends after the cut is memoized: the refused miss, and
    // every table above it, whose result lacks what was refused. A later call has a fresh budget and reads them again,
    // with the tables that did end before the cut already known.
    private Map<String, Drop> evalTableId(String id, Scenario s) {
        MemoKey key = new MemoKey(s, MAX_TABLE_DEPTH - depth);
        Map<MemoKey, Map<String, Drop>> byKey = memo.computeIfAbsent(id, k -> new HashMap<>());
        Map<String, Drop> cached = byKey.get(key);
        if (cached != null) return cached;
        if (evaluations >= MAX_TABLE_EVALUATIONS) {
            starved = true;
            return Map.of();
        }
        evaluations++;
        if (!visiting.add(id)) {
            // Named, so that it counts once for the table however many states and tools meet it.
            notes.skipped("loot table loops back to itself, the inner reference is ignored", id);
            return Map.of();
        }
        Map<String, Drop> result;
        String outer = current;
        current = id;
        try {
            JsonObject table = env.table(id);
            if (table == null) {
                notes.skipped("loot tables referenced but missing or unreadable", id);
                result = Map.of();
            } else {
                result = evalTable(table, s);
            }
        } catch (RuntimeException e) {
            // A table written in a shape this class did not expect costs that table; a table that nests it carries on.
            notes.skipped("loot tables that threw while evaluated", id + " (" + e.getClass().getSimpleName() + ")");
            result = Map.of();
        } finally {
            visiting.remove(id);
            current = outer;
        }
        if (!starved) byKey.put(key, result);
        return result;
    }

    private Map<String, Drop> evalTable(JsonObject table, Scenario s) {
        if (depth >= MAX_TABLE_DEPTH) {
            notes.skipped("loot tables nested more than " + MAX_TABLE_DEPTH + " deep (the deeper references are ignored)", current);
            return Map.of();
        }
        depth++;
        try {
            return evalTableAtDepth(table, s);
        } finally {
            depth--;
        }
    }

    private Map<String, Drop> evalTableAtDepth(JsonObject table, Scenario s) {
        noteFunctions(table.get("functions"), UNMODELED_TABLE_FUNCTIONS, "table-level loot functions that change counts are not applied");
        Map<String, Drop> out = new TreeMap<>();
        for (JsonElement pool : array(table, "pools")) {
            if (pool.isJsonObject()) merge(out, evalPool(pool.getAsJsonObject(), s));
        }
        return out;
    }

    private Map<String, Drop> evalPool(JsonObject pool, Scenario s) {
        double poolChance = allConditions(pool.get("conditions"), s);
        if (poolChance <= EPS) return Map.of();
        Num rolls = Num.parse(pool.get("rolls"), notes, current);
        JsonElement poolFunctions = pool.get("functions");
        noteFunctions(poolFunctions, UNMODELED_POOL_FUNCTIONS, "pool-level loot functions that change counts in ways not modeled");

        List<Expansion> expansions = new ArrayList<>();
        for (JsonElement entry : array(pool, "entries")) {
            if (entry.isJsonObject()) expansions.addAll(expand(entry.getAsJsonObject(), poolFunctions, s));
        }

        // The pool picks one entry per roll among those that are there, by weight. What an entry gets is its weight over
        // the weight of everything there, averaged over which of the uncertain entries are.
        PickOdds odds = new PickOdds(expansions);

        Map<String, double[]> perRoll = new TreeMap<>(); // item -> {expected, hit}
        for (int i = 0; i < expansions.size(); i++) {
            for (Outcome o : expansions.get(i).outcomes()) {
                int outcomeWeight = o.totalWeight();
                for (Leaf leaf : o.leaves()) {
                    if (leaf.weight() <= 0) continue;
                    double pick = o.prob() * odds.share(i, leaf.weight(), outcomeWeight);
                    for (Map.Entry<String, Drop> d : leaf.drops().entrySet()) {
                        double[] acc = perRoll.computeIfAbsent(d.getKey(), k -> new double[2]);
                        acc[0] += pick * d.getValue().expected();
                        acc[1] += pick * d.getValue().hit();
                    }
                }
            }
        }

        double meanRolls = rolls.mean();
        Map<String, Drop> out = new TreeMap<>();
        for (Map.Entry<String, double[]> e : perRoll.entrySet()) {
            double expected = poolChance * meanRolls * e.getValue()[0];
            double hit = poolChance * rolls.hitOverRolls(Math.min(1.0, e.getValue()[1]));
            if (expected > 0) out.put(e.getKey(), new Drop(expected, hit));
        }
        return out;
    }

    /**
     * The share of a roll's pick that an entry gets. One pick takes one entry among those that are there, by weight, so
     * an entry's share depends on which of the other entries are there: when they are certain it is its weight over
     * theirs, and when some are uncertain it is the average of that over every way they can be there, which is not its
     * weight over their average weight (a certain entry of weight 3 beside one of weight 1 that is there half the time
     * gets 1/2 * 3/3 + 1/2 * 3/4, not 3 / 3.5). The others are independent of each other.
     *
     * <p>The average runs over the different totals of weight the uncertain others can add up to, built by combining
     * them one at a time, which enumerates every way for the price of the totals. If those exceed
     * {@link #MAX_PICK_SUPPORT} for an entry, that entry's share is its weight over the others' expected weight, and
     * the table is noted.
     */
    private final class PickOdds {
        // For each expansion, the weight it adds to the pool and how likely, 0 when it is not there.
        private final List<TreeMap<Long, Double>> adds = new ArrayList<>();
        // The weight of an expansion that is always the same, or -1 when it varies.
        private final long[] fixed;
        private long fixedTotal;
        private final List<Integer> varying = new ArrayList<>();
        private final double[] expectedWeight;
        private double expectedTotal;
        // The combined weight of the varying expansions but one (index), or of all of them (index -1); an empty entry
        // marks one that went over the bound.
        private final Map<Integer, Optional<TreeMap<Long, Double>>> combined = new HashMap<>();

        PickOdds(List<Expansion> expansions) {
            this.fixed = new long[expansions.size()];
            this.expectedWeight = new double[expansions.size()];
            for (int i = 0; i < expansions.size(); i++) {
                TreeMap<Long, Double> add = new TreeMap<>();
                double there = 0;
                for (Outcome o : expansions.get(i).outcomes()) {
                    if (o.prob() <= EPS) continue;
                    add.merge((long) o.totalWeight(), o.prob(), Double::sum);
                    there += o.prob();
                    expectedWeight[i] += o.prob() * o.totalWeight();
                }
                if (there < 1 - EPS) add.merge(0L, 1 - there, Double::sum);
                if (add.isEmpty()) add.put(0L, 1.0);
                adds.add(add);
                expectedTotal += expectedWeight[i];
                if (add.size() == 1) {
                    fixed[i] = add.firstKey();
                    fixedTotal += fixed[i];
                } else {
                    fixed[i] = -1;
                    varying.add(i);
                }
            }
        }

        /** The share of the pick for a leaf of {@code weight}, in an outcome of expansion {@code i} that weighs {@code outcomeWeight}. */
        double share(int i, int weight, int outcomeWeight) {
            long certain = fixed[i] >= 0 ? fixedTotal - fixed[i] : fixedTotal;
            TreeMap<Long, Double> others = varyingOthers(fixed[i] >= 0 ? -1 : i);
            if (others == null) {
                notes.skipped("loot pools with too many uncertain entries (a pick chance is taken over the expected weight)", current);
                double rest = (expectedTotal - expectedWeight[i]) + (outcomeWeight - weight);
                return weight / (weight + rest);
            }
            double base = (double) outcomeWeight + certain;
            double share = 0;
            for (Map.Entry<Long, Double> e : others.entrySet()) share += e.getValue() * weight / (base + e.getKey());
            return share;
        }

        // The weights the varying expansions add up to, leaving out expansion `skip` (-1: none), or null past the bound.
        private TreeMap<Long, Double> varyingOthers(int skip) {
            return combined.computeIfAbsent(skip, k -> {
                TreeMap<Long, Double> total = new TreeMap<>(Map.of(0L, 1.0));
                for (int j : varying) {
                    if (j == skip) continue;
                    TreeMap<Long, Double> next = new TreeMap<>();
                    for (Map.Entry<Long, Double> a : total.entrySet()) {
                        for (Map.Entry<Long, Double> b : adds.get(j).entrySet()) next.merge(a.getKey() + b.getKey(), a.getValue() * b.getValue(), Double::sum);
                    }
                    if (next.size() > MAX_PICK_SUPPORT) return Optional.empty();
                    total = next;
                }
                return Optional.of(total);
            }).orElse(null);
        }
    }

    private static void merge(Map<String, Drop> into, Map<String, Drop> from) {
        for (Map.Entry<String, Drop> e : from.entrySet()) {
            into.merge(e.getKey(), e.getValue(),
                (a, b) -> new Drop(a.expected() + b.expected(), 1 - (1 - a.hit()) * (1 - b.hit())));
        }
    }

    // ---- entries -----------------------------------------------------------------------------------------------

    // poolFunctions are the functions of the pool the entry sits in, which run after the entry's own.
    private List<Expansion> expand(JsonObject entry, JsonElement poolFunctions, Scenario s) {
        double chance = allConditions(entry.get("conditions"), s);
        if (chance <= EPS) return List.of();
        String type = strip(string(entry, "type"));
        int weight = entry.has("weight") ? entry.get("weight").getAsInt() : 1;
        switch (type) {
            case "item": {
                String name = string(entry, "name");
                if (name.isEmpty()) {
                    notes.skipped("loot item entries with no item name", current);
                    return List.of();
                }
                Num count = count(entry, poolFunctions, s);
                return single(chance, new Leaf(weight, Map.of(name, new Drop(count.mean(), count.pPositive()))));
            }
            case "tag": {
                List<String> items = env.tagItems(string(entry, "name"));
                boolean expandTag = entry.has("expand") && entry.get("expand").getAsBoolean();
                if (items.isEmpty()) {
                    notes.skipped("item tags used by loot tables that are empty or unknown", string(entry, "name"));
                    return List.of();
                }
                Num count = count(entry, poolFunctions, s);
                Drop each = new Drop(count.mean(), count.pPositive());
                if (expandTag) {
                    // Each item of the tag is its own entry of the pool, with the tag entry's weight.
                    List<Leaf> leaves = new ArrayList<>();
                    for (String item : items) leaves.add(new Leaf(weight, Map.of(item, each)));
                    return List.of(new Expansion(List.of(new Outcome(chance, leaves))));
                }
                Map<String, Drop> drops = new TreeMap<>();
                for (String item : items) drops.put(item, each);
                return single(chance, new Leaf(weight, drops));
            }
            case "loot_table": {
                JsonElement value = entry.get("value");
                Map<String, Drop> drops;
                if (value != null && value.isJsonObject()) drops = evalTable(value.getAsJsonObject(), s);
                else if (value != null && value.isJsonPrimitive()) drops = evalTableId(value.getAsString(), s);
                else {
                    notes.skipped("loot_table entries with no readable value", current);
                    return List.of();
                }
                // The nested table's stacks are already aggregated into expected counts, so a pool-level set_count
                // cannot be laid over them exactly.
                noteFunctions(poolFunctions, SET_COUNT, "pool-level set_count over a nested loot_table entry is not applied");
                return single(chance, new Leaf(weight, drops));
            }
            case "empty":
                return single(chance, new Leaf(weight, Map.of()));
            case "dynamic":
                // Drops filled in by the block entity (a decorated pot's sherds, a shulker box's contents).
                notes.skipped("dynamic loot entries ignored (filled in by block entities)", string(entry, "name"));
                return single(chance, new Leaf(weight, Map.of()));
            case "alternatives": {
                // The first child whose conditions pass is the only one expanded.
                List<Outcome> exclusive = new ArrayList<>();
                List<Expansion> extra = new ArrayList<>();
                double reach = 1.0;
                for (JsonElement child : array(entry, "children")) {
                    if (!child.isJsonObject() || reach <= EPS) continue;
                    JsonObject c = child.getAsJsonObject();
                    List<Expansion> expanded = expand(c, poolFunctions, s);
                    if (expanded.size() == 1) {
                        for (Outcome o : expanded.get(0).outcomes()) exclusive.add(scale(o, chance * reach));
                    } else {
                        for (Expansion x : expanded) extra.add(scale(x, chance * reach));
                    }
                    reach *= 1 - success(c, s);
                }
                List<Expansion> out = new ArrayList<>();
                if (!exclusive.isEmpty()) out.add(new Expansion(exclusive));
                out.addAll(extra);
                return out;
            }
            case "group": {
                // Every child expands; they are separate entries of the pool.
                List<Expansion> out = new ArrayList<>();
                for (JsonElement child : array(entry, "children")) {
                    if (child.isJsonObject()) for (Expansion x : expand(child.getAsJsonObject(), poolFunctions, s)) out.add(scale(x, chance));
                }
                return out;
            }
            case "sequence": {
                // Children expand in order until one fails.
                List<Expansion> out = new ArrayList<>();
                double reach = chance;
                for (JsonElement child : array(entry, "children")) {
                    if (!child.isJsonObject() || reach <= EPS) continue;
                    JsonObject c = child.getAsJsonObject();
                    for (Expansion x : expand(c, poolFunctions, s)) out.add(scale(x, reach));
                    reach *= success(c, s);
                }
                return out;
            }
            default:
                notes.skipped("loot entry types not evaluated", type.isEmpty() ? "?" : type);
                return List.of();
        }
    }

    // The probability that expanding the entry succeeds, which is what makes an alternatives stop at it.
    private double success(JsonObject entry, Scenario s) {
        double chance = allConditions(entry.get("conditions"), s);
        if (chance <= EPS) return 0;
        String type = strip(string(entry, "type"));
        if (type.equals("alternatives")) {
            double none = 1;
            for (JsonElement child : array(entry, "children")) if (child.isJsonObject()) none *= 1 - success(child.getAsJsonObject(), s);
            return chance * (1 - none);
        }
        if (type.equals("sequence")) {
            double all = 1;
            for (JsonElement child : array(entry, "children")) if (child.isJsonObject()) all *= success(child.getAsJsonObject(), s);
            return chance * all;
        }
        return chance;
    }

    private static List<Expansion> single(double chance, Leaf leaf) {
        return List.of(new Expansion(List.of(new Outcome(chance, List.of(leaf)))));
    }

    private static Outcome scale(Outcome o, double factor) {
        return new Outcome(o.prob() * factor, o.leaves());
    }

    private static Expansion scale(Expansion x, double factor) {
        List<Outcome> out = new ArrayList<>();
        for (Outcome o : x.outcomes()) out.add(scale(o, factor));
        return new Expansion(out);
    }

    // The count a pick of an entry has been given so far, and whether a set_count was left out for want of certainty.
    private static final class Count {
        Num value = Num.constant(1);
        boolean skippedUncertain;
    }

    /**
     * The count one pick of an item entry gives: 1 unless a certain {@code set_count} says otherwise, the entry's own
     * functions first and its pool's after, which is the order the game runs them in.
     */
    private Num count(JsonObject entry, JsonElement poolFunctions, Scenario s) {
        Count count = new Count();
        applyFunctions(entry.get("functions"), count, s);
        applyFunctions(poolFunctions, count, s);
        // A multi-face block (glow lichen, vines) adds one per face and takes one off the base; with the per-face
        // additions skipped that would leave nothing. The entry exists to drop something, so a break gives at least 1.
        if (count.skippedUncertain && count.value.mean() <= 0) return Num.constant(1);
        return count.value;
    }

    private void applyFunctions(JsonElement functions, Count count, Scenario s) {
        if (functions == null || !functions.isJsonArray()) return;
        for (JsonElement f : functions.getAsJsonArray()) {
            if (!f.isJsonObject()) continue;
            JsonObject fn = f.getAsJsonObject();
            String id = strip(string(fn, "function"));
            if (id.equals("furnace_smelt") || id.equals("set_item")) {
                notes.skipped("loot functions that change the item (the unchanged item is indexed)", id);
                continue;
            }
            if (!id.equals("set_count")) continue;
            // Only a count that holds whatever the unjudgeable conditions say: a location or entity predicate may fail
            // on an attempt, and a count it gates cannot be assumed. A block state is judged, so a count it gates is
            // certain once the scenario has fixed the state, and one the state rules out (a face the block does not
            // have) is simply not there.
            double bestCase = Math.max(allConditions(fn.get("conditions"), s.withUnknown(true)), allConditions(fn.get("conditions"), s.withUnknown(false)));
            if (bestCase <= EPS) continue;
            if (!certain(fn.get("conditions"), s)) {
                count.skippedUncertain = true;
                continue;
            }
            Num value = Num.parse(fn.get("count"), notes, current);
            count.value = fn.has("add") && fn.get("add").getAsBoolean() ? count.value.plus(value).noted(notes, current) : value;
        }
    }

    // Names, under the table being evaluated, each of the functions that this class leaves out of a count.
    private void noteFunctions(JsonElement functions, Set<String> ids, String category) {
        if (functions == null || !functions.isJsonArray()) return;
        for (JsonElement f : functions.getAsJsonArray()) {
            if (!f.isJsonObject()) continue;
            String id = strip(string(f.getAsJsonObject(), "function"));
            if (ids.contains(id)) notes.skipped(category, current + " (" + id + ")");
        }
    }

    // ---- conditions --------------------------------------------------------------------------------------------

    private boolean certain(JsonElement conditions, Scenario s) {
        return allConditions(conditions, s.withUnknown(true)) >= 1 - EPS && allConditions(conditions, s.withUnknown(false)) >= 1 - EPS;
    }

    private double allConditions(JsonElement conditions, Scenario s) {
        double p = 1;
        if (conditions != null && conditions.isJsonArray()) {
            for (JsonElement c : conditions.getAsJsonArray()) {
                if (c.isJsonObject()) p *= condition(c.getAsJsonObject(), s);
                if (p <= EPS) return 0;
            }
        }
        return p;
    }

    private double condition(JsonObject c, Scenario s) {
        String id = strip(string(c, "condition"));
        switch (id) {
            case "survives_explosion":
                return 1;
            case "random_chance":
                return clamp(Num.floatValue(c.get("chance"), notes));
            case "random_chance_with_enchanted_bonus":
                return clamp(Num.floatValue(c.get("unenchanted_chance"), notes));
            case "table_bonus": {
                // The level-0 chance.
                JsonArray chances = array(c, "chances");
                return chances.isEmpty() ? 0 : clamp(chances.get(0).getAsDouble());
            }
            case "killed_by_player":
                return s.byPlayer() ? 1 : 0;
            case "match_tool":
                return matchTool(c.get("predicate"), s);
            case "any_of": {
                double none = 1;
                for (JsonElement t : array(c, "terms")) if (t.isJsonObject()) none *= 1 - condition(t.getAsJsonObject(), s);
                return 1 - none;
            }
            case "all_of": {
                double all = 1;
                for (JsonElement t : array(c, "terms")) if (t.isJsonObject()) all *= condition(t.getAsJsonObject(), s);
                return all;
            }
            case "inverted": {
                JsonElement term = c.get("term");
                return term != null && term.isJsonObject() ? 1 - condition(term.getAsJsonObject(), s) : unknown(s);
            }
            case "neoforge:can_item_perform_ability": {
                // NeoForge's own copy of the vanilla tables asks "can this tool shear" instead of matching shears.
                String ability = string(c, "ability");
                if (ability.equals("shears_dig")) return s.tool() == Tool.SHEARS ? 1 : 0;
                notes.skipped("tool abilities not judged (tried both ways)", ability);
                return unknown(s);
            }
            case "block_state_property":
                return blockState(c, s);
            case "entity_properties":
            case "damage_source_properties":
            case "location_check":
            case "weather_check":
            case "time_check":
            case "value_check":
            case "entity_scores":
            case "enchantment_active_check":
            case "reference":
                return unknown(s);
            default:
                notes.skipped("loot condition types not judged (tried both ways)", id.isEmpty() ? "?" : id);
                return unknown(s);
        }
    }

    private static double unknown(Scenario s) {
        return s.unknownHolds() ? 1 : 0;
    }

    // block_state_property: judged against the state the scenario gives the block, for each property it has one for. A
    // value that differs from the state's fails the whole condition; one the state does not cover is unjudged.
    private double blockState(JsonObject c, Scenario s) {
        JsonElement properties = c.get("properties");
        if (properties == null || !properties.isJsonObject() || properties.getAsJsonObject().size() == 0 || s.state().isEmpty()) return unknown(s);
        String block = string(c, "block");
        boolean covered = true;
        for (Map.Entry<String, JsonElement> property : properties.getAsJsonObject().entrySet()) {
            String has = s.state().get(stateKey(block, property.getKey()));
            if (has == null) {
                covered = false;
            } else if (!property.getValue().isJsonPrimitive() || !has.equals(property.getValue().getAsString())) {
                return 0;
            }
        }
        return covered ? 1 : unknown(s);
    }

    // match_tool: silk touch and shears are the two tools the index models; anything else is unjudged.
    private double matchTool(JsonElement predicate, Scenario s) {
        if (predicate == null || !predicate.isJsonObject()) return 1; // no predicate: any tool, or the bare hand
        JsonObject p = predicate.getAsJsonObject();
        if (p.size() == 0) return 1;
        boolean silk = requiresSilkTouch(p);
        boolean shears = itemsAre(p.get("items"), "minecraft:shears");
        if (silk && !shears) return s.tool() == Tool.SILK ? 1 : 0;
        if (shears && !silk) return s.tool() == Tool.SHEARS ? 1 : 0;
        return unknown(s);
    }

    private static boolean requiresSilkTouch(JsonObject predicate) {
        JsonElement predicates = predicate.get("predicates");
        if (predicates == null || !predicates.isJsonObject()) return false;
        JsonElement list = predicates.getAsJsonObject().get("minecraft:enchantments");
        if (list == null || !list.isJsonArray()) return false;
        for (JsonElement e : list.getAsJsonArray()) {
            if (!e.isJsonObject()) continue;
            JsonObject enchant = e.getAsJsonObject();
            if (!ids(enchant.get("enchantments")).contains("minecraft:silk_touch")) continue;
            JsonElement levels = enchant.get("levels");
            // No bound, or a minimum of at least 1, means the enchantment must be present.
            if (levels == null || !levels.isJsonObject() || !levels.getAsJsonObject().has("min")) return true;
            if (levels.getAsJsonObject().get("min").getAsInt() >= 1) return true;
        }
        return false;
    }

    private static boolean itemsAre(JsonElement items, String only) {
        List<String> ids = ids(items);
        return ids.size() == 1 && ids.get(0).equals(only);
    }

    // A holder set written as one id, a list of ids or a "#tag" string; only plain ids are returned.
    private static List<String> ids(JsonElement e) {
        List<String> out = new ArrayList<>();
        if (e == null) return out;
        if (e.isJsonPrimitive()) out.add(e.getAsString());
        else if (e.isJsonArray()) for (JsonElement x : e.getAsJsonArray()) if (x.isJsonPrimitive()) out.add(x.getAsString());
        return out;
    }

    // ---- JSON helpers ------------------------------------------------------------------------------------------

    private static double clamp(double p) {
        return Math.max(0, Math.min(1, p));
    }

    static JsonArray array(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : new JsonArray();
    }

    static String string(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
    }

    /** Drops the {@code minecraft:} prefix so a type can be compared by its plain name; other namespaces stay. */
    static String strip(String id) {
        return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
    }
}
