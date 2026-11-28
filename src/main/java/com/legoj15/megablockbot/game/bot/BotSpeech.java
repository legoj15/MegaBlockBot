package com.legoj15.megablockbot.game.bot;

import com.legoj15.megablockbot.core.EquipSlot;
import com.legoj15.megablockbot.core.ItemKey;
import com.legoj15.megablockbot.core.Pos;
import com.legoj15.megablockbot.core.exec.ExecutorView;
import com.legoj15.megablockbot.core.exec.GoalResult;
import com.legoj15.megablockbot.core.exec.NodeState;
import com.legoj15.megablockbot.core.goal.ComponentCheck;
import com.legoj15.megablockbot.core.goal.Goal;
import com.legoj15.megablockbot.core.planner.LookInside;
import com.legoj15.megablockbot.core.task.Failure;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * What a bot says, in plain words: a goal as the owner would have asked for it, a failure as a reason, a result as one
 * line, the executor's state as a status paragraph. Minecraft-free on purpose (it reads core types only); the runtime
 * decides who hears it. The words are for people: nothing reads them back, and routing never depends on them.
 */
public final class BotSpeech {
    private BotSpeech() {}

    /** An item as the owner says it: {@code oak planks}; another mod's items keep their namespace. */
    static String item(ItemKey key) {
        String path = key.path().replace('_', ' ');
        return key.namespace().equals("minecraft") ? path : key.namespace() + ":" + path;
    }

    /** The goal as a request: {@code get 4 oak planks}. A player's id is shown as it is. */
    public static String goal(Goal goal) {
        return goal(goal, id -> id);
    }

    /**
     * As above, with the players a goal names turned into names by {@code names} (their id when it answers null or
     * the id itself): the runtime knows the server, this class reads core types only.
     */
    public static String goal(Goal goal, java.util.function.Function<String, String> names) {
        if (goal == null) return "a goal that could not be read";
        return switch (goal) {
            case Goal.HaveItem g -> "get " + g.count() + " " + item(g.key()) + check(g.check());
            case Goal.HaveToolTier g -> "get a " + g.tier().name().toLowerCase(Locale.ROOT) + " "
                    + g.toolClass().name().toLowerCase(Locale.ROOT) + " or better";
            case Goal.Equip g -> "equip " + equipment(g.slots());
            case Goal.Stockpile g -> "put " + g.count() + " " + item(g.key()) + " into the container at "
                    + position(g.container());
            case Goal.Deliver g -> "give " + g.count() + " " + item(g.key()) + " to " + named(g.recipient(), names);
            case Goal.Build g -> "build " + g.blueprint() + " at " + position(g.anchor());
            case Goal.Reach g -> "go within " + g.radius() + " of " + position(g.pos());
            case Goal.Custom g -> LookInside.KIND.equals(g.kind()) && g.params().containsKey(LookInside.PARAM_CONTAINER)
                    ? "look inside the container at " + g.params().get(LookInside.PARAM_CONTAINER).replace(',', ' ')
                    : g.kind() + (g.params().isEmpty() ? "" : " " + new TreeMap<>(g.params()));
        };
    }

    /** The player's name {@code names} answers for an id, or the id when it has none. */
    private static String named(String id, java.util.function.Function<String, String> names) {
        String name = names.apply(id);
        return name == null || name.isBlank() ? id : name;
    }

    private static String check(ComponentCheck check) {
        if (check == null) return "";
        List<String> parts = new ArrayList<>();
        new TreeMap<>(check.minEnchantments()).forEach((id, level) -> parts.add(id.replace("minecraft:", "") + " " + level));
        if (!check.potion().isEmpty()) parts.add(check.potion().replace("minecraft:", ""));
        return parts.isEmpty() ? "" : " with " + String.join(", ", parts);
    }

    private static String equipment(Map<EquipSlot, ItemKey> slots) {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<EquipSlot, ItemKey> slot : new TreeMap<>(slots).entrySet()) {
            parts.add(item(slot.getValue()) + " (" + slot.getKey().name().toLowerCase(Locale.ROOT) + ")");
        }
        return String.join(", ", parts);
    }

    static String position(Pos pos) {
        return pos.x() + " " + pos.y() + " " + pos.z();
    }

    /** Why it failed, in words, with the failure's own detail after it when there is any. */
    static String reason(Failure failure) {
        if (failure == null) return "no reason was given";
        String what = switch (failure) {
            case Failure.Unreachable f -> "could not reach " + f.target() + (f.navReason().isEmpty() ? "" : " (" + f.navReason() + ")");
            case Failure.TargetGone f -> f.what() + " is gone or could not be found";
            case Failure.MissingTool f -> "needs " + (f.tier() == null ? "a" : "a " + f.tier().name().toLowerCase(Locale.ROOT))
                    + (f.toolClass() == null ? " tool" : " " + f.toolClass().name().toLowerCase(Locale.ROOT)) + " it does not have";
            case Failure.MissingInput f -> "is missing " + f.count() + " " + item(f.key());
            case Failure.NoSource f -> "nothing can supply " + item(f.key());
            case Failure.Protected f -> "may not touch " + position(f.pos()) + " (" + f.rule().replace('_', ' ') + ")";
            case Failure.Hazard f -> "was stopped by " + f.hazard() + " at " + position(f.pos());
            case Failure.TimedOut f -> "ran out of time after " + f.ticksUsed() + " ticks";
            case Failure.NoProgress f -> "made no progress for " + f.ticksUsed() + " ticks";
            case Failure.Died f -> "died at " + position(f.deathPos());
            case Failure.PreemptStalled f -> "was held up too long by " + f.reflexCause();
            case Failure.NoPlace f -> "found nowhere within " + f.radius() + " blocks to " + f.what();
            case Failure.NoRoom f -> "has no room in the inventory for " + item(f.key());
            case Failure.Internal f -> "hit an internal error";
        };
        String detail = failure.detail();
        return detail == null || detail.isBlank() ? what : what + ": " + detail;
    }

    /** One line for a finished goal: what was asked, how it ended, and for a failure the reason. */
    public static String result(GoalResult result) {
        return result(result, id -> id);
    }

    /** As above, with the players a goal names turned into names ({@link #goal(Goal, Function)}). */
    public static String result(GoalResult result, java.util.function.Function<String, String> names) {
        String asked = goal(result.goal(), names);
        return switch (result.status()) {
            case SATISFIED -> "Done: " + asked + ".";
            case CANCELLED -> "Cancelled: " + asked + ".";
            case PARTIAL -> "Gave up on \"" + asked + "\" with " + result.matched() + " of " + result.required()
                    + " done" + unmet(result) + ": " + reason(result.failure());
            case FAILED -> "Could not " + asked + unmet(result) + ": " + reason(result.failure());
        };
    }

    private static String unmet(GoalResult result) {
        return result.unmet().isEmpty() ? "" : " (still needed: " + String.join(", ", result.unmet()) + ")";
    }

    /** The one-paragraph status: which goal, which step, what waits. {@code lastResult} is the line of the last goal. */
    static String status(ExecutorView view, String lastResult) {
        return status(view, lastResult, -1, "");
    }

    /**
     * As above, with how long the body has been on the step ({@code stepTicks}, negative when unknown) and the phrase its
     * task gives for what it is doing now (empty for none): {@code On it for 2m 13s: heading for a block at 1 2 3.}
     */
    static String status(ExecutorView view, String lastResult, long stepTicks, String progress) {
        return status(view, lastResult, stepTicks, progress, id -> id);
    }

    /** As above, saying a player by name where a goal names one by id ({@code names}: id to name, or the id). */
    static String status(ExecutorView view, String lastResult, long stepTicks, String progress,
                         java.util.function.Function<String, String> names) {
        StringBuilder text = new StringBuilder();
        if (view.goal() == null) {
            text.append("Idle, no goal.");
            if (lastResult != null) text.append(" Last: ").append(lastResult);
        } else {
            text.append("Goal: ").append(goal(view.goal(), names)).append(" (")
                .append(view.status().name().toLowerCase(Locale.ROOT)).append(").");
            text.append(' ').append(step(view, names));
            text.append(onTheStep(stepTicks, progress));
            if (view.reflexHeld()) text.append(" A reflex holds the body.");
            if (view.totalReplans() > 0) text.append(" Replans: ").append(view.totalReplans()).append('.');
        }
        if (!view.queue().isEmpty()) {
            List<String> queued = new ArrayList<>();
            for (Goal queuedGoal : view.queue()) queued.add(goal(queuedGoal, names));
            text.append(" Queued: ").append(String.join("; ", queued)).append('.');
        }
        return text.toString();
    }

    /** {@code " On it for 2m 13s: heading for a block."}, or just the time, or just the phrase, or nothing. */
    private static String onTheStep(long stepTicks, String progress) {
        boolean phrase = progress != null && !progress.isBlank();
        if (stepTicks < 0) return phrase ? " Now: " + progress.strip() + "." : "";
        return " On it for " + duration(stepTicks) + (phrase ? ": " + progress.strip() + "." : ".");
    }

    /** Game ticks (twenty a second) as {@code 45s}, {@code 2m 13s} or {@code 1h 5m}. */
    static String duration(long ticks) {
        long seconds = Math.max(0, ticks) / 20;
        if (seconds < 60) return seconds + "s";
        if (seconds < 3600) return seconds / 60 + "m " + seconds % 60 + "s";
        return seconds / 3600 + "h " + seconds % 3600 / 60 + "m";
    }

    /**
     * The whole plan on one line, for the server log: every leaf in order with its task kind, its goal and the source
     * it gathers from, {@code 1 kill (get 3 leather) [kill:minecraft:cow]; 2 craft (get 1 book); ...}. Empty when the
     * view holds no leaf. A live run is read from the log afterwards, and the status line only ever shows one step.
     */
    public static String plan(ExecutorView view) {
        StringBuilder text = new StringBuilder();
        int index = 0;
        for (ExecutorView.NodeView node : view.nodes()) {
            if (node.taskKind() == null) continue;
            if (text.length() > 0) text.append("; ");
            text.append(++index).append(' ').append(node.taskKind()).append(" (").append(goal(node.goal())).append(')');
            if (node.source() != null && !node.source().isEmpty()) text.append(" [").append(node.source()).append(']');
        }
        return text.toString();
    }

    /** The step the bot is on, as its place among the plan's leaves: {@code Step 3 of 5: craft (get 4 stick).} */
    private static String step(ExecutorView view, java.util.function.Function<String, String> names) {
        List<ExecutorView.NodeView> leaves = new ArrayList<>();
        for (ExecutorView.NodeView node : view.nodes()) {
            if (node.taskKind() != null) leaves.add(node);
        }
        if (leaves.isEmpty()) return view.planPending() ? "Planning." : "No plan yet.";
        ExecutorView.NodeView current = null;
        int index = 0;
        for (int i = 0; i < leaves.size(); i++) {
            ExecutorView.NodeView leaf = leaves.get(i);
            if (leaf.state() == NodeState.ACTIVE) {
                current = leaf;
                index = i;
                break;
            }
            if (current == null && leaf.state() == NodeState.PENDING) {
                current = leaf;
                index = i;
            }
        }
        if (current == null) return "All " + leaves.size() + " steps are done.";
        String place = "Step " + (index + 1) + " of " + leaves.size() + (current.state() == NodeState.ACTIVE ? "" : " (next)");
        return place + ": " + current.taskKind() + " (" + goal(current.goal(), names) + ")." + (view.planPending() ? " Replanning." : "");
    }
}
