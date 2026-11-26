package com.legoj15.megablockbot.game.mixin;

import com.legoj15.megablockbot.game.body.BodyListener;
import com.legoj15.megablockbot.game.body.BotPlayer;
import com.mojang.authlib.GameProfile;
import net.minecraft.network.Connection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(PlayerList.class)
abstract class PlayerListBodyMixin {
    /**
     * Without it a body is placed with vanilla's packet listener: the join packets and everything after go to a
     * connection with no channel and throw, and the listener is not the one that logs the body out when kicked.
     * {@code placeNewPlayer} constructs the listener itself, so only a redirect of that {@code new} reaches it.
     */
    @Redirect(method = "placeNewPlayer", at = @At(value = "NEW",
        target = "(Lnet/minecraft/server/MinecraftServer;Lnet/minecraft/network/Connection;Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/server/network/CommonListenerCookie;)Lnet/minecraft/server/network/ServerGamePacketListenerImpl;"))
    private ServerGamePacketListenerImpl megablockbot$listenerFor(MinecraftServer server, Connection connection, ServerPlayer player, CommonListenerCookie cookie) {
        if (player instanceof BotPlayer body) return new BodyListener(server, connection, body, cookie);
        return new ServerGamePacketListenerImpl(server, connection, player, cookie);
    }

    /**
     * Without it a body that dies is replaced by a plain {@code ServerPlayer}: no brain, no owner, no input, and
     * from then on a player with no client that nothing ticks. {@code respawn} constructs the replacement itself.
     */
    @Redirect(method = "respawn", at = @At(value = "NEW",
        target = "(Lnet/minecraft/server/MinecraftServer;Lnet/minecraft/server/level/ServerLevel;Lcom/mojang/authlib/GameProfile;Lnet/minecraft/server/level/ClientInformation;)Lnet/minecraft/server/level/ServerPlayer;"))
    private ServerPlayer megablockbot$replacementFor(MinecraftServer server, ServerLevel level, GameProfile profile, ClientInformation info, ServerPlayer old) {
        if (old instanceof BotPlayer) return new BotPlayer(server, level, profile, info);
        return new ServerPlayer(server, level, profile, info);
    }
}
