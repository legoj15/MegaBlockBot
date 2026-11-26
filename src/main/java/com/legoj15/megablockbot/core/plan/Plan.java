package com.legoj15.megablockbot.core.plan;

import java.util.List;

/**
 * A goal tree for one goal.
 *
 * @param roots            top-level nodes, run in order
 * @param inputFingerprint hash of the inputs the planner actually read (design 3.4, Replanning): counts of the
 *                         item keys in the shortfall and its recipe closure, tool tiers, known stations. The
 *                         executor adopts the plan only if {@link Planner#inputFingerprint} on a fresh snapshot
 *                         still gives this value
 * @param maxTotalReplans  a higher per-goal replan total a strategy with long random phases declares, or 0 for
 *                         the default; the executor caps it at the server ceiling
 * @param notes            planner remarks for the log and the owner ("needs a Pulverizer recipe adapter")
 */
public record Plan(List<NodeSpec> roots, long inputFingerprint, int maxTotalReplans, List<String> notes) {
    public Plan {
        roots = List.copyOf(roots);
        notes = List.copyOf(notes);
    }
}
