package com.legoj15.megablockbot.game.gametest;

import com.legoj15.megablockbot.MegaBlockBot;
import com.legoj15.megablockbot.core.ItemKey;
import com.legoj15.megablockbot.core.exec.GoalResult;
import com.legoj15.megablockbot.core.goal.Goal;
import com.legoj15.megablockbot.game.body.BodyException;
import com.legoj15.megablockbot.game.body.BodyManager;
import com.legoj15.megablockbot.game.body.BotPlayer;
import com.legoj15.megablockbot.game.bot.BotRuntime;
import com.legoj15.megablockbot.game.bot.BotSpeech;
import com.legoj15.megablockbot.game.nav.BotNavigator;
import com.legoj15.megablockbot.game.safety.BlockSafety;
import com.legoj15.megablockbot.game.plan.BotMemory;
import com.legoj15.megablockbot.game.reflex.ReflexLayer;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The reflex layer through the real runtime (issue 8, round two): bodies with the default brain, a {@code BotRuntime},
 * asked for a goal as the owner would, with a zombie arriving in the middle of it. What these protect: that the layer
 * and the runtime agree on who owns the body and the navigator each tick. A hold that pauses the executor's task, a
 * runtime that ticks the navigator a second time under the layer's, a break filter left at "break nothing", or a step
 * of the runtime's own that is not paused and resumed fails one of them. The bot's goal must end satisfied whatever
 * happened on the way.
 *
 * <p>Each test spawns its own body (a name unique to this run) in the arena and removes it when it ends. Placing items in
 * a body's inventory is setup, not the bot cheating: the bot's own crafting, mining and fighting are what is measured.
 * The room is roofed with barrier so that no zombie burns in the sun and none is under the open sky for the night policy.
 */
public final class ReflexRuntimeGameTests {
    private static final int FLOOR_Y = 1;
    private static final int ROOF_Y = FLOOR_Y + 7;
    private static final String RUN_ID = Integer.toString((int) (System.nanoTime() & 0xFFFFF), 36);
    /** A pickaxe from logs takes about 200 ticks without a fight; a fight, a walk back and the rest of the goal take the rest. */
    private static final int GOAL_TICKS = 1100;
    /** The most a body has been seen to move in one tick when only a sprint, a jump and a zombie's knockback move it. */
    private static final double MAX_STEP = 0.8;

    private ReflexRuntimeGameTests() {}

    /**
     * A body with a sword is making planks from sixteen logs (a craft from what it holds, in the inventory's own grid: a
     * goal the arena can finish) when a zombie appears beside it. The zombie dies, the goal still ends satisfied with all
     * sixty-four planks, and the body never moves more than a player can in a tick. (A craft that needs a table is not used
     * here: a hold that carries the body off from the table it is taking up leaves the item out of the search radius of the
     * task that picks it up, which is the task's to handle and has its own test below, with the zombie in reach.)
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = GOAL_TICKS + 100, batch = "reflex_runtime")
    public static void aFightInTheMiddleOfAGoalLeavesTheGoalSatisfied(GameTestHelper helper) {
        room(helper, Blocks.STONE);
        Rig rig = Rig.spawn(helper, "fight", GOAL_TICKS + 50);
        rig.give(Items.OAK_LOG, 16);
        rig.give(Items.IRON_SWORD, 1);
        rig.runtime().submit(new Goal.HaveItem(ItemKey.of("oak_planks"), 64));
        Zombie[] zombie = {null};
        Vec3[] last = {null};
        double[] biggestStep = {0.0};
        rig.onEachTick(tick -> {
            BotPlayer body = rig.body();
            if (last[0] != null) biggestStep[0] = Math.max(biggestStep[0], body.position().distanceTo(last[0]));
            last[0] = body.position();
            helper.assertTrue(biggestStep[0] <= MAX_STEP, "the body moved " + biggestStep[0] + " blocks in one tick, more than a player does");
            // Part way through the craft: some of the planks are made and most are not.
            if (zombie[0] == null && rig.count(Items.OAK_PLANKS) >= 8) {
                zombie[0] = rig.zombie(helper.relativeVec(body.position()).add(1.8, 0.0, 0.0));
            }
            GoalResult result = rig.runtime().lastResult();
            if (result == null) {
                helper.assertTrue(tick < GOAL_TICKS, "the goal did not end: " + rig.runtime().statusText() + "; planks " + rig.count(Items.OAK_PLANKS)
                    + ", logs " + rig.count(Items.OAK_LOG) + "; zombie " + zombie[0]);
                return;
            }
            helper.assertTrue(zombie[0] != null, "the goal ended before the zombie appeared, so nothing was tested: " + BotSpeech.result(result));
            helper.assertTrue(result.status() == GoalResult.Status.SATISFIED, "the goal ended " + BotSpeech.result(result));
            helper.assertTrue(!zombie[0].isAlive(), "the zombie is still alive");
            helper.assertTrue(ReflexLayer.of(body).holdsStarted() >= 1, "the reflex layer never took the body");
            helper.assertTrue(rig.count(Items.OAK_PLANKS) == 64, "the body holds " + rig.count(Items.OAK_PLANKS) + " planks, not 64");
            MegaBlockBot.LOGGER.info("Reflex runtime GameTest fight: goal done after {} ticks, {} holds, biggest step {}", tick,
                ReflexLayer.of(body).holdsStarted(), biggestStep[0]);
            rig.pass();
        });
    }

    /**
     * A goal that needs blocks broken (three logs, from a column of oak logs standing in the room) is interrupted on the way
     * by a zombie. After the hold the body gets its blocks: the break filter is the rule every task arms the navigator with,
     * not the layer's "break nothing" left behind. A floor of barrier, so that the column is the only thing to mine (a
     * planned mine of stone looks first for the height stone is densest at, which a room with a barrier roof does not
     * have); a batch of its own, because the break rules are global state that other tests set.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = GOAL_TICKS + 100, batch = "reflex_runtime_mine")
    public static void aMiningGoalGetsItsBlocksAfterAHold(GameTestHelper helper) {
        room(helper, Blocks.BARRIER);
        for (int y = FLOOR_Y + 1; y <= FLOOR_Y + 3; y++) helper.setBlock(13, y, 8, Blocks.OAK_LOG);
        Rig rig = Rig.spawn(helper, "mine", GOAL_TICKS + 50);
        rig.give(Items.IRON_SWORD, 1);
        rig.runtime().submit(new Goal.HaveItem(ItemKey.of("oak_log"), 3));
        double startX = rig.body().getX();
        Zombie[] zombie = {null};
        Vec3[] last = {null};
        rig.onEachTick(tick -> {
            BotPlayer body = rig.body();
            if (last[0] != null) helper.assertTrue(body.position().distanceTo(last[0]) <= MAX_STEP, "the body moved " + body.position().distanceTo(last[0])
                + " blocks in one tick, more than a player does");
            last[0] = body.position();
            // On the way to the stone, the navigator working: the zombie comes.
            if (zombie[0] == null && body.getX() > startX + 2.5 && BotNavigator.of(body).state() == BotNavigator.State.WORKING) {
                zombie[0] = rig.zombie(helper.relativeVec(body.position()).add(-2.5, 0.0, 0.0));
            }
            GoalResult result = rig.runtime().lastResult();
            if (result == null) {
                if (tick >= GOAL_TICKS) {
                    ServerLevel level = helper.getLevel();
                    BlockPos stone = helper.absolutePos(new BlockPos(13, FLOOR_Y + 1, 8));
                    BotNavigator nav = BotNavigator.of(body);
                    BlockState stoneState = level.getBlockState(stone);
                    boolean here = nav.breakFilter().mayBreak(level, stone, stoneState);
                    boolean there = CompletableFuture.supplyAsync(() -> nav.breakFilter().mayBreak(level, stone, stoneState)).join();
                    helper.fail("the goal did not end: " + rig.runtime().statusText() + "; navigator " + nav.state() + " " + nav.failure() + "; at "
                        + helper.relativeVec(body.position()) + "; logs still standing " + countLogs(helper) + "; the filter on a log: server thread " + here
                        + ", search thread " + there + ", block safety " + BlockSafety.of(level.getServer()).mayBreak(body, level, stone, stoneState));
                }
                return;
            }
            helper.assertTrue(zombie[0] != null, "the goal ended before the zombie appeared, so nothing was tested: " + BotSpeech.result(result));
            helper.assertTrue(result.status() == GoalResult.Status.SATISFIED, "the goal ended " + BotSpeech.result(result));
            helper.assertTrue(!zombie[0].isAlive(), "the zombie is still alive");
            helper.assertTrue(ReflexLayer.of(body).holdsStarted() >= 1, "the reflex layer never took the body");
            helper.assertTrue(rig.count(Items.OAK_LOG) >= 3, "the body holds " + rig.count(Items.OAK_LOG) + " oak logs");
            rig.pass();
        });
    }

    /**
     * The runtime's own step that takes up a table left standing (after a logout in the middle of a craft) is a task like
     * any other: a hold while it runs pauses it, and the table is taken up afterwards. The body is logged out the moment its
     * table stands, comes back, and a zombie arrives while the table is still being taken up; when the zombie is dead the
     * table is in the inventory again, none stands, and the goal ends satisfied.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = GOAL_TICKS + 300, batch = "reflex_runtime")
    public static void aHoldWhileTheLeftoverTableIsTakenUpPausesItAndItFinishes(GameTestHelper helper) {
        room(helper, Blocks.STONE);
        Rig rig = Rig.spawn(helper, "tidyf", GOAL_TICKS + 250);
        rig.give(Items.OAK_LOG, 3);
        rig.give(Items.IRON_SWORD, 1);
        rig.runtime().submit(new Goal.HaveItem(ItemKey.of("wooden_pickaxe"), 1));
        BotRuntime[] before = new BotRuntime[1];
        Zombie[] zombie = {null};
        int[] stage = {0};
        int[] waited = {0};
        rig.onEachTick(tick -> {
            switch (stage[0]) {
                case 0 -> {
                    if (rig.tablesInArena().isEmpty()) return;
                    before[0] = rig.runtime();
                    rig.manager().despawn(rig.body());
                    stage[0] = 1;
                }
                case 1 -> {
                    if (++waited[0] < 5) return;
                    rig.respawn(helper.absoluteVec(new Vec3(8.5, FLOOR_Y + 1.0, 8.5)));
                    helper.assertTrue(rig.runtime() != before[0], "the respawned body kept the old runtime");
                    helper.assertTrue(!rig.tablesInArena().isEmpty(), "the table is gone before the new body did anything");
                    waited[0] = 0;
                    stage[0] = 2;
                }
                case 2 -> {
                    // The new runtime has begun to take the table up (a few ticks to walk and start breaking it); it is not done.
                    if (++waited[0] < 12) return;
                    helper.assertTrue(!rig.tablesInArena().isEmpty(), "the table was taken up before the zombie came, so the hold did not interrupt it");
                    // In reach at once, so that the body does not have to walk to it (and away from the table it is taking up).
                    zombie[0] = rig.zombie(helper.relativeVec(rig.body().position()).add(1.8, 0.0, 0.0));
                    stage[0] = 3;
                }
                default -> {
                    GoalResult result = rig.runtime().lastResult();
                    if (result == null) {
                        if (tick >= GOAL_TICKS + 200) {
                            List<String> drops = new ArrayList<>();
                            for (ItemEntity drop : helper.getLevel().getEntitiesOfClass(ItemEntity.class, rig.body().getBoundingBox().inflate(12.0))) {
                                drops.add(drop.getItem() + " at " + helper.relativeVec(drop.position()));
                            }
                            helper.fail("the goal did not end: " + rig.runtime().statusText() + "; body at " + helper.relativeVec(rig.body().position())
                                + "; tables " + rig.tablesInArena() + "; items near " + drops + "; the table item held " + rig.count(Items.CRAFTING_TABLE)
                                + "; zombie alive " + zombie[0].isAlive());
                        }
                        return;
                    }
                    helper.assertTrue(result.status() == GoalResult.Status.SATISFIED, "the goal ended " + BotSpeech.result(result));
                    helper.assertTrue(!zombie[0].isAlive(), "the zombie is still alive");
                    helper.assertTrue(ReflexLayer.of(rig.body()).holdsStarted() >= 1, "the reflex layer never took the body");
                    helper.assertTrue(rig.count(Items.WOODEN_PICKAXE) == 1, "no wooden pickaxe after the fight");
                    helper.assertTrue(rig.count(Items.CRAFTING_TABLE) == 1, "the table is not back in the inventory: " + rig.count(Items.CRAFTING_TABLE));
                    helper.assertTrue(rig.tablesInArena().isEmpty(), "a crafting table was left standing at " + rig.tablesInArena());
                    helper.assertTrue(BotMemory.of(rig.id()).placedCount() == 0, "the body still remembers a block it placed");
                    rig.pass();
                }
            }
        });
    }

    // ---------------------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------------------

    private static int countLogs(GameTestHelper helper) {
        int n = 0;
        for (int y = FLOOR_Y + 1; y <= FLOOR_Y + 3; y++) if (helper.getBlockState(new BlockPos(13, y, 8)).is(Blocks.OAK_LOG)) n++;
        return n;
    }

    /** A floor, barrier walls round the arena's edge and a barrier roof over all of it. */
    private static void room(GameTestHelper helper, Block floor) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                helper.setBlock(x, FLOOR_Y, z, floor);
                helper.setBlock(x, ROOF_Y, z, Blocks.BARRIER);
            }
        }
        for (int i = 0; i < 16; i++) {
            for (int y = FLOOR_Y + 1; y < ROOF_Y; y++) {
                helper.setBlock(i, y, 0, Blocks.BARRIER);
                helper.setBlock(i, y, 15, Blocks.BARRIER);
                helper.setBlock(0, y, i, Blocks.BARRIER);
                helper.setBlock(15, y, i, Blocks.BARRIER);
            }
        }
    }

    /** One test body with the default brain: spawned under a name unique to this run, found again by it (a respawn replaces the instance). */
    private static final class Rig {
        private final GameTestHelper helper;
        private final BodyManager manager;
        private final String name;
        private final UUID id;
        private final List<Zombie> zombies = new ArrayList<>();
        private int ticks;
        private boolean over;

        private Rig(GameTestHelper helper, String tag) {
            this.helper = helper;
            this.manager = BodyManager.of(helper.getLevel().getServer());
            this.name = "gt_rr_" + tag + "_" + RUN_ID;
            this.id = BodyManager.uuidFor(name);
        }

        static Rig spawn(GameTestHelper helper, String tag, int deadlineTicks) {
            Rig rig = new Rig(helper, tag);
            rig.respawn(helper.absoluteVec(new Vec3(2.5, FLOOR_Y + 1.0, 8.5)));
            helper.runAtTickTime(deadlineTicks, rig::cleanup);
            return rig;
        }

        String name() {
            return name;
        }

        UUID id() {
            return id;
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
                return manager.spawn(name, null, helper.getLevel(), absolute);
            } catch (BodyException e) {
                throw new IllegalStateException("could not spawn " + name + ": " + e.getMessage(), e);
            }
        }

        void give(Item item, int count) {
            body().getInventory().add(new ItemStack(item, count));
        }

        int count(Item item) {
            return body().getInventory().countItem(item);
        }

        /** A plain adult zombie, made without the spawn code's dice, at a position relative to the arena. */
        Zombie zombie(Vec3 relative) {
            ServerLevel level = helper.getLevel();
            Zombie zombie = EntityType.ZOMBIE.create(level);
            Vec3 at = helper.absoluteVec(relative);
            zombie.moveTo(at.x, at.y, at.z, 0.0F, 0.0F);
            zombie.setPersistenceRequired();
            level.addFreshEntity(zombie);
            zombies.add(zombie);
            return zombie;
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

        /** Every tick until the test ends; whatever the action throws, the body is cleaned up first. The argument is the tick number. */
        void onEachTick(java.util.function.IntConsumer action) {
            helper.onEachTick(() -> {
                if (over) return;
                try {
                    action.accept(++ticks);
                } catch (RuntimeException | Error e) {
                    cleanup();
                    throw e;
                }
            });
        }

        void pass() {
            cleanup();
            helper.succeed();
        }

        void cleanup() {
            if (over) return;
            over = true;
            for (Zombie zombie : zombies) zombie.discard();
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
