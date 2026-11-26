package com.legoj15.megablockbot.core.task;

import java.util.Map;

/**
 * A resumable state machine bound to one leaf of the goal tree (design 3.4, Task). {@code B} is the body type;
 * in the game it is the bot's player entity, in tests a fake.
 *
 * <p>Guarded transitions; an operation called from another state is a no-op that leaves the state alone:
 * <ul>
 *   <li>{@link #start} from PENDING: RUNNING, or FAILED with a typed failure.</li>
 *   <li>{@link #tick} from RUNNING.</li>
 *   <li>{@link #pause} from RUNNING: PAUSED; releases movement inputs and any held use or break.</li>
 *   <li>{@link #resume} from PAUSED: RUNNING; re-acquires its targets from the world. Saved fields are hints
 *       for where to look, never facts.</li>
 *   <li>{@link #abort} from any non-terminal state: CANCELLED; releases inputs.</li>
 * </ul>
 * A task never moves the body directly: movement goes through navigation as inputs. A task that throws is
 * failed with {@link Failure.Internal} by the caller, and is let go of the way an aborted one is: whatever it
 * held is released, because a FAILED task is never aborted afterwards ({@link AbstractTask} does this for a
 * throwing hook). {@code Completed} is a hint; the executor marks the node SATISFIED only when its postcondition
 * holds. Extend {@link AbstractTask} rather than implementing this.
 */
public interface Task<B> {
    TaskState state();

    /** The failure that ended it, or null unless {@link #state()} is FAILED. */
    Failure failure();

    void start(B body);

    TickResult tick(B body);

    void pause(B body, String reason);

    void resume(B body);

    void abort(B body, String reason);

    /**
     * The few facts worth keeping across a restart, as hints for where to look (a shaft's position and the Y it
     * reached, a village found). The task kind owns and validates its keys. Read by the executor when the task
     * ends or the goal is saved. A null key or value is dropped (and logged) by the executor, and a throw is
     * logged and the last stored checkpoint kept: releasing a task never fails on its bookkeeping.
     */
    default Map<String, String> checkpoint() {
        return Map.of();
    }

    /**
     * The last exception any of this task's hooks threw, or null. A task can be failed with a typed failure and
     * then have another hook throw; the failure wins the routing, and this keeps the exception so the executor
     * can still log it with its stack trace when it retires the task (design 3.4: nothing swallows a throw).
     */
    default Throwable lastHookError() {
        return null;
    }

    /**
     * True while the task is making progress that position and inventory do not show (waiting on a furnace, a
     * fishing bobber, a villager). Stops the executor's stuck watchdog from failing it as NO_PROGRESS; the
     * task's tick budget still applies.
     */
    default boolean waiting() {
        return false;
    }
}
