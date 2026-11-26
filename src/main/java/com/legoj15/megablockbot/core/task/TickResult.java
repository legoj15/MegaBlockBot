package com.legoj15.megablockbot.core.task;

/** What one {@link Task#tick} produced. */
public sealed interface TickResult {
    TickResult RUNNING = new Running();
    TickResult COMPLETED = new Completed();

    record Running() implements TickResult {}

    record Completed() implements TickResult {}

    record Failed(Failure failure) implements TickResult {}

    static TickResult failed(Failure failure) {
        return new Failed(failure);
    }
}
