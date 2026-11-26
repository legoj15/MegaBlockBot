package com.legoj15.megablockbot.core.goal;

import java.util.List;

/**
 * A postcondition's answer (design 3.4). UNKNOWN means the snapshot cannot tell (a container out of range or
 * in an unloaded chunk) and is never treated as done.
 *
 * @param matched  how much of the requirement holds now
 * @param required how much the goal asks for
 * @param unmet    what is still missing, as keys the planner and the owner can read (item ids, slot names,
 *                 a position)
 */
public record Evaluation(Status status, int matched, int required, List<String> unmet) {
    public enum Status { SATISFIED, UNSATISFIED, UNKNOWN }

    public Evaluation {
        unmet = List.copyOf(unmet);
    }

    public static Evaluation satisfied(int required) {
        return new Evaluation(Status.SATISFIED, required, required, List.of());
    }

    public static Evaluation unsatisfied(int matched, int required, List<String> unmet) {
        return new Evaluation(Status.UNSATISFIED, matched, required, unmet);
    }

    public static Evaluation unknown(int required, String what) {
        return new Evaluation(Status.UNKNOWN, 0, required, List.of(what));
    }

    public boolean isSatisfied() {
        return status == Status.SATISFIED;
    }

    public boolean isUnknown() {
        return status == Status.UNKNOWN;
    }
}
