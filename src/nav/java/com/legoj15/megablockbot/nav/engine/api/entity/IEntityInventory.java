// SPDX-License-Identifier: LGPL-3.0-or-later
// MegaBlockBot addition to the ported engine (see UPSTREAM.md). Upstream handed the engine its own concrete
// LivingEntityInventory; a player body already has a vanilla Inventory, so the engine now reads inventories through
// this interface. Slot numbering is LivingEntityInventory's (and vanilla Inventory's): 0-8 hotbar, 9-35 the rest of
// the main inventory, 40 the off hand.
package com.legoj15.megablockbot.nav.engine.api.entity;

import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;

public interface IEntityInventory {
   /** The 36 main slots, hotbar first. The engine reads it and swaps stacks through {@link #setItem}. */
   NonNullList<ItemStack> main();

   /** The single off-hand slot. */
   NonNullList<ItemStack> offHand();

   /** The selected hotbar slot, 0 to 8: what the entity holds in its main hand. */
   int getSelectedSlot();

   void setSelectedSlot(int slot);

   ItemStack getItem(int slot);

   void setItem(int slot, ItemStack stack);

   /** Puts the stack wherever it fits, shrinking it by what was taken. True when anything was taken. */
   boolean insertStack(ItemStack stack);
}
