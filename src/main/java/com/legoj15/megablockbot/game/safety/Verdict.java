package com.legoj15.megablockbot.game.safety;

import com.legoj15.megablockbot.core.Pos;
import com.legoj15.megablockbot.core.task.Failure;
import net.minecraft.core.BlockPos;

/**
 * The answer to "may this body break (or use) that block". Maps one to one onto {@link Failure.Protected}.
 * Allowed answers are the shared {@link #ALLOWED} instance, so the common case allocates nothing; the denied ones the
 * safety class hands out are shared constants too, except the protected-radius ones, which are built once per zone.
 *
 * @param rule one of the {@code SafetyConstants.RULE_*} names; empty when allowed
 * @param detail for logs and the owner only; never branch on it
 */
public record Verdict(boolean allowed, String rule, String detail) {
    public static final Verdict ALLOWED = new Verdict(true, SafetyConstants.RULE_NONE, "");

    public static Verdict deny(String rule, String detail) {
        return new Verdict(false, rule, detail);
    }

    /** The failure a task reports when it is refused. Only meaningful for a denied verdict. */
    public Failure.Protected toFailure(BlockPos pos) {
        return new Failure.Protected(new Pos(pos.getX(), pos.getY(), pos.getZ()), rule, detail);
    }
}
