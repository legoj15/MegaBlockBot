package com.legoj15.megablockbot.game.safety;

/**
 * Every tunable and every rule name of block safety (design 3.5), in one place so a later config pass can find them.
 * Nothing here is read from a config file yet.
 */
public final class SafetyConstants {
    private SafetyConstants() {}

    /**
     * How far, horizontally, from the owner's respawn point and from the overworld's world spawn a body may not break
     * anything. A square (|dx| and |dz| both at most this), not a circle: it needs no multiplication in a path search
     * and never protects less than the circle of the same radius. Height is ignored: a bed's protection reaches the
     * whole column.
     */
    public static final int PROTECTED_RADIUS = 16;

    /** How often, in ticks, the server thread republishes the protected zones that other threads read. */
    public static final int ZONE_REFRESH_TICKS = 10;

    // ---------------------------------------------------------------------------------------------------------
    // Rule names: what a Verdict carries and what Failure.Protected reports. "" means allowed.
    // ---------------------------------------------------------------------------------------------------------

    public static final String RULE_NONE = "";
    public static final String RULE_PROTECTED_RADIUS = "protected_radius";
    public static final String RULE_CLAIM = "claim";
    public static final String RULE_PLAYER_PLACED = "player_placed";
    public static final String RULE_CONTAINER = "container";
    public static final String RULE_BED = "bed";
    public static final String RULE_WORKSTATION = "workstation";
}
