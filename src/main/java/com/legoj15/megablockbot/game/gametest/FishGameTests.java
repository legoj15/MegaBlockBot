package com.legoj15.megablockbot.game.gametest;

import static com.legoj15.megablockbot.game.gametest.BotNavGameTests.FLOOR_Y;
import static com.legoj15.megablockbot.game.gametest.BotNavGameTests.room;

import com.legoj15.megablockbot.MegaBlockBot;
import com.legoj15.megablockbot.core.ItemKey;
import com.legoj15.megablockbot.core.goal.Goal;
import com.legoj15.megablockbot.core.plan.NodeSpec;
import com.legoj15.megablockbot.core.task.Failure;
import com.legoj15.megablockbot.core.task.Task;
import com.legoj15.megablockbot.core.task.TaskState;
import com.legoj15.megablockbot.core.task.TickResult;
import com.legoj15.megablockbot.game.body.BotBrain;
import com.legoj15.megablockbot.game.body.BotPlayer;
import com.legoj15.megablockbot.game.body.DeathRecord;
import com.legoj15.megablockbot.game.gametest.BotNavGameTests.NavRun;
import com.legoj15.megablockbot.game.nav.BotNavigator;
import com.legoj15.megablockbot.game.task.FishTask;
import com.legoj15.megablockbot.game.task.TaskFeature;
import com.legoj15.megablockbot.game.task.TaskRegistry;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The task that fishes (issue #16): what a {@code fish} leaf becomes in the world, through the same harness the
 * move-and-mine tests use (a body, a small brain that ticks the task and then the navigator, a tick budget). Each test
 * builds the leaf the way the planner writes it and asserts the task's end state and result, what the body holds, and
 * that no hook is left in the arena.
 *
 * <p>The pool is open to the sky on purpose: open water above the bobber is what gives the game's normal waiting times
 * (a covered sky makes the game wait longer before a bite), and these tests want the short waits.
 */
public final class FishGameTests {
    private static final String ITEM = "minecraft:";

    private FishGameTests() {}

    /**
     * A pool of 5 by 5 water sources, two deep, sunk one block into the floor so the water's surface is below it: the
     * body stands on the bank and casts down at it. The cells above the water are the arena's open air.
     */
    private static void pool(GameTestHelper helper, int x0, int z0) {
        // Walls first: under the arena's floor there is only air, and water with no side to it runs off beneath the
        // floor and carries the hook with it.
        for (int x = x0 - 1; x <= x0 + 5; x++) {
            for (int z = z0 - 1; z <= z0 + 5; z++) {
                if (x >= x0 && x < x0 + 5 && z >= z0 && z < z0 + 5) continue;
                helper.setBlock(x, FLOOR_Y - 2, z, Blocks.BARRIER);
                helper.setBlock(x, FLOOR_Y - 1, z, Blocks.BARRIER);
            }
        }
        for (int x = x0; x < x0 + 5; x++) {
            for (int z = z0; z < z0 + 5; z++) {
                helper.setBlock(x, FLOOR_Y - 3, z, Blocks.BARRIER);
                helper.setBlock(x, FLOOR_Y - 2, z, Blocks.WATER);
                helper.setBlock(x, FLOOR_Y - 1, z, Blocks.WATER);
                // The room's floor goes over the whole arena: over the pool it is taken out, or the water lies sealed
                // under it and there is nothing to cast into.
                helper.setBlock(x, FLOOR_Y, z, Blocks.AIR);
            }
        }
    }

    /** A fish leaf as the planner writes it: the goal is an absolute count of the item, the source names the table. */
    private static NodeSpec fishLeaf(String item, int count) {
        Map<String, String> params = new HashMap<>();
        params.put("item", ITEM + item);
        params.put("count", Integer.toString(count));
        params.put("table", ITEM + "gameplay/fishing");
        return NodeSpec.leaf(new Goal.HaveItem(ItemKey.of(item), count), TaskFeature.FISH, params,
                "fish:" + ITEM + "gameplay/fishing");
    }

    /**
     * A rod, given to the body once before the task starts (set-up): plain, or enchanted with Lure III as a player's
     * would be. Lure takes five seconds off the wait per level, so with Lure III a cast waits 2 to 25 seconds instead
     * of 5 to 30; it does not change what is caught, and it is not what is under test.
     */
    private static ItemStack rod(GameTestHelper helper, boolean lured) {
        ItemStack rod = new ItemStack(Items.FISHING_ROD);
        if (lured) {
            Holder<Enchantment> lure = helper.getLevel().registryAccess()
                    .registryOrThrow(Registries.ENCHANTMENT).getHolderOrThrow(Enchantments.LURE);
            rod.enchant(lure, 3);
        }
        return rod;
    }

    /** The hook entities still standing in this test's arena. */
    private static long hooksInArena(GameTestHelper helper) {
        BlockPos corner = helper.absolutePos(BlockPos.ZERO);
        AABB arena = new AABB(corner.getX(), corner.getY(), corner.getZ(),
                corner.getX() + 16, corner.getY() + 16, corner.getZ() + 16);
        return helper.getLevel().getEntitiesOfClass(FishingHook.class, arena, hook -> !hook.isRemoved()).size();
    }

    // ---------------------------------------------------------------------------------------------------------
    // The catch
    // ---------------------------------------------------------------------------------------------------------

    /**
     * A body holding a rod (set-up, once before the task starts) is given a {@code fish} leaf for one cod from the
     * fishing table: within the test's budget the body holds a cod, caught by its own casts. Fishing is random: a
     * cod is about half of what a cast brings up (the fish pool is 85 per cent of the table and cod 60 of the fish
     * weights), so over the twenty-odd casts this budget allows the odds of not one cod are about one in a million.
     * The body never stood in the water (its position is tracked every tick), and no hook is left in the arena.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 8400)
    public static void fishingCatchesACodFromThePool(GameTestHelper helper) {
        room(helper, 4, Blocks.BARRIER);
        pool(helper, 8, 8);
        Job job = Job.start(helper, "fcatch", new Vec3(3.5, FLOOR_Y + 1, 3.5), 8400, fishLeaf("cod", 1));
        job.body().getInventory().setItem(0, rod(helper, true));
        boolean[] inWater = {false};
        job.run.each(8100, () -> {
            failIfFailed(helper, job);
            BotPlayer body = job.body();
            inWater[0] |= body.level().getFluidState(body.blockPosition()).is(FluidTags.WATER);
            if (job.task.state() != TaskState.COMPLETED) return;
            helper.assertTrue(body.getInventory().countItem(Items.COD) >= 1, "completed holding no cod");
            helper.assertTrue(!inWater[0], "the body stood in the water");
            helper.assertTrue(hooksInArena(helper) == 0, "a hook is left in the arena");
            job.run.pass();
        });
    }

    /** No rod carried: the task fails MissingInput naming the rod, and the body does not move. */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 200)
    public static void fishingWithoutARodFailsMissingInput(GameTestHelper helper) {
        room(helper, 4, Blocks.BARRIER);
        pool(helper, 8, 8);
        Vec3 start = new Vec3(3.5, FLOOR_Y + 1, 3.5);
        Job job = Job.start(helper, "fnorod", start, 200, fishLeaf("cod", 1));
        job.run.each(100, () -> {
            helper.assertTrue(job.task.state() != TaskState.COMPLETED, "caught a cod with no rod");
            if (job.task.state() != TaskState.FAILED) return;
            if (!(job.task.failure() instanceof Failure.MissingInput missing)) {
                helper.fail("failed as " + job.task.failure());
                return;
            }
            helper.assertTrue(missing.key().equals(ItemKey.of("fishing_rod")), "MissingInput names " + missing.key());
            helper.assertTrue(helper.relativeVec(job.body().position()).distanceTo(start) < 0.5,
                    "the body moved to " + job.body().position());
            helper.assertTrue(hooksInArena(helper) == 0, "a hook is left in the arena");
            job.run.pass();
        });
    }

    /**
     * A sealed barrier room with no water in it or near it (the search is narrowed, for the test, to the arena's own
     * cells): the task fails {@code NoSource} naming the leaf's item and source id within the bound its small search
     * settings give, so the executor excludes fishing and the next plan gathers the item another way.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 900)
    public static void fishingWithNoWaterFailsNoSource(GameTestHelper helper) {
        room(helper, 4, Blocks.BARRIER);
        FishTask.Search small = new FishTask.Search(6, 8, 120, 8, 3);
        Job job = Job.start(helper, "fnowater", new Vec3(7.5, FLOOR_Y + 1, 7.5), 900,
                new FishTask(fishLeaf("cod", 1), Map.of(), small));
        job.body().getInventory().setItem(0, rod(helper, false));
        job.run.each(600, () -> {
            helper.assertTrue(job.task.state() != TaskState.COMPLETED, "caught a cod with no water");
            if (job.task.state() != TaskState.FAILED) return;
            if (!(job.task.failure() instanceof Failure.NoSource none)) {
                helper.fail("failed as " + job.task.failure());
                return;
            }
            helper.assertTrue(none.key().equals(ItemKey.of("cod")), "NoSource names " + none.key());
            helper.assertTrue(none.source().equals("fish:minecraft:gameplay/fishing"),
                    "NoSource carries source '" + none.source() + "'");
            MegaBlockBot.LOGGER.info("Fish GameTest: NoSource after {} ticks: {}", job.run.ticks, none.detail());
            job.run.pass();
        });
    }

    /**
     * A pause while the hook is out and a resume: the pause reels the line in (no hook entity is left in the arena
     * while the task is paused) and resets the input, and the resumed task still ends with the cod.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 8400)
    public static void aPausedFishingTaskResumesAndStillCatches(GameTestHelper helper) {
        room(helper, 4, Blocks.BARRIER);
        pool(helper, 8, 8);
        Job job = Job.start(helper, "fpause", new Vec3(3.5, FLOOR_Y + 1, 3.5), 8400, fishLeaf("cod", 1));
        job.body().getInventory().setItem(0, rod(helper, true));
        int[] pausedAt = {-1};
        boolean[] pauseChecked = {false};
        job.run.each(8100, () -> {
            failIfFailed(helper, job);
            BotPlayer body = job.body();
            FishingHook hook = body.fishing;
            if (pausedAt[0] < 0) {
                if (hook != null && hook.isAlive() && hook.isInWater()) {
                    job.task.pause(body, "test");
                    pausedAt[0] = job.run.ticks;
                }
                return;
            }
            if (!pauseChecked[0] && job.run.ticks >= pausedAt[0] + 5) {
                helper.assertTrue(job.task.state() == TaskState.PAUSED, "the paused task is " + job.task.state());
                helper.assertTrue(hooksInArena(helper) == 0, "a hook is left in the arena during the pause");
                helper.assertTrue(hookOf(body) == null, "the body's line is still out during the pause");
                job.task.resume(body);
                helper.assertTrue(job.task.state() == TaskState.RUNNING, "the resumed task is " + job.task.state());
                pauseChecked[0] = true;
            }
            if (job.task.state() != TaskState.COMPLETED) return;
            helper.assertTrue(body.getInventory().countItem(Items.COD) >= 1, "completed holding no cod");
            helper.assertTrue(hooksInArena(helper) == 0, "a hook is left in the arena");
            job.run.pass();
        });
    }

    /** The hook this body still has out, or null when there is none. */
    private static FishingHook hookOf(BotPlayer body) {
        FishingHook hook = body.fishing;
        return hook != null && hook.isAlive() ? hook : null;
    }

    private static void failIfFailed(GameTestHelper helper, Job job) {
        if (job.task.state() == TaskState.FAILED) helper.fail("the task failed: " + job.task.failure());
    }

    // ---------------------------------------------------------------------------------------------------------
    // Harness (the move-and-mine tests' own: a task ticked, then the navigator)
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
            // Breaking is allowed in this arena and nowhere outside the registered ones, as for the move-and-mine
            // tests (the rules are global and the tests run side by side in one world).
            run.atEnd(MoveAndMineGameTests.allowBreakingIn(helper));
            return new Job(run, task);
        }

        BotPlayer body() {
            return run.body();
        }
    }
}
