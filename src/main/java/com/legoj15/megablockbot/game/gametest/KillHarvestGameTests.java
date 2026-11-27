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
import com.legoj15.megablockbot.game.task.HarvestTask;
import com.legoj15.megablockbot.game.task.KillTask;
import com.legoj15.megablockbot.game.task.TaskFeature;
import com.legoj15.megablockbot.game.task.TaskRegistry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The tasks that kill and harvest (issues 22 and 15): what a {@code kill} leaf and a {@code harvest} leaf become in
 * the world, and the typed result each ends with. What these protect: that such leaves end with the item, taken by the
 * body's own hands, and leave alone what is not the body's to take -- a named cow, an unripe crop, the lowest block of
 * a plant. Each test builds the leaf the way the planner would, gets its task from the {@link TaskRegistry} (or makes
 * one with small search bounds, for the failures), and runs it on a real body through the shared harness, inside a
 * tick budget. No assertion on the wording of a phrase.
 *
 * <p>The cow tests each have a batch of their own: the scan for a quarry does not stop at walls, so cows in one arena
 * are targets for a body standing in another, and tests that run side by side would go after each other's herds. The
 * tests whose bodies only walk and stand (never fight) make them invulnerable: arenas share one world, and a
 * neighbour's mob may reach them.
 */
public final class KillHarvestGameTests {
    private static final String ITEM = "minecraft:";

    private KillHarvestGameTests() {}

    // ---------------------------------------------------------------------------------------------------------
    // kill
    // ---------------------------------------------------------------------------------------------------------

    /**
     * A walled pen of cows and a body with a wooden sword (set-up, once before the task starts: the pen's walls keep
     * the herd where it is, with a gap the body can walk through), a {@code kill} leaf for one leather from a cow: it
     * ends holding at least one leather. A cow drops 0 to 2 leather (no leather one time in three), so with two cows a
     * run without any is one in nine; the pen holds four, which makes it one in eighty-one.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 3000, batch = "kill_pen")
    public static void aKillLeafGetsLeatherFromAPennedCow(GameTestHelper helper) {
        room(helper, 5, Blocks.BARRIER);
        pen(helper);
        List<Entity> cows = new ArrayList<>();
        for (int i = 0; i < 4; i++) cow(helper, cows, new Vec3(5.5 + i, FLOOR_Y + 1, 10.5 + (i % 2)), null);
        Job job = Job.start(helper, "kleath", new Vec3(7.5, FLOOR_Y + 1, 4.5), 3000, killLeaf("leather", 1, "cow"));
        job.body().getInventory().setItem(0, new ItemStack(Items.WOODEN_SWORD));
        job.run.atEnd(() -> cows.forEach(Entity::discard));
        job.run.each(2400, () -> {
            failIfFailed(helper, job);
            inside(helper, job.body());
            if (job.task.state() != TaskState.COMPLETED) return;
            int leather = job.body().getInventory().countItem(Items.LEATHER);
            helper.assertTrue(leather >= 1, "completed holding " + leather + " leather");
            MegaBlockBot.LOGGER.info("Kill-harvest GameTest: leather after {} ticks from {} cow(s)",
                job.run.ticks, cows.stream().filter(c -> !c.isAlive()).count());
            job.run.pass();
        });
    }

    /**
     * A cow with a custom name stands with four unnamed ones: the leaf's leather comes from an unnamed cow, and the
     * named one is alive at the end. Four unnamed, as in the pen test, so that a run without any leather (one in
     * eighty-one) cannot leave the task hunting for a cow it may not touch.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 3000, batch = "kill_named")
    public static void aNamedCowIsLeftAlone(GameTestHelper helper) {
        room(helper, 5, Blocks.BARRIER);
        pen(helper);
        List<Entity> cows = new ArrayList<>();
        Cow named = cow(helper, cows, new Vec3(6.5, FLOOR_Y + 1, 12.5), Component.literal("Daisy"));
        for (int i = 0; i < 4; i++) cow(helper, cows, new Vec3(5.5 + i, FLOOR_Y + 1, 10.5 + (i % 2)), null);
        Job job = Job.start(helper, "kname", new Vec3(7.5, FLOOR_Y + 1, 4.5), 3000, killLeaf("leather", 1, "cow"));
        job.body().getInventory().setItem(0, new ItemStack(Items.WOODEN_SWORD));
        job.run.atEnd(() -> cows.forEach(Entity::discard));
        job.run.each(2400, () -> {
            failIfFailed(helper, job);
            inside(helper, job.body());
            if (job.task.state() != TaskState.COMPLETED) return;
            int leather = job.body().getInventory().countItem(Items.LEATHER);
            helper.assertTrue(leather >= 1, "completed holding " + leather + " leather");
            helper.assertTrue(named.isAlive() && !named.isRemoved(), "the named cow is not alive at the end");
            MegaBlockBot.LOGGER.info("Kill-harvest GameTest: named cow alive after {} ticks", job.run.ticks);
            job.run.pass();
        });
    }

    /**
     * No cow anywhere in a sealed arena: the search ends within the bound its small settings give, with
     * {@code NoSource} naming the leaf's item and the source the planner chose.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 900)
    public static void noCowAnywhereFailsNoSource(GameTestHelper helper) {
        room(helper, 3, Blocks.BARRIER);
        Job job = Job.start(helper, "knone", new Vec3(7.5, FLOOR_Y + 1, 7.5), 900,
            new KillTask(killLeaf("leather", 1, "cow"), Map.of(), new KillTask.Search(60, 2)));
        // The body only stands and walks: it never fights, and a neighbour's mob may reach it.
        job.body().setInvulnerable(true);
        job.run.each(700, () -> {
            helper.assertTrue(job.task.state() != TaskState.COMPLETED, "found a cow in a sealed arena");
            if (job.task.state() != TaskState.FAILED) return;
            if (!(job.task.failure() instanceof Failure.NoSource none)) {
                helper.fail("failed as " + job.task.failure());
                return;
            }
            helper.assertTrue(none.key().equals(ItemKey.of("leather")), "NoSource names " + none.key());
            helper.assertTrue(none.source().equals("kill:minecraft:cow"), "NoSource carries source '" + none.source() + "'");
            helper.assertTrue(job.run.ticks <= 600, "failed only at tick " + job.run.ticks);
            MegaBlockBot.LOGGER.info("Kill-harvest GameTest: NoSource after {} ticks: {}", job.run.ticks, none.detail());
            job.run.pass();
        });
    }

    // ---------------------------------------------------------------------------------------------------------
    // harvest
    // ---------------------------------------------------------------------------------------------------------

    /**
     * A row of four wheat crops on farmland, two of them fully grown (set-up, once before the task starts; water
     * beside the row keeps the farmland from drying out where a crop has just been taken), a {@code harvest} leaf for
     * two wheat: it ends holding 2 wheat, the two unripe crops still stand, and the two harvested spots hold wheat
     * again at age 0 -- replanted, from the seeds the harvest gave the body (a grown wheat can drop none, so a few
     * spare seeds are put in the inventory at set-up: what the test measures is the taking and the putting back).
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 900, batch = "harvest_wheat")
    public static void aWheatHarvestReplantsWhatItTook(GameTestHelper helper) {
        room(helper, 3, Blocks.BARRIER);
        BlockState ripe = Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7);
        BlockState unripe = Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 2);
        for (int x = 4; x <= 7; x++) {
            helper.setBlock(x, FLOOR_Y + 1, 8, Blocks.FARMLAND);
            helper.setBlock(x, FLOOR_Y + 2, 8, x <= 5 ? ripe : unripe);
        }
        basin(helper, 5, 12);
        Job job = Job.start(helper, "hwheat", new Vec3(2.5, FLOOR_Y + 1, 4.5), 900, harvestLeaf("wheat", 2, "wheat"));
        // Set-up: spare seeds, and a body that only walks (a neighbour's mob may reach it).
        job.body().getInventory().setItem(1, new ItemStack(Items.WHEAT_SEEDS, 4));
        job.body().setInvulnerable(true);
        job.run.each(700, () -> {
            failIfFailed(helper, job);
            if (job.task.state() != TaskState.COMPLETED) return;
            int wheat = job.body().getInventory().countItem(Items.WHEAT);
            helper.assertTrue(wheat == 2, "completed holding " + wheat + " wheat, the goal is 2");
            for (int x = 6; x <= 7; x++) {
                BlockState standing = helper.getBlockState(new BlockPos(x, FLOOR_Y + 2, 8));
                helper.assertTrue(standing.is(Blocks.WHEAT) && standing.getValue(CropBlock.AGE) == 2,
                    "the unripe crop at " + x + " is " + standing);
            }
            for (int x = 4; x <= 5; x++) {
                BlockState back = helper.getBlockState(new BlockPos(x, FLOOR_Y + 2, 8));
                helper.assertTrue(back.is(Blocks.WHEAT) && back.getValue(CropBlock.AGE) == 0,
                    "the harvested spot at " + x + " is " + back + ", not wheat replanted at age 0");
            }
            MegaBlockBot.LOGGER.info("Kill-harvest GameTest: wheat taken and replanted after {} ticks", job.run.ticks);
            job.run.pass();
        });
    }

    /**
     * A sweet berry bush with berries: the body ends holding berries and the bush still stands, picked rather than
     * broken. The body is invulnerable: a bush pricks whoever stands in it.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 600)
    public static void aBerryBushIsPickedNotBroken(GameTestHelper helper) {
        room(helper, 3, Blocks.BARRIER);
        helper.setBlock(8, FLOOR_Y + 1, 8, Blocks.GRASS_BLOCK);
        helper.setBlock(8, FLOOR_Y + 2, 8, Blocks.SWEET_BERRY_BUSH.defaultBlockState().setValue(SweetBerryBushBlock.AGE, 3));
        Job job = Job.start(helper, "hberry", new Vec3(2.5, FLOOR_Y + 1, 4.5), 600,
            harvestLeaf("sweet_berries", 1, "sweet_berry_bush"));
        job.body().setInvulnerable(true);
        job.run.each(500, () -> {
            failIfFailed(helper, job);
            if (job.task.state() != TaskState.COMPLETED) return;
            int berries = job.body().getInventory().countItem(Items.SWEET_BERRIES);
            helper.assertTrue(berries >= 1, "completed holding " + berries + " sweet berries");
            BlockState bush = helper.getBlockState(new BlockPos(8, FLOOR_Y + 2, 8));
            helper.assertTrue(bush.is(Blocks.SWEET_BERRY_BUSH), "the bush is gone: " + bush);
            MegaBlockBot.LOGGER.info("Kill-harvest GameTest: berries picked after {} ticks, bush is {}", job.run.ticks, bush);
            job.run.pass();
        });
    }

    /**
     * Sugar cane three high on sand beside water (the water is what lets the cane stand): the body ends holding cane
     * and the lowest block still stands, so the plant grows again.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 600)
    public static void sugarCaneKeepsItsLowestBlock(GameTestHelper helper) {
        room(helper, 3, Blocks.BARRIER);
        helper.setBlock(7, FLOOR_Y + 1, 8, Blocks.SAND);
        basin(helper, 8, 8);
        for (int y = FLOOR_Y + 2; y <= FLOOR_Y + 4; y++) helper.setBlock(7, y, 8, Blocks.SUGAR_CANE);
        Job job = Job.start(helper, "hcane", new Vec3(2.5, FLOOR_Y + 1, 4.5), 600, harvestLeaf("sugar_cane", 1, "sugar_cane"));
        job.body().setInvulnerable(true);
        job.run.each(500, () -> {
            failIfFailed(helper, job);
            if (job.task.state() != TaskState.COMPLETED) return;
            int cane = job.body().getInventory().countItem(Items.SUGAR_CANE);
            helper.assertTrue(cane >= 1, "completed holding " + cane + " sugar cane");
            helper.assertBlockPresent(Blocks.SUGAR_CANE, new BlockPos(7, FLOOR_Y + 2, 8));
            MegaBlockBot.LOGGER.info("Kill-harvest GameTest: cane taken after {} ticks, the lowest block stands", job.run.ticks);
            job.run.pass();
        });
    }

    /**
     * Only an unripe crop in a sealed arena: {@code NoSource} (the planner has no wait-for-it-to-grow), and the crop
     * still stands.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 900)
    public static void onlyAnUnripeCropFailsNoSource(GameTestHelper helper) {
        room(helper, 3, Blocks.BARRIER);
        helper.setBlock(7, FLOOR_Y + 1, 8, Blocks.FARMLAND);
        helper.setBlock(7, FLOOR_Y + 2, 8, Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 1));
        Job job = Job.start(helper, "hunrip", new Vec3(2.5, FLOOR_Y + 1, 4.5), 900,
            new HarvestTask(harvestLeaf("wheat", 1, "wheat"), Map.of(), new HarvestTask.Search(60, 2)));
        job.body().setInvulnerable(true);
        job.run.each(700, () -> {
            helper.assertTrue(job.task.state() != TaskState.COMPLETED, "harvested an unripe crop");
            if (job.task.state() != TaskState.FAILED) return;
            if (!(job.task.failure() instanceof Failure.NoSource none)) {
                helper.fail("failed as " + job.task.failure());
                return;
            }
            helper.assertTrue(none.key().equals(ItemKey.of("wheat")), "NoSource names " + none.key());
            helper.assertTrue(none.source().equals("harvest:minecraft:wheat"), "NoSource carries source '" + none.source() + "'");
            helper.assertTrue(job.run.ticks <= 600, "failed only at tick " + job.run.ticks);
            BlockState standing = helper.getBlockState(new BlockPos(7, FLOOR_Y + 2, 8));
            helper.assertTrue(standing.is(Blocks.WHEAT) && standing.getValue(CropBlock.AGE) == 1,
                "the unripe crop is " + standing);
            MegaBlockBot.LOGGER.info("Kill-harvest GameTest: NoSource after {} ticks: {}", job.run.ticks, none.detail());
            job.run.pass();
        });
    }

    // ---------------------------------------------------------------------------------------------------------
    // Building leaves and terrain
    // ---------------------------------------------------------------------------------------------------------

    /** A kill leaf as the planner writes it ({@code SolverPlanner}): the goal is an absolute count, the source names the mob. */
    private static NodeSpec killLeaf(String item, int count, String entity) {
        Map<String, String> params = new HashMap<>();
        params.put("item", ITEM + item);
        params.put("count", Integer.toString(count));
        params.put("entity", ITEM + entity);
        params.put("playerKillOnly", "false");
        return NodeSpec.leaf(new Goal.HaveItem(ItemKey.of(item), count), TaskFeature.KILL, params, "kill:" + ITEM + entity);
    }

    /** A harvest leaf as the planner writes it: the goal is an absolute count, the source names the block. */
    private static NodeSpec harvestLeaf(String item, int count, String block) {
        Map<String, String> params = new HashMap<>();
        params.put("item", ITEM + item);
        params.put("count", Integer.toString(count));
        params.put("block", ITEM + block);
        return NodeSpec.leaf(new Goal.HaveItem(ItemKey.of(item), count), TaskFeature.HARVEST, params, "harvest:" + ITEM + block);
    }

    /**
     * A pen for the cows (set-up): walls two high with a gap the body can walk through, so the herd stays put and the
     * task has to go in after it.
     */
    private static void pen(GameTestHelper helper) {
        for (int x = 4; x <= 10; x++) {
            for (int z = 8; z <= 13; z++) {
                boolean edge = x == 4 || x == 10 || z == 8 || z == 13;
                if (!edge) continue;
                if (x == 7 && z == 8) continue; // the gap
                helper.setBlock(x, FLOOR_Y + 1, z, Blocks.OAK_PLANKS);
                helper.setBlock(x, FLOOR_Y + 2, z, Blocks.OAK_PLANKS);
            }
        }
    }

    /** A cow made without the spawn code's dice, persistent so it does not despawn mid-test; named when the test says so. */
    private static Cow cow(GameTestHelper helper, List<Entity> mobs, Vec3 relative, Component name) {
        Cow cow = EntityType.COW.create(helper.getLevel());
        Vec3 at = helper.absoluteVec(relative);
        cow.moveTo(at.x, at.y, at.z, 0.0F, 0.0F);
        cow.setPersistenceRequired();
        if (name != null) cow.setCustomName(name);
        helper.getLevel().addFreshEntity(cow);
        mobs.add(cow);
        return cow;
    }

    /**
     * A water source in the walking layer, walled in on the sides that are open: left to itself it would run across the
     * arena floor and carry off whatever a harvest drops.
     */
    private static void basin(GameTestHelper helper, int x, int z) {
        helper.setBlock(x, FLOOR_Y + 1, z, Blocks.WATER);
        int[][] sides = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] side : sides) {
            BlockPos beside = new BlockPos(x + side[0], FLOOR_Y + 1, z + side[1]);
            if (helper.getBlockState(beside).isAir()) helper.setBlock(beside, Blocks.STONE);
        }
    }

    /** The body never left the arena: fails at once when it does. */
    private static void inside(GameTestHelper helper, BotPlayer body) {
        Vec3 at = helper.relativeVec(body.position());
        helper.assertTrue(at.x >= 0.0 && at.x <= 16.0 && at.z >= 0.0 && at.z <= 16.0,
            "the body left the arena: " + at);
    }

    private static void failIfFailed(GameTestHelper helper, Job job) {
        if (job.task.state() == TaskState.FAILED) helper.fail("the task failed: " + job.task.failure());
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
