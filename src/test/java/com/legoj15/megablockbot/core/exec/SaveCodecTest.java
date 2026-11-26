package com.legoj15.megablockbot.core.exec;

import static org.junit.jupiter.api.Assertions.*;

import com.legoj15.megablockbot.core.EquipSlot;
import com.legoj15.megablockbot.core.ItemKey;
import com.legoj15.megablockbot.core.Pos;
import com.legoj15.megablockbot.core.ToolClass;
import com.legoj15.megablockbot.core.ToolTier;
import com.legoj15.megablockbot.core.goal.ComponentCheck;
import com.legoj15.megablockbot.core.goal.Goal;
import com.legoj15.megablockbot.core.goal.Postconditions;
import com.legoj15.megablockbot.core.plan.Exclusions;
import com.legoj15.megablockbot.core.plan.NodeSpec;
import com.legoj15.megablockbot.core.task.Failure;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Round trips over every goal variant and the other saved shapes, plus the malformed cases. */
class SaveCodecTest {

    @Test
    void everyGoalVariantRoundTrips() {
        List<Goal> goals = List.of(
                new Goal.HaveItem(ItemKey.of("diamond"), 3),
                new Goal.HaveItem(ItemKey.of("enchanted_book"), 1,
                        new ComponentCheck(Map.of("minecraft:mending", 1), "minecraft:swiftness")),
                new Goal.HaveToolTier(ToolClass.PICKAXE, ToolTier.IRON),
                new Goal.Equip(Map.of(EquipSlot.HEAD, ItemKey.of("iron_helmet"),
                        EquipSlot.MAINHAND, ItemKey.of("diamond_sword"))),
                new Goal.Stockpile(ItemKey.of("cobblestone"), 64, "minecraft:overworld", new Pos(1, 2, 3)),
                new Goal.Build("quarry", "minecraft:overworld", new Pos(-4, 65, -5)),
                new Goal.Reach("minecraft:the_nether", new Pos(0, 40, 0), 8),
                new Goal.Custom("librarian_reroll", Map.of("offer", "mending")));
        for (Goal goal : goals) {
            assertEquals(goal, SaveCodec.decodeGoal(SaveCodec.encodeGoal(goal)), () -> "round trip of " + goal);
        }
    }

    @Test
    void aNodeRoundTripsWithItsStateAndInsertedFlag() {
        NodeSpec spec = NodeSpec.group(new Goal.HaveItem(ItemKey.of("diamond"), 1), List.of(
                NodeSpec.leaf(new Goal.HaveItem(ItemKey.of("iron_ore"), 2), "mine",
                        Map.of("pos", "10,64,10"), "mine:iron_ore").asBestEffort(),
                new NodeSpec(new Goal.Custom("watch", Map.of("k", "v")), "watch", Map.of(), "trade:x",
                        false, List.of())));
        Map<String, Object> encoded = SaveCodec.encodeNode(ExecNode.of(spec, true, null));
        SaveCodec.SavedNode decoded = SaveCodec.decodeNode(encoded);
        assertEquals(spec, decoded.spec());
        assertTrue(decoded.executorInserted());
        assertEquals(NodeState.PENDING, decoded.state());

        @SuppressWarnings("unchecked")
        Map<String, Object> firstChild = (Map<String, Object>) ((List<?>) encoded.get("children")).get(0);
        SaveCodec.SavedNode child = SaveCodec.decodeNode(firstChild);
        assertEquals(NodeState.PENDING, child.state());
        assertFalse(child.executorInserted()); // children are planner nodes, not executor-inserted

        var node = ExecNode.of(spec, true, null);
        node.state = NodeState.SATISFIED;
        SaveCodec.SavedNode satisfied = SaveCodec.decodeNode(SaveCodec.encodeNode(node));
        assertEquals(NodeState.SATISFIED, satisfied.state());
        assertTrue(satisfied.executorInserted());
    }

    @Test
    void exclusionsRoundTrip() {
        Exclusions exclusions = new Exclusions(
                java.util.Set.of("mine:iron_ore"),
                java.util.Set.of(new Pos(1, 2, 3)),
                List.of(new Exclusions.Area("minecraft:overworld", new Pos(5, 6, 7), 16, "death")));
        assertEquals(exclusions, SaveCodec.decodeExclusions(SaveCodec.encodeExclusions(exclusions)));
    }

    @Test
    void aDeathRecordRoundTrips() {
        Failure.Died died = new Failure.Died("minecraft:overworld", new Pos(1, 2, 3),
                List.of(new Pos(4, 5, 6), new Pos(7, 8, 9)), 12345L, "fell");
        Failure.Died decoded = SaveCodec.decodeDeath(SaveCodec.encodeDeath(died));
        assertEquals(died.dimension(), decoded.dimension());
        assertEquals(died.deathPos(), decoded.deathPos());
        assertEquals(died.dropPositions(), decoded.dropPositions());
        assertEquals(died.deathTime(), decoded.deathTime());
        assertEquals(died.detail(), decoded.detail());
    }

    @Test
    void theSaveFormHasOnlyJsonLikeValuesAndNoNulls() {
        Fakes.FakeHost host = new Fakes.FakeHost();
        GoalExecutor<Object> exec = new GoalExecutor<>(host, new Postconditions(), ExecutorConfig.defaults());
        ItemKey diamond = ItemKey.of("diamond");
        Fakes.FakeTask live = new Fakes.FakeTask();
        live.checkpoint = Map.of("shaft", "10,5,10");
        host.tasks.add(live);
        // A tree with a group: the group has no task kind, and the save must omit it rather than write null.
        host.offerPlan(NodeSpec.group(new Goal.HaveItem(diamond, 1), List.of(
                NodeSpec.leaf(new Goal.HaveItem(diamond, 1), "mine", Map.of("pos", "1,2,3"), "mine:ore"))));
        exec.submit(new Goal.HaveItem(diamond, 1));
        Object body = new Object();
        for (int i = 0; i < 4; i++) exec.tick(body, ReflexHold.NONE); // plan, adopt, start, one task tick
        exec.onDeath(new Failure.Died("minecraft:overworld", new Pos(1, 2, 3), List.of(new Pos(4, 5, 6)), 7L, ""));
        Map<String, Object> saved = exec.save();

        List<?> tree = (List<?>) saved.get("tree");
        assertEquals(1, tree.size());
        Map<?, ?> root = (Map<?, ?>) tree.get(0);
        assertTrue(root.containsKey("children"), "the saved tree must contain a group");
        assertFalse(root.containsKey("kind"), "a group has no task kind to save");
        assertTrue(saved.containsKey("deaths"));
        assertJsonLike(saved, 0);
    }

    private void assertJsonLike(Object value, int depth) {
        assertTrue(depth < 12, "too deep");
        if (value instanceof String || value instanceof Double || value instanceof Boolean) {
            return;
        }
        if (value instanceof Number n) {
            assertEquals(Long.class, n.getClass(), "numbers are Long"); // Integer would surprise NBT codecs
            return;
        }
        if (value instanceof List<?> list) {
            for (Object o : list) assertJsonLike(o, depth + 1);
            return;
        }
        if (value instanceof Map<?, ?> m) {
            m.forEach((k, v) -> {
                assertInstanceOf(String.class, k, "map keys are strings");
                assertNotNull(v, "the save form has no null values (key " + k + ")");
                assertJsonLike(v, depth + 1);
            });
            return;
        }
        fail("not a save-form value: " + (value == null ? "null" : value.getClass()));
    }

    @Test
    void malformedInputIsADecodeFailureNotACrash() {
        assertThrows(IllegalArgumentException.class, () -> SaveCodec.decodeGoal(Map.of("type", "nonsense")));
        assertThrows(IllegalArgumentException.class, () -> SaveCodec.decodeGoal("not a map"));
        assertThrows(IllegalArgumentException.class, () -> SaveCodec.decodeGoal(
                Map.of("type", "reach", "dim", "d", "pos", "1,2", "radius", 4)));
        assertThrows(IllegalArgumentException.class, () -> SaveCodec.decodeExclusions(Map.of("areas", "no")));
    }
}
