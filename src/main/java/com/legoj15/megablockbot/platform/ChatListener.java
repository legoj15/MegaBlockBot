package com.legoj15.megablockbot.platform;

import net.minecraft.server.level.ServerPlayer;

/**
 * Loader-neutral observer of a chat message; see {@link LoaderServices#onChatMessage}. It sees the message the player
 * typed, on the server thread, and cannot cancel or change it: the owner's line stays in chat for everyone to read.
 */
@FunctionalInterface
public interface ChatListener {
    /**
     * @param sender the player who sent it (a body's own chat counts: a body is a player)
     * @param message what they typed, without the loader's decoration
     */
    void chat(ServerPlayer sender, String message);
}
