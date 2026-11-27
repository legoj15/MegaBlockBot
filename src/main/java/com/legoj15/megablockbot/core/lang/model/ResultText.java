package com.legoj15.megablockbot.core.lang.model;

import com.legoj15.megablockbot.core.ItemKey;
import com.legoj15.megablockbot.core.Pos;
import com.legoj15.megablockbot.core.exec.GoalResult;
import com.legoj15.megablockbot.core.goal.ComponentCheck;
import com.legoj15.megablockbot.core.goal.Goal;
import com.legoj15.megablockbot.core.task.Failure;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Words for a goal and for the way it ended (design 3.3, failure explanations): the typed facts handed to the
 * model, and the built-in sentence the owner gets when the model cannot be asked. Both are made from the typed
 * {@link GoalResult} alone, and every {@code switch} here is over a sealed type with no default, so a goal or
 * failure kind added later fails to compile here instead of explaining itself as "something went wrong".
 */
final class ResultText {
    private ResultText() {}

    // ── goals ─────────────────────────────────────────────────────────────────────────────────────

    /** The goal in a short phrase: registry ids when {@code ids} (for the model), plain item names otherwise. */
    static String describe(Goal goal, boolean ids) {
        return switch (goal) {
            case Goal.HaveItem have -> {
                String text = "have " + have.count() + " " + item(have.key(), ids);
                ComponentCheck check = have.check();
                if (check == null) yield text;
                List<String> needs = new ArrayList<>();
                new TreeMap<>(check.minEnchantments()).forEach((id, level) -> needs.add(id + " level " + level));
                if (!check.potion().isEmpty()) needs.add("potion " + check.potion());
                yield text + " with " + String.join(" and ", needs);
            }
            case Goal.HaveToolTier tier -> "have a " + lower(tier.toolClass()) + " of at least " + lower(tier.tier()) + " tier";
            case Goal.Equip equip -> {
                List<String> slots = new ArrayList<>();
                new TreeMap<>(equip.slots()).forEach((slot, key) -> slots.add(item(key, ids) + " (" + lower(slot) + ")"));
                yield "equip " + String.join(", ", slots);
            }
            case Goal.Stockpile pile ->
                    "stockpile " + pile.count() + " " + item(pile.key(), ids) + " in the container at " + pos(pile.container());
            case Goal.Build build -> "build " + build.blueprint() + " at " + pos(build.anchor());
            case Goal.Reach reach -> "get within " + reach.radius() + " blocks of " + pos(reach.pos());
            case Goal.Custom custom ->
                    custom.kind() + (custom.params().isEmpty() ? "" : " " + new TreeMap<>(custom.params()));
        };
    }

    // ── facts for the model ───────────────────────────────────────────────────────────────────────

    /**
     * The typed result as lines of facts, registry ids and all: the goal, the status, how far it got, what was
     * unmet, and the failure's kind and every one of its fields. No exception object or stack trace goes in, only
     * its class name.
     */
    static String facts(GoalResult result) {
        StringBuilder out = new StringBuilder();
        out.append("Goal: ").append(describe(result.goal(), true)).append('\n');
        out.append("Status: ").append(result.status()).append('\n');
        out.append("Progress: matched ").append(result.matched()).append(" of ").append(result.required()).append('\n');
        if (!result.unmet().isEmpty()) out.append("Still missing: ").append(String.join(", ", result.unmet())).append('\n');
        Failure failure = result.failure();
        if (failure != null) {
            out.append("Failure kind: ").append(failure.kind()).append('\n');
            out.append("Failure fields: ").append(fields(failure)).append('\n');
        }
        return out.toString().stripTrailing();
    }

    private static String fields(Failure failure) {
        Map<String, Object> fields = new TreeMap<>();
        switch (failure) {
            case Failure.Unreachable f -> {
                fields.put("target", f.target());
                fields.put("navReason", f.navReason());
            }
            case Failure.TargetGone f -> fields.put("what", f.what());
            case Failure.MissingTool f -> {
                fields.put("toolClass", f.toolClass());
                fields.put("tier", f.tier());
            }
            case Failure.MissingInput f -> {
                fields.put("key", f.key());
                fields.put("count", f.count());
            }
            case Failure.NoSource f -> {
                fields.put("key", f.key());
                fields.put("source", f.source());
            }
            case Failure.Protected f -> {
                fields.put("pos", pos(f.pos()));
                fields.put("rule", f.rule());
            }
            case Failure.Hazard f -> {
                fields.put("hazard", f.hazard());
                fields.put("dimension", f.dimension());
                fields.put("pos", pos(f.pos()));
            }
            case Failure.TimedOut f -> fields.put("ticksUsed", f.ticksUsed());
            case Failure.NoProgress f -> fields.put("ticksUsed", f.ticksUsed());
            case Failure.Died f -> {
                fields.put("dimension", f.dimension());
                fields.put("deathPos", pos(f.deathPos()));
            }
            case Failure.PreemptStalled f -> fields.put("reflexCause", f.reflexCause());
            case Failure.NoPlace f -> {
                fields.put("what", f.what());
                fields.put("radius", f.radius());
            }
            case Failure.NoRoom f -> fields.put("key", f.key());
            // Only the exception's class: its message and stack trace are for logs, not for a model.
            case Failure.Internal f -> fields.put("exception",
                    f.exception() == null ? "none" : f.exception().getClass().getSimpleName());
        }
        fields.put("detail", failure.detail());
        return fields.toString();
    }

    // ── built-in sentences ────────────────────────────────────────────────────────────────────────

    /**
     * The sentence the owner gets when the model cannot be asked: the goal in plain words, why it ended (one
     * sentence per {@link Failure.Kind}), and how far it got. One for every ending that needs explaining: a
     * satisfied goal is never passed here.
     */
    static String builtIn(GoalResult result) {
        String goal = describe(result.goal(), false);
        if (result.status() == GoalResult.Status.CANCELLED) {
            return "I stopped working on that as asked (" + goal + ")" + progress(result) + ".";
        }
        String why = result.failure() == null
                ? "I ran out of ways to get the rest"
                : reason(result.failure());
        return "I could not finish (" + goal + "): " + why + progress(result) + ".";
    }

    private static String progress(GoalResult result) {
        return result.matched() > 0 ? ". I have " + result.matched() + " of " + result.required() : "";
    }

    private static String reason(Failure failure) {
        return switch (failure) {
            case Failure.Unreachable f -> "I could not find a way to get to " + f.target();
            case Failure.TargetGone f -> f.what() + " was gone when I got there";
            case Failure.MissingTool f -> "I do not have the " + lower(f.tier()) + " " + lower(f.toolClass()) + " this needs";
            case Failure.MissingInput f -> "I ran short of " + f.count() + " " + item(f.key(), false) + " that this needs";
            case Failure.NoSource f -> "I could not find anywhere to get " + item(f.key(), false);
            case Failure.Protected f -> "I will not break the block at " + pos(f.pos()) + " (" + f.rule() + ")";
            case Failure.Hazard f -> "it was too dangerous: " + f.hazard() + " near " + pos(f.pos());
            case Failure.TimedOut f -> "it took me too long, so I gave up";
            case Failure.NoProgress f -> "I was getting nowhere, so I stopped";
            case Failure.Died f -> "I died while working on it";
            case Failure.PreemptStalled f ->
                    "I kept being interrupted by " + f.reflexCause() + " and could not get back to it";
            case Failure.NoPlace f -> "I found nowhere within " + f.radius() + " blocks to " + f.what();
            case Failure.NoRoom f -> "I had no room in my inventory for " + item(f.key(), false);
            case Failure.Internal f -> "something went wrong on my side";
        };
    }

    // ── small pieces ──────────────────────────────────────────────────────────────────────────────

    private static String item(ItemKey key, boolean ids) {
        return ids ? key.id() : key.path().replace('_', ' ');
    }

    private static String pos(Pos pos) {
        return pos.x() + " " + pos.y() + " " + pos.z();
    }

    private static String lower(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
