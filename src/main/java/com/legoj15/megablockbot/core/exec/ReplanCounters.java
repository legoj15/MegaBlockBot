package com.legoj15.megablockbot.core.exec;

/**
 * The per-goal replan and death counters (design 3.4, Retry/replan/give-up). They persist with the goal, so
 * a restart does not reset them. Package-private: read through {@link ExecutorView}.
 */
final class ReplanCounters {

    /** Every plan-caused replan, progress or not. */
    int totalReplans;

    /** Consecutive replans that were not progress. */
    int consecutiveWithoutProgress;

    /** Replans after the tree ran out unsatisfied. */
    int repairReplans;

    /** Deaths within this goal. */
    int deaths;
}
