package com.legoj15.megablockbot.core.task;

/**
 * Enforces {@link Task}'s guard table (design 3.4, Task) so a subclass cannot get the transitions wrong.
 * The public operations are final; subclasses fill the hooks. A hook that throws fails the task with
 * {@link Failure.Internal} carrying the exception -- never swallowed (design: "A task that throws is failed
 * with INTERNAL and logged"). A call from a state the table does not allow changes nothing.
 *
 * <p>The state machine covers results too: a tick that returns {@code Completed} sets COMPLETED, one that
 * returns {@code Failed} sets FAILED with that failure, one that called {@link #fail} and then returned
 * {@code Running} yields the failed result, and a null result is {@code Failure.Internal}. Whatever the hook
 * returned, the task's state and the result the caller sees agree, so the executor can never be told "running"
 * by a task that has already ended. A tick on a task that was cancelled says so as a failed result.
 *
 * <p>A hook that throws leaves the task holding whatever it had acquired, and nobody calls {@code abort} on a
 * FAILED task, so {@link #onAbort} runs for it before the task settles on FAILED (itself guarded: a second
 * throw is attached to the first, not swallowed and not a replacement for it).
 *
 * <p>Subclasses: {@link #onStart} may veto by calling {@link #fail}; {@link #onTick} drives the work and
 * returns a {@link TickResult}; the other hooks release and re-acquire inputs.
 */
public abstract class AbstractTask<B> implements Task<B> {

    private TaskState state = TaskState.PENDING;
    private Failure failure;
    private Throwable lastHookError;

    @Override
    public final TaskState state() {
        return state;
    }

    @Override
    public final Failure failure() {
        return failure;
    }

    @Override
    public final Throwable lastHookError() {
        return lastHookError;
    }

    @Override
    public final void start(B body) {
        if (state != TaskState.PENDING) return;
        try {
            onStart(body);
        } catch (RuntimeException | Error e) {
            hookThrew("onStart", body, e);
            return;
        }
        // onStart may have failed the task already; otherwise it is now running.
        if (state == TaskState.PENDING) state = TaskState.RUNNING;
    }

    @Override
    public final TickResult tick(B body) {
        // Outside RUNNING nothing is done. A task that already ended says how, so a caller that ticks it anyway
        // is not told "running" forever; a pending or paused one has nothing to report yet.
        if (state != TaskState.RUNNING) return resultOfEndedTask();
        TickResult result;
        try {
            result = onTick(body);
        } catch (RuntimeException | Error e) {
            hookThrew("onTick", body, e);
            return TickResult.failed(failure);
        }
        // The hook may have failed the task itself (fail(...)) and still returned something else: the failure
        // wins, whatever came back.
        if (state == TaskState.FAILED) return TickResult.failed(failure);
        if (result == null) {
            fail(new Failure.Internal(null, "onTick returned no result"));
            return TickResult.failed(failure);
        }
        return switch (result) {
            case TickResult.Running running -> running;
            case TickResult.Completed completed -> {
                state = TaskState.COMPLETED;
                yield completed;
            }
            case TickResult.Failed failed -> {
                fail(failed.failure() != null ? failed.failure()
                        : new Failure.Internal(null, "onTick returned a failure with no content"));
                yield TickResult.failed(failure);
            }
        };
    }

    @Override
    public final void pause(B body, String reason) {
        if (state != TaskState.RUNNING) return;
        state = TaskState.PAUSED;
        try {
            onPause(body, reason);
        } catch (RuntimeException | Error e) {
            hookThrew("onPause", body, e);
        }
    }

    @Override
    public final void resume(B body) {
        if (state != TaskState.PAUSED) return;
        state = TaskState.RUNNING;
        try {
            onResume(body);
        } catch (RuntimeException | Error e) {
            hookThrew("onResume", body, e);
        }
    }

    @Override
    public final void abort(B body, String reason) {
        if (state.terminal()) return;
        try {
            onAbort(body, reason);
        } catch (RuntimeException | Error e) {
            // The cleanup itself broke: nothing is left to run, the task just ends FAILED with the exception.
            lastHookError = e;
            fail(new Failure.Internal(e, "onAbort threw"));
            return;
        }
        // The hook may have failed the task; a failure from abort beats CANCELLED (the exception is not swallowed).
        if (!state.terminal()) state = TaskState.CANCELLED;
    }

    /** Marks the task FAILED. Callable from any hook (typically {@link #onStart}); ignored once terminal. */
    protected final void fail(Failure f) {
        if (state.terminal()) return;
        state = TaskState.FAILED;
        failure = f;
    }

    /**
     * Keeps the exception, lets the task release what it held through {@link #onAbort}, then fails the task with
     * the exception. When the hook had already failed the task with a typed failure, that failure keeps the
     * routing and the exception is still kept for the executor to log.
     */
    private void hookThrew(String hook, B body, Throwable e) {
        lastHookError = e;
        try {
            onAbort(body, hook + " threw");
        } catch (RuntimeException | Error cleanup) {
            // The first exception is the cause and stays the failure; the cleanup's rides along with it.
            if (cleanup != e) e.addSuppressed(cleanup);
        }
        fail(new Failure.Internal(e, hook + " threw"));
    }

    private TickResult resultOfEndedTask() {
        return switch (state) {
            case COMPLETED -> TickResult.COMPLETED;
            case FAILED -> TickResult.failed(failure);
            case CANCELLED -> TickResult.failed(new Failure.Internal(null, "the task was cancelled"));
            default -> TickResult.RUNNING;
        };
    }

    protected void onStart(B body) {}

    protected abstract TickResult onTick(B body);

    protected void onPause(B body, String reason) {}

    protected void onResume(B body) {}

    protected void onAbort(B body, String reason) {}
}
