package com.legoj15.megablockbot.core.exec;

import java.util.Map;

/**
 * The executor's budgets and thresholds (design 3.4). The numbers are starting values that later work
 * tunes; every rule they feed is in the design's tables.
 *
 * @param maxReplansWithoutProgress   consecutive replans with no progress before giving up
 * @param maxTotalReplans             per goal, every plan-caused replan
 * @param totalReplanCeiling          cap on a plan's declared higher total ({@code Plan.maxTotalReplans})
 * @param maxRepairReplans            when the tree runs out unsatisfied
 * @param maxDeathsPerGoal            the third death within one goal gives up
 * @param deathExclusionRadius        area excluded around a death position
 * @param hazardExclusionRadius       area excluded around a HAZARD failure's position
 * @param defaultTaskBudgetTicks      per task; exceeding it is TIMED_OUT
 * @param taskBudgetTicks             per task kind override
 * @param stuckTicks                  no progress-signature change while RUNNING and not {@code waiting()}
 *                                    before NO_PROGRESS
 * @param preemptMaxPausedTicks       total reflex-paused ticks before PREEMPT_STALLED
 * @param preemptMaxCount             repeated preemptions within {@code preemptWindowTicks} before
 *                                    PREEMPT_STALLED
 * @param preemptWindowTicks          the window for {@code preemptMaxCount}
 * @param planRerunLimit              re-runs when the input fingerprint moved; the next result is adopted
 *                                    regardless
 * @param planTimeoutTicks            ticks spent waiting on one plan ticket before it is cancelled and the goal
 *                                    gives up TIMED_OUT; a re-run is a new ticket with a fresh count
 */
public record ExecutorConfig(
        int maxReplansWithoutProgress,
        int maxTotalReplans,
        int totalReplanCeiling,
        int maxRepairReplans,
        int maxDeathsPerGoal,
        int deathExclusionRadius,
        int hazardExclusionRadius,
        int defaultTaskBudgetTicks,
        Map<String, Integer> taskBudgetTicks,
        int stuckTicks,
        int preemptMaxPausedTicks,
        int preemptMaxCount,
        int preemptWindowTicks,
        int planRerunLimit,
        int planTimeoutTicks) {

    public ExecutorConfig {
        taskBudgetTicks = Map.copyOf(taskBudgetTicks);
    }

    public static ExecutorConfig defaults() {
        return new ExecutorConfig(3, 12, 48, 3, 3, 16, 8, 12000, Map.of(), 600, 2400, 8, 1200, 2, 1200);
    }

    /** The tick budget for a task kind: the per-kind override, else the default. */
    public int budgetFor(String taskKind) {
        return taskBudgetTicks.getOrDefault(taskKind, defaultTaskBudgetTicks);
    }
}
