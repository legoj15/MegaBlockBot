package com.legoj15.megablockbot.game.body;

import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.RelativeMovement;

/**
 * The packet listener of a body, put in place of vanilla's by a mixin on {@code PlayerList.placeNewPlayer}. It is
 * never ticked and never receives a packet; what it is for is the other direction: everything the server sends to a
 * player (join packets, chunks, health, chat, a mod's payload sent "to all players") ends here and is dropped.
 */
public final class BodyListener extends ServerGamePacketListenerImpl {
    public BodyListener(MinecraftServer server, Connection connection, BotPlayer body, CommonListenerCookie cookie) {
        super(server, connection, body, cookie);
    }

    /**
     * Drops the packet. Vanilla's version first asks NeoForge whether the client supports the payload, which for a
     * client that does not exist would throw, so the check is skipped along with the send. {@code send(Packet)}
     * delegates here.
     */
    @Override
    public void send(Packet<?> packet, @Nullable PacketSendListener listener) {
        if (listener != null) listener.onSuccess();
    }

    /**
     * A kick, a duplicate login or the server's shutdown. Vanilla would send a disconnect packet, close the channel
     * and let the connection report the disconnect back; this connection has neither, so the logout is done directly,
     * through the same {@code onDisconnect} a real player's connection ends in. That is what saves the body. Except in
     * the server's final stop, where the manager asks for the body to stay listed
     * ({@link BodyManager#leavesLoggedInAtShutdown}).
     */
    @Override
    public void disconnect(DisconnectionDetails details) {
        if (!player.hasDisconnected()) {
            server.executeBlocking(() -> {
                if (player.hasDisconnected()) return;
                // Asked without creating one: a manager made here for a server that has stopped would never be dropped.
                BodyManager manager = BodyManager.existing(server);
                if (player instanceof BotPlayer body && manager != null && manager.leavesLoggedInAtShutdown(body)) return;
                onDisconnect(details);
            });
        }
    }

    /**
     * Vanilla's teleport, plus what the connection tick would have done for a real player: record the position as
     * good and refresh the chunk tickets. Only for a body already in the level, or its chunk map has no entry for it.
     */
    @Override
    public void teleport(double x, double y, double z, float yaw, float pitch, Set<RelativeMovement> relatives) {
        super.teleport(x, y, z, yaw, pitch, relatives);
        ServerPlayer body = this.player;
        if (body.serverLevel().getPlayerByUUID(body.getUUID()) != null) {
            resetPosition();
            body.serverLevel().getChunkSource().move(body);
        }
    }
}
