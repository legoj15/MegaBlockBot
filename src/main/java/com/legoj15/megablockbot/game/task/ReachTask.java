package com.legoj15.megablockbot.game.task;

import com.legoj15.megablockbot.core.Pos;
import com.legoj15.megablockbot.core.goal.Goal;
import com.legoj15.megablockbot.core.plan.NodeSpec;
import com.legoj15.megablockbot.core.task.TickResult;
import com.legoj15.megablockbot.game.body.BotPlayer;
import com.legoj15.megablockbot.game.nav.BotNavigator;
import net.minecraft.core.BlockPos;

/**
 * {@code navigate} (a planner leaf with a {@link Goal.Reach} goal) and {@code reach} (the node the executor inserts to
 * bring something into snapshot range, with the position and dimension as parameters): walk the body to a place. The
 * navigator does the walking, digging and bridging; this reports how it went.
 *
 * <p>The target is the {@code pos} and {@code dimension} parameters when the leaf has them, else the goal's. The radius
 * is the goal's (the executor's reach node asks for a few blocks, a planner's navigate leaf for what it needs), else the
 * {@code radius} parameter, else the block itself.
 *
 * <p>Failures: a path the navigator cannot find, or loses, is {@code Unreachable} with the navigator's reason; a
 * target in another dimension is {@code Unreachable} saying so, since dimension travel is not built.
 */
final class ReachTask extends BodyTask {
    private final String dimension;
    private final Pos target;
    private final int radius;

    ReachTask(NodeSpec leaf) {
        Goal.Reach reach = leaf.goal() instanceof Goal.Reach r ? r : null;
        String posText = leaf.params().get("pos");
        String dimText = leaf.params().get("dimension");
        if (posText == null && reach == null) throw new IllegalArgumentException(leaf.taskKind() + " leaf has no 'pos' and no Reach goal");
        if (dimText == null && reach == null) throw new IllegalArgumentException(leaf.taskKind() + " leaf has no 'dimension' and no Reach goal");
        this.target = posText != null ? Pos.parse(posText) : reach.pos();
        this.dimension = dimText != null ? dimText : reach.dimension();
        this.radius = reach != null ? reach.radius() : Math.max(0, intParam(leaf.params(), "radius", 0));
    }

    @Override
    protected void begin(BotPlayer body) {
        if (!dimension.equals(dimensionOf(body))) {
            fail(unreachable(target.encode(), "different dimension",
                "the body is in " + dimensionOf(body) + ", the target in " + dimension + "; dimension travel is not built"));
            return;
        }
        go(body);
    }

    @Override
    protected void resumeWork(BotPlayer body) {
        // Re-path from wherever the body is now; the target is a place, not something that moved.
        go(body);
    }

    private void go(BotPlayer body) {
        BlockPos at = blockPos(target);
        nav(body).goTo(at, radius);
    }

    @Override
    protected TickResult onTick(BotPlayer body) {
        BotNavigator nav = nav(body);
        switch (nav.state()) {
            case DONE -> {
                release(body);
                return TickResult.COMPLETED;
            }
            case FAILED -> {
                String reason = nav.failure();
                release(body);
                return TickResult.failed(unreachable(target.encode(), reason,
                    "no way to " + target.encode() + " in " + dimension + ": " + reason));
            }
            case IDLE -> go(body); // something else stopped the navigator: this task still owns the body
            case WORKING -> { }
        }
        return TickResult.RUNNING;
    }
}
