package com.legoj15.megablockbot.core.acquire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.legoj15.megablockbot.core.ItemKey;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The lint's four lists over a hand-built index (the vanilla data behind them is covered by the GameTests): a source
 * outranks a way, a recipe outranks a way, and only an item with none of the three is unobtainable. Assertions are on
 * the lists and counts the report exposes, and on the file text only where its shape is fixed (an item followed by
 * its ways, "way from").
 */
final class LintReportTest {
    private static final ItemKey LEATHER = ItemKey.of("minecraft:leather");
    private static final ItemKey LAVA_BUCKET = ItemKey.of("minecraft:lava_bucket");
    private static final ItemKey NETHER_STAR = ItemKey.of("minecraft:nether_star");
    private static final ItemKey SPAWN_EGG = ItemKey.of("minecraft:zombie_spawn_egg");
    private static final ItemKey BREAD = ItemKey.of("minecraft:bread");
    private static final ItemKey WIDGET = ItemKey.of("somemod:widget");

    private static AcquisitionIndex index() {
        return AcquisitionIndex.builder()
            .addItem(LEATHER).addItem(LAVA_BUCKET).addItem(NETHER_STAR).addItem(SPAWN_EGG).addItem(BREAD)
            .addItem(WIDGET)
            // A villager's bread is craftable and has no source; the ways below are inserted out of id order.
            .addSource(new Source.KillMob("kill:minecraft:cow", LEATHER, "minecraft:cow", false, 1.0, false))
            .addCraftable(BREAD)
            .addKnownWay(new KnownWay("barter:minecraft:gameplay/piglin_barter", LEATHER, KnownWay.BARTER,
                "minecraft:gameplay/piglin_barter"))
            .addKnownWay(new KnownWay("modded_fill:minecraft:lava", LAVA_BUCKET, "modded_fill", "minecraft:lava"))
            .addKnownWay(new KnownWay("code:wither", NETHER_STAR, KnownWay.GAME_CODE, "the wither drops it when it dies"))
            .addKnownWay(new KnownWay("other_loot:somemod:mystery", WIDGET, KnownWay.OTHER_LOOT, "somemod:mystery"))
            .addKnownWay(new KnownWay("modded_fill:somemod:acid", WIDGET, "modded_fill", "somemod:acid"))
            .build();
    }

    @Test
    void aSourceOrARecipeOutranksAWay() {
        LintReport report = LintReport.of(index(), null);
        assertEquals(6, report.total());
        assertEquals(1, report.withSource()); // leather has both a source and a way, and counts once, with the source
        assertEquals(1, report.craftableOnly());
        assertEquals(List.of(LAVA_BUCKET, NETHER_STAR, WIDGET), report.noStrategy());
        assertEquals(List.of(SPAWN_EGG), report.unobtainable());
        assertEquals(4, report.knownWays().size()); // only the no-strategy items' ways are carried, not leather's
    }

    @Test
    void theNamespaceLimitsEveryList() {
        LintReport report = LintReport.of(index(), "minecraft");
        assertEquals(5, report.total());
        assertEquals(List.of(LAVA_BUCKET, NETHER_STAR), report.noStrategy());
        LintReport mod = LintReport.of(index(), "somemod");
        assertEquals(List.of(WIDGET), mod.noStrategy());
        assertEquals(0, mod.unobtainable().size());
    }

    @Test
    void waysAreHeldApartFromTheSourcesAndSortedById() {
        AcquisitionIndex index = index();
        assertEquals(List.of(), index.knownWays(SPAWN_EGG));
        assertEquals(List.of("modded_fill:somemod:acid", "other_loot:somemod:mystery"),
            index.knownWays(WIDGET).stream().map(KnownWay::id).toList());
    }

    @Test
    void theFileListsEachNoStrategyItemWithItsWays() {
        String text = LintReport.of(index(), null).fullText();
        assertTrue(text.contains("minecraft:lava_bucket: modded_fill minecraft:lava\n"), text);
        assertTrue(text.contains("minecraft:nether_star: game_code the wither drops it when it dies\n"), text);
        assertTrue(text.contains("somemod:widget: modded_fill somemod:acid, other_loot somemod:mystery\n"), text);
        assertFalse(text.contains("minecraft:leather:"), text); // with a source, so no way line of its own
        assertTrue(text.contains("minecraft:zombie_spawn_egg\n"), text);
    }
}
