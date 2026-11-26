package com.legoj15.megablockbot.game.gametest;

import com.legoj15.megablockbot.MegaBlockBot;
import com.legoj15.megablockbot.game.body.BodyException;
import com.legoj15.megablockbot.game.body.BodyManager;
import com.legoj15.megablockbot.game.body.BotBrain;
import com.legoj15.megablockbot.game.body.BotPlayer;
import com.legoj15.megablockbot.game.body.DeathRecord;
import com.legoj15.megablockbot.game.nav.BotNavigator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The acceptance check of navigation driving the body (design 2.2, issue 7): a real {@link BotPlayer} in the test
 * arena, driven only by its {@link BotNavigator} (ticked from the body's brain, as a real brain will), on terrain each
 * test builds. Each asserts the outcome within a tick budget and that the body never moved more than one block in a
 * single tick (no teleport, no velocity set from outside).
 *
 * <p>The arena is open at its sides, so every test that must leave the body only the routes it builds walls the
 * arena with barrier blocks, which nothing can break or climb. Relative coordinates; bodies are named uniquely per run
 * and removed when the test ends, as in {@link BodyGameTests}.
 */
public final class BotNavGameTests {
    private static final String RUN_ID = Integer.toString((int) (System.nanoTime() & 0xFFFFF), 36);
    private static final int FLOOR_Y = 1;

    /** Every block a bot body broke, as the platform's block-broken hook reported it (see {@code NavFeature}). */
    private static final List<Break> BREAKS = new CopyOnWriteArrayList<>();

    private record Break(BlockPos pos, UUID by) {}

    private BotNavGameTests() {}

    /** The platform hook's listener: records breaks whose cause is a bot body. */
    public static void recordBreak(ServerLevel level, BlockPos pos, BlockState state, @Nullable Entity cause) {
        if (cause instanceof BotPlayer) BREAKS.add(new Break(pos.immutable(), cause.getUUID()));
    }

    // ---------------------------------------------------------------------------------------------------------

    /**
     * 12.5 blocks of flat ground. A player's full forward input walks about 0.22 blocks a tick, so 100 ticks is ample;
     * upstream's 0.3-scale input moves a player about 0.065 (0.084 sprinting) and needs at least 149.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 160)
    public static void walksAcrossFlatGroundAtWalkingSpeed(GameTestHelper helper) {
        room(helper, 3);
        NavRun run = NavRun.start(helper, "nwalk", new Vec3(1.5, FLOOR_Y + 1, 7.5), 160);
        BlockPos goal = helper.absolutePos(new BlockPos(14, FLOOR_Y + 1, 7));
        run.nav().goTo(goal, 0);
        run.each(100, () -> {
            if (run.nav().state() == BotNavigator.State.FAILED) helper.fail("failed: " + run.nav().failure());
            if (run.nav().state() == BotNavigator.State.DONE) {
                helper.assertTrue(run.body().blockPosition().equals(goal), "DONE at " + run.body().blockPosition() + ", goal " + goal);
                run.pass();
            }
        });
    }

    /**
     * A dirt wall three high spans the walled arena: the only way is through. The cells it opened are gone, the
     * platform's block-broken hook saw this body break each of them, and the shovel it carried was selected and worn.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 700)
    public static void digsThroughADirtWall(GameTestHelper helper) {
        room(helper, 5);
        wall(helper, 8, Blocks.DIRT);
        NavRun run = NavRun.start(helper, "ndig", new Vec3(7.5, FLOOR_Y + 1, 3.5), 700);
        BotPlayer body = run.body();
        body.getInventory().setItem(4, new ItemStack(Items.IRON_SHOVEL));
        body.getInventory().selected = 0;
        boolean[] heldShovel = {false};
        run.nav().goTo(helper.absolutePos(new BlockPos(7, FLOOR_Y + 1, 13)), 0);
        run.each(600, () -> {
            if (run.body().getMainHandItem().is(Items.IRON_SHOVEL)) heldShovel[0] = true;
            if (run.nav().state() == BotNavigator.State.FAILED) helper.fail("failed: " + run.nav().failure());
            if (run.nav().state() != BotNavigator.State.DONE) return;
            int opened = 0;
            for (int x = 1; x <= 14; x++) {
                for (int y = FLOOR_Y + 1; y <= FLOOR_Y + 3; y++) {
                    BlockPos cell = new BlockPos(x, y, 8);
                    if (helper.getBlockState(cell).is(Blocks.DIRT)) continue;
                    opened++;
                    BlockPos abs = helper.absolutePos(cell);
                    helper.assertTrue(BREAKS.stream().anyMatch(b -> b.pos().equals(abs) && b.by().equals(run.id)),
                        "wall cell " + cell + " is gone but the block-broken hook did not see this body break it");
                }
            }
            helper.assertTrue(opened >= 2, "arrived but only " + opened + " wall cells are open");
            ItemStack shovel = findItem(run.body(), Items.IRON_SHOVEL);
            helper.assertTrue(heldShovel[0], "the shovel was never selected");
            helper.assertTrue(shovel != null && shovel.getDamageValue() > 0, "the shovel took no damage: " + shovel);
            run.pass();
        });
    }

    /** The same wall with a filter that forbids dirt: no way around, so the command fails with a reason, wall intact. */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 1600)
    public static void breakFilterIsObeyed(GameTestHelper helper) {
        room(helper, 5);
        wall(helper, 8, Blocks.DIRT);
        NavRun run = NavRun.start(helper, "nfilt", new Vec3(7.5, FLOOR_Y + 1, 3.5), 1600);
        run.body().getInventory().setItem(0, new ItemStack(Items.IRON_SHOVEL));
        run.nav().setBreakFilter((level, pos, state) -> !state.is(Blocks.DIRT));
        run.nav().goTo(helper.absolutePos(new BlockPos(7, FLOOR_Y + 1, 13)), 0);
        run.each(1500, () -> {
            helper.assertTrue(run.nav().state() != BotNavigator.State.DONE, "reached a goal behind a wall it may not break");
            if (run.nav().state() != BotNavigator.State.FAILED) return;
            String why = run.nav().failure();
            helper.assertTrue(why != null && !why.isBlank(), "FAILED without a reason");
            for (int x = 1; x <= 14; x++) {
                for (int y = FLOOR_Y + 1; y <= FLOOR_Y + 3; y++) helper.assertBlockPresent(Blocks.DIRT, new BlockPos(x, y, 8));
            }
            MegaBlockBot.LOGGER.info("Nav GameTest: filtered wall failed as expected after {} ticks: {}", run.ticks, why);
            run.pass();
        });
    }

    /**
     * A gap three wide across a raised walkway, with magma at its bottom so that dropping in is no route: the body
     * bridges it with dirt from its inventory. It arrives, the gap holds the placed blocks, and the dirt it carries
     * went down by exactly what was placed.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 900)
    public static void bridgesAGapWithDirt(GameTestHelper helper) {
        int top = 4;
        walls(helper, top + 4);
        for (int x = 1; x <= 14; x++) {
            for (int z = 1; z <= 14; z++) {
                if (z >= 6 && z <= 8) helper.setBlock(x, FLOOR_Y, z, Blocks.MAGMA_BLOCK);
                else helper.setBlock(x, top, z, Blocks.STONE);
            }
        }
        NavRun run = NavRun.start(helper, "nbridge", new Vec3(7.5, top + 1, 3.5), 900);
        run.body().getInventory().setItem(0, new ItemStack(Items.DIRT, 16));
        run.nav().goTo(helper.absolutePos(new BlockPos(7, top + 1, 12)), 0);
        run.each(800, () -> {
            if (run.nav().state() == BotNavigator.State.FAILED) helper.fail("failed: " + run.nav().failure());
            if (run.nav().state() != BotNavigator.State.DONE) return;
            int inGap = 0;
            for (int x = 1; x <= 14; x++) {
                for (int z = 6; z <= 8; z++) if (helper.getBlockState(new BlockPos(x, top, z)).is(Blocks.DIRT)) inGap++;
            }
            int placed = countInArena(helper, Blocks.DIRT, top + 3);
            helper.assertTrue(inGap >= 3, "arrived with only " + inGap + " dirt blocks in the gap");
            int held = run.body().getInventory().countItem(Items.DIRT);
            helper.assertTrue(held == 16 - placed, "carries " + held + " dirt after placing " + placed + " of 16");
            run.pass();
        });
    }

    /** A goal three blocks straight up: the body pillars with dirt from its inventory. */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 500)
    public static void pillarsUpThreeBlocks(GameTestHelper helper) {
        room(helper, 9);
        NavRun run = NavRun.start(helper, "npillar", new Vec3(7.5, FLOOR_Y + 1, 7.5), 500);
        run.body().getInventory().setItem(0, new ItemStack(Items.DIRT, 16));
        BlockPos goal = helper.absolutePos(new BlockPos(7, FLOOR_Y + 4, 7));
        run.nav().goTo(goal, 0);
        run.each(400, () -> {
            if (run.nav().state() == BotNavigator.State.FAILED) helper.fail("failed: " + run.nav().failure());
            if (run.nav().state() != BotNavigator.State.DONE) return;
            helper.assertTrue(run.body().blockPosition().equals(goal), "DONE at " + run.body().blockPosition() + ", goal " + goal);
            int placed = countInArena(helper, Blocks.DIRT, FLOOR_Y + 8);
            helper.assertTrue(placed >= 3, "placed " + placed + " dirt to rise three blocks");
            helper.assertTrue(run.body().getInventory().countItem(Items.DIRT) == 16 - placed, "dirt count does not match what was placed");
            run.pass();
        });
    }

    /** A pool three deep and eight long spans the walled arena: the body swims across and climbs out. */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 900)
    public static void crossesAPoolThreeDeep(GameTestHelper helper) {
        int top = 4;
        walls(helper, top + 4);
        for (int x = 1; x <= 14; x++) {
            for (int z = 1; z <= 14; z++) {
                for (int y = FLOOR_Y; y <= top; y++) {
                    boolean pool = z >= 4 && z <= 11 && y > FLOOR_Y;
                    helper.setBlock(x, y, z, pool ? Blocks.WATER : Blocks.STONE);
                }
            }
        }
        NavRun run = NavRun.start(helper, "nswim", new Vec3(7.5, top + 1, 2.5), 900);
        double[] lowestFeet = {Double.MAX_VALUE};
        int[] inWater = {0};
        run.nav().goTo(helper.absolutePos(new BlockPos(7, top + 1, 13)), 0);
        run.each(800, () -> {
            BotPlayer body = run.body();
            if (body.isInWater()) {
                inWater[0]++;
                lowestFeet[0] = Math.min(lowestFeet[0], helper.relativeVec(body.position()).y);
            }
            if (run.nav().state() == BotNavigator.State.FAILED) helper.fail("failed: " + run.nav().failure());
            if (run.nav().state() != BotNavigator.State.DONE) return;
            helper.assertTrue(inWater[0] > 0, "arrived without ever being in the water");
            MegaBlockBot.LOGGER.info("Nav GameTest: crossed the pool in {} ticks, {} of them in water, lowest feet y {} (surface {})",
                run.ticks, inWater[0], lowestFeet[0], top);
            run.pass();
        });
    }

    /**
     * A trench four wide and one deep spans the walled arena, fed by sources at both ends so that the water in the
     * middle, where the body crosses, is flowing. The body wades through it (upstream's rules exclude flowing water).
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 700)
    public static void crossesFlowingWater(GameTestHelper helper) {
        int top = FLOOR_Y + 1;
        walls(helper, top + 4);
        for (int x = 1; x <= 14; x++) {
            for (int z = 1; z <= 14; z++) {
                helper.setBlock(x, FLOOR_Y, z, Blocks.STONE);
                boolean trench = z >= 6 && z <= 9;
                helper.setBlock(x, top, z, trench ? (x == 1 || x == 14 ? Blocks.WATER : Blocks.AIR) : Blocks.STONE);
            }
        }
        NavRun run = NavRun.start(helper, "nriver", new Vec3(7.5, top + 1, 2.5), 700);
        boolean[] started = {false};
        boolean[] waded = {false};
        // The water needs about 40 ticks to spread from both ends and meet in the middle.
        helper.runAfterDelay(60, () -> {
            helper.assertTrue(helper.getBlockState(new BlockPos(7, top, 7)).getFluidState().getType()
                == net.minecraft.world.level.material.Fluids.FLOWING_WATER, "the trench's middle is not flowing water");
            run.nav().goTo(helper.absolutePos(new BlockPos(7, top + 1, 13)), 0);
            started[0] = true;
        });
        run.each(600, () -> {
            if (!started[0]) return;
            if (run.body().isInWater()) waded[0] = true;
            if (run.nav().state() == BotNavigator.State.FAILED) helper.fail("failed: " + run.nav().failure());
            if (run.nav().state() != BotNavigator.State.DONE) return;
            helper.assertTrue(waded[0], "arrived without going through the water");
            run.pass();
        });
    }

    /** A strip of magma blocks with a way around at one end: the body goes around and never stands on magma. */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 700)
    public static void goesAroundMagma(GameTestHelper helper) {
        room(helper, 3);
        for (int x = 1; x <= 10; x++) {
            for (int z = 7; z <= 8; z++) helper.setBlock(x, FLOOR_Y, z, Blocks.MAGMA_BLOCK);
        }
        NavRun run = NavRun.start(helper, "nmagma", new Vec3(4.5, FLOOR_Y + 1, 3.5), 700);
        run.nav().goTo(helper.absolutePos(new BlockPos(4, FLOOR_Y + 1, 12)), 0);
        run.each(600, () -> {
            BotPlayer body = run.body();
            BlockState under = body.level().getBlockState(BlockPos.containing(body.getX(), body.getY() - 0.2, body.getZ()));
            helper.assertTrue(!under.is(Blocks.MAGMA_BLOCK) || !body.onGround(), "stood on magma at " + helper.relativeVec(body.position()));
            if (run.nav().state() == BotNavigator.State.FAILED) helper.fail("failed: " + run.nav().failure());
            if (run.nav().state() != BotNavigator.State.DONE) return;
            helper.assertTrue(body.getHealth() == body.getMaxHealth(), "hurt on the way: health " + body.getHealth());
            run.pass();
        });
    }

    /**
     * A dirt wall under a roof: two gravel above its head-height cells everywhere but at one end. The body must not
     * dig a cell with gravel over it, so it walks to the end and digs there; no gravel falls.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 1000)
    public static void doesNotDigUnderGravel(GameTestHelper helper) {
        room(helper, 4);
        for (int x = 1; x <= 14; x++) {
            for (int z = 1; z <= 14; z++) helper.setBlock(x, FLOOR_Y + 5, z, Blocks.BARRIER);
            helper.setBlock(x, FLOOR_Y + 1, 8, Blocks.DIRT);
            helper.setBlock(x, FLOOR_Y + 2, 8, Blocks.DIRT);
            // Two gravel high, so that no route opens the wall by digging the gravel itself (a cell whose own
            // falling block is dug is no hazard; one with gravel above it is).
            helper.setBlock(x, FLOOR_Y + 3, 8, x <= 12 ? Blocks.GRAVEL : Blocks.STONE);
            helper.setBlock(x, FLOOR_Y + 4, 8, x <= 12 ? Blocks.GRAVEL : Blocks.STONE);
        }
        NavRun run = NavRun.start(helper, "ngravel", new Vec3(3.5, FLOOR_Y + 1, 3.5), 1000);
        run.body().getInventory().setItem(0, new ItemStack(Items.IRON_SHOVEL));
        run.nav().goTo(helper.absolutePos(new BlockPos(3, FLOOR_Y + 1, 12)), 0);
        run.each(900, () -> {
            for (int x = 1; x <= 12; x++) {
                for (int y = FLOOR_Y + 2; y <= FLOOR_Y + 4; y++) {
                    BlockPos cell = new BlockPos(x, y, 8);
                    Block expected = y >= FLOOR_Y + 3 ? Blocks.GRAVEL : Blocks.DIRT;
                    if (!helper.getBlockState(cell).is(expected)) {
                        helper.fail(cell + " is " + helper.getBlockState(cell) + ", not " + expected + "; this body broke "
                            + run.brokenRelative() + ", it stands at " + helper.relativeVec(run.body().position()));
                    }
                }
            }
            if (run.nav().state() == BotNavigator.State.FAILED) helper.fail("failed: " + run.nav().failure());
            if (run.nav().state() != BotNavigator.State.DONE) return;
            run.pass();
        });
    }

    /** Two oak logs stand in the arena; mine(oak_log, 2) ends with both in the inventory. */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 1300)
    public static void minesTwoOakLogs(GameTestHelper helper) {
        room(helper, 3);
        helper.setBlock(4, FLOOR_Y + 1, 11, Blocks.OAK_LOG);
        helper.setBlock(11, FLOOR_Y + 1, 11, Blocks.OAK_LOG);
        NavRun run = NavRun.start(helper, "nmine", new Vec3(7.5, FLOOR_Y + 1, 4.5), 1300);
        try {
            run.nav().mine(List.of(Blocks.OAK_LOG), 2);
        } catch (RuntimeException e) {
            MegaBlockBot.LOGGER.error("mine threw", e);
            run.cleanup();
            throw e;
        }
        run.each(1200, () -> {
            if (run.nav().state() == BotNavigator.State.FAILED) helper.fail("failed: " + run.nav().failure());
            if (run.nav().state() != BotNavigator.State.DONE) return;
            int logs = run.body().getInventory().countItem(Items.OAK_LOG);
            helper.assertTrue(logs >= 2, "DONE holding " + logs + " oak logs");
            run.pass();
        });
    }

    /**
     * The body dies mid-path and respawns as a new instance. The command running on the old instance fails, and a new
     * goTo drives the new body to the goal (the test puts the respawned body back in the arena; the world's spawn
     * point is elsewhere).
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 500)
    public static void drivesTheRespawnedBody(GameTestHelper helper) {
        room(helper, 3);
        Vec3 start = helper.absoluteVec(new Vec3(1.5, FLOOR_Y + 1, 7.5));
        BlockPos goal = helper.absolutePos(new BlockPos(14, FLOOR_Y + 1, 7));
        NavRun run = NavRun.start(helper, "ndeath", new Vec3(1.5, FLOOR_Y + 1, 7.5), 500);
        BotPlayer first = run.body();
        run.nav().goTo(goal, 0);
        BotPlayer[] second = {null};
        run.each(450, () -> {
            BotPlayer now = run.body();
            if (second[0] == null) {
                if (now == first) {
                    if (run.ticks == 15) {
                        helper.assertTrue(run.nav().state() == BotNavigator.State.WORKING, "not working before the death");
                        first.hurt(helper.getLevel().damageSources().genericKill(), 1000.0F);
                    }
                    return;
                }
                // The replacement's first tick: the brain ticked the navigator with it, which must have dropped the
                // command that ran on the dead instance.
                helper.assertTrue(run.nav().state() == BotNavigator.State.FAILED, "after the respawn the state is " + run.nav().state());
                second[0] = now;
                now.teleportTo(helper.getLevel(), start.x, start.y, start.z, 0.0F, 0.0F);
                run.resetStep();
                run.nav().goTo(goal, 0);
                return;
            }
            helper.assertTrue(first.isRemoved(), "the dead instance is still in the level");
            if (run.nav().state() == BotNavigator.State.FAILED) helper.fail("failed after respawn: " + run.nav().failure());
            if (run.nav().state() != BotNavigator.State.DONE) return;
            helper.assertTrue(now == second[0] && now.blockPosition().equals(goal), "DONE but the respawned body is at " + now.blockPosition());
            run.pass();
        });
    }

    // ---------------------------------------------------------------------------------------------------------
    // Terrain
    // ---------------------------------------------------------------------------------------------------------

    /** A stone floor at {@link #FLOOR_Y} and barrier walls {@code height} high around the arena's edge. */
    private static void room(GameTestHelper helper, int height) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) helper.setBlock(x, FLOOR_Y, z, Blocks.STONE);
        }
        walls(helper, height);
    }

    /** Barrier walls on the arena's edge, from the floor up {@code height} blocks; the inside is x and z 1 to 14. */
    private static void walls(GameTestHelper helper, int height) {
        for (int i = 0; i < 16; i++) {
            for (int y = FLOOR_Y; y <= FLOOR_Y + height; y++) {
                helper.setBlock(i, y, 0, Blocks.BARRIER);
                helper.setBlock(i, y, 15, Blocks.BARRIER);
                helper.setBlock(0, y, i, Blocks.BARRIER);
                helper.setBlock(15, y, i, Blocks.BARRIER);
            }
        }
    }

    /** A wall three high across the inside of the arena at {@code z}. */
    private static void wall(GameTestHelper helper, int z, Block block) {
        for (int x = 1; x <= 14; x++) {
            for (int y = FLOOR_Y + 1; y <= FLOOR_Y + 3; y++) helper.setBlock(x, y, z, block);
        }
    }

    private static int countInArena(GameTestHelper helper, Block block, int maxY) {
        int n = 0;
        for (int x = 1; x <= 14; x++) {
            for (int z = 1; z <= 14; z++) {
                for (int y = FLOOR_Y; y <= maxY; y++) if (helper.getBlockState(new BlockPos(x, y, z)).is(block)) n++;
            }
        }
        return n;
    }

    @Nullable
    private static ItemStack findItem(BotPlayer body, Item item) {
        for (ItemStack stack : body.getInventory().items) if (stack.is(item)) return stack;
        return null;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Harness
    // ---------------------------------------------------------------------------------------------------------

    /** The brain a real one will be: it ticks the body's navigator, before the body applies its input. */
    private static final class NavBrain implements BotBrain {
        @Override public void tick(BotPlayer body) { BotNavigator.of(body).tick(body); }
        @Override public void onDeath(BotPlayer body, DeathRecord death) {}
        @Override public void onRespawn(BotPlayer newBody) {}
        @Override public boolean wantsToStayLoaded() { return true; }
        @Override public void save(CompoundTag tag, HolderLookup.Provider registries) {}
        @Override public void load(CompoundTag tag, HolderLookup.Provider registries) {}
    }

    /** One test body: spawned with a {@link NavBrain}, watched every tick, despawned and forgotten when the test ends. */
    private static final class NavRun {
        private final GameTestHelper helper;
        private final BodyManager manager;
        private final String name;
        final UUID id;
        int ticks;
        private Vec3 last;
        private BotPlayer lastBody;
        private boolean over;

        private NavRun(GameTestHelper helper, String name) {
            this.helper = helper;
            this.manager = BodyManager.of(helper.getLevel().getServer());
            this.name = name;
            this.id = BodyManager.uuidFor(name);
        }

        static NavRun start(GameTestHelper helper, String tag, Vec3 relative, int deadlineTicks) {
            NavRun run = new NavRun(helper, "gt_" + tag + "_" + RUN_ID);
            try {
                run.manager.spawn(run.name, null, helper.getLevel(), helper.absoluteVec(relative), 0.0F, NavBrain::new);
            } catch (BodyException e) {
                throw new IllegalStateException("could not spawn " + run.name + ": " + e.getMessage(), e);
            }
            helper.runAtTickTime(deadlineTicks, run::cleanup);
            return run;
        }

        BotPlayer body() {
            BotPlayer current = manager.get(id);
            if (current == null) throw new IllegalStateException("no body " + name + " is logged in");
            return current;
        }

        BotNavigator nav() {
            return BotNavigator.of(body());
        }

        /** The blocks this body broke so far, relative to the test, for failure messages. */
        List<BlockPos> brokenRelative() {
            return BREAKS.stream().filter(b -> b.by().equals(id)).map(b -> b.pos().subtract(helper.absolutePos(BlockPos.ZERO))).toList();
        }

        /** Forget the last position, after the test itself moved the body. */
        void resetStep() {
            last = null;
        }

        /**
         * Every tick until the test ends: fails on a single-tick move over one block or when {@code budget} ticks pass,
         * then runs {@code check}, which passes or fails the test. Whatever it throws, the body is cleaned up first.
         */
        void each(int budget, Runnable check) {
            helper.onEachTick(() -> {
                if (over) return;
                try {
                    ticks++;
                    BotPlayer body = body();
                    Vec3 now = body.position();
                    if (body != lastBody) last = null; // a respawn is a new body somewhere else, not a move
                    if (last != null) {
                        double step = now.distanceTo(last);
                        helper.assertTrue(step <= 1.0, "moved " + step + " blocks in one tick at tick " + ticks);
                    }
                    last = now;
                    lastBody = body;
                    if (ticks > budget) {
                        helper.fail("not done after " + budget + " ticks: " + nav().state() + ", failure " + nav().failure()
                            + ", at " + helper.relativeVec(body.position()));
                    }
                    check.run();
                } catch (RuntimeException | Error e) {
                    cleanup();
                    throw e;
                }
            });
        }

        void pass() {
            MegaBlockBot.LOGGER.info("Nav GameTest {}: passed after {} ticks", name, ticks);
            cleanup();
            helper.succeed();
        }

        void cleanup() {
            if (over) return;
            over = true;
            BotPlayer current = manager.get(id);
            if (current != null) {
                BotNavigator.of(current).stop();
                manager.despawn(current);
            }
            try {
                manager.deleteSaved(id);
            } catch (BodyException ignored) {
                // Still logged in only if despawn did not take; the unique name keeps it from mattering.
            }
        }
    }
}
