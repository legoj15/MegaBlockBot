package com.legoj15.megablockbot.core.planner;

import com.legoj15.megablockbot.core.goal.Goal;
import com.legoj15.megablockbot.core.plan.NodeSpec;
import java.util.List;
import java.util.Optional;

/**
 * A hand-written multi-stage plan the generic planner may slot in (design 4.4): the librarian reroll, a
 * branch-mine-at-Y, a blueprint build. The planner asks every strategy for a goal it cannot plan itself
 * (a component-checked item, a build, a custom goal) and, for a leaf item, compares the cheapest proposal
 * with the best gather source's cost. Nothing is implemented here in P12; the tests use a fake one.
 */
public interface Strategy {
    /** Stable id, for logs and for breaking a tie between two proposals of equal cost. */
    String id();

    /** Nodes that achieve the goal (or obtain the leaf item) and their cost, or empty when it does not apply. */
    Optional<Proposal> propose(Goal goal, PlanContext context);

    /**
     * @param nodes the nodes to run, in order; their side effects are reflected in the context's working stock
     * @param cost  abstract cost (think ticks), ranked against gather costs and other proposals
     * @param notes what the proposal could not consider, for {@code Plan.notes}
     */
    record Proposal(List<NodeSpec> nodes, long cost, List<String> notes) {
        public Proposal {
            nodes = List.copyOf(nodes);
            notes = List.copyOf(notes);
        }
    }
}
