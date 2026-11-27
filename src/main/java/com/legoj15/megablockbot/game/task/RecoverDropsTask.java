package com.legoj15.megablockbot.game.task;

import com.legoj15.megablockbot.core.Pos;
import com.legoj15.megablockbot.core.plan.NodeSpec;
import com.legoj15.megablockbot.core.task.TickResult;
import com.legoj15.megablockbot.game.body.BotPlayer;
import com.legoj15.megablockbot.game.nav.BotNavigator;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

/**
 * {@code recover_drops}: the node the executor puts in front of the new tree after a death (design 3.4, Failure DIED).
 * Walks to each place the death's items fell and picks up what still lies there.
 *
 * <p>Parameters (written by the executor): {@code dimension} and {@code drops}, the positions as
 * {@code x,y,z} joined with {@code ;}. A position whose drops cannot be got at is skipped and so is every position in
 * another dimension (dimension travel is not built). It never fails the goal: the node is best-effort, a death's items
 * are recovered if they can be and the plan goes on without them if not, so this task always completes.
 *
 * <p><b>Where a drop lies now, not where it was recorded.</b> Items roll, fall and float: by the time the body is
 * back, a drop can lie a block or two from where the death record holds it, and many blocks lower when it fell into a
 * cave or sank. At each recorded position the task looks for item entities within {@link #SEARCH_RADIUS} blocks
 * horizontally and down to {@link #SEARCH_DEPTH} below it, and picks each up as every task picks up a drop
 * ({@link DropPickup}); a position with nothing within that radius is done at once. An item the body cannot get at is
 * given up, and so is one that lies in reach and is not taken; a drop that has been picked up or despawned (the task
 * cannot tell those apart) is never waited for.
 *
 * <p>No checkpoint: the executor keys checkpoints by node identity, which two recover nodes of different deaths share,
 * and a restarted recovery that finds the places already emptied just walks on.
 */
final class RecoverDropsTask extends BodyTask {
    /** Items fall and roll, and the pickup range is a block around the body: get this close, not onto the block. */
    private static final int ARRIVAL_RADIUS = 1;
    /** How far around a recorded position its drops are looked for, in blocks: items roll and float sideways. */
    private static final double SEARCH_RADIUS = 4.0;
    /** How far below a recorded position its drops are looked for, in blocks: an item can fall far into a cave. */
    private static final int SEARCH_DEPTH = 8;

    private final String dimension;
    private final List<BlockPos> positions = new ArrayList<>();
    private int next;
    /** False while the body walks to the recorded position; true once it looks around it for what lies there. */
    private boolean arrived;
    private final DropPickup pickup = new DropPickup();

    RecoverDropsTask(NodeSpec leaf) {
        this.dimension = required(leaf, "dimension");
        String drops = leaf.params().getOrDefault("drops", "");
        for (String text : drops.split(";")) {
            if (text.isBlank()) continue;
            try {
                positions.add(blockPos(Pos.parse(text)));
            } catch (IllegalArgumentException e) {
                // A position that does not parse is a drop that cannot be found: skipped, like one that cannot be reached.
            }
        }
    }

    @Override
    protected void begin(BotPlayer body) {
        if (!dimension.equals(dimensionOf(body))) {
            // The drops are somewhere the bot cannot go: nothing to recover, nothing to fail.
            next = positions.size();
            return;
        }
        walkToNext(body);
    }

    @Override
    protected void resumeWork(BotPlayer body) {
        // Whatever the body was doing at a position, it starts that position again: the items may have moved, and
        // what was out of reach may not be any more.
        walkToNext(body);
    }

    private void walkToNext(BotPlayer body) {
        arrived = false;
        pickup.rest();
        if (next >= positions.size()) return;
        nav(body).goTo(positions.get(next), ARRIVAL_RADIUS);
    }

    @Override
    protected TickResult onTick(BotPlayer body) {
        if (next >= positions.size()) {
            release(body);
            return TickResult.COMPLETED;
        }
        BlockPos at = positions.get(next);
        if (!arrived) {
            // The walk to the record comes first, however far: what lies there cannot be seen before its chunks are
            // loaded around the body. A record the body cannot get to is looked around for from where the walk ended.
            if (nav(body).state() == BotNavigator.State.WORKING) return TickResult.RUNNING;
            arrived = true;
            pickup.start();
        }
        if (pickup.tick(this, body, drop -> true, searchBox(at))) return TickResult.RUNNING;
        next++;
        walkToNext(body);
        return TickResult.RUNNING;
    }

    /** Where the drops of a recorded position are looked for: its own cell and {@link #SEARCH_RADIUS} blocks around it,
     *  and {@link #SEARCH_DEPTH} blocks below, where a fall or sinking water took them. */
    private static AABB searchBox(BlockPos pos) {
        return new AABB(pos.getX() - SEARCH_RADIUS, pos.getY() - SEARCH_DEPTH, pos.getZ() - SEARCH_RADIUS,
            pos.getX() + 1 + SEARCH_RADIUS, pos.getY() + 1 + SEARCH_RADIUS, pos.getZ() + 1 + SEARCH_RADIUS);
    }

    @Override
    public boolean waiting() {
        return pickup.waiting();
    }

    @Override
    protected void letGo(BotPlayer body) {
        pickup.rest();
    }
}
