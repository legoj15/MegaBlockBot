package com.legoj15.megablockbot.game.task;

import com.legoj15.megablockbot.MegaBlockBot;
import com.legoj15.megablockbot.core.ItemKey;
import com.legoj15.megablockbot.core.goal.Goal;
import com.legoj15.megablockbot.core.plan.NodeSpec;
import com.legoj15.megablockbot.core.task.Failure;
import com.legoj15.megablockbot.core.task.TickResult;
import com.legoj15.megablockbot.game.body.BotPlayer;
import com.legoj15.megablockbot.game.nav.BotNavigator;
import com.legoj15.megablockbot.game.plan.BotMemory;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.phys.Vec3;

/**
 * {@code deliver} (goal {@link Goal.Deliver}, issue #19): walk to the player the goal names and drop the items at
 * their feet, as a player drops with the drop key. The body cannot put anything into another player's inventory —
 * what a player carries is theirs to decide — so the hand-over is the drop itself, a stack at a time, thrown where
 * the body is looking: it faces the recipient first, and the count that has really left the inventory is the count
 * that was given.
 *
 * <p>The recipient may move, so the approach is a follow (as the kill task follows its quarry), bounded by a watch
 * of the chase: a recipient who keeps away for {@link #CHASE_LIMIT_TICKS} ticks ends the leaf {@code Unreachable}.
 * What was already handed over counts: the task drops only what the goal still owes, and each stack it does drop is
 * noted in the body's memory at once (recipient, item, count, the game time), so a paused, restarted or replanned
 * delivery goes on from the truth, and the goal's postcondition is read from those notes.
 *
 * <p>Failures: {@code Unreachable} when the recipient is not online or stands in another dimension (saying which),
 * or keeps away past the chase bound; {@code MissingInput} when the body carries fewer than are still owed — with
 * what it carries kept, for nothing is dropped unless all of it can be.
 */
final class DeliverTask extends BodyTask {
    /** Close enough to hand things over: three blocks, as the goal's words have it. */
    private static final double DELIVER_RADIUS = 3.0;
    /** The follow command keeps the body within this many blocks of the recipient it follows. */
    private static final int FOLLOW_DISTANCE = 2;
    /**
     * Ticks a recipient neither comes within reach nor gets closer before the leaf gives up on them. A follow command
     * never fails for want of a path (it keeps trying), so this watch is what ends a chase after a player who keeps
     * away; the navigator's own stall bound for one block is the same number.
     */
    private static final int CHASE_LIMIT_TICKS = BotNavigator.TARGET_STALL_TICKS;
    /** Getting this much closer counts as a gain; less is the two of them swaying about. */
    private static final double CLOSER_BY = 0.5;

    private enum Phase { APPROACHING, DROPPING }

    private final ItemKey itemKey;
    private final String recipientId;
    private final int count;
    private final long since;

    private Item item;
    private Phase phase = Phase.APPROACHING;
    @Nullable private ServerPlayer recipient;
    private String recipientName = "";
    /** How many have been handed over by this task; the memory holds the count across everything. */
    private int dropped;
    private double bestDistance = Double.MAX_VALUE;
    private int noProgress;

    DeliverTask(NodeSpec leaf) {
        Goal.Deliver goal = leaf.goal() instanceof Goal.Deliver d ? d : null;
        if (goal == null) throw new IllegalArgumentException("deliver leaf has no Deliver goal");
        this.itemKey = goal.key();
        this.recipientId = leaf.params().getOrDefault("recipient", goal.recipient());
        this.count = intParam(leaf.params(), "count", goal.count());
        this.since = goal.since();
        if (count < 1) throw new IllegalArgumentException("deliver count must be positive");
    }

    // ---------------------------------------------------------------------------------------------------------
    // Start
    // ---------------------------------------------------------------------------------------------------------

    @Override
    protected void begin(BotPlayer body) {
        ResourceLocation id = idOf(itemKey.id());
        item = id == null ? null : BuiltInRegistries.ITEM.getOptional(id).orElse(null);
        if (item == null) {
            fail(new Failure.MissingInput(itemKey, count, "'" + itemKey.id() + "' is not a registered item"));
            return;
        }
        recipient = findRecipient(body);
        if (recipient == null) {
            fail(unreachable(recipientId, goneReason(body), "cannot deliver to " + recipientId + ": "
                + goneReason(body)));
            return;
        }
        recipientName = recipient.getGameProfile().getName();
        // What earlier runs of this goal already handed over is not owed again (the notes are the goal's own count).
        int owed = count - delivered(body);
        int held = held(body);
        if (owed <= 0) return; // nothing to do, and nothing is touched: the first tick says so
        if (held < owed) {
            fail(missing(body, owed));
        }
    }

    @Override
    protected void resumeWork(BotPlayer body) {
        // Everything is looked at again from the world: the recipient may have moved or gone, and what the body
        // carries and has already handed over is counted anew.
        recipient = null;
        recipientName = "";
        bestDistance = Double.MAX_VALUE;
        noProgress = 0;
        phase = Phase.APPROACHING;
    }

    private int held(BotPlayer body) {
        return body.getInventory().countItem(item);
    }

    /** How many of the item the body has noted handing to this recipient since the goal was given. */
    private int delivered(BotPlayer body) {
        return BotMemory.of(body.getUUID()).deliveredSince(recipientId, itemKey, since);
    }

    private Failure missing(BotPlayer body, int owed) {
        return new Failure.MissingInput(itemKey, owed, "the body holds " + held(body) + " of " + itemKey.id() + ", "
            + owed + " to deliver to " + (recipientName.isEmpty() ? recipientId : recipientName));
    }

    // ---------------------------------------------------------------------------------------------------------
    // The recipient
    // ---------------------------------------------------------------------------------------------------------

    /**
     * The player the goal names, in the body's own level, or null when there is no such player there. The level's
     * own players are the ones standing in this dimension — a player who left the world is out of every level, and
     * one in another dimension is in another level's — so the one question answers both of the goal's conditions.
     */
    @Nullable
    private ServerPlayer findRecipient(BotPlayer body) {
        UUID id = uuid();
        if (id == null) return null;
        MinecraftServer server = body.getServer();
        if (server != null) {
            ServerPlayer online = server.getPlayerList().getPlayer(id);
            if (online != null && online.level() == body.level()) return online;
        }
        for (ServerPlayer player : body.serverLevel().players()) {
            if (player.getUUID().equals(id)) return player;
        }
        Entity anywhere = body.serverLevel().getEntity(id);
        return anywhere instanceof ServerPlayer player ? player : null;
    }

    /** The goal's recipient as a UUID, or null when the text is not one. */
    @Nullable
    private UUID uuid() {
        try {
            return UUID.fromString(recipientId);
        } catch (IllegalArgumentException notAnId) {
            return null;
        }
    }

    /**
     * Why the recipient cannot be delivered to just now: online but in another dimension, or not online at all (the
     * server's own player list says which, for a player {@link #findRecipient} could not find in this level).
     */
    private String goneReason(BotPlayer body) {
        UUID id = uuid();
        if (id != null && body.getServer() != null) {
            ServerPlayer elsewhere = body.getServer().getPlayerList().getPlayer(id);
            if (elsewhere != null && elsewhere.level() != body.level()) return "different dimension";
        }
        return "not online";
    }

    // ---------------------------------------------------------------------------------------------------------
    // Tick
    // ---------------------------------------------------------------------------------------------------------

    @Override
    protected TickResult onTick(BotPlayer body) {
        if (item == null) return TickResult.COMPLETED; // begin already failed; the executor saw it
        int owed = count - delivered(body);
        if (owed <= 0) {
            release(body);
            return TickResult.COMPLETED;
        }
        recipient = findRecipient(body);
        if (recipient == null) {
            release(body);
            return TickResult.failed(unreachable(recipientId, goneReason(body),
                "the player to deliver to (" + recipientId + ") is " + goneReason(body)));
        }
        recipientName = recipient.getGameProfile().getName();
        if (held(body) < owed) {
            release(body);
            return TickResult.failed(missing(body, owed));
        }
        return switch (phase) {
            case APPROACHING -> approach(body);
            case DROPPING -> drop(body);
        };
    }

    private TickResult approach(BotPlayer body) {
        BotNavigator nav = nav(body);
        double distance = recipient.distanceTo(body);
        if (distance <= DELIVER_RADIUS) {
            stopOurCommand(nav);
            phase = Phase.DROPPING;
            return drop(body);
        }
        TickResult stalled = watchTheChase(body, distance);
        if (stalled != null) return stalled;
        if (nav.state() != BotNavigator.State.WORKING) {
            nav.follow(recipient, FOLLOW_DISTANCE);
        }
        return TickResult.RUNNING;
    }

    /**
     * Whether the chase is going anywhere: it is while the body keeps getting closer. A follow command never fails
     * for want of a path, so this watch is what ends a delivery after a recipient who keeps away. Null while it goes
     * on.
     */
    @Nullable
    private TickResult watchTheChase(BotPlayer body, double distance) {
        // A gain is measured against where the last gain left the body, not against the tick before: a walk covers
        // a fifth of a block a tick, which is never a gain on its own and is one every three ticks.
        if (distance < bestDistance - CLOSER_BY) {
            bestDistance = distance;
            noProgress = 0;
            return null;
        }
        if (++noProgress <= CHASE_LIMIT_TICKS) return null;
        release(body);
        return TickResult.failed(unreachable(recipientName.isEmpty() ? recipientId : recipientName, "kept away",
            "could not get within " + (int) DELIVER_RADIUS + " blocks of "
                + (recipientName.isEmpty() ? recipientId : recipientName) + " in " + CHASE_LIMIT_TICKS + " ticks"));
    }

    /** Faces the recipient and drops one stack toward them, counting what really left the inventory. */
    private TickResult drop(BotPlayer body) {
        if (recipient.distanceTo(body) > DELIVER_RADIUS) {
            // They moved off (a player does): after them again.
            phase = Phase.APPROACHING;
            bestDistance = Double.MAX_VALUE;
            noProgress = 0;
            return TickResult.RUNNING;
        }
        stopOurCommand(nav(body));
        Inventory inventory = body.getInventory();
        int owed = count - delivered(body);
        boolean gave = false;
        for (int slot = 0; slot < inventory.items.size(); slot++) {
            ItemStack stack = inventory.items.get(slot);
            if (!stack.is(item)) continue;
            int before = inventory.countItem(item);
            int ofIt = Math.min(Math.min(owed, stack.getCount()), stack.getMaxStackSize());
            if (ofIt < 1) break;
            // Face them, then throw the stack where the body is looking, as the drop key does: both the input (what
            // the body will keep looking at) and the rotation (what this drop is aimed by, now).
            Vec3 aim = recipient.getEyePosition();
            body.input().lookAt(aim);
            body.lookAt(EntityAnchorArgument.Anchor.EYES, aim);
            ItemStack toGive = stack.copyWithCount(ofIt);
            inventory.removeItem(slot, ofIt);
            boolean thrown = body.drop(toGive, false, false) != null;
            // A drop the game refused threw nothing: the stack goes back where it came from, so nothing is lost.
            if (!thrown) inventory.placeItemBackInInventory(toGive);
            if (!thrown || inventory.countItem(item) != before - ofIt) {
                release(body);
                return TickResult.failed(new Failure.Internal(new IllegalStateException(
                    "dropping " + ofIt + " of " + itemKey.id() + " did not leave the inventory"),
                    "the drop of " + ofIt + " " + itemKey.id() + " did not leave the inventory"));
            }
            dropped += ofIt;
            gave = true;
            // Noted under the goal's own stamp, which is what the goal is judged by.
            BotMemory.of(body.getUUID()).noteDelivery(recipientId, itemKey, ofIt, since);
            MegaBlockBot.LOGGER.info("{} handed {} {} to {}",
                body.getGameProfile().getName(), ofIt, itemKey.id(), recipientName);
            break; // one stack a tick, as a player drops
        }
        if (!gave) {
            // Counted somewhere the hand cannot reach (worn, or in the off hand): the craft task's own rule.
            release(body);
            return TickResult.failed(missing(body, owed));
        }
        if (delivered(body) >= count) {
            release(body);
            return TickResult.COMPLETED;
        }
        return TickResult.RUNNING;
    }

    /** Stops the command this task gave, and nobody else's: the reflex layer's is not this task's to stop. */
    private void stopOurCommand(BotNavigator nav) {
        if (!nav.runsCommandOfAnother(this)) nav.stop();
    }

    // ---------------------------------------------------------------------------------------------------------

    @Override
    protected void letGo(BotPlayer body) {
        body.input().reset();
    }

    /** What the task is doing now, for the status line. */
    @Override
    public String progress() {
        if (item == null) return ""; // nothing is known before the task starts
        String who = recipientName.isEmpty() ? recipientId : recipientName;
        return switch (phase) {
            case APPROACHING -> "bringing " + count + " " + itemKey.id().replace("minecraft:", "") + " to " + who;
            case DROPPING -> "handing " + (count - Math.min(dropped, count)) + " of " + count + " "
                + itemKey.id().replace("minecraft:", "") + " to " + who;
        };
    }
}
