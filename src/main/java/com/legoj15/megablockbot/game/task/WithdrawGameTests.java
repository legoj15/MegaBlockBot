package com.legoj15.megablockbot.game.task;

import com.legoj15.megablockbot.MegaBlockBot;
import com.legoj15.megablockbot.core.ItemKey;
import com.legoj15.megablockbot.core.Pos;
import com.legoj15.megablockbot.core.goal.Goal;
import com.legoj15.megablockbot.core.plan.NodeSpec;
import com.legoj15.megablockbot.game.body.BodyException;
import com.legoj15.megablockbot.game.body.BodyManager;
import com.legoj15.megablockbot.game.body.BotPlayer;
import com.legoj15.megablockbot.game.plan.BotMemory;
import com.mojang.authlib.GameProfile;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.phys.Vec3;

/**
 * What a withdraw does with the stack it lifts (design 3.5, supply containers): it never ends with anything on the
 * cursor, whether or not the slot it lifted from takes the rest back. A furnace's result slot does not (a named furnace
 * can be withdrawn from): what the body does not set down in its inventory goes to its inventory too, and to the ground
 * only when that is full. The task's own clicks are driven on a menu the test opened, because the task closes its menu
 * when it ends, and closing hands the cursor back by itself, which would hide the stranded remainder.
 */
public final class WithdrawGameTests {
    private static final String RUN_ID = Integer.toString((int) (System.nanoTime() & 0xFFFFF), 36);
    private static final ItemKey CHARCOAL = ItemKey.of("charcoal");
    private static final BlockPos FURNACE = new BlockPos(4, 2, 4);

    private WithdrawGameTests() {}

    /**
     * Eight charcoal lie in a furnace's result slot and the leaf takes three. The slot lets the stack be lifted and does
     * not take any of it back, so the five that are not set down are the inventory's, whole: the cursor is empty, the
     * furnace has none, the body holds all eight, nothing lies on the ground, and the count the task reports is the
     * inventory's gain, not the number of items it set down one by one.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 40)
    public static void aPartialTakeFromASlotThatRefusesTheRestLeavesNothingOnTheCursor(GameTestHelper helper) {
        helper.setBlock(FURNACE, Blocks.FURNACE);
        AbstractFurnaceBlockEntity furnace = (AbstractFurnaceBlockEntity) helper.getBlockEntity(FURNACE);
        furnace.setItem(2, new ItemStack(Items.CHARCOAL, 8));
        Body body = Body.spawn(helper, "cursor");
        try {
            AbstractContainerMenu menu = open(body.player, furnace);
            WithdrawTask task = body.task(helper, 3);
            int taken = task.takeItems(body.player, menu, 3);
            helper.assertTrue(menu.getCarried().isEmpty(), "the cursor still holds " + menu.getCarried());
            helper.assertTrue(furnace.getItem(2).isEmpty(), "the result slot still holds " + furnace.getItem(2));
            helper.assertTrue(body.player.getInventory().countItem(Items.CHARCOAL) == 8,
                "the body holds " + body.player.getInventory().countItem(Items.CHARCOAL) + " charcoal, not the 8 that left the furnace");
            helper.assertTrue(taken == 8, "the task counted " + taken + " taken, the inventory gained 8");
            helper.assertTrue(onTheGround(body.player, Items.CHARCOAL) == 0, "charcoal was dropped: " + onTheGround(body.player, Items.CHARCOAL));
        } finally {
            body.player.closeContainer();
            body.cleanup();
        }
        helper.succeed();
    }

    /**
     * The same take with an inventory that has room for two more charcoal and nothing else: the two are set down, the six
     * that are left have nowhere to go but the ground, where they are not lost, and the count reported is the two the
     * inventory took, which is less than the leaf wanted, so the task says there is no room.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 40)
    public static void aPartialTakeFromASlotThatRefusesTheRestDropsOnlyWhatTheInventoryCannotHold(GameTestHelper helper) {
        helper.setBlock(FURNACE, Blocks.FURNACE);
        AbstractFurnaceBlockEntity furnace = (AbstractFurnaceBlockEntity) helper.getBlockEntity(FURNACE);
        furnace.setItem(2, new ItemStack(Items.CHARCOAL, 8));
        Body body = Body.spawn(helper, "full");
        try {
            for (int slot = 0; slot < body.player.getInventory().items.size(); slot++) {
                body.player.getInventory().items.set(slot, new ItemStack(Items.COBBLESTONE, 64));
            }
            body.player.getInventory().items.set(5, new ItemStack(Items.CHARCOAL, 62));
            AbstractContainerMenu menu = open(body.player, furnace);
            WithdrawTask task = body.task(helper, 3);
            int taken = task.takeItems(body.player, menu, 3);
            helper.assertTrue(menu.getCarried().isEmpty(), "the cursor still holds " + menu.getCarried());
            helper.assertTrue(body.player.getInventory().countItem(Items.CHARCOAL) == 64,
                "the body holds " + body.player.getInventory().countItem(Items.CHARCOAL) + " charcoal, room was for 2 more");
            helper.assertTrue(taken == 2, "the task counted " + taken + " taken, the inventory gained 2");
            helper.assertTrue(onTheGround(body.player, Items.CHARCOAL) == 6, "the ground holds " + onTheGround(body.player, Items.CHARCOAL) + " charcoal, not the 6 left over");
            helper.assertTrue(furnace.getItem(2).isEmpty(), "the result slot still holds " + furnace.getItem(2));
        } finally {
            body.player.closeContainer();
            body.cleanup();
        }
        helper.succeed();
    }

    private static AbstractContainerMenu open(BotPlayer player, AbstractFurnaceBlockEntity furnace) {
        player.openMenu(furnace);
        if (player.containerMenu == player.inventoryMenu) throw new IllegalStateException("the furnace did not open");
        return player.containerMenu;
    }

    private static int onTheGround(BotPlayer player, Item item) {
        int total = 0;
        for (ItemEntity stack : player.serverLevel().getEntitiesOfClass(ItemEntity.class, player.getBoundingBox().inflate(6))) {
            if (stack.getItem().is(item)) total += stack.getItem().getCount();
        }
        return total;
    }

    /** One body in the test's room, forgotten with its memory when the test is done. */
    private static final class Body {
        final GameTestHelper helper;
        final BodyManager manager;
        final UUID id;
        final BotPlayer player;

        private Body(GameTestHelper helper, BodyManager manager, UUID id, BotPlayer player) {
            this.helper = helper;
            this.manager = manager;
            this.id = id;
            this.player = player;
        }

        static Body spawn(GameTestHelper helper, String tag) {
            BodyManager manager = BodyManager.of(helper.getLevel().getServer());
            String name = "gw_" + tag + "_" + RUN_ID;
            Vec3 at = helper.absoluteVec(new Vec3(8.5, 2.0, 8.5));
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) helper.setBlock(x, 1, z, Blocks.STONE);
            }
            try {
                BotPlayer player = manager.spawn(name, new GameProfile(UUID.randomUUID(), "owner_" + tag), helper.getLevel(), at);
                return new Body(helper, manager, BodyManager.uuidFor(name), player);
            } catch (BodyException e) {
                throw new IllegalStateException("could not spawn " + name + ": " + e.getMessage(), e);
            }
        }

        /** The task of a leaf that takes {@code count} charcoal out of the furnace, prepared as the executor would start it. */
        WithdrawTask task(GameTestHelper helper, int count) {
            Map<String, String> params = new TreeMap<>();
            params.put("item", CHARCOAL.id());
            params.put("count", Integer.toString(count));
            BlockPos at = helper.absolutePos(FURNACE);
            params.put("container", new Pos(at.getX(), at.getY(), at.getZ()).encode());
            params.put("dimension", helper.getLevel().dimension().location().toString());
            WithdrawTask task = new WithdrawTask(NodeSpec.leaf(new Goal.HaveItem(CHARCOAL, count), "withdraw", params, "withdraw:test"));
            if (!task.prepare(player)) throw new IllegalStateException("the leaf's item is not registered");
            return task;
        }

        void cleanup() {
            manager.despawn(player);
            try {
                manager.deleteSaved(id);
            } catch (BodyException ignored) {
                // The unique name keeps a body that stayed logged in from mattering.
            }
            BotMemory.forget(id);
        }
    }
}
