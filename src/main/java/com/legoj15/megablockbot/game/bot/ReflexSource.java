package com.legoj15.megablockbot.game.bot;

import com.legoj15.megablockbot.core.exec.ReflexHold;
import com.legoj15.megablockbot.game.body.BotPlayer;
import net.minecraft.nbt.CompoundTag;

/**
 * Tells a body's runtime, once per tick, whether the reflex layer owns the body (design 3.2, 3.4 Preemption), and
 * carries the moments and the state of the brain's own life cycle to it: a death ends the hold at once, and the
 * shelter blocks the layer remembers travel with the brain's save. The runtime hands the hold to the executor, which
 * pauses its task for as long as it lasts. Nothing registers one yet: until the reflex layer does, {@link #NONE}
 * says the executor always has the body.
 */
@FunctionalInterface
public interface ReflexSource {
    /** The default: the reflex layer never holds the body, no death is told, no state is kept. */
    ReflexSource NONE = body -> ReflexHold.NONE;

    /** Called on the server thread, once per body tick, before the executor's tick. */
    ReflexHold holdFor(BotPlayer body);

    /**
     * The body died (its brain's {@code onDeath}): the layer drops its hold and the hold's state now, rather than on the
     * replacement's first tick. The default does nothing.
     */
    default void onDeath(BotPlayer body) {}

    /**
     * Writes where the blocks the layer remembers as this body's night shelter stand (dimension and position of each)
     * into {@code out}, for the brain's save: without them a restart forgets which blocks to take down. The default
     * writes nothing.
     */
    default void saveShelterBlocks(BotPlayer body, CompoundTag out) {}

    /** Restores what {@link #saveShelterBlocks} wrote, for the body whose save it was. The default does nothing. */
    default void loadShelterBlocks(BotPlayer body, CompoundTag in) {}
}
