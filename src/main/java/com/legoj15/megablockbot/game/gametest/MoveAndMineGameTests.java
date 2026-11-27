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
import com.legoj15.megablockbot.game.body.BodyInput;
import com.legoj15.megablockbot.game.body.BotBrain;
import com.legoj15.megablockbot.game.body.BotPlayer;
import com.legoj15.megablockbot.game.body.DeathRecord;
import com.legoj15.megablockbot.game.gametest.BotNavGameTests.NavRun;
import com.legoj15.megablockbot.game.nav.BodyInteractionManager;
import com.legoj15.megablockbot.game.nav.BotNavigator;
import com.legoj15.megablockbot.game.task.BreakRules;
import com.legoj15.megablockbot.game.task.MineTask;
import com.legoj15.megablockbot.game.task.TaskFeature;
import com.legoj15.megablockbot.game.task.TaskRegistry;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.AABB;
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
     * NoSource naming the item, the block, the source id the planner chose and how far it looked.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 2700)
    public static void mineWhereNothingGrowsFailsNoSource(GameTestHelper helper) {
        room(helper, 3, Blocks.BARRIER);
        MineTask.Search small = new MineTask.Search(8, 100, 24, 3);
        Job job = Job.start(helper, "mnone", new Vec3(7.5, FLOOR_Y + 1, 7.5), 2700,
            new MineTask(mineLeaf("diamond", 1, "diamond_ore", Map.of()), Map.of(), small));
        job.run.each(2100, () -> {
            helper.assertTrue(job.task.state() != TaskState.COMPLETED, "found diamond in a sealed arena");
            if (job.task.state() != TaskState.FAILED) return;
            if (!(job.task.failure() instanceof Failure.NoSource none)) {
                helper.fail("failed as " + job.task.failure());
                return;
            }
            helper.assertTrue(none.key().equals(ItemKey.of("diamond")), "NoSource names " + none.key());
            helper.assertTrue(none.source().equals("mine:minecraft:diamond_ore"), "NoSource carries source '" + none.source() + "'");
            helper.assertTrue(none.detail().contains("diamond_ore") && none.detail().contains("blocks"), "no block or distance in: " + none.detail());
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
            new MineTask(mineLeaf("diamond", 1, "diamond_ore", Map.of()), checkpoint, small));
        job.run.each(600, () -> {
            if (job.task.state() == TaskState.COMPLETED) helper.fail("found diamond in a sealed arena");
            if (job.task.state() != TaskState.FAILED) return;
            if (!(job.task.failure() instanceof Failure.NoSource none)) {
                helper.fail("failed as " + job.task.failure());
                return;
            }
            // Not the sealed arena's quick failure to explore: the budget the checkpoint carried is what ended the search.
            helper.assertTrue(none.detail().contains("explored 24 blocks in 1 attempts"), "the search did not start from the checkpoint: " + none.detail());
            helper.assertTrue(job.run.ticks <= 40, "took " + job.run.ticks + " ticks: it searched again");
            helper.assertTrue(job.task.checkpoint().containsKey("origin"), "no checkpoint to hand on: " + job.task.checkpoint());
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
            helper.assertTrue(unreachable.navReason().contains("dimension"), "the reason does not say why: " + unreachable.navReason());
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
        AABB box = arena(helper);
        ARENAS.put(box, Boolean.TRUE);
        BreakRules.set((level, at, state) -> {
            for (Map.Entry<AABB, Boolean> arena : ARENAS.entrySet()) {
                if (arena.getKey().contains(at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5)) return arena.getValue();
            }
            return false;
        });
        run.atEnd(() -> {
            ARENAS.remove(box);
            if (ARENAS.isEmpty()) BreakRules.reset();
        });
        return box;
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
