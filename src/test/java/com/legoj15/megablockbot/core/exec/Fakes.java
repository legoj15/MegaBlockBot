package com.legoj15.megablockbot.core.exec;

import com.legoj15.megablockbot.core.goal.Goal;
import com.legoj15.megablockbot.core.plan.NodeSpec;
import com.legoj15.megablockbot.core.plan.Plan;
import com.legoj15.megablockbot.core.plan.PlanOutcome;
import com.legoj15.megablockbot.core.plan.PlanRequest;
import com.legoj15.megablockbot.core.plan.Profile;
import com.legoj15.megablockbot.core.snapshot.Snapshot;
import com.legoj15.megablockbot.core.task.AbstractTask;
import com.legoj15.megablockbot.core.task.Failure;
import com.legoj15.megablockbot.core.task.Task;
import com.legoj15.megablockbot.core.task.TaskState;
import com.legoj15.megablockbot.core.task.TickResult;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;

/**
 * The scripted fakes the executor tests drive: a host with a settable snapshot, queued tasks and queued plan
 * tickets, a fake task whose behaviour a test picks, and a recording publish.
 */
final class Fakes {

    private Fakes() {}

    /** A host whose every answer the test sets in a field. */
    static final class FakeHost implements ExecutorHost<Object> {
        Snapshot snapshot = Snapshot.builder().build();
        long signature;
        long inputFingerprint = 1L;
        boolean dropsRecoverable;

        final Queue<Task<Object>> tasks = new ArrayDeque<>();
        final List<NodeSpec> taskRequests = new ArrayList<>();
        final List<Map<String, String>> taskHints = new ArrayList<>();
        final List<Task<Object>> createdTasks = new ArrayList<>();

        final Queue<PlanTicket> tickets = new ArrayDeque<>();
        final List<PlanRequest> planRequests = new ArrayList<>();

        /** The death records handed to {@code dropsRecoverable}, to check what a death replan was given. */
        final List<Failure.Died> recoverChecks = new ArrayList<>();

        final List<GoalResult> published = new ArrayList<>();
        final List<String> logs = new ArrayList<>();
        /** The throwables handed to {@code log}, to check that a stack trace was not dropped. */
        final List<Throwable> loggedErrors = new ArrayList<>();

        /** When set, the host call of that name throws it, like a broken snapshot collector or planner. */
        RuntimeException snapshotThrows;
        RuntimeException planThrows;

        @Override
        public Snapshot snapshot(Object body, Goal goal) {
            if (snapshotThrows != null) throw snapshotThrows;
            return snapshot;
        }

        @Override
        public long progressSignature(Object body) {
            return signature;
        }

        @Override
        public Task<Object> createTask(NodeSpec leaf, Map<String, String> hints) {
            taskRequests.add(leaf);
            taskHints.add(hints);
            Task<Object> task = tasks.isEmpty() ? new FakeTask() : tasks.poll();
            createdTasks.add(task);
            return task;
        }

        @Override
        public PlanTicket plan(PlanRequest request) {
            if (planThrows != null) throw planThrows;
            planRequests.add(request);
            return tickets.isEmpty() ? new FakeTicket() : tickets.poll();
        }

        @Override
        public long inputFingerprint(Goal goal, Snapshot snapshot) {
            return inputFingerprint;
        }

        @Override
        public boolean dropsRecoverable(Failure.Died death, Snapshot now) {
            recoverChecks.add(death);
            return dropsRecoverable;
        }

        @Override
        public void publish(GoalResult result) {
            published.add(result);
        }

        @Override
        public Profile profile() {
            return Profile.ASSISTED;
        }

        @Override
        public void log(String message, Throwable error) {
            logs.add(message);
            if (error != null) loggedErrors.add(error);
        }

        /** Queues a plan that adopts immediately, for the current input fingerprint. */
        void offerPlan(NodeSpec... roots) {
            tickets.add(FakeTicket.plan(roots));
        }

        void offerPlan(long fingerprint, NodeSpec... roots) {
            tickets.add(new FakeTicket(new PlanOutcome.Planned(
                    new Plan(List.of(roots), fingerprint, 0, List.of())), 1));
        }
    }

    /** A plan ticket that is ready after N polls, or that rethrows for a throwing planner. */
    static class FakeTicket implements PlanTicket {
        final PlanOutcome outcome;
        final int readyAfter;
        Exception throwOnOutcome;
        int polls;
        boolean cancelled;

        FakeTicket() {
            this((PlanOutcome) null, Integer.MAX_VALUE); // never ready: planning hangs
        }

        FakeTicket(PlanOutcome outcome, int readyAfter) {
            this.outcome = outcome;
            this.readyAfter = readyAfter;
        }

        static FakeTicket plan(NodeSpec... roots) {
            return new FakeTicket(new PlanOutcome.Planned(
                    new Plan(List.of(roots), 1L, 0, List.of())), 1);
        }

        static FakeTicket unplannable(Failure failure) {
            return new FakeTicket(new PlanOutcome.Unplannable(failure), 1);
        }

        static FakeTicket throwing(Exception e) {
            FakeTicket t = new FakeTicket(null, 1);
            t.throwOnOutcome = e;
            return t;
        }

        @Override
        public boolean done() {
            polls++;
            return polls >= readyAfter && (outcome != null || throwOnOutcome != null);
        }

        @Override
        public PlanOutcome outcome() throws Exception {
            if (throwOnOutcome != null) throw throwOnOutcome;
            return outcome;
        }

        @Override
        public void cancel() {
            cancelled = true;
        }
    }

    /** A task whose behaviour the test picks; records every hook call. */
    static class FakeTask extends AbstractTask<Object> {
        enum Mode { RUN_FOREVER, COMPLETE, FAIL, THROW_TICK, FAIL_THEN_THROW }

        Mode mode = Mode.RUN_FOREVER;
        Failure failure;
        Map<String, String> checkpoint = Map.of();
        /** When set, {@code checkpoint()} throws it, like a task whose bookkeeping has a bug. */
        RuntimeException checkpointThrows;
        boolean waiting;
        int ticksRun;
        boolean throwOnPause;
        boolean throwOnResume;
        boolean throwOnAbort;
        final List<String> calls = new ArrayList<>();
        /** The body each hook last received, to check the executor hands over the real one. */
        Object pausedWith = "never";
        Object resumedWith = "never";
        Object abortedWith = "never";
        final RuntimeException thrown = new IllegalStateException("boom");

        FakeTask() {}

        static FakeTask completing() {
            FakeTask t = new FakeTask();
            t.mode = Mode.COMPLETE;
            return t;
        }

        static FakeTask failing(Failure failure) {
            FakeTask t = new FakeTask();
            t.mode = Mode.FAIL;
            t.failure = failure;
            return t;
        }

        static FakeTask throwingOnTick() {
            FakeTask t = new FakeTask();
            t.mode = Mode.THROW_TICK;
            return t;
        }

        /** Fails itself with a typed failure and then throws, as a hook with a bug after a clean veto would. */
        static FakeTask failingThenThrowing(Failure failure) {
            FakeTask t = new FakeTask();
            t.mode = Mode.FAIL_THEN_THROW;
            t.failure = failure;
            return t;
        }

        /** The task fails between two ticks, as a callback from an event handler could make it. */
        void failFromOutside(Failure f) {
            fail(f);
        }

        @Override
        protected void onStart(Object body) {
            calls.add("start");
        }

        @Override
        protected TickResult onTick(Object body) {
            calls.add("tick");
            ticksRun++;
            return switch (mode) {
                case COMPLETE -> TickResult.COMPLETED;
                case FAIL -> TickResult.failed(failure);
                case THROW_TICK -> throw thrown;
                case FAIL_THEN_THROW -> {
                    fail(failure);
                    throw thrown;
                }
                case RUN_FOREVER -> TickResult.RUNNING;
            };
        }

        @Override
        protected void onPause(Object body, String reason) {
            calls.add("pause:" + reason);
            pausedWith = body;
            if (throwOnPause) throw thrown;
        }

        @Override
        protected void onResume(Object body) {
            calls.add("resume");
            resumedWith = body;
            if (throwOnResume) throw thrown;
        }

        @Override
        protected void onAbort(Object body, String reason) {
            calls.add("abort:" + reason);
            abortedWith = body;
            if (throwOnAbort) throw thrown;
        }

        @Override
        public Map<String, String> checkpoint() {
            if (checkpointThrows != null) throw checkpointThrows;
            return checkpoint;
        }

        @Override
        public boolean waiting() {
            return waiting;
        }
    }

    /**
     * A task that implements {@link Task} directly, with a state the test sets and an abort counter: for the
     * cases {@link AbstractTask} cannot produce (a task that is COMPLETED between ticks, one left in a state
     * the executor did not expect, one that must not be aborted after it ended itself).
     */
    static final class RawTask implements Task<Object> {
        TaskState state = TaskState.PENDING;
        Failure failure;
        /** The state {@code start} moves the task to; null leaves it where it was. */
        TaskState stateAfterStart = TaskState.RUNNING;
        TickResult tickResult = TickResult.RUNNING;
        int aborts;

        static RawTask completing() {
            RawTask t = new RawTask();
            t.tickResult = TickResult.COMPLETED;
            return t;
        }

        static RawTask failing(Failure failure) {
            RawTask t = new RawTask();
            t.tickResult = TickResult.failed(failure);
            return t;
        }

        @Override
        public TaskState state() {
            return state;
        }

        @Override
        public Failure failure() {
            return failure;
        }

        @Override
        public void start(Object body) {
            if (stateAfterStart != null) state = stateAfterStart;
        }

        @Override
        public TickResult tick(Object body) {
            return tickResult;
        }

        @Override
        public void pause(Object body, String reason) {
            state = TaskState.PAUSED;
        }

        @Override
        public void resume(Object body) {
            state = TaskState.RUNNING;
        }

        @Override
        public void abort(Object body, String reason) {
            aborts++;
            state = TaskState.CANCELLED;
        }
    }
}
