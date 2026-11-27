package com.legoj15.megablockbot.game.gametest;

import com.legoj15.megablockbot.MegaBlockBot;
import com.legoj15.megablockbot.core.EquipSlot;
import com.legoj15.megablockbot.core.ItemKey;
import com.legoj15.megablockbot.core.exec.ExecutorView;
import com.legoj15.megablockbot.core.exec.GoalResult;
import com.legoj15.megablockbot.core.exec.GoalStatus;
import com.legoj15.megablockbot.core.exec.NodeState;
import com.legoj15.megablockbot.core.exec.ReflexHold;
import com.legoj15.megablockbot.core.goal.Goal;
import com.legoj15.megablockbot.core.plan.Exclusions;
import com.legoj15.megablockbot.core.plan.NodeSpec;
import com.legoj15.megablockbot.core.plan.PlanOutcome;
import com.legoj15.megablockbot.core.plan.PlanRequest;
import com.legoj15.megablockbot.core.plan.Profile;
import com.legoj15.megablockbot.core.planner.CraftLayout;
import com.legoj15.megablockbot.core.planner.PlannerConfig;
import com.legoj15.megablockbot.core.planner.SolverPlanner;
import com.legoj15.megablockbot.core.task.Failure;
import com.legoj15.megablockbot.core.task.Task;
import com.legoj15.megablockbot.core.task.TaskState;
import com.legoj15.megablockbot.core.task.TickResult;
import com.legoj15.megablockbot.game.body.BodyException;
import com.legoj15.megablockbot.game.body.BodyManager;
import com.legoj15.megablockbot.game.body.BotPlayer;
import com.legoj15.megablockbot.game.bot.BotRuntime;
import com.legoj15.megablockbot.game.bot.BotSpeech;
import com.legoj15.megablockbot.game.bot.NbtForm;
import com.legoj15.megablockbot.game.bot.ReflexSource;
import com.legoj15.megablockbot.game.bot.ReflexSources;
import com.legoj15.megablockbot.game.build.Blueprints;
import com.legoj15.megablockbot.game.plan.BotMemory;
import com.legoj15.megablockbot.game.plan.RecipeBooks;
import com.legoj15.megablockbot.game.plan.SnapshotCollector;
import com.legoj15.megablockbot.game.resolver.AcquisitionIndexes;
import com.legoj15.megablockbot.game.safety.BlockSafety;
import com.legoj15.megablockbot.game.safety.ClaimProvider;
import com.legoj15.megablockbot.game.task.TableSites;
import com.legoj15.megablockbot.game.task.TaskRegistry;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.LastSeenMessages;
import net.minecraft.network.protocol.game.ServerboundChatPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

/**
 * The bot runtime against a running server (design 3.4, issues 10 to 12). Body names are at most 16 characters, so a
 * test's tag is at most 7. What these protect: that a goal typed to a
 * body turns into real crafting through the menus a player uses (a recipe that needs a table cannot be made any other
 * way), that the table the body puts down comes up again and the one it found stays, that a typed goal and a chat line
 * mean the same thing and only the owner's chat counts, and that a goal in progress survives a save and a respawn of
 * the body. A craft that skips the menu, a table left behind, a result that names no reason, or a goal that vanishes
 * across a save fails one of them.
 *
 * <p>Each test spawns its own body (a name unique to this run) in the arena, whose floor is the stone layer at y=1:
 * feet stand at y=2.0. Bodies are despawned and forgotten when the test ends, passed or not. Placing items in a body's
 * inventory is test setup, not the bot cheating: the bot's own crafting is what is measured.
 */
public final class BotRuntimeGameTests {
    private static final int FLOOR_Y = 1;
    private static final double FLOOR_TOP = FLOOR_Y + 1.0;
    private static final String RUN_ID = Integer.toString((int) (System.nanoTime() & 0xFFFFF), 36);
    /** A pickaxe goal takes about 200 ticks: planks, table, sticks, then place, craft and break the table. */
    private static final int PICKAXE_TICKS = 700;

    private BotRuntimeGameTests() {}

    // ---------------------------------------------------------------------------------------------------------
    // Crafting
    // ---------------------------------------------------------------------------------------------------------

    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 300)
    public static void fourPlanksFromOneLog(GameTestHelper helper) {
        floor(helper);
        Rig rig = Rig.spawn(helper, "planks", null, 300);
        rig.give(Items.OAK_LOG, 1);
        rig.runtime().submit(new Goal.HaveItem(ItemKey.of("oak_planks"), 4));
        rig.onEachTick(() -> {
            GoalResult result = rig.runtime().lastResult();
            if (result == null) return;
            helper.assertTrue(result.status() == GoalResult.Status.SATISFIED, "the goal ended " + BotSpeech.result(result));
            helper.assertTrue(rig.count(Items.OAK_PLANKS) == 4 && rig.count(Items.OAK_LOG) == 0,
                "expected 4 planks and no log, got " + rig.count(Items.OAK_PLANKS) + " planks and " + rig.count(Items.OAK_LOG) + " logs");
            rig.assertHandsEmpty();
            rig.pass();
        });
    }

    /** The text goes through the command, the language layer and the planner: planks first, then sticks from them. */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 300)
    public static void sticksFromTextThroughTheCommandPath(GameTestHelper helper) {
        floor(helper);
        Rig rig = Rig.spawnOwned(helper, "sticks", 300);
        rig.give(Items.OAK_LOG, 1);
        Feedback feedback = new Feedback();
        helper.assertTrue(run(rig.server(), rig.ownerSource(feedback), "aiplayer goal " + rig.name() + " make 4 stick") == 1,
            "the goal command failed: " + feedback.messages);
        // What the words became is the executor's goal, not the bot's sentence about it.
        helper.assertTrue(isHaveItem(rig.runtime().view().goal(), "minecraft:stick", 4),
            "the command did not become a goal of 4 sticks: " + rig.runtime().view().goal());
        helper.assertTrue(!feedback.messages.isEmpty(), "the bot told the command's source nothing");
        rig.onEachTick(() -> {
            GoalResult result = rig.runtime().lastResult();
            if (result == null) return;
            helper.assertTrue(result.status() == GoalResult.Status.SATISFIED, "the goal ended " + BotSpeech.result(result));
            // One log is four planks, and four sticks cost two of them.
            helper.assertTrue(rig.count(Items.STICK) == 4 && rig.count(Items.OAK_PLANKS) == 2 && rig.count(Items.OAK_LOG) == 0,
                "expected 4 sticks, 2 planks and no log, got " + rig.count(Items.STICK) + ", " + rig.count(Items.OAK_PLANKS) + ", "
                    + rig.count(Items.OAK_LOG));
            helper.assertTrue(isHaveItem(result.goal(), "minecraft:stick", 4), "the result is for " + result.goal());
            rig.pass();
        });
    }

    /**
     * Three logs and nothing else: planks, a table, sticks, then the pickaxe, which only the 3x3 grid can make. The
     * table is placed beside the body, used and broken again, and its item is back in the inventory at the end.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = PICKAXE_TICKS)
    public static void woodenPickaxeFromThreeLogs(GameTestHelper helper) {
        floor(helper);
        Rig rig = Rig.spawn(helper, "pickaxe", null, PICKAXE_TICKS);
        rig.give(Items.OAK_LOG, 3);
        rig.runtime().submit(new Goal.HaveItem(ItemKey.of("wooden_pickaxe"), 1));
        boolean[] sawTable = {false};
        rig.onEachTick(() -> {
            if (!rig.tablesNearBody().isEmpty()) sawTable[0] = true;
            GoalResult result = rig.runtime().lastResult();
            if (result == null) return;
            helper.assertTrue(result.status() == GoalResult.Status.SATISFIED, "the goal ended " + BotSpeech.result(result));
            helper.assertTrue(rig.count(Items.WOODEN_PICKAXE) == 1, "no wooden pickaxe in the inventory");
            helper.assertTrue(rig.firstStack(Items.WOODEN_PICKAXE).getDamageValue() == 0, "the new pickaxe was worn down breaking the table");
            helper.assertTrue(rig.count(Items.CRAFTING_TABLE) == 1, "the crafting table is not back in the inventory: " + rig.count(Items.CRAFTING_TABLE));
            helper.assertTrue(sawTable[0], "no crafting table ever stood beside the body, so the pickaxe was not made at one");
            helper.assertTrue(rig.tablesInArena().isEmpty(), "a crafting table was left standing at " + rig.tablesInArena());
            helper.assertTrue(BotMemory.of(rig.id()).placedCount() == 0, "the body still remembers a block it placed");
            rig.assertHandsEmpty();
            rig.pass();
        });
    }

    /**
     * The table's item hops away from the block it came from and can come to rest out of the body's pickup reach. The test
     * moves it three blocks off the moment it appears, which is where it would sit on a bad hop: the body must walk over
     * and take it, by its input, and the goal must still end with the table back in the inventory.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = PICKAXE_TICKS)
    public static void aTableDropOutOfReachIsWalkedOver(GameTestHelper helper) {
        floor(helper);
        Rig rig = Rig.spawn(helper, "fetch", null, PICKAXE_TICKS);
        rig.give(Items.OAK_LOG, 3);
        rig.runtime().submit(new Goal.HaveItem(ItemKey.of("wooden_pickaxe"), 1));
        boolean[] moved = {false};
        rig.onEachTick(() -> {
            BotPlayer body = rig.body();
            if (!moved[0]) {
                for (ItemEntity drop : helper.getLevel().getEntitiesOfClass(ItemEntity.class, body.getBoundingBox().inflate(3.0),
                    candidate -> candidate.getItem().is(Items.CRAFTING_TABLE))) {
                    drop.setPos(body.getX() + 3.0, body.getY() + 0.3, body.getZ());
                    drop.setDeltaMovement(Vec3.ZERO);
                    moved[0] = true;
                }
            }
            GoalResult result = rig.runtime().lastResult();
            if (result == null) return;
            helper.assertTrue(moved[0], "the table's drop never appeared, so nothing was tested");
            helper.assertTrue(result.status() == GoalResult.Status.SATISFIED, "the goal ended " + BotSpeech.result(result));
            helper.assertTrue(rig.count(Items.WOODEN_PICKAXE) == 1, "no wooden pickaxe in the inventory");
            helper.assertTrue(rig.count(Items.CRAFTING_TABLE) == 1, "the table's item was not fetched: " + rig.count(Items.CRAFTING_TABLE));
            helper.assertTrue(rig.tablesInArena().isEmpty(), "a crafting table was left standing at " + rig.tablesInArena());
            rig.assertHandsEmpty();
            rig.pass();
        });
    }

    /** A table that stands beside the body is used where it is and left standing; the bot placed none of its own. */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = PICKAXE_TICKS)
    public static void usesTheTableThatStandsBeside(GameTestHelper helper) {
        floor(helper);
        BlockPos standing = new BlockPos(9, FLOOR_Y + 1, 8);
        helper.setBlock(standing, Blocks.CRAFTING_TABLE);
        Rig rig = Rig.spawn(helper, "stand", null, PICKAXE_TICKS);
        rig.give(Items.OAK_LOG, 3);
        rig.runtime().submit(new Goal.HaveItem(ItemKey.of("wooden_pickaxe"), 1));
        boolean[] extraTable = {false};
        rig.onEachTick(() -> {
            if (rig.tablesNearBody().size() > 1) extraTable[0] = true;
            GoalResult result = rig.runtime().lastResult();
            if (result == null) return;
            helper.assertTrue(result.status() == GoalResult.Status.SATISFIED, "the goal ended " + BotSpeech.result(result));
            helper.assertTrue(rig.count(Items.WOODEN_PICKAXE) == 1, "no wooden pickaxe in the inventory");
            helper.assertTrue(helper.getBlockState(standing).is(Blocks.CRAFTING_TABLE), "the table that stood there was taken away");
            helper.assertTrue(rig.tablesInArena().size() == 1, "expected only the table that stood there, found " + rig.tablesInArena());
            helper.assertTrue(!extraTable[0], "the body placed a table of its own although one stood beside it");
            rig.assertHandsEmpty();
            rig.pass();
        });
    }

    /** What the planner chose for each slot is what goes in the grid; without a layout, the first alternative held. */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 200)
    public static void craftFollowsTheLayoutItIsGiven(GameTestHelper helper) {
        floor(helper);
        Rig rig = Rig.spawn(helper, "layout", null, 200);
        rig.give(Items.COAL, 2);
        rig.give(Items.CHARCOAL, 2);
        rig.give(Items.STICK, 4);
        ItemKey torch = ItemKey.of("torch");
        // Torches take a coal or a charcoal over a stick. The tag lists coal first, so only a layout puts charcoal in.
        Map<String, String> withLayout = Map.of("recipe", "minecraft:torch", "times", "1", "station", "",
            "layout", "1*minecraft:charcoal,minecraft:stick");
        Map<String, String> withoutLayout = Map.of("recipe", "minecraft:torch", "times", "1", "station", "");
        Task<BotPlayer> first = TaskRegistry.create(NodeSpec.leaf(new Goal.HaveItem(torch, 4), "craft", withLayout, ""), Map.of());
        Task<BotPlayer> second = TaskRegistry.create(NodeSpec.leaf(new Goal.HaveItem(torch, 8), "craft", withoutLayout, ""), Map.of());
        AtomicReference<Task<BotPlayer>> current = new AtomicReference<>(first);
        int[] stage = {0};
        rig.onEachTick(() -> {
            BotPlayer body = rig.body();
            Task<BotPlayer> task = current.get();
            if (task.state() == TaskState.PENDING) task.start(body);
            TickResult result = task.tick(body);
            if (result instanceof TickResult.Failed failed) {
                helper.fail("the craft failed: " + failed.failure());
                return;
            }
            if (!(result instanceof TickResult.Completed)) return;
            if (stage[0] == 0) {
                helper.assertTrue(rig.count(Items.CHARCOAL) == 1 && rig.count(Items.COAL) == 2 && rig.count(Items.TORCH) == 4,
                    "the layout asked for charcoal: coal " + rig.count(Items.COAL) + ", charcoal " + rig.count(Items.CHARCOAL)
                        + ", torches " + rig.count(Items.TORCH));
                stage[0] = 1;
                current.set(second);
                return;
            }
            helper.assertTrue(rig.count(Items.COAL) == 1 && rig.count(Items.CHARCOAL) == 1 && rig.count(Items.TORCH) == 8,
                "with no layout the first alternative held is coal: coal " + rig.count(Items.COAL) + ", charcoal "
                    + rig.count(Items.CHARCOAL) + ", torches " + rig.count(Items.TORCH));
            rig.assertHandsEmpty();
            rig.pass();
        });
    }

    // ---------------------------------------------------------------------------------------------------------
    // Where the table goes, and what a craft never loses (issue 27)
    // ---------------------------------------------------------------------------------------------------------

    /** The wooden pickaxe's materials and a table to put down: the plan is the one craft that needs the 3x3 grid. */
    private static void giveThePickaxeMaterials(Rig rig) {
        rig.give(Items.OAK_PLANKS, 3);
        rig.give(Items.STICK, 2);
        rig.give(Items.CRAFTING_TABLE, 1);
    }

    private static Task<BotPlayer> craftTask(String recipe, int times, String station, Goal goal) {
        return TaskRegistry.create(NodeSpec.leaf(goal, "craft", Map.of("recipe", recipe, "times", Integer.toString(times), "station", station), ""),
            Map.of());
    }

    private static Task<BotPlayer> pickaxeTask() {
        return craftTask("minecraft:wooden_pickaxe", 1, "minecraft:crafting_table", new Goal.HaveItem(ItemKey.of("wooden_pickaxe"), 1));
    }

    /**
     * The body stands on a pillar five blocks high with air all round. The pillar's own sides are faces it could click,
     * but every spot beside the pillar has air below it, and a table put there would drop its item into the gap when it is
     * broken again (the one rule {@code TableSites} adds to the game's, because the task takes the table up). So a table
     * cannot go down where it stands: the craft has to walk to a place with a usable spot beside it (here, down off the
     * pillar), put the table there, craft and take it up again.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 600)
    public static void aCraftWalksToAPlaceForItsTable(GameTestHelper helper) {
        floor(helper);
        for (int y = FLOOR_Y + 1; y <= FLOOR_Y + 5; y++) helper.setBlock(8, y, 8, Blocks.STONE);
        Rig rig = Rig.spawnAt(helper, "pillar", 600, new Vec3(8.5, FLOOR_Y + 6.0, 8.5));
        giveThePickaxeMaterials(rig);
        rig.runtime().submit(new Goal.HaveItem(ItemKey.of("wooden_pickaxe"), 1));
        BlockPos[] placedAt = {null};
        double[] bodyYThen = {Double.NaN};
        rig.onEachTick(() -> {
            if (placedAt[0] == null) {
                List<BlockPos> tables = rig.tablesInArena();
                if (!tables.isEmpty()) {
                    placedAt[0] = tables.get(0);
                    bodyYThen[0] = rig.body().getY();
                }
            }
            GoalResult result = rig.runtime().lastResult();
            if (result == null) return;
            helper.assertTrue(result.status() == GoalResult.Status.SATISFIED, "the goal ended " + BotSpeech.result(result));
            helper.assertTrue(rig.count(Items.WOODEN_PICKAXE) == 1, "no wooden pickaxe in the inventory");
            helper.assertTrue(placedAt[0] != null, "no table was ever put down");
            helper.assertTrue(placedAt[0].getY() <= FLOOR_Y + 2, "the table went down at " + placedAt[0] + ", not on the ground");
            helper.assertTrue(bodyYThen[0] < FLOOR_Y + 3.0, "the body was still up on the pillar (y " + bodyYThen[0] + ") when the table went down");
            helper.assertTrue(rig.count(Items.CRAFTING_TABLE) == 1, "the table is not back in the inventory: " + rig.count(Items.CRAFTING_TABLE));
            helper.assertTrue(rig.tablesInArena().isEmpty(), "a crafting table was left standing at " + rig.tablesInArena());
            helper.assertTrue(BotMemory.of(rig.id()).placedCount() == 0, "the body still remembers a block it placed");
            rig.assertHandsEmpty();
            rig.pass();
        });
    }

    /**
     * What a player in a tree canopy does: puts the table on the leaves. Leaves hold no sturdy face, and a table does not
     * need one, so the only thing that can stop it is a rule stricter than the game's.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 600)
    public static void aCraftPlacesItsTableOnLeaves(GameTestHelper helper) {
        floor(helper);
        BlockState leaf = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
        for (int x = 6; x <= 10; x++) {
            for (int z = 6; z <= 10; z++) helper.setBlock(new BlockPos(x, 5, z), leaf);
        }
        Rig rig = Rig.spawnAt(helper, "leaves", 600, new Vec3(8.5, 6.0, 8.5));
        giveThePickaxeMaterials(rig);
        rig.runtime().submit(new Goal.HaveItem(ItemKey.of("wooden_pickaxe"), 1));
        BlockPos[] placedAt = {null};
        boolean[] onLeaves = {false};
        rig.onEachTick(() -> {
            if (placedAt[0] == null) {
                List<BlockPos> tables = rig.tablesInArena();
                if (!tables.isEmpty()) {
                    placedAt[0] = tables.get(0);
                    onLeaves[0] = helper.getBlockState(placedAt[0].below()).is(BlockTags.LEAVES);
                }
            }
            GoalResult result = rig.runtime().lastResult();
            if (result == null) return;
            helper.assertTrue(result.status() == GoalResult.Status.SATISFIED, "the goal ended " + BotSpeech.result(result));
            helper.assertTrue(rig.count(Items.WOODEN_PICKAXE) == 1, "no wooden pickaxe in the inventory");
            helper.assertTrue(placedAt[0] != null && onLeaves[0], "the table did not stand on leaves: " + placedAt[0]);
            helper.assertTrue(rig.count(Items.CRAFTING_TABLE) == 1, "the table is not back in the inventory: " + rig.count(Items.CRAFTING_TABLE));
            helper.assertTrue(rig.tablesInArena().isEmpty(), "a crafting table was left standing at " + rig.tablesInArena());
            rig.assertHandsEmpty();
            rig.pass();
        });
    }

    /**
     * The body is in a shaft cut in solid stone, thicker than the search is wide (narrowed to three blocks for this
     * body; the real one is twelve): nowhere to put a table and nowhere to stand beside one. The craft fails, the goal
     * ends at once with the failure, and nothing is lost. A replan cannot change this answer, so the executor must not
     * be given a failure it answers with the same plan again.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 300)
    public static void aCraftWithNowhereForItsTableGivesUpAtOnce(GameTestHelper helper) {
        for (int x = 3; x <= 13; x++) {
            for (int y = 3; y <= 13; y++) {
                for (int z = 3; z <= 13; z++) helper.setBlock(x, y, z, Blocks.STONE);
            }
        }
        helper.setBlock(8, 8, 8, Blocks.AIR);
        helper.setBlock(8, 9, 8, Blocks.AIR);
        Rig rig = Rig.spawnAt(helper, "shaft", 300, new Vec3(8.5, 8.0, 8.5));
        TableSites.setRadii(rig.id(), 3, 3);
        giveThePickaxeMaterials(rig);
        rig.runtime().submit(new Goal.HaveItem(ItemKey.of("wooden_pickaxe"), 1));
        int[] ticks = {0};
        int[] mostReplans = {0};
        rig.onEachTick(() -> {
            ticks[0]++;
            mostReplans[0] = Math.max(mostReplans[0], rig.runtime().view().totalReplans());
            GoalResult result = rig.runtime().lastResult();
            if (result == null) return;
            helper.assertTrue(ticks[0] <= 100, "the goal took " + ticks[0] + " ticks to end");
            helper.assertTrue(result.status() == GoalResult.Status.FAILED, "the goal ended " + BotSpeech.result(result));
            helper.assertTrue(result.failure() instanceof Failure.NoPlace none && none.radius() == 3,
                "the failure is not a NoPlace that looked three blocks out: " + result.failure());
            helper.assertTrue(mostReplans[0] == 0, "the executor answered the failure with " + mostReplans[0] + " replans");
            helper.assertTrue(rig.count(Items.OAK_PLANKS) == 3 && rig.count(Items.STICK) == 2 && rig.count(Items.CRAFTING_TABLE) == 1,
                "something was lost: planks " + rig.count(Items.OAK_PLANKS) + ", sticks " + rig.count(Items.STICK) + ", tables "
                    + rig.count(Items.CRAFTING_TABLE));
            helper.assertTrue(rig.tablesInArena().isEmpty(), "a crafting table was put down at " + rig.tablesInArena());
            rig.assertHandsEmpty();
            rig.pass();
        });
    }

    /**
     * The body starts inside the protected square around its owner's respawn point, where block safety refuses every spot
     * (the table could not be taken up again). The live runs of every bot that begins at the world spawn ended this way:
     * three identical replans, then the goal. A player walks out of the square, and so does the craft. The square here
     * ends at x = 7 (its centre is nine blocks west of the arena), so the table can only go down at x of 8 or more.
     * The neighbouring arenas are five blocks off, so the test cannot start from the square's middle, whose way out is
     * seventeen blocks; it narrows the normal search to two blocks instead, so that what finds the way out is the wide
     * search that follows a refusal, which is the same code as from the middle.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 700)
    public static void aCraftLeavesAProtectedAreaForItsTable(GameTestHelper helper) {
        floor(helper);
        Rig rig = Rig.spawnInZoneOf(helper, "zone", 700, new Vec3(0.5, FLOOR_TOP, 8.5), new BlockPos(-9, FLOOR_Y + 1, 8));
        TableSites.setRadii(rig.id(), 2, TableSites.WIDE_RADIUS);
        giveThePickaxeMaterials(rig);
        rig.runtime().submit(new Goal.HaveItem(ItemKey.of("wooden_pickaxe"), 1));
        BlockPos[] placedAt = {null};
        int[] mostReplans = {0};
        rig.onEachTick(() -> {
            mostReplans[0] = Math.max(mostReplans[0], rig.runtime().view().totalReplans());
            if (placedAt[0] == null) {
                List<BlockPos> tables = rig.tablesInArena();
                if (!tables.isEmpty()) placedAt[0] = tables.get(0);
            }
            GoalResult result = rig.runtime().lastResult();
            if (result == null) return;
            helper.assertTrue(result.status() == GoalResult.Status.SATISFIED, "the goal ended " + BotSpeech.result(result));
            helper.assertTrue(mostReplans[0] == 0, "the craft was replanned " + mostReplans[0] + " times");
            helper.assertTrue(rig.count(Items.WOODEN_PICKAXE) == 1, "no wooden pickaxe in the inventory");
            helper.assertTrue(placedAt[0] != null && placedAt[0].getX() >= 8, "the table went down inside the square, at " + placedAt[0]);
            helper.assertTrue(rig.count(Items.CRAFTING_TABLE) == 1, "the table is not back in the inventory: " + rig.count(Items.CRAFTING_TABLE));
            helper.assertTrue(rig.tablesInArena().isEmpty(), "a crafting table was left standing at " + rig.tablesInArena());
            helper.assertTrue(BotMemory.of(rig.id()).placedCount() == 0, "the body still remembers a block it placed");
            rig.assertHandsEmpty();
            rig.pass();
        });
    }

    /**
     * The protected square covers everything the wide search reaches (narrowed to five blocks): there is no place to put the
     * table, and that is a body with nowhere to go, not a block it may not touch. The answer is NoPlace with the radius it
     * looked over, at once and without a replan; a Protected answer excludes one block and replans, which is a plan that
     * finds the same refusal again.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 300)
    public static void aCraftThatCannotLeaveAProtectedAreaIsNoPlaceNotProtected(GameTestHelper helper) {
        floor(helper);
        Rig rig = Rig.spawnInZoneOf(helper, "locked", 300, new Vec3(8.5, FLOOR_TOP, 8.5), new BlockPos(8, FLOOR_Y + 1, 8));
        TableSites.setRadii(rig.id(), 2, 5);
        giveThePickaxeMaterials(rig);
        rig.runtime().submit(new Goal.HaveItem(ItemKey.of("wooden_pickaxe"), 1));
        int[] ticks = {0};
        int[] mostReplans = {0};
        rig.onEachTick(() -> {
            ticks[0]++;
            mostReplans[0] = Math.max(mostReplans[0], rig.runtime().view().totalReplans());
            GoalResult result = rig.runtime().lastResult();
            if (result == null) return;
            helper.assertTrue(ticks[0] <= 100, "the goal took " + ticks[0] + " ticks to end");
            helper.assertTrue(result.status() == GoalResult.Status.FAILED, "the goal ended " + BotSpeech.result(result));
            helper.assertTrue(result.failure() instanceof Failure.NoPlace none && none.radius() == 5,
                "the failure is not a NoPlace that looked five blocks out: " + result.failure());
            helper.assertTrue(mostReplans[0] == 0, "the executor answered the failure with " + mostReplans[0] + " replans");
            helper.assertTrue(rig.count(Items.OAK_PLANKS) == 3 && rig.count(Items.STICK) == 2 && rig.count(Items.CRAFTING_TABLE) == 1,
                "something was lost: planks " + rig.count(Items.OAK_PLANKS) + ", sticks " + rig.count(Items.STICK) + ", tables "
                    + rig.count(Items.CRAFTING_TABLE));
            helper.assertTrue(rig.tablesInArena().isEmpty(), "a crafting table was put down at " + rig.tablesInArena());
            rig.assertHandsEmpty();
            rig.pass();
        });
    }

    /**
     * The body is sealed in a tube of bedrock on an open floor: no spot beside it can be clicked from inside, and every
     * place the search offers outside is one the navigator cannot get to. The walks are bounded, and when they have all
     * failed the craft says it found no place (a replan would offer the same places, three times over), not that
     * something was unreachable.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 900)
    public static void aCraftWhoseWalksAllFailEndsInNoPlace(GameTestHelper helper) {
        floor(helper);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int y = FLOOR_Y + 1; y <= FLOOR_Y + 3; y++) {
                    boolean inside = dx == 0 && dz == 0 && y <= FLOOR_Y + 2;
                    if (!inside) helper.setBlock(8 + dx, y, 8 + dz, Blocks.BEDROCK);
                }
            }
        }
        Rig rig = Rig.spawn(helper, "sealed", null, 900);
        giveThePickaxeMaterials(rig);
        rig.runtime().submit(new Goal.HaveItem(ItemKey.of("wooden_pickaxe"), 1));
        int[] mostReplans = {0};
        rig.onEachTick(() -> {
            mostReplans[0] = Math.max(mostReplans[0], rig.runtime().view().totalReplans());
            GoalResult result = rig.runtime().lastResult();
            if (result == null) return;
            helper.assertTrue(result.status() == GoalResult.Status.FAILED, "the goal ended " + BotSpeech.result(result));
            helper.assertTrue(result.failure() instanceof Failure.NoPlace none && none.radius() == TableSites.DEFAULT_RADIUS,
                "the failure is not a NoPlace that looked the normal radius out: " + result.failure());
            helper.assertTrue(mostReplans[0] == 0, "the executor answered the failure with " + mostReplans[0] + " replans");
            helper.assertTrue(rig.count(Items.OAK_PLANKS) == 3 && rig.count(Items.STICK) == 2 && rig.count(Items.CRAFTING_TABLE) == 1,
                "something was lost: planks " + rig.count(Items.OAK_PLANKS) + ", sticks " + rig.count(Items.STICK) + ", tables "
                    + rig.count(Items.CRAFTING_TABLE));
            rig.assertHandsEmpty();
            rig.pass();
        });
    }

    /**
     * Four planks come out of one log and the inventory is full: 34 slots of unstackable tools, a stack of planks, and the
     * logs. A craft takes its result whole or not at all. Taking it partly would count the craft as made, use up its log,
     * and leave the other planks lying at the feet of a body with no room to pick them up; and the log the grid is given
     * back must find its way home, not onto the floor. Two fills: room for two of the four planks (the stack stands at 62),
     * and room for none (it stands at 64). After the craft has failed with {@code NoRoom} naming the planks, every slot
     * holds what it held and no item of any kind lies in the arena.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 100)
    public static void aResultThatDoesNotFitIsNotTakenAtAll(GameTestHelper helper) {
        floor(helper);
        Rig rig = Rig.spawn(helper, "room", null, 100);
        int[] planksInTheStack = {62, 64};
        int[] logsInTheStack = {2, 3};
        List<ItemStack> before = new ArrayList<>();
        TaskRun[] run = {null};
        int[] fill = {0};
        Runnable stock = () -> {
            Inventory inventory = rig.body().getInventory();
            inventory.clearContent();
            for (int slot = 0; slot < 34; slot++) inventory.setItem(slot, new ItemStack(Items.STONE_PICKAXE));
            inventory.setItem(34, new ItemStack(Items.OAK_PLANKS, planksInTheStack[fill[0]]));
            inventory.setItem(35, new ItemStack(Items.OAK_LOG, logsInTheStack[fill[0]]));
            before.clear();
            for (int slot = 0; slot < inventory.getContainerSize(); slot++) before.add(inventory.getItem(slot).copy());
            run[0] = new TaskRun(craftTask("minecraft:oak_planks", 1, "", new Goal.HaveItem(ItemKey.of("oak_planks"), 70)));
        };
        stock.run();
        rig.onEachTick(() -> {
            if (!run[0].tick(rig.body())) return;
            String state = "fill " + fill[0] + ": planks " + rig.count(Items.OAK_PLANKS) + ", logs " + rig.count(Items.OAK_LOG);
            helper.assertTrue(run[0].failure() instanceof Failure.NoRoom noRoom && noRoom.key().id().equals("minecraft:oak_planks"),
                "the craft ended " + run[0].result + " (" + state + ")");
            Inventory after = rig.body().getInventory();
            for (int slot = 0; slot < after.getContainerSize(); slot++) {
                helper.assertTrue(ItemStack.matches(before.get(slot), after.getItem(slot)),
                    "slot " + slot + " held " + before.get(slot) + " and holds " + after.getItem(slot) + " (" + state + ")");
            }
            List<ItemEntity> onTheFloor = helper.getLevel().getEntitiesOfClass(ItemEntity.class, arenaBox(helper),
                candidate -> !candidate.isRemoved());
            helper.assertTrue(onTheFloor.isEmpty(), "items lie in the arena: " + onTheFloor + " (" + state + ")");
            rig.assertHandsEmpty();
            if (++fill[0] == planksInTheStack.length) {
                rig.pass();
                return;
            }
            stock.run();
        });
    }

    /**
     * A table the body put down is gone when it comes to use it (someone broke it in the tick between): the body's memory
     * of having placed one there goes too, instead of staying for ever. The craft fails, since the only table was the one
     * put down.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 100)
    public static void aRememberedTableThatIsGoneIsForgotten(GameTestHelper helper) {
        floor(helper);
        Rig rig = Rig.spawn(helper, "gone", null, 100);
        giveThePickaxeMaterials(rig);
        TaskRun run = new TaskRun(pickaxeTask());
        boolean[] removed = {false};
        rig.onEachTick(() -> {
            if (!removed[0]) {
                List<BlockPos> tables = rig.tablesInArena();
                if (!tables.isEmpty()) {
                    helper.assertTrue(BotMemory.of(rig.id()).placedCount() == 1, "the body does not remember placing the table");
                    helper.setBlock(tables.get(0), Blocks.AIR);
                    removed[0] = true;
                }
            }
            if (!run.tick(rig.body())) return;
            helper.assertTrue(removed[0], "no table was ever put down, so nothing was tested");
            helper.assertTrue(run.failure() instanceof Failure.MissingInput, "the craft ended " + run.result);
            helper.assertTrue(BotMemory.of(rig.id()).placedCount() == 0, "the body still remembers a table that is gone: " + BotMemory.of(rig.id()));
            rig.assertHandsEmpty();
            rig.pass();
        });
    }

    /**
     * The body may not break the table it put down (a claim arrived while the craft was made). The craft is what the goal
     * asked for, so it still succeeds; the table stays where it is and stays the body's, so that the tidy step or the next
     * craft takes it up.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 200)
    public static void aRefusedTableBreakStillCompletesTheCraft(GameTestHelper helper) {
        floor(helper);
        Rig rig = Rig.spawn(helper, "claim", null, 200);
        giveThePickaxeMaterials(rig);
        AABB arena = arenaBox(helper);
        AtomicBoolean claimed = new AtomicBoolean();
        ClaimProvider claim = (level, pos, actingFor) -> claimed.get() && arena.contains(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        BlockSafety.registerClaimProvider(claim);
        rig.atEnd(() -> BlockSafety.unregisterClaimProvider(claim));
        TaskRun run = new TaskRun(pickaxeTask());
        rig.onEachTick(() -> {
            if (!rig.tablesInArena().isEmpty()) claimed.set(true); // from the moment the table stands
            if (!run.tick(rig.body())) return;
            helper.assertTrue(run.result instanceof TickResult.Completed, "the craft ended " + run.result);
            helper.assertTrue(rig.count(Items.WOODEN_PICKAXE) == 1, "no wooden pickaxe in the inventory");
            List<BlockPos> tables = rig.tablesInArena();
            helper.assertTrue(tables.size() == 1, "expected the table to be left standing, found " + tables);
            helper.assertTrue(BotMemory.of(rig.id()).isPlaced(helper.getLevel().dimension().location().toString(), helper.absolutePos(tables.get(0))),
                "the body no longer remembers the table it left standing");
            rig.assertHandsEmpty();
            rig.pass();
        });
    }

    /**
     * A craft at a table the task placed, with the inventory filled so that the result fits and the table's item does not
     * (set up once before the task starts): 34 unstackable pickaxes, a stack of 64 planks and the table. The bowls are
     * taken from the middle of the planks stack, so no ingredient slot ever empties, and the craft's four bowls fill the
     * one slot the table left when it went down -- after the craft there is no room for the table's item. The table is not
     * broken: the craft completes, the table still stands, remembered as the body's, and no item entity lies in the arena.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 300)
    public static void aTakenUpTableWhoseItemDoesNotFitIsLeftStanding(GameTestHelper helper) {
        floor(helper);
        Rig rig = Rig.spawn(helper, "nofit", null, 300);
        rig.give(Items.OAK_PLANKS, 64);
        rig.give(Items.CRAFTING_TABLE, 1);
        Inventory inventory = rig.body().getInventory();
        for (int slot = 2; slot < 36; slot++) inventory.setItem(slot, new ItemStack(Items.STONE_PICKAXE));
        TaskRun run = new TaskRun(craftTask("minecraft:bowl", 1, "minecraft:crafting_table", new Goal.HaveItem(ItemKey.of("bowl"), 4)));
        rig.onEachTick(() -> {
            if (!run.tick(rig.body())) return;
            helper.assertTrue(run.result instanceof TickResult.Completed, "the craft ended " + run.result);
            helper.assertTrue(rig.count(Items.BOWL) == 4, "no bowls in the inventory: " + rig.count(Items.BOWL));
            List<BlockPos> tables = rig.tablesInArena();
            helper.assertTrue(tables.size() == 1, "expected the table to be left standing, found " + tables);
            helper.assertTrue(BotMemory.of(rig.id()).isPlaced(helper.getLevel().dimension().location().toString(),
                helper.absolutePos(tables.get(0))), "the body no longer remembers the table it left standing");
            List<ItemEntity> onTheFloor = helper.getLevel().getEntitiesOfClass(ItemEntity.class, arenaBox(helper),
                candidate -> !candidate.isRemoved());
            helper.assertTrue(onTheFloor.isEmpty(), "items lie in the arena: " + onTheFloor);
            rig.assertHandsEmpty();
            rig.pass();
        });
    }

    /**
     * What is worn or held in the off hand is in use: a craft draws neither its ingredients nor its table from there, and
     * what it finds is the same as what it counts. A table in the off hand is no table to put down, which is a missing
     * input (a replan from the live inventory), not an internal failure; ingredients in the off hand are missing too.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 100)
    public static void whatIsHeldInTheOffHandIsNotDrawnFrom(GameTestHelper helper) {
        floor(helper);
        Rig rig = Rig.spawn(helper, "offhand", null, 100);
        rig.give(Items.OAK_PLANKS, 3);
        rig.give(Items.STICK, 2);
        rig.body().getInventory().setItem(Inventory.SLOT_OFFHAND, new ItemStack(Items.CRAFTING_TABLE));
        TaskRun table = new TaskRun(pickaxeTask());
        TaskRun planks = new TaskRun(craftTask("minecraft:stick", 1, "", new Goal.HaveItem(ItemKey.of("stick"), 6)));
        int[] stage = {0};
        rig.onEachTick(() -> {
            if (stage[0] == 0) {
                if (!table.tick(rig.body())) return;
                helper.assertTrue(table.failure() instanceof Failure.MissingInput missing && missing.key().id().equals("minecraft:crafting_table"),
                    "a table in the off hand: the craft ended " + table.result);
                helper.assertTrue(rig.count(Items.CRAFTING_TABLE) == 1 && rig.tablesInArena().isEmpty(), "the off-hand table moved");
                // Now the planks go to the off hand too (the table's craft above left them where they were).
                Inventory inventory = rig.body().getInventory();
                inventory.clearContent();
                inventory.setItem(Inventory.SLOT_OFFHAND, new ItemStack(Items.OAK_PLANKS, 4));
                stage[0] = 1;
                return;
            }
            if (!planks.tick(rig.body())) return;
            helper.assertTrue(planks.failure() instanceof Failure.MissingInput, "planks in the off hand: the craft ended " + planks.result);
            helper.assertTrue(rig.count(Items.OAK_PLANKS) == 4 && rig.count(Items.STICK) == 0, "an off-hand item was used");
            rig.assertHandsEmpty();
            rig.pass();
        });
    }

    /**
     * A craft that needs a table goes through the table's menu: the menu is open while the craft runs, which takes more than
     * one tick, and the item is not in the inventory until the craft has been clicked through. Items written straight into
     * the inventory could do neither.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 200)
    public static void aCraftAtATableGoesThroughItsMenu(GameTestHelper helper) {
        floor(helper);
        Rig rig = Rig.spawn(helper, "menu", null, 200);
        giveThePickaxeMaterials(rig);
        rig.runtime().submit(new Goal.HaveItem(ItemKey.of("wooden_pickaxe"), 1));
        int[] menuTicks = {0};
        int[] pickaxesWhenItOpened = {-1};
        rig.onEachTick(() -> {
            if (rig.body().containerMenu instanceof CraftingMenu) {
                if (menuTicks[0]++ == 0) pickaxesWhenItOpened[0] = rig.count(Items.WOODEN_PICKAXE);
            }
            GoalResult result = rig.runtime().lastResult();
            if (result == null) return;
            helper.assertTrue(result.status() == GoalResult.Status.SATISFIED, "the goal ended " + BotSpeech.result(result));
            helper.assertTrue(menuTicks[0] >= 2, "the crafting menu was open for " + menuTicks[0] + " ticks: nothing was clicked through it");
            helper.assertTrue(pickaxesWhenItOpened[0] == 0, "the pickaxe was already in the inventory when the menu opened");
            helper.assertTrue(rig.count(Items.WOODEN_PICKAXE) == 1, "no wooden pickaxe in the inventory");
            rig.assertHandsEmpty();
            rig.pass();
        });
    }

    /** The moments of a craft at a table that a reflex can interrupt. */
    private enum Moment { CRAFTING, BREAKING, DROPPED }

    /**
     * What a reflex does to a craft: it holds the body, which pauses the task, and carries the body several blocks away
     * before the task goes on. The test stands in for the retreat: the goal is paused at the moment, the body is walked off
     * through its own input until the table is out of reach, and the goal is resumed. The craft must find its table again
     * (or the table's dropped item) and end as if nothing had happened: the pickaxe made, the table back in the inventory,
     * nothing left standing.
     */
    private static void interruptedCraft(GameTestHelper helper, String tag, Moment moment) {
        floor(helper);
        Rig rig = Rig.spawn(helper, tag, null, 700);
        giveThePickaxeMaterials(rig);
        rig.runtime().submit(new Goal.HaveItem(ItemKey.of("wooden_pickaxe"), 1));
        Vec3[] interest = {null};
        Vec3[] target = {null};
        int[] stage = {0};
        int[] walked = {0};
        rig.onEachTick(() -> {
            BotPlayer body = rig.body();
            GoalResult result = rig.runtime().lastResult();
            switch (stage[0]) {
                case 0 -> {
                    helper.assertTrue(result == null, "the goal ended before the moment " + moment + " came: " + (result == null ? "" : BotSpeech.result(result)));
                    Vec3 point = pointOfMoment(helper, rig, moment);
                    if (point == null) return;
                    interest[0] = point;
                    rig.runtime().pause();
                    helper.assertTrue(rig.runtime().view().status() == GoalStatus.PAUSED, "the goal is " + rig.runtime().view().status() + " after the pause");
                    if (moment == Moment.DROPPED) {
                        // A walk from a standstill is slow: the body would still be inside its own pickup box when the item's
                        // ten-tick delay ran out, and take it. A real retreat starts further off than this; the delay is held
                        // back so that the distance, not the timing, is what keeps the body from the item.
                        for (ItemEntity drop : helper.getLevel().getEntitiesOfClass(ItemEntity.class, arenaBox(helper),
                            candidate -> candidate.getItem().is(Items.CRAFTING_TABLE))) {
                            drop.setPickUpDelay(80);
                        }
                    }
                    target[0] = farthestPlaceFrom(helper, body, point);
                    stage[0] = 1;
                }
                case 1 -> {
                    Vec3 at = body.position();
                    boolean arrived = Math.hypot(at.x - target[0].x, at.z - target[0].z) < 0.5;
                    if (arrived || ++walked[0] > 150) {
                        body.input().reset();
                        double apart = Math.hypot(at.x - interest[0].x, at.z - interest[0].z);
                        helper.assertTrue(apart > 5.5, "setup: the body is only " + apart + " blocks from the table after the walk");
                        rig.runtime().resume();
                        stage[0] = 2;
                        return;
                    }
                    body.input().lookAt(new Vec3(target[0].x, body.getEyeY(), target[0].z));
                    body.input().forward = 1.0F;
                }
                default -> {
                    if (result == null) return;
                    helper.assertTrue(result.status() == GoalResult.Status.SATISFIED, "the goal ended " + BotSpeech.result(result));
                    helper.assertTrue(rig.count(Items.WOODEN_PICKAXE) == 1, "no wooden pickaxe in the inventory");
                    helper.assertTrue(rig.count(Items.CRAFTING_TABLE) == 1, "the table is not back in the inventory: " + rig.count(Items.CRAFTING_TABLE));
                    helper.assertTrue(rig.tablesInArena().isEmpty(), "a crafting table was left standing at " + rig.tablesInArena());
                    helper.assertTrue(BotMemory.of(rig.id()).placedCount() == 0, "the body still remembers a block it placed");
                    rig.assertHandsEmpty();
                    rig.pass();
                }
            }
        });
    }

    /** Where the table (or its dropped item) is when the moment has come, or null while it has not. Absolute. */
    private static Vec3 pointOfMoment(GameTestHelper helper, Rig rig, Moment moment) {
        List<BlockPos> tables = rig.tablesInArena();
        boolean made = rig.count(Items.WOODEN_PICKAXE) == 1;
        switch (moment) {
            case CRAFTING -> {
                if (!tables.isEmpty() && !made && rig.body().containerMenu instanceof CraftingMenu) {
                    return Vec3.atCenterOf(helper.absolutePos(tables.get(0)));
                }
            }
            case BREAKING -> {
                if (!tables.isEmpty() && made && rig.body().getInteractionManager().isMining()) {
                    return Vec3.atCenterOf(helper.absolutePos(tables.get(0)));
                }
            }
            case DROPPED -> {
                if (tables.isEmpty() && made) {
                    for (ItemEntity drop : helper.getLevel().getEntitiesOfClass(ItemEntity.class, arenaBox(helper),
                        candidate -> !candidate.isRemoved() && candidate.getItem().is(Items.CRAFTING_TABLE))) {
                        return drop.position();
                    }
                }
            }
        }
        return null;
    }

    /** The point inside the arena, in line with the body along one axis, that is farthest from {@code point}. Absolute. */
    private static Vec3 farthestPlaceFrom(GameTestHelper helper, BotPlayer body, Vec3 point) {
        Vec3 origin = helper.absoluteVec(Vec3.ZERO);
        double x = body.getX() - origin.x;
        double z = body.getZ() - origin.z;
        Vec3[] candidates = {new Vec3(1.5, 0, z), new Vec3(14.5, 0, z), new Vec3(x, 0, 1.5), new Vec3(x, 0, 14.5)};
        Vec3 best = null;
        double bestDistance = -1.0;
        for (Vec3 candidate : candidates) {
            double distance = Math.hypot(origin.x + candidate.x - point.x, origin.z + candidate.z - point.z);
            if (distance > bestDistance) {
                bestDistance = distance;
                best = new Vec3(origin.x + candidate.x, body.getY(), origin.z + candidate.z);
            }
        }
        return best;
    }

    /** Carried off with the menu open and the pickaxe not yet made: the table stands out of reach, and nothing was made yet. */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 700)
    public static void aCraftInterruptedAtItsTableWalksBackToIt(GameTestHelper helper) {
        interruptedCraft(helper, "intcraf", Moment.CRAFTING);
    }

    /** Carried off while the table is being broken: the take-up goes back to the table before it breaks it again. */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 700)
    public static void aTableBeingTakenUpWhenTheBodyIsCarriedOffIsWalkedBackTo(GameTestHelper helper) {
        interruptedCraft(helper, "intbrk", Moment.BREAKING);
    }

    /**
     * Carried off the moment the table is broken: its item lies where the table stood, further from the body than the
     * body's own pickup reach and than the four blocks the take-up used to look, and the take-up must walk to it.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 700)
    public static void aTableItemFarFromTheBodyIsWalkedTo(GameTestHelper helper) {
        interruptedCraft(helper, "intdrop", Moment.DROPPED);
    }

    /**
     * A goal typed while the reflex layer holds the body is queued and has not started, and the body is still wanted: it
     * must be there when the hold lifts, so it stays loaded.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 200)
    public static void aQueuedGoalKeepsTheBodyLoaded(GameTestHelper helper) {
        floor(helper);
        Rig rig = Rig.spawn(helper, "queued", null, 200);
        rig.give(Items.OAK_LOG, 1);
        ReflexSource before = ReflexSources.current();
        boolean[] held = {true};
        // The source is the server's one: every other body's calls go on to the real one, all of them (a wrapper that
        // passed only holdFor on would swallow the deaths and the saves of the tests running beside this one).
        ReflexSources.set(new ReflexSource() {
            @Override
            public ReflexHold holdFor(BotPlayer body) {
                return body.getUUID().equals(rig.id()) && held[0] ? new ReflexHold(true, "a test") : before.holdFor(body);
            }

            @Override
            public void onDeath(BotPlayer body) {
                before.onDeath(body);
            }

            @Override
            public void saveShelterBlocks(BotPlayer body, CompoundTag out) {
                before.saveShelterBlocks(body, out);
            }

            @Override
            public void loadShelterBlocks(BotPlayer body, CompoundTag in) {
                before.loadShelterBlocks(body, in);
            }
        });
        rig.atEnd(() -> ReflexSources.set(before));
        int[] tick = {0};
        boolean[] queued = {false};
        rig.onEachTick(() -> {
            if (!queued[0]) {
                if (++tick[0] < 3) return; // the executor has been ticked, so it knows the body is held
                helper.assertTrue(!rig.runtime().wantsToStayLoaded(), "an idle body wants to stay loaded");
                rig.runtime().submit(new Goal.HaveItem(ItemKey.of("oak_planks"), 4));
                helper.assertTrue(!rig.runtime().hasGoal(), "a goal started while the reflex layer held the body");
                helper.assertTrue(rig.runtime().wantsToStayLoaded(), "a goal that is queued and not started does not keep the body loaded");
                held[0] = false;
                queued[0] = true;
                return;
            }
            GoalResult result = rig.runtime().lastResult();
            if (result == null) return;
            helper.assertTrue(result.status() == GoalResult.Status.SATISFIED, "the goal ended " + BotSpeech.result(result));
            rig.pass();
        });
    }

    /**
     * What the real planner writes for a craft is what the craft follows. A pickaxe's slots matter (planks in the top row,
     * sticks below) and the stock holds two kinds of planks. The layout the planner wrote names the items in the recipe's
     * slot order; the runtime crafts with exactly those, and a task given the same leaf with a stock that cannot supply
     * them fails instead of choosing again (a layout is followed, never re-decided).
     *
     * <p>Run on the code before this change this passes too: the layout format and the slot order of the planner and of
     * the task agree. What it pins is that they keep agreeing.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 300)
    public static void aCraftFollowsTheLayoutThePlannerWrote(GameTestHelper helper) {
        floor(helper);
        Rig rig = Rig.spawn(helper, "planned", null, 300);
        rig.give(Items.SPRUCE_PLANKS, 3);
        rig.give(Items.OAK_PLANKS, 3);
        rig.give(Items.STICK, 2);
        rig.give(Items.CRAFTING_TABLE, 1);
        Goal goal = new Goal.HaveItem(ItemKey.of("wooden_pickaxe"), 1);
        NodeSpec leaf = plannedCraft(rig, goal, "minecraft:wooden_pickaxe");
        String text = leaf.params().get("layout");
        helper.assertTrue(text != null && !text.isBlank(), "the planner wrote no layout for the pickaxe: " + leaf.params());
        List<CraftLayout.Run> runs = CraftLayout.decode(text);
        helper.assertTrue(runs.size() == 1 && runs.get(0).crafts() == 1, "the layout is not one craft: " + text);
        List<ItemKey> items = runs.get(0).items();
        helper.assertTrue(items.size() == 5, "the layout names " + items.size() + " items for the pickaxe's five slots: " + text);
        for (int slot = 0; slot < 3; slot++) {
            helper.assertTrue(items.get(slot).id().endsWith("_planks"), "slot " + slot + " (the top row) is " + items.get(slot).id());
        }
        helper.assertTrue(items.get(3).id().equals("minecraft:stick") && items.get(4).id().equals("minecraft:stick"),
            "the slots below the top row are " + items.get(3).id() + " and " + items.get(4).id());
        int plannedOak = (int) items.stream().filter(item -> item.id().equals("minecraft:oak_planks")).count();
        int plannedSpruce = 3 - plannedOak;

        rig.runtime().submit(goal);
        int[] stage = {0};
        TaskRun again = new TaskRun(TaskRegistry.create(leaf, Map.of()));
        rig.onEachTick(() -> {
            if (stage[0] == 0) {
                GoalResult result = rig.runtime().lastResult();
                if (result == null) return;
                helper.assertTrue(result.status() == GoalResult.Status.SATISFIED, "the goal ended " + BotSpeech.result(result));
                helper.assertTrue(rig.count(Items.WOODEN_PICKAXE) == 1, "no wooden pickaxe in the inventory");
                helper.assertTrue(rig.count(Items.OAK_PLANKS) == 3 - plannedOak && rig.count(Items.SPRUCE_PLANKS) == 3 - plannedSpruce,
                    "the layout used " + plannedOak + " oak and " + plannedSpruce + " spruce planks, the craft left "
                        + rig.count(Items.OAK_PLANKS) + " oak and " + rig.count(Items.SPRUCE_PLANKS) + " spruce");
                // The same leaf again, with only the kind of planks the layout did not name: any plank would do for the
                // recipe, so a task that chose for itself would make the pickaxe.
                Inventory inventory = rig.body().getInventory();
                inventory.clearContent();
                inventory.add(new ItemStack(plannedOak == 3 ? Items.SPRUCE_PLANKS : Items.OAK_PLANKS, 3));
                inventory.add(new ItemStack(Items.STICK, 2));
                inventory.add(new ItemStack(Items.CRAFTING_TABLE, 1));
                stage[0] = 1;
                return;
            }
            if (!again.tick(rig.body())) return;
            helper.assertTrue(again.failure() instanceof Failure.MissingInput, "the task chose for itself instead of following the layout: "
                + again.result + ", pickaxes " + rig.count(Items.WOODEN_PICKAXE));
            helper.assertTrue(rig.count(Items.WOODEN_PICKAXE) == 0, "a pickaxe was made from planks the layout did not name");
            rig.assertHandsEmpty();
            rig.pass();
        });
    }

    /** The craft leaf for a recipe in the plan the real planner makes for the goal from what the body carries now. */
    private static NodeSpec plannedCraft(Rig rig, Goal goal, String recipe) {
        SolverPlanner planner = new SolverPlanner(RecipeBooks.current(), AcquisitionIndexes.current(), PlannerConfig.defaults(), List.of());
        PlanOutcome outcome = planner.plan(new PlanRequest(goal, SnapshotCollector.collect(rig.body()), Exclusions.NONE,
            Profile.STRICT_SURVIVAL, Map.of()));
        if (!(outcome instanceof PlanOutcome.Planned planned)) throw new IllegalStateException("the planner made no plan for " + goal + ": " + outcome);
        NodeSpec found = craftOf(planned.plan().roots(), recipe);
        if (found == null) throw new IllegalStateException("the plan for " + goal + " has no craft of " + recipe + ": " + planned.plan().roots());
        return found;
    }

    private static NodeSpec craftOf(List<NodeSpec> nodes, String recipe) {
        for (NodeSpec node : nodes) {
            if (node.isLeaf() && recipe.equals(node.params().get("recipe"))) return node;
            NodeSpec inside = craftOf(node.children(), recipe);
            if (inside != null) return inside;
        }
        return null;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Results
    // ---------------------------------------------------------------------------------------------------------

    /** Nothing supplies bedrock: the goal fails at planning, and the result names the item. */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 200)
    public static void anItemNothingSuppliesFailsAndNamesIt(GameTestHelper helper) {
        floor(helper);
        Rig rig = Rig.spawn(helper, "bedrock", null, 200);
        rig.runtime().submit(new Goal.HaveItem(ItemKey.of("bedrock"), 1));
        rig.onEachTick(() -> {
            GoalResult result = rig.runtime().lastResult();
            if (result == null) return;
            helper.assertTrue(result.status() == GoalResult.Status.FAILED, "expected FAILED, the goal ended " + BotSpeech.result(result));
            helper.assertTrue(result.failure() instanceof Failure.NoSource none && none.key().id().equals("minecraft:bedrock"),
                "the failure does not name bedrock: " + result.failure());
            helper.assertTrue(BotSpeech.result(result).contains("bedrock"), "what the bot says does not name the item: " + BotSpeech.result(result));
            rig.pass();
        });
    }

    // ---------------------------------------------------------------------------------------------------------
    // Persistence
    // ---------------------------------------------------------------------------------------------------------

    /**
     * A goal in progress is saved with the body, the body is logged out and made again, and the brain it gets is a new
     * runtime that loads the goal and finishes it. The body keeps its inventory through the same save.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = PICKAXE_TICKS + 100)
    public static void aGoalInProgressSurvivesALogout(GameTestHelper helper) {
        floor(helper);
        Rig rig = Rig.spawn(helper, "persist", null, PICKAXE_TICKS + 100);
        rig.give(Items.OAK_LOG, 3);
        rig.runtime().submit(new Goal.HaveItem(ItemKey.of("wooden_pickaxe"), 1));
        BotRuntime[] before = new BotRuntime[1];
        int[] stage = {0};
        int[] waited = {0};
        rig.onEachTick(() -> {
            switch (stage[0]) {
                case 0 -> {
                    // The plan is adopted: there is a goal with steps to save.
                    if (rig.runtime().view().nodes().isEmpty()) return;
                    before[0] = rig.runtime();
                    helper.assertTrue(rig.runtime().wantsToStayLoaded(), "a body with a goal does not want to stay loaded");
                    rig.manager().despawn(rig.body());
                    stage[0] = 1;
                }
                case 1 -> {
                    if (++waited[0] < 5) return;
                    rig.respawn(helper.absoluteVec(new Vec3(8.5, FLOOR_TOP, 8.5)));
                    BotRuntime after = rig.runtime();
                    helper.assertTrue(after != before[0], "the respawned body kept the old runtime");
                    helper.assertTrue(after.hasGoal(), "the goal did not come back with the body");
                    helper.assertTrue(after.view().goal() instanceof Goal.HaveItem have && have.key().id().equals("minecraft:wooden_pickaxe"),
                        "the goal that came back is " + after.view().goal());
                    stage[0] = 2;
                }
                default -> {
                    GoalResult result = rig.runtime().lastResult();
                    if (result == null) return;
                    helper.assertTrue(result.status() == GoalResult.Status.SATISFIED, "the goal ended " + BotSpeech.result(result));
                    helper.assertTrue(rig.count(Items.WOODEN_PICKAXE) == 1, "no wooden pickaxe after the restart");
                    helper.assertTrue(rig.tablesInArena().isEmpty(), "a crafting table was left standing at " + rig.tablesInArena());
                    rig.pass();
                }
            }
        });
    }

    /**
     * The body is logged out the moment its table stands, which is what a server stop in the middle of a craft does. The
     * planner counts a table in the inventory, not in the world, so a table left standing would cost the goal its planks:
     * the new runtime takes the table up first (its memory says it placed it), then finishes the goal.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = PICKAXE_TICKS + 200)
    public static void aTableLeftStandingByALogoutIsTakenUp(GameTestHelper helper) {
        floor(helper);
        Rig rig = Rig.spawn(helper, "tidy", null, PICKAXE_TICKS + 200);
        rig.give(Items.OAK_LOG, 3);
        rig.runtime().submit(new Goal.HaveItem(ItemKey.of("wooden_pickaxe"), 1));
        BotRuntime[] before = new BotRuntime[1];
        int[] stage = {0};
        int[] waited = {0};
        rig.onEachTick(() -> {
            switch (stage[0]) {
                case 0 -> {
                    if (rig.tablesInArena().isEmpty()) return;
                    helper.assertTrue(BotMemory.of(rig.id()).placedCount() == 1, "the body does not remember placing the table");
                    before[0] = rig.runtime();
                    rig.manager().despawn(rig.body());
                    stage[0] = 1;
                }
                case 1 -> {
                    if (++waited[0] < 5) return;
                    rig.respawn(helper.absoluteVec(new Vec3(8.5, FLOOR_TOP, 8.5)));
                    helper.assertTrue(rig.runtime() != before[0], "the respawned body kept the old runtime");
                    helper.assertTrue(!rig.tablesInArena().isEmpty(), "the table is gone before the new body did anything");
                    stage[0] = 2;
                }
                default -> {
                    GoalResult result = rig.runtime().lastResult();
                    if (result == null) return;
                    helper.assertTrue(result.status() == GoalResult.Status.SATISFIED, "the goal ended " + BotSpeech.result(result));
                    helper.assertTrue(rig.count(Items.WOODEN_PICKAXE) == 1, "no wooden pickaxe after the restart");
                    helper.assertTrue(rig.count(Items.CRAFTING_TABLE) == 1, "the table is not back in the inventory: " + rig.count(Items.CRAFTING_TABLE));
                    helper.assertTrue(rig.tablesInArena().isEmpty(), "a crafting table was left standing at " + rig.tablesInArena());
                    helper.assertTrue(BotMemory.of(rig.id()).placedCount() == 0, "the body still remembers a block it placed");
                    rig.assertHandsEmpty();
                    rig.pass();
                }
            }
        });
    }

    /** The save form's types and nesting survive the trip through NBT unchanged, a list of mixed things included. */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 20)
    public static void theSaveFormRoundTripsThroughNbt(GameTestHelper helper) {
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("flag", true);
        inner.put("off", false);
        inner.put("ratio", -0.5);
        inner.put("big", Long.MAX_VALUE);
        inner.put("small", Long.MIN_VALUE);
        inner.put("text", "mine:minecraft:iron_ore");
        inner.put("empty", "");
        Map<String, Object> form = new LinkedHashMap<>();
        form.put("v", 1L);
        form.put("paused", false);
        form.put("nested", inner);
        form.put("emptyMap", new LinkedHashMap<String, Object>());
        form.put("emptyList", new ArrayList<>());
        form.put("mixed", new ArrayList<Object>(List.of("a", 2L, 3.5, true, new LinkedHashMap<>(Map.of("k", "v")), List.of(List.of(1L)))));
        CompoundTag tag = NbtForm.toTag(form);
        Map<String, Object> back = NbtForm.toMap(tag);
        helper.assertTrue(back.equals(form), "the form changed on the way through NBT:\n" + form + "\n" + back);
        helper.assertTrue(back.get("v") instanceof Long && back.get("paused") instanceof Boolean && back.get("mixed") instanceof List<?>,
            "a value came back as another type: " + back);
        boolean refused = false;
        try {
            NbtForm.toTag(Map.of("bad", new Object()));
        } catch (IllegalArgumentException expected) {
            refused = true;
        }
        helper.assertTrue(refused, "a value outside the save form was written");
        helper.succeed();
    }

    // ---------------------------------------------------------------------------------------------------------
    // Talking to the bot
    // ---------------------------------------------------------------------------------------------------------

    /**
     * A build goal made from the owner's words: a blueprint saved as a file at set-up (six planks of the arena, test
     * set-up), then {@code build <name> at <x> <y> <z>} through the goal command. The words become a running build
     * goal and the body builds the file's shape at the anchor. Passes because the language context now lists the
     * blueprint folder's names ({@code BotTalk.context}, commit 528bad0); on the commit before that fix the list was
     * always empty and the words were answered "I don't know a blueprint called ...".
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 600)
    public static void aBuildGoalFromTheOwnersWords(GameTestHelper helper) {
        floor(helper);
        // Set-up: the shape the blueprint is saved from, six planks in a 3x2 box.
        for (int x = 4; x <= 6; x++) {
            for (int z = 4; z <= 5; z++) helper.setBlock(x, FLOOR_Y + 1, z, Blocks.OAK_PLANKS);
        }
        String name = "gtwb" + RUN_ID;
        String refused = Blueprints.save(helper.getLevel(), name, helper.absolutePos(new BlockPos(4, FLOOR_Y + 1, 4)),
            helper.absolutePos(new BlockPos(6, FLOOR_Y + 1, 5)));
        helper.assertTrue(refused == null, "the save was refused: " + refused);
        BlockPos anchor = helper.absolutePos(new BlockPos(4, FLOOR_Y + 1, 10));
        Rig rig = Rig.spawnOwned(helper, "bwords", 600);
        rig.atEnd(() -> Blueprints.remove(name));
        rig.give(Items.OAK_PLANKS, 6);
        Feedback feedback = new Feedback();
        int done = run(rig.server(), rig.ownerSource(feedback), "aiplayer goal " + rig.name() + " build " + name
            + " at " + anchor.getX() + " " + anchor.getY() + " " + anchor.getZ());
        helper.assertTrue(done == 1, "the goal command failed: " + feedback.messages);
        helper.assertTrue(rig.runtime().view().goal() instanceof Goal.Build building && building.blueprint().equals(name),
            "the words did not become a build goal: " + rig.runtime().view().goal());
        rig.onEachTick(() -> {
            GoalResult result = rig.runtime().lastResult();
            if (result == null) return;
            helper.assertTrue(result.status() == GoalResult.Status.SATISFIED, "the goal ended " + BotSpeech.result(result));
            for (int x = 4; x <= 6; x++) {
                for (int z = 10; z <= 11; z++) {
                    helper.assertTrue(helper.getBlockState(new BlockPos(x, FLOOR_Y + 1, z)).is(Blocks.OAK_PLANKS),
                        "the saved shape does not stand at " + x + " " + z);
                }
            }
            rig.pass();
        });
    }

    /** Status names the goal and the step; pause and resume flip the goal's state; cancel ends it as CANCELLED. */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 200)
    public static void statusPauseResumeAndCancelThroughCommands(GameTestHelper helper) {
        floor(helper);
        Rig rig = Rig.spawnOwned(helper, "cmds", 200);
        rig.give(Items.OAK_LOG, 3);
        MinecraftServer server = rig.server();
        helper.assertTrue(run(server, rig.ownerSource(new Feedback()), "aiplayer goal " + rig.name() + " make 1 wooden pickaxe") == 1,
            "the goal command failed");
        int[] stage = {0};
        rig.onEachTick(() -> {
            if (stage[0] > 0 || rig.runtime().view().nodes().isEmpty()) return;
            stage[0] = 1;
            Feedback status = new Feedback();
            helper.assertTrue(run(server, rig.ownerSource(status), "aiplayer status " + rig.name()) == 1, "status failed: " + status.messages);
            // What status reports is the executor's view: the goal, and a step of the plan still to do.
            ExecutorView view = rig.runtime().view();
            helper.assertTrue(!status.messages.isEmpty(), "status answered nothing");
            helper.assertTrue(isHaveItem(view.goal(), "minecraft:wooden_pickaxe", 1), "the executor's goal is " + view.goal());
            helper.assertTrue(view.nodes().stream().anyMatch(node -> node.taskKind() != null && node.state() != NodeState.SATISFIED),
                "the executor shows no step still to do: " + view.nodes());

            Feedback pause = new Feedback();
            run(server, rig.ownerSource(pause), "aiplayer pause " + rig.name());
            helper.assertTrue(rig.runtime().view().status() == GoalStatus.PAUSED, "the goal is " + rig.runtime().view().status() + " after pause");
            run(server, rig.ownerSource(new Feedback()), "aiplayer resume " + rig.name());
            helper.assertTrue(rig.runtime().view().status() == GoalStatus.RUNNING, "the goal is " + rig.runtime().view().status() + " after resume");

            Feedback cancel = new Feedback();
            helper.assertTrue(run(server, rig.ownerSource(cancel), "aiplayer cancel " + rig.name()) == 1, "cancel failed: " + cancel.messages);
            GoalResult result = rig.runtime().lastResult();
            helper.assertTrue(result != null && result.status() == GoalResult.Status.CANCELLED, "the goal did not end CANCELLED: " + result);
            helper.assertTrue(!cancel.messages.isEmpty(), "the cancel was not told to its source");
            helper.assertTrue(!rig.runtime().hasGoal(), "a cancelled goal is still active");
            rig.assertHandsEmpty();

            Feedback idle = new Feedback();
            run(server, rig.ownerSource(idle), "aiplayer status " + rig.name());
            helper.assertTrue(!idle.messages.isEmpty(), "an idle bot answered a status with nothing");
            helper.assertTrue(rig.runtime().view().goal() == null && rig.runtime().view().status() == null,
                "the executor of an idle bot shows " + rig.runtime().view().goal() + " " + rig.runtime().view().status());

            Feedback stranger = new Feedback();
            helper.assertTrue(run(server, rig.strangerSource(stranger), "aiplayer goal " + rig.name() + " make 4 planks") == 0,
                "a stranger could give the bot a goal");
            helper.assertTrue(!rig.runtime().hasGoal() && !stranger.messages.isEmpty(), "the refusal said nothing or the goal was set");
            rig.pass();
        });
    }

    /**
     * The owner's chat line that starts with the bot's name is a goal, by the real chat path (the packet handler and the
     * loader's chat event); the same line from another player is not. Both speakers are bodies, which are players.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 200)
    public static void onlyTheOwnersChatSetsAGoal(GameTestHelper helper) {
        floor(helper);
        Rig owner = Rig.spawn(helper, "cown", null, 200);
        Rig stranger = Rig.spawn(helper, "coth", null, 200);
        Rig bot = Rig.spawn(helper, "cbot", owner.profile(), 200);
        owner.alongside(stranger);
        owner.alongside(bot);
        bot.give(Items.OAK_LOG, 1);
        int[] tick = {0};
        owner.onEachTick(() -> {
            int t = ++tick[0];
            if (t == 3) say(stranger.body(), bot.name() + " make 4 oak planks");
            if (t == 8) {
                helper.assertTrue(!bot.runtime().hasGoal() && bot.runtime().lastResult() == null, "a stranger's chat line set a goal");
                say(owner.body(), bot.name() + ", make 4 oak planks");
            }
            if (t > 8 && bot.runtime().lastResult() != null) {
                GoalResult result = bot.runtime().lastResult();
                helper.assertTrue(result.status() == GoalResult.Status.SATISFIED, "the goal ended " + BotSpeech.result(result));
                helper.assertTrue(bot.count(Items.OAK_PLANKS) == 4, "expected 4 planks, got " + bot.count(Items.OAK_PLANKS));
                owner.pass();
            }
        });
    }

    // ---------------------------------------------------------------------------------------------------------
    // Equipping
    // ---------------------------------------------------------------------------------------------------------

    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 200)
    public static void equipPutsAHelmetOnTheHead(GameTestHelper helper) {
        floor(helper);
        Rig rig = Rig.spawn(helper, "equip", null, 200);
        rig.give(Items.IRON_HELMET, 1);
        rig.runtime().submit(new Goal.Equip(Map.of(EquipSlot.HEAD, ItemKey.of("iron_helmet"))));
        rig.onEachTick(() -> {
            GoalResult result = rig.runtime().lastResult();
            if (result == null) return;
            helper.assertTrue(result.status() == GoalResult.Status.SATISFIED, "the goal ended " + BotSpeech.result(result));
            helper.assertTrue(rig.body().getItemBySlot(EquipmentSlot.HEAD).is(Items.IRON_HELMET), "the helmet is not on the head");
            helper.assertTrue(rig.carried(Items.IRON_HELMET) == 0, "the helmet is on the head and still in the inventory");
            rig.assertHandsEmpty();
            rig.pass();
        });
    }

    // ---------------------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------------------

    private static void floor(GameTestHelper helper) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) helper.setBlock(x, FLOOR_Y, z, Blocks.STONE);
        }
    }

    /** The goal is to have {@code count} of the item: what a typed "make 4 stick" becomes. */
    private static boolean isHaveItem(Goal goal, String id, int count) {
        return goal instanceof Goal.HaveItem have && have.key().id().equals(id) && have.count() == count;
    }

    /** This test's arena as an absolute box. */
    private static AABB arenaBox(GameTestHelper helper) {
        BlockPos corner = helper.absolutePos(BlockPos.ZERO);
        return new AABB(corner.getX(), corner.getY(), corner.getZ(), corner.getX() + 16, corner.getY() + 16, corner.getZ() + 16);
    }

    /** One task ticked the way the executor ticks it, keeping how it ended. */
    private static final class TaskRun {
        private final Task<BotPlayer> task;
        TickResult result;

        TaskRun(Task<BotPlayer> task) {
            this.task = task;
        }

        /** One tick of the task, starting it first; true once it has ended. */
        boolean tick(BotPlayer body) {
            if (result != null && !(result instanceof TickResult.Running)) return true;
            if (task.state() == TaskState.PENDING) task.start(body);
            result = task.tick(body);
            return !(result instanceof TickResult.Running);
        }

        Failure failure() {
            return result instanceof TickResult.Failed failed ? failed.failure() : null;
        }
    }

    /** What the server wrote for a mock player that was in the world: its data, statistics and advancements. */
    private static void forgetPlayerFiles(MinecraftServer server, UUID id) {
        try {
            Files.deleteIfExists(server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(id + ".dat"));
            Files.deleteIfExists(server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(id + ".dat_old"));
            Files.deleteIfExists(server.getWorldPath(LevelResource.PLAYER_STATS_DIR).resolve(id + ".json"));
            Files.deleteIfExists(server.getWorldPath(LevelResource.PLAYER_ADVANCEMENTS_DIR).resolve(id + ".json"));
        } catch (IOException ignored) {
            // Leftover files of a test player are harmless; its id is random.
        }
    }

    /** A chat line as the client sends it: through the packet handler, which fires the loader's chat event. */
    private static void say(BotPlayer speaker, String line) {
        speaker.connection.handleChat(new ServerboundChatPacket(line, Instant.now(), 0L, null, new LastSeenMessages.Update(0, new BitSet())));
    }

    private static CommandSourceStack source(MinecraftServer server, ServerLevel level, int permission, net.minecraft.world.entity.Entity entity,
                                             CommandSource sink) {
        return new CommandSourceStack(sink, Vec3.ZERO, Vec2.ZERO, level, permission, "gt", Component.literal("gt"), server, entity);
    }

    private static int run(MinecraftServer server, CommandSourceStack source, String line) {
        try {
            return server.getCommands().getDispatcher().execute(line, source);
        } catch (CommandSyntaxException e) {
            throw new IllegalStateException("'" + line + "' did not parse: " + e.getMessage(), e);
        }
    }

    /** Keeps what a command source is told. */
    private static final class Feedback implements CommandSource {
        final List<String> messages = new ArrayList<>();

        boolean contains(String text) {
            return messages.stream().anyMatch(message -> message.contains(text));
        }

        @Override public void sendSystemMessage(Component message) { messages.add(message.getString()); }
        @Override public boolean acceptsSuccess() { return true; }
        @Override public boolean acceptsFailure() { return true; }
        @Override public boolean shouldInformAdmins() { return false; }
    }

    /**
     * One test body, spawned in the arena under a name unique to this run and found again by that name (a respawn
     * replaces the instance); despawned and forgotten when the test ends, passed or not.
     */
    private static final class Rig {
        private final GameTestHelper helper;
        private final BodyManager manager;
        private final String name;
        private final UUID id;
        private final GameProfile owner;
        private final ServerPlayer ownerPlayer;
        private final List<Rig> companions = new ArrayList<>();
        private final List<Runnable> endings = new ArrayList<>();

        private Rig(GameTestHelper helper, String tag, GameProfile owner, ServerPlayer ownerPlayer) {
            this.helper = helper;
            this.manager = BodyManager.of(helper.getLevel().getServer());
            this.name = "gt_" + tag + "_" + RUN_ID;
            this.id = BodyManager.uuidFor(name);
            this.owner = owner;
            this.ownerPlayer = ownerPlayer;
        }

        /** A body with no owner: the bot's answers go to the log only. */
        static Rig spawn(GameTestHelper helper, String tag, GameProfile owner, int deadlineTicks) {
            Rig rig = new Rig(helper, tag, owner, null);
            rig.respawn(helper.absoluteVec(new Vec3(8.5, FLOOR_TOP, 8.5)));
            helper.runAtTickTime(deadlineTicks, rig::cleanup);
            return rig;
        }

        /** A body with no owner, at a place of the arena's own coordinates instead of on its floor. */
        static Rig spawnAt(GameTestHelper helper, String tag, int deadlineTicks, Vec3 relative) {
            Rig rig = new Rig(helper, tag, null, null);
            rig.respawn(helper.absoluteVec(relative));
            helper.runAtTickTime(deadlineTicks, rig::cleanup);
            return rig;
        }

        /** A body owned by a player who is not in the world, so a command can come from its owner. */
        static Rig spawnOwned(GameTestHelper helper, String tag, int deadlineTicks) {
            ServerLevel level = helper.getLevel();
            GameProfile profile = new GameProfile(UUID.randomUUID(), "owner_" + tag);
            ServerPlayer player = new ServerPlayer(level.getServer(), level, profile, ClientInformation.createDefault());
            Vec3 at = helper.absoluteVec(new Vec3(8.5, FLOOR_TOP, 8.5));
            player.setPos(at.x, at.y, at.z);
            Rig rig = new Rig(helper, tag, profile, player);
            rig.respawn(at);
            helper.runAtTickTime(deadlineTicks, rig::cleanup);
            return rig;
        }

        /**
         * A body owned by a player who is in the world and whose respawn point is {@code relativeHome}: block safety then
         * refuses to let the body break anything within sixteen blocks of it, as around a real bed. The player and the
         * files the server wrote for it are gone when the test ends.
         */
        static Rig spawnInZoneOf(GameTestHelper helper, String tag, int deadlineTicks, Vec3 relative, BlockPos relativeHome) {
            ServerPlayer owner = helper.makeMockServerPlayerInLevel();
            owner.setRespawnPosition(Level.OVERWORLD, helper.absolutePos(relativeHome), 0.0F, true, false);
            Rig rig = new Rig(helper, tag, owner.getGameProfile(), owner);
            rig.atEnd(() -> {
                MinecraftServer server = helper.getLevel().getServer();
                if (server.getPlayerList().getPlayers().contains(owner)) server.getPlayerList().remove(owner);
                forgetPlayerFiles(server, owner.getUUID());
            });
            rig.respawn(helper.absoluteVec(relative));
            helper.runAtTickTime(deadlineTicks, rig::cleanup);
            return rig;
        }

        String name() {
            return name;
        }

        UUID id() {
            return id;
        }

        GameProfile profile() {
            return new GameProfile(id, name);
        }

        MinecraftServer server() {
            return helper.getLevel().getServer();
        }

        BodyManager manager() {
            return manager;
        }

        BotPlayer body() {
            BotPlayer current = manager.get(id);
            if (current == null) throw new IllegalStateException("no body " + name + " is logged in");
            return current;
        }

        BotRuntime runtime() {
            BotRuntime runtime = BotRuntime.of(body());
            if (runtime == null) throw new IllegalStateException(name + " has the brain " + body().brain() + ", not a runtime");
            return runtime;
        }

        BotPlayer respawn(Vec3 absolute) {
            try {
                return manager.spawn(name, owner, helper.getLevel(), absolute);
            } catch (BodyException e) {
                throw new IllegalStateException("could not spawn " + name + ": " + e.getMessage(), e);
            }
        }

        CommandSourceStack ownerSource(CommandSource sink) {
            return source(server(), helper.getLevel(), 0, ownerPlayer, sink);
        }

        CommandSourceStack strangerSource(CommandSource sink) {
            ServerLevel level = helper.getLevel();
            ServerPlayer stranger = new ServerPlayer(level.getServer(), level, new GameProfile(UUID.randomUUID(), "stranger"),
                ClientInformation.createDefault());
            return source(server(), level, 0, stranger, sink);
        }

        void give(Item item, int count) {
            body().getInventory().add(new ItemStack(item, count));
        }

        /** What the inventory's carried storage holds of the item (not what is worn). */
        int carried(Item item) {
            int total = 0;
            for (int slot = 0; slot < 36; slot++) {
                ItemStack stack = body().getInventory().getItem(slot);
                if (stack.is(item)) total += stack.getCount();
            }
            return total;
        }

        int count(Item item) {
            return body().getInventory().countItem(item);
        }

        /** The first stack of the item in the inventory, or an empty stack. */
        ItemStack firstStack(Item item) {
            for (int slot = 0; slot < body().getInventory().getContainerSize(); slot++) {
                ItemStack stack = body().getInventory().getItem(slot);
                if (stack.is(item)) return stack;
            }
            return ItemStack.EMPTY;
        }

        /** The cursor and every crafting grid slot of the menu the body has open are empty. */
        void assertHandsEmpty() {
            BotPlayer body = body();
            helper.assertTrue(body.containerMenu == body.inventoryMenu, "a menu is still open: " + body.containerMenu);
            helper.assertTrue(body.containerMenu.getCarried().isEmpty(), "the cursor holds " + body.containerMenu.getCarried());
            for (int slot = 1; slot <= 4; slot++) {
                helper.assertTrue(body.inventoryMenu.getSlot(slot).getItem().isEmpty(),
                    "the 2x2 grid slot " + slot + " holds " + body.inventoryMenu.getSlot(slot).getItem());
            }
        }

        /** Every crafting table within three blocks of the body (and one up or down), as absolute positions. */
        List<BlockPos> tablesNearBody() {
            BlockPos at = body().blockPosition();
            ServerLevel level = helper.getLevel();
            List<BlockPos> found = new ArrayList<>();
            for (BlockPos pos : BlockPos.betweenClosed(at.offset(-3, -1, -3), at.offset(3, 1, 3))) {
                if (level.getBlockState(pos).is(Blocks.CRAFTING_TABLE)) found.add(pos.immutable());
            }
            return found;
        }

        /** Every crafting table block in the arena, as relative positions. */
        List<BlockPos> tablesInArena() {
            List<BlockPos> found = new ArrayList<>();
            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        if (helper.getBlockState(new BlockPos(x, y, z)).is(Blocks.CRAFTING_TABLE)) found.add(new BlockPos(x, y, z));
                    }
                }
            }
            return found;
        }

        void onEachTick(Runnable action) {
            helper.onEachTick(() -> {
                try {
                    action.run();
                } catch (RuntimeException | Error e) {
                    cleanup();
                    throw e;
                }
            });
        }

        void alongside(Rig other) {
            companions.add(other);
        }

        /** Something to undo when the test ends, passed or not (a global the test set). */
        void atEnd(Runnable undo) {
            endings.add(undo);
        }

        void pass() {
            cleanup();
            helper.succeed();
        }

        void cleanup() {
            for (Runnable undo : endings) undo.run();
            endings.clear();
            TableSites.clearRadius(id);
            for (Rig companion : companions) companion.cleanup();
            BotPlayer current = manager.get(id);
            if (current != null) manager.despawn(current);
            try {
                manager.deleteSaved(id);
            } catch (BodyException ignored) {
                // Still logged in only if the despawn did not take; the unique name keeps that from mattering.
            }
            BotMemory.forget(id);
        }
    }
}
