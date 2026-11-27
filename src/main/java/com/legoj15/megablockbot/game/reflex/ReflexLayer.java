package com.legoj15.megablockbot.game.reflex;

import com.legoj15.megablockbot.MegaBlockBot;
import com.legoj15.megablockbot.core.exec.ReflexHold;
import com.legoj15.megablockbot.game.body.BotPlayer;
import com.legoj15.megablockbot.game.nav.BotNavigator;
import com.legoj15.megablockbot.game.nav.BreakFilter;
import com.legoj15.megablockbot.game.reflex.ReflexConstants.NightPolicy;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nullable;

/**
 * The reflex layer of one body (design 3.2): above whatever goal runs, every tick, it looks at the body and what is
 * around it and, when something needs dealing with now, takes the body over, deals with it and hands it back.
 *
 * <p><b>Contract with the runtime.</b> Call {@link #tick} once per body tick, before the executor. While the hold it
 * returns says {@code held}, the layer drives the body (inputs, the navigator, the game mode) and the runtime must
 * leave the executor's task and the navigator alone: this layer ticks the navigator itself when it has a command
 * running. While it returns {@link ReflexHold#NONE} the layer touches nothing. On taking the body it stops the
 * navigator, drops the input, the item in use and any block break in progress; on letting go it leaves none of them
 * behind and puts the navigator's break filter back. It never changes the goal tree, never cancels a goal and never
 * teleports.
 *
 * <p><b>Hysteresis.</b> Each reflex keeps the body until its cause is clearly over (the named constants in
 * {@link ReflexConstants}), so the executor's task is not paused and resumed every few ticks. A higher reflex takes
 * the body from a lower one at once; that hands the hold over without letting it go, so the executor sees one hold.
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

    private BotPlayer body;
    private List<Reflex> reflexes = ReflexRegistry.instantiate();
    private final BlockWork work = new BlockWork();
    private final Map<UUID, Long> ignored = new HashMap<>();
    private final Map<String, Long> quarantine = new HashMap<>();
    private NightPolicy nightPolicy = ReflexConstants.NIGHT_POLICY;

    @Nullable
    private Reflex holder;
    private int heldTicks;
    private int holdsStarted;
    private boolean navigatorUsed;
    @Nullable
    private BreakFilter savedFilter;
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
            dropHold("the body is gone");
            return ReflexHold.NONE;
        }
        ReflexContext ctx = new ReflexContext(this, body);
        note = "";
        if (ctx.time % 200 == 0) ignored.values().removeIf(until -> until <= ctx.time);

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
        if (holder == null) return;
        switchTo(new ReflexContext(this, body), null);
    }

    public boolean isHolding() {
        return holder != null;
    }

    /** How many holds have started (a handover between reflexes is not a new hold). */
    public int holdsStarted() {
        return holdsStarted;
    }

    /** The cause of the latest hold tick, or of the last hold when none is running. */
    public String lastCause() {
        return lastCause;
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
        dropHold("the body was replaced (respawn)");
        body = current;
        reflexes = ReflexRegistry.instantiate();
        ignored.clear();
        quarantine.clear();
    }

    /** Forgets a hold without touching the body: it is dead or replaced, and its input and navigator went with it. */
    private void dropHold(String why) {
        if (holder == null) return;
        MegaBlockBot.LOGGER.info("reflex {}: hold dropped after {} ticks ({}): {}", name(), heldTicks, lastCause, why);
        holder = null;
        navigatorUsed = false;
        savedFilter = null;
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
        savedFilter = ctx.nav.breakFilter();
        ctx.nav.setBreakFilter(NO_BREAKS);
        navigatorUsed = false;
        heldTicks = 0;
        holdsStarted++;
        MegaBlockBot.LOGGER.info("reflex {}: hold started by {} (health {}, food {})", name(), next.name(), health(), ctx.food());
    }

    private void finishHold(ReflexContext ctx, @Nullable Reflex last) {
        if (navigatorUsed) {
            ctx.nav.stop();
            navigatorUsed = false;
        }
        if (savedFilter != null) {
            ctx.nav.setBreakFilter(savedFilter);
            savedFilter = null;
        }
        body.input().reset();
        if (body.isUsingItem()) body.stopUsingItem();
        work.abort(body);
        String outcome = last == null ? "" : last.outcome();
        MegaBlockBot.LOGGER.info("reflex {}: hold ended after {} ticks: {}{} (health {}, food {})", name(), heldTicks, lastCause,
            outcome.isEmpty() ? "" : "; " + outcome, health(), ctx.food());
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
