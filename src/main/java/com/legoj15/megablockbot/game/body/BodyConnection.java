package com.legoj15.megablockbot.game.body;

import io.netty.channel.embedded.EmbeddedChannel;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.PacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.Packet;

/**
 * The network connection of a body: it looks connected and swallows everything.
 *
 * <p>It does own a channel, an {@code EmbeddedChannel} (see {@link #create()}): NeoForge reads per-connection
 * attributes off {@code Connection.channel()} for every player it handles (the payload setup, the connection type,
 * the data maps the client knows, all in {@code placeNewPlayer} through {@code OnDatapackSyncEvent}), and a
 * connection with no channel throws there. Nothing is ever written to that channel, though: Carpet leaves
 * {@code Connection.send} live, so anything sent straight to its connection queues in an {@code EmbeddedChannel}
 * that nothing reads. Here every send, the per-tick flush and the queue of work for "when connected" are overridden,
 * so nothing sent to a body is kept anywhere. The channel is only a holder for those attributes.
 */
final class BodyConnection extends Connection {
    private static final SocketAddress LOOPBACK = new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);

    private BodyConnection() {
        super(PacketFlow.SERVERBOUND);
    }

    /**
     * Registering the connection as the handler of an embedded channel is what makes the channel active, which
     * is what stores it in the connection ({@code Connection.channelActive}); no reflection or accessor needed.
     */
    static BodyConnection create() {
        BodyConnection connection = new BodyConnection();
        new EmbeddedChannel(connection);
        return connection;
    }

    @Override
    public void send(Packet<?> packet) {}

    @Override
    public void send(Packet<?> packet, @Nullable PacketSendListener listener) {
        if (listener != null) listener.onSuccess();
    }

    @Override
    public void send(Packet<?> packet, @Nullable PacketSendListener listener, boolean flush) {
        if (listener != null) listener.onSuccess();
    }

    /** {@code MinecraftServer} flushes every player's connection every tick. */
    @Override
    public void flushChannel() {}

    @Override
    public void runOnceConnected(Consumer<Connection> action) {}

    @Override
    public void tick() {}

    /** Loopback, so {@code ServerPlayer.getIpAddress()} answers 127.0.0.1 instead of failing on a null address. */
    @Override
    public SocketAddress getRemoteAddress() {
        return LOOPBACK;
    }

    // Protocol and lifecycle setup that vanilla runs against a pipeline of packet codecs, which this channel does not have.
    @Override
    public <T extends PacketListener> void setupInboundProtocol(ProtocolInfo<T> protocol, T listener) {}

    @Override
    public void setupOutboundProtocol(ProtocolInfo<?> protocol) {}

    @Override
    public void setListenerForServerboundHandshake(PacketListener listener) {}

    @Override
    public void setReadOnly() {}

    @Override
    public void handleDisconnection() {}

    // The body's own listener turns a kick or a shutdown into a normal logout (BodyListener.disconnect); a
    // disconnect that reaches the connection itself has nothing to close.
    @Override
    public void disconnect(Component reason) {}

    @Override
    public void disconnect(DisconnectionDetails details) {}
}
