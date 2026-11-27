package com.legoj15.megablockbot.game.gametest;

import com.legoj15.megablockbot.MegaBlockBot;
import com.legoj15.megablockbot.core.ItemKey;
import com.legoj15.megablockbot.core.exec.GoalResult;
import com.legoj15.megablockbot.core.goal.Goal;
import com.legoj15.megablockbot.core.plan.NodeSpec;
import com.legoj15.megablockbot.core.task.Failure;
import com.legoj15.megablockbot.core.task.Task;
import com.legoj15.megablockbot.core.task.TaskState;
import com.legoj15.megablockbot.core.task.TickResult;
import com.legoj15.megablockbot.game.body.BodyException;
import com.legoj15.megablockbot.game.body.BodyManager;
import com.legoj15.megablockbot.game.body.BotPlayer;
import com.legoj15.megablockbot.game.bot.BotRuntime;
import com.legoj15.megablockbot.game.bot.BotSpeech;
import com.legoj15.megablockbot.game.plan.BotMemory;
import com.legoj15.megablockbot.game.task.TaskRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The smelt task against a running server (issue #14). The sticks variant of the iron recipe cooks one raw iron per
 * run in 200 ticks with two sticks, so the two runs every test asks for take about 400 ticks of game time plus the
 * placing, the walks and the take-up. What these protect: that a smelt leaf ends with the result in the inventory and
 * nothing lost, whatever stands in the way — no pickaxe for the furnace, a furnace somebody else put there, no fuel,
 * an interruption in the middle of the burn.
 *
 * <p>Each test spawns its own body (a name unique to this run) in the arena, whose floor is the stone layer at y=1:
 * feet stand at y=2.0. Bodies are despawned and forgotten when the test ends, passed or not. Placing items in a
 * body's inventory is test setup, not the bot cheating: the bot's own smelting is what is measured.
 */
public final class SmeltGameTests {
    private static final int FLOOR_Y = 1;
    private static final double FLOOR_TOP = FLOOR_Y + 1.0;
    private static final String RUN_ID = Integer.toString((int) (System.nanoTime() & 0xFFFFF), 36);
    /** Two runs of the sticks variant: 400 ticks of cooking, the placing, the walks and the take-up. */
    private static final int SMELT_TICKS = 800;
    private static final String VARIANT = "minecraft:iron_ingot_from_smelting_raw_iron#sticks";

    private SmeltGameTests() {}

    // ---------------------------------------------------------------------------------------------------------
    // The smelt leaf
    // ---------------------------------------------------------------------------------------------------------

    /**
     * A body holding a furnace, 2 raw iron, 4 sticks and a wooden pickaxe (set-up), given the sticks variant with
     * {@code times} 2: it ends holding 2 iron ingots, no raw iron and no sticks, the furnace is back in its
     * inventory, and no furnace stands in the arena.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = SMELT_TICKS)
    public static void ironFromTwoRawIronAndSticks(GameTestHelper helper) {
        floor(helper);
        Rig rig = Rig.spawn(helper, "smelt", SMELT_TICKS);
        giveTheKit(rig, true);
        TaskRun run = new TaskRun(smeltTask(2));
        rig.onEachTick(() -> {
            if (!run.tick(rig.body())) return;
            helper.assertTrue(run.result instanceof TickResult.Completed, "the smelt ended " + run.result);
            helper.assertTrue(rig.count(Items.IRON_INGOT) == 2, "expected 2 iron ingots, got " + rig.count(Items.IRON_INGOT));
            helper.assertTrue(rig.count(Items.RAW_IRON) == 0 && rig.count(Items.STICK) == 0,
                "the raw iron or the sticks were not all spent: " + rig.count(Items.RAW_IRON) + " raw iron, "
                    + rig.count(Items.STICK) + " sticks");
            helper.assertTrue(rig.count(Items.FURNACE) == 1, "the furnace is not back in the inventory: " + rig.count(Items.FURNACE));
            helper.assertTrue(rig.furnacesInArena().isEmpty(), "a furnace was left standing at " + rig.furnacesInArena());
            helper.assertTrue(itemsOnTheFloor(helper).isEmpty(), "items lie in the arena: " + itemsOnTheFloor(helper));
            helper.assertTrue(BotMemory.of(rig.id()).placedCount() == 0, "the body still remembers a block it placed");
            rig.assertHandsEmpty();
            rig.pass();
        });
    }

    /**
     * The same without a pickaxe (set-up): a furnace broken by hand drops nothing, so it is left standing, remembered
     * as the body's, and the task completes all the same with the 2 ingots made.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = SMELT_TICKS)
    public static void aFurnaceWithoutAPickaxeIsLeftStanding(GameTestHelper helper) {
        floor(helper);
        Rig rig = Rig.spawn(helper, "nopick", SMELT_TICKS);
        giveTheKit(rig, false);
        TaskRun run = new TaskRun(smeltTask(2));
        rig.onEachTick(() -> {
            if (!run.tick(rig.body())) return;
            helper.assertTrue(run.result instanceof TickResult.Completed, "the smelt ended " + run.result);
            helper.assertTrue(rig.count(Items.IRON_INGOT) == 2, "expected 2 iron ingots, got " + rig.count(Items.IRON_INGOT));
            List<BlockPos> furnaces = rig.furnacesInArena();
            helper.assertTrue(furnaces.size() == 1, "expected the furnace to be left standing, found " + furnaces);
            helper.assertTrue(rig.count(Items.FURNACE) == 0, "the furnace that stands is also in the inventory");
            helper.assertTrue(BotMemory.of(rig.id()).isPlaced(helper.getLevel().dimension().location().toString(),
                helper.absolutePos(furnaces.get(0))), "the body no longer remembers the furnace it left standing");
            rig.assertHandsEmpty();
            rig.pass();
        });
    }

    /**
     * A furnace somebody else put there (set-up: a block the test places, not in the body's memory) stands within
     * reach: the body does not open it, it puts its own down, and the stranger's furnace still stands at the end.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = SMELT_TICKS)
    public static void aFurnaceSomebodyElsePutThereIsNotUsed(GameTestHelper helper) {
        floor(helper);
        BlockPos stranger = new BlockPos(9, FLOOR_Y + 1, 8);
        helper.setBlock(stranger, Blocks.FURNACE);
        Rig rig = Rig.spawn(helper, "other", SMELT_TICKS);
        giveTheKit(rig, true);
        TaskRun run = new TaskRun(smeltTask(2));
        boolean[] ownPlaced = {false};
        rig.onEachTick(() -> {
            if (!ownPlaced[0] && rig.furnacesInArena().size() > 1) ownPlaced[0] = true;
            if (!run.tick(rig.body())) return;
            helper.assertTrue(run.result instanceof TickResult.Completed, "the smelt ended " + run.result);
            helper.assertTrue(rig.count(Items.IRON_INGOT) == 2, "expected 2 iron ingots, got " + rig.count(Items.IRON_INGOT));
            helper.assertTrue(ownPlaced[0], "the body never put a furnace of its own down, so it worked at the one that stood there");
            helper.assertTrue(helper.getBlockState(stranger).is(Blocks.FURNACE), "the furnace somebody else put there is gone");
            helper.assertTrue(rig.count(Items.FURNACE) == 1, "the body's furnace is not back in the inventory: " + rig.count(Items.FURNACE));
            helper.assertTrue(rig.furnacesInArena().size() == 1, "expected only the stranger's furnace to stand, found "
                + rig.furnacesInArena());
            rig.assertHandsEmpty();
            rig.pass();
        });
    }

    /** No fuel in the inventory: {@code MissingInput} naming a fuel item, and nothing was put down. */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 100)
    public static void noFuelIsAMissingInputBeforeAnythingIsPutDown(GameTestHelper helper) {
        floor(helper);
        Rig rig = Rig.spawn(helper, "nofuel", 100);
        rig.give(Items.FURNACE, 1);
        rig.give(Items.RAW_IRON, 2);
        rig.give(Items.WOODEN_PICKAXE, 1);
        TaskRun run = new TaskRun(smeltTask(2));
        rig.onEachTick(() -> {
            if (!run.tick(rig.body())) return;
            helper.assertTrue(run.failure() instanceof Failure.MissingInput missing
                && missing.key().id().equals("minecraft:stick"), "the smelt ended " + run.result);
            helper.assertTrue(rig.furnacesInArena().isEmpty(), "a furnace was put down at " + rig.furnacesInArena());
            helper.assertTrue(rig.count(Items.FURNACE) == 1 && rig.count(Items.RAW_IRON) == 2, "something was lost");
            rig.assertHandsEmpty();
            rig.pass();
        });
    }

    /**
     * A pause and a resume in the middle of the burn, as the craft tests pause and resume: the goal is paused once
     * the furnace is burning and nothing is made yet, the body is walked off through its own input until the furnace
     * is out of reach, and the goal is resumed. It still ends with the 2 ingots, the furnace back in the inventory and
     * nothing left in the furnace or spilled.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 1000)
    public static void aSmeltInterruptedMidBurnCarriesOn(GameTestHelper helper) {
        floor(helper);
        Rig rig = Rig.spawn(helper, "midburn", 1000);
        giveTheKit(rig, true);
        rig.runtime().submit(new Goal.HaveItem(ItemKey.of("iron_ingot"), 2));
        int[] stage = {0};
        int[] walked = {0};
        rig.onEachTick(() -> {
            BotPlayer body = rig.body();
            GoalResult result = rig.runtime().lastResult();
            switch (stage[0]) {
                case 0 -> {
                    helper.assertTrue(result == null, "the goal ended before the burn began: "
                        + (result == null ? "" : BotSpeech.result(result)));
                    if (rig.litFurnace() != null && rig.count(Items.IRON_INGOT) == 0) {
                        rig.runtime().pause();
                        stage[0] = 1;
                    }
                }
                case 1 -> {
                    BlockPos furnace = rig.furnacesInArena().isEmpty() ? null
                        : helper.absolutePos(rig.furnacesInArena().get(0));
                    if (furnace == null || !body.canInteractWithBlock(furnace, 0.0) || ++walked[0] > 150) {
                        body.input().reset();
                        helper.assertTrue(furnace != null && !body.canInteractWithBlock(furnace, 0.0),
                            "the walk did not carry the body out of the furnace's reach");
                        rig.runtime().resume();
                        stage[0] = 2;
                        return;
                    }
                    // The corner of the arena farthest from the furnace: the task chooses where the furnace goes, and a
                    // fixed place to walk to is within reach of it whenever it was put down on that side.
                    Vec3 from = Vec3.atCenterOf(furnace);
                    Vec3 away = null;
                    for (double x : new double[] {1.5, 14.5}) {
                        for (double z : new double[] {1.5, 14.5}) {
                            Vec3 corner = helper.absoluteVec(new Vec3(x, 0, z));
                            if (away == null || corner.distanceToSqr(from) > away.distanceToSqr(from)) away = corner;
                        }
                    }
                    body.input().lookAt(new Vec3(away.x, body.getEyeY(), away.z));
                    body.input().forward = 1.0F;
                }
                default -> {
                    if (result == null) return;
                    helper.assertTrue(result.status() == GoalResult.Status.SATISFIED, "the goal ended " + BotSpeech.result(result));
                    helper.assertTrue(rig.count(Items.IRON_INGOT) == 2, "expected 2 iron ingots, got " + rig.count(Items.IRON_INGOT));
                    helper.assertTrue(rig.count(Items.RAW_IRON) == 0 && rig.count(Items.STICK) == 0,
                        "the raw iron or the sticks were not all spent: " + rig.count(Items.RAW_IRON) + " raw iron, "
                            + rig.count(Items.STICK) + " sticks");
                    helper.assertTrue(rig.count(Items.FURNACE) == 1, "the furnace is not back in the inventory: " + rig.count(Items.FURNACE));
                    helper.assertTrue(rig.furnacesInArena().isEmpty(), "a furnace was left standing at " + rig.furnacesInArena());
                    helper.assertTrue(itemsOnTheFloor(helper).isEmpty(), "what the furnace held was spilled: " + itemsOnTheFloor(helper));
                    helper.assertTrue(BotMemory.of(rig.id()).placedCount() == 0, "the body still remembers a block it placed");
                    rig.assertHandsEmpty();
                    rig.pass();
                }
            }
        });
    }

    /**
     * Through the runtime, a goal and not a bare leaf (set-up: 1 raw iron, 2 sticks, a furnace and a wooden pickaxe):
     * asked to have 1 iron ingot, the plan smelts the raw iron it carries and ends satisfied.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 700)
    public static void oneIronIngotThroughThePlanner(GameTestHelper helper) {
        floor(helper);
        Rig rig = Rig.spawn(helper, "ingot", 700);
        rig.give(Items.RAW_IRON, 1);
        rig.give(Items.STICK, 2);
        rig.give(Items.FURNACE, 1);
        rig.give(Items.WOODEN_PICKAXE, 1);
        rig.runtime().submit(new Goal.HaveItem(ItemKey.of("iron_ingot"), 1));
        rig.onEachTick(() -> {
            GoalResult result = rig.runtime().lastResult();
            if (result == null) return;
            helper.assertTrue(result.status() == GoalResult.Status.SATISFIED, "the goal ended " + BotSpeech.result(result));
            helper.assertTrue(rig.count(Items.IRON_INGOT) == 1, "expected 1 iron ingot, got " + rig.count(Items.IRON_INGOT));
            helper.assertTrue(rig.count(Items.RAW_IRON) == 0, "the raw iron was not smelted: " + rig.count(Items.RAW_IRON));
            helper.assertTrue(rig.count(Items.FURNACE) == 1 && rig.furnacesInArena().isEmpty(),
                "the furnace is not back in the inventory and off the ground: " + rig.count(Items.FURNACE) + ", "
                    + rig.furnacesInArena());
            rig.assertHandsEmpty();
            rig.pass();
        });
    }

    // ---------------------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------------------

    private static void floor(GameTestHelper helper) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) helper.setBlock(x, FLOOR_Y, z, Blocks.STONE);
        }
    }

    /** The kit of the smelt tests (set-up, not the bot's doing): a furnace, two raw iron, four sticks, and a pickaxe
     * for the take-up unless the test is about a body without one. */
    private static void giveTheKit(Rig rig, boolean pickaxe) {
        rig.give(Items.FURNACE, 1);
        rig.give(Items.RAW_IRON, 2);
        rig.give(Items.STICK, 4);
        if (pickaxe) rig.give(Items.WOODEN_PICKAXE, 1);
    }

    /** The sticks-variant leaf for two runs, whose goal is the absolute count of the result afterwards. */
    private static Task<BotPlayer> smeltTask(int times) {
        return TaskRegistry.create(NodeSpec.leaf(new Goal.HaveItem(ItemKey.of("iron_ingot"), 2), "smelt",
            Map.of("recipe", VARIANT, "times", Integer.toString(times), "station", "minecraft:furnace"), ""), Map.of());
    }

    /** Every item entity in the arena. */
    private static List<ItemEntity> itemsOnTheFloor(GameTestHelper helper) {
        return helper.getLevel().getEntitiesOfClass(ItemEntity.class, arenaBox(helper), candidate -> !candidate.isRemoved());
    }

    /** This test's arena as an absolute box. */
    private static AABB arenaBox(GameTestHelper helper) {
        BlockPos corner = helper.absolutePos(BlockPos.ZERO);
        return new AABB(corner.getX(), corner.getY(), corner.getZ(), corner.getX() + 16, corner.getY() + 16, corner.getZ() + 16);
    }

    /** One task ticked the way the executor ticks it, keeping how it ended. */
    private static final class TaskRun {
        private final Task<BotPlayer> task;
        TickResult result;

        TaskRun(Task<BotPlayer> task) {
            this.task = task;
        }

        /** One tick of the task, starting it first; true once it has ended. */
        boolean tick(BotPlayer body) {
            if (result != null && !(result instanceof TickResult.Running)) return true;
            if (task.state() == TaskState.PENDING) task.start(body);
            result = task.tick(body);
            return !(result instanceof TickResult.Running);
        }

        Failure failure() {
            return result instanceof TickResult.Failed failed ? failed.failure() : null;
        }
    }

    /**
     * One test body, spawned in the arena under a name unique to this run; despawned and forgotten when the test
     * ends, passed or not.
     */
    private static final class Rig {
        private final GameTestHelper helper;
        private final BodyManager manager;
        private final String name;
        private final UUID id;

        private Rig(GameTestHelper helper, String tag) {
            this.helper = helper;
            this.manager = BodyManager.of(helper.getLevel().getServer());
            this.name = "gt_" + tag + "_" + RUN_ID;
            this.id = BodyManager.uuidFor(name);
        }

        /** A body with no owner: the bot's answers go to the log only. */
        static Rig spawn(GameTestHelper helper, String tag, int deadlineTicks) {
            Rig rig = new Rig(helper, tag);
            rig.respawn(helper.absoluteVec(new Vec3(8.5, FLOOR_TOP, 8.5)));
            helper.runAtTickTime(deadlineTicks, rig::cleanup);
            return rig;
        }

        UUID id() {
            return id;
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

        /** Every furnace block in the arena, as relative positions. */
        List<BlockPos> furnacesInArena() {
            List<BlockPos> found = new ArrayList<>();
            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        BlockState state = helper.getBlockState(new BlockPos(x, y, z));
                        if (state.is(Blocks.FURNACE) || state.is(Blocks.BLAST_FURNACE) || state.is(Blocks.SMOKER)) {
                            found.add(new BlockPos(x, y, z));
                        }
                    }
                }
            }
            return found;
        }

        /** The furnace in the arena that is burning, or null. */
        @Nullable
        BlockPos litFurnace() {
            for (BlockPos pos : furnacesInArena()) {
                if (helper.getBlockState(pos).getValue(BlockStateProperties.LIT)) return pos;
            }
            return null;
        }

        /** The cursor and every crafting grid slot of the menu the body has open are empty. */
        void assertHandsEmpty() {
            BotPlayer body = body();
            helper.assertTrue(body.containerMenu == body.inventoryMenu, "a menu is still open: " + body.containerMenu);
            helper.assertTrue(body.containerMenu.getCarried().isEmpty(), "the cursor holds " + body.containerMenu.getCarried());
            for (int slot = 1; slot <= 4; slot++) {
                helper.assertTrue(body.inventoryMenu.getSlot(slot).getItem().isEmpty(),
                    "the 2x2 grid slot " + slot + " holds " + body.inventoryMenu.getSlot(slot).getItem());
            }
        }

        void onEachTick(Runnable action) {
            helper.onEachTick(() -> {
                try {
                    action.run();
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
