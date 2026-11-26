package com.legoj15.megablockbot.game.mixin;

import com.legoj15.megablockbot.game.body.BotPlayer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.SleepStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(SleepStatus.class)
abstract class SleepStatusBodyMixin {
    /**
     * Without it every awake body counts as an active player in the sleep percentage, so a body that never sleeps
     * stops the night from being skipped (one sleeper of two active players is 50 percent, not enough at 100).
     * Spectators are the players vanilla leaves out of the count; an awake body is treated as one for this purpose
     * only. A body that is asleep still counts, as a sleeper and as an active player.
     */
    @Redirect(method = "update", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;isSpectator()Z"))
    private boolean megablockbot$awakeBodyIsNotActive(ServerPlayer player) {
        return player.isSpectator() || (player instanceof BotPlayer && !player.isSleeping());
    }
}
