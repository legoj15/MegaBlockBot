package com.legoj15.megablockbot.core.planner;

import com.legoj15.megablockbot.core.goal.Goal;
import com.legoj15.megablockbot.core.plan.NodeSpec;
import java.util.List;
import java.util.Optional;

/**
 * A hand-written multi-stage plan the generic planner may slot in (design 4.4): the librarian reroll, a
 * branch-mine-at-Y, a blueprint build. The planner asks every strategy for a goal it cannot plan itself
 * (a component-checked item, a build, a custom goal) and, for a leaf item, compares each proposal's cost with
 * what gathering the whole demand costs, the gather's tool prerequisite included. Nothing is implemented here in
 * P12; the tests use fakes.
 *
 * <p>Every strategy is asked against the working stock as it was before any of them: the planner takes the stock
 * back after each {@link #propose} and keeps the effect of the winning proposal alone. A proposal that obtains a
 * leaf item without planning it through {@link PlanContext#subPlan} (a leaf of its own) is credited to the stock
 * by the planner; one whose sub-plans already brought the stock to the goal count is not credited twice.
 */
public interface Strategy {
    /** Stable id, for logs and for breaking a tie between two proposals of equal cost. */
    String id();

    /** Nodes that achieve the goal (or obtain the leaf item) and their cost, or empty when it does not apply. */
    Optional<Proposal> propose(Goal goal, PlanContext context);

    /**
     * @param nodes the nodes to run, in order; sub-plans they came from advanced the context's working stock
     * @param cost  abstract cost (think ticks) of the whole proposal, ranked against the cost of gathering the whole
     *              demand, its tool prerequisite included, and against other proposals
     * @param notes what the proposal could not consider, for {@code Plan.notes}
     */
    record Proposal(List<NodeSpec> nodes, long cost, List<String> notes) {
        public Proposal {
            nodes = List.copyOf(nodes);
            notes = List.copyOf(notes);
        }
    }
}
