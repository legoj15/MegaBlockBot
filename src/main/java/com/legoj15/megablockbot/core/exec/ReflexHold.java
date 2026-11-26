package com.legoj15.megablockbot.core.exec;

/**
 * What the reflex layer tells the executor this tick (design 3.4, Preemption): whether it owns the body and
 * why. {@code held} is true for as long as the resume gate is shut.
 */
public record ReflexHold(boolean held, String cause) {

    public static final ReflexHold NONE = new ReflexHold(false, "");

    public ReflexHold {
        cause = cause == null ? "" : cause;
    }
}
