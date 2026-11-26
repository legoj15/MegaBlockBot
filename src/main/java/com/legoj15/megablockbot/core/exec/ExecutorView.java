package com.legoj15.megablockbot.core.exec;

import com.legoj15.megablockbot.core.goal.Goal;
import java.util.List;

/**
 * An immutable picture of the executor for the status command and the language layer (design 3.4). Built by
 * {@link GoalExecutor#view()} on the server thread; a snapshot of one instant, never live state.
 *
 * @param goal                             the active goal, or null when idle
 * @param status                           its status, or null when idle
 * @param nodes                            the tree, depth first
 * @param totalReplans                     every plan-caused replan this goal
 * @param consecutiveReplansWithoutProgress the no-progress streak
 * @param repairReplans                    replans after the tree ran out unsatisfied
 * @param deaths                           deaths within this goal
 * @param queue                            goals waiting, first to run first
 * @param planPending                      a planning result has not been adopted yet
 * @param reflexHeld                       the reflex layer owns the body right now
 */
public record ExecutorView(
        Goal goal,
        GoalStatus status,
        List<NodeView> nodes,
        int totalReplans,
        int consecutiveReplansWithoutProgress,
        int repairReplans,
        int deaths,
        List<Goal> queue,
        boolean planPending,
        boolean reflexHeld) {

    public ExecutorView {
        nodes = List.copyOf(nodes);
        queue = List.copyOf(queue);
    }

    /**
     * @param depth            nesting depth, 0 for a root
     * @param goal             the node's sub-goal
     * @param taskKind         the leaf's task kind, null for a non-leaf
     * @param source           the chosen source or position, for the status line
     * @param state            the node's state; derived for non-leaves
     * @param bestEffort       a failure skips it instead of routing
     * @param executorInserted the executor (not the planner) put it there: a Reach for UNKNOWN, recover-drops
     */
    public record NodeView(int depth, Goal goal, String taskKind, String source, NodeState state,
                           boolean bestEffort, boolean executorInserted) {
    }
}
