package com.legoj15.megablockbot.game.task;

import com.legoj15.megablockbot.game.body.BotPlayer;
import com.legoj15.megablockbot.game.nav.BreakFilter;
import java.util.Objects;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;

/**
 * The one place tasks get the rule for which blocks the bot may break (design 3.5). Block safety is its own feature;
 * until it installs its rules the default allows everything. A task hands {@link #filter()} to its body's navigator
 * before it starts, and the filter it hands over reads the current rules on every question, so rules installed or
 * replaced while a task runs apply to it at once.
 *
 * <p>The filter is asked on path-search threads as well as the server thread (see {@link BreakFilter}); a rule
 * installed here must be thread-safe.
 */
public final class BreakRules {
    private static volatile BreakFilter rules = BreakFilter.ALLOW_ALL;
    private static final BreakFilter CURRENT = (level, pos, state) -> rules.mayBreak(level, pos, state);

    private BreakRules() {}

    /** A rule that depends on which body asks: block safety knows a body's owner and its own placements. */
    @FunctionalInterface
    public interface BodyRules {
        boolean mayBreak(BotPlayer body, ServerLevel level, BlockPos pos, BlockState state);
    }

    private static volatile BodyRules bodyRules = (body, level, pos, state) -> true;

    /** Installs the per-body rules (block safety does, at init). Asked on path-search threads too. */
    public static void setBodyRules(BodyRules newRules) {
        bodyRules = Objects.requireNonNull(newRules, "newRules");
    }

    /** The filter for one body's navigator: the general rules and the per-body rules, both as installed now. */
    public static BreakFilter filterFor(BotPlayer body) {
        return (level, pos, state) -> rules.mayBreak(level, pos, state) && bodyRules.mayBreak(body, level, pos, state);
    }

    /** The filter for a navigator: always the same object, answering with whatever rules are installed now. */
    public static BreakFilter filter() {
        return CURRENT;
    }

    /** Installs the rules the safety feature (or a test) wants applied from now on. */
    public static void set(BreakFilter newRules) {
        rules = Objects.requireNonNull(newRules, "newRules");
    }

    /** The rules installed now, for a caller that wants to restore them later. */
    public static BreakFilter installed() {
        return rules;
    }

    /** Back to the default: everything may be broken. */
    public static void reset() {
        rules = BreakFilter.ALLOW_ALL;
    }
}
