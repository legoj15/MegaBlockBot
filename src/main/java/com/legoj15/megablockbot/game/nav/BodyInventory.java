package com.legoj15.megablockbot.game.nav;

import com.legoj15.megablockbot.nav.engine.api.entity.IEntityInventory;
import net.minecraft.core.NonNullList;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * The navigation engine's view of a body's own {@link Inventory}. No copy: the engine reads and swaps the player's
 * real stacks, and the slot it selects is the slot the player holds, so tool choice and block placing use what the
 * body actually carries. Vanilla's slot numbers are the engine's (0-8 hotbar, 9-35 main, 40 off hand).
 */
public final class BodyInventory implements IEntityInventory {
    private final Inventory inventory;

    public BodyInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public NonNullList<ItemStack> main() {
        return inventory.items;
    }

    @Override
    public NonNullList<ItemStack> offHand() {
        return inventory.offhand;
    }

    @Override
    public int getSelectedSlot() {
        return inventory.selected;
    }

    @Override
    public void setSelectedSlot(int slot) {
        // A client can only select a hotbar slot. The engine's lookups answer -1 for "none", which must not stick.
        if (Inventory.isHotbarSlot(slot)) inventory.selected = slot;
    }

    @Override
    public ItemStack getItem(int slot) {
        return inventory.getItem(slot);
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        inventory.setItem(slot, stack);
    }

    @Override
    public boolean insertStack(ItemStack stack) {
        return inventory.add(stack);
    }
}
