package com.legoj15.megablockbot.game.gametest;

import com.legoj15.megablockbot.MegaBlockBot;
import com.legoj15.megablockbot.core.ItemKey;
import com.legoj15.megablockbot.core.goal.Goal;
import com.legoj15.megablockbot.core.plan.NodeSpec;
import com.legoj15.megablockbot.core.task.Failure;
import com.legoj15.megablockbot.core.task.Task;
import com.legoj15.megablockbot.core.task.TaskState;
import com.legoj15.megablockbot.core.task.TickResult;
import com.legoj15.megablockbot.game.body.BodyException;
import com.legoj15.megablockbot.game.body.BodyManager;
import com.legoj15.megablockbot.game.body.BotPlayer;
import com.legoj15.megablockbot.game.plan.BotMemory;
import com.legoj15.megablockbot.game.task.TaskRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The stonecut and smith tasks against a running server (issue #14). The slab recipe cuts one stone into two stone
 * slabs, so the three cuts of the first test take three stone and make six slabs; the netherite upgrade turns a
 * diamond pickaxe and a netherite ingot into a netherite pickaxe over one template. What these protect: that a leaf
 * of either kind ends with the result in the inventory and every input spent, the station back in the inventory and
 * nothing left in the menu or on the floor, and that a missing input is a {@code MissingInput} that puts nothing down.
 *
 * <p>Each test spawns its own body (a name unique to this run) in the arena, whose floor is the stone layer at y=1:
 * feet stand at y=2.0. Bodies are despawned and forgotten when the test ends, passed or not. Placing items in a
 * body's inventory is test setup, not the bot cheating: the bot's own stonecutting and smithing is what is measured.
 */
public final class StationGameTests {
    private static final int FLOOR_Y = 1;
    private static final double FLOOR_TOP = FLOOR_Y + 1.0;
    private static final String RUN_ID = Integer.toString((int) (System.nanoTime() & 0xFFFFF), 36);
    /**
     * The bound of a three-cut stonecut or one-upgrade smith. The work itself is a dozen clicks; the place, the open,
     * the take-up (a wooden pickaxe breaks a stonecutter in about fifty ticks, and its item lands after a ten-tick
     * pickup delay and a few hops) and the walks are another two hundred at most, so the work needs about 300. The
     * bound is twice that: a slow hop or a longer walk than the arena usually forces must not fail a test that is
     * about something else.
     */
    private static final int STATION_TICKS = 600;
    /** The vanilla recipe ids: stone into two slabs at a stonecutter, a diamond pickaxe into a netherite one at a table. */
    private static final String SLAB_RECIPE = "minecraft:stone_slab_from_stone_stonecutting";
    private static final String PICKAXE_RECIPE = "minecraft:netherite_pickaxe_smithing";

    private StationGameTests() {}

    // ---------------------------------------------------------------------------------------------------------
    // The tests
    // ---------------------------------------------------------------------------------------------------------

    /**
     * A body holding a stonecutter, 3 stone and a wooden pickaxe (set-up; a stonecutter is stone, and breaking one
     * without a tool drops nothing), given a {@code stonecut} leaf for three cuts of the slab recipe: it ends holding
     * 6 stone slabs and no stone, the stonecutter is back in its inventory, and no stonecutter stands in the arena.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = STATION_TICKS)
    public static void sixStoneSlabsFromThreeStone(GameTestHelper helper) {
        floor(helper);
        Rig rig = Rig.spawn(helper, "stone", STATION_TICKS);
        rig.give(Items.STONECUTTER, 1);
        rig.give(Items.STONE, 3);
        rig.give(Items.WOODEN_PICKAXE, 1);
        TaskRun run = new TaskRun(stonecutTask(3));
        rig.onEachTick(() -> {
            if (!run.tick(rig.body())) return;
            helper.assertTrue(run.result instanceof TickResult.Completed, "the stonecut ended " + run.result);
            helper.assertTrue(rig.count(Items.STONE_SLAB) == 6, "expected 6 stone slabs, got " + rig.count(Items.STONE_SLAB));
            helper.assertTrue(rig.count(Items.STONE) == 0, "the stone was not all spent: " + rig.count(Items.STONE));
            helper.assertTrue(rig.count(Items.STONECUTTER) == 1,
                "the stonecutter is not back in the inventory: " + rig.count(Items.STONECUTTER));
            helper.assertTrue(rig.stationsInArena(Blocks.STONECUTTER).isEmpty(),
                "a stonecutter was left standing at " + rig.stationsInArena(Blocks.STONECUTTER));
            helper.assertTrue(itemsOnTheFloor(helper).isEmpty(), "items lie in the arena: " + itemsOnTheFloor(helper));
            helper.assertTrue(BotMemory.of(rig.id()).placedCount() == 0, "the body still remembers a block it placed");
            rig.assertHandsEmpty();
            rig.pass();
        });
    }

    /**
     * A body holding a smithing table, a netherite upgrade template, a diamond pickaxe and a netherite ingot
     * (set-up), given a {@code smith} leaf for one upgrade: it ends holding the netherite pickaxe, the three inputs
     * gone, and the smithing table back in its inventory (a smithing table is wood, so breaking it needs no tool).
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = STATION_TICKS)
    public static void aDiamondPickaxeBecomesANetheriteOne(GameTestHelper helper) {
        floor(helper);
        Rig rig = Rig.spawn(helper, "smith", STATION_TICKS);
        rig.give(Items.SMITHING_TABLE, 1);
        rig.give(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE, 1);
        rig.give(Items.DIAMOND_PICKAXE, 1);
        rig.give(Items.NETHERITE_INGOT, 1);
        TaskRun run = new TaskRun(smithTask());
        rig.onEachTick(() -> {
            if (!run.tick(rig.body())) return;
            helper.assertTrue(run.result instanceof TickResult.Completed, "the smith ended " + run.result);
            helper.assertTrue(rig.count(Items.NETHERITE_PICKAXE) == 1,
                "expected 1 netherite pickaxe, got " + rig.count(Items.NETHERITE_PICKAXE));
            helper.assertTrue(rig.count(Items.DIAMOND_PICKAXE) == 0 && rig.count(Items.NETHERITE_INGOT) == 0
                    && rig.count(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE) == 0,
                "the inputs were not all spent: " + rig.count(Items.DIAMOND_PICKAXE) + " diamond pickaxe(s), "
                    + rig.count(Items.NETHERITE_INGOT) + " netherite ingot(s), "
                    + rig.count(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE) + " template(s)");
            helper.assertTrue(rig.count(Items.SMITHING_TABLE) == 1,
                "the smithing table is not back in the inventory: " + rig.count(Items.SMITHING_TABLE));
            helper.assertTrue(rig.stationsInArena(Blocks.SMITHING_TABLE).isEmpty(),
                "a smithing table was left standing at " + rig.stationsInArena(Blocks.SMITHING_TABLE));
            helper.assertTrue(itemsOnTheFloor(helper).isEmpty(), "items lie in the arena: " + itemsOnTheFloor(helper));
            helper.assertTrue(BotMemory.of(rig.id()).placedCount() == 0, "the body still remembers a block it placed");
            rig.assertHandsEmpty();
            rig.pass();
        });
    }

    /** No stone in the inventory: {@code MissingInput} naming it, and nothing was put down. */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 100)
    public static void noStoneIsAMissingInputBeforeAnythingIsPutDown(GameTestHelper helper) {
        floor(helper);
        Rig rig = Rig.spawn(helper, "nostone", 100);
        rig.give(Items.STONECUTTER, 1);
        TaskRun run = new TaskRun(stonecutTask(3));
        rig.onEachTick(() -> {
            if (!run.tick(rig.body())) return;
            helper.assertTrue(run.failure() instanceof Failure.MissingInput missing
                && ItemKey.of("minecraft:stone").equals(missing.key()), "the stonecut ended " + run.result);
            helper.assertTrue(rig.stationsInArena(Blocks.STONECUTTER).isEmpty(),
                "a stonecutter was put down at " + rig.stationsInArena(Blocks.STONECUTTER));
            helper.assertTrue(rig.count(Items.STONECUTTER) == 1, "something was lost");
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

    /** The stonecut leaf for {@code times} cuts of the slab recipe, whose goal is the absolute count of slabs afterwards. */
    private static Task<BotPlayer> stonecutTask(int times) {
        return TaskRegistry.create(NodeSpec.leaf(new Goal.HaveItem(ItemKey.of("stone_slab"), 6), "stonecut",
            Map.of("recipe", SLAB_RECIPE, "times", Integer.toString(times), "station", "minecraft:stonecutter"), ""), Map.of());
    }

    /** The smith leaf for one upgrade of the pickaxe recipe, whose goal is the absolute count of pickaxes afterwards. */
    private static Task<BotPlayer> smithTask() {
        return TaskRegistry.create(NodeSpec.leaf(new Goal.HaveItem(ItemKey.of("netherite_pickaxe"), 1), "smith",
            Map.of("recipe", PICKAXE_RECIPE, "times", "1", "station", "minecraft:smithing_table"), ""), Map.of());
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

        /** Every block of this station kind in the arena, as relative positions. */
        List<BlockPos> stationsInArena(Block station) {
            List<BlockPos> found = new ArrayList<>();
            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        BlockState state = helper.getBlockState(new BlockPos(x, y, z));
                        if (state.is(station)) found.add(new BlockPos(x, y, z));
                    }
                }
            }
            return found;
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
