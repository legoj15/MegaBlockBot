package com.legoj15.megablockbot.core.plan;

import com.legoj15.megablockbot.core.goal.Goal;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One node of a planned goal tree, as the planner emits it (design 3.4, Goal tree). Immutable; the executor
 * wraps it in its own stateful node.
 *
 * <p>A leaf has a {@code taskKind} and no children: the executor asks the task factory for a task of that kind
 * with {@code params}. A non-leaf has children and a null {@code taskKind}; its state is derived from its
 * postcondition and its descendants. Children run in list order, depth first.
 *
 * @param goal       the sub-goal; its postcondition gates the node before it starts and after its task ends
 * @param taskKind   the task kind a leaf binds to, null for a non-leaf
 * @param params     task parameters as strings (positions through {@code Pos.encode()}); owned by the task kind
 * @param source     the chosen source or position, e.g. {@code mine:minecraft:iron_ore} or
 *                   {@code trade:minecraft:librarian}; empty when the node has no alternative. Part of the plan
 *                   fingerprint, and what a NO_SOURCE replan excludes
 * @param bestEffort a failed best-effort node is SKIPPED with no retry or replan
 */
public record NodeSpec(Goal goal, String taskKind, Map<String, String> params, String source, boolean bestEffort,
                       List<NodeSpec> children) {
    public NodeSpec {
        Objects.requireNonNull(goal, "goal");
        params = Map.copyOf(params);
        source = source == null ? "" : source;
        children = List.copyOf(children);
        if (taskKind != null && !children.isEmpty()) {
            throw new IllegalArgumentException("a node with a task kind is a leaf and has no children");
        }
        if (taskKind == null && children.isEmpty()) {
            throw new IllegalArgumentException("a node needs a task kind or children");
        }
    }

    public static NodeSpec leaf(Goal goal, String taskKind, Map<String, String> params, String source) {
        return new NodeSpec(goal, taskKind, params, source, false, List.of());
    }

    public static NodeSpec group(Goal goal, List<NodeSpec> children) {
        return new NodeSpec(goal, null, Map.of(), "", false, children);
    }

    public NodeSpec asBestEffort() {
        return new NodeSpec(goal, taskKind, params, source, true, children);
    }

    public boolean isLeaf() {
        return taskKind != null;
    }
}
