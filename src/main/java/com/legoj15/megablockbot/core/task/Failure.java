package com.legoj15.megablockbot.core.task;

import com.legoj15.megablockbot.core.ItemKey;
import com.legoj15.megablockbot.core.Pos;
import com.legoj15.megablockbot.core.ToolClass;
import com.legoj15.megablockbot.core.ToolTier;
import java.util.List;

/**
 * Why a task, a plan or a goal failed (design 3.4, Failure). Routing reads the type; {@link #detail()} is for
 * logs and the owner only and must never drive a decision.
 */
public sealed interface Failure {
    /** One value per failure type: the unit of "one retry per node per failure type". */
    enum Kind {
        UNREACHABLE, TARGET_GONE, MISSING_TOOL, MISSING_INPUT, NO_SOURCE, PROTECTED, HAZARD, TIMED_OUT,
        NO_PROGRESS, DIED, PREEMPT_STALLED, NO_PLACE, INTERNAL
    }

    Kind kind();

    String detail();

    record Unreachable(String target, String navReason, String detail) implements Failure {
        public Kind kind() { return Kind.UNREACHABLE; }
    }

    record TargetGone(String what, String detail) implements Failure {
        public Kind kind() { return Kind.TARGET_GONE; }
    }

    record MissingTool(ToolClass toolClass, ToolTier tier, String detail) implements Failure {
        public Kind kind() { return Kind.MISSING_TOOL; }
    }

    record MissingInput(ItemKey key, int count, String detail) implements Failure {
        public Kind kind() { return Kind.MISSING_INPUT; }
    }

    /** @param source the source id the planner chose, so a replan can exclude exactly that one; may be empty */
    record NoSource(ItemKey key, String source, String detail) implements Failure {
        public Kind kind() { return Kind.NO_SOURCE; }
    }

    /** @param rule which block-safety rule refused (design 3.5) */
    record Protected(Pos pos, String rule, String detail) implements Failure {
        public Kind kind() { return Kind.PROTECTED; }
    }

    /** @param hazard lava, fall, drowning... */
    record Hazard(String hazard, String dimension, Pos pos, String detail) implements Failure {
        public Kind kind() { return Kind.HAZARD; }
    }

    record TimedOut(int ticksUsed, String detail) implements Failure {
        public Kind kind() { return Kind.TIMED_OUT; }
    }

    record NoProgress(int ticksUsed, String detail) implements Failure {
        public Kind kind() { return Kind.NO_PROGRESS; }
    }

    record Died(String dimension, Pos deathPos, List<Pos> dropPositions, long deathTime, String detail)
            implements Failure {
        public Died {
            dropPositions = List.copyOf(dropPositions);
        }

        public Kind kind() { return Kind.DIED; }
    }

    record PreemptStalled(String reflexCause, String detail) implements Failure {
        public Kind kind() { return Kind.PREEMPT_STALLED; }
    }

    /**
     * The body looked and found nowhere to do {@code what} (put a crafting table down, stand to work), and a new plan
     * would find the same: the planner does not see where a block fits, so replanning cannot change the answer and the
     * executor gives the goal up at once.
     *
     * @param what   the thing it could not find a place for, as a verb phrase ("put a crafting table down")
     * @param radius how far from where it stood, in blocks, it looked
     */
    record NoPlace(String what, int radius, String detail) implements Failure {
        public Kind kind() { return Kind.NO_PLACE; }
    }

    /** A bug: a condition the code did not expect. A task that merely cannot do its work here says so with its own kind. */
    record Internal(Throwable exception, String detail) implements Failure {
        public Kind kind() { return Kind.INTERNAL; }
    }
}
