package com.legoj15.megablockbot.core.exec;

/**
 * The life of one goal (design 3.4). PAUSED is set and cleared only by the owner's pause/resume command; a
 * reflex preemption pauses only the active task and leaves the goal RUNNING.
 */
public enum GoalStatus {
    RUNNING, PAUSED, SATISFIED, GAVE_UP, CANCELLED;

    public boolean terminal() {
        return this == SATISFIED || this == GAVE_UP || this == CANCELLED;
    }
}
