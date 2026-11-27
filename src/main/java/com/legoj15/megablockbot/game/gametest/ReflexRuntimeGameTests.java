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
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The reflex layer through the real runtime (issue 8): bodies with the default brain, a {@code BotRuntime}, asked for a
 * goal as the owner would, with a mob arriving in the middle of it. What these protect, and how each is measured:
 *
 * <ul>
 *   <li>a goal ends satisfied, with what it asked for in the pack, after a fight in the middle of it (the goal's own result);
 *   <li>a mining goal gets its blocks after a hold, so the navigator's break filter is not left at "break nothing" (the
 *       blocks arrive);
 *   <li>the runtime's leftover-table step is paused by a hold and finishes after it (the table is back in the pack and none
 *       stands);
 *   <li>a retreat that begins on the very tick the hold does is not stopped by the executor pausing its task (the navigator
 *       is working on each of the next ten ticks and the body has moved on by the twenty-fifth);
 *   <li>a creeper that targets the body but hovers far off and does not come is not run from, and the goal is not given up
 *       for the holds it would have caused (the layer's hold count, and the goal's result);
 *   <li>on every tick of every test, the navigator's engine is ticked at most once per body tick
 *       ({@link BotNavigator#engineTicks}). That is the only guard against two drivers of the navigator; how far the body
 *       moves in a tick is not measured here, because a walk at twice a player's speed is not far enough to tell.
 * </ul>
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
    /** How long a creeper hovers far off in the test of that name. */
    private static final int HOVER_TICKS = 600;

    private ReflexRuntimeGameTests() {}

    /**
     * A body with a sword is making planks from sixteen logs (a craft from what it holds, in the inventory's own grid: a
     * goal the arena can finish) when a zombie appears beside it. The zombie dies and the goal still ends satisfied with all
     * sixty-four planks. (A craft that needs a table is not used here: a hold that carries the body off from the table it is
     * taking up leaves the item out of the search radius of the task that picks it up, which is the task's to handle and
     * has its own test below, with the zombie in reach.)
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = GOAL_TICKS + 100, batch = "reflex_runtime")
    public static void aFightInTheMiddleOfAGoalLeavesTheGoalSatisfied(GameTestHelper helper) {
        room(helper, Blocks.STONE);
        Rig rig = Rig.spawn(helper, "fight", GOAL_TICKS + 50);
        rig.give(Items.OAK_LOG, 16);
        rig.give(Items.IRON_SWORD, 1);
        rig.runtime().submit(new Goal.HaveItem(ItemKey.of("oak_planks"), 64));
        Zombie[] zombie = {null};
        rig.onEachTick(tick -> {
            BotPlayer body = rig.body();
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
            MegaBlockBot.LOGGER.info("Reflex runtime GameTest fight: goal done after {} ticks, {} holds", tick, ReflexLayer.of(body).holdsStarted());
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
        rig.onEachTick(tick -> {
            BotPlayer body = rig.body();
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

    /**
     * A retreat that begins on the tick the hold does. The layer runs first in a body tick and starts a navigator command (the
     * retreat); the executor, run next in the same tick, sees the hold begin and pauses the task, and a task must not stop a
     * command that is not its own. The body has a mining goal and is on its way to a column of logs, with low health, no
     * weapon and a zombie two and a half blocks behind it: the mob reflex retreats at once. Protects: the body standing still,
     * believing its walk is on, until the layer notices (about 48 ticks by a reviewer's trace; the first twelve ticks of a
     * retreat run on the layer's own running start and hide it, so the checks are the navigator, which the running start does
     * not touch, and how far the body has got after those twelve). Measured from the first tick of the hold: the navigator is
     * working on each of the next ten ticks and the body has moved on from where it was by a block in ten and by four blocks
     * in twenty-five. Batch of its own, as the other mining test: a mining goal sets the break rules, which are global state.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 700, batch = "reflex_runtime_mine")
    public static void aRetreatThatBeginsWithTheHoldIsNotStoppedByThePausedTask(GameTestHelper helper) {
        room(helper, Blocks.BARRIER);
        for (int y = FLOOR_Y + 1; y <= FLOOR_Y + 3; y++) helper.setBlock(13, y, 8, Blocks.OAK_LOG);
        Rig rig = Rig.spawn(helper, "retr", 650);
        rig.body().setHealth(8.0F);
        rig.runtime().submit(new Goal.HaveItem(ItemKey.of("oak_log"), 3));
        double startX = rig.body().getX();
        Zombie[] zombie = {null};
        int[] spawnedAt = {0};
        long[] serialBefore = {0};
        int[] holdStart = {-1};
        Vec3[] holdFrom = {null};
        rig.onEachTick(tick -> {
            BotPlayer body = rig.body();
            BotNavigator nav = BotNavigator.of(body);
            ReflexLayer layer = ReflexLayer.of(body);
            helper.assertTrue(body.getHealth() > 0.0F, "the body died");
            if (zombie[0] == null) {
                // On its way to the logs, the navigator working for the task: the zombie comes up behind it.
                if (body.getX() > startX + 2.5 && nav.state() == BotNavigator.State.WORKING) {
                    zombie[0] = rig.zombie(helper.relativeVec(body.position()).add(-2.5, 0.0, 0.0));
                    spawnedAt[0] = tick;
                    serialBefore[0] = nav.serial();
                }
                return;
            }
            if (holdStart[0] < 0) {
                if (!layer.isHolding()) {
                    helper.assertTrue(tick - spawnedAt[0] < 60, "the reflex layer never took the body for the zombie");
                    return;
                }
                holdStart[0] = tick;
                holdFrom[0] = body.position();
            }
            int held = tick - holdStart[0];
            if (held <= 10) {
                helper.assertTrue(layer.holderName().equals("mobs"), "the body is held by '" + layer.holderName() + "', not by the mob reflex");
                helper.assertTrue(nav.state() == BotNavigator.State.WORKING, "the navigator is " + nav.state() + " " + held
                    + " ticks into the hold: the retreat that began with it was stopped, by the executor pausing the task");
                helper.assertTrue(nav.serial() > serialBefore[0], "the navigator has begun no command since the zombie came: what it runs is the task's");
            }
            if (held == 10) {
                helper.assertTrue(body.position().distanceTo(holdFrom[0]) >= 1.0, "ten ticks into the hold the body has moved "
                    + body.position().distanceTo(holdFrom[0]) + " blocks");
            }
            if (held < 25) return;
            helper.assertTrue(layer.holderName().equals("mobs"), "the body is held by '" + layer.holderName() + "' after 25 ticks");
            helper.assertTrue(body.position().distanceTo(holdFrom[0]) >= 4.0, "twenty-five ticks into the hold the body has moved only "
                + body.position().distanceTo(holdFrom[0]) + " blocks: the retreat stopped when the running start did");
            rig.pass();
        });
    }

    /**
     * A creeper that targets the body and hovers twelve to fourteen blocks off, as the creepers of the first night run did:
     * they did not come, and the layer ran from each sight of one, nine holds in thirty seconds, until the executor gave the
     * goal up. A threat that is far and not closing is watched, not run from. Protects: the body being held, running, and
     * walked back by its task, over and over, for a mob that is not coming. The creeper stands where the test puts it (no AI,
     * so it never walks; the test moves it, slowly, between twelve and fourteen blocks of where the body started, which
     * works at a craft that takes the whole of the time). The brief's bound is two holds at most; the decided behaviour is
     * that the layer takes the body not at all, and that is what is asserted (on every tick, so that a failure names the first
     * hold), with the goal never given up.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = HOVER_TICKS + 150, batch = "reflex_runtime")
    public static void aCreeperHoveringFarOffIsWatchedNotRunFrom(GameTestHelper helper) {
        room(helper, Blocks.STONE);
        Rig rig = Rig.spawnAt(helper, "hover", new Vec3(1.5, FLOOR_Y + 1.0, 2.5), HOVER_TICKS + 100);
        rig.give(Items.OAK_LOG, 64);
        rig.runtime().submit(new Goal.HaveItem(ItemKey.of("oak_planks"), 256));
        Creeper creeper = rig.hoveringCreeper(new Vec3(1.5 + 9.25, FLOOR_Y + 1.0, 2.5 + 9.25));
        Vec3 start = rig.body().position();
        rig.onEachTick(tick -> {
            BotPlayer body = rig.body();
            // Between twelve and fourteen blocks of where the body started, by a swing too slow to be mistaken for a creeper that walks.
            double a = 9.25 + 0.65 * Math.sin(tick / 90.0);
            Vec3 at = helper.absoluteVec(new Vec3(1.5 + a, FLOOR_Y + 1.0, 2.5 + a));
            creeper.moveTo(at.x, at.y, at.z, 0.0F, 0.0F);
            creeper.setTarget(body);
            double off = start.distanceTo(creeper.position());
            helper.assertTrue(off >= 11.8 && off <= 14.2, "setup: the creeper is " + off + " blocks from where the body started");
            ReflexLayer layer = ReflexLayer.of(body);
            helper.assertTrue(layer.holdsStarted() == 0, "the layer took the body at tick " + tick + " (" + layer.holdsStarted() + " holds, held by '"
                + layer.holderName() + "') for a creeper " + body.distanceTo(creeper) + " blocks off that is not coming: a far threat that is not closing is watched");
            GoalResult result = rig.runtime().lastResult();
            if (result != null) helper.assertTrue(result.status() == GoalResult.Status.SATISFIED, "the goal was given up: " + BotSpeech.result(result));
            if (tick < HOVER_TICKS) return;
            MegaBlockBot.LOGGER.info("Reflex runtime GameTest hovering creeper: {} holds, {} ticks held in {}; goal {}", layer.holdsStarted(),
                rig.heldTicks(), tick, result == null ? "still running" : "satisfied");
            rig.pass();
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
        private final List<Mob> mobs = new ArrayList<>();
        private int ticks;
        private int heldTicks;
        private long lastEngineTicks = -1;
        private long lastGameTime;
        private boolean over;

        private Rig(GameTestHelper helper, String tag) {
            this.helper = helper;
            this.manager = BodyManager.of(helper.getLevel().getServer());
            this.name = "gt_rr_" + tag + "_" + RUN_ID;
            this.id = BodyManager.uuidFor(name);
        }

        static Rig spawn(GameTestHelper helper, String tag, int deadlineTicks) {
            return spawnAt(helper, tag, new Vec3(2.5, FLOOR_Y + 1.0, 8.5), deadlineTicks);
        }

        static Rig spawnAt(GameTestHelper helper, String tag, Vec3 relative, int deadlineTicks) {
            Rig rig = new Rig(helper, tag);
            rig.respawn(helper.absoluteVec(relative));
            helper.runAtTickTime(deadlineTicks, rig::cleanup);
            return rig;
        }

        /** Ticks on which the reflex layer held the body so far. */
        int heldTicks() {
            return heldTicks;
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
            mobs.add(zombie);
            return zombie;
        }

        /** A creeper that is after the body and stays where it is put: no AI, so it never walks, and the test moves it. */
        Creeper hoveringCreeper(Vec3 relative) {
            ServerLevel level = helper.getLevel();
            Creeper creeper = EntityType.CREEPER.create(level);
            creeper.setNoAi(true);
            Vec3 at = helper.absoluteVec(relative);
            creeper.moveTo(at.x, at.y, at.z, 0.0F, 0.0F);
            creeper.setPersistenceRequired();
            level.addFreshEntity(creeper);
            creeper.setTarget(body());
            mobs.add(creeper);
            return creeper;
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

        /**
         * Every tick until the test ends; whatever the action throws, the body is cleaned up first. The argument is the tick
         * number. Before the action, on every tick: the navigator's engine has not been ticked more often than the game has
         * ticked (the layer ticks it while it holds the body, the runtime while it does not, and never both).
         */
        void onEachTick(java.util.function.IntConsumer action) {
            helper.onEachTick(() -> {
                if (over) return;
                try {
                    ticks++;
                    // A body that is logged out for a moment (a test that despawns it) is not looked at.
                    BotPlayer present = manager.get(id);
                    if (present != null) {
                        watchEngine(present);
                        if (ReflexLayer.tracks(id) && ReflexLayer.of(present).isHolding()) heldTicks++;
                    }
                    action.accept(ticks);
                } catch (RuntimeException | Error e) {
                    cleanup();
                    throw e;
                }
            });
        }

        /** The navigator's engine was ticked at most once for each tick of the game since the last look. */
        private void watchEngine(BotPlayer present) {
            if (!BotNavigator.tracks(id)) return;
            long engine = BotNavigator.of(present).engineTicks();
            long time = helper.getLevel().getGameTime();
            if (lastEngineTicks >= 0) {
                long ticked = engine - lastEngineTicks;
                long allowed = Math.max(1L, time - lastGameTime);
                helper.assertTrue(ticked <= allowed, "the navigator's engine was ticked " + ticked + " times in " + allowed + " body ticks");
            }
            lastEngineTicks = engine;
            lastGameTime = time;
        }

        void pass() {
            cleanup();
            helper.succeed();
        }

        void cleanup() {
            if (over) return;
            over = true;
            for (Mob mob : mobs) mob.discard();
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
