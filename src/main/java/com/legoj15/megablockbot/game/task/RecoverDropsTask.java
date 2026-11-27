package com.legoj15.megablockbot.game.task;

import com.legoj15.megablockbot.core.Pos;
import com.legoj15.megablockbot.core.plan.NodeSpec;
import com.legoj15.megablockbot.core.task.TickResult;
import com.legoj15.megablockbot.game.body.BotPlayer;
import com.legoj15.megablockbot.game.nav.BotNavigator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.ItemEntity;
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
 * horizontally and down to {@link #SEARCH_DEPTH} below it, and walks to each it finds at the item's own position, as
 * a player walks over an item; a position with nothing within that radius is done at once. An item the body cannot
 * get at is skipped, and so is one the pickup wait ran out on; a drop that has been picked up or despawned (the task
 * cannot tell those apart) is never waited for.
 *
 * <p>No checkpoint: the executor keys checkpoints by node identity, which two recover nodes of different deaths share,
 * and a restarted recovery that finds the places already emptied just walks on.
 */
final class RecoverDropsTask extends BodyTask {
    /** Items fall and roll, and the pickup range is a block around the body: get this close, not onto the block. */
    private static final int ARRIVAL_RADIUS = 1;
    /** Ticks to wait for pickup at an item the body has walked to. A dropped item cannot be taken for ~10. */
    static final int PICKUP_WAIT_TICKS = 40;
    /** How far around a recorded position its drops are looked for, in blocks: items roll and float sideways. */
    private static final double SEARCH_RADIUS = 4.0;
    /** How far below a recorded position its drops are looked for, in blocks: an item can fall far into a cave. */
    private static final int SEARCH_DEPTH = 8;
    /** An item this close to the body still counts as one it may pick up. */
    private static final double ITEM_NEAR = 3.0;

    private final String dimension;
    private final List<BlockPos> positions = new ArrayList<>();
    private int next;
    private int waited;
    private boolean waitingForPickup;
    /** The item this position's work is on, by entity id; null while the task only walks to the recorded position. */
    @Nullable
    private UUID target;
    /** The items of the position being worked that the body could not get at, or could not pick up in its wait. */
    private final Set<UUID> skipped = new HashSet<>();

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
        skipped.clear();
        walkToNext(body);
    }

    private void walkToNext(BotPlayer body) {
        waitingForPickup = false;
        waited = 0;
        target = null;
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
        BlockPos at = positions.get(next);
        if (waitingForPickup) {
            waited++;
            // Picked up, despawned or rolled away: whatever it was, the body is not waiting the wait out for it.
            if (!itemNear(body)) return nextDrop(body, at);
            if (waited >= PICKUP_WAIT_TICKS) {
                // The item had its time and is still there: not one this body can pick up.
                if (target != null) skipped.add(target);
                return nextDrop(body, at);
            }
            return TickResult.RUNNING;
        }
        switch (nav.state()) {
            case DONE -> {
                // Arrived, at the recorded position or at an item: look around from where the body stands.
                return nextDrop(body, at);
            }
            case FAILED, IDLE -> {
                // What the body headed for cannot be got at: the item is skipped, like one that despawned.
                if (target != null) skipped.add(target);
                return nextDrop(body, at);
            }
            default -> { }
        }
        return TickResult.RUNNING;
    }

    /**
     * The next drop of the position being worked, nearest of those found first: walked to at its own position, or
     * waited for where the body stands when it is already on it. None within the radius: the position is done and the
     * next one begins.
     */
    private TickResult nextDrop(BotPlayer body, BlockPos at) {
        waitingForPickup = false;
        waited = 0;
        ItemEntity nearest = null;
        for (ItemEntity drop : body.serverLevel().getEntitiesOfClass(ItemEntity.class, searchBox(at), ItemEntity::isAlive)) {
            if (skipped.contains(drop.getUUID())) continue;
            if (nearest == null || body.distanceToSqr(drop) < body.distanceToSqr(nearest)) nearest = drop;
        }
        if (nearest == null) {
            next++;
            walkToNext(body);
            return TickResult.RUNNING;
        }
        target = nearest.getUUID();
        // A player takes an item that lies in the box a block around them and half a block above and below: only then
        // is there nothing to walk to, only the pickup to wait for. An item two blocks off is near and is not that.
        if (body.getBoundingBox().inflate(1.0, 0.5, 1.0).intersects(nearest.getBoundingBox())) {
            waitingForPickup = true;
            waited = 0;
            return TickResult.RUNNING;
        }
        // Onto the item's own block: a goal a block wide is met from beside it, or from the edge above it.
        nav(body).goTo(nearest.blockPosition(), 0);
        return TickResult.RUNNING;
    }

    /** Where the drops of a recorded position are looked for: its own cell and {@link #SEARCH_RADIUS} blocks around it,
     *  and {@link #SEARCH_DEPTH} blocks below, where a fall or sinking water took them. */
    private static AABB searchBox(BlockPos pos) {
        return new AABB(pos.getX() - SEARCH_RADIUS, pos.getY() - SEARCH_DEPTH, pos.getZ() - SEARCH_RADIUS,
            pos.getX() + 1 + SEARCH_RADIUS, pos.getY() + 1 + SEARCH_RADIUS, pos.getZ() + 1 + SEARCH_RADIUS);
    }

    /** True while an item entity lies near the body: it may still pick it up. */
    private static boolean itemNear(BotPlayer body) {
        AABB box = body.getBoundingBox().inflate(ITEM_NEAR);
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
