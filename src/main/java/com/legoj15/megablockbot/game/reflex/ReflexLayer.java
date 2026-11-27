package com.legoj15.megablockbot.game.reflex;

import com.legoj15.megablockbot.MegaBlockBot;
import com.legoj15.megablockbot.core.exec.ReflexHold;
import com.legoj15.megablockbot.game.body.BotPlayer;
import com.legoj15.megablockbot.game.nav.BotNavigator;
import com.legoj15.megablockbot.game.nav.BreakFilter;
import com.legoj15.megablockbot.game.reflex.ReflexConstants.NightPolicy;
import com.legoj15.megablockbot.game.task.BreakRules;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

/**
 * The reflex layer of one body (design 3.2): above whatever goal runs, every tick, it looks at the body and what is
 * around it and, when something needs dealing with now, takes the body over, deals with it and hands it back.
 *
 * <p><b>Contract with the runtime.</b> Call {@link #tick} once per body tick, before the executor. While the hold it
 * returns says {@code held}, the layer drives the body (inputs, the navigator, the game mode) and the runtime must
 * leave the executor's task and the navigator alone: this layer ticks the navigator itself when it has a command
 * running. While it returns {@link ReflexHold#NONE} the layer touches nothing. On taking the body it stops the
 * navigator, drops the input, the item in use and any block break in progress, and sets the navigator's break filter to
 * "break nothing"; whenever a hold ends, for whatever reason (finished, the body died or was replaced, the layer was
 * stopped), the filter is set to {@link BreakRules#filterFor} for the body as it is then and no input, item in use or
 * navigator command is left behind. The layer remembers no filter of anyone else's: the executor's tasks arm the navigator
 * themselves whenever they start or resume. It never changes the goal tree, never cancels a goal and never teleports.
 * The navigator command the layer starts on the tick its hold begins stays: the executor pauses its task in that same tick,
 * and a paused task stops only a command that is its own ({@code BodyTask}, {@code BotNavigator#issuingFor}).
 *
 * <p><b>Hysteresis.</b> Each reflex keeps the body until its cause is clearly over (the named constants in
 * {@link ReflexConstants}), so the executor's task is not paused and resumed every few ticks. A higher reflex takes
 * the body from a lower one at once; that hands the hold over without letting it go, so the executor sees one hold. A
 * handover is made by the layer, not by the reflexes: whatever navigator command the old holder had running is stopped, so
 * the new holder starts with the navigator idle and uses it only if it starts a command of its own.
 *
 * <p><b>Faults.</b> A brain must not throw (design 3.4), and this runs inside the body's tick, so a reflex that throws is
 * logged with its stack trace, counted ({@link #faults}) and left out for {@link #QUARANTINE_TICKS} ticks instead of
 * taking the server down with it.
 *
 * <p>Server thread only, like the body.
 */
public final class ReflexLayer {
    /** How long a reflex that threw stays off for its body. */
    static final int QUARANTINE_TICKS = 1200;

    private static final Map<UUID, ReflexLayer> LAYERS = new HashMap<>();
    private static final AtomicInteger FAULTS = new AtomicInteger();
    /** While a reflex navigates it must not dig: breaking terrain is the executor's business, not a retreat's. */
    private static final BreakFilter NO_BREAKS = (level, pos, state) -> false;

    /** How a night hold ended, for the log and for a test; the text of the outcome says why. */
    public enum NightResult {
        /** No night hold has ended yet. */
        NONE,
        /** The body slept in a bed. */
        SLEPT,
        /** It went for a bed and did not sleep in it: the policy changed, the bed was gone, morning came first. */
        DID_NOT_SLEEP,
        /** A shelter was closed and the night was spent in it. */
        SHELTERED,
        /** A shelter was begun and never closed. */
        NO_SHELTER
    }

    private BotPlayer body;
    private List<Reflex> reflexes = ReflexRegistry.instantiate();
    private final BlockWork work = new BlockWork();
    private final Map<UUID, Long> ignored = new HashMap<>();
    /** Mobs the body could not get closer to and that went on hurting it: run from, out of their sight, never ignored. Mob to the tick until which. */
    private final Map<UUID, Long> unreachable = new HashMap<>();
    /** Mob to the game time it last hurt the body. */
    private final Map<UUID, Long> hurtBy = new HashMap<>();
    private int lastHurtStamp = -1;
    private final ShelterBlocks shelter = new ShelterBlocks();
    /** How the mobs in sight are moving: whether a far creeper is coming. */
    private final ApproachWatch approach = new ApproachWatch();
    private final Map<String, Long> quarantine = new HashMap<>();
    private NightPolicy nightPolicy = ReflexConstants.NIGHT_POLICY;

    @Nullable
    private Reflex holder;
    private int heldTicks;
    private int holdsStarted;
    private boolean navigatorUsed;
    /** The navigator's break filter is "break nothing" because of this layer, and has not been given back yet. */
    private boolean filterOurs;
    private NightResult nightResult = NightResult.NONE;
    private String note = "";
    private String lastCause = "";

    private ReflexLayer(BotPlayer body) {
        this.body = body;
    }

    /** The layer of the body with this one's UUID, made on first use; it follows the body across respawns. */
    public static ReflexLayer of(BotPlayer body) {
        ReflexLayer layer = LAYERS.computeIfAbsent(body.getUUID(), id -> new ReflexLayer(body));
        layer.follow(body);
        return layer;
    }

    /** Forgets every layer. Called when a server stops; the bodies go with it. */
    public static void clearAll() {
        LAYERS.clear();
    }

    /** True while a layer exists for the body with this UUID, whatever it is doing. For tests. */
    public static boolean tracks(UUID bodyId) {
        return LAYERS.containsKey(bodyId);
    }

    /**
     * Drops the layers of bodies that are gone for good, as {@code BotNavigator.sweep} does for navigators. A layer follows
     * its body through a respawn (same UUID, new instance), so only a body that is no longer logged in at all, a despawn
     * nothing replaced, makes its layer dead weight; left alone it would keep the removed body in memory for the life of
     * the process. Called periodically from {@link ReflexFeature}.
     */
    public static void sweep(MinecraftServer server) {
        LAYERS.values().removeIf(layer -> {
            if (layer.body.getServer() != server) return false;
            if (server.getPlayerList().getPlayer(layer.body.getUUID()) != null) return false;
            // The navigator of a body that is gone is swept with it: nothing of that body's is touched here.
            layer.dropHold("the body was removed", null);
            return true;
        });
    }

    /** How many times a reflex has thrown since the JVM started: a GameTest asserts it stays put. */
    public static int faults() {
        return FAULTS.get();
    }

    // ---------------------------------------------------------------------------------------------------------
    // The tick
    // ---------------------------------------------------------------------------------------------------------

    /** Once per body tick, before the executor. Returns who owns the body this tick: {@link ReflexHold#NONE}, or a hold with its cause. */
    public ReflexHold tick(BotPlayer current) {
        follow(current);
        if (body.isDeadOrDying() || body.isRemoved() || body.hasDisconnected()) {
            // Nothing to hand over: a respawn is a new instance, and the old one's state means nothing to it.
            dropHold("the body is gone", body);
            return ReflexHold.NONE;
        }
        ReflexContext ctx = new ReflexContext(this, body);
        note = "";
        // A hold that was dropped (the body died, or was replaced) never gave the filter back: the live body gets it now.
        if (holder == null && filterOurs) giveBackFilter(ctx);
        if (ctx.time % 200 == 0) {
            ignored.values().removeIf(until -> until <= ctx.time);
            unreachable.values().removeIf(until -> until <= ctx.time);
            approach.prune(ctx.time);
        }
        noteHurt(ctx);

        Reflex chosen = null;
        for (Reflex reflex : reflexes) {
            if (quarantined(reflex, ctx.time)) continue;
            boolean wants = false;
            try {
                wants = reflex.wants(ctx);
            } catch (RuntimeException e) {
                fault(reflex, e, ctx.time);
            }
            if (wants) {
                chosen = reflex;
                break;
            }
        }
        if (chosen != holder) switchTo(ctx, chosen);
        if (chosen == null) return ReflexHold.NONE;

        try {
            chosen.act(ctx);
        } catch (RuntimeException e) {
            fault(chosen, e, ctx.time);
            switchTo(ctx, null);
            return ReflexHold.NONE;
        }
        // The navigator belongs to whoever has a command running on it: while held, that is this layer (or nobody).
        if (navigatorUsed) ctx.tickNavigator();
        try {
            chosen.afterNavigator(ctx);
        } catch (RuntimeException e) {
            fault(chosen, e, ctx.time);
            switchTo(ctx, null);
            return ReflexHold.NONE;
        }
        heldTicks++;
        lastCause = chosen.name() + ": " + chosen.cause();
        return new ReflexHold(true, lastCause);
    }

    /** One line for the owner: what the layer is doing and why, or "calm". */
    public String status() {
        String doing = holder == null ? "calm" : holder.name() + ": " + holder.cause() + " (held " + heldTicks + " ticks)";
        return String.format(Locale.ROOT, "%s; health %.1f/%.0f, food %d%s; night policy %s", doing, body.getHealth(), body.getMaxHealth(),
            body.getFoodData().getFoodLevel(), note.isEmpty() ? "" : ", " + note, nightPolicy);
    }

    /** Lets go of the body now, whatever holds it: what the dev command does when it takes the dev brain away. */
    public void stop() {
        if (holder == null) {
            if (filterOurs && !body.isDeadOrDying() && !body.isRemoved() && !body.hasDisconnected()) giveBackFilter(new ReflexContext(this, body));
            return;
        }
        switchTo(new ReflexContext(this, body), null);
    }

    public boolean isHolding() {
        return holder != null;
    }

    /** The name of the reflex that holds the body now ({@code hazard}, {@code mobs}, {@code vitals}, {@code night}), or empty when none does. */
    public String holderName() {
        return holder == null ? "" : holder.name();
    }

    /** How the latest night hold ended; {@link NightResult#NONE} until one has. */
    public NightResult lastNightResult() {
        return nightResult;
    }

    /** How many holds have started (a handover between reflexes is not a new hold). */
    public int holdsStarted() {
        return holdsStarted;
    }

    /** The cause of the latest hold tick, or of the last hold when none is running. */
    public String lastCause() {
        return lastCause;
    }

    /** How many shelter blocks this layer has recorded as standing (placed by the body, not yet taken down). */
    public int recordedShelterBlocks() {
        return shelter.size();
    }

    /**
     * Records a block as one this body put up for a night shelter, so that a calm tick in daylight takes it down. The night
     * reflex does this itself as it builds; this is for a block it did not see go up (a GameTest's, or one restored after a
     * restart once the record is kept across it).
     */
    public void rememberShelterBlock(ServerLevel level, BlockPos pos) {
        shelter.add(level, pos, level.getBlockState(pos).getBlock());
    }

    /**
     * The shelter blocks this body has standing, as the brain's save holds them: the dimension and position of each, and
     * nothing else. What the block is, the world says on the way back in (see {@link #loadShelterBlocks}).
     */
    public void saveShelterBlocks(CompoundTag tag) {
        ListTag blocks = new ListTag();
        for (ShelterBlocks.Entry entry : shelter.recorded()) {
            CompoundTag each = new CompoundTag();
            each.putString("dim", entry.dimension.location().toString());
            each.putLong("pos", entry.pos.asLong());
            blocks.add(each);
        }
        // Nothing standing, nothing written: a body with no shelter keeps the brain's save as it was.
        if (!blocks.isEmpty()) tag.put("blocks", blocks);
    }

    /**
     * Restores what {@link #saveShelterBlocks} wrote. A block that no longer holds a shelter block where the save had it
     * -- air, another block, a position in a dimension that is gone or a chunk that is not loaded to look at -- is not
     * the body's to take down any more and is dropped; the rest are recorded as they stand, and a calm daylight tick
     * takes them down (issue 29: after a restart the body no longer knew which blocks were its own).
     */
    public void loadShelterBlocks(BotPlayer current, CompoundTag tag) {
        MinecraftServer server = current.getServer();
        ListTag blocks = tag.getList("blocks", Tag.TAG_COMPOUND);
        for (int i = 0; i < blocks.size(); i++) {
            CompoundTag each = blocks.getCompound(i);
            ResourceLocation id = ResourceLocation.tryParse(each.getString("dim"));
            ResourceKey<Level> dimension = id == null ? null : ResourceKey.create(Registries.DIMENSION, id);
            ServerLevel level = server == null || dimension == null ? null : server.getLevel(dimension);
            BlockPos pos = BlockPos.of(each.getLong("pos"));
            if (level == null || !level.isLoaded(pos)) continue;
            Block block = level.getBlockState(pos).getBlock();
            if (!ReflexConstants.SHELTER_BLOCKS.contains(block.asItem())) continue;
            shelter.add(level, pos, block);
        }
    }

    /**
     * The body died: the brain's {@code onDeath} says so through the reflex source. The hold ends with the body that
     * died, at once, and its state with it -- not on the replacement's first tick, which is a tick the executor already
     * counts as held (issue 29). The navigator, kept per UUID across respawns, is stopped on the dying instance, which
     * is the one its command still follows.
     */
    public void bodyDied(BotPlayer dead) {
        dropHold("the body died", dead);
    }

    public NightPolicy nightPolicy() {
        return nightPolicy;
    }

    public void setNightPolicy(NightPolicy policy) {
        this.nightPolicy = Objects.requireNonNull(policy);
    }

    // ---------------------------------------------------------------------------------------------------------
    // For the reflexes
    // ---------------------------------------------------------------------------------------------------------

    Map<UUID, Long> ignoredMobs() {
        return ignored;
    }

    BlockWork work() {
        return work;
    }

    Map<UUID, Long> unreachableMobs() {
        return unreachable;
    }

    ShelterBlocks shelterBlocks() {
        return shelter;
    }

    ApproachWatch approachWatch() {
        return approach;
    }

    void setNightResult(NightResult result) {
        nightResult = result;
    }

    /** Whether this mob hurt the body in the last {@link ReflexConstants#HURT_MEMORY_TICKS} ticks. */
    boolean hurtRecently(UUID mob, long now) {
        Long at = hurtBy.get(mob);
        return at != null && now - at <= ReflexConstants.HURT_MEMORY_TICKS;
    }

    void markNavigatorUsed() {
        navigatorUsed = true;
    }

    /** A line about the body's state that is worth saying though no reflex took the body for it ("low health, nothing to eat"). */
    void note(String text) {
        note = text;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Taking and letting go
    // ---------------------------------------------------------------------------------------------------------

    /** Points this layer at the body as it is now; a replacement body starts from nothing. */
    private void follow(BotPlayer current) {
        if (current == body) return;
        dropHold("the body was replaced (respawn)", current);
        body = current;
        reflexes = ReflexRegistry.instantiate();
        approach.clear();
        ignored.clear();
        unreachable.clear();
        hurtBy.clear();
        lastHurtStamp = -1;
        quarantine.clear();
    }

    /**
     * What hurt the body is remembered by mob: a mob that hurt it in the last few seconds is never on the ignore list,
     * however it got there (a chase given up on, an earlier fight). The body's own record of its last attacker lasts five
     * seconds and holds one mob; this one keeps each.
     */
    private void noteHurt(ReflexContext ctx) {
        int stamp = body.getLastHurtByMobTimestamp();
        LivingEntity by = body.getLastHurtByMob();
        if (by instanceof Mob mob && stamp != lastHurtStamp) hurtBy.put(mob.getUUID(), ctx.time);
        lastHurtStamp = stamp;
        if (hurtBy.isEmpty()) return;
        hurtBy.values().removeIf(at -> ctx.time - at > ReflexConstants.HURT_MEMORY_TICKS);
        for (UUID id : hurtBy.keySet()) ignored.remove(id);
    }

    /**
     * Forgets a hold without touching the body it was for: that one is dead or replaced, and its input went with it. Its
     * navigator did not (it is kept per UUID across respawns), so a hold that was running a command stops it on the body
     * that is live now, {@code live}: the replacement, or the dead body itself when it is still the one the navigator
     * follows. A hold dropped because the body was removed for good has no live body to give: its navigator is swept.
     */
    private void dropHold(String why, @Nullable BotPlayer live) {
        if (holder == null) return;
        MegaBlockBot.LOGGER.info("reflex {}: hold dropped after {} ticks ({}): {}", name(), heldTicks, lastCause, why);
        if (navigatorUsed && live != null) {
            try {
                BotNavigator.of(live).stop();
            } catch (RuntimeException e) {
                // A dead body's engine may not take being told to stop; the replacement's first command starts it afresh anyway.
                MegaBlockBot.LOGGER.warn("reflex {}: could not stop the navigator of the dropped hold", name(), e);
            }
        }
        holder = null;
        navigatorUsed = false;
        // filterOurs stays: the filter is given back to the body that is alive next (see tick), not to this dead instance.
        reflexes = ReflexRegistry.instantiate();
    }

    private void switchTo(ReflexContext ctx, @Nullable Reflex next) {
        Reflex previous = holder;
        if (previous != null) {
            try {
                previous.release(ctx);
            } catch (RuntimeException e) {
                fault(previous, e, ctx.time);
            }
            body.input().reset();
        }
        if (previous == null && next != null) takeOver(ctx, next);
        if (previous != null && next != null) {
            // Whatever the old holder had running on the navigator ends with its hold; the new one starts from idle.
            ctx.nav.stop();
            navigatorUsed = false;
            String outcome = previous.outcome();
            MegaBlockBot.LOGGER.info("reflex {}: hold handed from {} to {}{} (health {}, food {})", name(), previous.name(), next.name(),
                outcome.isEmpty() ? "" : " [" + previous.name() + ": " + outcome + "]", health(), ctx.food());
        }
        holder = next;
        if (next == null) finishHold(ctx, previous);
    }

    private void takeOver(ReflexContext ctx, Reflex next) {
        if (ctx.nav.state() == BotNavigator.State.WORKING) ctx.nav.stop();
        body.input().reset();
        if (body.isUsingItem()) body.stopUsingItem();
        work.abort(body);
        ctx.nav.setBreakFilter(NO_BREAKS);
        filterOurs = true;
        navigatorUsed = false;
        heldTicks = 0;
        holdsStarted++;
        MegaBlockBot.LOGGER.info("reflex {}: hold started by {} (health {}, food {})", name(), next.name(), health(), ctx.food());
    }

    private void finishHold(ReflexContext ctx, @Nullable Reflex last) {
        if (navigatorUsed || ctx.nav.state() == BotNavigator.State.WORKING) ctx.nav.stop();
        navigatorUsed = false;
        giveBackFilter(ctx);
        body.input().reset();
        if (body.isUsingItem()) body.stopUsingItem();
        work.abort(body);
        String outcome = last == null ? "" : last.outcome();
        MegaBlockBot.LOGGER.info("reflex {}: hold ended after {} ticks: {}{} (health {}, food {})", name(), heldTicks, lastCause,
            outcome.isEmpty() ? "" : "; " + outcome, health(), ctx.food());
    }

    /**
     * Ends "break nothing": the navigator gets the rule every task arms it with, for the body as it is now. The layer keeps
     * no filter of its own to put back, because the one it replaced belonged to a task that has since been paused,
     * resumed (which re-arms it), replaced or lost with a dead body.
     */
    private void giveBackFilter(ReflexContext ctx) {
        ctx.nav.setBreakFilter(BreakRules.filterFor(body));
        filterOurs = false;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Faults
    // ---------------------------------------------------------------------------------------------------------

    private boolean quarantined(Reflex reflex, long now) {
        Long until = quarantine.get(reflex.name());
        return until != null && until > now;
    }

    private void fault(Reflex reflex, RuntimeException e, long now) {
        FAULTS.incrementAndGet();
        quarantine.put(reflex.name(), now + QUARANTINE_TICKS);
        MegaBlockBot.LOGGER.error("Reflex {} threw for body {}; it is off for {} ticks", reflex.name(), name(), QUARANTINE_TICKS, e);
    }

    private String name() {
        return body.getGameProfile().getName();
    }

    private String health() {
        return String.format(Locale.ROOT, "%.1f", body.getHealth());
    }
}
