package com.legoj15.megablockbot.core.exec;

import com.legoj15.megablockbot.core.goal.Goal;
import com.legoj15.megablockbot.core.task.Failure;
import java.util.List;

/**
 * The terminal result of one goal, published to the owner and then to the language layer (design 3.4,
 * give-up). PARTIAL and FAILED are both give-ups: PARTIAL when {@code matched > 0}.
 *
 * @param failure the failure that ended it; null when SATISFIED or CANCELLED
 */
public record GoalResult(Goal goal, Status status, int matched, int required, List<String> unmet,
                         Failure failure) {
    public enum Status { SATISFIED, PARTIAL, FAILED, CANCELLED }

    public GoalResult {
        unmet = List.copyOf(unmet);
    }
}
