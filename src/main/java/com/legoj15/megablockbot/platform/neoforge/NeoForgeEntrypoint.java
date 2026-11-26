package com.legoj15.megablockbot.platform.neoforge;

//? if neoforge {
import com.legoj15.megablockbot.MegaBlockBot;
import com.legoj15.megablockbot.platform.Platform;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

/**
 * NeoForge's {@code @Mod} entrypoint. Gives the mod event bus to the loader services, which need it for mod-bus events,
 * then hands to the loader-neutral {@link MegaBlockBot#init()}.
 */
@Mod(MegaBlockBot.MOD_ID)
public final class NeoForgeEntrypoint {
    public NeoForgeEntrypoint(IEventBus modBus) {
        ((NeoForgeLoaderServices) Platform.loader()).bindModBus(modBus);
        MegaBlockBot.init();
    }
}
//?}
