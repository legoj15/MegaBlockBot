package com.legoj15.megablockbot.game.bot;

import java.util.Objects;

/** The reflex source every runtime asks. The reflex layer replaces the default with its own from its {@code init()}. */
public final class ReflexSources {
    private static volatile ReflexSource current = ReflexSource.NONE;

    private ReflexSources() {}

    public static ReflexSource current() {
        return current;
    }

    public static void set(ReflexSource source) {
        current = Objects.requireNonNull(source, "source");
    }
}
