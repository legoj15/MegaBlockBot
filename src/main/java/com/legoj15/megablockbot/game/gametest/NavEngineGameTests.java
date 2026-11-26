package com.legoj15.megablockbot.game.gametest;

import com.legoj15.megablockbot.MegaBlockBot;
import com.legoj15.megablockbot.nav.engine.api.IBaritone;
import com.legoj15.megablockbot.nav.engine.api.pathing.goals.GoalBlock;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Proves the ported pathing engine (navigation module) plans a path and moves an entity on NeoForge 1.21.1 through its
 * own input application: a broken mixin, a missing access transformer line or a dead search pool fails these.
 *
 * <p>The driven entity is an armor stand, the smallest vanilla {@code LivingEntity} the engine can drive unchanged. A
 * zombie cannot be: {@code Mob.serverAiStep} is final and never calls {@code LivingEntity.serverAiStep}, which is the
 * method the engine's AI-suppression mixin cancels, so the zombie's own move control zeroes the engine's forward input
 * every tick. The armor stand runs the base method, which the mixin cancels while the engine is active. Its movement
 * speed attribute (0.7 by default) is set to 0.25 so that the engine's forward input walks it at about a player's
 * walking pace instead of twice a player's sprint.
 */
public final class NavEngineGameTests {
    private NavEngineGameTests() {}

    private static final int MAX_TICKS = 400;
    private static final double MOVEMENT_SPEED = 0.25;

    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = MAX_TICKS + 40)
    public static void pathsAcrossFlatGround(GameTestHelper helper) {
        buildFloor(helper);
        walk(helper, new BlockPos(2, 1, 2), new BlockPos(14, 1, 2), baritone -> {}, () -> {});
    }

    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = MAX_TICKS + 40)
    public static void pathsAroundAWall(GameTestHelper helper) {
        buildFloor(helper);
        // 5 wide (z 5..9), 3 high (y 1..3), across the straight line from start to goal at z 7.
        for (int z = 5; z <= 9; z++) {
            for (int y = 1; y <= 3; y++) {
                helper.setBlock(new BlockPos(8, y, z), Blocks.STONE_BRICKS);
            }
        }
        walk(helper, new BlockPos(2, 1, 7), new BlockPos(14, 1, 7),
            baritone -> baritone.settings().allowBreak.set(false),
            () -> {
                for (int z = 5; z <= 9; z++) {
                    for (int y = 1; y <= 3; y++) {
                        helper.assertBlockPresent(Blocks.STONE_BRICKS, new BlockPos(8, y, z));
                    }
                }
            });
    }

    private static void buildFloor(GameTestHelper helper) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
    }

    /**
     * Spawns the armor stand at {@code start}, gives the engine a block goal at {@code goal} and ticks the engine once
     * per server tick. Passes when the stand stands on the ground within one block of the goal inside
     * {@link #MAX_TICKS} ticks without ever moving more than one block in a single tick.
     */
    private static void walk(GameTestHelper helper, BlockPos start, BlockPos goal, Consumer<IBaritone> configure, Runnable finalCheck) {
        ArmorStand stand = helper.spawn(EntityType.ARMOR_STAND, start);
        stand.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(MOVEMENT_SPEED);

        IBaritone baritone = IBaritone.KEY.get(stand);
        configure.accept(baritone);
        BlockPos goalAbsolute = helper.absolutePos(goal);
        baritone.getCustomGoalProcess().setGoalAndPath(new GoalBlock(goalAbsolute));
        Vec3 goalCenter = Vec3.atBottomCenterOf(goalAbsolute);

        int[] ticks = {0};
        Vec3[] previous = {stand.position()};
        boolean[] done = {false};
        // GameTests tick after the level's entities, so this runs between one movement step and the next: the input the
        // engine sets here is what the stand's next tick applies.
        helper.onEachTick(() -> {
            if (done[0]) {
                return;
            }
            ticks[0]++;
            Vec3 now = stand.position();
            double step = now.distanceTo(previous[0]);
            previous[0] = now;
            if (step > 1.0) {
                done[0] = true;
                stop(baritone, stand);
                helper.fail("moved " + step + " blocks in one tick at tick " + ticks[0] + " (to " + now + ")");
                return;
            }
            if (stand.onGround() && now.distanceTo(goalCenter) <= 1.0) {
                done[0] = true;
                stop(baritone, stand);
                finalCheck.run();
                if (IBaritone.KEY.getNullable(stand) != null) {
                    helper.fail("the engine's component entry survived the entity's removal");
                    return;
                }
                MegaBlockBot.LOGGER.info("Nav engine GameTest: reached {} in {} ticks", goal, ticks[0]);
                helper.succeed();
                return;
            }
            if (ticks[0] > MAX_TICKS) {
                done[0] = true;
                stop(baritone, stand);
                helper.fail("not within one block of " + goal + " after " + MAX_TICKS + " ticks; at " + helper.relativeVec(now)
                    + ", pathing " + baritone.getPathingBehavior().isPathing() + ", active " + baritone.isActive());
                return;
            }
            baritone.serverTick();
        });
    }

    private static void stop(IBaritone baritone, ArmorStand stand) {
        baritone.getCustomGoalProcess().onLostControl();
        baritone.getPathingBehavior().forceCancel();
        baritone.getInputOverrideHandler().clearAllKeys();
        stand.discard();
    }
}
