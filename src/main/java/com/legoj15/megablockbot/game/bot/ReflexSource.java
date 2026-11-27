package com.legoj15.megablockbot.game.bot;

import com.legoj15.megablockbot.core.exec.ReflexHold;
import com.legoj15.megablockbot.game.body.BotPlayer;

/**
 * Tells a body's runtime, once per tick, whether the reflex layer owns the body (design 3.2, 3.4 Preemption). The
 * runtime hands the answer to the executor, which pauses its task for as long as it is held. Nothing registers one
 * yet: until the reflex layer does, {@link #NONE} says the executor always has the body.
 */
@FunctionalInterface
public interface ReflexSource {
    /** The default: the reflex layer never holds the body. */
    ReflexSource NONE = body -> ReflexHold.NONE;

    /** Called on the server thread, once per body tick, before the executor's tick. */
    ReflexHold holdFor(BotPlayer body);
}
