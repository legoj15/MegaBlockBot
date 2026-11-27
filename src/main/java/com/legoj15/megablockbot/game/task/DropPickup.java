package com.legoj15.megablockbot.game.task;

import com.legoj15.megablockbot.game.body.BotPlayer;
import com.legoj15.megablockbot.game.nav.BotNavigator;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;

/**
 * Picking up what a task's work dropped: the leather a cow fell with, the wheat a crop gave. One instance belongs to
 * one task and is started anew for each fall.
 *
 * <p>The game takes an item into a player once it lies inside the pick-up box (the body's own box, a block wider each
 * way and half a block taller) and its pickup delay has run. So the work is getting that box onto the item and then
 * standing; the item is never moved. A fresh drop hops, rolls off its block and may be carried by water, and a second
 * may fall while the first is walked to, so the nearest drop is looked for anew every tick and nothing remembers where
 * one lay: a drop that leaves the box is walked after, it does not end the pick-up.
 *
 * <p>Getting there: far off, the navigator brings the body near, to within {@link #APPROACH} blocks and never into the
 * drop's own cell, which for a harvest taken by use is the cell the plant still stands in; the last stretch is the
 * body's own steps, which break nothing; steps that a block's edge stops end in the navigator being asked for the
 * drop's cell exactly. A drop the navigator cannot reach, or one that lies in the box and is not taken (no room for
 * it), is given up and another looked for. The pick-up has a time of its own, counted from the last drop that came
 * in, so a drop the water carries away cannot hold its task and a death's thirty stacks each get their walk, and a
 * bound that nothing starts again: however many drops come and go, the pick-up itself ends. However it ends, the
 * body's movement input is stood still and its look let go; a navigator command another owner is running is waited
 * for, never walked over.
 */
final class DropPickup {
    /**
     * Ticks a drop may lie in the pick-up box before it is given up. A dropped item cannot be taken for 10 ticks, one a
     * player threw for 40; half as much again on top.
     */
    static final int WAIT_TICKS = 60;
    /**
     * Ticks the pick-up may go on without the drop it is after coming in or going away: twenty seconds, several walks
     * of {@link #STEPS_ALLOWED} and a wait.
     */
    static final int BUDGET_TICKS = 400;
    /**
     * Ticks the whole pick-up may last from its {@code start}, whatever comes in or goes away and however often the
     * per-drop budget starts again: a drop that bobs across the edge of the search box is out of the box on one tick
     * and in on the next, and each leaving starts {@link #BUDGET_TICKS} anew, so nothing else would end the walk.
     * Five of those budgets: a death's many stacks, lying close together, each get their walk, and a pick-up this
     * long is over.
     */
    static final int HARD_BOUND_TICKS = 2000;
    /**
     * Past this many blocks from a drop, measured flat, the navigator walks the body towards it; nearer, the body walks
     * itself. {@link #APPROACH} plus the way a drop lies off its cell's centre, so an arrival always hands over to the
     * body's own steps.
     */
    static final double WALK = 3.5;
    /**
     * How near the drop's cell the navigator is asked to bring the body. Two, not one: berries pop inside their bush,
     * which may stand a block above the ground the body walks on, and no cell the body can stand in is within one
     * block of such a cell.
     */
    static final int APPROACH = 2;
    /** Steps of plain input the last stretch may take before the drop's cell is asked for exactly: two seconds. */
    static final int STEPS_ALLOWED = 40;

    private final Set<UUID> givenUp = new HashSet<>();
    /** The drop this pick-up is after; the counts below are for it and start again with another. */
    @Nullable
    private UUID after;
    private int waited;
    private int steps;
    private int ticks;
    /** Ticks since this pick-up began, never started again by anything: the bound that always ends it. */
    private int hardTicks;
    /** The navigator command this pick-up gave last, so another's failure is not taken for its own. */
    private long serial = -1;
    private boolean waiting;

    /** A new fall: nothing is given up yet and the time starts again. */
    void start() {
        givenUp.clear();
        after = null;
        waited = 0;
        steps = 0;
        ticks = 0;
        hardTicks = 0;
        serial = -1;
        waiting = false;
    }

    /** True while the last tick stood waiting for the game to take a drop: the task is waiting on the game. */
    boolean waiting() {
        return waiting;
    }

    /** The task was paused or stopped: it is no longer waiting on anything. */
    void rest() {
        waiting = false;
    }

    /**
     * One tick. True while a drop that is {@code wanted} within {@code where} is being walked to or waited for; false
     * when none is left to get (taken, gone, given up) or the time is spent. {@code owner} is the task, whose
     * navigator commands these are.
     */
    boolean tick(Object owner, BotPlayer body, Predicate<ItemEntity> wanted, AABB where) {
        waiting = false;
        ItemEntity pursued = null;
        ItemEntity drop = null;
        double nearest = Double.MAX_VALUE;
        for (ItemEntity candidate : body.serverLevel().getEntitiesOfClass(ItemEntity.class, where,
                item -> item.isAlive() && wanted.test(item))) {
            if (candidate.getUUID().equals(after)) pursued = candidate;
            if (givenUp.contains(candidate.getUUID())) continue;
            double distance = candidate.distanceToSqr(body);
            if (distance < nearest) {
                nearest = distance;
                drop = candidate;
            }
        }
        if (after != null && pursued == null) ticks = 0; // the one it was after is taken (or gone): the time starts again
        hardTicks++;
        if (++ticks > BUDGET_TICKS || hardTicks > HARD_BOUND_TICKS) {
            standStill(body);
            return false;
        }
        if (drop == null) {
            standStill(body);
            return false;
        }
        if (!drop.getUUID().equals(after)) {
            after = drop.getUUID();
            waited = 0;
            steps = 0;
            serial = -1;
        }
        if (box(body).intersects(drop.getBoundingBox())) {
            // The game takes it from here once its pickup delay has run: stand and let it come in.
            stopOurs(owner, body);
            stand(body);
            waiting = true;
            if (++waited > WAIT_TICKS) givenUp.add(drop.getUUID());
            return true;
        }
        waited = 0;
        double flatX = drop.getX() - body.getX();
        double flatZ = drop.getZ() - body.getZ();
        if (flatX * flatX + flatZ * flatZ > WALK * WALK) return walk(owner, body, drop, APPROACH);
        if (steps < STEPS_ALLOWED) {
            stopOurs(owner, body);
            body.input().lookAt(drop.position());
            body.input().forward = 1.0F;
            body.input().strafe = 0.0F;
            body.input().jump = false;
            body.input().sprint = false;
            steps++;
            return true;
        }
        return walk(owner, body, drop, 0);
    }

    /** The navigator's part: towards the drop's cell, to within {@code radius} of it. A walk that fails gives the drop up. */
    private boolean walk(Object owner, BotPlayer body, ItemEntity drop, int radius) {
        BotNavigator nav = BotNavigator.of(body);
        boolean ours = nav.serial() == serial;
        if (ours && nav.state() == BotNavigator.State.WORKING) return true;
        if (ours && nav.state() == BotNavigator.State.FAILED) {
            givenUp.add(drop.getUUID()); // not one this body can walk to: another is looked for
            return true;
        }
        if (nav.state() == BotNavigator.State.WORKING && nav.runsCommandOfAnother(owner)) {
            // Someone else's command is running (the reflex layer's, say): not this pick-up's to walk over, as it
            // is not its task's to stop. It is waited for, as a hunt waits, and the pick-up's own bound ends the
            // wait if the command never does.
            return true;
        }
        nav.issuingFor(owner).goTo(drop.blockPosition(), radius);
        serial = nav.serial();
        return true;
    }

    /** Stops the navigator when what runs is the owner's own command; the reflex layer's is not the task's to stop. */
    private static void stopOurs(Object owner, BotPlayer body) {
        BotNavigator nav = BotNavigator.of(body);
        if (nav.state() == BotNavigator.State.WORKING && !nav.runsCommandOfAnother(owner)) nav.stop();
    }

    /** The box the game takes items into a player from. */
    static AABB box(BotPlayer body) {
        return body.getBoundingBox().inflate(1.0, 0.5, 1.0);
    }

    private static void stand(BotPlayer body) {
        body.input().forward = 0.0F;
        body.input().strafe = 0.0F;
        body.input().jump = false;
        body.input().sprint = false;
    }

    /** The pick-up is over, however it ended: the body stands, and the look the walk wrote at a drop is let go. */
    private static void standStill(BotPlayer body) {
        stand(body);
        body.input().clearLook();
    }
}
