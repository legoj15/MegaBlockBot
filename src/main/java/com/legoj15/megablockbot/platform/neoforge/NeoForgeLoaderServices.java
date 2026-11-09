package com.legoj15.megablockbot.platform.neoforge;

//? if neoforge {
import com.legoj15.megablockbot.platform.LoaderServices;
import java.util.function.Consumer;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

public final class NeoForgeLoaderServices implements LoaderServices {
    @Override
    public String loaderName() {
        return "NeoForge";
    }

    @Override
    public void onServerStarted(Consumer<MinecraftServer> listener) {
        NeoForge.EVENT_BUS.addListener(ServerStartedEvent.class, event -> listener.accept(event.getServer()));
    }
}
//?}
