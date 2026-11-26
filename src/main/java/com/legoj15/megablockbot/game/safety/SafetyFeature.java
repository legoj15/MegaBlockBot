package com.legoj15.megablockbot.game.safety;

/**
 * Block safety (design 3.5): what the bot must never break or take from. Registers its hooks, GameTests and
 * {@code /aiplayer} sub-commands. Called once from {@code GameBootstrap}.
 */
public final class SafetyFeature {
    private SafetyFeature() {}

    public static void init() {
    }
}
