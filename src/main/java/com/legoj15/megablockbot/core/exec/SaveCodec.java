package com.legoj15.megablockbot.core.exec;

import com.legoj15.megablockbot.core.EquipSlot;
import com.legoj15.megablockbot.core.ItemKey;
import com.legoj15.megablockbot.core.Pos;
import com.legoj15.megablockbot.core.ToolClass;
import com.legoj15.megablockbot.core.ToolTier;
import com.legoj15.megablockbot.core.goal.ComponentCheck;
import com.legoj15.megablockbot.core.goal.Goal;
import com.legoj15.megablockbot.core.plan.Exclusions;
import com.legoj15.megablockbot.core.plan.NodeSpec;
import com.legoj15.megablockbot.core.task.Failure;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Encodes and decodes the save form (design 3.4, Persistence): a JSON-like tree of {@code String}, {@code Long},
 * {@code Double}, {@code Boolean}, {@code List} and nested maps -- never a null value -- that Minecraft code turns
 * into NBT. The executor never sees NBT. Decode throws {@link IllegalArgumentException} on anything malformed;
 * {@link GoalExecutor#load} catches it so a bad save never crashes the server. Package-private: only the
 * executor reads and writes the form.
 */
final class SaveCodec {

    private SaveCodec() {}

    // --- Goal --------------------------------------------------------------------------------

    static Map<String, Object> encodeGoal(Goal goal) {
        Map<String, Object> m = new LinkedHashMap<>();
        switch (goal) {
            case Goal.HaveItem g -> {
                m.put("type", "have_item");
                m.put("key", g.key().id());
                m.put("count", (long) g.count());
                if (g.check() != null) {
                    Map<String, Object> check = new LinkedHashMap<>();
                    Map<String, Object> ench = new LinkedHashMap<>();
                    g.check().minEnchantments().forEach((id, lvl) -> ench.put(id, (long) lvl));
                    check.put("ench", ench);
                    check.put("potion", g.check().potion());
                    m.put("check", check);
                }
            }
            case Goal.HaveToolTier g -> {
                m.put("type", "tool_tier");
                m.put("tool", g.toolClass().name());
                m.put("tier", g.tier().name());
            }
            case Goal.Equip g -> {
                m.put("type", "equip");
                Map<String, Object> slots = new LinkedHashMap<>();
                g.slots().forEach((slot, key) -> slots.put(slot.name(), key.id()));
                m.put("slots", slots);
            }
            case Goal.Stockpile g -> {
                m.put("type", "stockpile");
                m.put("key", g.key().id());
                m.put("count", (long) g.count());
                m.put("dim", g.dimension());
                m.put("pos", g.container().encode());
            }
            case Goal.Build g -> {
                m.put("type", "build");
                m.put("blueprint", g.blueprint());
                m.put("dim", g.dimension());
                m.put("pos", g.anchor().encode());
            }
            case Goal.Reach g -> {
                m.put("type", "reach");
                m.put("dim", g.dimension());
                m.put("pos", g.pos().encode());
                m.put("radius", (long) g.radius());
            }
            case Goal.Custom g -> {
                m.put("type", "custom");
                m.put("kind", g.kind());
                m.put("params", new LinkedHashMap<>(g.params()));
            }
        }
        return m;
    }

    static Goal decodeGoal(Object o) {
        Map<String, Object> m = expectMap(o, "goal");
        return switch (str(m, "type")) {
            case "have_item" -> {
                ComponentCheck check = null;
                if (m.containsKey("check") && m.get("check") != null) {
                    Map<String, Object> c = expectMap(m.get("check"), "check");
                    Map<String, Integer> ench = new HashMap<>();
                    expectMap(c.get("ench"), "ench").forEach((id, lvl) -> ench.put(id, intOf(lvl, "level")));
                    check = new ComponentCheck(ench, c.containsKey("potion") ? str(c, "potion") : "");
                }
                yield new Goal.HaveItem(ItemKey.of(str(m, "key")), intOf(m.get("count"), "count"), check);
            }
            case "tool_tier" -> new Goal.HaveToolTier(ToolClass.valueOf(str(m, "tool")), ToolTier.valueOf(str(m, "tier")));
            case "equip" -> {
                Map<EquipSlot, ItemKey> slots = new HashMap<>();
                expectMap(m.get("slots"), "slots").forEach((slot, id) ->
                        slots.put(EquipSlot.valueOf(slot), ItemKey.of((String) id)));
                yield new Goal.Equip(slots);
            }
            case "stockpile" -> new Goal.Stockpile(ItemKey.of(str(m, "key")), intOf(m.get("count"), "count"),
                    str(m, "dim"), Pos.parse(str(m, "pos")));
            case "build" -> new Goal.Build(str(m, "blueprint"), str(m, "dim"), Pos.parse(str(m, "pos")));
            case "reach" -> new Goal.Reach(str(m, "dim"), Pos.parse(str(m, "pos")), intOf(m.get("radius"), "radius"));
            case "custom" -> {
                Map<String, String> params = new HashMap<>();
                if (m.containsKey("params") && m.get("params") != null) {
                    expectMap(m.get("params"), "params").forEach((k, v) -> params.put(k, (String) v));
                }
                yield new Goal.Custom(str(m, "kind"), params);
            }
            default -> throw new IllegalArgumentException("unknown goal type");
        };
    }

    // --- Node spec with its runtime state ----------------------------------------------------

    /** A decoded tree node: the spec plus what the executor knew about it. */
    record SavedNode(NodeSpec spec, NodeState state, boolean executorInserted) {}

    static Map<String, Object> encodeNode(ExecNode<?> node) {
        Map<String, Object> m = new LinkedHashMap<>();
        NodeSpec spec = node.spec;
        m.put("goal", encodeGoal(spec.goal()));
        // A group has no task kind: leave the key out rather than save a null the NBT layer cannot hold.
        if (spec.taskKind() != null) m.put("kind", spec.taskKind());
        if (!spec.params().isEmpty()) m.put("params", new LinkedHashMap<>(spec.params()));
        m.put("source", spec.source());
        m.put("best", spec.bestEffort());
        m.put("state", node.state.name());
        m.put("ins", node.executorInserted);
        List<Object> children = new ArrayList<>();
        for (ExecNode<?> child : node.children) children.add(encodeNode(child));
        if (!children.isEmpty()) m.put("children", children);
        return m;
    }

    static SavedNode decodeNode(Object o) {
        Map<String, Object> m = expectMap(o, "node");
        NodeState state = NodeState.valueOf(str(m, "state"));
        boolean inserted = m.containsKey("ins") && Boolean.TRUE.equals(m.get("ins"));
        return new SavedNode(decodeSpec(m), state, inserted);
    }

    /**
     * The executor-inserted nodes of a saved tree, and only those. The restart replan rebuilds everything the
     * planner made, so the planner's nodes (and the states of their children) are never decoded: a malformed one
     * cannot drop a goal that is about to be replanned anyway. The structure of the list itself is still checked.
     */
    static List<SavedNode> decodeInsertedNodes(Object tree) {
        List<SavedNode> inserted = new ArrayList<>();
        for (Object o : expectList(tree, "tree")) {
            Map<String, Object> m = expectMap(o, "node");
            if (Boolean.TRUE.equals(m.get("ins"))) inserted.add(decodeNode(m));
        }
        return inserted;
    }

    /** A node's spec with its children's specs: what the planner made, without the states the executor added. */
    private static NodeSpec decodeSpec(Map<String, Object> m) {
        Goal goal = decodeGoal(m.get("goal"));
        String taskKind = m.containsKey("kind") && m.get("kind") != null ? str(m, "kind") : null;
        Map<String, String> params = new HashMap<>();
        if (m.containsKey("params") && m.get("params") != null) {
            expectMap(m.get("params"), "params").forEach((k, v) -> params.put(k, (String) v));
        }
        String source = m.containsKey("source") ? str(m, "source") : "";
        boolean best = m.containsKey("best") && Boolean.TRUE.equals(m.get("best"));
        List<NodeSpec> children = new ArrayList<>();
        if (m.containsKey("children") && m.get("children") != null) {
            for (Object c : expectList(m.get("children"), "children")) children.add(decodeSpec(expectMap(c, "node")));
        }
        return new NodeSpec(goal, taskKind, params, source, best, children);
    }

    // --- Exclusions and death records --------------------------------------------------------

    static Map<String, Object> encodeExclusions(Exclusions e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sources", new ArrayList<>(e.sources()));
        List<Object> blocks = new ArrayList<>();
        for (Pos p : e.blocks()) blocks.add(p.encode());
        m.put("blocks", blocks);
        List<Object> areas = new ArrayList<>();
        for (Exclusions.Area a : e.areas()) {
            Map<String, Object> am = new LinkedHashMap<>();
            am.put("dim", a.dimension());
            am.put("pos", a.center().encode());
            am.put("radius", (long) a.radius());
            am.put("reason", a.reason());
            areas.add(am);
        }
        m.put("areas", areas);
        return m;
    }

    static Exclusions decodeExclusions(Object o) {
        Map<String, Object> m = expectMap(o, "exclusions");
        Set<String> sources = new java.util.HashSet<>(expectList(m.get("sources"), "sources").stream()
                .map(s -> (String) s).toList());
        Set<Pos> blocks = new java.util.HashSet<>(expectList(m.get("blocks"), "blocks").stream()
                .map(s -> Pos.parse((String) s)).toList());
        List<Exclusions.Area> areas = new ArrayList<>();
        for (Object a : expectList(m.get("areas"), "areas")) {
            Map<String, Object> am = expectMap(a, "area");
            areas.add(new Exclusions.Area(str(am, "dim"), Pos.parse(str(am, "pos")),
                    intOf(am.get("radius"), "radius"), am.containsKey("reason") ? str(am, "reason") : ""));
        }
        return new Exclusions(sources, blocks, areas);
    }

    static Map<String, Object> encodeDeath(Failure.Died d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dim", d.dimension());
        m.put("pos", d.deathPos().encode());
        List<Object> drops = new ArrayList<>();
        for (Pos p : d.dropPositions()) drops.add(p.encode());
        m.put("drops", drops);
        m.put("time", d.deathTime());
        m.put("detail", d.detail() == null ? "" : d.detail());
        return m;
    }

    static Failure.Died decodeDeath(Object o) {
        Map<String, Object> m = expectMap(o, "death");
        List<Pos> drops = new ArrayList<>();
        for (Object p : expectList(m.get("drops"), "drops")) drops.add(Pos.parse((String) p));
        return new Failure.Died(str(m, "dim"), Pos.parse(str(m, "pos")), drops, longOf(m.get("time"), "time"),
                m.containsKey("detail") ? str(m, "detail") : "");
    }

    // --- Counters and checkpoints ------------------------------------------------------------

    static Map<String, Object> encodeCounters(ReplanCounters counters) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", (long) counters.totalReplans);
        m.put("noProgress", (long) counters.consecutiveWithoutProgress);
        m.put("repair", (long) counters.repairReplans);
        m.put("deaths", (long) counters.deaths);
        return m;
    }

    /** Reads the counters into {@code into}; a key that is missing counts as zero. */
    static void decodeCountersInto(Object o, ReplanCounters into) {
        Map<String, Object> c = expectMap(o, "counters");
        into.totalReplans = c.containsKey("total") ? intOf(c.get("total"), "total") : 0;
        into.consecutiveWithoutProgress = c.containsKey("noProgress") ? intOf(c.get("noProgress"), "noProgress") : 0;
        into.repairReplans = c.containsKey("repair") ? intOf(c.get("repair"), "repair") : 0;
        into.deaths = c.containsKey("deaths") ? intOf(c.get("deaths"), "deaths") : 0;
    }

    static Map<String, Object> encodeCheckpoints(Map<String, Map<String, String>> checkpoints) {
        Map<String, Object> m = new LinkedHashMap<>();
        checkpoints.forEach((id, cp) -> m.put(id, new LinkedHashMap<>(cp)));
        return m;
    }

    /**
     * Reads the checkpoint map. An entry that is not a string map is dropped alone and reported to
     * {@code onDropped}; the others are kept. A checkpoint map that is not a map at all is a decode failure.
     */
    static Map<String, Map<String, String>> decodeCheckpoints(Object o,
                                                              java.util.function.BiConsumer<String, RuntimeException> onDropped) {
        Map<String, Map<String, String>> kept = new HashMap<>();
        for (Map.Entry<?, ?> e : ((Map<?, ?>) expectMap(o, "checkpoints")).entrySet()) {
            // A key that is not a string (a null from a damaged save) would be written back as it is.
            if (!(e.getKey() instanceof String id)) {
                onDropped.accept(String.valueOf(e.getKey()), new IllegalArgumentException("not a string key"));
                continue;
            }
            try {
                Map<String, String> cp = new HashMap<>();
                expectMap(e.getValue(), "checkpoint").forEach((k, val) -> {
                    if (k == null) throw new IllegalArgumentException("null key");
                    if (!(val instanceof String s)) throw new IllegalArgumentException("not a string value");
                    cp.put(k, s);
                });
                kept.put(id, cp);
            } catch (RuntimeException ex) {
                onDropped.accept(id, ex);
            }
        }
        return kept;
    }

    // --- Field readers: wrong shapes are decode failures, not crashes ------------------------

    static Map<String, Object> expectMap(Object o, String what) {
        if (!(o instanceof Map<?, ?> m)) throw new IllegalArgumentException(what + " is not a map");
        @SuppressWarnings("unchecked")
        Map<String, Object> typed = (Map<String, Object>) m;
        return typed;
    }

    static List<Object> expectList(Object o, String what) {
        if (!(o instanceof List<?> l)) throw new IllegalArgumentException(what + " is not a list");
        @SuppressWarnings("unchecked")
        List<Object> typed = (List<Object>) l;
        return typed;
    }

    static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (!(v instanceof String s)) throw new IllegalArgumentException(key + " is not a string");
        return s;
    }

    static int intOf(Object v, String what) {
        if (v instanceof Number n) return n.intValue();
        throw new IllegalArgumentException(what + " is not a number");
    }

    static long longOf(Object v, String what) {
        if (v instanceof Number n) return n.longValue();
        throw new IllegalArgumentException(what + " is not a number");
    }
}
