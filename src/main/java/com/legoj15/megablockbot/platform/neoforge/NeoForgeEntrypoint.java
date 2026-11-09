package com.legoj15.megablockbot.platform.neoforge;

//? if neoforge {
import com.legoj15.megablockbot.MegaBlockBot;
import net.neoforged.fml.common.Mod;

/** NeoForge's {@code @Mod} entrypoint. Hands straight to the loader-neutral {@link MegaBlockBot#init()}. */
@Mod(MegaBlockBot.MOD_ID)
public final class NeoForgeEntrypoint {
    public NeoForgeEntrypoint() {
        MegaBlockBot.init();
    }
}
//?}
