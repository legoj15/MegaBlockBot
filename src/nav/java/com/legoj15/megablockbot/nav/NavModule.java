// SPDX-License-Identifier: LGPL-3.0-or-later
package com.legoj15.megablockbot.nav;

//? if neoforge {
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entrypoint of the navigation module: its own mod ({@code megablockbot_nav}) and its own jar, nested in the main
 * jar, under the LGPL-3.0-or-later rather than the MIT licence of the rest of the mod. The main mod depends on it
 * and calls into it; this module never calls back into {@code com.legoj15.megablockbot}.
 */
@Mod(NavModule.MOD_ID)
public final class NavModule {
    public static final String MOD_ID = "megablockbot_nav";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public NavModule() {
        LOGGER.info("{} loaded", MOD_ID);
    }
}
//?}
