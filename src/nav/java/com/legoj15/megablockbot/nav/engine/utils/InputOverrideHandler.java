/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.legoj15.megablockbot.nav.engine.utils;

import com.legoj15.megablockbot.nav.engine.Baritone;
import com.legoj15.megablockbot.nav.engine.api.entity.IMovementInputReceiver;
import com.legoj15.megablockbot.nav.engine.api.utils.IInputOverrideHandler;
import com.legoj15.megablockbot.nav.engine.api.utils.input.Input;
import com.legoj15.megablockbot.nav.engine.behavior.Behavior;
import java.util.EnumSet;
import java.util.Set;
import net.minecraft.world.entity.LivingEntity;

public final class InputOverrideHandler extends Behavior implements IInputOverrideHandler {
   private final Set<Input> inputForceStateMap = EnumSet.noneOf(Input.class);
   private final BlockBreakHelper blockBreakHelper;
   private final BlockPlaceHelper blockPlaceHelper;
   private boolean needsUpdate;
   // MegaBlockBot: the path executor's sprint decision, kept here so an input-driven entity receives it as an input
   // (upstream set the entity's sprint flag directly).
   private boolean sprint;

   public InputOverrideHandler(Baritone baritone) {
      super(baritone);
      this.blockBreakHelper = new BlockBreakHelper(baritone.getEntityContext());
      this.blockPlaceHelper = new BlockPlaceHelper(baritone.getEntityContext());
   }

   @Override
   public final synchronized boolean isInputForcedDown(Input input) {
      return input != null && this.inputForceStateMap.contains(input);
   }

   @Override
   public final synchronized void setInputForceState(Input input, boolean forced) {
      if (forced) {
         this.inputForceStateMap.add(input);
      } else {
         this.inputForceStateMap.remove(input);
      }

      this.needsUpdate = true;
   }

   @Override
   public final synchronized void clearAllKeys() {
      this.sprint = false;
      if (!(this.ctx.entity() instanceof IMovementInputReceiver) && this.ctx.entity().isSprinting()) {
         this.ctx.entity().setSprinting(false);
      }

      this.inputForceStateMap.clear();
      this.needsUpdate = true;
   }

   /** The path executor's sprint decision for this tick. Written into the entity unless it takes inputs. */
   public final synchronized void setSprinting(boolean sprint) {
      this.sprint = sprint;
      if (!(this.ctx.entity() instanceof IMovementInputReceiver)) {
         this.ctx.entity().setSprinting(sprint);
      }
   }

   @Override
   public final void onTickServer() {
      if (this.ctx.entity() instanceof IMovementInputReceiver receiver) {
         // MegaBlockBot: an input-driven entity gets the keys every tick, at full strength (1, as a player's keys;
         // upstream's 0.3 is a mob's walking impulse), and nothing is written into the entity.
         if (this.isInputForcedDown(Input.CLICK_LEFT)) {
            this.setInputForceState(Input.CLICK_RIGHT, false);
         }

         float forward = (this.isInputForcedDown(Input.MOVE_FORWARD) ? 1.0F : 0.0F) - (this.isInputForcedDown(Input.MOVE_BACK) ? 1.0F : 0.0F);
         float strafe = (this.isInputForcedDown(Input.MOVE_LEFT) ? 1.0F : 0.0F) - (this.isInputForcedDown(Input.MOVE_RIGHT) ? 1.0F : 0.0F);
         boolean sprinting;
         synchronized (this) {
            sprinting = this.sprint || this.isInputForcedDown(Input.SPRINT);
         }

         receiver.setMovementInput(forward, strafe, this.isInputForcedDown(Input.JUMP), this.isInputForcedDown(Input.SNEAK), sprinting);
         // Every tick, not only after a key changed: a held click must keep breaking, and a released one must abort.
         this.blockBreakHelper.tick(this.isInputForcedDown(Input.CLICK_LEFT));
         this.blockPlaceHelper.tick(this.isInputForcedDown(Input.CLICK_RIGHT));
         this.needsUpdate = false;
         return;
      }

      if (this.needsUpdate) {
         if (this.isInputForcedDown(Input.CLICK_LEFT)) {
            this.setInputForceState(Input.CLICK_RIGHT, false);
         }

         LivingEntity entity = this.ctx.entity();
         entity.xxa = 0.0F;
         entity.zza = 0.0F;
         entity.setShiftKeyDown(false);
         entity.setJumping(this.isInputForcedDown(Input.JUMP));
         float speed = 0.3F;
         if (this.isInputForcedDown(Input.MOVE_FORWARD)) {
            entity.zza += speed;
         }

         if (this.isInputForcedDown(Input.MOVE_BACK)) {
            entity.zza -= speed;
         }

         if (this.isInputForcedDown(Input.MOVE_LEFT)) {
            entity.xxa += speed;
         }

         if (this.isInputForcedDown(Input.MOVE_RIGHT)) {
            entity.xxa -= speed;
         }

         if (this.isInputForcedDown(Input.SNEAK)) {
            entity.setShiftKeyDown(true);
            entity.xxa = (float)(entity.xxa * 0.3);
            entity.zza = (float)(entity.zza * 0.3);
         }

         this.blockBreakHelper.tick(this.isInputForcedDown(Input.CLICK_LEFT));
         this.blockPlaceHelper.tick(this.isInputForcedDown(Input.CLICK_RIGHT));
         this.needsUpdate = false;
      }
   }

   public BlockBreakHelper getBlockBreakHelper() {
      return this.blockBreakHelper;
   }
}
