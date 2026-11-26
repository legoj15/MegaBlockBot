package com.legoj15.megablockbot.game.safety;

/**
 * An open exception for one goal (see {@link BlockSafety#allow}): until it is closed, the positions it covers pass the
 * player-placed and container rules for the body that opened it, and no other rule. The protected radius, claims, beds
 * and villager workstations are never relaxed. Use it in a try-with-resources, or close it when the goal ends
 * ({@link BlockSafety#closeAll} closes everything a body opened); one left open ends anyway when its body dies, despawns
 * or is replaced.
 */
public interface Allowance extends AutoCloseable {
    /** Ends the allowance. Any thread; closing twice, or after it already ended, is harmless. */
    @Override
    void close();
}
