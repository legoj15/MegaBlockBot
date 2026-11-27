package com.legoj15.megablockbot.game.gametest;

import static com.legoj15.megablockbot.game.gametest.BotNavGameTests.FLOOR_Y;
import static com.legoj15.megablockbot.game.gametest.BotNavGameTests.room;

import com.legoj15.megablockbot.MegaBlockBot;
import com.legoj15.megablockbot.core.ItemKey;
import com.legoj15.megablockbot.core.exec.ExecutorView;
import com.legoj15.megablockbot.core.exec.GoalResult;
import com.legoj15.megablockbot.core.goal.Goal;
import com.legoj15.megablockbot.game.body.BodyException;
import com.legoj15.megablockbot.game.body.BodyManager;
import com.legoj15.megablockbot.game.body.BotPlayer;
import com.legoj15.megablockbot.game.bot.BotRuntime;
import com.legoj15.megablockbot.game.bot.BotSpeech;
import com.legoj15.megablockbot.game.plan.BotMemory;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * A body that walks and mines for a goal, through the real brain (design 3.4, issue 27): the runtime that the bot is
 * spawned with, not a test's own brain. What this protects: that the runtime drives the navigator for a mine step.
 * {@code BotRuntime.tick} once forgot to, so a goal whose first step was to mine left the body standing still until the
 * stuck watchdog gave up on it, and no test saw it, because every test that moved a body ticked the navigator itself.
 * With the runtime's call to the navigator taken out, this test ends with the goal failed and the logs still standing.
 *
 * <p>Separate from {@link BotRuntimeGameTests} on purpose (another change is editing that class); it follows the same
 * conventions: a body named uniquely for the run, despawned and forgotten when the test ends, passed or not.
 */
public final class BotRuntimeMiningGameTests {
    private static final String RUN_ID = Integer.toString((int) (System.nanoTime() & 0xFFFFF), 36);

    private BotRuntimeMiningGameTests() {}

    /**
     * A goal to hold two mangrove logs (a kind no other test places: the tests run side by side, and a body goes for the
     * nearest logs of its kind, in the next arena too), with two logs standing in the arena and nothing in the inventory: the plan has a
     * {@code mine} leaf, the body walks to the logs and breaks them by its inputs, and the goal ends satisfied with the
     * logs in the inventory and no log left standing.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 1800)
    public static void aGoalWithAMineStepGetsItsLogsThroughTheRuntime(GameTestHelper helper) {
        room(helper, 3, Blocks.BARRIER);
        helper.setBlock(9, FLOOR_Y + 1, 5, Blocks.MANGROVE_LOG);
        helper.setBlock(4, FLOOR_Y + 1, 12, Blocks.MANGROVE_LOG);
        // The rules of the mining tests are global and apply to bodies side by side with them: this arena is allowed.
        Runnable undoRules = MoveAndMineGameTests.allowBreakingIn(helper);

        String name = "gt_rmine_" + RUN_ID;
        UUID id = BodyManager.uuidFor(name);
        BodyManager manager = BodyManager.of(helper.getLevel().getServer());
        Vec3 start = new Vec3(2.5, FLOOR_Y + 1, 2.5);
        Runnable cleanup = () -> {
            undoRules.run();
            BotPlayer current = manager.get(id);
            if (current != null) manager.despawn(current);
            try {
                manager.deleteSaved(id);
            } catch (BodyException ignored) {
                // Still logged in only if the despawn did not take; the unique name keeps that from mattering.
            }
            BotMemory.forget(id);
        };
        helper.runAtTickTime(1800, cleanup);
        try {
            // The default brain: BotBrains' factory, which is the BotRuntime.
            manager.spawn(name, null, helper.getLevel(), helper.absoluteVec(start));
            BotRuntime.of(manager.get(id)).submit(new Goal.HaveItem(ItemKey.of("mangrove_log"), 2));
        } catch (BodyException | RuntimeException e) {
            cleanup.run();
            throw new IllegalStateException("could not start " + name + ": " + e.getMessage(), e);
        }

        boolean[] mineLeaf = {false};
        boolean[] saidHowLong = {false};
        boolean[] saidWhere = {false};
        double[] farthest = {0.0};
        int[] ticks = {0};
        helper.onEachTick(() -> {
            try {
                BotPlayer body = manager.get(id);
                BotRuntime runtime = BotRuntime.of(body);
                helper.assertTrue(runtime != null, "the body has no runtime: its brain is " + body.brain());
                ticks[0]++;
                farthest[0] = Math.max(farthest[0], helper.relativeVec(body.position()).distanceTo(start));
                for (ExecutorView.NodeView node : runtime.view().nodes()) {
                    if ("mine".equals(node.taskKind())) mineLeaf[0] = true;
                }
                // `aiplayer status` while the step runs: how long the body has been on it and where it is heading.
                String status = runtime.statusText();
                if (status.contains("On it for")) saidHowLong[0] = true;
                if (status.contains("heading for a block at")) saidWhere[0] = true;
                GoalResult result = runtime.lastResult();
                if (result == null) {
                    helper.assertTrue(ticks[0] < 1500, "no result after " + ticks[0] + " ticks; the body moved " + farthest[0]
                        + " blocks from where it started; status: " + runtime.statusText());
                    return;
                }
                helper.assertTrue(result.status() == GoalResult.Status.SATISFIED, "the goal ended " + BotSpeech.result(result));
                helper.assertTrue(mineLeaf[0], "the plan had no mine leaf, so no mining was driven");
                helper.assertTrue(saidHowLong[0] && saidWhere[0], "the status never said how long the step had run (" + saidHowLong[0]
                    + ") and what the body was heading for (" + saidWhere[0] + ")");
                helper.assertTrue(body.getInventory().countItem(Items.MANGROVE_LOG) == 2,
                    "holds " + body.getInventory().countItem(Items.MANGROVE_LOG) + " mangrove logs");
                helper.assertBlockPresent(Blocks.AIR, new BlockPos(9, FLOOR_Y + 1, 5));
                helper.assertBlockPresent(Blocks.AIR, new BlockPos(4, FLOOR_Y + 1, 12));
                MegaBlockBot.LOGGER.info("Runtime mining GameTest: satisfied after {} ticks, {} blocks from the start", ticks[0], farthest[0]);
                cleanup.run();
                helper.succeed();
            } catch (RuntimeException | Error e) {
                cleanup.run();
                throw e;
            }
        });
    }
}
