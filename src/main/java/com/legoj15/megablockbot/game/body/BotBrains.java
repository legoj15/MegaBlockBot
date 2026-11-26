package com.legoj15.megablockbot.game.body;

import java.util.function.Supplier;

/**
 * Where the executor tells the body layer how to make a brain. Every new body (and the replacement for a brain whose
 * save would not load) gets {@code factory().get()}. Until the executor registers one, bodies get {@link BotBrain#NONE}.
 */
public final class BotBrains {
    private static volatile Supplier<BotBrain> factory = () -> BotBrain.NONE;

    private BotBrains() {}

    public static Supplier<BotBrain> factory() {
        return factory;
    }

    public static void setFactory(Supplier<BotBrain> newFactory) {
        factory = java.util.Objects.requireNonNull(newFactory);
    }
}
