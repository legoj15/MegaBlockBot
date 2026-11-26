package com.legoj15.megablockbot.core.exec;

import com.legoj15.megablockbot.core.goal.Goal;
import com.legoj15.megablockbot.core.plan.NodeIdentity;
import java.util.List;

/**
 * The plan fingerprint (design 3.4, Retry/replan/give-up): a hash of the tree's pending nodes -- task kind,
 * target, the count when the goal has one, and the chosen source -- so switching to another source counts as
 * a change even when the item does not. Package-private.
 */
final class PlanFingerprint {

    private PlanFingerprint() {}

    static long of(List<? extends ExecNode<?>> roots) {
        long h = 1125899906842597L;
        for (ExecNode<?> n : roots) h = h * 31 + node(n);
        return h;
    }

    private static long node(ExecNode<?> n) {
        long h = 1;
        if (n.state == NodeState.PENDING) {
            String kind = n.spec.taskKind() == null ? "group" : n.spec.taskKind();
            h = 31 * h + kind.hashCode();
            h = 31 * h + NodeIdentity.target(n.spec.goal()).hashCode();
            h = 31 * h + countOf(n.spec.goal());
            h = 31 * h + n.spec.source().hashCode();
        }
        for (ExecNode<?> c : n.children) h = h * 31 + node(c);
        return h;
    }

    private static long countOf(Goal goal) {
        return switch (goal) {
            case Goal.HaveItem g -> g.count();
            case Goal.Stockpile g -> g.count();
            default -> 0;
        };
    }
}
