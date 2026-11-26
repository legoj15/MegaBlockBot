package com.legoj15.megablockbot.core.task;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** The guard table in {@link Task}'s javadoc, plus the throwing-hook rule (design 3.4). */
class AbstractTaskTest {

    private static final Object BODY = new Object();

    /** A task whose hooks do nothing; the guards are what is under test. */
    private static final class EmptyTask extends AbstractTask<Object> {
        @Override
        protected TickResult onTick(Object body) {
            return TickResult.RUNNING;
        }
    }

    @Test
    void startFromPendingGoesRunning() {
        EmptyTask task = new EmptyTask();
        task.start(BODY);
        assertEquals(TaskState.RUNNING, task.state());
        assertNull(task.failure());
    }

    @Test
    void tickFromAnotherStateThanRunningChangesNothing() {
        EmptyTask pending = new EmptyTask();
        assertEquals(TickResult.RUNNING, pending.tick(BODY)); // PENDING: a no-op result
        assertEquals(TaskState.PENDING, pending.state());
        EmptyTask running = new EmptyTask();
        running.start(BODY);
        running.pause(BODY, "test");
        assertEquals(TickResult.RUNNING, running.tick(BODY)); // PAUSED
        assertEquals(TaskState.PAUSED, running.state());
    }

    @Test
    void pauseOnlyFromRunning() {
        EmptyTask task = new EmptyTask();
        task.pause(BODY, "test"); // PENDING: no-op
        assertEquals(TaskState.PENDING, task.state());
        task.start(BODY);
        task.pause(BODY, "test");
        assertEquals(TaskState.PAUSED, task.state());
        task.pause(BODY, "again"); // already PAUSED: no-op
        assertEquals(TaskState.PAUSED, task.state());
    }

    @Test
    void resumeOnlyFromPaused() {
        EmptyTask task = new EmptyTask();
        task.resume(BODY); // PENDING: no-op
        assertEquals(TaskState.PENDING, task.state());
        task.start(BODY);
        task.resume(BODY); // RUNNING: no-op
        assertEquals(TaskState.RUNNING, task.state());
        task.pause(BODY, "test");
        task.resume(BODY);
        assertEquals(TaskState.RUNNING, task.state());
    }

    @Test
    void abortFromAnyNonTerminalStateAndNoOpOnTerminal() {
        EmptyTask task = new EmptyTask();
        task.abort(BODY, "test"); // PENDING
        assertEquals(TaskState.CANCELLED, task.state());
        task.abort(BODY, "again"); // terminal: no-op
        assertEquals(TaskState.CANCELLED, task.state());

        EmptyTask paused = new EmptyTask();
        paused.start(BODY);
        paused.pause(BODY, "test");
        paused.abort(BODY, "test");
        assertEquals(TaskState.CANCELLED, paused.state());
    }

    @Test
    void onStartMayFailTheTaskWithATypedFailure() {
        class Veto extends AbstractTask<Object> {
            @Override
            protected void onStart(Object body) {
                fail(new Failure.MissingInput(null, 1, "nothing to work with"));
            }

            @Override
            protected TickResult onTick(Object body) {
                return TickResult.RUNNING;
            }
        }
        Veto task = new Veto();
        task.start(BODY);
        assertEquals(TaskState.FAILED, task.state());
        assertEquals(Failure.Kind.MISSING_INPUT, task.failure().kind());
    }

    @Test
    void aThrowingHookFailsTheTaskWithInternalCarryingTheException() {
        class Boom extends AbstractTask<Object> {
            @Override
            protected TickResult onTick(Object body) {
                throw new IllegalStateException("boom");
            }
        }
        Boom task = new Boom();
        task.start(BODY);
        TickResult result = task.tick(BODY);
        assertEquals(TaskState.FAILED, task.state());
        assertInstanceOf(Failure.Internal.class, task.failure());
        assertInstanceOf(IllegalStateException.class, ((Failure.Internal) task.failure()).exception());
        assertInstanceOf(TickResult.Failed.class, result);
        assertEquals(Failure.Kind.INTERNAL, ((TickResult.Failed) result).failure().kind());
    }

    @Test
    void aThrowingPauseHookFailsTheTaskInsteadOfPausingIt() {
        class Boom extends AbstractTask<Object> {
            @Override
            protected TickResult onTick(Object body) {
                return TickResult.RUNNING;
            }

            @Override
            protected void onPause(Object body, String reason) {
                throw new UnsupportedOperationException("boom");
            }
        }
        Boom task = new Boom();
        task.start(BODY);
        task.pause(BODY, "reflex");
        assertEquals(TaskState.FAILED, task.state());
        assertInstanceOf(Failure.Internal.class, task.failure());
    }

    /** A task whose tick returns what the test hands it, so each result the guard table covers can be driven. */
    private static final class Scripted extends AbstractTask<Object> {
        private final java.util.function.Supplier<TickResult> result;

        Scripted(java.util.function.Supplier<TickResult> result) {
            this.result = result;
        }

        @Override
        protected TickResult onTick(Object body) {
            return result.get();
        }

        void failFromOutside(Failure f) {
            fail(f);
        }
    }

    @Test
    void aTickThatReturnsCompletedSetsTheStateCompleted() {
        Scripted task = new Scripted(() -> TickResult.COMPLETED);
        task.start(BODY);
        assertEquals(TickResult.COMPLETED, task.tick(BODY));
        assertEquals(TaskState.COMPLETED, task.state());
        task.abort(BODY, "late"); // terminal: still a no-op
        assertEquals(TaskState.COMPLETED, task.state());
    }

    @Test
    void aTickThatReturnsFailedSetsTheStateFailedWithThatFailure() {
        Failure unreachable = new Failure.Unreachable("t", "no path", "");
        Scripted task = new Scripted(() -> TickResult.failed(unreachable));
        task.start(BODY);
        assertEquals(TickResult.failed(unreachable), task.tick(BODY));
        assertEquals(TaskState.FAILED, task.state());
        assertSame(unreachable, task.failure());
    }

    @Test
    void aHookThatCalledFailAndThenReturnedRunningYieldsTheFailedResult() {
        Failure missing = new Failure.MissingInput(null, 1, "");
        Scripted[] holder = new Scripted[1];
        holder[0] = new Scripted(() -> {
            holder[0].failFromOutside(missing);
            return TickResult.RUNNING;
        });
        holder[0].start(BODY);
        TickResult result = holder[0].tick(BODY);
        assertEquals(TaskState.FAILED, holder[0].state());
        assertEquals(TickResult.failed(missing), result);
    }

    @Test
    void aNullTickResultFailsTheTaskWithInternal() {
        Scripted task = new Scripted(() -> null);
        task.start(BODY);
        TickResult result = task.tick(BODY);
        assertEquals(TaskState.FAILED, task.state());
        assertEquals(Failure.Kind.INTERNAL, task.failure().kind());
        assertEquals(TickResult.failed(task.failure()), result);
    }

    @Test
    void aFailedResultWithoutAFailureFailsTheTaskWithInternal() {
        Scripted task = new Scripted(() -> new TickResult.Failed(null));
        task.start(BODY);
        TickResult result = task.tick(BODY);
        assertEquals(TaskState.FAILED, task.state());
        assertEquals(Failure.Kind.INTERNAL, task.failure().kind());
        assertEquals(TickResult.failed(task.failure()), result);
    }

    @Test
    void aTickOnAFinishedTaskReportsHowItEndedNotRunning() {
        Scripted done = new Scripted(() -> TickResult.COMPLETED);
        done.start(BODY);
        done.tick(BODY);
        assertEquals(TickResult.COMPLETED, done.tick(BODY)); // not RUNNING, which would keep a caller waiting

        Failure missing = new Failure.MissingInput(null, 1, "");
        Scripted failed = new Scripted(() -> TickResult.RUNNING);
        failed.start(BODY);
        failed.failFromOutside(missing);
        assertEquals(TickResult.failed(missing), failed.tick(BODY));
    }

    /** Throws from the one hook the test names and counts how often onAbort ran, so release can be checked. */
    private static final class Holding extends AbstractTask<Object> {
        private final String throwIn;
        final RuntimeException thrown = new IllegalStateException("boom");
        final RuntimeException abortError = new IllegalArgumentException("abort failed too");
        boolean abortThrows;
        int aborts;

        Holding(String throwIn) {
            this.throwIn = throwIn;
        }

        private void maybeThrow(String hook) {
            if (hook.equals(throwIn)) throw thrown;
        }

        @Override
        protected void onStart(Object body) {
            maybeThrow("onStart");
        }

        @Override
        protected TickResult onTick(Object body) {
            maybeThrow("onTick");
            return TickResult.RUNNING;
        }

        @Override
        protected void onPause(Object body, String reason) {
            maybeThrow("onPause");
        }

        @Override
        protected void onResume(Object body) {
            maybeThrow("onResume");
        }

        @Override
        protected void onAbort(Object body, String reason) {
            aborts++;
            if (abortThrows) throw abortError;
        }

        /** Runs the task as far as the named hook, which then throws. */
        void driveTo(String hook) {
            start(BODY);
            if (hook.equals("onStart")) return;
            if (hook.equals("onTick")) {
                tick(BODY);
                return;
            }
            pause(BODY, "test");
            if (hook.equals("onPause")) return;
            resume(BODY);
        }
    }

    @Test
    void aHookThatThrowsStillLetsTheTaskReleaseWhatItHeldThroughOnAbort() {
        for (String hook : new String[] {"onStart", "onTick", "onPause", "onResume"}) {
            Holding task = new Holding(hook);
            task.driveTo(hook);
            assertEquals(1, task.aborts, hook + " threw: onAbort must run once");
            assertEquals(TaskState.FAILED, task.state(), hook);
            assertInstanceOf(Failure.Internal.class, task.failure(), hook);
            assertSame(task.thrown, ((Failure.Internal) task.failure()).exception(), hook);
            assertSame(task.thrown, task.lastHookError(), hook);
            task.abort(BODY, "the executor retires it later"); // terminal: the hook is not run a second time
            assertEquals(1, task.aborts, hook);
        }
    }

    @Test
    void anOnAbortThatThrowsAfterAHookThrewKeepsTheFirstExceptionAsTheFailureAndAttachesTheSecond() {
        Holding task = new Holding("onTick");
        task.abortThrows = true;
        task.driveTo("onTick");
        assertEquals(TaskState.FAILED, task.state());
        assertSame(task.thrown, ((Failure.Internal) task.failure()).exception());
        assertSame(task.thrown, task.lastHookError());
        assertTrue(java.util.Arrays.asList(task.thrown.getSuppressed()).contains(task.abortError),
                "the cleanup's own exception is not swallowed");
    }

    @Test
    void aTickOnACancelledTaskReportsAFailureNotRunning() {
        EmptyTask task = new EmptyTask();
        task.start(BODY);
        task.abort(BODY, "test");
        TickResult result = task.tick(BODY);
        assertInstanceOf(TickResult.Failed.class, result); // RUNNING would keep a caller waiting for nothing
        assertEquals(Failure.Kind.INTERNAL, ((TickResult.Failed) result).failure().kind());
    }

    @Test
    void anExceptionThrownAfterFailIsKeptAsTheLastHookErrorWhileTheFailureStays() {
        RuntimeException thrown = new IllegalStateException("after the veto");
        Failure veto = new Failure.MissingInput(null, 1, "clean veto");
        class VetoThenThrow extends AbstractTask<Object> {
            @Override
            protected void onStart(Object body) {
                fail(veto);
                throw thrown;
            }

            @Override
            protected TickResult onTick(Object body) {
                return TickResult.RUNNING;
            }
        }
        VetoThenThrow task = new VetoThenThrow();
        assertNull(task.lastHookError());
        task.start(BODY);
        assertEquals(TaskState.FAILED, task.state());
        assertSame(veto, task.failure()); // the typed failure keeps the routing
        assertSame(thrown, task.lastHookError()); // the exception is not lost
    }
}
