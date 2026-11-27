package com.legoj15.megablockbot.game.gametest;

import com.legoj15.megablockbot.MegaBlockBot;
import com.legoj15.megablockbot.core.ItemKey;
import com.legoj15.megablockbot.core.ToolClass;
import com.legoj15.megablockbot.core.ToolTier;
import com.legoj15.megablockbot.core.acquire.AcquisitionIndex;
import com.legoj15.megablockbot.core.acquire.KnownWay;
import com.legoj15.megablockbot.core.acquire.LintReport;
import com.legoj15.megablockbot.core.acquire.Source;
import com.legoj15.megablockbot.game.resolver.AcquisitionIndexBuilder;
import com.legoj15.megablockbot.game.resolver.AcquisitionIndexes;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.VillagerTrades;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;

/**
 * The acquisition index built from the real vanilla data the GameTest server loads. What these protect: a Minecraft
 * version that changes the loot-table or tag format (the codec-written JSON the resolver walks, the {@code
 * incorrect_for_*_tool} tags the tiers come from), and the alternatives/silk-touch logic that keeps an ore's plain
 * drop from being hidden behind its silk-touch drop.
 */
public final class ResolverGameTests {
    private ResolverGameTests() {}

    /** The overworld's noise settings put the sea at y=63. */
    private static final int OVERWORLD_SEA_LEVEL = 63;

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

    /**
     * Which blocks the world generates is read from the generator's own data. What it protects: a read that finds
     * nothing would leave every made block's source standing (a wasted search each), and one that finds too little
     * would take real sources away, so both sides are asked, by blocks a first day's plan meets.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 20)
    public static void generatedBlocksAreToldFromMadeOnes(GameTestHelper helper) {
        AcquisitionIndex index = index(helper);
        String[][] generated = {
            {"minecraft:cobblestone", "minecraft:stone"}, {"minecraft:oak_log", "minecraft:oak_log"},
            {"minecraft:raw_iron", "minecraft:iron_ore"}, {"minecraft:coal", "minecraft:coal_ore"},
            {"minecraft:diorite", "minecraft:diorite"}, {"minecraft:clay_ball", "minecraft:clay"},
            {"minecraft:sand", "minecraft:sand"}, {"minecraft:glowstone_dust", "minecraft:glowstone"},
            {"minecraft:redstone", "minecraft:redstone_ore"}, {"minecraft:obsidian", "minecraft:obsidian"},
            {"minecraft:diamond", "minecraft:diamond_ore"}, {"minecraft:raw_gold", "minecraft:gold_ore"}};
        for (String[] pair : generated) {
            helper.assertTrue(mine(helper, index, pair[0], pair[1], false).generated(), pair[1] + " is not counted as generated");
        }
        String[][] made = {
            {"minecraft:charcoal", "minecraft:campfire"}, {"minecraft:book", "minecraft:bookshelf"},
            {"minecraft:crafting_table", "minecraft:crafting_table"}, {"minecraft:oak_planks", "minecraft:oak_planks"},
            {"minecraft:redstone", "minecraft:redstone_wire"}};
        for (String[] pair : made) {
            helper.assertTrue(!mine(helper, index, pair[0], pair[1], false).generated(), pair[1] + " is counted as generated");
        }
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

    // Iron is generated by three features: a mountain-only one peaking at y=232, a middle one peaking at y=16 and a
    // small one. The first used to win on range width, and a bot sent to y=232 to mine iron is not mining.
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 20)
    public static void ironIsMinedAtOrBelowSeaLevel(GameTestHelper helper) {
        AcquisitionIndex index = index(helper);
        for (String block : List.of("minecraft:iron_ore", "minecraft:deepslate_iron_ore")) {
            Source.MineBlock source = mine(helper, index, "minecraft:raw_iron", block, false);
            helper.assertTrue(source.ore() != null, block + " has no ore range");
            helper.assertTrue(source.ore().bestY() <= OVERWORLD_SEA_LEVEL,
                block + " best Y is " + source.ore().bestY() + ", above the sea level " + OVERWORLD_SEA_LEVEL);
            // The best Y ignores the mountain feature; the range must not (it spans y=80..384, clamped to the build limit).
            helper.assertTrue(source.ore().maxY() >= 232, block + " range tops out at " + source.ore().maxY()
                + ", the mountain feature is missing from it");
        }
        helper.succeed();
    }

    // gameplay/fishing.json picks one of junk (weight 10), treasure (5, only in open water: a condition the index cannot
    // judge) and fish (85). Cod is 60 of the fish table's 100. Away from open water the pool is 95 wide, so cod is
    // 85/95 * 60/100 = 0.5368 per cast; in open water it is 85/100 * 60/100 = 0.51. The index reports the larger, the
    // reading in which the bot can fish it. Likewise string: 10/95 * 5/100 away from open water and outside a jungle
    // (the junk table's bamboo entry needs one), 10/100 * 5/110 with both.
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 20)
    public static void fishingChanceIsTheMorePermissiveReading(GameTestHelper helper) {
        AcquisitionIndex index = index(helper);
        double cod = fishingYield(helper, index, "minecraft:cod");
        helper.assertTrue(Math.abs(cod - 51.0 / 95.0) < 1e-9, "cod per cast is " + cod + ", expected " + 51.0 / 95.0);
        double string = fishingYield(helper, index, "minecraft:string");
        helper.assertTrue(Math.abs(string - 50.0 / 9500.0) < 1e-9, "string per cast is " + string + ", expected " + 50.0 / 9500.0);
        helper.succeed();
    }

    private static double fishingYield(GameTestHelper helper, AcquisitionIndex index, String item) {
        Optional<Source.Fishing> fishing = find(index, item, Source.Fishing.class, f -> true);
        if (fishing.isEmpty()) helper.fail(item + " has no fishing source: " + index.sources(ItemKey.of(item)));
        return fishing.get().yield();
    }

    // blocks/sweet_berry_bush.json has two pools, one for age 3 (set_count uniform 2..3, mean 2.5) and one for age 2
    // (uniform 1..2, mean 1.5), each set on the pool and not on the entry. A bush has one age, so the pools are
    // alternatives. A bush is harvested when it is ripe, so the state that gives the most is read, age 3, 2.5 (the
    // default state, age 0, gives none, so the old mined rule read the same figure). The pools used to be added,
    // 2.5 + 1.5 = 4.0. Without the pool-level function every pick counted 1, and the figure was 1.0.
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 20)
    public static void sweetBerryPoolsOfDifferentAgesAreAlternatives(GameTestHelper helper) {
        AcquisitionIndex index = index(helper);
        Optional<Source.Harvest> berries = find(index, "minecraft:sweet_berries", Source.Harvest.class,
            h -> h.block().equals("minecraft:sweet_berry_bush"));
        helper.assertTrue(berries.isPresent(), "sweet berries have no harvest source from the bush: "
            + index.sources(ItemKey.of("minecraft:sweet_berries")));
        helper.assertTrue(Math.abs(berries.get().yield() - 2.5) < 1e-9, "berries per bush are " + berries.get().yield() + ", expected 2.5");
        helper.succeed();
    }

    // blocks/tall_grass.json has a pool for the lower half and one for the upper half (each also asks a location check
    // for its other half, which the index cannot judge and reads as holding). With shears each pool is short_grass x2,
    // and a block is one half (the default state is the lower), so it is 2.0, not the 2 + 2 = 4.0 of both pools. Without shears each pool
    // gives wheat seeds with a 0.125 chance, so 0.125 and not 0.25.
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 20)
    public static void tallGrassHalvesAreAlternatives(GameTestHelper helper) {
        AcquisitionIndex index = index(helper);
        Optional<Source.MineBlock> grass = find(index, "minecraft:short_grass", Source.MineBlock.class,
            m -> m.block().equals("minecraft:tall_grass") && m.needsShears());
        helper.assertTrue(grass.isPresent(), "short grass has no shears source from tall grass: " + index.sources(ItemKey.of("minecraft:short_grass")));
        helper.assertTrue(Math.abs(grass.get().yield() - 2.0) < 1e-9, "short grass per tall grass is " + grass.get().yield() + ", expected 2.0");
        Optional<Source.MineBlock> seeds = find(index, "minecraft:wheat_seeds", Source.MineBlock.class,
            m -> m.block().equals("minecraft:tall_grass") && !m.needsShears() && !m.needsSilkTouch());
        helper.assertTrue(seeds.isPresent(), "wheat seeds have no plain source from tall grass: " + index.sources(ItemKey.of("minecraft:wheat_seeds")));
        helper.assertTrue(Math.abs(seeds.get().yield() - 0.125) < 1e-9, "seeds per tall grass are " + seeds.get().yield() + ", expected 0.125");
        helper.succeed();
    }

    // The state a yield is read in depends on the kind of source. A block that is mined keeps the state it was placed
    // in: the planner prices a source by the figure, and a high one makes a plan fall short (it counts on four candles
    // from a block that was placed with one) where a low one only collects a little too much, so the default state is
    // read whenever it yields the item at all, and only an item it gives none of is read in the state that gives the
    // most. A harvested growable is picked when it is grown, so its yield is the ripe state's, whatever an unripe plant
    // would give.
    // Mined, default state first: oak_slab.json set_count 2 only when type is double: 1. candle.json set_count 2, 3, 4
    // for that many candles: 1 with one. sea_pickle.json the same for 2, 3, 4 pickles: 1. pink_petals.json set_count
    // 1..4 for flower_amount 1..4: 1. snow.json set_count 1..8 snowballs for that many layers, 1 at one layer, and its
    // plain branch never gives the snow block, which only the silk-touch branch gives for layers 8. Mined, the default
    // state gives none so the best state is read: glow_lichen.json with shears starts the stack at 1, adds 1 for each
    // of the six faces that has lichen and then adds -1, so the default state, which has no face, gives 0 and a lichen
    // on every face gives 1 + 6 - 1 = 6.
    // Harvested, grown: cocoa.json set_count 3 at age 2: 3. nether_wart.json set_count uniform 2..4 at age 3: mean 3.
    // carrots.json pool 1 gives 1 carrot in every state and pool 2 another at age 7: 2 (the unripe plant's 1 used to be
    // read). potatoes.json is carrots.json with the name swapped: 2. beetroot.json gives the beetroot only in the age-3
    // branch of its alternatives (the seed otherwise): 1. wheat.json gives the wheat only at age 7: 1.
    // Each figure is collected and the test fails once with every one that is off, so a run shows them all.
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 20)
    public static void aMinedYieldIsReadAsPlacedAndAHarvestWhenGrown(GameTestHelper helper) {
        AcquisitionIndex index = index(helper);
        List<String> off = new ArrayList<>();
        check(off, "oak slab per oak slab", sourceYield(index, "minecraft:oak_slab", Source.MineBlock.class, m -> m.block().equals("minecraft:oak_slab")), 1.0);
        check(off, "candles per candle block", sourceYield(index, "minecraft:candle", Source.MineBlock.class, m -> m.block().equals("minecraft:candle")), 1.0);
        check(off, "sea pickles per sea pickle block", sourceYield(index, "minecraft:sea_pickle", Source.MineBlock.class, m -> m.block().equals("minecraft:sea_pickle")), 1.0);
        check(off, "pink petals per pink petals block", sourceYield(index, "minecraft:pink_petals", Source.MineBlock.class, m -> m.block().equals("minecraft:pink_petals")), 1.0);
        check(off, "snowballs per snow block", sourceYield(index, "minecraft:snowball", Source.MineBlock.class, m -> m.block().equals("minecraft:snow") && !m.needsSilkTouch()), 1.0);
        check(off, "glow lichen per block, with shears", sourceYield(index, "minecraft:glow_lichen", Source.MineBlock.class,
            m -> m.block().equals("minecraft:glow_lichen") && m.needsShears()), 6.0);
        check(off, "cocoa beans per pod", sourceYield(index, "minecraft:cocoa_beans", Source.Harvest.class, h -> h.block().equals("minecraft:cocoa")), 3.0);
        check(off, "nether wart per plant", sourceYield(index, "minecraft:nether_wart", Source.Harvest.class, h -> h.block().equals("minecraft:nether_wart")), 3.0);
        check(off, "carrots per carrot crop", sourceYield(index, "minecraft:carrot", Source.Harvest.class, h -> h.block().equals("minecraft:carrots")), 2.0);
        check(off, "potatoes per potato crop", sourceYield(index, "minecraft:potato", Source.Harvest.class, h -> h.block().equals("minecraft:potatoes")), 2.0);
        check(off, "beetroot per beetroot crop", sourceYield(index, "minecraft:beetroot", Source.Harvest.class, h -> h.block().equals("minecraft:beetroots")), 1.0);
        check(off, "wheat per wheat crop", sourceYield(index, "minecraft:wheat", Source.Harvest.class, h -> h.block().equals("minecraft:wheat")), 1.0);
        if (find(index, "minecraft:snow_block", Source.MineBlock.class, m -> m.block().equals("minecraft:snow") && !m.needsSilkTouch()).isPresent()) {
            off.add("a plain tool gets the snow block from snow");
        }
        helper.assertTrue(off.isEmpty(), "figures that are off: " + off);
        helper.succeed();
    }

    private static <T extends Source> double sourceYield(AcquisitionIndex index, String item, Class<T> kind, Predicate<T> match) {
        return find(index, item, kind, match).map(Source::yield).orElse(Double.NaN);
    }

    private static void check(List<String> off, String what, double got, double expected) {
        if (!(Math.abs(got - expected) < 1e-9)) off.add(what + " " + got + " (expected " + expected + ")");
    }

    // An offer whose result cannot be read, in a listing placed ahead of the real ones of a farmer's first level. The
    // rest of that level, and every profession after it, must still be sampled; the failure is named in the notes.
    private static final class PoisonListing implements VillagerTrades.ItemListing {
        @Override
        public MerchantOffer getOffer(Entity trader, RandomSource random) {
            return new MerchantOffer(new ItemCost(Items.EMERALD, 1), new ItemStack(Items.BREAD), 1, 1, 0.05F) {
                @Override
                public ItemStack getResult() {
                    throw new IllegalStateException("poisoned offer");
                }
            };
        }
    }

    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 20)
    public static void aTradeListingThatThrowsCostsOnlyItself(GameTestHelper helper) {
        Int2ObjectMap<VillagerTrades.ItemListing[]> farmer = VillagerTrades.TRADES.get(VillagerProfession.FARMER);
        VillagerTrades.ItemListing[] original = farmer.get(1);
        VillagerTrades.ItemListing[] poisoned = new VillagerTrades.ItemListing[original.length + 1];
        poisoned[0] = new PoisonListing();
        System.arraycopy(original, 0, poisoned, 1, original.length);
        AcquisitionIndex built;
        try {
            farmer.put(1, poisoned);
            built = AcquisitionIndexBuilder.build(helper.getLevel().getServer());
        } finally {
            farmer.put(1, original);
        }
        // Farmer level 1 also sells bread for emeralds (ItemsForEmeralds(BREAD, 1, 6, 16, 1)), after the listing that threw.
        helper.assertTrue(find(built, "minecraft:bread", Source.Trade.class,
            t -> t.profession().equals("minecraft:farmer") && t.level() == 1).isPresent(),
            "the farmer's bread trade was lost with the listing that threw: " + built.sources(ItemKey.of("minecraft:bread")));
        // The librarian comes after the farmer in profession order.
        helper.assertTrue(find(built, "minecraft:enchanted_book", Source.Trade.class,
            t -> t.profession().equals("minecraft:librarian") && t.enchantments().containsKey("minecraft:mending")).isPresent(),
            "the librarian's trades were lost with the farmer's listing that threw");
        // The index every other test reads was built without the poisoned listing, so a note that names the farmer only
        // comes from this build having skipped it.
        helper.assertTrue(index(helper).notes().stream().noneMatch(n -> n.contains("minecraft:farmer")),
            "the farmer is named in the notes of a build that skipped nothing: " + index(helper).notes());
        helper.assertTrue(built.notes().stream().anyMatch(n -> n.contains("minecraft:farmer")),
            "the farmer whose listing threw is not named in the notes: " + built.notes());
        helper.succeed();
    }

    // What a kill source says, against the table's own numbers. A count at or below zero gives nothing, and looting is
    // level 0, so enchanted_count_increase adds nothing.
    // entities/zombie.json, pool 1: rotten_flesh with set_count uniform 0..2, each of 0, 1, 2 equally likely: mean
    // (0 + 1 + 2) / 3 = 1.0, and 2/3 of kills give any. Pool 2: only when a player kills it, with a 0.025 chance, one
    // entry of three of equal weight (iron_ingot, carrot, potato): each is 0.025 / 3 per kill, and the pool never opens
    // without the player kill, so the source says the drop needs one.
    // entities/spider.json, pool 2: spider_eye with set_count uniform -1..1, only for a player kill: the values -1 and
    // 0 give nothing, 1 gives one, so the mean is 1/3.
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 20)
    public static void killSourcesCarryTheTablesOwnNumbers(GameTestHelper helper) {
        AcquisitionIndex index = index(helper);
        Source.KillMob flesh = kill(helper, index, "minecraft:rotten_flesh", "minecraft:zombie");
        helper.assertTrue(Math.abs(flesh.yield() - 1.0) < 1e-9, "rotten flesh per zombie is " + flesh.yield() + ", expected 1.0");
        helper.assertTrue(flesh.random() && !flesh.playerKillOnly(), "zombie rotten flesh is " + flesh);
        Source.KillMob iron = kill(helper, index, "minecraft:iron_ingot", "minecraft:zombie");
        helper.assertTrue(Math.abs(iron.yield() - 0.025 / 3) < 1e-9, "iron ingots per zombie are " + iron.yield() + ", expected " + 0.025 / 3);
        helper.assertTrue(iron.playerKillOnly(), "zombie iron ingots do not need a player kill: " + iron);
        Source.KillMob eye = kill(helper, index, "minecraft:spider_eye", "minecraft:spider");
        helper.assertTrue(Math.abs(eye.yield() - 1.0 / 3) < 1e-9, "spider eyes per spider are " + eye.yield() + ", expected " + 1.0 / 3);
        helper.assertTrue(eye.playerKillOnly(), "spider eyes do not need a player kill: " + eye);
        helper.succeed();
    }

    // The mob a kill source names must exist and be a mob a player can fight: not a player, an armor stand, a boat or
    // any other entity that is not living. In vanilla data only mobs have loot-table contents, so this guards a version
    // (or a pack) that gives one of those drops of its own and the filter that keeps them out of the index.
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 20)
    public static void killSourcesNameMobsOnly(GameTestHelper helper) {
        AcquisitionIndex index = index(helper);
        Set<String> checked = new HashSet<>();
        for (ItemKey item : index.sourced()) {
            for (Source source : index.sources(item)) {
                if (!(source instanceof Source.KillMob kill) || !checked.add(kill.entity())) continue;
                ResourceLocation id = ResourceLocation.parse(kill.entity());
                helper.assertTrue(BuiltInRegistries.ENTITY_TYPE.containsKey(id), kill.entity() + " is named by a kill source but is not an entity type");
                Entity entity = BuiltInRegistries.ENTITY_TYPE.get(id).create(helper.getLevel());
                helper.assertTrue(entity instanceof Mob, kill.entity() + " is the entity of a kill source but is not a mob");
            }
        }
        helper.assertTrue(checked.size() > 20, "only " + checked.size() + " entity types have kill sources");
        helper.succeed();
    }

    private static Source.KillMob kill(GameTestHelper helper, AcquisitionIndex index, String item, String entity) {
        Optional<Source.KillMob> found = find(index, item, Source.KillMob.class, k -> k.entity().equals(entity));
        if (found.isEmpty()) helper.fail(item + " has no kill source from " + entity + ": " + index.sources(ItemKey.of(item)));
        return found.get();
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

    // What a player gets by using a held tool on something (issue #28), now sources the planner can choose: a lava
    // bucket is an empty bucket scooped into a lava source and a powder snow bucket one scooped over the powder snow
    // block; a mob bucket is a water bucket closed on its fish, not a scoop of the water it holds, so the axolotl
    // bucket's source is the axolotl and never the fluid.
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 20)
    public static void bucketsAreUsedOnTheirTargetAndNotOnTheWaterTheyHold(GameTestHelper helper) {
        AcquisitionIndex index = index(helper);
        helper.assertTrue(hasUse(index, "minecraft:lava_bucket", "minecraft:bucket", "fluid", "minecraft:lava", null),
            "the lava bucket has no use-on source from minecraft:lava");
        helper.assertTrue(hasUse(index, "minecraft:powder_snow_bucket", "minecraft:bucket", "block",
            "minecraft:powder_snow", null), "the powder snow bucket has no use-on source from minecraft:powder_snow");
        helper.assertTrue(hasUse(index, "minecraft:axolotl_bucket", "minecraft:water_bucket", "entity",
            "minecraft:axolotl", null), "the axolotl bucket has no use-on source from minecraft:axolotl");
        helper.assertTrue(!hasAnyUse(index, "minecraft:axolotl_bucket", "fluid", "minecraft:water"),
            "the axolotl bucket is had by scooping water: " + index.sources(ItemKey.of("minecraft:axolotl_bucket")));
        helper.assertTrue(hasUse(index, "minecraft:milk_bucket", "minecraft:bucket", "entity", "minecraft:cow", null),
            "the milk bucket has no use-on source from minecraft:cow");
        helper.assertTrue(hasUse(index, "minecraft:white_wool", "minecraft:shears", "entity", "minecraft:sheep",
            "white"), "the white wool has no use-on source from a white sheep");
        helper.succeed();
    }

    // A pottery sherd comes out of an archaeology table (its way names the table) and a nether star out of the
    // wither's own code, not its loot table.
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 20)
    public static void sherdsComeFromArchaeologyAndCodeDropsFromCode(GameTestHelper helper) {
        AcquisitionIndex index = index(helper);
        Optional<KnownWay> sherd = index.knownWays(ItemKey.of("minecraft:angler_pottery_sherd")).stream()
            .filter(w -> w.way().equals(KnownWay.ARCHAEOLOGY)).findFirst();
        helper.assertTrue(sherd.isPresent(), "the angler pottery sherd has no archaeology way: "
            + index.knownWays(ItemKey.of("minecraft:angler_pottery_sherd")));
        helper.assertTrue(sherd.map(w -> w.id().startsWith("archaeology:")).orElse(false),
            "the sherd's archaeology way is not keyed by its table: " + sherd);
        helper.assertTrue(hasAnyWay(index, "minecraft:nether_star", KnownWay.GAME_CODE),
            "the nether star has no game_code way");
        helper.succeed();
    }

    // What the lint's unobtainable list means: that a player cannot have the item at all. An item a player has a way
    // for that no Source records goes to the no-strategy list instead, a spawn egg stays unobtainable, an item with
    // both a source and a way (leather: cows drop it and piglins barter it) counts with the source, and what a
    // use-on source records (a lava bucket, wool) counts with its sources.
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 20)
    public static void theLintSeparatesNoStrategyFromUnobtainable(GameTestHelper helper) {
        AcquisitionIndex index = index(helper);
        LintReport report = LintReport.of(index, "minecraft");
        for (String id : List.of("minecraft:angler_pottery_sherd", "minecraft:nether_star")) {
            ItemKey item = ItemKey.of(id);
            helper.assertTrue(report.noStrategy().contains(item), id + " is not in the no-strategy list");
            helper.assertTrue(!report.unobtainable().contains(item), id + " is listed unobtainable");
        }
        for (String id : List.of("minecraft:lava_bucket", "minecraft:axolotl_bucket", "minecraft:powder_snow_bucket",
            "minecraft:milk_bucket", "minecraft:white_wool")) {
            ItemKey item = ItemKey.of(id);
            helper.assertTrue(!index.sources(item).isEmpty(), id + " has no source: " + index.knownWays(item));
            helper.assertTrue(!report.noStrategy().contains(item), id + " is in the no-strategy list");
            helper.assertTrue(!report.unobtainable().contains(item), id + " is listed unobtainable");
        }
        ItemKey egg = ItemKey.of("minecraft:zombie_spawn_egg");
        helper.assertTrue(report.unobtainable().contains(egg), "the zombie spawn egg is not unobtainable");
        helper.assertTrue(!report.noStrategy().contains(egg), "the zombie spawn egg is in the no-strategy list");
        // The water bucket holds no mob and no recipe output names it; whichever way the index has it (a source, a
        // way, or both), a player scoops water into one, so it cannot be unobtainable.
        helper.assertTrue(!report.unobtainable().contains(ItemKey.of("minecraft:water_bucket")),
            "the water bucket is unobtainable");
        ItemKey leather = ItemKey.of("minecraft:leather");
        helper.assertTrue(!index.sources(leather).isEmpty(), "leather has no source: " + index.sources(leather));
        helper.assertTrue(hasAnyWay(index, "minecraft:leather", KnownWay.BARTER), "leather has no barter way: "
            + index.knownWays(leather));
        helper.assertTrue(!report.noStrategy().contains(leather), "leather is in the no-strategy list");
        helper.assertTrue(!report.unobtainable().contains(leather), "leather is unobtainable");
        helper.succeed();
    }

    private static boolean hasAnyWay(AcquisitionIndex index, String item, String way) {
        return index.knownWays(ItemKey.of(item)).stream().anyMatch(w -> w.way().equals(way));
    }

    private static boolean hasUse(AcquisitionIndex index, String item, String tool, String targetKind, String target,
                                  @Nullable String variant) {
        return index.sources(ItemKey.of(item)).stream().anyMatch(s -> s instanceof Source.UseOn use
            && use.tool().id().equals(tool) && use.targetKind().equals(targetKind) && use.target().equals(target)
            && java.util.Objects.equals(use.variant(), variant));
    }

    private static boolean hasAnyUse(AcquisitionIndex index, String item, String targetKind, String target) {
        return index.sources(ItemKey.of(item)).stream().anyMatch(s -> s instanceof Source.UseOn use
            && use.targetKind().equals(targetKind) && use.target().equals(target));
    }
}
