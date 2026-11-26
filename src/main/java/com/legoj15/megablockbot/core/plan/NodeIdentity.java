package com.legoj15.megablockbot.core.plan;

import com.legoj15.megablockbot.core.goal.Goal;

/**
 * The identity checkpoints are keyed by (design 3.4, Persistence): task kind plus target. A replan carries a
 * checkpoint over to a new node with the same identity and drops the rest.
 */
public final class NodeIdentity {
    private NodeIdentity() {}

    public static String of(NodeSpec node) {
        return (node.taskKind() == null ? "group" : node.taskKind()) + "|" + target(node.goal());
    }

    /** The goal's target as one stable string: an item id, a tool class, a position. */
    public static String target(Goal goal) {
        return switch (goal) {
            case Goal.HaveItem g -> g.key().id();
            case Goal.HaveToolTier g -> g.toolClass().name();
            case Goal.Equip g -> "equip:" + new java.util.TreeMap<>(g.slots());
            case Goal.Stockpile g -> g.key().id() + "@" + g.container().encode();
            case Goal.Build g -> g.blueprint() + "@" + g.anchor().encode();
            case Goal.Reach g -> g.dimension() + "@" + g.pos().encode();
            case Goal.Custom g -> g.kind() + ":" + new java.util.TreeMap<>(g.params());
        };
    }
}
