package com.legoj15.megablockbot.platform;

import java.util.function.Consumer;
import net.minecraft.server.MinecraftServer;

/** Loader identity and server lifecycle. Register calls are plain and Fabric-shaped; NeoForge wires them to its bus. */
public interface LoaderServices {
    /** The loader's name as shown in logs, e.g. "NeoForge". */
    String loaderName();

    /** Runs the listener once each time a server finishes starting. */
    void onServerStarted(Consumer<MinecraftServer> listener);
}
