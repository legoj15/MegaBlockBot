package com.legoj15.megablockbot.core.planner;

import com.legoj15.megablockbot.core.ItemKey;
import com.legoj15.megablockbot.core.acquire.AcquisitionIndex;
import com.legoj15.megablockbot.core.goal.Goal;
import com.legoj15.megablockbot.core.plan.Exclusions;
import com.legoj15.megablockbot.core.plan.PlanOutcome;
import com.legoj15.megablockbot.core.plan.Profile;
import com.legoj15.megablockbot.core.snapshot.Snapshot;
import java.util.Map;

/**
 * What a strategy sees while one planning run is under way (brief P12, design 4.4): everything the request carried,
 * the working stock, and a way to plan another goal. The working stock is the stock the body will hold once the nodes
 * planned so far have run; a strategy that proposes nodes plans their effect through {@link #subPlan}, which
 * advances it, and the planner takes the working stock back to what it was before a proposal unless that proposal wins.
 */
public interface PlanContext {
    /** The immutable snapshot the plan was asked for. */
    Snapshot snapshot();

    /** The world-knowledge fairness of this request (design 4.5), so a strategy may refuse a locate or a scan. */
    Profile profile();

    /** Hints kept from earlier nodes, by node identity: where a shaft was dug, which village was found. */
    Map<String, Map<String, String>> checkpoints();

    /**
     * What the body will hold when the nodes planned so far have run. A read-only live view: it changes as
     * {@link #subPlan} advances the stock, and a strategy cannot write to it.
     */
    Map<ItemKey, Long> stock();

    RecipeBook recipes();

    AcquisitionIndex sources();

    Exclusions exclusions();

    PlannerConfig config();

    /**
     * Plans another goal against the same working stock (the planner's own expansion, strategies included).
     * The stock advances when the outcome is a plan and is left as it was when the outcome is
     * {@code Unplannable}; the caller is responsible for putting the returned nodes into its own proposal in a
     * runnable order.
     */
    PlanOutcome subPlan(Goal goal);
}
