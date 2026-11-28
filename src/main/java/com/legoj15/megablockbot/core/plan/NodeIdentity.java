package com.legoj15.megablockbot.core.plan;

import com.legoj15.megablockbot.core.goal.Goal;

/**
 * The identity checkpoints are keyed by (design 3.4, Persistence): task kind plus target, plus the source a gather
 * takes the target from. A replan carries a checkpoint over to a new node with the same identity and drops the rest.
 *
 * <p>The source is part of it because a checkpoint is the record of one search: how far it looked and how often says
 * nothing about another source of the same item. Without it a hunt for cows that ran dry handed its spent count to
 * the hunt for horses the replan put in its place, which gave up before its first look, and so did every source
 * after it.
 */
public final class NodeIdentity {
    private NodeIdentity() {}

    public static String of(NodeSpec node) {
        String source = node.source() == null || node.source().isEmpty() ? "" : "|" + node.source();
        return (node.taskKind() == null ? "group" : node.taskKind()) + "|" + target(node.goal()) + source;
    }

    /** The goal's target as one stable string: an item id, a tool class, a position. */
    public static String target(Goal goal) {
        return switch (goal) {
            case Goal.HaveItem g -> g.key().id();
            case Goal.HaveToolTier g -> g.toolClass().name();
            case Goal.Equip g -> "equip:" + new java.util.TreeMap<>(g.slots());
            case Goal.Stockpile g -> g.key().id() + "@" + g.container().encode();
            case Goal.Deliver g -> g.key().id() + "@" + g.recipient();
            case Goal.Build g -> g.blueprint() + "@" + g.anchor().encode();
            case Goal.Reach g -> g.dimension() + "@" + g.pos().encode();
            case Goal.Custom g -> g.kind() + ":" + new java.util.TreeMap<>(g.params());
        };
    }
}
