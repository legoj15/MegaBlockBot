package com.legoj15.megablockbot.core.task;

public enum TaskState {
    PENDING, RUNNING, PAUSED, COMPLETED, FAILED, CANCELLED;

    public boolean terminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }
}
