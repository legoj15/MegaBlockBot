package com.legoj15.megablockbot.core.exec;

/** A goal-tree node's state (design 3.4, Node transition table). */
public enum NodeState {
    PENDING, ACTIVE, SATISFIED, FAILED, SKIPPED
}
