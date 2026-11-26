// SPDX-License-Identifier: LGPL-3.0-or-later
// Replaces the PlayerEngine helper of the same name, which ignored its enchantment argument and
// always returned the Efficiency level (see UPSTREAM.md).
package com.legoj15.megablockbot.nav.engine.utils;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;

public final class EnchantmentUtils {
   private EnchantmentUtils() {
   }

   /**
    * The level of the given enchantment on the stack, 0 when absent. Reads the stack's enchantment component by key,
    * so it needs no registry access and works off the server thread.
    */
   public static int getEnchantmentLevel(ItemStack stack, ResourceKey<Enchantment> enchantment) {
      if (stack == null || stack.isEmpty()) {
         return 0;
      }
      ItemEnchantments enchantments = stack.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
      for (Object2IntMap.Entry<Holder<Enchantment>> entry : enchantments.entrySet()) {
         if (entry.getKey().is(enchantment)) {
            return entry.getIntValue();
         }
      }
      return 0;
   }
}
