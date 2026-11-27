package com.legoj15.megablockbot.game.task;

import com.legoj15.megablockbot.game.body.BotPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * A body's hand on an open menu: the slot clicks a player makes with the mouse, and nothing else. Every item moves
 * through {@code AbstractContainerMenu.clicked}, the method a client's click packet ends in, so slots enforce their own
 * rules (an armor slot takes one fitting piece, a result slot takes only out) and the crafting grid recomputes its result.
 *
 * <p>The cursor is the menu's carried stack. {@link #pickUp} remembers which slot it took from so {@link #putBack} can
 * return it there. Whoever uses a hand ends with {@link #putBack} and, for a crafting grid, {@link #emptyGrid}: a task
 * never leaves anything on the cursor or in a grid, on any exit.
 */
final class MenuHand {
    /** The slots of {@link Inventory#items}: hotbar and main storage, not armor or the off hand. */
    static final int CARRIED_SLOTS = 36;

    private final BotPlayer body;
    private final AbstractContainerMenu menu;
    private int origin = -1;

    MenuHand(BotPlayer body, AbstractContainerMenu menu) {
        this.body = body;
        this.menu = menu;
    }

    /** The menu slot that shows the body's inventory slot, or -1 when this menu does not show it. */
    int slotOf(int inventorySlot) {
        Inventory inventory = body.getInventory();
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            if (slot.container == inventory && slot.getContainerSlot() == inventorySlot) return i;
        }
        return -1;
    }

    Slot slot(int menuSlot) {
        return menu.getSlot(menuSlot);
    }

    /** The first menu slot that shows a stack of {@code item} in carried storage, or -1. */
    int findCarried(Item item) {
        Inventory inventory = body.getInventory();
        for (int i = 0; i < CARRIED_SLOTS; i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && stack.is(item)) return slotOf(i);
        }
        return -1;
    }

    void click(int menuSlot, int button, ClickType type) {
        menu.clicked(menuSlot, button, type, body);
    }

    /**
     * Makes the cursor hold a stack of {@code item}: it already does, or whatever it held is put back and a stack is
     * picked up. False when the body carries none.
     */
    boolean pickUp(Item item) {
        ItemStack carried = menu.getCarried();
        if (!carried.isEmpty() && carried.is(item)) return true;
        putBack();
        int from = findCarried(item);
        if (from < 0) return false;
        click(from, 0, ClickType.PICKUP);
        origin = from;
        return menu.getCarried().is(item);
    }

    /** A right click: puts one item of the cursor stack into the slot. */
    void placeOne(int menuSlot) {
        click(menuSlot, 1, ClickType.PICKUP);
        if (menu.getCarried().isEmpty()) origin = -1;
    }

    /** A shift click: the game sends the stack where that slot's menu sends it (inventory, armor slot, hotbar). */
    void moveOut(int menuSlot) {
        click(menuSlot, 0, ClickType.QUICK_MOVE);
    }

    /**
     * Empties the cursor: back to the slot it was picked from, and what does not fit there into the inventory the way
     * closing a menu would, dropping only when the inventory is full too.
     */
    void putBack() {
        if (!menu.getCarried().isEmpty() && origin >= 0) click(origin, 0, ClickType.PICKUP);
        ItemStack carried = menu.getCarried();
        if (!carried.isEmpty()) {
            body.getInventory().placeItemBackInInventory(carried);
            menu.setCarried(ItemStack.EMPTY);
        }
        origin = -1;
    }

    /** Empties {@code count} grid slots starting at menu slot {@code first}: into the inventory, as a close would. */
    void emptyGrid(int first, int count) {
        for (int i = first; i < first + count; i++) {
            Slot slot = menu.getSlot(i);
            if (!slot.hasItem()) continue;
            moveOut(i);
            if (slot.hasItem()) {
                body.getInventory().placeItemBackInInventory(slot.getItem().copy());
                slot.set(ItemStack.EMPTY);
            }
        }
    }
}
