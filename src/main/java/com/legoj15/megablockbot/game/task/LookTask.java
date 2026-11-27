package com.legoj15.megablockbot.game.task;

import com.legoj15.megablockbot.core.Pos;
import com.legoj15.megablockbot.core.goal.Goal;
import com.legoj15.megablockbot.core.plan.NodeSpec;
import com.legoj15.megablockbot.core.planner.LookInside;
import com.legoj15.megablockbot.core.task.Failure;
import com.legoj15.megablockbot.core.task.TickResult;
import com.legoj15.megablockbot.game.body.BotPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;

/**
 * {@code look_inside} (the one leaf of {@link LookInside}, the goal the body gives itself when its owner names a container
 * for it to take from): walk to the container, open it with the real menu, note what is in it, close it. It moves
 * nothing. A player cannot see into a closed chest, so this is the only way the body comes to know what a named
 * container holds; what it notes is what every later plan counts, until the next time it opens the container.
 *
 * <p>The same task is the plan of a stockpile goal whose target the body has never been inside ({@link
 * LookInside#leafBeforeDeposit}): that goal names the container, so the look asks block safety on the goal's word, as the
 * deposit does, and a claim still refuses it.
 *
 * <p>Leaf params: {@code container} (the position) and {@code dimension}. Failures are {@link ContainerTask}'s: the
 * container will not open is {@code Unreachable}, so a chest with a block on it is retried and then given up on, with
 * the owner told (the goal is the look, and there is no other source of the knowledge); the goal that follows the look
 * plans without that container, as it has nothing noted of it.
 */
final class LookTask extends ContainerTask {
    /** The look a stockpile goal's plan makes of its own target: the goal names the container, which is the owner's word for it. */
    private final boolean goalNamesIt;

    LookTask(NodeSpec leaf) {
        super(required(leaf, LookInside.PARAM_DIMENSION), Pos.parse(required(leaf, LookInside.PARAM_CONTAINER)));
        this.goalNamesIt = leaf.goal() instanceof Goal.Custom goal && LookInside.isBeforeDeposit(goal);
    }

    @Override
    protected boolean prepare(BotPlayer body) {
        return true;
    }

    @Override
    protected boolean askedOnTheGoalsWord() {
        return goalNamesIt;
    }

    @Override
    protected Failure wouldNotOpen(BotPlayer body) {
        return unreachable(containerPos.encode(), "container would not open",
            "using the container at " + containerPos.encode() + " opened no menu (blocked, locked, or out of reach)");
    }

    @Override
    protected TickResult inside(BotPlayer body, AbstractContainerMenu menu) {
        note(body, contentsOf(body, menu));
        return end(body, TickResult.COMPLETED);
    }
}
