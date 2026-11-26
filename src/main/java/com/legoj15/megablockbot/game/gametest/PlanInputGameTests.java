package com.legoj15.megablockbot.game.gametest;

import com.legoj15.megablockbot.MegaBlockBot;
import com.legoj15.megablockbot.core.EquipSlot;
import com.legoj15.megablockbot.core.ItemKey;
import com.legoj15.megablockbot.core.Pos;
import com.legoj15.megablockbot.core.ToolClass;
import com.legoj15.megablockbot.core.ToolTier;
import com.legoj15.megablockbot.core.planner.RecipeBook;
import com.legoj15.megablockbot.core.planner.RecipeDef;
import com.legoj15.megablockbot.core.snapshot.ContainerSight;
import com.legoj15.megablockbot.core.snapshot.Snapshot;
import com.legoj15.megablockbot.core.snapshot.StackFacts;
import com.legoj15.megablockbot.core.snapshot.StationSight;
import com.legoj15.megablockbot.game.body.BodyException;
import com.legoj15.megablockbot.game.body.BodyManager;
import com.legoj15.megablockbot.game.body.BotPlayer;
import com.legoj15.megablockbot.game.plan.BotMemory;
import com.legoj15.megablockbot.game.plan.RecipeBookBuilder;
import com.legoj15.megablockbot.game.plan.RecipeBooks;
import com.legoj15.megablockbot.game.plan.RecipeSource;
import com.legoj15.megablockbot.game.plan.RecipeSources;
import com.legoj15.megablockbot.game.plan.SnapshotCollector;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.EnchantedBookItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.component.Tool;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentInstance;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * The two things the planner is fed from the game, read from the real vanilla data the GameTest server loads. What
 * they protect: the recipe shapes the planner depends on (which recipes need a table, what a slot accepts, what a
 * bucket leaves behind), a Minecraft version that changes how recipes or ingredients are stored, and that a snapshot
 * says what a body really has, wears, can mine with and stands near.
 *
 * <p>Snapshot tests spawn a real {@link BotPlayer} and read it in the same tick; nothing waits on the body's own
 * ticking. Every body has a name unique to this run and is despawned, forgotten and its memory dropped when its test
 * ends, because the GameTest world's player data outlives a run.
 */
public final class PlanInputGameTests {
    private static final String RUN_ID = Integer.toString((int) (System.nanoTime() & 0xFFFFF), 36);

    private PlanInputGameTests() {}

    // ---------------------------------------------------------------------------------------------------------
    // The recipe book
    // ---------------------------------------------------------------------------------------------------------

    private static RecipeDef recipe(GameTestHelper helper, String id) {
        Optional<RecipeDef> found = RecipeBooks.current().byId(id);
        if (found.isEmpty()) helper.fail("the recipe book has no recipe " + id + " (it holds " + RecipeBooks.current().all().size() + " recipes)");
        return found.get();
    }

    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 20)
    public static void stonePickaxeNeedsATableAndFiveSlots(GameTestHelper helper) {
        RecipeDef pickaxe = recipe(helper, "minecraft:stone_pickaxe");
        helper.assertTrue(RecipeDef.CRAFT.equals(pickaxe.kind()), "kind is " + pickaxe.kind());
        helper.assertTrue(ItemKey.of("minecraft:crafting_table").equals(pickaxe.station()), "station is " + pickaxe.station());
        helper.assertTrue(ItemKey.of("minecraft:stone_pickaxe").equals(pickaxe.result()) && pickaxe.resultCount() == 1,
            "makes " + pickaxe.resultCount() + " of " + pickaxe.result());
        helper.assertTrue(pickaxe.slots().size() == 5, "has " + pickaxe.slots().size() + " slots: " + pickaxe.slots());

        int stone = 0;
        int sticks = 0;
        for (RecipeDef.Slot slot : pickaxe.slots()) {
            helper.assertTrue(slot.count() == 1, "a slot consumes " + slot.count());
            if (slot.alternatives().contains(ItemKey.of("minecraft:cobblestone"))) {
                stone++;
                for (String material : List.of("minecraft:blackstone", "minecraft:cobbled_deepslate")) {
                    helper.assertTrue(slot.alternatives().contains(ItemKey.of(material)), "a head slot does not accept " + material + ": " + slot.alternatives());
                }
            } else if (slot.alternatives().equals(List.of(ItemKey.of("minecraft:stick")))) {
                sticks++;
            }
        }
        helper.assertTrue(stone == 3, stone + " slots accept cobblestone, expected 3");
        helper.assertTrue(sticks == 2, sticks + " slots are one stick, expected 2");
        helper.succeed();
    }

    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 20)
    public static void planksAndATableNeedNoStation(GameTestHelper helper) {
        RecipeDef planks = recipe(helper, "minecraft:oak_planks");
        helper.assertTrue(planks.station() == null, "oak planks name the station " + planks.station());
        helper.assertTrue(ItemKey.of("minecraft:oak_planks").equals(planks.result()) && planks.resultCount() == 4,
            "oak planks make " + planks.resultCount() + " of " + planks.result());
        helper.assertTrue(planks.slots().size() == 1 && planks.slots().get(0).alternatives().contains(ItemKey.of("minecraft:oak_log")),
            "the oak planks slots are " + planks.slots());

        RecipeDef table = recipe(helper, "minecraft:crafting_table");
        helper.assertTrue(table.station() == null, "the crafting table names the station " + table.station());
        helper.assertTrue(ItemKey.of("minecraft:crafting_table").equals(table.result()), "the table recipe makes " + table.result());
        helper.succeed();
    }

    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 20)
    public static void aMilkBucketLeavesABucket(GameTestHelper helper) {
        ItemKey left = RecipeBooks.current().remainder(ItemKey.of("minecraft:milk_bucket"));
        helper.assertTrue(ItemKey.of("minecraft:bucket").equals(left), "a milk bucket leaves " + left);
        helper.succeed();
    }

    /**
     * Every recipe in the book is one the game has, with one slot per ingredient the game reports (a blank cell of a
     * shaped grid is not one), and everything it names exists. The slot count is what a source can get wrong without
     * {@code RecipeDef}'s own checks noticing: a lost or an invented ingredient still leaves a valid recipe.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 40)
    public static void everyRecipeMatchesTheGameAndNamesRealItems(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        // One read: the stations must be the ones this very book names, whatever rebuilds happen around the test.
        RecipeBooks.Published published = RecipeBooks.published();
        RecipeBook book = published.book();
        helper.assertTrue(!book.all().isEmpty(), "the recipe book is empty");
        Set<ItemKey> namedStations = new TreeSet<>();
        for (RecipeDef recipe : book.all()) {
            if (recipe.station() != null) namedStations.add(recipe.station());
            if (RecipeDef.CRAFT.equals(recipe.kind())) {
                Optional<RecipeHolder<?>> inGame = server.getRecipeManager().byKey(ResourceLocation.parse(recipe.id()));
                helper.assertTrue(inGame.isPresent(), recipe.id() + " is not a recipe in the game");
                long ingredients = inGame.get().value().getIngredients().stream().filter(ingredient -> !ingredient.isEmpty()).count();
                helper.assertTrue(recipe.slots().size() == ingredients,
                    recipe.id() + " has " + recipe.slots().size() + " slots, the game's recipe has " + ingredients + " ingredients");
            }
            for (RecipeDef.Slot slot : recipe.slots()) {
                for (ItemKey item : slot.alternatives()) {
                    helper.assertTrue(itemExists(item), recipe.id() + " has a slot that accepts " + item + ", which is not an item");
                }
            }
            helper.assertTrue(itemExists(recipe.result()), recipe.id() + " makes " + recipe.result() + ", which is not an item");
            if (recipe.station() != null) {
                helper.assertTrue(itemExists(recipe.station()), recipe.id() + " names the station " + recipe.station() + ", which is not an item");
            }
        }
        helper.assertTrue(published.stations().equals(namedStations),
            "the published stations are " + published.stations() + ", the book's recipes name " + namedStations);
        helper.assertTrue(published.stations().contains(ItemKey.of("minecraft:crafting_table")),
            "the stations the book names are " + published.stations());
        for (ItemKey station : published.stations()) {
            helper.assertTrue(itemExists(station), "the book names the station " + station + ", which is not an item");
        }
        helper.succeed();
    }

    private static boolean itemExists(ItemKey key) {
        ResourceLocation id = ResourceLocation.tryParse(key.id());
        return id != null && BuiltInRegistries.ITEM.containsKey(id) && BuiltInRegistries.ITEM.get(id) != Items.AIR;
    }

    /**
     * A reload must run the build again, not keep the old book: a datapack that changes a recipe is only seen this way.
     * It runs the same reload {@code /reload} does and waits for the swap. A source registered just before the reload
     * is how the test knows the build ran after it: no earlier build has read that source, so the remainder it reports
     * (numbered by how often it was asked) can only be in a book that a later build made.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 1200)
    public static void bookIsRebuiltAfterReload(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        RecipeBook before = RecipeBooks.current();
        helper.assertTrue(!before.all().isEmpty(), "the recipe book was empty before the reload");
        int recipes = before.all().size();

        ItemKey probed = ItemKey.of("megablockbot_test:probe_" + RUN_ID);
        AtomicInteger builds = new AtomicInteger();
        AtomicBoolean live = new AtomicBoolean(true);
        // There is no way to unregister a source; switched off, it adds nothing to a later build in this JVM.
        RecipeSources.register(new FakeSource("reload_probe_" + RUN_ID, out -> {
            if (live.get()) out.remainder(probed, ItemKey.of("megablockbot_test:build_" + builds.incrementAndGet()));
        }));

        Throwable[] failure = {null};
        server.reloadResources(server.getPackRepository().getSelectedIds()).exceptionally(e -> {
            failure[0] = e;
            return null;
        });
        helper.onEachTick(() -> {
            if (failure[0] != null) {
                live.set(false);
                helper.fail("the reload failed: " + failure[0]);
            }
            RecipeBook now = RecipeBooks.current();
            if (now == before) return;
            live.set(false);
            helper.assertTrue(builds.get() >= 1, "a new book was published but the probe source was never read");
            ItemKey expected = ItemKey.of("megablockbot_test:build_" + builds.get());
            helper.assertTrue(expected.equals(now.remainder(probed)),
                "the book after the reload reports " + now.remainder(probed) + " for the probe, expected " + expected + " from the last build");
            helper.assertTrue(now.all().size() == recipes, "the rebuilt book has " + now.all().size() + " recipes, the old one " + recipes);
            helper.assertTrue(now.byId("minecraft:stone_pickaxe").isPresent(), "the rebuilt book lost the stone pickaxe");
            helper.succeed();
        });
    }

    // ---------------------------------------------------------------------------------------------------------
    // The build itself: one broken source must not cost the others
    // ---------------------------------------------------------------------------------------------------------

    private record FakeSource(String id, Consumer<RecipeSource.Collector> body) implements RecipeSource {
        @Override
        public void collect(MinecraftServer server, Collector out) {
            body.accept(out);
        }
    }

    private static RecipeDef fake(String id, String result) {
        return new RecipeDef(id, RecipeDef.CRAFT, List.of(RecipeDef.Slot.of(ItemKey.of("minecraft:stick"))), null, ItemKey.of(result), 1);
    }

    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 20)
    public static void aBrokenSourceAndADuplicateIdDoNotStopTheBuild(GameTestHelper helper) {
        List<RecipeSource> sources = List.of(
            new FakeSource("first", out -> {
                out.recipe(fake("test:r1", "minecraft:apple"));
                out.recipe(fake("test:r2", "minecraft:apple"));
                out.remainder(ItemKey.of("minecraft:honey_bottle"), ItemKey.of("minecraft:glass_bottle"));
            }),
            // Throws after producing a recipe: the half it made must not leak into the book.
            new FakeSource("broken", out -> {
                out.recipe(fake("test:r3", "minecraft:apple"));
                throw new IllegalStateException("broken on purpose, by the test");
            }),
            new FakeSource("later", out -> {
                out.recipe(fake("test:r1", "minecraft:carrot"));
                out.recipe(fake("test:r4", "minecraft:carrot"));
            }));

        RecipeBookBuilder.Built built = RecipeBookBuilder.build(helper.getLevel().getServer(), sources);
        List<String> ids = built.book().all().stream().map(RecipeDef::id).toList();
        helper.assertTrue(ids.equals(List.of("test:r1", "test:r2", "test:r4")), "the book holds " + ids);
        helper.assertTrue(built.book().byId("test:r1").get().result().equals(ItemKey.of("minecraft:apple")),
            "the duplicate id did not keep the earlier recipe: test:r1 makes " + built.book().byId("test:r1").get().result());
        helper.assertTrue(built.recipesBySource().equals(Map.of("first", 2, "broken", 0, "later", 1)),
            "recipes by source are " + built.recipesBySource());
        helper.assertTrue(ItemKey.of("minecraft:glass_bottle").equals(built.book().remainder(ItemKey.of("minecraft:honey_bottle"))),
            "the remainder of the first source is gone");
        helper.assertTrue(built.notes().stream().anyMatch(n -> n.startsWith("broken: failed")), "no note says the broken source failed: " + built.notes());
        helper.assertTrue(built.notes().stream().anyMatch(n -> n.startsWith("later:") && n.contains("same id")),
            "no note says a recipe was dropped for its id: " + built.notes());
        helper.succeed();
    }

    // ---------------------------------------------------------------------------------------------------------
    // The snapshot
    // ---------------------------------------------------------------------------------------------------------

    private static BotPlayer spawn(GameTestHelper helper, String tag) {
        MinecraftServer server = helper.getLevel().getServer();
        try {
            return BodyManager.of(server).spawn("pi_" + tag + "_" + RUN_ID, null, helper.getLevel(),
                helper.absoluteVec(new Vec3(8.5, 2.0, 8.5)), 0.0F, null);
        } catch (BodyException e) {
            throw new IllegalStateException("could not spawn the body of " + tag + ": " + e.getMessage(), e);
        }
    }

    private static void despawn(GameTestHelper helper, BotPlayer body) {
        BodyManager manager = BodyManager.of(helper.getLevel().getServer());
        UUID id = body.getUUID();
        manager.despawn(body);
        try {
            manager.deleteSaved(id);
        } catch (BodyException ignored) {
            // Still logged in only if the despawn did not take; the unique name keeps it from mattering.
        }
        BotMemory.forget(id);
    }

    private static String dimension(GameTestHelper helper) {
        return helper.getLevel().dimension().location().toString();
    }

    private static ItemStack bookOf(ServerLevel level, ResourceKey<Enchantment> enchantment, int levelOf) {
        Holder<Enchantment> holder = level.registryAccess().registryOrThrow(Registries.ENCHANTMENT).getHolderOrThrow(enchantment);
        return EnchantedBookItem.createForEnchantment(new EnchantmentInstance(holder, levelOf));
    }

    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 40)
    public static void snapshotSaysWhatTheBodyHas(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BotPlayer body = spawn(helper, "snap");
        try {
            body.getInventory().setItem(0, new ItemStack(Items.COBBLESTONE, 3));
            body.getInventory().setItem(1, new ItemStack(Items.IRON_PICKAXE));
            body.getInventory().setItem(2, bookOf(level, Enchantments.MENDING, 1));
            body.getInventory().setItem(3, PotionContents.createItemStack(Items.POTION, Potions.HEALING));
            body.getInventory().setItem(4, new ItemStack(Items.BREAD, 2));
            body.getInventory().setItem(5, new ItemStack(Items.COBBLESTONE, 7)); // a second stack of one item
            body.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
            body.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.TORCH, 4));

            // The body stands at relative (8,2,8). A table it placed two blocks away; a furnace it did not place; a
            // table nine blocks away, which is out of reach; a chest it remembers, one it remembers where there is none
            // now, and one far away.
            String dimension = dimension(helper);
            BlockPos placedTable = helper.absolutePos(new BlockPos(10, 2, 8));
            BlockPos strangerFurnace = helper.absolutePos(new BlockPos(6, 2, 9));
            BlockPos farTable = helper.absolutePos(new BlockPos(15, 2, 15));
            helper.setBlock(new BlockPos(10, 2, 8), Blocks.CRAFTING_TABLE);
            helper.setBlock(new BlockPos(6, 2, 9), Blocks.FURNACE);
            helper.setBlock(new BlockPos(15, 2, 15), Blocks.CRAFTING_TABLE);
            BlockPos nearChest = helper.absolutePos(new BlockPos(8, 2, 10));
            BlockPos vanishedChest = helper.absolutePos(new BlockPos(8, 2, 6));
            BlockPos farChest = helper.absolutePos(new BlockPos(0, 2, 0));
            helper.setBlock(new BlockPos(8, 2, 10), Blocks.CHEST);
            helper.setBlock(new BlockPos(0, 2, 0), Blocks.CHEST);

            BotMemory memory = BotMemory.of(body.getUUID());
            memory.notePlaced(dimension, placedTable);
            memory.noteContainer(dimension, nearChest, "minecraft:chest", Map.of(ItemKey.of("minecraft:iron_ingot"), 5));
            memory.noteContainer(dimension, vanishedChest, "minecraft:chest", Map.of(ItemKey.of("minecraft:gold_ingot"), 1));
            memory.noteContainer(dimension, farChest, "minecraft:chest", Map.of(ItemKey.of("minecraft:diamond"), 9));

            Snapshot snapshot = SnapshotCollector.collect(body);

            helper.assertTrue(snapshot.dimension().equals(dimension), "dimension is " + snapshot.dimension());
            helper.assertTrue(snapshot.gameTime() == level.getGameTime(), "game time is " + snapshot.gameTime());
            helper.assertTrue(snapshot.pos().equals(new Pos(body.blockPosition().getX(), body.blockPosition().getY(), body.blockPosition().getZ())),
                "position is " + snapshot.pos());
            helper.assertTrue(snapshot.health() == body.getHealth() && snapshot.hunger() == body.getFoodData().getFoodLevel(),
                "health " + snapshot.health() + " and hunger " + snapshot.hunger());

            helper.assertTrue(snapshot.count(ItemKey.of("minecraft:cobblestone")) == 10, "cobblestone, 3 and 7 in two stacks: " + snapshot.items());
            helper.assertTrue(snapshot.count(ItemKey.of("minecraft:iron_pickaxe")) == 1, "iron pickaxe: " + snapshot.items());
            helper.assertTrue(snapshot.count(ItemKey.of("minecraft:enchanted_book")) == 1, "enchanted book: " + snapshot.items());
            helper.assertTrue(snapshot.count(ItemKey.of("minecraft:iron_helmet")) == 1, "worn helmet: " + snapshot.items());
            // The offhand is part of the inventory's slots and of what the body wears: once in the count, and in OFFHAND.
            helper.assertTrue(snapshot.count(ItemKey.of("minecraft:torch")) == 4, "torches in the offhand, counted once: " + snapshot.items());
            helper.assertTrue(snapshot.count(ItemKey.of("minecraft:diamond")) == 0, "a diamond out of nowhere: " + snapshot.items());

            helper.assertTrue(snapshot.bestTool(ToolClass.PICKAXE) == ToolTier.IRON, "pickaxe tier is " + snapshot.bestTool(ToolClass.PICKAXE));
            helper.assertTrue(snapshot.bestTool(ToolClass.AXE) == ToolTier.HAND && !snapshot.bestTools().containsKey(ToolClass.AXE),
                "an axe from nowhere: " + snapshot.bestTools());

            helper.assertTrue(ItemKey.of("minecraft:iron_helmet").equals(snapshot.equipped().get(EquipSlot.HEAD)), "head: " + snapshot.equipped());
            helper.assertTrue(ItemKey.of("minecraft:cobblestone").equals(snapshot.equipped().get(EquipSlot.MAINHAND)), "main hand: " + snapshot.equipped());
            helper.assertTrue(ItemKey.of("minecraft:torch").equals(snapshot.equipped().get(EquipSlot.OFFHAND)), "offhand: " + snapshot.equipped());
            helper.assertTrue(!snapshot.equipped().containsKey(EquipSlot.CHEST), "something worn that is not: " + snapshot.equipped());

            var bookFacts = snapshot.stackFacts().get(ItemKey.of("minecraft:enchanted_book"));
            helper.assertTrue(bookFacts != null && bookFacts.size() == 1 && bookFacts.get(0).count() == 1
                && Integer.valueOf(1).equals(bookFacts.get(0).enchantments().get("minecraft:mending")), "book facts: " + bookFacts);
            var potionFacts = snapshot.stackFacts().get(ItemKey.of("minecraft:potion"));
            helper.assertTrue(potionFacts != null && potionFacts.size() == 1 && "minecraft:healing".equals(potionFacts.get(0).potion()),
                "potion facts: " + potionFacts);
            // One entry per stack, not per item: the two cobblestone stacks are two entries.
            var stoneFacts = snapshot.stackFacts().get(ItemKey.of("minecraft:cobblestone"));
            helper.assertTrue(stoneFacts != null && stoneFacts.size() == 2
                && stoneFacts.stream().map(StackFacts::count).sorted().toList().equals(List.of(3, 7))
                && stoneFacts.stream().allMatch(f -> f.enchantments().isEmpty() && f.potion().isEmpty()), "cobblestone facts: " + stoneFacts);
            var torchFacts = snapshot.stackFacts().get(ItemKey.of("minecraft:torch"));
            helper.assertTrue(torchFacts != null && torchFacts.size() == 1 && torchFacts.get(0).count() == 4, "torch facts: " + torchFacts);

            // Bread restores 5 hunger points in 1.21.1; the other items are not food.
            helper.assertTrue(snapshot.foodUnits() == 10, "food units are " + snapshot.foodUnits());

            StationSight table = station(snapshot, "minecraft:crafting_table", placedTable);
            helper.assertTrue(table != null && table.placedByBot(), "the table the body placed: " + snapshot.stations());
            StationSight furnace = station(snapshot, "minecraft:furnace", strangerFurnace);
            helper.assertTrue(furnace != null && !furnace.placedByBot(), "the furnace the body did not place: " + snapshot.stations());
            helper.assertTrue(station(snapshot, "minecraft:crafting_table", farTable) == null, "a table out of reach is listed: " + snapshot.stations());
            helper.assertTrue(snapshot.stations().size() == 2, "stations: " + snapshot.stations());

            ContainerSight near = snapshot.containerAt(new Pos(nearChest.getX(), nearChest.getY(), nearChest.getZ()));
            helper.assertTrue(near != null && near.contentsKnown() && near.count(ItemKey.of("minecraft:iron_ingot")) == 5, "the chest it remembers: " + near);
            ContainerSight vanished = snapshot.containerAt(new Pos(vanishedChest.getX(), vanishedChest.getY(), vanishedChest.getZ()));
            helper.assertTrue(vanished != null && !vanished.contentsKnown() && vanished.contents().isEmpty(), "the chest that is gone: " + vanished);
            ContainerSight far = snapshot.containerAt(new Pos(farChest.getX(), farChest.getY(), farChest.getZ()));
            helper.assertTrue(far != null && !far.contentsKnown() && far.contents().isEmpty(), "the chest out of reach: " + far);
            helper.assertTrue(snapshot.containers().size() == 3, "containers: " + snapshot.containers());
            helper.assertTrue(snapshot.structures().isEmpty() && snapshot.observations().isEmpty(), "structures or observations from nowhere");
            helper.succeed();
        } finally {
            despawn(helper, body);
        }
    }

    private static StationSight station(Snapshot snapshot, String item, BlockPos at) {
        for (StationSight sight : snapshot.stations()) {
            if (sight.station().equals(ItemKey.of(item)) && sight.pos().equals(new Pos(at.getX(), at.getY(), at.getZ()))) return sight;
        }
        return null;
    }

    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 40)
    public static void aGoldenAxeMinesLikeWood(GameTestHelper helper) {
        BotPlayer body = spawn(helper, "gold");
        try {
            body.getInventory().setItem(0, new ItemStack(Items.GOLDEN_AXE));
            Snapshot snapshot = SnapshotCollector.collect(body);
            helper.assertTrue(snapshot.bestTool(ToolClass.AXE) == ToolTier.WOOD, "the golden axe is " + snapshot.bestTool(ToolClass.AXE));
            helper.assertTrue(snapshot.bestTools().size() == 1, "tools from nowhere: " + snapshot.bestTools());
            helper.succeed();
        } finally {
            despawn(helper, body);
        }
    }

    /** Each class from the game's tags, the best tier kept per class, shears as their own class. */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 40)
    public static void everyToolClassAndTierIsRead(GameTestHelper helper) {
        BotPlayer body = spawn(helper, "tools");
        try {
            body.getInventory().setItem(0, new ItemStack(Items.WOODEN_SHOVEL));
            body.getInventory().setItem(1, new ItemStack(Items.DIAMOND_SWORD));
            body.getInventory().setItem(2, new ItemStack(Items.NETHERITE_HOE));
            body.getInventory().setItem(3, new ItemStack(Items.SHEARS));
            body.getInventory().setItem(4, new ItemStack(Items.STONE_PICKAXE));
            body.getInventory().setItem(5, new ItemStack(Items.GOLDEN_PICKAXE)); // worse than the stone one: must not win
            body.getInventory().setItem(6, new ItemStack(Items.DIAMOND_PICKAXE));
            Snapshot snapshot = SnapshotCollector.collect(body);
            Map<ToolClass, ToolTier> expected = Map.of(
                ToolClass.SHOVEL, ToolTier.WOOD, ToolClass.SWORD, ToolTier.DIAMOND, ToolClass.HOE, ToolTier.NETHERITE,
                ToolClass.SHEARS, ToolTier.HAND, ToolClass.PICKAXE, ToolTier.DIAMOND);
            helper.assertTrue(snapshot.bestTools().equals(expected), "best tools are " + snapshot.bestTools() + ", expected " + expected);
            helper.succeed();
        } finally {
            despawn(helper, body);
        }
    }

    /** The best tools a body reads as having when it carries one stick that holds the given tool component. */
    private static Map<ToolClass, ToolTier> bestToolsOfAStickWith(GameTestHelper helper, String tag, Tool tool) {
        BotPlayer body = spawn(helper, tag);
        try {
            ItemStack stick = new ItemStack(Items.STICK);
            stick.set(DataComponents.TOOL, tool);
            body.getInventory().setItem(0, stick);
            return SnapshotCollector.collect(body).bestTools();
        } finally {
            despawn(helper, body);
        }
    }

    private static HolderSet<Block> blocksDeniedBy(GameTestHelper helper, TagKey<Block> tag) {
        List<Holder<Block>> blocks = BuiltInRegistries.BLOCK.getTag(tag).orElseThrow().stream().toList();
        helper.assertTrue(!blocks.isEmpty(), "the tag " + tag.location() + " is empty");
        return HolderSet.direct(blocks);
    }

    private static Tool.Rule denies(HolderSet<Block> blocks) {
        return new Tool.Rule(blocks, Optional.empty(), Optional.of(false));
    }

    /**
     * A modded tool is only known by its data. A stick (nothing in its name or its item says tool) given a tool
     * component that mines pickaxe blocks and denies drops on exactly the blocks an iron pickaxe cannot harvest must
     * read as an iron pickaxe: the denied blocks are a direct list here, so only their contents can say.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 40)
    public static void aToolKnownOnlyByItsDataIsRead(GameTestHelper helper) {
        Map<ToolClass, ToolTier> tools = bestToolsOfAStickWith(helper, "mod", new Tool(List.of(
            denies(blocksDeniedBy(helper, BlockTags.INCORRECT_FOR_IRON_TOOL)),
            Tool.Rule.minesAndDrops(BlockTags.MINEABLE_WITH_PICKAXE, 6.0F)), 1.0F, 1));
        helper.assertTrue(tools.equals(Map.of(ToolClass.PICKAXE, ToolTier.IRON)), "best tools are " + tools);
        helper.succeed();
    }

    /**
     * Every rule that denies drops counts: the tool cannot harvest what any of them names, so its tier is the most
     * restrictive one. The iron rule and the stone rule together are a stone tool, whichever comes first and whether
     * the rules name a tag or a list of blocks (a single vanilla tag is read by its name, two are not).
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 40)
    public static void aToolWithTwoDenyingRulesIsRatedByTheStricterOne(GameTestHelper helper) {
        HolderSet<Block> iron = blocksDeniedBy(helper, BlockTags.INCORRECT_FOR_IRON_TOOL);
        HolderSet<Block> stone = blocksDeniedBy(helper, BlockTags.INCORRECT_FOR_STONE_TOOL);
        Tool.Rule mines = Tool.Rule.minesAndDrops(BlockTags.MINEABLE_WITH_PICKAXE, 6.0F);

        Map<ToolClass, ToolTier> tags = bestToolsOfAStickWith(helper, "dtags", new Tool(List.of(
            denies(BuiltInRegistries.BLOCK.getOrCreateTag(BlockTags.INCORRECT_FOR_IRON_TOOL)),
            denies(BuiltInRegistries.BLOCK.getOrCreateTag(BlockTags.INCORRECT_FOR_STONE_TOOL)), mines), 1.0F, 1));
        helper.assertTrue(tags.equals(Map.of(ToolClass.PICKAXE, ToolTier.STONE)), "an iron tag then a stone tag read as " + tags);

        Map<ToolClass, ToolTier> mixed = bestToolsOfAStickWith(helper, "dmix", new Tool(List.of(
            denies(stone), denies(BuiltInRegistries.BLOCK.getOrCreateTag(BlockTags.INCORRECT_FOR_IRON_TOOL)), mines), 1.0F, 1));
        helper.assertTrue(mixed.equals(Map.of(ToolClass.PICKAXE, ToolTier.STONE)), "a stone list then an iron tag read as " + mixed);

        Map<ToolClass, ToolTier> lists = bestToolsOfAStickWith(helper, "dlists", new Tool(List.of(
            denies(iron), denies(stone), mines), 1.0F, 1));
        helper.assertTrue(lists.equals(Map.of(ToolClass.PICKAXE, ToolTier.STONE)), "an iron list then a stone list read as " + lists);
        helper.succeed();
    }

    // ---------------------------------------------------------------------------------------------------------
    // The body's memory
    // ---------------------------------------------------------------------------------------------------------

    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 20)
    public static void memoryComesBackFromItsSaveForm(GameTestHelper helper) {
        UUID id = UUID.randomUUID();
        try {
            BotMemory memory = BotMemory.of(id);
            BlockPos a = new BlockPos(-30000000, 70, 12);
            BlockPos b = new BlockPos(5, -60, 5);
            memory.notePlaced("minecraft:overworld", a);
            memory.notePlaced("minecraft:the_nether", b);
            memory.notePlaced("minecraft:overworld", b);
            memory.forgetPlaced("minecraft:overworld", b);
            memory.noteContainer("minecraft:overworld", a, "minecraft:barrel", Map.of(ItemKey.of("minecraft:coal"), 64, ItemKey.of("minecraft:stick"), 3));

            CompoundTag saved = memory.save();
            // Something the body noted after that save: the restored memory is the save and nothing else.
            BlockPos strayBlock = new BlockPos(1, 2, 3);
            memory.notePlaced("minecraft:overworld", strayBlock);

            BotMemory restored = BotMemory.restore(id, saved);
            helper.assertTrue(restored != memory && BotMemory.of(id) == restored, "the restored memory is not the one the body has now");
            helper.assertTrue(restored.isPlaced("minecraft:overworld", a) && restored.isPlaced("minecraft:the_nether", b), "placed blocks were lost: " + restored);
            helper.assertTrue(!restored.isPlaced("minecraft:overworld", b) && !restored.isPlaced("minecraft:overworld", strayBlock) && restored.placedCount() == 2,
                "a block that was forgotten, or noted after the save, is in the restored memory: " + restored);
            helper.assertTrue(restored.containers().equals(memory.containers()) && restored.containers().size() == 1,
                "containers differ: " + restored.containers() + " against " + memory.containers());

            // A save that is missing, or damaged, must read as nothing rather than throw, and still replace the memory.
            BotMemory empty = BotMemory.restore(id, new CompoundTag());
            helper.assertTrue(BotMemory.of(id) == empty && empty.placedCount() == 0 && empty.containers().isEmpty(), "an empty save is not empty: " + empty);
            CompoundTag damaged = new CompoundTag();
            damaged.putInt("placed", 5); // should be a compound of long arrays
            ListTag halfAContainer = new ListTag();
            CompoundTag noPosition = new CompoundTag();
            noPosition.putString("dimension", "minecraft:overworld");
            halfAContainer.add(noPosition);
            damaged.put("containers", halfAContainer);
            BotMemory fromDamaged = BotMemory.restore(id, damaged);
            helper.assertTrue(BotMemory.of(id) == fromDamaged && fromDamaged.placedCount() == 0 && fromDamaged.containers().isEmpty(),
                "a damaged save is not read as nothing: " + fromDamaged);
            helper.succeed();
        } finally {
            BotMemory.forget(id);
        }
    }
}
