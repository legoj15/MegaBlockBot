package com.legoj15.megablockbot.game.gametest;

import static com.legoj15.megablockbot.game.gametest.BotNavGameTests.FLOOR_Y;
import static com.legoj15.megablockbot.game.gametest.BotNavGameTests.room;

import com.legoj15.megablockbot.MegaBlockBot;
import com.legoj15.megablockbot.core.ItemKey;
import com.legoj15.megablockbot.core.Pos;
import com.legoj15.megablockbot.core.ToolClass;
import com.legoj15.megablockbot.core.ToolTier;
import com.legoj15.megablockbot.core.goal.Goal;
import com.legoj15.megablockbot.core.plan.NodeSpec;
import com.legoj15.megablockbot.core.task.Failure;
import com.legoj15.megablockbot.core.task.Task;
import com.legoj15.megablockbot.core.task.TaskState;
import com.legoj15.megablockbot.core.task.TickResult;
import com.legoj15.megablockbot.game.body.BodyException;
import com.legoj15.megablockbot.game.body.BodyInput;
import com.legoj15.megablockbot.game.body.BodyManager;
import com.legoj15.megablockbot.game.body.BotBrain;
import com.legoj15.megablockbot.game.body.BotPlayer;
import com.legoj15.megablockbot.game.body.DeathRecord;
import com.legoj15.megablockbot.game.gametest.BotNavGameTests.NavRun;
import com.legoj15.megablockbot.game.nav.BodyInteractionManager;
import com.legoj15.megablockbot.game.nav.BotNavigator;
import com.legoj15.megablockbot.game.plan.BotMemory;
import com.legoj15.megablockbot.game.safety.BlockSafety;
import com.legoj15.megablockbot.game.task.BreakRules;
import com.legoj15.megablockbot.game.task.MineTask;
import com.legoj15.megablockbot.game.task.TaskFeature;
import com.legoj15.megablockbot.game.task.TaskRegistry;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The tasks that move and mine (design 3.4, issue 12): what a plan leaf becomes in the world, and the typed result it
 * ends with. Each test builds a leaf the way the planner or the executor would, gets its task from the
 * {@link TaskRegistry}, and runs it on a real body in the 16 x 16 x 16 arena with a small brain that ticks the task
 * and then the navigator (the runtime's order: a command a task gives starts driving the body the same tick). Each
 * asserts the task's end state and result, and what the body holds or where it stands, inside a tick budget.
 *
 * <p>Floors are barrier wherever a test places the only blocks it wants mined, so nothing else is breakable; tests that
 * must keep the body in use barrier walls too (see {@link BotNavGameTests}). Bodies are named per run and removed when a
 * test ends.
 */
public final class MoveAndMineGameTests {
    private static final String ITEM = "minecraft:";

    /**
     * The break rules of these tests, per arena: whether breaking is allowed inside it. Every test that runs a task
     * registers its arena, and anything outside every registered arena may not be broken. That keeps the tests apart:
     * they run side by side in one world, and the engine finds a block of the kind it is told to mine in the next
     * arena's floor as well as in its own (it cannot get there, but it tries, and blacklists the wrong block).
     */
    private static final Map<AABB, Boolean> ARENAS = new ConcurrentHashMap<>();

    private MoveAndMineGameTests() {}

    // ---------------------------------------------------------------------------------------------------------
    // navigate and reach
    // ---------------------------------------------------------------------------------------------------------

    /** A navigate leaf with a Reach goal walks the body across the arena and completes with it standing on the spot. */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 780)
    public static void navigateCrossesTheArena(GameTestHelper helper) {
        room(helper, 3);
        BlockPos goal = helper.absolutePos(new BlockPos(14, FLOOR_Y + 1, 7));
        NodeSpec leaf = NodeSpec.leaf(new Goal.Reach(dimension(helper), pos(goal), 0), TaskFeature.NAVIGATE, Map.of(), "");
        Job job = Job.start(helper, "mnav", new Vec3(1.5, FLOOR_Y + 1, 7.5), 780, leaf);
        job.run.each(600, () -> {
            failIfFailed(helper, job);
            if (job.task.state() != TaskState.COMPLETED) return;
            helper.assertTrue(job.body().blockPosition().equals(goal), "completed at " + job.body().blockPosition() + ", goal " + goal);
            job.run.pass();
        });
    }

    /**
     * The executor's reach node: position and dimension in the parameters, a radius in the goal. It completes with the
     * body within that radius of the position, which it never stands on (it is a block of the wall).
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 780)
    public static void reachNodeStopsWithinItsRadius(GameTestHelper helper) {
        room(helper, 3);
        BlockPos wallBlock = helper.absolutePos(new BlockPos(14, FLOOR_Y + 1, 7));
        helper.setBlock(14, FLOOR_Y + 1, 7, Blocks.BARRIER);
        helper.setBlock(14, FLOOR_Y + 2, 7, Blocks.BARRIER);
        Map<String, String> params = Map.of("pos", pos(wallBlock).encode(), "dimension", dimension(helper));
        NodeSpec leaf = NodeSpec.leaf(new Goal.Reach(dimension(helper), pos(wallBlock), 4), TaskFeature.REACH, params, "");
        Job job = Job.start(helper, "mreach", new Vec3(1.5, FLOOR_Y + 1, 7.5), 780, leaf);
        job.run.each(600, () -> {
            failIfFailed(helper, job);
            if (job.task.state() != TaskState.COMPLETED) return;
            double away = Math.sqrt(job.body().blockPosition().distSqr(wallBlock));
            helper.assertTrue(away <= 4.0, "completed " + away + " blocks from the position, the radius is 4");
            job.run.pass();
        });
    }

    /**
     * A dirt wall splits the arena and the break rules forbid breaking in it: there is no way to the far side, so the
     * task fails with a typed reason (Unreachable, or Protected) and the wall stands.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 2700)
    public static void navigateToAWalledOffPointFails(GameTestHelper helper) {
        room(helper, 5);
        for (int x = 1; x <= 14; x++) {
            for (int y = FLOOR_Y + 1; y <= FLOOR_Y + 3; y++) helper.setBlock(x, y, 8, Blocks.DIRT);
        }
        BlockPos goal = helper.absolutePos(new BlockPos(7, FLOOR_Y + 1, 13));
        NodeSpec leaf = NodeSpec.leaf(new Goal.Reach(dimension(helper), pos(goal), 0), TaskFeature.NAVIGATE, Map.of(), "");
        Job job = Job.start(helper, "mwall", new Vec3(7.5, FLOOR_Y + 1, 3.5), 2700, leaf);
        job.body().getInventory().setItem(0, new ItemStack(Items.IRON_SHOVEL));
        protect(helper);
        job.run.each(2400, () -> {
            helper.assertTrue(job.task.state() != TaskState.COMPLETED, "reached a point behind a wall it may not break");
            if (job.task.state() != TaskState.FAILED) return;
            Failure failure = job.task.failure();
            helper.assertTrue(failure instanceof Failure.Unreachable || failure instanceof Failure.Protected, "failed as " + failure);
            for (int x = 1; x <= 14; x++) {
                for (int y = FLOOR_Y + 1; y <= FLOOR_Y + 3; y++) helper.assertBlockPresent(Blocks.DIRT, new BlockPos(x, y, 8));
            }
            job.run.pass();
        });
    }

    // ---------------------------------------------------------------------------------------------------------
    // mine
    // ---------------------------------------------------------------------------------------------------------

    /** Two oak logs stand in the arena and the goal is to hold two: the task completes with both in the inventory. */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 4500)
    public static void mineTwoOakLogs(GameTestHelper helper) {
        room(helper, 3);
        helper.setBlock(4, FLOOR_Y + 1, 11, Blocks.OAK_LOG);
        helper.setBlock(11, FLOOR_Y + 1, 11, Blocks.OAK_LOG);
        Job job = Job.start(helper, "mlogs", new Vec3(7.5, FLOOR_Y + 1, 4.5), 4500, mineLeaf("oak_log", 2, "oak_log", Map.of()));
        job.run.each(4200, () -> {
            failIfFailed(helper, job);
            if (job.task.state() != TaskState.COMPLETED) return;
            int logs = job.body().getInventory().countItem(Items.OAK_LOG);
            helper.assertTrue(logs >= 2, "completed holding " + logs + " oak logs");
            helper.assertBlockPresent(Blocks.AIR, new BlockPos(4, FLOOR_Y + 1, 11));
            helper.assertBlockPresent(Blocks.AIR, new BlockPos(11, FLOOR_Y + 1, 11));
            job.run.pass();
        });
    }

    /**
     * Cobblestone from stone with a wooden pickaxe, the way the planner writes the leaf: the item differs from the
     * block, and the tool parameters are met by the pickaxe in the hotbar.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 3600)
    public static void mineCobblestoneFromStone(GameTestHelper helper) {
        room(helper, 3, Blocks.BARRIER);
        for (int x = 5; x <= 8; x++) helper.setBlock(x, FLOOR_Y + 1, 10, Blocks.STONE);
        Map<String, String> tool = Map.of("toolClass", "PICKAXE", "minTier", "WOOD");
        Job job = Job.start(helper, "mstone", new Vec3(6.5, FLOOR_Y + 1, 4.5), 3600, mineLeaf("cobblestone", 3, "stone", tool));
        job.body().getInventory().setItem(0, new ItemStack(Items.WOODEN_PICKAXE));
        job.run.each(3300, () -> {
            failIfFailed(helper, job);
            if (job.task.state() != TaskState.COMPLETED) return;
            int held = job.body().getInventory().countItem(Items.COBBLESTONE);
            helper.assertTrue(held >= 3, "completed holding " + held + " cobblestone");
            helper.assertTrue(job.body().getInventory().countItem(Items.STONE) == 0, "holds stone, which a pickaxe does not drop");
            job.run.pass();
        });
    }

    /**
     * A leaf that names a tool the body lacks fails MissingTool before anything is broken: with empty hands, with a tool
     * of too low a tier, and with a tool of the wrong class.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 100)
    public static void mineWithoutTheToolFailsMissingTool(GameTestHelper helper) {
        room(helper, 3, Blocks.BARRIER);
        helper.setBlock(5, FLOOR_Y + 1, 5, Blocks.STONE);
        NavRun run = NavRun.start(helper, "mtool", new Vec3(4.5, FLOOR_Y + 1, 4.5), 100);
        try {
            BotPlayer body = run.body();
            // Empty hands: a pickaxe is needed.
            Task<BotPlayer> bare = startedMine(body, "cobblestone", "stone", Map.of("toolClass", "PICKAXE", "minTier", "WOOD"));
            assertMissingTool(helper, bare, ToolClass.PICKAXE, ToolTier.WOOD);
            // A wooden pickaxe is not a stone one.
            body.getInventory().setItem(0, new ItemStack(Items.WOODEN_PICKAXE));
            Task<BotPlayer> weak = startedMine(body, "iron_ore", "iron_ore", Map.of("toolClass", "PICKAXE", "minTier", "STONE"));
            assertMissingTool(helper, weak, ToolClass.PICKAXE, ToolTier.STONE);
            // A pickaxe is not an axe.
            Task<BotPlayer> wrong = startedMine(body, "oak_log", "oak_log", Map.of("toolClass", "AXE", "minTier", "WOOD"));
            assertMissingTool(helper, wrong, ToolClass.AXE, ToolTier.WOOD);

            BodyInteractionManager manager = (BodyInteractionManager) body.getInteractionManager();
            helper.assertTrue(manager.blocksBroken() == 0, "broke " + manager.blocksBroken() + " blocks while failing for the tool");
            helper.assertBlockPresent(Blocks.STONE, new BlockPos(5, FLOOR_Y + 1, 5));
            helper.assertTrue(BotNavigator.of(body).state() == BotNavigator.State.IDLE, "the navigator was started");
        } catch (RuntimeException | Error e) {
            run.cleanup();
            throw e;
        }
        run.pass();
    }

    /**
     * A body whose carried storage is full of another item (set up once, before the task starts: every slot an
     * unstackable pickaxe) is given a mine leaf for a log that stands beside it: the step ends {@code NoRoom} naming the
     * log's item, and the log still stands. No block is broken for a drop the body could not pick up.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 100)
    public static void mineWithAFullInventoryFailsNoRoom(GameTestHelper helper) {
        room(helper, 3, Blocks.BARRIER);
        helper.setBlock(6, FLOOR_Y + 1, 5, Blocks.OAK_LOG);
        NavRun run = NavRun.start(helper, "mfull", new Vec3(5.5, FLOOR_Y + 1, 5.5), 100);
        try {
            BotPlayer body = run.body();
            for (int slot = 0; slot < 36; slot++) body.getInventory().setItem(slot, new ItemStack(Items.STONE_PICKAXE));
            Task<BotPlayer> task = startedMine(body, "oak_log", "oak_log", Map.of());
            helper.assertTrue(task.state() == TaskState.FAILED, "the task is " + task.state() + ", not FAILED");
            if (!(task.failure() instanceof Failure.NoRoom noRoom)) {
                helper.fail("failed as " + task.failure());
                return;
            }
            helper.assertTrue(noRoom.key().equals(ItemKey.of("oak_log")), "NoRoom names " + noRoom.key());
            helper.assertBlockPresent(Blocks.OAK_LOG, new BlockPos(6, FLOOR_Y + 1, 5));
            BodyInteractionManager manager = (BodyInteractionManager) body.getInteractionManager();
            helper.assertTrue(manager.blocksBroken() == 0, "broke " + manager.blocksBroken() + " blocks while failing for room");
            helper.assertTrue(BotNavigator.of(body).state() == BotNavigator.State.IDLE, "the navigator was started");
        } catch (RuntimeException | Error e) {
            run.cleanup();
            throw e;
        }
        run.pass();
    }

    /** The goal's count is already held: the task completes on its first tick and touches nothing. */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 100)
    public static void mineWhatIsAlreadyHeldCompletesAtOnce(GameTestHelper helper) {
        room(helper, 3, Blocks.BARRIER);
        for (int x = 4; x <= 6; x++) helper.setBlock(x, FLOOR_Y + 1, 5, Blocks.STONE);
        Job job = Job.start(helper, "mheld", new Vec3(5.5, FLOOR_Y + 1, 3.5), 100, mineLeaf("cobblestone", 3, "stone", Map.of()));
        job.body().getInventory().setItem(2, new ItemStack(Items.COBBLESTONE, 3));
        job.run.each(20, () -> {
            failIfFailed(helper, job);
            if (job.task.state() != TaskState.COMPLETED) return;
            helper.assertTrue(job.run.ticks <= 3, "completed only at tick " + job.run.ticks);
            BodyInteractionManager manager = (BodyInteractionManager) job.body().getInteractionManager();
            helper.assertTrue(manager.blocksBroken() == 0, "broke " + manager.blocksBroken() + " blocks with the goal already met");
            for (int x = 4; x <= 6; x++) helper.assertBlockPresent(Blocks.STONE, new BlockPos(x, FLOOR_Y + 1, 5));
            helper.assertTrue(job.body().getInventory().countItem(Items.COBBLESTONE) == 3, "the held count changed");
            job.run.pass();
        });
    }

    /**
     * A block with a height range (the planner's minY, maxY and bestY): when none is in range, the body digs down to the
     * best height first and mines from there. The arena is solid stone up to eight blocks above the floor and the body
     * starts on top; no coal exists until the body has dug most of the way down to the best height, where the test puts
     * one in the rock beside the shaft. The task completes with the coal, having gone down there to find it.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 6000)
    public static void mineOreDiggingDownToItsBestHeight(GameTestHelper helper) {
        room(helper, 14, Blocks.BARRIER);
        int top = FLOOR_Y + 8;
        int bestY = FLOOR_Y + 2;
        for (int x = 1; x <= 14; x++) {
            for (int z = 1; z <= 14; z++) {
                for (int y = FLOOR_Y + 1; y <= top; y++) helper.setBlock(x, y, z, Blocks.STONE);
            }
        }
        BlockPos corner = helper.absolutePos(BlockPos.ZERO);
        Map<String, String> range = Map.of("toolClass", "PICKAXE", "minTier", "WOOD",
            "minY", Integer.toString(corner.getY() + FLOOR_Y + 1), "maxY", Integer.toString(corner.getY() + top),
            "bestY", Integer.toString(corner.getY() + bestY));
        Job job = Job.start(helper, "mdeep", new Vec3(7.5, top + 1, 7.5), 6000, mineLeaf("coal", 1, "coal_ore", range));
        job.body().getInventory().setItem(0, new ItemStack(Items.WOODEN_PICKAXE));
        boolean[] placed = {false};
        double[] lowest = {Double.MAX_VALUE};
        job.run.each(5400, () -> {
            failIfFailed(helper, job);
            double feet = helper.relativeVec(job.body().position()).y;
            lowest[0] = Math.min(lowest[0], feet);
            if (!placed[0] && feet <= bestY + 2.0) {
                // The body has dug most of the way down (and is still on its way: the walk to the best height ends when
                // its feet enter that layer, a block lower): now there is something to find at that height, in the rock.
                helper.setBlock(9, bestY, 7, Blocks.COAL_ORE);
                placed[0] = true;
            }
            if (job.task.state() != TaskState.COMPLETED) return;
            helper.assertTrue(placed[0], "completed without ever digging down to the best height (lowest feet y " + lowest[0] + ")");
            helper.assertTrue(job.body().getInventory().countItem(Items.COAL) >= 1, "completed without coal");
            job.run.pass();
        });
    }

    /**
     * No block of the kind anywhere in a sealed arena: the search ends within its (small, for the test) budget with
     * NoSource naming the item, the block, the source id the planner chose and how far it looked. The block is ancient
     * debris, which does not generate in the overworld: the world around the arenas is natural terrain, and a diamond ore
     * in it behind an arena's wall is a block the task knows of and cannot get at, which is {@code NoSource} naming that
     * source too.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 2700)
    public static void mineWhereNothingGrowsFailsNoSource(GameTestHelper helper) {
        room(helper, 3, Blocks.BARRIER);
        MineTask.Search small = new MineTask.Search(8, 100, 24, 3);
        Job job = Job.start(helper, "mnone", new Vec3(7.5, FLOOR_Y + 1, 7.5), 2700,
            new MineTask(mineLeaf("ancient_debris", 1, "ancient_debris", Map.of()), Map.of(), small));
        job.run.each(2100, () -> {
            helper.assertTrue(job.task.state() != TaskState.COMPLETED, "found ancient debris in a sealed arena");
            if (job.task.state() != TaskState.FAILED) return;
            if (!(job.task.failure() instanceof Failure.NoSource none)) {
                helper.fail("failed as " + job.task.failure());
                return;
            }
            helper.assertTrue(none.key().equals(ItemKey.of("ancient_debris")), "NoSource names " + none.key());
            helper.assertTrue(none.source().equals("mine:minecraft:ancient_debris"), "NoSource carries source '" + none.source() + "'");
            MegaBlockBot.LOGGER.info("Move-and-mine GameTest: NoSource after {} ticks: {}", job.run.ticks, none.detail());
            job.run.pass();
        });
    }

    /**
     * What a restart keeps: a task that is given the checkpoint of one that had already explored its whole budget does
     * not go exploring again; it says NoSource at once.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 900)
    public static void aRestartedSearchKeepsWhatItSpent(GameTestHelper helper) {
        room(helper, 3, Blocks.BARRIER);
        MineTask.Search small = new MineTask.Search(8, 100, 24, 3);
        Vec3 start = new Vec3(7.5, FLOOR_Y + 1, 7.5);
        BlockPos at = helper.absolutePos(BlockPos.containing(start));
        Map<String, String> checkpoint = Map.of("dim", dimension(helper), "origin", pos(at).encode(), "explored", "24");
        Job job = Job.start(helper, "mrestart", start, 900,
            new MineTask(mineLeaf("ancient_debris", 1, "ancient_debris", Map.of()), checkpoint, small));
        job.run.each(600, () -> {
            if (job.task.state() == TaskState.COMPLETED) helper.fail("found ancient debris in a sealed arena");
            if (job.task.state() != TaskState.FAILED) return;
            if (!(job.task.failure() instanceof Failure.NoSource none)) {
                helper.fail("failed as " + job.task.failure());
                return;
            }
            // Not the sealed arena's quick failure to explore: the budget the checkpoint carried is what ended the search.
            helper.assertTrue(none.key().equals(ItemKey.of("ancient_debris")), "NoSource names " + none.key());
            helper.assertTrue(none.source().equals("mine:minecraft:ancient_debris"), "NoSource carries source '" + none.source() + "'");
            helper.assertTrue(job.run.ticks <= 40, "took " + job.run.ticks + " ticks: it searched again");
            helper.assertTrue(job.task.checkpoint().containsKey("origin"), "no checkpoint to hand on: " + job.task.checkpoint());
            job.run.pass();
        });
    }

    /**
     * A body that is no longer the navigator's while the task explores for a block that does not exist -- despawned
     * through the body manager and brought back through it, as other tests do it, a new instance under the same name
     * whose brain goes on ticking the same task -- says nothing about the leaf's source, so the task does not end
     * NoSource (which would have the executor exclude that source for the rest of the goal): it ends Unreachable, the
     * failure the reach task answers the navigator with.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 700)
    public static void aBodyDespawnedWhileExploringDoesNotFailNoSource(GameTestHelper helper) {
        // Open at the sides (a floor, no walls): the first exploring stretch has somewhere to go and keeps running,
        // so the body is taken out while the task is exploring and not between commands of its own.
        room(helper, 0, Blocks.BARRIER);
        // A step long enough that the first exploring stretch is still running when the body goes.
        MineTask.Search longStep = new MineTask.Search(64, 600, 256, 12);
        Vec3 start = new Vec3(7.5, FLOOR_Y + 1, 7.5);
        Job job = Job.start(helper, "mdesp", start, 700,
            new MineTask(mineLeaf("ancient_debris", 1, "ancient_debris", Map.of()), Map.of(), longStep));
        boolean[] gone = {false};
        job.run.each(600, () -> {
            if (!gone[0]) {
                failIfFailed(helper, job);
                // While nothing is found the commands alternate mine, explore, mine...: the second one the navigator
                // works on is the first exploring stretch, and it is the one the body is taken out under.
                BotNavigator nav = BotNavigator.of(job.body());
                if (nav.serial() != 2 || nav.state() != BotNavigator.State.WORKING) return;
                gone[0] = true;
                // Set-up, once, while the task explores: the body is taken out through the manager and brought back
                // through it, so the command the task gave runs on an instance the navigator no longer owns.
                BodyManager manager = BodyManager.of(helper.getLevel().getServer());
                String name = job.body().getGameProfile().getName();
                manager.despawn(job.body());
                try {
                    manager.spawn(name, null, helper.getLevel(), start, 0.0F, () -> new TaskBrain(job.task));
                } catch (BodyException e) {
                    throw new IllegalStateException("could not bring the body " + name + " back: " + e.getMessage(), e);
                }
                return;
            }
            if (job.task.state() == TaskState.RUNNING) return;
            helper.assertTrue(job.task.state() == TaskState.FAILED, "the task is " + job.task.state());
            Failure failure = job.task.failure();
            helper.assertTrue(!(failure instanceof Failure.NoSource), "the body was lost and the task excluded its source for it");
            helper.assertTrue(failure instanceof Failure.Unreachable, "failed as " + failure);
            job.run.pass();
        });
    }

    /**
     * Emerald blocks stand in the arena but the break rules forbid every break in it: the task fails Protected, naming
     * one of those blocks, and nothing is broken. (A block no other test places: the check for forbidden blocks looks at
     * everything loaded around the body, and the next arena's stone floor would count as one the rules allow.)
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 6000)
    public static void mineWhatTheRulesForbidFailsProtected(GameTestHelper helper) {
        room(helper, 3, Blocks.BARRIER);
        for (int x = 5; x <= 7; x++) helper.setBlock(x, FLOOR_Y + 1, 9, Blocks.EMERALD_BLOCK);
        Job job = Job.start(helper, "mprot", new Vec3(6.5, FLOOR_Y + 1, 4.5), 6000, mineLeaf("emerald_block", 2, "emerald_block", Map.of()));
        protect(helper);
        job.run.each(5400, () -> {
            helper.assertTrue(job.task.state() != TaskState.COMPLETED, "mined what the rules forbid");
            if (job.task.state() != TaskState.FAILED) return;
            if (!(job.task.failure() instanceof Failure.Protected protectedBlock)) {
                helper.fail("failed as " + job.task.failure());
                return;
            }
            BlockPos at = new BlockPos(protectedBlock.pos().x(), protectedBlock.pos().y(), protectedBlock.pos().z()).subtract(helper.absolutePos(BlockPos.ZERO));
            helper.assertTrue(at.getY() == FLOOR_Y + 1 && at.getZ() == 9 && at.getX() >= 5 && at.getX() <= 7,
                "Protected names " + at + " (relative), which is not one of the three emerald blocks");
            for (int x = 5; x <= 7; x++) helper.assertBlockPresent(Blocks.EMERALD_BLOCK, new BlockPos(x, FLOOR_Y + 1, 9));
            helper.assertTrue(job.body().getInventory().countItem(Items.EMERALD_BLOCK) == 0, "holds emerald blocks");
            job.run.pass();
        });
    }

    /**
     * A log the break rules forbid for this body only (its own rules, as the navigator is armed with them: the general
     * rules allow breaking in the arena): the task answers {@code Protected} without trying to break it, naming the log.
     * The scan for forbidden blocks asks the per-body rules; with only the general ones it would see a breakable log and
     * send the task exploring instead.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 6000)
    public static void aLogTheBodyItselfMayNotBreakFailsProtected(GameTestHelper helper) {
        room(helper, 3, Blocks.BARRIER);
        helper.setBlock(7, FLOOR_Y + 1, 10, Blocks.BAMBOO_BLOCK);
        BlockPos log = new BlockPos(7, FLOOR_Y + 1, 10);
        Job job = Job.start(helper, "mbody", new Vec3(7.5, FLOOR_Y + 1, 4.5), 6000, mineLeaf("bamboo_block", 1, "bamboo_block", Map.of()));
        // Set-up, once before the command starts driving anything, undone when the test ends however it ends: this
        // body's own rules refuse the log, as SafetyFeature installs rules that ask block safety per body (restored the
        // same way); every other body keeps block safety's answer, so tests running side by side are not touched.
        UUID body = job.run.id;
        BreakRules.setBodyRules((asked, level, at, state) -> {
            if (asked.getUUID().equals(body) && state.is(Blocks.BAMBOO_BLOCK)) return false;
            return BlockSafety.of(level.getServer()).mayBreak(asked, level, at, state).allowed();
        });
        job.run.atEnd(() -> BreakRules.setBodyRules((asked, level, at, state) ->
            BlockSafety.of(level.getServer()).mayBreak(asked, level, at, state).allowed()));
        job.run.each(5400, () -> {
            helper.assertTrue(job.task.state() != TaskState.COMPLETED, "mined a log the body itself may not break");
            if (job.task.state() != TaskState.FAILED) return;
            if (!(job.task.failure() instanceof Failure.Protected protectedBlock)) {
                helper.fail("failed as " + job.task.failure());
                return;
            }
            BlockPos at = new BlockPos(protectedBlock.pos().x(), protectedBlock.pos().y(), protectedBlock.pos().z())
                .subtract(helper.absolutePos(BlockPos.ZERO));
            helper.assertTrue(at.equals(log), "Protected names " + at + " (relative), not the bamboo block at " + log);
            helper.assertBlockPresent(Blocks.BAMBOO_BLOCK, log);
            BodyInteractionManager manager = (BodyInteractionManager) job.body().getInteractionManager();
            helper.assertTrue(manager.blocksBroken() == 0, "broke " + manager.blocksBroken() + " blocks trying for the log");
            helper.assertTrue(!manager.isMining(), "a break is still in progress");
            helper.assertTrue(job.body().getInventory().countItem(Items.BAMBOO_BLOCK) == 0, "holds bamboo blocks");
            job.run.pass();
        });
    }

    /** A target in another dimension: dimension travel is not built, so the task fails Unreachable and says why, going nowhere. */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 600)
    public static void navigateToAnotherDimensionFailsUnreachable(GameTestHelper helper) {
        room(helper, 3);
        BlockPos target = helper.absolutePos(new BlockPos(7, FLOOR_Y + 1, 7));
        NodeSpec leaf = NodeSpec.leaf(new Goal.Reach("minecraft:the_nether", pos(target), 0), TaskFeature.NAVIGATE, Map.of(), "");
        Vec3 start = new Vec3(2.5, FLOOR_Y + 1, 2.5);
        Job job = Job.start(helper, "mdim", start, 600, leaf);
        job.run.each(300, () -> {
            helper.assertTrue(job.task.state() != TaskState.COMPLETED, "reached a point in another dimension");
            if (job.task.state() != TaskState.FAILED) return;
            if (!(job.task.failure() instanceof Failure.Unreachable unreachable)) {
                helper.fail("failed as " + job.task.failure());
                return;
            }
            helper.assertTrue(unreachable.target().equals(pos(target).encode()),
                "Unreachable names " + unreachable.target() + ", not the target " + pos(target).encode());
            helper.assertTrue(helper.relativeVec(job.body().position()).distanceTo(start) < 0.5, "the body moved to " + job.body().position());
            job.run.pass();
        });
    }

    /**
     * Aborting a running mine lets go of the body: while it was breaking a log, no movement input, no break in progress
     * in the manager or the game mode, the navigator idle, the log still standing, and the body at rest a few ticks later.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 1800)
    public static void abortingAMineStopsTheBody(GameTestHelper helper) {
        room(helper, 3);
        helper.setBlock(7, FLOOR_Y + 1, 10, Blocks.OAK_LOG);
        Job job = Job.start(helper, "mabort", new Vec3(7.5, FLOOR_Y + 1, 4.5), 1800, mineLeaf("oak_log", 1, "oak_log", Map.of()));
        int[] abortedAt = {-1};
        int[] miningTicks = {0};
        job.run.each(1500, () -> {
            BotPlayer body = job.body();
            BodyInteractionManager manager = (BodyInteractionManager) body.getInteractionManager();
            if (abortedAt[0] < 0) {
                failIfFailed(helper, job);
                helper.assertTrue(job.task.state() != TaskState.COMPLETED, "mined the log before it could be aborted");
                if (!manager.isMining()) return;
                // The break is progress that neither position nor inventory shows: the task says it is waiting on it.
                // (It reads the navigator at the start of its tick, so a break begun this tick shows from the next.)
                if (++miningTicks[0] < 3) return;
                helper.assertTrue(job.task.waiting(), "the task is breaking a block and does not say it is waiting");
                job.task.abort(body, "test");
                helper.assertTrue(!job.task.waiting(), "an aborted task still says it is waiting");
                abortedAt[0] = job.run.ticks;
                helper.assertTrue(job.task.state() == TaskState.CANCELLED, "the aborted task is " + job.task.state());
                return;
            }
            if (job.run.ticks < abortedAt[0] + 8) return;
            BodyInput input = body.input();
            helper.assertTrue(input.forward == 0.0F && input.strafe == 0.0F && !input.jump && !input.sneak && !input.sprint,
                "movement input left on: forward " + input.forward + " strafe " + input.strafe + " jump " + input.jump
                    + " sneak " + input.sneak + " sprint " + input.sprint);
            helper.assertTrue(!manager.isMining(), "the interaction manager is still breaking");
            helper.assertTrue(!body.gameMode.isDestroyingBlock, "the game mode is still working on a block");
            helper.assertTrue(BotNavigator.of(body).state() == BotNavigator.State.IDLE, "the navigator is " + BotNavigator.of(body).state());
            helper.assertTrue(body.getDeltaMovement().horizontalDistanceSqr() < 1.0E-4, "still moving: " + body.getDeltaMovement());
            helper.assertBlockPresent(Blocks.OAK_LOG, new BlockPos(7, FLOOR_Y + 1, 10));
            job.run.pass();
        });
    }

    /**
     * A paused mine lets go of the body like an abort does, and a resumed one picks the work up again from the world
     * as it is: it still finishes with the logs in the inventory.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 4500)
    public static void aPausedMineResumesAndFinishes(GameTestHelper helper) {
        room(helper, 3);
        helper.setBlock(4, FLOOR_Y + 1, 11, Blocks.OAK_LOG);
        helper.setBlock(11, FLOOR_Y + 1, 11, Blocks.OAK_LOG);
        Job job = Job.start(helper, "mpause", new Vec3(7.5, FLOOR_Y + 1, 4.5), 4500, mineLeaf("oak_log", 2, "oak_log", Map.of()));
        int[] pausedAt = {-1};
        job.run.each(4200, () -> {
            BotPlayer body = job.body();
            failIfFailed(helper, job);
            if (pausedAt[0] < 0) {
                if (job.run.ticks == 30) {
                    job.task.pause(body, "test");
                    pausedAt[0] = job.run.ticks;
                    helper.assertTrue(job.task.state() == TaskState.PAUSED, "the paused task is " + job.task.state());
                }
                return;
            }
            if (job.run.ticks == pausedAt[0] + 10) {
                BodyInput input = body.input();
                helper.assertTrue(input.forward == 0.0F && !input.jump && !input.sprint, "movement input left on while paused");
                helper.assertTrue(BotNavigator.of(body).state() == BotNavigator.State.IDLE, "the navigator ran on while paused");
                helper.assertTrue(job.task.state() == TaskState.PAUSED, "the task left PAUSED by itself");
                job.task.resume(body);
                helper.assertTrue(job.task.state() == TaskState.RUNNING, "the resumed task is " + job.task.state());
                return;
            }
            if (job.task.state() != TaskState.COMPLETED) return;
            helper.assertTrue(body.getInventory().countItem(Items.OAK_LOG) >= 2, "completed holding " + body.getInventory().countItem(Items.OAK_LOG) + " logs");
            job.run.pass();
        });
    }

    // ---------------------------------------------------------------------------------------------------------
    // mine: blocks the body cannot get at, and blocks under water. What these protect: a mining step ends, with the
    // item or with a typed failure, whatever the blocks it knows of are like.
    // ---------------------------------------------------------------------------------------------------------

    /**
     * Two logs, one sealed in what the break rules forbid (so it cannot be reached) and one reachable, further off: the
     * task ends holding the reachable log. Unlike a log the body cannot reach, a sealed one is not headed for at all while
     * a reachable one is known, because the search finds a whole path to the other; so nothing is set aside here, and the
     * sealed log stands.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 1500)
    public static void aSealedLogDoesNotKeepTheBodyFromAReachableOne(GameTestHelper helper) {
        room(helper, 5, Blocks.BARRIER);
        sealedLog(helper, Blocks.DARK_OAK_LOG, 11, FLOOR_Y + 2, 3);
        helper.setBlock(3, FLOOR_Y + 1, 12, Blocks.DARK_OAK_LOG);
        Job job = Job.start(helper, "mseal", new Vec3(2.5, FLOOR_Y + 1, 3.5), 1500, mineLeaf("dark_oak_log", 1, "dark_oak_log", Map.of()));
        job.run.each(1200, () -> {
            failIfFailed(helper, job);
            if (job.task.state() != TaskState.COMPLETED) return;
            helper.assertTrue(job.body().getInventory().countItem(Items.DARK_OAK_LOG) == 1,
                "completed holding " + job.body().getInventory().countItem(Items.DARK_OAK_LOG) + " logs");
            helper.assertBlockPresent(Blocks.AIR, new BlockPos(3, FLOOR_Y + 1, 12));
            helper.assertBlockPresent(Blocks.DARK_OAK_LOG, new BlockPos(11, FLOOR_Y + 2, 3));
            job.run.pass();
        });
    }

    /**
     * Only logs the body cannot reach, sealed in what the rules forbid in an arena it cannot leave: the task ends, within
     * the bound that its small search settings give, with {@code NoSource} carrying the leaf's item and source id (blocks
     * were known and none could be got at, which the executor answers by excluding that source, so the next plan gathers
     * the item another way), the sealed log is among the set-aside blocks the task keeps, its position is in the
     * checkpoint, and afterwards the body does not move.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 1200)
    public static void onlyUnreachableLogsFailNoSourceWithinTheBound(GameTestHelper helper) {
        room(helper, 5, Blocks.BARRIER);
        sealedLog(helper, Blocks.CHERRY_LOG, 12, FLOOR_Y + 2, 8);
        BlockPos sealed = helper.absolutePos(new BlockPos(12, FLOOR_Y + 2, 8));
        // Two stalled rounds of 100 ticks and an exploring step of 100 between them, and 200 for the walk to the cube and the
        // engine's two goes at a block it cannot path to (the navigator tries a single give-up once more).
        MineTask.Search small = new MineTask.Search(8, 100, 24, 3, 60, 100, 2);
        int bound = 2 * 100 + 100 + 200;
        Job job = Job.start(helper, "munreach", new Vec3(2.5, FLOOR_Y + 1, 8.5), 1200,
            new MineTask(mineLeaf("cherry_log", 1, "cherry_log", Map.of()), Map.of(), small));
        int[] failedAt = {-1};
        job.run.each(900, () -> {
            helper.assertTrue(job.task.state() != TaskState.COMPLETED, "mined a log that is sealed in");
            BotPlayer body = job.body();
            if (failedAt[0] < 0) {
                if (job.task.state() != TaskState.FAILED) return;
                if (!(job.task.failure() instanceof Failure.NoSource none)) {
                    helper.fail("failed as " + job.task.failure());
                    return;
                }
                helper.assertTrue(job.run.ticks <= bound, "failed only at tick " + job.run.ticks + ", the bound is " + bound);
                helper.assertTrue(none.key().equals(ItemKey.of("cherry_log")), "NoSource names " + none.key());
                helper.assertTrue(none.source().equals("mine:minecraft:cherry_log"), "NoSource carries source '" + none.source() + "'");
                helper.assertTrue(((MineTask) job.task).setAside().contains(sealed),
                    "the sealed log " + sealed + " is not among the set-aside blocks: " + ((MineTask) job.task).setAside());
                helper.assertTrue(job.task.checkpoint().getOrDefault("setAside", "").contains(pos(sealed).encode()),
                    "the sealed log " + pos(sealed).encode() + " is not among the checkpoint's set-aside blocks: " + job.task.checkpoint());
                MegaBlockBot.LOGGER.info("Move-and-mine GameTest: NoSource after {} ticks: {}", job.run.ticks, none.detail());
                failedAt[0] = job.run.ticks;
                return;
            }
            if (job.run.ticks < failedAt[0] + 10) return;
            helper.assertTrue(BotNavigator.of(body).state() == BotNavigator.State.IDLE, "the navigator is " + BotNavigator.of(body).state());
            helper.assertTrue(body.getDeltaMovement().horizontalDistanceSqr() < 1.0E-4, "still moving: " + body.getDeltaMovement());
            BodyInput input = body.input();
            helper.assertTrue(input.forward == 0.0F && input.strafe == 0.0F && !input.jump, "movement input left on");
            helper.assertBlockPresent(Blocks.CHERRY_LOG, new BlockPos(12, FLOOR_Y + 2, 8));
            job.run.pass();
        });
    }

    /**
     * A body that cannot get any closer to the log it heads for (the test holds it where it stands, as deep water or a
     * current would) sets that log aside after the stall bound, and goes for the next one once released. The log it was
     * held from is still standing, and is in the checkpoint as set aside.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 1500)
    public static void aBlockTheBodyCannotCloseOnIsSetAside(GameTestHelper helper) {
        room(helper, 3, Blocks.BARRIER);
        helper.setBlock(10, FLOOR_Y + 1, 3, Blocks.SPRUCE_LOG);
        helper.setBlock(3, FLOOR_Y + 1, 13, Blocks.SPRUCE_LOG);
        BlockPos nearer = helper.absolutePos(new BlockPos(10, FLOOR_Y + 1, 3));
        Vec3 start = new Vec3(2.5, FLOOR_Y + 1, 2.5);
        // Only the navigator's own bound can act here: the task's is out of reach.
        MineTask.Search stalls = new MineTask.Search(32, 600, 256, 12, 40, 100_000, 3);
        Job job = Job.start(helper, "mstall", start, 1500,
            new MineTask(mineLeaf("spruce_log", 1, "spruce_log", Map.of()), Map.of(), stalls));
        int[] releasedAt = {-1};
        job.run.each(1200, () -> {
            failIfFailed(helper, job);
            BotPlayer body = job.body();
            if (releasedAt[0] < 0) {
                if (job.task.checkpoint().getOrDefault("setAside", "").contains(pos(nearer).encode())) {
                    releasedAt[0] = job.run.ticks;
                    // The stall bound is 40 ticks of heading for it: not long before, and not never.
                    helper.assertTrue(job.run.ticks >= 40 && job.run.ticks <= 200, "set the log aside at tick " + job.run.ticks);
                } else {
                    body.setPos(helper.absoluteVec(start));
                    body.setDeltaMovement(Vec3.ZERO);
                    job.run.resetStep();
                    return;
                }
            }
            if (job.task.state() != TaskState.COMPLETED) return;
            helper.assertTrue(body.getInventory().countItem(Items.SPRUCE_LOG) == 1, "completed holding " + body.getInventory().countItem(Items.SPRUCE_LOG) + " logs");
            helper.assertBlockPresent(Blocks.SPRUCE_LOG, new BlockPos(10, FLOOR_Y + 1, 3));
            helper.assertBlockPresent(Blocks.AIR, new BlockPos(3, FLOOR_Y + 1, 13));
            job.run.pass();
        });
    }

    /**
     * A body that moves all the time and gets nowhere (the test swings it back and forth two blocks, as a body does that
     * climbs out of a flooded cave and falls back in: its block position changes every tick, so the executor's stuck
     * watchdog, which watches that and the inventory, would never fire): the task sees that neither the count it holds nor
     * the best distance to the log improves, sets the log aside as a stalled round, and fails {@code NoSource} carrying
     * the leaf's item and source within the bound. The set it keeps holds plain positions, found again by equality with
     * the block's own. The navigator's own bound for one block is out of reach here, so only the task's watch can end it.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 900)
    public static void aStepThatMovesButGetsNowhereGivesUpNoSource(GameTestHelper helper) {
        room(helper, 3, Blocks.BARRIER);
        helper.setBlock(12, FLOOR_Y + 1, 3, Blocks.STRIPPED_OAK_LOG);
        Vec3 start = new Vec3(2.5, FLOOR_Y + 1, 3.5);
        BlockPos log = helper.absolutePos(new BlockPos(12, FLOOR_Y + 1, 3));
        int noGain = 60;
        MineTask.Search swings = new MineTask.Search(8, 100, 24, 3, 100_000, noGain, 2);
        // 60 ticks without a gain, then at most the attempts of the search at a step of 100 ticks each, and some slack.
        int bound = noGain + swings.maxAttempts() * swings.stepTicks() + 60;
        Job job = Job.start(helper, "mswing", start, 900,
            new MineTask(mineLeaf("stripped_oak_log", 1, "stripped_oak_log", Map.of()), Map.of(), swings));
        Set<BlockPos> blockPositions = new HashSet<>();
        job.run.each(700, () -> {
            helper.assertTrue(job.task.state() != TaskState.COMPLETED, "mined a log it was held away from");
            BotPlayer body = job.body();
            blockPositions.add(body.blockPosition());
            if (job.task.state() == TaskState.FAILED) {
                if (!(job.task.failure() instanceof Failure.NoSource none)) {
                    helper.fail("failed as " + job.task.failure());
                    return;
                }
                helper.assertTrue(job.run.ticks <= bound, "failed only at tick " + job.run.ticks + ", the bound is " + bound);
                helper.assertTrue(blockPositions.size() >= 2, "the body stayed in one block, which the executor's watchdog would have caught");
                helper.assertTrue(none.key().equals(ItemKey.of("stripped_oak_log")), "NoSource names " + none.key());
                helper.assertTrue(none.source().equals("mine:minecraft:stripped_oak_log"), "NoSource carries source '" + none.source() + "'");
                // What the stalled round set aside is found again as the plain position of the block it is: the engine
                // has its own position subclass, whose hash would leave it unrecognized in the set.
                helper.assertTrue(((MineTask) job.task).setAside().contains(log),
                    "the log " + log + " is not among the set-aside blocks: " + ((MineTask) job.task).setAside());
                helper.assertTrue(job.task.checkpoint().getOrDefault("setAside", "").contains(pos(log).encode()),
                    "the log is not among the checkpoint's set-aside blocks: " + job.task.checkpoint());
                job.run.pass();
                return;
            }
            body.setPos(helper.absoluteVec(start.add(0.0, 0.0, 2.0 * (job.run.ticks % 2))));
            body.setDeltaMovement(Vec3.ZERO);
            job.run.resetStep();
        });
    }

    /**
     * What a retry keeps: a task given the checkpoint of one that had set a log aside, and that had explored its whole
     * budget, does not go back to the log although nothing stands in the way. It fails {@code NoSource} at once, naming
     * the leaf's item and source (the blocks were known and could not be got at), and the log is still there.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 600)
    public static void aRetryDoesNotGoBackToWhatWasSetAside(GameTestHelper helper) {
        room(helper, 3, Blocks.BARRIER);
        helper.setBlock(9, FLOOR_Y + 1, 8, Blocks.STRIPPED_SPRUCE_LOG);
        Vec3 start = new Vec3(2.5, FLOOR_Y + 1, 8.5);
        BlockPos at = helper.absolutePos(BlockPos.containing(start));
        BlockPos log = helper.absolutePos(new BlockPos(9, FLOOR_Y + 1, 8));
        MineTask.Search small = new MineTask.Search(8, 100, 24, 3);
        Map<String, String> checkpoint = Map.of("dim", dimension(helper), "origin", pos(at).encode(), "explored", "24",
            "setAside", pos(log).encode());
        Job job = Job.start(helper, "mretry", start, 600, new MineTask(mineLeaf("stripped_spruce_log", 1, "stripped_spruce_log", Map.of()), checkpoint, small));
        job.run.each(400, () -> {
            helper.assertTrue(job.task.state() != TaskState.COMPLETED, "mined the log it had set aside");
            if (job.task.state() != TaskState.FAILED) return;
            if (!(job.task.failure() instanceof Failure.NoSource none)) {
                helper.fail("failed as " + job.task.failure());
                return;
            }
            helper.assertTrue(none.key().equals(ItemKey.of("stripped_spruce_log")), "NoSource names " + none.key());
            helper.assertTrue(none.source().equals("mine:minecraft:stripped_spruce_log"), "NoSource carries source '" + none.source() + "'");
            helper.assertTrue(((MineTask) job.task).setAside().contains(log),
                "the set-aside blocks lost the log: " + ((MineTask) job.task).setAside());
            helper.assertTrue(job.run.ticks <= 40, "took " + job.run.ticks + " ticks: it went for the log");
            helper.assertTrue(helper.relativeVec(job.body().position()).distanceTo(start) < 2.0, "the body moved to " + job.body().position());
            helper.assertBlockPresent(Blocks.STRIPPED_SPRUCE_LOG, new BlockPos(9, FLOOR_Y + 1, 8));
            helper.assertTrue(job.task.checkpoint().getOrDefault("setAside", "").contains(pos(log).encode()), "the checkpoint lost it: " + job.task.checkpoint());
            job.run.pass();
        });
    }

    /**
     * A log under two blocks of water and one on dry ground at the same distance: the body takes the dry one, and never
     * goes under (its air stays full). The engine would not break the log under the water in any case (it refuses a block with
     * water directly above it), and the water is walled in barrier, so this is the end to end check that a mining step ends
     * with the dry log; the target rules for what the engine does not refuse are in the two tests after it.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 1200)
    public static void aDryLogIsTakenBeforeOneUnderTwoBlocksOfWater(GameTestHelper helper) {
        room(helper, 5, Blocks.BARRIER);
        // A column of barrier three blocks wide: the log on the floor in the middle and two blocks of water above it.
        for (int x = 10; x <= 12; x++) {
            for (int z = 7; z <= 9; z++) {
                boolean rim = x != 11 || z != 8;
                for (int y = FLOOR_Y + 1; y <= FLOOR_Y + 3; y++) {
                    helper.setBlock(x, y, z, rim ? Blocks.BARRIER : (y == FLOOR_Y + 1 ? Blocks.ACACIA_LOG : Blocks.WATER));
                }
            }
        }
        // Both logs 3.5 blocks from the body.
        helper.setBlock(4, FLOOR_Y + 1, 8, Blocks.ACACIA_LOG);
        Job job = Job.start(helper, "mdry", new Vec3(7.5, FLOOR_Y + 1, 8.5), 1200, mineLeaf("acacia_log", 1, "acacia_log", Map.of()));
        int[] lowestAir = {Integer.MAX_VALUE};
        job.run.each(900, () -> {
            failIfFailed(helper, job);
            lowestAir[0] = Math.min(lowestAir[0], job.body().getAirSupply());
            if (job.task.state() != TaskState.COMPLETED) return;
            helper.assertBlockPresent(Blocks.AIR, new BlockPos(4, FLOOR_Y + 1, 8));
            helper.assertBlockPresent(Blocks.ACACIA_LOG, new BlockPos(11, FLOOR_Y + 1, 8));
            helper.assertTrue(lowestAir[0] >= job.body().getMaxAirSupply(), "the body's air fell to " + lowestAir[0]);
            job.run.pass();
        });
    }

    /**
     * A log with water two blocks under it, nearer than a dry one: both are wanted, and the body takes the dry one first,
     * then the wet one (nothing dry is left, and its air is full).
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 2400)
    public static void aLogWithWaterInItsColumnIsTakenAfterTheDryOne(GameTestHelper helper) {
        int top = plateau(helper);
        wetLog(helper, top, 10, 4, Blocks.BIRCH_LOG);
        helper.setBlock(2, top + 1, 13, Blocks.BIRCH_LOG);
        Job job = Job.start(helper, "mwet", new Vec3(2.5, top + 1, 4.5), 2400, mineLeaf("birch_log", 2, "birch_log", Map.of()));
        int[] dryGoneAt = {-1};
        int[] wetGoneAt = {-1};
        job.run.each(2100, () -> {
            failIfFailed(helper, job);
            if (dryGoneAt[0] < 0 && helper.getBlockState(new BlockPos(2, top + 1, 13)).isAir()) dryGoneAt[0] = job.run.ticks;
            if (wetGoneAt[0] < 0 && helper.getBlockState(new BlockPos(10, top + 1, 4)).isAir()) wetGoneAt[0] = job.run.ticks;
            if (job.task.state() != TaskState.COMPLETED) return;
            helper.assertTrue(job.body().getInventory().countItem(Items.BIRCH_LOG) == 2, "holds " + job.body().getInventory().countItem(Items.BIRCH_LOG) + " logs");
            helper.assertTrue(dryGoneAt[0] >= 0 && wetGoneAt[0] >= 0 && dryGoneAt[0] < wetGoneAt[0],
                "the dry log went at tick " + dryGoneAt[0] + ", the one in the water at " + wetGoneAt[0]);
            // The wet log is in the engine's mind once the dry one is gone: the task never had to go looking for it.
            helper.assertTrue("0".equals(job.task.checkpoint().get("explored")), "the task went exploring: " + job.task.checkpoint());
            job.run.pass();
        });
    }

    /**
     * The only log known has water in its column, and the body's air is down: it does not start for the log until the air is
     * full again, and then gets it.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 1500)
    public static void aLogWithWaterInItsColumnWaitsForFullAir(GameTestHelper helper) {
        int top = plateau(helper);
        wetLog(helper, top, 10, 4, Blocks.JUNGLE_LOG);
        Vec3 start = new Vec3(2.5, top + 1, 4.5);
        Job job = Job.start(helper, "mair", start, 1500, mineLeaf("jungle_log", 1, "jungle_log", Map.of()));
        job.body().setAirSupply(60);
        boolean[] started = {false};
        job.run.each(1200, () -> {
            failIfFailed(helper, job);
            BotPlayer body = job.body();
            if (!started[0] && helper.relativeVec(body.position()).distanceTo(start) > 0.4) {
                started[0] = true;
                helper.assertTrue(body.getAirSupply() >= body.getMaxAirSupply(), "set off with " + body.getAirSupply() + " air of " + body.getMaxAirSupply() + " at tick " + job.run.ticks + ", at " + helper.relativeVec(body.position()) + ", moving " + body.getDeltaMovement() + ", the navigator is " + BotNavigator.of(body).state());
            }
            if (job.task.state() != TaskState.COMPLETED) return;
            helper.assertTrue(started[0], "completed without moving");
            helper.assertTrue("0".equals(job.task.checkpoint().get("explored")), "the task went exploring: " + job.task.checkpoint());
            helper.assertBlockPresent(Blocks.AIR, new BlockPos(10, top + 1, 4));
            job.run.pass();
        });
    }

    /**
     * A body standing on the floor of a pool two blocks deep, with only a wet log known and its air set low once before
     * the command starts (set-up: a body that came down through the water on the way): the wait for air happens out of
     * the water or not at all. Within the budget its eyes are out of the water -- a body still in it keeps losing air
     * all the while -- its air is rising again, and it is alive; the step then still gets the log, so the wait was a
     * wait and not a give-up.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 2400)
    public static void aBodyInTheWaterGetsItsHeadOutToWaitForAir(GameTestHelper helper) {
        int top = plateau(helper);
        // A pool two blocks deep, sunk into the plateau over one column: water at top and top-1, barrier under it.
        helper.setBlock(10, top - 1, 8, Blocks.WATER);
        helper.setBlock(10, top, 8, Blocks.WATER);
        // A kind no other test places: the tests run side by side, and a body goes for the nearest log of its kind.
        wetLog(helper, top, 4, 4, Blocks.STRIPPED_JUNGLE_LOG);
        Job job = Job.start(helper, "mhead", new Vec3(10.5, top + 1, 8.5), 2400,
            mineLeaf("stripped_jungle_log", 1, "stripped_jungle_log", Map.of()));
        // Set-up, once before the command starts driving anything: the body comes to the command short of air.
        job.body().setAirSupply(60);
        boolean[] wasUnder = {false};
        int[] airWhenOut = {-1};
        job.run.each(2100, () -> {
            failIfFailed(helper, job);
            BotPlayer body = job.body();
            if (body.isEyeInFluid(FluidTags.WATER)) {
                wasUnder[0] = true;
                helper.assertTrue(body.isAlive(), "the body died in the water at tick " + job.run.ticks + " with " + body.getAirSupply() + " air");
                return;
            }
            if (wasUnder[0] && airWhenOut[0] < 0) airWhenOut[0] = body.getAirSupply();
            if (job.task.state() != TaskState.COMPLETED) return;
            helper.assertTrue(wasUnder[0], "completed without ever having its eyes in the water");
            helper.assertTrue(airWhenOut[0] >= 1, "completed without getting its head out of the water");
            helper.assertTrue(body.getAirSupply() > airWhenOut[0],
                "the air never rose again once the head was out: out at " + airWhenOut[0] + ", now " + body.getAirSupply());
            helper.assertTrue(body.getInventory().countItem(Items.STRIPPED_JUNGLE_LOG) == 1, "completed without the log");
            job.run.pass();
        });
    }

    /** A log in the middle of a 3 x 3 x 3 cube of obsidian, which the break rules of these tests forbid. */
    private static void sealedLog(GameTestHelper helper, Block log, int x, int y, int z) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) helper.setBlock(x + dx, y + dy, z + dz, Blocks.OBSIDIAN);
            }
        }
        helper.setBlock(x, y, z, log);
    }

    /**
     * A barrier plateau, four blocks thick, over the whole walled arena, with room to stand on it; returns the layer of
     * its top cells. Thick enough to hold a pocket of water under the ground.
     */
    private static int plateau(GameTestHelper helper) {
        int top = FLOOR_Y + 3;
        room(helper, top + 4, Blocks.BARRIER);
        for (int x = 1; x <= 14; x++) {
            for (int z = 1; z <= 14; z++) {
                for (int y = FLOOR_Y + 1; y <= top; y++) helper.setBlock(x, y, z, Blocks.BARRIER);
            }
        }
        return top;
    }

    /**
     * A log standing on the plateau with a pocket of water sunk two blocks under it. The engine finds nothing wrong with
     * the log (it refuses a block with water above it or beside it, and this one has neither), and the body walks up to it
     * like any other; the water is in the column the body works in, which is what makes the log wet for the task. The one
     * wet block a test can have that the engine does not already refuse and a body can reach without swimming.
     */
    private static void wetLog(GameTestHelper helper, int top, int x, int z, Block log) {
        helper.setBlock(x, top - 1, z, Blocks.WATER);
        helper.setBlock(x, top + 1, z, log);
    }

    // ---------------------------------------------------------------------------------------------------------
    // recover_drops
    // ---------------------------------------------------------------------------------------------------------

    /** An item lies across the arena where a death's drops fell: the body walks there, takes it, and the task completes. */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 1200)
    public static void recoverDropsPicksUpAnItem(GameTestHelper helper) {
        room(helper, 3);
        ItemEntity drop = helper.spawnItem(Items.DIAMOND, 12.5F, FLOOR_Y + 1.0F, 12.5F);
        BlockPos at = helper.absolutePos(new BlockPos(12, FLOOR_Y + 1, 12));
        Job job = Job.start(helper, "mdrops", new Vec3(2.5, FLOOR_Y + 1, 2.5), 1200, recoverLeaf(helper, at));
        job.run.each(1050, () -> {
            failIfFailed(helper, job);
            if (job.task.state() != TaskState.COMPLETED) return;
            helper.assertTrue(job.body().getInventory().countItem(Items.DIAMOND) == 1, "completed without the item");
            helper.assertTrue(drop.isRemoved(), "the item entity is still in the world");
            job.run.pass();
        });
    }

    /**
     * A position the body cannot reach (a cell sealed in barrier) comes first, then one with an item: the first is
     * skipped, the second recovered, and the task completes, never failing the goal.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 2700)
    public static void recoverDropsSkipsWhatItCannotReach(GameTestHelper helper) {
        room(helper, 5);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx != 0 || dy != 0 || dz != 0) helper.setBlock(7 + dx, FLOOR_Y + 2 + dy, 7 + dz, Blocks.BARRIER);
                }
            }
        }
        ItemEntity drop = helper.spawnItem(Items.EMERALD, 12.5F, FLOOR_Y + 1.0F, 3.5F);
        BlockPos sealed = helper.absolutePos(new BlockPos(7, FLOOR_Y + 2, 7));
        BlockPos reachable = helper.absolutePos(new BlockPos(12, FLOOR_Y + 1, 3));
        Job job = Job.start(helper, "mdrop2", new Vec3(2.5, FLOOR_Y + 1, 12.5), 2700, recoverLeaf(helper, sealed, reachable));
        job.run.each(2400, () -> {
            failIfFailed(helper, job);
            if (job.task.state() != TaskState.COMPLETED) return;
            helper.assertTrue(job.body().getInventory().countItem(Items.EMERALD) == 1, "completed without the item that could be reached");
            helper.assertTrue(drop.isRemoved(), "the item entity is still in the world");
            job.run.pass();
        });
    }

    /** Drops left in another dimension cannot be reached: the task does not fail, it completes at once and goes nowhere. */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 600)
    public static void recoverDropsInAnotherDimensionCompletesAtOnce(GameTestHelper helper) {
        room(helper, 3);
        Map<String, String> params = Map.of("dimension", "minecraft:the_nether", "drops", "5,70,5;6,70,6");
        NodeSpec leaf = NodeSpec.leaf(new Goal.Custom(TaskFeature.RECOVER_DROPS, params), TaskFeature.RECOVER_DROPS, params, "");
        Vec3 start = new Vec3(2.5, FLOOR_Y + 1, 2.5);
        Job job = Job.start(helper, "mdrop3", start, 600, leaf);
        job.run.each(300, () -> {
            failIfFailed(helper, job);
            if (job.task.state() != TaskState.COMPLETED) return;
            helper.assertTrue(job.run.ticks <= 3, "completed only at tick " + job.run.ticks);
            helper.assertTrue(helper.relativeVec(job.body().position()).distanceTo(start) < 0.5, "the body moved to " + job.body().position());
            job.run.pass();
        });
    }

    /**
     * A death's drop has moved by the time the body is back: an item placed two blocks from the recorded position and
     * one block below it (set-up, once before the task starts; the record sits a block above the floor, as one taken
     * at eye height, and the item lies on the floor where it settled). The task looks for the drops of a position
     * around it and below, walks to where the item lies, and the body ends holding it.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 1200)
    public static void recoverDropsFindsAnItemThatMovedFromTheRecordedPosition(GameTestHelper helper) {
        room(helper, 3, Blocks.BARRIER);
        ItemEntity drop = helper.spawnItem(Items.DIAMOND, 9.5F, FLOOR_Y + 1.5F, 7.5F);
        BlockPos recorded = helper.absolutePos(new BlockPos(7, FLOOR_Y + 2, 7));
        Job job = Job.start(helper, "mdrop4", new Vec3(2.5, FLOOR_Y + 1, 2.5), 1200, recoverLeaf(helper, recorded));
        job.run.each(1050, () -> {
            failIfFailed(helper, job);
            if (job.task.state() != TaskState.COMPLETED) return;
            helper.assertTrue(job.body().getInventory().countItem(Items.DIAMOND) == 1, "completed without the item");
            helper.assertTrue(drop.isRemoved(), "the item entity is still in the world");
            job.run.pass();
        });
    }

    /**
     * One recorded position with nothing within the radius around it: the position is done at once -- the task does
     * not wait a pickup wait out for it -- and the task completes within a few ticks of the body reaching the record.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 600)
    public static void recoverDropsWithNothingNearThePositionIsDoneAtOnce(GameTestHelper helper) {
        room(helper, 3, Blocks.BARRIER);
        BlockPos recorded = helper.absolutePos(new BlockPos(12, FLOOR_Y + 1, 12));
        Job job = Job.start(helper, "mdrop5", new Vec3(2.5, FLOOR_Y + 1, 2.5), 600, recoverLeaf(helper, recorded));
        int[] arrived = {-1};
        job.run.each(500, () -> {
            failIfFailed(helper, job);
            // The task walks to the recorded position first: that is the centre it looks around from.
            if (arrived[0] < 0 && job.body().blockPosition().distSqr(recorded) <= 4) arrived[0] = job.run.ticks;
            if (job.task.state() != TaskState.COMPLETED) return;
            helper.assertTrue(arrived[0] >= 0, "completed at tick " + job.run.ticks + " without ever reaching the recorded position");
            helper.assertTrue(job.run.ticks - arrived[0] <= 10,
                "done with the empty position only " + (job.run.ticks - arrived[0]) + " ticks after reaching it");
            job.run.pass();
        });
    }

    // ---------------------------------------------------------------------------------------------------------
    // deposit
    // ---------------------------------------------------------------------------------------------------------

    /**
     * Five of the eight cobblestone the body carries go into a chest across the arena: the chest holds five, the body
     * three, and no menu is left open with anything on the cursor.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 1200)
    public static void depositPutsPartOfAStackInTheChest(GameTestHelper helper) {
        room(helper, 3);
        helper.setBlock(10, FLOOR_Y + 1, 10, Blocks.CHEST);
        BlockPos chestPos = new BlockPos(10, FLOOR_Y + 1, 10);
        Job job = Job.start(helper, "mdep", new Vec3(3.5, FLOOR_Y + 1, 3.5), 1200, depositLeaf(helper, chestPos, "cobblestone", 5));
        job.body().getInventory().setItem(3, new ItemStack(Items.COBBLESTONE, 8));
        job.run.each(1050, () -> {
            failIfFailed(helper, job);
            if (job.task.state() != TaskState.COMPLETED) return;
            ChestBlockEntity chest = helper.getBlockEntity(chestPos);
            helper.assertTrue(chest.countItem(Items.COBBLESTONE) == 5, "the chest holds " + chest.countItem(Items.COBBLESTONE) + " cobblestone");
            helper.assertTrue(job.body().getInventory().countItem(Items.COBBLESTONE) == 3, "the body holds " + job.body().getInventory().countItem(Items.COBBLESTONE) + " cobblestone");
            assertNoMenuOpen(helper, job.body());
            job.run.pass();
        });
    }

    /** Seven from two stacks of three and eight: the whole small stack and four of the large one, and the cursor ends empty. */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 1200)
    public static void depositMovesWholeStacksAndPartOfOne(GameTestHelper helper) {
        room(helper, 3);
        helper.setBlock(10, FLOOR_Y + 1, 10, Blocks.CHEST);
        BlockPos chestPos = new BlockPos(10, FLOOR_Y + 1, 10);
        Job job = Job.start(helper, "mdep2", new Vec3(3.5, FLOOR_Y + 1, 3.5), 1200, depositLeaf(helper, chestPos, "cobblestone", 7));
        job.body().getInventory().setItem(0, new ItemStack(Items.COBBLESTONE, 3));
        job.body().getInventory().setItem(1, new ItemStack(Items.COBBLESTONE, 8));
        job.run.each(1050, () -> {
            failIfFailed(helper, job);
            if (job.task.state() != TaskState.COMPLETED) return;
            ChestBlockEntity chest = helper.getBlockEntity(chestPos);
            helper.assertTrue(chest.countItem(Items.COBBLESTONE) == 7, "the chest holds " + chest.countItem(Items.COBBLESTONE) + " cobblestone");
            helper.assertTrue(job.body().getInventory().countItem(Items.COBBLESTONE) == 4, "the body holds " + job.body().getInventory().countItem(Items.COBBLESTONE) + " cobblestone");
            assertNoMenuOpen(helper, job.body());
            job.run.pass();
        });
    }

    /** The body holds less than it is to deposit: MissingInput, before it walks anywhere. */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 100)
    public static void depositWithTooLittleFailsMissingInput(GameTestHelper helper) {
        room(helper, 3);
        helper.setBlock(10, FLOOR_Y + 1, 10, Blocks.CHEST);
        Job job = Job.start(helper, "mdep3", new Vec3(3.5, FLOOR_Y + 1, 3.5), 100,
            depositLeaf(helper, new BlockPos(10, FLOOR_Y + 1, 10), "cobblestone", 5));
        job.body().getInventory().setItem(0, new ItemStack(Items.COBBLESTONE, 2));
        job.run.each(20, () -> {
            helper.assertTrue(job.task.state() != TaskState.COMPLETED, "deposited what the body does not have");
            if (job.task.state() != TaskState.FAILED) return;
            if (!(job.task.failure() instanceof Failure.MissingInput missing)) {
                helper.fail("failed as " + job.task.failure());
                return;
            }
            helper.assertTrue(missing.key().equals(ItemKey.of("cobblestone")), "MissingInput names " + missing.key());
            helper.assertTrue(job.body().getInventory().countItem(Items.COBBLESTONE) == 2, "the body's cobblestone changed");
            job.run.pass();
        });
    }

    /** Nothing stands where the container should: the body walks there and fails TargetGone. */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 1200)
    public static void depositWithNoChestFailsTargetGone(GameTestHelper helper) {
        room(helper, 3);
        Job job = Job.start(helper, "mdep4", new Vec3(3.5, FLOOR_Y + 1, 3.5), 1200,
            depositLeaf(helper, new BlockPos(10, FLOOR_Y + 1, 10), "cobblestone", 5));
        job.body().getInventory().setItem(0, new ItemStack(Items.COBBLESTONE, 5));
        job.run.each(1050, () -> {
            helper.assertTrue(job.task.state() != TaskState.COMPLETED, "deposited into nothing");
            if (job.task.state() != TaskState.FAILED) return;
            helper.assertTrue(job.task.failure() instanceof Failure.TargetGone, "failed as " + job.task.failure());
            helper.assertTrue(job.body().getInventory().countItem(Items.COBBLESTONE) == 5, "the body's cobblestone changed");
            assertNoMenuOpen(helper, job.body());
            job.run.pass();
        });
    }

    /**
     * Another block stands where the container the deposit was counted against was: the task asks what stands at the
     * position before it opens anything, as the tasks that take items out do, so it fails TargetGone, lets go of what the
     * body holds of the spot, and puts nothing anywhere (design 3.5: every record of a container is held to its block).
     *
     * <p>The task and not the goal, because the executor retries a TARGET_GONE leaf once (GoalExecutor.routeFailure) and
     * the record is dropped by the first try: a goal-level test of this would end with the cobblestone in the barrel.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 1200)
    public static void depositIntoAContainerThatIsAnotherBlockNowFailsTargetGone(GameTestHelper helper) {
        room(helper, 3);
        BlockPos chestPos = new BlockPos(10, FLOOR_Y + 1, 10);
        helper.setBlock(chestPos, Blocks.CHEST);
        Job job = Job.start(helper, "mdep6", new Vec3(3.5, FLOOR_Y + 1, 3.5), 1200, depositLeaf(helper, chestPos, "cobblestone", 5));
        // Set-up, not the body's doing: the five the leaf moves, and a note of the chest's inside, which is what
        // ContainerAccess.note leaves of a container the body has been inside and nobody named.
        job.body().getInventory().setItem(0, new ItemStack(Items.COBBLESTONE, 5));
        BotMemory memory = BotMemory.of(job.run.id);
        job.run.atEnd(() -> BotMemory.forget(job.run.id));
        memory.noteContainer(dimension(helper), helper.absolutePos(chestPos), "minecraft:chest", Map.of());
        // The chest is taken away and an empty barrel set in its place while the body is on its way.
        helper.setBlock(chestPos, Blocks.BARREL);
        job.run.each(1050, () -> {
            helper.assertTrue(job.task.state() != TaskState.COMPLETED, "deposited into a block the plan was not about");
            if (job.task.state() != TaskState.FAILED) return;
            helper.assertTrue(job.task.failure() instanceof Failure.TargetGone, "failed as " + job.task.failure());
            helper.assertTrue(((BarrelBlockEntity) helper.getBlockEntity(chestPos)).isEmpty(), "something is in the barrel");
            helper.assertTrue(job.body().getInventory().countItem(Items.COBBLESTONE) == 5, "the body's cobblestone changed");
            helper.assertTrue(memory.containerAt(dimension(helper), helper.absolutePos(chestPos)) == null,
                "the body still holds a note of the chest: " + memory.containers());
            assertNoMenuOpen(helper, job.body());
            job.run.pass();
        });
    }

    /** A chest with a solid block on top will not open: the body reaches it, finds it shut, and fails Unreachable. */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 1200)
    public static void depositIntoABlockedChestFailsUnreachable(GameTestHelper helper) {
        room(helper, 3);
        helper.setBlock(10, FLOOR_Y + 1, 10, Blocks.CHEST);
        helper.setBlock(10, FLOOR_Y + 2, 10, Blocks.STONE);
        BlockPos chestPos = new BlockPos(10, FLOOR_Y + 1, 10);
        Job job = Job.start(helper, "mdep5", new Vec3(3.5, FLOOR_Y + 1, 3.5), 1200, depositLeaf(helper, chestPos, "cobblestone", 5));
        job.body().getInventory().setItem(0, new ItemStack(Items.COBBLESTONE, 5));
        job.run.each(1050, () -> {
            helper.assertTrue(job.task.state() != TaskState.COMPLETED, "deposited into a chest that cannot be opened");
            if (job.task.state() != TaskState.FAILED) return;
            helper.assertTrue(job.task.failure() instanceof Failure.Unreachable, "failed as " + job.task.failure());
            helper.assertTrue(((ChestBlockEntity) helper.getBlockEntity(chestPos)).isEmpty(), "something is in the chest");
            assertNoMenuOpen(helper, job.body());
            job.run.pass();
        });
    }

    /**
     * A chest with room for only part of what the leaf moves (set-up, once before the task starts: every slot full of
     * dirt but one, which holds 60 cobblestone and so has room for four more): the task completes having moved
     * exactly what fitted -- four, counted by what left the body, not by what the clicks were meant to move -- the
     * body still holds the rest, and nothing is on the cursor or the floor.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 1200)
    public static void depositIntoAFullChestMovesOnlyWhatFits(GameTestHelper helper) {
        room(helper, 3);
        BlockPos chestPos = new BlockPos(10, FLOOR_Y + 1, 10);
        helper.setBlock(chestPos, Blocks.CHEST);
        ChestBlockEntity chest = helper.getBlockEntity(chestPos);
        for (int slot = 0; slot < 26; slot++) chest.setItem(slot, new ItemStack(Items.DIRT, 64));
        chest.setItem(26, new ItemStack(Items.COBBLESTONE, 60));
        Job job = Job.start(helper, "mdep7", new Vec3(3.5, FLOOR_Y + 1, 3.5), 1200, depositLeaf(helper, chestPos, "cobblestone", 8));
        job.body().getInventory().setItem(3, new ItemStack(Items.COBBLESTONE, 8));
        job.run.each(1050, () -> {
            failIfFailed(helper, job);
            if (job.task.state() != TaskState.COMPLETED) return;
            helper.assertTrue(chest.countItem(Items.COBBLESTONE) == 64,
                "the chest holds " + chest.countItem(Items.COBBLESTONE) + " cobblestone, room was for 4 more");
            helper.assertTrue(job.body().getInventory().countItem(Items.COBBLESTONE) == 4,
                "the body holds " + job.body().getInventory().countItem(Items.COBBLESTONE) + " cobblestone, not the 4 that did not fit");
            assertNoMenuOpen(helper, job.body());
            helper.assertTrue(onTheGround(job.body(), Items.COBBLESTONE) == 0, "cobblestone was dropped: "
                + onTheGround(job.body(), Items.COBBLESTONE));
            job.run.pass();
        });
    }

    /**
     * A menu that was open before the task opened the container is not the container's. Set-up: a second chest within
     * reach of where the body starts, opened by the body's own use-click in the test's first ticks, and a stone block
     * above the leaf's chest, whose use opens no menu. What the body holds must not go into the other chest: the task
     * closes the foreign menu before it uses the container, finds the leaf's chest shut and fails Unreachable, with
     * the items still held and nothing on any cursor.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 1200)
    public static void depositDoesNotUseAMenuThatWasOpenBeforeTheContainer(GameTestHelper helper) {
        room(helper, 3);
        BlockPos other = new BlockPos(4, FLOOR_Y + 1, 4);
        BlockPos chestPos = new BlockPos(10, FLOOR_Y + 1, 10);
        helper.setBlock(other, Blocks.CHEST);
        helper.setBlock(chestPos, Blocks.CHEST);
        helper.setBlock(10, FLOOR_Y + 2, 10, Blocks.STONE);
        Job job = Job.start(helper, "mdep8", new Vec3(3.5, FLOOR_Y + 1, 3.5), 1200, depositLeaf(helper, chestPos, "cobblestone", 5));
        job.body().getInventory().setItem(0, new ItemStack(Items.COBBLESTONE, 5));
        boolean[] opened = {false};
        job.run.each(1050, () -> {
            if (!opened[0]) {
                // Set-up, in the test's first ticks: the body's own use-click opens the other chest, as a container
                // used before this step would have left it open.
                useChest(job.body(), helper.absolutePos(other));
                helper.assertTrue(job.body().containerMenu != job.body().inventoryMenu, "the other chest did not open");
                opened[0] = true;
                return;
            }
            helper.assertTrue(job.task.state() != TaskState.COMPLETED, "the task deposited into a chest that will not open");
            if (job.task.state() != TaskState.FAILED) return;
            helper.assertTrue(job.task.failure() instanceof Failure.Unreachable, "failed as " + job.task.failure());
            ChestBlockEntity otherChest = helper.getBlockEntity(other);
            helper.assertTrue(otherChest.isEmpty(), "the other chest holds what was meant for the leaf's container");
            helper.assertTrue(job.body().getInventory().countItem(Items.COBBLESTONE) == 5, "the body's cobblestone changed");
            assertNoMenuOpen(helper, job.body());
            job.run.pass();
        });
    }

    // ---------------------------------------------------------------------------------------------------------
    // Building leaves
    // ---------------------------------------------------------------------------------------------------------

    /** A mine leaf as the planner writes it: the goal is an absolute count of the item, the source names the block. */
    private static NodeSpec mineLeaf(String item, int count, String block, Map<String, String> extra) {
        Map<String, String> params = new HashMap<>(extra);
        params.put("item", ITEM + item);
        params.put("count", Integer.toString(count));
        params.put("block", ITEM + block);
        return NodeSpec.leaf(new Goal.HaveItem(ItemKey.of(item), count), TaskFeature.MINE, params, "mine:" + ITEM + block);
    }

    /** A recover-drops node as the executor inserts it: positions as x,y,z joined by semicolons. */
    private static NodeSpec recoverLeaf(GameTestHelper helper, BlockPos... drops) {
        StringBuilder positions = new StringBuilder();
        for (BlockPos drop : drops) {
            if (!positions.isEmpty()) positions.append(';');
            positions.append(pos(drop).encode());
        }
        Map<String, String> params = Map.of("dimension", dimension(helper), "drops", positions.toString(), "deathPos", pos(drops[0]).encode());
        return NodeSpec.leaf(new Goal.Custom(TaskFeature.RECOVER_DROPS, params), TaskFeature.RECOVER_DROPS, params, "");
    }

    /** A deposit leaf: the goal says where the container is and what it is to hold, {@code count} how many to move. */
    private static NodeSpec depositLeaf(GameTestHelper helper, BlockPos relativeChest, String item, int count) {
        Pos chest = pos(helper.absolutePos(relativeChest));
        return NodeSpec.leaf(new Goal.Stockpile(ItemKey.of(item), count, dimension(helper), chest), TaskFeature.DEPOSIT,
            Map.of("count", Integer.toString(count)), "");
    }

    /** A mine task started on the body, but never ticked: only what it decides in start() is looked at. */
    private static Task<BotPlayer> startedMine(BotPlayer body, String item, String block, Map<String, String> tool) {
        Task<BotPlayer> task = TaskRegistry.create(mineLeaf(item, 1, block, tool), Map.of());
        task.start(body);
        return task;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Checks
    // ---------------------------------------------------------------------------------------------------------

    private static void failIfFailed(GameTestHelper helper, Job job) {
        if (job.task.state() == TaskState.FAILED) helper.fail("the task failed: " + job.task.failure());
    }

    private static void assertMissingTool(GameTestHelper helper, Task<BotPlayer> task, ToolClass toolClass, ToolTier tier) {
        helper.assertTrue(task.state() == TaskState.FAILED, "the task is " + task.state() + ", not FAILED");
        if (!(task.failure() instanceof Failure.MissingTool missing)) {
            helper.fail("failed as " + task.failure());
            return;
        }
        helper.assertTrue(missing.toolClass() == toolClass && missing.tier() == tier,
            "MissingTool asks for " + missing.tier() + " " + missing.toolClass() + ", not " + tier + " " + toolClass);
    }

    /** No menu is open on the body and nothing is on any cursor. */
    private static void assertNoMenuOpen(GameTestHelper helper, BotPlayer body) {
        helper.assertTrue(body.containerMenu == body.inventoryMenu, "a menu is still open: " + body.containerMenu);
        helper.assertTrue(body.containerMenu.getCarried().isEmpty(), "the cursor holds " + body.containerMenu.getCarried());
        helper.assertTrue(body.inventoryMenu.getCarried().isEmpty(), "the inventory menu's cursor holds " + body.inventoryMenu.getCarried());
    }

    /** How many of an item lie on the ground near the body, as item entities: nothing a deposit moves should. */
    private static int onTheGround(BotPlayer body, Item item) {
        int total = 0;
        for (ItemEntity stack : body.serverLevel().getEntitiesOfClass(ItemEntity.class, body.getBoundingBox().inflate(8))) {
            if (stack.getItem().is(item)) total += stack.getItem().getCount();
        }
        return total;
    }

    /** The body's own use-click on the block at {@code at} (absolute), on the face that looks toward its eyes. */
    private static void useChest(BotPlayer body, BlockPos at) {
        Vec3 center = Vec3.atCenterOf(at);
        Vec3 toEyes = body.getEyePosition().subtract(center);
        Direction face = Direction.getNearest(toEyes.x, toEyes.y, toEyes.z);
        Vec3 hitAt = center.add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        BodyInteractionManager use = (BodyInteractionManager) body.getInteractionManager();
        use.interactBlock(body, body.serverLevel(), body.getMainHandItem(), InteractionHand.MAIN_HAND,
            new BlockHitResult(hitAt, face, at, false));
    }

    /** This test's arena, as the box {@link #ARENAS} knows it by. */
    private static AABB arena(GameTestHelper helper) {
        BlockPos corner = helper.absolutePos(BlockPos.ZERO);
        return new AABB(corner.getX(), corner.getY(), corner.getZ(), corner.getX() + 16, corner.getY() + 16, corner.getZ() + 16);
    }

    /**
     * Registers the arena with the break rules (breaking allowed inside, nothing outside) for as long as the test
     * runs. The rules are global ({@link BreakRules} is the one place tasks look), so the filter consults every
     * registered arena, and each test removes its own however it ends.
     */
    private static AABB registerArena(GameTestHelper helper, NavRun run) {
        run.atEnd(allowBreakingIn(helper));
        return arena(helper);
    }

    /**
     * Breaking is allowed in this test's arena from now on, and nowhere else; the returned action takes the arena out of
     * the rules again. For a test in another class, which runs side by side with these in one world: the rules are global,
     * so a body that is not in a registered arena may break nothing while one of these tests is running.
     *
     * <p>Obsidian is the one block these rules forbid everywhere, so that a test can seal a block in with what the rules
     * will not let a body break (and the search knows it, as it asks the same rules).
     */
    static Runnable allowBreakingIn(GameTestHelper helper) {
        // Two changes each added this registration under their own name; one body, both names kept for their callers.
        return allowBreaking(helper);
    }

    /**
     * The registration {@link #registerArena} makes, for a test of another class whose body mines inside its arena
     * through the real runtime: breaking is allowed in this test's arena and nowhere outside the registered ones, as for
     * the tests here (the rules are global, so a class that sets its own would take these tests' away). Returns what
     * undoes it; the test calls it however it ends.
     */
    static Runnable allowBreaking(GameTestHelper helper) {
        AABB box = arena(helper);
        ARENAS.put(box, Boolean.TRUE);
        BreakRules.set((level, at, state) -> {
            if (state.is(Blocks.OBSIDIAN)) return false;
            for (Map.Entry<AABB, Boolean> arena : ARENAS.entrySet()) {
                if (arena.getKey().contains(at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5)) return arena.getValue();
            }
            return false;
        });
        return () -> {
            ARENAS.remove(box);
            if (ARENAS.isEmpty()) BreakRules.reset();
        };
    }

    /** From now on nothing inside this test's arena may be broken. */
    private static void protect(GameTestHelper helper) {
        ARENAS.put(arena(helper), Boolean.FALSE);
    }

    private static String dimension(GameTestHelper helper) {
        return helper.getLevel().dimension().location().toString();
    }

    private static Pos pos(BlockPos at) {
        return new Pos(at.getX(), at.getY(), at.getZ());
    }

    // ---------------------------------------------------------------------------------------------------------
    // Harness
    // ---------------------------------------------------------------------------------------------------------

    /** Ticks one task, then the navigator, every body tick: the order that lets a command start driving the same tick. */
    private static final class TaskBrain implements BotBrain {
        private final Task<BotPlayer> task;

        TaskBrain(Task<BotPlayer> task) {
            this.task = task;
        }

        @Override
        public void tick(BotPlayer body) {
            if (task.state() == TaskState.PENDING) task.start(body);
            if (task.state() == TaskState.RUNNING) {
                TickResult result = task.tick(body);
                if (result == null) throw new IllegalStateException("the task returned no result");
            }
            BotNavigator.of(body).tick(body);
        }

        @Override public void onDeath(BotPlayer body, DeathRecord death) {}
        @Override public void onRespawn(BotPlayer newBody) {}
        @Override public boolean wantsToStayLoaded() { return true; }
        @Override public void save(CompoundTag tag, HolderLookup.Provider registries) {}
        @Override public void load(CompoundTag tag, HolderLookup.Provider registries) {}
    }

    /** One test: a body running one task, watched by the shared harness. */
    private static final class Job {
        final NavRun run;
        final Task<BotPlayer> task;

        private Job(NavRun run, Task<BotPlayer> task) {
            this.run = run;
            this.task = task;
        }

        static Job start(GameTestHelper helper, String tag, Vec3 relative, int deadlineTicks, NodeSpec leaf) {
            return start(helper, tag, relative, deadlineTicks, TaskRegistry.create(leaf, Map.of()));
        }

        static Job start(GameTestHelper helper, String tag, Vec3 relative, int deadlineTicks, Task<BotPlayer> task) {
            TaskBrain brain = new TaskBrain(task);
            NavRun run = NavRun.start(helper, tag, relative, deadlineTicks, () -> brain);
            registerArena(helper, run);
            return new Job(run, task);
        }

        BotPlayer body() {
            return run.body();
        }
    }
}
