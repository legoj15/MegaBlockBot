package com.legoj15.megablockbot.core.exec;

import com.legoj15.megablockbot.core.goal.Goal;
import com.legoj15.megablockbot.core.plan.Exclusions;
import com.legoj15.megablockbot.core.plan.NodeSpec;
import com.legoj15.megablockbot.core.plan.PlanRequest;
import com.legoj15.megablockbot.core.plan.Profile;
import com.legoj15.megablockbot.core.snapshot.Snapshot;
import com.legoj15.megablockbot.core.task.Failure;
import com.legoj15.megablockbot.core.task.Task;
import java.util.Map;

/**
 * Everything the executor needs from outside (design 3.4). Implemented by the game layer on the server
 * thread; by fakes in tests. The executor never blocks: planning goes through {@link #plan} and is polled.
 *
 * <p>Rules a host author has to know:
 * <ul>
 *   <li>The host reports a body's death through {@link GoalExecutor#onDeath}, and a task whose body died should
 *       not also return {@code Failed(Died)}: the executor would count that death a second time.</li>
 *   <li>Whatever a host call throws (a snapshot, the planner, a task factory, a task hook) is logged and ends the
 *       goal with {@code Failure.Internal}; it never reaches the server tick. A plan ticket that never becomes
 *       done is cancelled after {@link ExecutorConfig#planTimeoutTicks()} ticks and the goal gives up.</li>
 *   <li>The failure behind the last replan is not part of the save, so a budget give-up right after a load
 *       reports {@code NoProgress}.</li>
 * </ul>
 */
public interface ExecutorHost<B> {

    /** A fresh snapshot, including whatever extra data this goal asks for. Never changes state. */
    Snapshot snapshot(B body, Goal goal);

    /** Cheap hash of body position and carried items, for the stuck watchdog. */
    long progressSignature(B body);

    /** A new task for a leaf. {@code hints} is the checkpoint kept for that node identity, or empty. */
    Task<B> createTask(NodeSpec leaf, Map<String, String> hints);

    /** Starts planning off-thread. The executor polls the ticket. */
    PlanTicket plan(PlanRequest request);

    /** Planner.inputFingerprint for this goal on this snapshot, under the goal's own exclusions. */
    long inputFingerprint(Goal goal, Snapshot snapshot, Exclusions exclusions);

    /** True when the drops of this death can still be reached before they despawn. */
    boolean dropsRecoverable(Failure.Died death, Snapshot now);

    /** A terminal goal result: to the owner, then to the language layer. */
    void publish(GoalResult result);

    Profile profile();

    /** error may be null. */
    void log(String message, Throwable error);
}
