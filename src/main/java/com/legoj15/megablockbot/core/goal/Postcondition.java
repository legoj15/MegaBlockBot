package com.legoj15.megablockbot.core.goal;

import com.legoj15.megablockbot.core.snapshot.Snapshot;

/** A pure function from a snapshot to an evaluation (design 3.4). Reads nothing but the snapshot. */
@FunctionalInterface
public interface Postcondition {
    Evaluation evaluate(Snapshot snapshot);
}
