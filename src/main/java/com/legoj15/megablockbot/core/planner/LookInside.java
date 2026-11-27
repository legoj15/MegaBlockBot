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
 *
 * <p>The planner uses the same leaf for a second purpose: a stockpile goal's target the body has no note of at all (it
 * has never been inside) cannot be judged, so the plan for that goal is this look and nothing else
 * ({@link #leafBeforeDeposit}); once the body has looked, the goal is judged on what it found, and when it is not met
 * the executor replans with the contents known. That goal's own container is the owner's word for the look, as it is for
 * the deposit, and a container with no sight at all is not "nobody names it any more" but "not looked at yet".
 */
public final class LookInside implements Strategy {
    /** The custom goal kind and, for want of a second one, the task kind of its leaf. */
    public static final String KIND = "look_inside";
    public static final String TASK = "look_inside";
    public static final String PARAM_CONTAINER = "container";
    public static final String PARAM_DIMENSION = "dimension";
    /** Set (to {@link #FOR_STOCKPILE}) on the look a stockpile goal's plan makes of its own target; absent on a named container's. */
    public static final String PARAM_FOR = "for";
    public static final String FOR_STOCKPILE = "stockpile";

    /** The goal "know what is in the container at {@code container} in {@code dimension}". */
    public static Goal.Custom goal(String dimension, Pos container) {
        Map<String, String> params = new TreeMap<>();
        params.put(PARAM_DIMENSION, dimension);
        params.put(PARAM_CONTAINER, container.encode());
        return new Goal.Custom(KIND, params);
    }

    /**
     * The look a stockpile goal's plan makes of its target before anything else: as {@link #goal}, except that a
     * container the snapshot has no sight of is not one nobody names any more but one not looked at yet.
     */
    public static Goal.Custom goalBeforeDeposit(String dimension, Pos container) {
        Map<String, String> params = new TreeMap<>();
        params.put(PARAM_DIMENSION, dimension);
        params.put(PARAM_CONTAINER, container.encode());
        params.put(PARAM_FOR, FOR_STOCKPILE);
        return new Goal.Custom(KIND, params);
    }

    /** The one leaf of {@link #goalBeforeDeposit}: the look a stockpile plan of a container with no sight consists of. */
    public static NodeSpec leafBeforeDeposit(String dimension, Pos container) {
        Goal.Custom goal = goalBeforeDeposit(dimension, container);
        return NodeSpec.leaf(goal, TASK, goal.params(), "look:" + container.encode());
    }

    /** Whether the goal is the look a stockpile goal's plan makes of its own target, which that goal's word covers. */
    public static boolean isBeforeDeposit(Goal.Custom goal) {
        return FOR_STOCKPILE.equals(goal.params().get(PARAM_FOR));
    }

    /**
     * Satisfied once the snapshot has a note of what is inside the container (a note outlives the body's walking away,
     * and is replaced each time the body opens the container again). A container nobody names any more (the owner took it
     * back, or it was replaced by another block, so the snapshot lists nothing there) has nothing left to look at, which
     * also counts as done: the owner's later word is what the body acts on. In another dimension than the body's the
     * goal cannot be met, and the leaf says why. The one exception is the look a stockpile plan makes ({@link
     * #goalBeforeDeposit}): its container is named by the stockpile goal, which nobody takes back, so no sight at all
     * means the body has not looked, and it is not met.
     */
    public static Postcondition postcondition(Goal.Custom goal) {
        String dimension = goal.params().getOrDefault(PARAM_DIMENSION, "");
        boolean beforeDeposit = isBeforeDeposit(goal);
        Pos container = containerOf(goal);
        // A goal read back from a damaged save has no position to look at: the snapshot cannot tell, never "done".
        if (container == null) return snapshot -> Evaluation.unknown(1, "a container position");
        String what = "inside the container at " + container.encode();
        return snapshot -> {
            if (!snapshot.dimension().equals(dimension)) return Evaluation.unsatisfied(0, 1, List.of(what));
            ContainerSight sight = snapshot.containerAt(container);
            if (sight == null) return beforeDeposit ? Evaluation.unsatisfied(0, 1, List.of(what)) : Evaluation.satisfied(1);
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
