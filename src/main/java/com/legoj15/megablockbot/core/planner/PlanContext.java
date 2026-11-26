package com.legoj15.megablockbot.core.planner;

import com.legoj15.megablockbot.core.ItemKey;
import com.legoj15.megablockbot.core.acquire.AcquisitionIndex;
import com.legoj15.megablockbot.core.goal.Goal;
import com.legoj15.megablockbot.core.plan.Exclusions;
import com.legoj15.megablockbot.core.plan.PlanOutcome;
import com.legoj15.megablockbot.core.snapshot.Snapshot;
import java.util.Map;

/**
 * What a strategy sees while one planning run is under way (brief P12). The working stock is the stock the
 * body will hold once the nodes planned so far have run: a strategy that proposes nodes must keep it true,
 * and {@link #subPlan} advances it with whatever the sub-plan gathers and crafts.
 */
public interface PlanContext {
    /** The immutable snapshot the plan was asked for. */
    Snapshot snapshot();

    /** What the body will hold when the nodes planned so far have run; the strategy may read, not write it. */
    Map<ItemKey, Long> stock();

    RecipeBook recipes();

    AcquisitionIndex sources();

    Exclusions exclusions();

    PlannerConfig config();

    /**
     * Plans another goal against the same working stock (the planner's own expansion, strategies included).
     * The stock advances when the outcome is a plan; the caller is responsible for putting the returned
     * nodes into its own proposal in a runnable order.
     */
    PlanOutcome subPlan(Goal goal);
}
