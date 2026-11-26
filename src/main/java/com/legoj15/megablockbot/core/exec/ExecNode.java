package com.legoj15.megablockbot.core.exec;

import com.legoj15.megablockbot.core.plan.NodeSpec;
import com.legoj15.megablockbot.core.task.Failure;
import com.legoj15.megablockbot.core.task.Task;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * One node of the tree the executor runs (design 3.4, Goal tree): the planner's immutable {@link NodeSpec}
 * plus everything the executor adds -- the state, the task instance, the retry memory and the watchdog
 * counters. Package-private on purpose: only the executor sees it.
 */
final class ExecNode<B> {

    final NodeSpec spec;
    final boolean executorInserted;
    final List<ExecNode<B>> children;
    final ExecNode<B> parent;

    NodeState state = NodeState.PENDING;

    /** The live task while this node is ACTIVE; a fresh instance per start and per retry. */
    Task<B> task;

    /** Failure kinds already retried for this node: one retry per node per failure type. */
    final Set<Failure.Kind> retriedKinds = EnumSet.noneOf(Failure.Kind.class);

    /** A Reach node was already inserted for an UNKNOWN of this node's postcondition. */
    boolean unknownReachTried;

    /** A non-leaf only: its own postcondition was evaluated when execution first reached it. */
    boolean groupChecked;

    int budgetTicksUsed;
    long lastSignature;
    int stuckTicks;

    private ExecNode(NodeSpec spec, boolean executorInserted, ExecNode<B> parent) {
        this.spec = spec;
        this.executorInserted = executorInserted;
        this.parent = parent;
        this.children = new ArrayList<>();
        for (NodeSpec child : spec.children()) children.add(of(child, false, this));
    }

    static <B> ExecNode<B> of(NodeSpec spec, boolean executorInserted, ExecNode<B> parent) {
        return new ExecNode<B>(spec, executorInserted, parent);
    }

    boolean isLeaf() {
        return spec.isLeaf();
    }

    boolean terminal() {
        return state == NodeState.SATISFIED || state == NodeState.FAILED || state == NodeState.SKIPPED;
    }
}
