package com.legoj15.megablockbot.core.plan;

import com.legoj15.megablockbot.core.goal.Goal;
import com.legoj15.megablockbot.core.snapshot.Snapshot;

/**
 * Expands a goal into a goal tree (design 4). Called off the server thread on immutable inputs: an
 * implementation reads the request and its own immutable indexes (recipes, acquisition sources) and nothing
 * live.
 */
public interface Planner {
    PlanOutcome plan(PlanRequest request);

    /**
     * The hash {@link Plan#inputFingerprint()} would carry for this goal on this snapshot and these exclusions.
     * Cheap; called on the server thread to decide whether a finished plan is still current. The exclusions are an
     * input the planner reads (which sources and containers it may use), so a goal whose failures have ruled
     * something out since must not be fingerprinted as it was.
     */
    long inputFingerprint(Goal goal, Snapshot snapshot, Exclusions exclusions);
}
