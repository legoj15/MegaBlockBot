// SPDX-License-Identifier: LGPL-3.0-or-later
// Ported from PlayerEngine's mixins/baritone/MixinEntity (see UPSTREAM.md). The getEyeHeight invoker is gone
// (Entity.getEyeHeight(Pose) is public on 1.21.1); the removal hook now also drops the entity's component entries.
package com.legoj15.megablockbot.nav.mixin;

import com.legoj15.megablockbot.nav.engine.api.IBaritone;
import com.legoj15.megablockbot.nav.engine.api.selection.ISelectionManager;
import com.legoj15.megablockbot.nav.engine.api.utils.IInteractionController;
import com.legoj15.megablockbot.nav.engine.behavior.PathingBehavior;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Entity.RemovalReason;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin({Entity.class})
public abstract class MixinEntity {
   @Shadow
   public abstract Level level();

   @Inject(
      method = {"setRemoved"},
      at = {@At("RETURN")}
   )
   private void shutdownPathingOnUnloading(RemovalReason reason, CallbackInfo ci) {
      if (!this.level().isClientSide() && ((Object)this) instanceof LivingEntity living) {
         IBaritone.KEY.maybeGet(living).ifPresent(b -> ((PathingBehavior)b.getPathingBehavior()).shutdown());
         IBaritone.KEY.remove(living);
         IInteractionController.KEY.remove(living);
         ISelectionManager.KEY.remove(living);
      }
   }
}
