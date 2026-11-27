package com.legoj15.megablockbot.core.exec;

import com.legoj15.megablockbot.core.task.Failure;

/**
 * Adds a note to a failure's detail without changing its type or payload. Used when a budget runs out: the goal
 * result carries the failure the bot actually hit (design 3.4, give-up), and the note names which budget ended
 * the retrying. Routing never reads the detail, so the copy routes exactly like the original.
 */
final class FailureNotes {

    private FailureNotes() {}

    static Failure with(Failure failure, String note) {
        String old = failure.detail();
        String detail = old == null || old.isEmpty() ? note : old + "; " + note;
        return switch (failure) {
            case Failure.Unreachable f -> new Failure.Unreachable(f.target(), f.navReason(), detail);
            case Failure.TargetGone f -> new Failure.TargetGone(f.what(), detail);
            case Failure.MissingTool f -> new Failure.MissingTool(f.toolClass(), f.tier(), detail);
            case Failure.MissingInput f -> new Failure.MissingInput(f.key(), f.count(), detail);
            case Failure.NoSource f -> new Failure.NoSource(f.key(), f.source(), detail);
            case Failure.Protected f -> new Failure.Protected(f.pos(), f.rule(), detail);
            case Failure.Hazard f -> new Failure.Hazard(f.hazard(), f.dimension(), f.pos(), detail);
            case Failure.TimedOut f -> new Failure.TimedOut(f.ticksUsed(), detail);
            case Failure.NoProgress f -> new Failure.NoProgress(f.ticksUsed(), detail);
            case Failure.Died f -> new Failure.Died(f.dimension(), f.deathPos(), f.dropPositions(), f.deathTime(),
                    detail);
            case Failure.PreemptStalled f -> new Failure.PreemptStalled(f.reflexCause(), detail);
            case Failure.NoPlace f -> new Failure.NoPlace(f.what(), f.radius(), detail);
            case Failure.NoRoom f -> new Failure.NoRoom(f.key(), detail);
            case Failure.Internal f -> new Failure.Internal(f.exception(), detail);
        };
    }
}
