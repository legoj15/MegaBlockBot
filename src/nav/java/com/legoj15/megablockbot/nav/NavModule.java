// SPDX-License-Identifier: LGPL-3.0-or-later
package com.legoj15.megablockbot.nav;

//? if neoforge {
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/**
 * Entrypoint of the navigation module: its own mod ({@code megablockbot_nav}) and its own jar, nested in the main
 * jar, under the LGPL-3.0-or-later rather than the MIT licence of the rest of the mod. The main mod depends on it
 * and calls into it; this module never calls back into {@code com.legoj15.megablockbot}.
 *
 * <p>The only loader code of the module. It hands the engine its directories ({@link NavRuntime}) and stops the
 * path-search pool when a server stops. It registers no tick event: whoever owns a driven entity calls
 * {@code IBaritone.serverTick()} once per server tick, before the entity's own movement step.
 */
@Mod(NavModule.MOD_ID)
public final class NavModule {
    public static final String MOD_ID = NavRuntime.MOD_ID;

    public NavModule() {
        NavRuntime.setGameDir(FMLPaths.GAMEDIR.get());
        NavRuntime.setConfigDir(FMLPaths.CONFIGDIR.get().resolve(MOD_ID));
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent event) -> NavRuntime.shutdownExecutor());
        NavRuntime.LOGGER.info("{} loaded", MOD_ID);
    }
}
//?}
