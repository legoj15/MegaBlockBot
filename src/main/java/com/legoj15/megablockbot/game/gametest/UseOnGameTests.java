package com.legoj15.megablockbot.game.gametest;

import static com.legoj15.megablockbot.game.gametest.BotNavGameTests.FLOOR_Y;
import static com.legoj15.megablockbot.game.gametest.BotNavGameTests.room;

import com.legoj15.megablockbot.MegaBlockBot;
import com.legoj15.megablockbot.core.ItemKey;
import com.legoj15.megablockbot.core.Pos;
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
import com.legoj15.megablockbot.game.task.TaskFeature;
import com.legoj15.megablockbot.game.task.TaskRegistry;
import com.legoj15.megablockbot.game.task.UseOnTask;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.Salmon;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * The task that uses a held tool on something (issue #28): what a {@code use_on} leaf becomes in the world, through
 * the same harness the kill-harvest tests use (a body, a small brain that ticks the task and then the navigator, a
 * tick budget). Each test builds the leaf the way the planner writes it and asserts the task's end state: the body
 * holds the item, taken by the body's own hand through the game's own use, and what the use took from is gone or
 * spared as it should be. No assertion on the wording of a phrase.
 *
 * <p>Every test is in a batch of its own, as the kill tests' flocks are: the search for a target does not stop at
 * walls, so a lava basin or an animal in one arena is a target for a body standing in another, and two tests run in
 * one batch share a world at the same time. The mobs are placed at the arena's absolute position, as the kill-harvest
 * tests place theirs. The tests whose bodies only walk (never fight) make them invulnerable: arenas share one world,
 * and a neighbour's mob may reach them. The lava test does not, because what it measures is that the body's health is
 * what it was.
 */
public final class UseOnGameTests {
    private static final String ITEM = "minecraft:";

    private UseOnGameTests() {}

    // ---------------------------------------------------------------------------------------------------------
    // A bucket on a fluid
    // ---------------------------------------------------------------------------------------------------------

    /**
     * A body with one bucket beside a walled basin holding one lava source (set-up, once before the task starts): a
     * {@code use_on} leaf for one lava bucket ends with the body holding one, the basin's cell no longer lava, and the
     * body's health what it was — it stood on the bank, not in the lava, and pathed to a dry stand.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 1200, batch = "useon_lava")
    public static void aBucketBesideAWalledLavaBasinEndsHoldingALavaBucket(GameTestHelper helper) {
        room(helper, 3, Blocks.BARRIER);
        BlockPos cell = new BlockPos(8, FLOOR_Y + 1, 8);
        basin(helper, cell, Blocks.LAVA);
        Job job = Job.start(helper, "ulava", new Vec3(4.5, FLOOR_Y + 1, 4.5), 1200,
            useOnLeaf("lava_bucket", 1, "bucket", true, "fluid", "lava", null));
        job.body().getInventory().setItem(0, new ItemStack(Items.BUCKET));
        float health = job.body().getHealth();
        job.run.each(1000, () -> {
            failIfFailed(helper, job);
            if (job.task.state() != TaskState.COMPLETED) return;
            helper.assertTrue(job.body().getInventory().countItem(Items.LAVA_BUCKET) >= 1,
                "completed holding no lava bucket");
            helper.assertTrue(job.body().getInventory().countItem(Items.BUCKET) == 0, "the bucket was not spent");
            helper.assertTrue(!helper.getBlockState(cell).getFluidState().is(FluidTags.LAVA),
                "the cell is still lava: " + helper.getBlockState(cell));
            helper.assertTrue(job.body().getHealth() == health,
                "the body's health changed: " + job.body().getHealth() + " was " + health);
            MegaBlockBot.LOGGER.info("Use-on GameTest: lava bucket after {} ticks", job.run.ticks);
            job.run.pass();
        });
    }

    // ---------------------------------------------------------------------------------------------------------
    // A bucket on a mob
    // ---------------------------------------------------------------------------------------------------------

    /**
     * A penned cow and a body with a bucket: a {@code use_on} leaf for one milk bucket ends with the body holding
     * one and the cow still alive (milking takes nothing from it but the milk).
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 1200, batch = "useon_cow")
    public static void aBucketAndAPennedCowEndWithAMilkBucketAndTheCowAlive(GameTestHelper helper) {
        room(helper, 3, Blocks.BARRIER);
        pen(helper);
        Cow cow = cow(helper, new Vec3(7.5, FLOOR_Y + 1, 10.5));
        Job job = Job.start(helper, "umilk", new Vec3(4.5, FLOOR_Y + 1, 4.5), 1200,
            useOnLeaf("milk_bucket", 1, "bucket", true, "entity", "cow", null));
        job.body().getInventory().setItem(0, new ItemStack(Items.BUCKET));
        // The body only walks: a neighbour's mob may reach it.
        job.body().setInvulnerable(true);
        job.run.atEnd(() -> cow.discard());
        job.run.each(1000, () -> {
            failIfFailed(helper, job);
            if (job.task.state() != TaskState.COMPLETED) return;
            helper.assertTrue(job.body().getInventory().countItem(Items.MILK_BUCKET) >= 1,
                "completed holding no milk bucket");
            helper.assertTrue(cow.isAlive() && !cow.isRemoved(), "the cow is not alive at the end");
            MegaBlockBot.LOGGER.info("Use-on GameTest: milk bucket after {} ticks", job.run.ticks);
            job.run.pass();
        });
    }

    /**
     * Two cows and a bucket: the nearer one sealed in a pen with no gap and walls thick enough that the body cannot
     * come within the game's reach of it, the other free in the arena. A follow command never fails for want of a
     * path, so the sealed one has to be given up on -- the chase watched, as the kill task watches one -- before the
     * free one is milked; the task ends holding a milk bucket and the sealed cow alive and unmilked.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 2400, batch = "useon_sealed")
    public static void aSealedCowIsGivenUpForTheFreeOne(GameTestHelper helper) {
        room(helper, 3, Blocks.BARRIER);
        sealedPen(helper);
        Cow sealedCow = cow(helper, new Vec3(7.5, FLOOR_Y + 1, 10.5));
        Cow freeCow = cow(helper, new Vec3(12.5, FLOOR_Y + 1, 3.5));
        Job job = Job.start(helper, "useal", new Vec3(4.5, FLOOR_Y + 1, 4.5), 2400,
            useOnLeaf("milk_bucket", 1, "bucket", true, "entity", "cow", null));
        job.body().getInventory().setItem(0, new ItemStack(Items.BUCKET));
        // The body only walks: a neighbour's mob may reach it.
        job.body().setInvulnerable(true);
        job.run.atEnd(() -> {
            sealedCow.discard();
            freeCow.discard();
        });
        job.run.each(2000, () -> {
            failIfFailed(helper, job);
            if (job.task.state() != TaskState.COMPLETED) return;
            helper.assertTrue(job.body().getInventory().countItem(Items.MILK_BUCKET) >= 1,
                "completed holding no milk bucket");
            helper.assertTrue(sealedCow.isAlive() && !sealedCow.isRemoved(), "the sealed cow is not alive at the end");
            MegaBlockBot.LOGGER.info("Use-on GameTest: milk from the free cow after {} ticks", job.run.ticks);
            job.run.pass();
        });
    }

    /**
     * A walled pool, open to the air and two deep, holding one salmon, and a body with a water bucket: a
     * {@code use_on} leaf for one salmon bucket ends with the body holding one.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 2400, batch = "useon_fish")
    public static void aWaterBucketAndAPoolEndWithASalmonBucket(GameTestHelper helper) {
        room(helper, 4, Blocks.BARRIER);
        pool(helper, 8, 8);
        Salmon salmon = salmon(helper, new Vec3(10.5, FLOOR_Y - 1, 10.5));
        Job job = Job.start(helper, "ufish", new Vec3(4.5, FLOOR_Y + 1, 4.5), 2400,
            useOnLeaf("salmon_bucket", 1, "water_bucket", true, "entity", "salmon", null));
        job.body().getInventory().setItem(0, new ItemStack(Items.WATER_BUCKET));
        job.body().setInvulnerable(true);
        job.run.atEnd(() -> salmon.discard());
        job.run.each(2100, () -> {
            failIfFailed(helper, job);
            if (job.task.state() != TaskState.COMPLETED) return;
            helper.assertTrue(job.body().getInventory().countItem(Items.SALMON_BUCKET) >= 1,
                "completed holding no salmon bucket");
            MegaBlockBot.LOGGER.info("Use-on GameTest: salmon bucket after {} ticks", job.run.ticks);
            job.run.pass();
        });
    }

    // ---------------------------------------------------------------------------------------------------------
    // Shears on a sheep
    // ---------------------------------------------------------------------------------------------------------

    /**
     * A penned white sheep and a body with shears: a {@code use_on} leaf for one white wool ends with the body
     * holding wool (picked up from where the shearing dropped it), the sheep alive and sheared.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 1200, batch = "useon_sheep")
    public static void shearsAndAPennedWhiteSheepEndHoldingWoolWithTheSheepSheared(GameTestHelper helper) {
        room(helper, 3, Blocks.BARRIER);
        pen(helper);
        Sheep sheep = sheep(helper, new Vec3(7.5, FLOOR_Y + 1, 10.5));
        Job job = Job.start(helper, "uwool", new Vec3(4.5, FLOOR_Y + 1, 4.5), 1200,
            useOnLeaf("white_wool", 1, "shears", false, "entity", "sheep", "white"));
        job.body().getInventory().setItem(0, new ItemStack(Items.SHEARS));
        job.body().setInvulnerable(true);
        job.run.atEnd(() -> sheep.discard());
        job.run.each(1000, () -> {
            failIfFailed(helper, job);
            if (job.task.state() != TaskState.COMPLETED) return;
            helper.assertTrue(job.body().getInventory().countItem(Items.WHITE_WOOL) >= 1,
                "completed holding no white wool");
            helper.assertTrue(job.body().getInventory().countItem(Items.SHEARS) >= 1, "the shears were not kept");
            helper.assertTrue(sheep.isAlive() && !sheep.isRemoved(), "the sheep is not alive at the end");
            helper.assertTrue(sheep.isSheared(), "the sheep is not sheared");
            MegaBlockBot.LOGGER.info("Use-on GameTest: wool after {} ticks", job.run.ticks);
            job.run.pass();
        });
    }

    // ---------------------------------------------------------------------------------------------------------
    // The failures
    // ---------------------------------------------------------------------------------------------------------

    /**
     * No lava anywhere in a sealed arena: the search ends within the bound its small settings give, with
     * {@code NoSource} naming the leaf's item and the source the planner chose.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 900, batch = "useon_none")
    public static void noLavaAnywhereFailsNoSource(GameTestHelper helper) {
        room(helper, 3, Blocks.BARRIER);
        Job job = Job.start(helper, "unone", new Vec3(7.5, FLOOR_Y + 1, 7.5), 900,
            new UseOnTask(useOnLeaf("lava_bucket", 1, "bucket", true, "fluid", "lava", null), Map.of(),
                new UseOnTask.Search(60, 2)));
        job.body().getInventory().setItem(0, new ItemStack(Items.BUCKET));
        job.body().setInvulnerable(true);
        job.run.each(700, () -> {
            helper.assertTrue(job.task.state() != TaskState.COMPLETED, "found lava in a sealed arena");
            if (job.task.state() != TaskState.FAILED) return;
            if (!(job.task.failure() instanceof Failure.NoSource none)) {
                helper.fail("failed as " + job.task.failure());
                return;
            }
            helper.assertTrue(none.key().equals(ItemKey.of("lava_bucket")), "NoSource names " + none.key());
            helper.assertTrue(none.source().equals("use:minecraft:bucket@fluid:minecraft:lava"),
                "NoSource carries source '" + none.source() + "'");
            helper.assertTrue(job.run.ticks <= 700, "failed only at tick " + job.run.ticks);
            MegaBlockBot.LOGGER.info("Use-on GameTest: NoSource after {} ticks: {}", job.run.ticks, none.detail());
            job.run.pass();
        });
    }

    /** No bucket carried: the task fails {@code MissingInput} naming the bucket, and the body does not move. */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 200, batch = "useon_notool")
    public static void noBucketCarriedFailsMissingInput(GameTestHelper helper) {
        room(helper, 3, Blocks.BARRIER);
        BlockPos cell = new BlockPos(8, FLOOR_Y + 1, 8);
        basin(helper, cell, Blocks.LAVA);
        Vec3 start = new Vec3(4.5, FLOOR_Y + 1, 4.5);
        Job job = Job.start(helper, "unotool", start, 200,
            useOnLeaf("lava_bucket", 1, "bucket", true, "fluid", "lava", null));
        job.run.each(100, () -> {
            helper.assertTrue(job.task.state() != TaskState.COMPLETED, "scooped lava with no bucket");
            if (job.task.state() != TaskState.FAILED) return;
            if (!(job.task.failure() instanceof Failure.MissingInput missing)) {
                helper.fail("failed as " + job.task.failure());
                return;
            }
            helper.assertTrue(missing.key().equals(ItemKey.of("minecraft:bucket")),
                "MissingInput names " + missing.key());
            helper.assertTrue(helper.relativeVec(job.body().position()).distanceTo(start) < 0.5,
                "the body moved to " + job.body().position());
            helper.assertTrue(helper.getBlockState(cell).getFluidState().is(FluidTags.LAVA), "the lava was taken");
            job.run.pass();
        });
    }

    /**
     * A lava source the break rules refuse, one cell above the arena the test registers (above every other test's
     * too, wherever they stand) and walled in so it cannot run, and a bucket: the task fails {@code Protected}
     * naming that cell and holds the empty bucket still.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 400, batch = "useon_refused")
    public static void lavaTheBreakRulesRefuseFailsProtected(GameTestHelper helper) {
        room(helper, 3, Blocks.BARRIER);
        BlockPos cell = new BlockPos(8, FLOOR_Y + 15, 8);
        helper.setBlock(cell.getX(), cell.getY() - 1, cell.getZ(), Blocks.STONE);
        basin(helper, cell, Blocks.LAVA);
        Job job = Job.start(helper, "urefus", new Vec3(4.5, FLOOR_Y + 1, 4.5), 400,
            useOnLeaf("lava_bucket", 1, "bucket", true, "fluid", "lava", null));
        job.body().getInventory().setItem(0, new ItemStack(Items.BUCKET));
        job.body().setInvulnerable(true);
        job.run.each(300, () -> {
            helper.assertTrue(job.task.state() != TaskState.COMPLETED, "scooped lava the rules refuse");
            if (job.task.state() != TaskState.FAILED) return;
            if (!(job.task.failure() instanceof Failure.Protected refused)) {
                helper.fail("failed as " + job.task.failure());
                return;
            }
            helper.assertTrue(refused.pos().equals(absolute(helper, cell)),
                "Protected names " + refused.pos() + ", not the lava at " + cell);
            helper.assertTrue(helper.getBlockState(cell).getFluidState().is(FluidTags.LAVA), "the cell is still lava");
            helper.assertTrue(job.body().getInventory().countItem(Items.BUCKET) == 1, "the bucket was spent");
            helper.assertTrue(job.body().getInventory().countItem(Items.LAVA_BUCKET) == 0, "holds a lava bucket");
            MegaBlockBot.LOGGER.info("Use-on GameTest: Protected after {} ticks: {}", job.run.ticks, refused.detail());
            job.run.pass();
        });
    }

    // ---------------------------------------------------------------------------------------------------------
    // Building leaves and terrain
    // ---------------------------------------------------------------------------------------------------------

    /** A use-on leaf as the planner writes it ({@code SolverPlanner}): the goal is an absolute count, the source names the use. */
    private static NodeSpec useOnLeaf(String item, int count, String tool, boolean spent, String targetKind,
                                      String target, @Nullable String variant) {
        Map<String, String> params = new HashMap<>();
        params.put("item", ITEM + item);
        params.put("count", Integer.toString(count));
        params.put("tool", ITEM + tool);
        params.put("spent", Boolean.toString(spent));
        params.put("targetKind", targetKind);
        params.put("target", ITEM + target);
        if (variant != null) params.put("variant", variant);
        return NodeSpec.leaf(new Goal.HaveItem(ItemKey.of(item), count), TaskFeature.USE_ON, params,
            "use:" + ITEM + tool + "@" + targetKind + ":" + ITEM + target + (variant == null ? "" : "#" + variant));
    }

    /**
     * A fluid source in the walking layer, walled in on the sides that are open: left to itself lava would run across
     * the arena floor and set it alight.
     */
    private static void basin(GameTestHelper helper, BlockPos at, Block fluid) {
        helper.setBlock(at.getX(), at.getY(), at.getZ(), fluid);
        int[][] sides = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] side : sides) {
            BlockPos beside = new BlockPos(at.getX() + side[0], at.getY(), at.getZ() + side[1]);
            if (helper.getBlockState(beside).isAir()) helper.setBlock(beside, Blocks.STONE);
        }
    }

    /**
     * A pool of 5 by 5 water sources, two deep, sunk one block into the floor so the water's surface is below it: open
     * to the air above, walled below (the fish tests' own pool, for a fish that lives in it).
     */
    private static void pool(GameTestHelper helper, int x0, int z0) {
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
                helper.setBlock(x, FLOOR_Y, z, Blocks.AIR);
            }
        }
    }

    /**
     * A pen for the mob (set-up): walls two high with a gap the body can walk through, so the animal stays put and
     * the task has to go in after it.
     */
    private static void pen(GameTestHelper helper) {
        for (int x = 5; x <= 10; x++) {
            for (int z = 9; z <= 12; z++) {
                boolean edge = x == 5 || x == 10 || z == 9 || z == 12;
                if (!edge || (x == 7 && z == 9)) continue; // the gap
                helper.setBlock(x, FLOOR_Y + 1, z, Blocks.OAK_PLANKS);
                helper.setBlock(x, FLOOR_Y + 2, z, Blocks.OAK_PLANKS);
            }
        }
    }

    /**
     * A pen with no gap: barrier four blocks thick around the one cell the animal stands in, two high as the penned
     * animals' pen is. The thickness is the point: the game's reach to an entity is three blocks from the eyes, and
     * four blocks of wall keep the body outside that, so the animal inside is one the body can neither walk to nor
     * lean over the wall at -- only give up on.
     */
    private static void sealedPen(GameTestHelper helper) {
        for (int x = 3; x <= 11; x++) {
            for (int z = 6; z <= 14; z++) {
                if (x == 7 && z == 10) continue; // the one cell the animal stands in
                helper.setBlock(x, FLOOR_Y + 1, z, Blocks.BARRIER);
                helper.setBlock(x, FLOOR_Y + 2, z, Blocks.BARRIER);
            }
        }
    }

    /** A cow made without the spawn code's dice, set down in this arena, persistent so it does not despawn mid-test. */
    private static Cow cow(GameTestHelper helper, Vec3 relative) {
        Cow cow = EntityType.COW.create(helper.getLevel());
        Vec3 at = helper.absoluteVec(relative);
        cow.moveTo(at.x, at.y, at.z, 0.0F, 0.0F);
        cow.setPersistenceRequired();
        helper.getLevel().addFreshEntity(cow);
        return cow;
    }

    /**
     * A salmon set down in the pool's water, in this arena, persistent so it does not despawn mid-test. Placed where
     * the water is and only then added, so it is in its element from its first tick: a fish out of water counts down to
     * suffocation, and the body takes a while to walk over.
     */
    private static Salmon salmon(GameTestHelper helper, Vec3 relative) {
        Salmon salmon = EntityType.SALMON.create(helper.getLevel());
        Vec3 at = helper.absoluteVec(relative);
        salmon.moveTo(at.x, at.y, at.z, 0.0F, 0.0F);
        salmon.setPersistenceRequired();
        helper.getLevel().addFreshEntity(salmon);
        return salmon;
    }

    /** A white sheep made without the spawn code's dice, set down in this arena, persistent so it does not despawn. */
    private static Sheep sheep(GameTestHelper helper, Vec3 relative) {
        Sheep sheep = EntityType.SHEEP.create(helper.getLevel());
        Vec3 at = helper.absoluteVec(relative);
        sheep.moveTo(at.x, at.y, at.z, 0.0F, 0.0F);
        sheep.setColor(DyeColor.WHITE);
        sheep.setPersistenceRequired();
        helper.getLevel().addFreshEntity(sheep);
        return sheep;
    }

    private static void failIfFailed(GameTestHelper helper, Job job) {
        if (job.task.state() == TaskState.FAILED) helper.fail("the task failed: " + job.task.failure());
    }

    /** A relative cell as a failure carries it: a plain position, in the world's own coordinates. */
    private static Pos absolute(GameTestHelper helper, BlockPos relative) {
        BlockPos at = helper.absolutePos(relative);
        return new Pos(at.getX(), at.getY(), at.getZ());
    }

    // ---------------------------------------------------------------------------------------------------------
    // Harness (the kill-harvest tests' own: a task ticked, then the navigator)
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

    /** One test: a body running one task, watched by the shared harness, inside this arena's break rules. */
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
            run.atEnd(MoveAndMineGameTests.allowBreakingIn(helper));
            return new Job(run, task);
        }

        BotPlayer body() {
            return run.body();
        }
    }
}
