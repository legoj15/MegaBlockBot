package com.legoj15.megablockbot.core.plan;

import com.legoj15.megablockbot.core.task.Failure;

/**
 * What planning produced. {@link Unplannable} is an expected answer (no source left for an item, no strategy
 * applies) and ends the goal with that failure. A planner that throws is a bug: the executor treats it as
 * {@link Failure.Internal}.
 */
public sealed interface PlanOutcome {
    record Planned(Plan plan) implements PlanOutcome {}

    record Unplannable(Failure failure) implements PlanOutcome {}
}
