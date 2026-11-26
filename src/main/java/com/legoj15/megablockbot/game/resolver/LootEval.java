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
import java.util.Set;
import java.util.TreeMap;

/**
 * Reads a loot table as the JSON the codec writes (the datapack format) and says which items it can produce and how
 * many per attempt. It never opens the loot classes, whose fields are private.
 *
 * <p>A table is evaluated once per <em>scenario</em>: which tool the player holds (none that matters, a silk-touch
 * tool, shears), whether a player made the kill, and whether conditions this class cannot judge (block state, entity
 * and location predicates, other mods' conditions) are assumed to hold. In a scenario every condition is a number
 * between 0 (never) and 1 (always), so {@code alternatives} works as the game does: a later child applies only when
 * the earlier ones' conditions fail, and the silk-touch child of an ore does not hide the plain one. The per-scenario
 * results are then folded into routes, keeping for each item only its least demanding way out.
 *
 * <p>Simplifications, all on the side of reporting an item as obtainable: a condition this class cannot judge is
 * tried both ways; luck, looting and fortune are at level 0; {@code apply_bonus}, {@code explosion_decay},
 * {@code limit_count} and every function that edits components are ignored; a function whose condition is not certain
 * under both readings is ignored; global loot modifiers are not run; one roll picks among the entries that can run by
 * weight, and the chance that a leaf is picked when other entries are themselves uncertain is the leaf's weight over
 * the expected weight in the pool (exact when nothing else is uncertain).
 */
final class LootEval {
    /** What the player holds when the table is rolled. */
    enum Tool { PLAIN, SILK, SHEARS }

    record Scenario(Tool tool, boolean byPlayer, boolean unknownHolds) {
        Scenario withUnknown(boolean holds) {
            return new Scenario(tool, byPlayer, holds);
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

    private final Environment env;
    private final BuildNotes notes;
    private final Map<String, Map<Scenario, Map<String, Drop>>> memo = new HashMap<>();
    private final Set<String> visiting = new HashSet<>();

    LootEval(Environment env, BuildNotes notes) {
        this.env = env;
        this.notes = notes;
    }

    // ---- routes ------------------------------------------------------------------------------------------------

    /**
     * The routes by which the table yields items. {@code tools} are the tool scenarios to try (PLAIN first) and
     * {@code playerKills} whether to try a player kill as well. An item that a less demanding scenario already yields
     * gets no route for a more demanding one.
     */
    List<Route> routes(String tableId, Set<Tool> tools, boolean playerKills) {
        List<Route> routes = new ArrayList<>();
        // item id -> routes already emitted for it, to find the dominated ones.
        Map<String, List<Route>> emitted = new HashMap<>();
        for (Tool tool : EnumSet.copyOf(tools)) {
            for (boolean byPlayer : playerKills ? new boolean[] {false, true} : new boolean[] {false}) {
                Map<String, Drop> merged = new TreeMap<>(evalTableId(tableId, new Scenario(tool, byPlayer, false)));
                merged.putAll(evalTableId(tableId, new Scenario(tool, byPlayer, true)));
                for (Map.Entry<String, Drop> e : merged.entrySet()) {
                    Drop drop = e.getValue();
                    if (drop.expected() <= EPS || drop.hit() <= EPS) continue;
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

    // ---- tables ------------------------------------------------------------------------------------------------

    private Map<String, Drop> evalTableId(String id, Scenario s) {
        Map<Scenario, Map<String, Drop>> byScenario = memo.computeIfAbsent(id, k -> new HashMap<>());
        Map<String, Drop> cached = byScenario.get(s);
        if (cached != null) return cached;
        if (!visiting.add(id)) {
            notes.count("loot table loops back to itself, the inner reference is ignored");
            return Map.of();
        }
        Map<String, Drop> result;
        try {
            JsonObject table = env.table(id);
            if (table == null) {
                notes.skipped("loot tables referenced but missing or unreadable", id);
                result = Map.of();
            } else {
                result = evalTable(table, s);
            }
        } finally {
            visiting.remove(id);
        }
        byScenario.put(s, result);
        return result;
    }

    private Map<String, Drop> evalTable(JsonObject table, Scenario s) {
        Map<String, Drop> out = new TreeMap<>();
        for (JsonElement pool : array(table, "pools")) {
            if (pool.isJsonObject()) merge(out, evalPool(pool.getAsJsonObject(), s));
        }
        return out;
    }

    private Map<String, Drop> evalPool(JsonObject pool, Scenario s) {
        double poolChance = allConditions(pool.get("conditions"), s);
        if (poolChance <= EPS) return Map.of();
        Num rolls = Num.parse(pool.get("rolls"), notes);

        List<Expansion> expansions = new ArrayList<>();
        for (JsonElement entry : array(pool, "entries")) {
            if (entry.isJsonObject()) expansions.addAll(expand(entry.getAsJsonObject(), s));
        }

        // The pool picks one entry per roll among those that expanded, by weight. For an uncertain entry, the weight
        // it competes against is its expectation.
        double[] expectedWeight = new double[expansions.size()];
        double totalExpected = 0;
        for (int i = 0; i < expansions.size(); i++) {
            for (Outcome o : expansions.get(i).outcomes()) expectedWeight[i] += o.prob() * o.totalWeight();
            totalExpected += expectedWeight[i];
        }

        Map<String, double[]> perRoll = new TreeMap<>(); // item -> {expected, hit}
        for (int i = 0; i < expansions.size(); i++) {
            for (Outcome o : expansions.get(i).outcomes()) {
                int outcomeWeight = o.totalWeight();
                for (Leaf leaf : o.leaves()) {
                    if (leaf.weight() <= 0) continue;
                    double others = (totalExpected - expectedWeight[i]) + (outcomeWeight - leaf.weight());
                    double pick = o.prob() * leaf.weight() / (leaf.weight() + others);
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

    private static void merge(Map<String, Drop> into, Map<String, Drop> from) {
        for (Map.Entry<String, Drop> e : from.entrySet()) {
            into.merge(e.getKey(), e.getValue(),
                (a, b) -> new Drop(a.expected() + b.expected(), 1 - (1 - a.hit()) * (1 - b.hit())));
        }
    }

    // ---- entries -----------------------------------------------------------------------------------------------

    private List<Expansion> expand(JsonObject entry, Scenario s) {
        double chance = allConditions(entry.get("conditions"), s);
        if (chance <= EPS) return List.of();
        String type = strip(string(entry, "type"));
        int weight = entry.has("weight") ? entry.get("weight").getAsInt() : 1;
        switch (type) {
            case "item": {
                String name = string(entry, "name");
                if (name.isEmpty()) return List.of();
                Num count = count(entry, s);
                return single(chance, new Leaf(weight, Map.of(name, new Drop(count.mean(), count.pPositive()))));
            }
            case "tag": {
                List<String> items = env.tagItems(string(entry, "name"));
                boolean expandTag = entry.has("expand") && entry.get("expand").getAsBoolean();
                if (items.isEmpty()) {
                    notes.skipped("item tags used by loot tables that are empty or unknown", string(entry, "name"));
                    return List.of();
                }
                if (expandTag) {
                    // Each item of the tag is its own entry of the pool, with the tag entry's weight.
                    List<Leaf> leaves = new ArrayList<>();
                    for (String item : items) leaves.add(new Leaf(weight, Map.of(item, new Drop(1, 1))));
                    return List.of(new Expansion(List.of(new Outcome(chance, leaves))));
                }
                Num count = count(entry, s);
                Map<String, Drop> drops = new TreeMap<>();
                for (String item : items) drops.put(item, new Drop(count.mean(), count.pPositive()));
                return single(chance, new Leaf(weight, drops));
            }
            case "loot_table": {
                JsonElement value = entry.get("value");
                Map<String, Drop> drops;
                if (value != null && value.isJsonObject()) drops = evalTable(value.getAsJsonObject(), s);
                else if (value != null && value.isJsonPrimitive()) drops = evalTableId(value.getAsString(), s);
                else return List.of();
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
                    List<Expansion> expanded = expand(c, s);
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
                    if (child.isJsonObject()) for (Expansion x : expand(child.getAsJsonObject(), s)) out.add(scale(x, chance));
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
                    for (Expansion x : expand(c, s)) out.add(scale(x, reach));
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

    /** The count one pick of an item entry gives: 1 unless a certain {@code set_count} says otherwise. */
    private Num count(JsonObject entry, Scenario s) {
        Num count = Num.constant(1);
        boolean skippedUncertain = false;
        for (JsonElement f : array(entry, "functions")) {
            if (!f.isJsonObject()) continue;
            JsonObject fn = f.getAsJsonObject();
            String id = strip(string(fn, "function"));
            if (id.equals("furnace_smelt") || id.equals("set_item")) {
                notes.skipped("loot functions that change the item (the unchanged item is indexed)", id);
                continue;
            }
            if (!id.equals("set_count")) continue;
            // Only a count that holds whatever the unjudgeable conditions say: a double slab drops two slabs only
            // when it is a double slab, and a single attempt cannot be assumed to be one.
            if (!certain(fn.get("conditions"), s)) {
                skippedUncertain = true;
                continue;
            }
            Num value = Num.parse(fn.get("count"), notes);
            count = fn.has("add") && fn.get("add").getAsBoolean() ? count.plus(value) : value;
        }
        // A multi-face block (glow lichen, vines) adds one per face and takes one off the base; with the per-face
        // additions skipped that would leave nothing. The entry exists to drop something, so a break gives at least 1.
        if (skippedUncertain && count.mean() <= 0) return Num.constant(1);
        return count;
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
