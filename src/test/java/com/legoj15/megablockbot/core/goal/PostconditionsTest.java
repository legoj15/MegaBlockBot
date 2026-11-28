package com.legoj15.megablockbot.core.goal;

import static org.junit.jupiter.api.Assertions.*;

import com.legoj15.megablockbot.core.EquipSlot;
import com.legoj15.megablockbot.core.ItemKey;
import com.legoj15.megablockbot.core.Pos;
import com.legoj15.megablockbot.core.ToolClass;
import com.legoj15.megablockbot.core.ToolTier;
import com.legoj15.megablockbot.core.snapshot.ContainerSight;
import com.legoj15.megablockbot.core.snapshot.Snapshot;
import com.legoj15.megablockbot.core.snapshot.StackFacts;
import com.legoj15.megablockbot.core.snapshot.StructureReport;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Every goal variant's postcondition, including each UNKNOWN case (design 3.4). */
class PostconditionsTest {

    private final Postconditions postconditions = new Postconditions();
    private final ItemKey diamond = ItemKey.of("diamond");

    private Evaluation eval(Goal goal, Snapshot snapshot) {
        return postconditions.of(goal).evaluate(snapshot);
    }

    @Test
    void haveItemWithoutCheckIsSatisfiedAtTheCount() {
        var goal = new Goal.HaveItem(diamond, 3);
        assertEquals(Evaluation.Status.SATISFIED, eval(goal, Snapshot.builder().item(diamond, 3).build()).status());
        Evaluation miss = eval(goal, Snapshot.builder().item(diamond, 2).build());
        assertEquals(Evaluation.Status.UNSATISFIED, miss.status());
        assertEquals(2, miss.matched());
        assertEquals(3, miss.required());
        assertEquals(List.of(diamond.id()), miss.unmet());
    }

    @Test
    void haveItemWithCheckCountsOnlyStacksThatPassIt() {
        ItemKey book = ItemKey.of("enchanted_book");
        var check = ComponentCheck.enchantment("minecraft:mending", 1);
        var goal = new Goal.HaveItem(book, 1, check);
        Snapshot wrong = Snapshot.builder().item(book, 2)
                .facts(book, StackFacts.plain(2)).build(); // present but wrong components
        Evaluation miss = eval(goal, wrong);
        assertEquals(Evaluation.Status.UNSATISFIED, miss.status());
        assertEquals(0, miss.matched());
        Snapshot right = Snapshot.builder().item(book, 2)
                .facts(book, new StackFacts(1, Map.of("minecraft:mending", 1), "", Map.of()))
                .facts(book, StackFacts.plain(1)).build();
        assertEquals(Evaluation.Status.SATISFIED, eval(goal, right).status());
    }

    @Test
    void haveItemWithCheckIsUnknownWhenTheItemIsCarriedButNotLookedAt() {
        ItemKey book = ItemKey.of("enchanted_book");
        var goal = new Goal.HaveItem(book, 1, ComponentCheck.enchantment("minecraft:mending", 1));
        Evaluation ev = eval(goal, Snapshot.builder().item(book, 1).build()); // no stack facts
        assertEquals(Evaluation.Status.UNKNOWN, ev.status());
        // Carrying none of the key is a plain miss, not unknown.
        assertEquals(Evaluation.Status.UNSATISFIED, eval(goal, Snapshot.builder().build()).status());
    }

    @Test
    void aDeliverGoalCountsOnlyTheHandOversNotedForIt() {
        // Two requests for the same item and player: each is satisfied by its own hand-over. A note carries the
        // stamp of the goal it was made for, so the three sticks handed over for the second request do not make
        // the first one done, and the first one's do not make the second.
        ItemKey stick = ItemKey.of("stick");
        String player = "11111111-2222-3333-4444-555555555555";
        Goal.Deliver first = new Goal.Deliver(stick, 3, player, 100);
        Goal.Deliver second = new Goal.Deliver(stick, 3, player, 101);
        Snapshot onlyTheSecond = Snapshot.builder()
                .delivery(new com.legoj15.megablockbot.core.snapshot.Delivery(player, stick, 3, 101)).build();
        assertFalse(eval(first, onlyTheSecond).isSatisfied());
        assertTrue(eval(second, onlyTheSecond).isSatisfied());
        Snapshot onlyTheFirst = Snapshot.builder()
                .delivery(new com.legoj15.megablockbot.core.snapshot.Delivery(player, stick, 3, 100)).build();
        assertTrue(eval(first, onlyTheFirst).isSatisfied());
        assertFalse(eval(second, onlyTheFirst).isSatisfied());
    }

    @Test
    void haveToolTierComparesTheBestToolOfThatClass() {
        var goal = new Goal.HaveToolTier(ToolClass.PICKAXE, ToolTier.IRON);
        Snapshot held = Snapshot.builder().tool(ToolClass.PICKAXE, ToolTier.DIAMOND).build();
        assertEquals(Evaluation.Status.SATISFIED, eval(goal, held).status());
        Evaluation none = eval(goal, Snapshot.builder().build());
        assertEquals(Evaluation.Status.UNSATISFIED, none.status());
        assertEquals(0, none.matched());
        assertEquals(1, none.required());
        assertEquals(List.of("PICKAXE>=IRON"), none.unmet());
    }

    @Test
    void equipRequiresEverySlotToHoldItsItem() {
        ItemKey helmet = ItemKey.of("iron_helmet");
        ItemKey boots = ItemKey.of("iron_boots");
        var goal = new Goal.Equip(Map.of(EquipSlot.HEAD, helmet, EquipSlot.FEET, boots));
        Snapshot full = Snapshot.builder().equip(EquipSlot.HEAD, helmet).equip(EquipSlot.FEET, boots).build();
        assertEquals(Evaluation.Status.SATISFIED, eval(goal, full).status());
        Snapshot half = Snapshot.builder().equip(EquipSlot.HEAD, helmet).build();
        Evaluation ev = eval(goal, half);
        assertEquals(Evaluation.Status.UNSATISFIED, ev.status());
        assertEquals(1, ev.matched());
        assertEquals(2, ev.required());
        assertEquals(List.of("FEET"), ev.unmet());
    }

    @Test
    void stockpileReadsTheKnownContainerSightInTheGoalsDimension() {
        Pos chest = new Pos(10, 64, 10);
        var goal = new Goal.Stockpile(diamond, 5, "minecraft:overworld", chest);
        Snapshot known = Snapshot.builder()
                .container(new ContainerSight(chest, "minecraft:chest", true, false, Map.of(diamond, 5)))
                .build();
        assertEquals(Evaluation.Status.SATISFIED, eval(goal, known).status());
        Snapshot shortBy = Snapshot.builder()
                .container(new ContainerSight(chest, "minecraft:chest", true, false, Map.of(diamond, 2)))
                .build();
        Evaluation ev = eval(goal, shortBy);
        assertEquals(Evaluation.Status.UNSATISFIED, ev.status());
        assertEquals(2, ev.matched());
    }

    @Test
    void stockpileReadsADoubleChestWhicheverHalfTheGoalNames() {
        // One inventory is one sight, kept at one half; the owner may type either half in the goal.
        Pos low = new Pos(13, 65, 1);
        Pos high = new Pos(14, 65, 1);
        Snapshot pair = Snapshot.builder()
                .container(new ContainerSight(low, high, "minecraft:chest", true, false, false, true, Map.of(diamond, 5)))
                .build();
        for (Pos named : List.of(low, high)) {
            assertEquals(Evaluation.Status.SATISFIED, eval(new Goal.Stockpile(diamond, 5, "minecraft:overworld", named), pair).status(),
                    "the goal names " + named + ", a half of the pair");
        }
        Evaluation shortBy = eval(new Goal.Stockpile(diamond, 8, "minecraft:overworld", high), pair);
        assertEquals(Evaluation.Status.UNSATISFIED, shortBy.status(), "the far half is not 'no sight'");
        assertEquals(5, shortBy.matched());
        // Another position is no part of the pair.
        assertEquals(Evaluation.Status.UNKNOWN,
                eval(new Goal.Stockpile(diamond, 5, "minecraft:overworld", new Pos(15, 65, 1)), pair).status());
    }

    @Test
    void stockpileIsUnknownWhenTheDimensionDiffersOrThereIsNoSightOrContentsAreUnknown() {
        Pos chest = new Pos(10, 64, 10);
        var goal = new Goal.Stockpile(diamond, 5, "minecraft:overworld", chest);
        assertEquals(Evaluation.Status.UNKNOWN,
                eval(goal, Snapshot.builder().dimension("minecraft:the_nether")
                        .container(new ContainerSight(chest, "minecraft:chest", true, false, Map.of())).build())
                        .status());
        assertEquals(Evaluation.Status.UNKNOWN,
                eval(goal, Snapshot.builder().build()).status()); // no sight at that position
        assertEquals(Evaluation.Status.UNKNOWN,
                eval(goal, Snapshot.builder()
                        .container(new ContainerSight(chest, "minecraft:chest", false, false, Map.of())).build())
                        .status()); // out of range: never "empty"
    }

    @Test
    void buildReadsTheStructureReportUnderBlueprintAtAnchor() {
        Pos anchor = new Pos(0, 64, 0);
        var goal = new Goal.Build("hut", "minecraft:overworld", anchor);
        String key = "hut@" + anchor.encode();
        Snapshot done = Snapshot.builder().structure(key,
                new StructureReport(true, 10, 10, Map.of())).build();
        assertEquals(Evaluation.Status.SATISFIED, eval(goal, done).status());
        Snapshot half = Snapshot.builder().structure(key,
                new StructureReport(true, 4, 10, Map.of(ItemKey.of("planks"), 6))).build();
        Evaluation ev = eval(goal, half);
        assertEquals(Evaluation.Status.UNSATISFIED, ev.status());
        assertEquals(4, ev.matched());
        assertEquals(10, ev.required());
        assertEquals(List.of("minecraft:planks"), ev.unmet());
    }

    @Test
    void buildIsUnknownWhenThereIsNoReportOrItIsNotKnown() {
        var goal = new Goal.Build("hut", "minecraft:overworld", new Pos(0, 64, 0));
        assertEquals(Evaluation.Status.UNKNOWN, eval(goal, Snapshot.builder().build()).status());
        assertEquals(Evaluation.Status.UNKNOWN, eval(goal, Snapshot.builder()
                .structure("hut@0,64,0", new StructureReport(false, 0, 10, Map.of())).build()).status());
    }

    @Test
    void anUnknownBuildCarriesTheReportsRequiredCountWhenThereIsAReport() {
        var goal = new Goal.Build("hut", "minecraft:overworld", new Pos(0, 64, 0));
        Evaluation partlyLoaded = eval(goal, Snapshot.builder()
                .structure("hut@0,64,0", new StructureReport(false, 3, 10, Map.of())).build());
        assertEquals(Evaluation.Status.UNKNOWN, partlyLoaded.status());
        assertEquals(10, partlyLoaded.required()); // so a give-up knows how much was asked for
        assertEquals(3, partlyLoaded.matched()); // and how much of it stands, so the result can be PARTIAL
        Evaluation noReport = eval(goal, Snapshot.builder().build());
        assertEquals(0, noReport.required()); // nothing to carry
    }

    @Test
    void reachIsHorizontalDistanceWithinRadiusWithALooseVerticalTolerance() {
        var goal = new Goal.Reach("minecraft:overworld", new Pos(100, 64, 100), 8);
        Snapshot there = Snapshot.builder().pos(new Pos(103, 66, 100)).build();
        assertEquals(Evaluation.Status.SATISFIED, eval(goal, there).status());
        Evaluation far = eval(goal, Snapshot.builder().pos(new Pos(120, 64, 100)).build());
        assertEquals(Evaluation.Status.UNSATISFIED, far.status());
        assertEquals(List.of("minecraft:overworld@100,64,100"), far.unmet());
        // Two blocks up or down is inside even with a small radius...
        assertEquals(Evaluation.Status.SATISFIED,
                eval(goal, Snapshot.builder().pos(new Pos(100, 66, 100)).build()).status());
        // ...and another dimension is a miss, not unknown.
        assertEquals(Evaluation.Status.UNSATISFIED,
                eval(goal, Snapshot.builder().dimension("minecraft:the_nether").pos(new Pos(100, 64, 100)).build())
                        .status());
    }

    @Test
    void aRegisteredCustomKindUsesItsFactory() {
        postconditions.register("villager_offers", g -> snapshot -> Evaluation.satisfied(1));
        Goal.Custom goal = new Goal.Custom("villager_offers", Map.of("villager", "v1"));
        assertEquals(Evaluation.Status.SATISFIED, eval(goal, Snapshot.builder().build()).status());
    }

    @Test
    void anUnregisteredCustomKindIsUnknownNeverDone() {
        Goal.Custom goal = new Goal.Custom("librarian_reroll", Map.of());
        assertEquals(Evaluation.Status.UNKNOWN, eval(goal, Snapshot.builder().build()).status());
    }
}
