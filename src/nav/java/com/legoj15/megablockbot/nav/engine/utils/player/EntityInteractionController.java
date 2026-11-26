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

package com.legoj15.megablockbot.nav.engine.utils.player;

import com.legoj15.megablockbot.nav.engine.Baritone;
import com.legoj15.megablockbot.nav.engine.api.IBaritone;
import com.legoj15.megablockbot.nav.engine.api.entity.IInteractionManager;
import com.legoj15.megablockbot.nav.engine.api.entity.IInteractionManagerProvider;
import com.legoj15.megablockbot.nav.engine.api.utils.IInteractionController;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

// MegaBlockBot: every method now copes with an entity that has no interaction manager (upstream dereferenced null):
// it breaks nothing and places nothing, and Movement.prepared fails the movement instead. clickBlock also refuses
// blocks the owning engine's break filter forbids. See UPSTREAM.md.
public class EntityInteractionController implements IInteractionController {
   private final LivingEntity player;
   private int sequence;

   public EntityInteractionController(LivingEntity player) {
      this.player = player;
   }

   @Override
   public boolean hasBrokenBlock() {
      IInteractionManager interactionManager = this.getInteractionManager();
      return interactionManager != null && interactionManager.hasBrokenBlock();
   }

   @Override
   public boolean onPlayerDamageBlock(BlockPos pos, Direction side) {
      IInteractionManager interactionManager = this.getInteractionManager();
      if (interactionManager != null && interactionManager.isMining()) {
         interactionManager.continueBreaking(pos, side);
         if (!interactionManager.isMining()) {
            return true;
         }

         int progress = interactionManager.getBlockBreakingProgress();
         if (progress >= 10) {
            interactionManager.processBlockBreakingAction(
               interactionManager.getMiningPos(), Action.STOP_DESTROY_BLOCK, side, this.player.level().getMaxBuildHeight(), this.sequence++
            );
         }

         return true;
      } else {
         return false;
      }
   }

   @Override
   public void resetBlockRemoving() {
      IInteractionManager interactionManager = this.getInteractionManager();
      if (interactionManager != null && interactionManager.isMining()) {
         interactionManager.processBlockBreakingAction(
            interactionManager.getMiningPos(), Action.ABORT_DESTROY_BLOCK, Direction.UP, this.player.level().getMaxBuildHeight(), this.sequence++
         );
      }
   }

   @Override
   public GameType getGameType() {
      return GameType.SURVIVAL;
   }

   @Override
   public InteractionResult processRightClickBlock(LivingEntity player, Level world, InteractionHand hand, BlockHitResult result) {
      IInteractionManager interactionManager = this.getInteractionManager();
      return interactionManager == null
         ? InteractionResult.FAIL
         : interactionManager.interactBlock(this.player, this.player.level(), this.player.getItemInHand(hand), hand, result);
   }

   @Override
   public InteractionResult processRightClick(LivingEntity player, Level world, InteractionHand hand) {
      IInteractionManager interactionManager = this.getInteractionManager();
      return interactionManager == null
         ? InteractionResult.FAIL
         : interactionManager.interactItem(this.player, this.player.level(), this.player.getItemInHand(hand), hand);
   }

   @Override
   public boolean clickBlock(BlockPos loc, Direction face) {
      IInteractionManager interactionManager = this.getInteractionManager();
      BlockState state = this.player.level().getBlockState(loc);
      if (interactionManager == null || state.isAir() || !mayBreak(this.player, loc, state)) {
         return false;
      } else {
         interactionManager.processBlockBreakingAction(loc, Action.START_DESTROY_BLOCK, face, this.player.level().getMaxBuildHeight(), this.sequence++);
         return interactionManager.isMining() || this.player.level().isEmptyBlock(loc);
      }
   }

   @Nullable
   public IInteractionManager getInteractionManager() {
      return this.player instanceof IInteractionManagerProvider managerProvider ? managerProvider.getInteractionManager() : null;
   }

   /** Whether the engine driving {@code entity}, if any, lets it break this block (its break filter). */
   public static boolean mayBreak(LivingEntity entity, BlockPos pos, BlockState state) {
      IBaritone baritone = IBaritone.KEY.getNullable(entity);
      return !(baritone instanceof Baritone b) || b.mayBreak(entity.level(), pos, state);
   }

   @Override
   public void setHittingBlock(boolean hittingBlock) {
   }

   @Override
   public double getBlockReachDistance() {
      return 4.5;
   }
}
