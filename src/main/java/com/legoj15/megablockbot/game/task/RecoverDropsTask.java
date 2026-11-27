package com.legoj15.megablockbot.game.task;

import com.legoj15.megablockbot.core.Pos;
import com.legoj15.megablockbot.core.plan.NodeSpec;
import com.legoj15.megablockbot.core.task.TickResult;
import com.legoj15.megablockbot.game.body.BotPlayer;
import com.legoj15.megablockbot.game.nav.BotNavigator;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;

/**
 * {@code recover_drops}: the node the executor puts in front of the new tree after a death (design 3.4, Failure DIED).
 * Walks to each place the death's items fell, waits a moment for them to be picked up, goes on to the next.
 *
 * <p>Parameters (written by the executor): {@code dimension} and {@code drops}, the positions as
 * {@code x,y,z} joined with {@code ;}. A position it cannot get to is skipped and so is every position in another
 * dimension (dimension travel is not built). It never fails the goal: the node is best-effort, a death's items are
 * recovered if they can be and the plan goes on without them if not, so this task always completes.
 *
 * <p>No checkpoint: the executor keys checkpoints by node identity, which two recover nodes of different deaths share,
 * and a restarted recovery that finds the places already emptied just walks on.
 */
final class RecoverDropsTask extends BodyTask {
    /** Items fall and roll, and the pickup range is a block around the body: get this close, not onto the block. */
    private static final int ARRIVAL_RADIUS = 1;
    /** Ticks to wait for pickup at a position that still has an item entity near it. A dropped item cannot be taken for ~10. */
    static final int PICKUP_WAIT_TICKS = 40;
    /** An item this close to the position still counts as one the body is waiting to pick up. */
    private static final double ITEM_NEAR = 3.0;

    private final String dimension;
    private final List<BlockPos> positions = new ArrayList<>();
    private int next;
    private int waited;
    private boolean waitingForPickup;

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
        // Whatever the body was doing at a position, it starts that position again: the items may have moved.
        walkToNext(body);
    }

    private void walkToNext(BotPlayer body) {
        waitingForPickup = false;
        waited = 0;
        if (next >= positions.size()) return;
        nav(body).goTo(positions.get(next), ARRIVAL_RADIUS);
    }

    @Override
    protected TickResult onTick(BotPlayer body) {
        if (next >= positions.size()) {
            release(body);
            return TickResult.COMPLETED;
        }
        BotNavigator nav = nav(body);
        if (waitingForPickup) {
            waited++;
            if (waited >= PICKUP_WAIT_TICKS || !itemNear(body, positions.get(next))) advance(body);
            return TickResult.RUNNING;
        }
        switch (nav.state()) {
            case DONE -> {
                waitingForPickup = true;
                waited = 0;
            }
            case FAILED, IDLE -> advance(body); // out of reach: skipped
            default -> { }
        }
        return TickResult.RUNNING;
    }

    private void advance(BotPlayer body) {
        next++;
        walkToNext(body);
    }

    /** True while an item entity lies near the position the body is at: it may still pick it up. */
    private static boolean itemNear(BotPlayer body, BlockPos pos) {
        AABB box = new AABB(pos).inflate(ITEM_NEAR);
        return !body.serverLevel().getEntitiesOfClass(ItemEntity.class, box, ItemEntity::isAlive).isEmpty();
    }

    @Override
    public boolean waiting() {
        return waitingForPickup;
    }

    @Override
    protected void letGo(BotPlayer body) {
        waitingForPickup = false;
    }
}
