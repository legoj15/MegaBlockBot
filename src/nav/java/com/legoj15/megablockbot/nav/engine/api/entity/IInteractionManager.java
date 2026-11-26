// SPDX-License-Identifier: LGPL-3.0-or-later
// MegaBlockBot addition to the ported engine (see UPSTREAM.md). Upstream returned its own
// LivingEntityInteractionManager, which breaks and places blocks by itself; a player body must do both through its
// own game mode instead, so the engine's interaction controller talks to this interface. LivingEntityInteractionManager
// stays the implementation for entities that are not players.
package com.legoj15.megablockbot.nav.engine.api.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;

public interface IInteractionManager {
   /** START, STOP or ABORT a block break, the three actions a client sends. */
   void processBlockBreakingAction(BlockPos pos, Action action, Direction direction, int worldHeight, int sequence);

   /**
    * Called once per tick while the engine keeps hitting {@code pos}, before {@link #getBlockBreakingProgress()} is
    * read. A client adds the tick's destroy progress here; the upstream manager advances in its own update instead.
    */
   default void continueBreaking(BlockPos pos, Direction side) {
   }

   boolean isMining();

   BlockPos getMiningPos();

   /** Break progress in tenths: the engine sends STOP once this reaches 10. */
   int getBlockBreakingProgress();

   boolean hasBrokenBlock();

   InteractionResult interactBlock(LivingEntity player, Level world, ItemStack stack, InteractionHand hand, BlockHitResult hitResult);

   InteractionResult interactItem(LivingEntity player, Level world, ItemStack stack, InteractionHand hand);
}
