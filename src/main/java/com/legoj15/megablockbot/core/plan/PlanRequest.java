package com.legoj15.megablockbot.core.plan;

import com.legoj15.megablockbot.core.goal.Goal;
import com.legoj15.megablockbot.core.snapshot.Snapshot;
import java.util.Map;

/**
 * Everything one planning run may read. Immutable, so planning runs off the server thread (design 3.4,
 * Threading).
 *
 * @param checkpoints hints kept from earlier nodes, by node identity ({@link NodeIdentity}); the planner may
 *                    use them to prefer a known place (a shaft already dug, a village already found)
 */
public record PlanRequest(Goal goal, Snapshot snapshot, Exclusions exclusions, Profile profile,
                          Map<String, Map<String, String>> checkpoints) {
    public PlanRequest {
        checkpoints = Map.copyOf(checkpoints);
    }
}
