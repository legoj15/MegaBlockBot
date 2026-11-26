package com.legoj15.megablockbot.platform;

import com.mojang.brigadier.CommandDispatcher;
import java.nio.file.Path;
import java.util.function.Consumer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;

/**
 * Loader identity, config location and the hooks bot code needs. Every hook is a plain register call made during
 * init and is Fabric-shaped; NeoForge wires each to its bus. Listeners run on the server thread, except the command
 * registration one (below).
 */
public interface LoaderServices {
    /** The loader's name as shown in logs, e.g. "NeoForge". */
    String loaderName();

    /** The loader's config directory. */
    Path configDir();

    /** Runs the listener once each time a server finishes starting. */
    void onServerStarted(Consumer<MinecraftServer> listener);

    /** Runs the listener when a server begins stopping, while its levels and players are still there. */
    void onServerStopping(Consumer<MinecraftServer> listener);

    /** Runs the listener at the end of every server tick. */
    void onServerTick(Consumer<MinecraftServer> listener);

    /**
     * Runs the listener whenever the server builds its command tree (at start and on every {@code /reload}), with the
     * dispatcher to add commands to. This happens while data loads, on a worker thread, not the server thread.
     */
    void onRegisterCommands(Consumer<CommandDispatcher<CommandSourceStack>> listener);

    /**
     * Runs the listener once the server's data (tags, recipes, loot tables, villager trades) is loaded: once at server
     * start and once after every {@code /reload}. By then the loader's own data hooks have run, so the villager trade
     * lists are the rebuilt ones.
     */
    void onServerDataLoaded(Consumer<MinecraftServer> listener);

    /**
     * Runs the listener after a player breaks a block. Server side only; it observes and cannot cancel. Only player
     * breaks are reported (explosions, pistons and fluids are not), and a break another handler cancelled is not.
     */
    void onBlockBroken(BlockChangeListener listener);

    /** Runs the listener after an entity places a block. Server side only; it observes and cannot cancel. */
    void onBlockPlaced(BlockChangeListener listener);

    /**
     * Registers the vanilla {@code @GameTest} methods of the given classes. A no-op unless GameTests are enabled, so it
     * is safe to call on every start.
     */
    void registerGameTests(Class<?>... holders);
}
