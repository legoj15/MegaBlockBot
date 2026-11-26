package com.legoj15.megablockbot.core.exec;

import com.legoj15.megablockbot.core.plan.PlanOutcome;

/**
 * A planning run in flight (design 3.4, Replanning). Created by {@link ExecutorHost#plan} off the server
 * thread and polled by the executor; {@link #outcome()} rethrows what the planner threw.
 */
public interface PlanTicket {
    boolean done();

    PlanOutcome outcome() throws Exception;

    void cancel();
}
