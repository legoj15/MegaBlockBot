package com.legoj15.megablockbot.game.gametest;

import com.legoj15.megablockbot.MegaBlockBot;
import com.legoj15.megablockbot.core.ItemKey;
import com.legoj15.megablockbot.core.ToolClass;
import com.legoj15.megablockbot.core.ToolTier;
import com.legoj15.megablockbot.core.acquire.AcquisitionIndex;
import com.legoj15.megablockbot.core.acquire.Source;
import com.legoj15.megablockbot.game.resolver.AcquisitionIndexes;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/**
 * The acquisition index built from the real vanilla data the GameTest server loads. What these protect: a Minecraft
 * version that changes the loot-table or tag format (the codec-written JSON the resolver walks, the {@code
 * incorrect_for_*_tool} tags the tiers come from), and the alternatives/silk-touch logic that keeps an ore's plain
 * drop from being hidden behind its silk-touch drop.
 */
public final class ResolverGameTests {
    private ResolverGameTests() {}

    private static AcquisitionIndex index(GameTestHelper helper) {
        AcquisitionIndex index = AcquisitionIndexes.current();
        if (index.items().isEmpty()) helper.fail("the acquisition index was not built before the test ran");
        return index;
    }

    private static <T extends Source> Optional<T> find(AcquisitionIndex index, String item, Class<T> kind, Predicate<T> match) {
        return index.sources(ItemKey.of(item)).stream().filter(kind::isInstance).map(kind::cast).filter(match).findFirst();
    }

    // helper.fail throws the framework's assertion exception, so a missing source ends the test right here.
    private static Source.MineBlock mine(GameTestHelper helper, AcquisitionIndex index, String item, String block, boolean silkTouch) {
        Optional<Source.MineBlock> found = find(index, item, Source.MineBlock.class,
            m -> m.block().equals(block) && m.needsSilkTouch() == silkTouch);
        if (found.isEmpty()) {
            helper.fail(item + " has no " + (silkTouch ? "silk-touch " : "") + "MineBlock source from " + block
                + "; its sources are " + index.sources(ItemKey.of(item)));
        }
        return found.get();
    }

    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 20)
    public static void diamondComesFromBothOresWithAnIronPickaxe(GameTestHelper helper) {
        AcquisitionIndex index = index(helper);
        for (String block : List.of("minecraft:diamond_ore", "minecraft:deepslate_diamond_ore")) {
            Source.MineBlock source = mine(helper, index, "minecraft:diamond", block, false);
            helper.assertTrue(source.toolClass() == ToolClass.PICKAXE, block + " tool class is " + source.toolClass());
            helper.assertTrue(source.minTier() == ToolTier.IRON, block + " min tier is " + source.minTier());
            helper.assertTrue(source.toolRequired(), block + " does not require a tool");
            helper.assertTrue(!source.needsSilkTouch(), block + " needs silk touch for diamond");
            helper.assertTrue(source.ore() != null, block + " has no ore range");
            helper.assertTrue(source.ore().bestY() < 0, block + " best Y is " + source.ore().bestY() + ", expected below 0");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 20)
    public static void cobblestoneComesFromStoneWithAWoodenPickaxe(GameTestHelper helper) {
        AcquisitionIndex index = index(helper);
        Source.MineBlock cobble = mine(helper, index, "minecraft:cobblestone", "minecraft:stone", false);
        helper.assertTrue(cobble.minTier() == ToolTier.WOOD, "cobblestone from stone has min tier " + cobble.minTier());
        // The silk-touch branch of stone must not hide the plain one, and must not be lost either.
        Source.MineBlock stone = mine(helper, index, "minecraft:stone", "minecraft:stone", true);
        helper.assertTrue(stone.needsSilkTouch(), "stone from stone does not need silk touch");
        helper.succeed();
    }

    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 20)
    public static void logsNeedNoTool(GameTestHelper helper) {
        AcquisitionIndex index = index(helper);
        Source.MineBlock log = mine(helper, index, "minecraft:oak_log", "minecraft:oak_log", false);
        helper.assertTrue(!log.toolRequired(), "an oak log requires a tool");
        helper.succeed();
    }

    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 20)
    public static void mendingBookIsALibrarianTrade(GameTestHelper helper) {
        AcquisitionIndex index = index(helper);
        Optional<Source.Trade> trade = find(index, "minecraft:enchanted_book", Source.Trade.class,
            t -> t.profession().equals("minecraft:librarian") && t.enchantments().containsKey("minecraft:mending"));
        helper.assertTrue(trade.isPresent(), "no librarian trade for a book of mending; the enchanted book sources are "
            + index.sources(ItemKey.of("minecraft:enchanted_book")));
        helper.succeed();
    }

    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 20)
    public static void stringComesFromSpidersAndFishing(GameTestHelper helper) {
        AcquisitionIndex index = index(helper);
        helper.assertTrue(find(index, "minecraft:string", Source.KillMob.class, k -> k.entity().equals("minecraft:spider")).isPresent(),
            "string has no kill source for the spider: " + index.sources(ItemKey.of("minecraft:string")));
        helper.assertTrue(find(index, "minecraft:string", Source.Fishing.class, f -> true).isPresent(),
            "string has no fishing source: " + index.sources(ItemKey.of("minecraft:string")));
        helper.succeed();
    }

    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 20)
    public static void wheatIsAHarvest(GameTestHelper helper) {
        AcquisitionIndex index = index(helper);
        helper.assertTrue(find(index, "minecraft:wheat", Source.Harvest.class, Source.Harvest::renewable).isPresent(),
            "wheat has no renewable harvest source: " + index.sources(ItemKey.of("minecraft:wheat")));
        helper.succeed();
    }

    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 20)
    public static void creativeOnlyItemsAreUnobtainable(GameTestHelper helper) {
        AcquisitionIndex index = index(helper);
        for (String id : List.of("minecraft:bedrock", "minecraft:command_block", "minecraft:barrier")) {
            ItemKey item = ItemKey.of(id);
            helper.assertTrue(index.items().contains(item), id + " is not in the item universe");
            helper.assertTrue(index.sources(item).isEmpty(), id + " has sources: " + index.sources(item));
            helper.assertTrue(!index.craftable().contains(item), id + " is craftable");
        }
        helper.assertTrue(index.craftable().contains(ItemKey.of("minecraft:diamond_pickaxe")), "a diamond pickaxe is not craftable");
        helper.succeed();
    }

    // Planners key a source by (item, id); two sources of one item with one id would silently merge into one.
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 20)
    public static void sourceIdsAreUniquePerItem(GameTestHelper helper) {
        AcquisitionIndex index = index(helper);
        for (ItemKey item : index.sourced()) {
            Set<String> ids = new HashSet<>();
            for (Source source : index.sources(item)) {
                helper.assertTrue(ids.add(source.id()), item + " has two sources with the id " + source.id());
            }
        }
        helper.succeed();
    }
}
