package com.legoj15.megablockbot.game.task;

import com.legoj15.megablockbot.core.Pos;
import com.legoj15.megablockbot.core.plan.NodeSpec;
import com.legoj15.megablockbot.core.task.AbstractTask;
import com.legoj15.megablockbot.core.task.Failure;
import com.legoj15.megablockbot.game.body.BotPlayer;
import com.legoj15.megablockbot.game.nav.BotNavigator;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

/**
 * What the tasks of the body share: the navigator is the only thing that moves the body, it is armed with the break
 * rules before anything starts, and every way a task stops (aborted, paused, or having ended itself) lets go of the
 * body. The executor does not abort a task that ended on its own (design 3.4), so each subclass calls
 * {@link #release} itself before it returns a result.
 *
 * <p>Subclasses fill {@link #begin}, {@link #onTick} and, when they hold more than the navigator,
 * {@link #letGo} and {@link #resumeWork}.
 */
abstract class BodyTask extends AbstractTask<BotPlayer> {
    private boolean began;

    /** Arms the navigator, then starts the work; the work may veto with {@link #fail}. */
    @Override
    protected final void onStart(BotPlayer body) {
        began = true;
        arm(body);
        begin(body);
    }

    protected abstract void begin(BotPlayer body);

    /** The task is paused: the body stops. Nothing is kept running behind the executor's back. */
    @Override
    protected final void onPause(BotPlayer body, String reason) {
        release(body);
    }

    /** Re-acquires everything from the world as it is now: the body may have moved, and blocks may have changed. */
    @Override
    protected final void onResume(BotPlayer body) {
        arm(body);
        resumeWork(body);
    }

    protected abstract void resumeWork(BotPlayer body);

    @Override
    protected final void onAbort(BotPlayer body, String reason) {
        release(body);
    }

    /**
     * Stops the body and lets go of anything else this task holds. Safe to call twice, and before the task began. The
     * navigator is stopped only when what it runs is this task's own command (or nothing): in one body tick the reflex layer
     * runs first and may have begun a retreat by the time the executor pauses this task for it, and that retreat is not
     * this task's to stop. The task cannot see the end of its own tick (its ticks are the subclasses'), so the navigator
     * remembers who gave each command ({@link #nav}) instead of the task remembering which one was current.
     */
    protected final void release(BotPlayer body) {
        if (!began) return;
        letGo(body);
        BotNavigator navigator = BotNavigator.of(body);
        if (!navigator.runsCommandOfAnother(this)) navigator.stop();
    }

    /** Whatever the task holds besides the navigator (an open menu, a look target). */
    protected void letGo(BotPlayer body) {}

    /** The navigator, told that the command the caller gives next is this task's. */
    protected final BotNavigator nav(BotPlayer body) {
        return BotNavigator.of(body).issuingFor(this);
    }

    /** The rule for what may be broken comes from one place, and every task that moves the body takes it from there. */
    private void arm(BotPlayer body) {
        BotNavigator.of(body).setBreakFilter(BreakRules.filterFor(body));
    }

    // --- helpers for reading a leaf's parameters ----------------------------------------------------

    protected static String dimensionOf(BotPlayer body) {
        return body.level().dimension().location().toString();
    }

    protected static BlockPos blockPos(Pos pos) {
        return new BlockPos(pos.x(), pos.y(), pos.z());
    }

    protected static Pos corePos(BlockPos pos) {
        return new Pos(pos.getX(), pos.getY(), pos.getZ());
    }

    protected static String required(NodeSpec leaf, String key) {
        String value = leaf.params().get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(leaf.taskKind() + " leaf has no '" + key + "' parameter");
        }
        return value;
    }

    protected static int intParam(Map<String, String> params, String key, int fallback) {
        String value = params.get(key);
        if (value == null || value.isBlank()) return fallback;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("parameter '" + key + "' is not a number: " + value);
        }
    }

    /** A registry id, or null when it is not one. */
    protected static ResourceLocation idOf(String text) {
        return ResourceLocation.tryParse(text.trim());
    }

    protected static Failure unreachable(String target, String reason, String detail) {
        return new Failure.Unreachable(target, reason, detail);
    }
}
