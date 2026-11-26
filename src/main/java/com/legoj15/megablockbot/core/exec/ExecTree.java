package com.legoj15.megablockbot.core.exec;

import com.legoj15.megablockbot.core.plan.NodeIdentity;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Walks and rewrites the goal tree the executor runs (design 3.4, Goal tree): finding the active node and the
 * next piece of work, deriving a non-leaf's state, marking a subtree done, and keeping the executor-inserted
 * nodes across a replan. Pure functions over {@link ExecNode}; the executor decides when to call them.
 * Package-private.
 */
final class ExecTree {

    private ExecTree() {}

    /** The ACTIVE node, depth first; at most one exists. */
    static <B> ExecNode<B> findActive(List<ExecNode<B>> roots) {
        for (ExecNode<B> r : roots) {
            ExecNode<B> found = findActive(r);
            if (found != null) return found;
        }
        return null;
    }

    private static <B> ExecNode<B> findActive(ExecNode<B> node) {
        if (node.state == NodeState.ACTIVE) return node;
        for (ExecNode<B> c : node.children) {
            ExecNode<B> found = findActive(c);
            if (found != null) return found;
        }
        return null;
    }

    /** The next node execution has to look at: a group not yet evaluated, or a pending leaf. Depth first. */
    static <B> ExecNode<B> nextWork(List<ExecNode<B>> roots) {
        for (ExecNode<B> r : roots) {
            ExecNode<B> found = nextWork(r);
            if (found != null) return found;
        }
        return null;
    }

    private static <B> ExecNode<B> nextWork(ExecNode<B> node) {
        if (node.terminal()) return null;
        if (node.isLeaf()) return node.state == NodeState.PENDING ? node : null;
        if (!node.groupChecked) return node;
        for (ExecNode<B> c : node.children) {
            ExecNode<B> found = nextWork(c);
            if (found != null) return found;
        }
        return null;
    }

    /** Marks a group and every descendant that has not finished as done. */
    static <B> void markDone(ExecNode<B> group) {
        if (group.state == NodeState.PENDING || group.state == NodeState.ACTIVE) group.state = NodeState.SATISFIED;
        for (ExecNode<B> c : group.children) markDone(c);
    }

    /** The executor-inserted nodes that have not finished or failed, in order: what survives a replan. */
    static <B> List<ExecNode<B>> retainInserted(List<ExecNode<B>> roots) {
        List<ExecNode<B>> kept = new ArrayList<>();
        for (ExecNode<B> r : roots) {
            if (r.executorInserted && !r.terminal()) kept.add(r);
        }
        return kept;
    }

    /** Every node identity in the subtree, for carrying checkpoints over to a replanned tree. */
    static <B> void collectIdentities(ExecNode<B> node, Set<String> into) {
        into.add(NodeIdentity.of(node.spec));
        for (ExecNode<B> c : node.children) collectIdentities(c, into);
    }

    /** A non-leaf has no task; its state is derived (design 3.4). */
    static <B> NodeState derivedState(ExecNode<B> node) {
        if (node.isLeaf() || node.state == NodeState.SATISFIED) return node.state;
        boolean allDone = true;
        for (ExecNode<B> c : node.children) {
            NodeState s = derivedState(c);
            if (s == NodeState.ACTIVE) return NodeState.ACTIVE;
            if (s != NodeState.SATISFIED && s != NodeState.SKIPPED) allDone = false;
        }
        return allDone ? NodeState.SATISFIED : NodeState.PENDING;
    }
}
