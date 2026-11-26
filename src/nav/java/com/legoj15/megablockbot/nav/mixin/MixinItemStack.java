// SPDX-License-Identifier: LGPL-3.0-or-later
// Ported from PlayerEngine's mixins/baritone/MixinItemStack (see UPSTREAM.md). Unchanged apart from the package.
package com.legoj15.megablockbot.nav.mixin;

import com.legoj15.megablockbot.nav.engine.api.utils.accessor.IItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin({ItemStack.class})
public abstract class MixinItemStack implements IItemStack {
   @Shadow
   @Final
   private Item item;
   @Unique
   private int baritoneHash;

   @Shadow
   public abstract int getDamageValue();

   private void recalculateHash() {
      this.baritoneHash = this.item == null ? -1 : this.item.hashCode() + this.getDamageValue();
   }

   @Inject(
      method = {"<init>*"},
      at = {@At("RETURN")}
   )
   private void onInit(CallbackInfo ci) {
      this.recalculateHash();
   }

   @Inject(
      method = {"setDamageValue"},
      at = {@At("TAIL")}
   )
   private void onItemDamageSet(CallbackInfo ci) {
      this.recalculateHash();
   }

   @Override
   public int getBaritoneHash() {
      return this.baritoneHash;
   }
}
