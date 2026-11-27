package com.legoj15.megablockbot.core.planner;

import com.legoj15.megablockbot.core.Pos;
import com.legoj15.megablockbot.core.goal.Evaluation;
import com.legoj15.megablockbot.core.goal.Goal;
import com.legoj15.megablockbot.core.goal.Postcondition;
import com.legoj15.megablockbot.core.plan.NodeSpec;
import com.legoj15.megablockbot.core.snapshot.ContainerSight;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * "Know what is in that container" (design 3.5, supply containers): the small goal of its own that a strategy defines
 * (design 3.4, {@link Goal.Custom}). A body does only what a player can, and a player cannot see into a closed chest, so
 * when its owner names a container for it to take from, the body does not know what is in it: it goes and opens it, notes
 * what it finds and closes it, as the one leaf of this goal. What it noted is what every later plan counts.
 *
 * <p>This class is the goal's three parts: the goal ({@link #goal}), its plan (the strategy: one {@link #TASK} leaf) and
 * its postcondition ({@link #postcondition}, registered under {@link #KIND}), which reads the snapshot and so is true
 * only once the body has a note of the container. Server code puts the strategy in the planner and the postcondition in
 * the executor's registry.
 */
public final class LookInside implements Strategy {
    /** The custom goal kind and, for want of a second one, the task kind of its leaf. */
    public static final String KIND = "look_inside";
    public static final String TASK = "look_inside";
    public static final String PARAM_CONTAINER = "container";
    public static final String PARAM_DIMENSION = "dimension";

    /** The goal "know what is in the container at {@code container} in {@code dimension}". */
    public static Goal.Custom goal(String dimension, Pos container) {
        Map<String, String> params = new TreeMap<>();
        params.put(PARAM_DIMENSION, dimension);
        params.put(PARAM_CONTAINER, container.encode());
        return new Goal.Custom(KIND, params);
    }

    /**
     * Satisfied once the snapshot has a note of what is inside the container (a note outlives the body's walking away,
     * and is replaced each time the body opens the container again). A container nobody names any more (the owner took it
     * back, or it was replaced by another block, so the snapshot lists nothing there) has nothing left to look at, which
     * also counts as done: the owner's later word is what the body acts on. In another dimension than the body's the
     * goal cannot be met, and the leaf says why.
     */
    public static Postcondition postcondition(Goal.Custom goal) {
        String dimension = goal.params().getOrDefault(PARAM_DIMENSION, "");
        Pos container = containerOf(goal);
        // A goal read back from a damaged save has no position to look at: the snapshot cannot tell, never "done".
        if (container == null) return snapshot -> Evaluation.unknown(1, "a container position");
        String what = "inside the container at " + container.encode();
        return snapshot -> {
            if (!snapshot.dimension().equals(dimension)) return Evaluation.unsatisfied(0, 1, List.of(what));
            ContainerSight sight = snapshot.containerAt(container);
            if (sight == null) return Evaluation.satisfied(1);
            return sight.remembered() ? Evaluation.satisfied(1) : Evaluation.unsatisfied(0, 1, List.of(what));
        };
    }

    /** The container the goal is about, or null when its parameter is missing or not a position. */
    private static Pos containerOf(Goal.Custom goal) {
        try {
            return Pos.parse(goal.params().getOrDefault(PARAM_CONTAINER, ""));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Override
    public String id() {
        return "look-inside";
    }

    @Override
    public Optional<Proposal> propose(Goal goal, PlanContext context) {
        if (!(goal instanceof Goal.Custom custom) || !KIND.equals(custom.kind())) return Optional.empty();
        Pos container = containerOf(custom);
        if (container == null) return Optional.empty();
        NodeSpec leaf = NodeSpec.leaf(custom, TASK, custom.params(), "look:" + container.encode());
        return Optional.of(new Proposal(List.of(leaf), 1, List.of()));
    }
}
