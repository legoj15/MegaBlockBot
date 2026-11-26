package com.legoj15.megablockbot.game.body;

/**
 * Every tunable of the body, in one place so a later config pass can find them. Nothing here is read from a config
 * file yet.
 */
public final class BodyConstants {
    private BodyConstants() {}

    /** How long a body that nobody needs stays logged in before the manager despawns it (design 3.1). */
    public static final int IDLE_GRACE_TICKS = 600;

    /**
     * The same, for a body with no owner (spawned from the console). There is no owner to be near it, so the ordinary
     * grace would despawn it 30 seconds after it was made; whoever spawned it from the console wants it to stay.
     */
    public static final int OWNERLESS_IDLE_GRACE_TICKS = 6000;

    /** At most this many bodies may belong to one source below {@link #PRIVILEGED_PERMISSION_LEVEL}. */
    public static final int MAX_BODIES_PER_OWNER = 2;

    /** From this permission level (ops, command blocks, the console) the spawn limits of {@link #MAX_BODIES_PER_OWNER} do not apply. */
    public static final int PRIVILEGED_PERMISSION_LEVEL = 2;

    /** A pending respawn that throws this many times is given up on and its body despawned. */
    public static final int MAX_RESPAWN_FAILURES = 3;

    /** A body loads chunks like a player; this keeps its ring small. It is the view distance the body reports. */
    public static final int VIEW_DISTANCE = 6;

    /** The owner counts as "near" inside this many blocks, in the same dimension. */
    public static final int OWNER_NEAR_BLOCKS = 128;

    /** How often a body refreshes its chunk tickets and its connection's last-good position (Carpet's cadence). */
    public static final int CHUNK_REFRESH_TICKS = 10;

    /** How often the manager evaluates the idle policy. The idle counter advances by this much per evaluation. */
    public static final int IDLE_CHECK_INTERVAL_TICKS = 20;

    /** How often the body record is brought up to date for bodies that are logged in. */
    public static final int RECORD_REFRESH_TICKS = 200;

    /** Longest a body may be asked to hold a test input with {@code /aiplayer input}. */
    public static final int MAX_HELD_INPUT_TICKS = 6000;
}
