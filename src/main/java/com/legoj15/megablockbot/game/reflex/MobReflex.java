package com.legoj15.megablockbot.game.reflex;

import com.legoj15.megablockbot.game.body.BotPlayer;
import com.legoj15.megablockbot.game.nav.BotNavigator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * Priority 2 (design 3.2): hostile mobs that target the body or are within melee range. Fights with the best weapon
 * the body holds, the way a player does: selects it, faces the mob, swings through {@code Player.attack} when the attack
 * cooldown has recovered, and holds a shield up between swings (a player cannot swing while a shield is up, so it is
 * lowered for the swing). Retreats instead when outmatched: health at or below the retreat threshold, a creeper about to
 * explode, a mob far stronger than a player, more attackers than the constant allows, or no weapon against more than
 * a lone mob. A retreat is a walk to a spot some way off through the navigator. A retreat that finds no way out, or keeps
 * being caught, turns into a stand: cornered, it fights whatever is at hand.
 *
 * <p>It holds the body until no threat has been around for {@link ReflexConstants#THREAT_QUIET_TICKS} and no mob has
 * hurt the body for {@link ReflexConstants#HURT_QUIET_TICKS} (design 3.4's resume gate, less the health condition, which
 * the vitals reflex owns because it is the one that can do something about it). A hold that retreated, from a threat that is
 * still about, lasts {@link ReflexConstants#RETREAT_QUIET_TICKS} past the last sight of one: one creeper is one hold.
 * What counts as a threat, a creeper that hovers far off excepted, is {@link Threats}'s to say.
 */
final class MobReflex implements Reflex {
    private boolean active;
    private int quietTicks;
    private String cause = "";
    private String outcome = "";

    // Fighting.
    @Nullable
    private UUID chasing;
    private double bestDistance;
    private int noProgress;
    private boolean shieldChecked;
    private int swings;
    private final Map<UUID, Mob> engaged = new LinkedHashMap<>();
    private int kills;

    // Retreating.
    private boolean retreating;
    /** This hold has retreated at least once: it is kept for {@link ReflexConstants#RETREAT_QUIET_TICKS} after the last threat is seen. */
    private boolean retreated;
    /** The retreat in progress is to a spot the mobs cannot see, from a shooter that cannot be reached. */
    private boolean hiding;
    private long retreatPickedAt;
    private int arrivals;
    private long corneredUntil;
    @Nullable
    private BlockPos retreatSpot;
    private final List<BlockPos> badSpots = new ArrayList<>();

    @Override
    public String name() {
        return "mobs";
    }

    @Override
    public String cause() {
        return cause;
    }

    @Override
    public String outcome() {
        return outcome;
    }

    @Override
    public boolean wants(ReflexContext ctx) {
        List<Threat> threats = ctx.threats();
        if (!active) {
            if (threats.isEmpty()) return false;
            active = true;
            quietTicks = 0;
            outcome = "";
            return true;
        }
        if (!threats.isEmpty()) {
            quietTicks = 0;
            return true;
        }
        quietTicks++;
        BotPlayer body = ctx.body;
        boolean hurtRecently = body.getLastHurtByMob() != null && body.tickCount - body.getLastHurtByMobTimestamp() < ReflexConstants.HURT_QUIET_TICKS;
        // A body that ran is not handed back the moment the threat is out of sight: the cover it ran behind is what hides it.
        // A threat that is dead or gone is dealt with, and the short quiet will do.
        int quiet = retreated && !engaged.isEmpty() ? ReflexConstants.RETREAT_QUIET_TICKS : ReflexConstants.THREAT_QUIET_TICKS;
        return quietTicks < quiet || hurtRecently;
    }

    @Override
    public void release(ReflexContext ctx) {
        BotPlayer body = ctx.body;
        lowerShield(body);
        if (retreating) ctx.nav.stop();
        harvestKills(body);
        long survivors = engaged.size();
        outcome = String.format(Locale.ROOT, "%d killed, %d got away or are still about, %d swings", kills, survivors, swings);
        active = false;
        quietTicks = 0;
        chasing = null;
        noProgress = 0;
        shieldChecked = false;
        swings = 0;
        engaged.clear();
        kills = 0;
        retreating = false;
        retreated = false;
        hiding = false;
        arrivals = 0;
        corneredUntil = 0;
        retreatSpot = null;
        badSpots.clear();
    }

    @Override
    public void act(ReflexContext ctx) {
        BotPlayer body = ctx.body;
        List<Threat> threats = ctx.threats();
        for (Threat threat : threats) engaged.putIfAbsent(threat.mob().getUUID(), threat.mob());
        harvestKills(body);
        if (threats.isEmpty()) {
            calm(ctx);
            return;
        }

        Threat blow = null;
        Threat strong = null;
        Threat shooter = null;
        int attackers = 0;
        for (Threat threat : threats) {
            if (threat.aboutToBlow() && blow == null) blow = threat;
            if (threat.mob().getMaxHealth() >= ReflexConstants.OUTCLASSED_MAX_HEALTH && strong == null) strong = threat;
            if (shooter == null && keepsHurtingFromAfar(ctx, threat)) shooter = threat;
            if (threat.targetsBody() || threat.distance() <= ReflexConstants.MELEE_TRIGGER) attackers++;
        }
        boolean lowHealth = body.getHealth() <= ReflexConstants.RETREAT_HEALTH;
        boolean tooMany = attackers > ReflexConstants.MAX_ATTACKERS;
        boolean unarmedAndOutnumbered = Hands.bestWeapon(body) < 0
            && (attackers > ReflexConstants.FIST_MAX_ATTACKERS || body.getHealth() < ReflexConstants.FIST_MIN_HEALTH);
        boolean outmatched = lowHealth || tooMany || blow != null || strong != null || unarmedAndOutnumbered || shooter != null;
        boolean hide = shooter != null;

        // A fuse is burning close by and there is a shield: face the creeper with it up rather than run from the blast.
        if (blow != null && blow.fusing() && blow.distance() <= ReflexConstants.CREEPER_BRACE_RANGE && brace(ctx, blow)) return;

        if (outmatched && ctx.time >= corneredUntil) {
            String why = blow != null ? "a creeper about to explode" : strong != null ? "a mob far stronger than a player"
                : lowHealth ? "health " + String.format(Locale.ROOT, "%.1f", body.getHealth()) : tooMany ? attackers + " attackers"
                : shooter != null ? describe(shooter.mob()) + " that it cannot reach keeps hurting it" : "no weapon";
            if (retreat(ctx, threats, why, hide)) return;
        } else if (!outmatched) {
            retreating = false;
            hiding = false;
            arrivals = 0;
        }
        fight(ctx, threats);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Fighting
    // ---------------------------------------------------------------------------------------------------------

    private void fight(ReflexContext ctx, List<Threat> threats) {
        BotPlayer body = ctx.body;
        if (retreating) {
            ctx.nav.stop();
            retreating = false;
        }
        Threat target = pickTarget(threats);
        Mob mob = target.mob();
        cause = (corneredNow(ctx) ? "cornered, " : "") + "fighting " + describe(mob) + (threats.size() > 1 ? " and " + (threats.size() - 1) + " more" : "");

        equipForFight(body);
        Vec3 aim = mob.getBoundingBox().getCenter();
        Steer.face(body, aim);

        if (!mob.getUUID().equals(chasing)) {
            chasing = mob.getUUID();
            bestDistance = target.distance();
            noProgress = 0;
        }

        boolean inReach = body.canInteractWithEntity(mob, 0.0);
        if (inReach) {
            ctx.layer.unreachableMobs().remove(mob.getUUID());
            Steer.stand(body);
            bestDistance = target.distance();
            noProgress = 0;
            if (body.getAttackStrengthScale(0.5F) >= ReflexConstants.ATTACK_READY) {
                if (body.isUsingItem()) {
                    // Lowered this tick; the swing is next tick's, as a player's release and click are two inputs.
                    body.releaseUsingItem();
                } else {
                    swing(body, mob);
                }
            } else {
                raiseShieldIfUseful(ctx, threats);
            }
            return;
        }

        lowerShield(body);
        if (target.creeper() && target.distance() > ReflexConstants.CREEPER_SWELL_RANGE) {
            // Never walk up to a creeper: stand, face it, and let it come (the shield goes up when its fuse burns).
            Steer.stand(body);
            cause = "holding off " + describe(mob) + ", not approaching it";
            // One that does not come (stuck behind a river) is not waited for for ever.
            if (target.distance() < bestDistance - 0.5) {
                bestDistance = target.distance();
                noProgress = 0;
            } else if (++noProgress > ReflexConstants.CHASE_GIVE_UP_TICKS) {
                ctx.layer.ignoredMobs().put(mob.getUUID(), ctx.time + ReflexConstants.IGNORE_MOB_TICKS);
                chasing = null;
                noProgress = 0;
            }
            return;
        }
        boolean far = target.distance() > ReflexConstants.SPRINT_APPROACH_DISTANCE;
        Steer.walkToward(body, aim, far);
        boolean hurtingUs = ctx.layer.hurtRecently(mob.getUUID(), ctx.time);
        if (target.distance() < bestDistance - 0.5) {
            bestDistance = target.distance();
            noProgress = 0;
        } else if (++noProgress > (hurtingUs ? ReflexConstants.CHASE_GIVE_UP_HURT_TICKS : ReflexConstants.CHASE_GIVE_UP_TICKS)) {
            if (hurtingUs) {
                // It gets no closer and goes on hurting the body: never ignored. It is run from, out of its sight.
                ctx.layer.unreachableMobs().put(mob.getUUID(), ctx.time + ReflexConstants.IGNORE_MOB_TICKS);
            } else {
                ctx.layer.ignoredMobs().put(mob.getUUID(), ctx.time + ReflexConstants.IGNORE_MOB_TICKS);
            }
            chasing = null;
            noProgress = 0;
        }
    }

    /** A mob the body could not get closer to, that has hurt it in the last few seconds and is still out of reach. */
    private static boolean keepsHurtingFromAfar(ReflexContext ctx, Threat threat) {
        Long until = ctx.layer.unreachableMobs().get(threat.mob().getUUID());
        if (until == null || until <= ctx.time) return false;
        return ctx.layer.hurtRecently(threat.mob().getUUID(), ctx.time) && !ctx.body.canInteractWithEntity(threat.mob(), 0.0);
    }

    /** The nearest threat that is not a creeper about to explode; when there is only that, the creeper (cornered). */
    private static Threat pickTarget(List<Threat> threats) {
        for (Threat threat : threats) {
            if (!threat.aboutToBlow()) return threat;
        }
        return threats.get(0);
    }

    private void equipForFight(BotPlayer body) {
        int weapon = Hands.bestWeapon(body);
        if (weapon >= 0 && Hands.weaponScore(body.getMainHandItem()) < Hands.weaponScore(body.getInventory().items.get(weapon))) {
            Hands.toMainHand(body, weapon);
        }
        if (!shieldChecked && !body.isUsingItem()) {
            shieldChecked = true;
            int shield = Hands.findShield(body);
            if (shield >= 0 && !Hands.isShield(body.getOffhandItem())) Hands.toOffhand(body, shield);
        }
    }

    private void swing(BotPlayer body, Mob mob) {
        body.resetLastActionTime();
        // The player's own attack: damage, enchantments, knockback, sweeping, and the tool's wear all happen in it. The
        // swing comes after it, as a client sends it: ServerPlayer.swing resets the attack cooldown, and a swing first
        // would make every attack a weak one.
        body.attack(mob);
        body.swing(InteractionHand.MAIN_HAND);
        swings++;
    }

    /** Faces the creeper with the shield up. False when there is no shield to put up (it is run from, or fought, as usual). */
    private boolean brace(ReflexContext ctx, Threat creeper) {
        BotPlayer body = ctx.body;
        if (!Hands.shieldReady(body)) {
            int shield = Hands.findShield(body);
            if (shield < 0 || body.isUsingItem()) return false;
            Hands.toOffhand(body, shield);
            if (!Hands.shieldReady(body)) return false;
        }
        if (retreating) {
            ctx.nav.stop();
            retreating = false;
        }
        Steer.face(body, creeper.mob().getBoundingBox().getCenter());
        Steer.stand(body);
        cause = "bracing against a creeper, shield up";
        if (!body.isUsingItem()) {
            body.resetLastActionTime();
            body.gameMode.useItem(body, ctx.level, body.getOffhandItem(), InteractionHand.OFF_HAND);
        }
        return true;
    }

    /** Between swings, with a shield in the off hand and a mob that is after the body close enough to hit it. */
    private void raiseShieldIfUseful(ReflexContext ctx, List<Threat> threats) {
        BotPlayer body = ctx.body;
        if (body.isUsingItem() || !Hands.shieldReady(body)) return;
        boolean incoming = false;
        for (Threat threat : threats) {
            if (threat.targetsBody() && threat.distance() <= ReflexConstants.SHIELD_RANGE && !threat.creeper()) {
                incoming = true;
                break;
            }
        }
        if (!incoming) return;
        ItemStack shield = body.getOffhandItem();
        body.resetLastActionTime();
        body.gameMode.useItem(body, ctx.level, shield, InteractionHand.OFF_HAND);
    }

    private static void lowerShield(BotPlayer body) {
        if (body.isUsingItem() && Hands.isShield(body.getUseItem())) body.releaseUsingItem();
    }

    /**
     * No threat is in sight but the hold goes on for the quiet period: shield down, and stand ready. A retreat that is under
     * way is finished first (the navigator is the layer's and is ticked after this): out of sight is not out of reach, a
     * zombie that was behind the body is still coming, and a body that stops where it lost sight of it waits for it.
     */
    private void calm(ReflexContext ctx) {
        BotPlayer body = ctx.body;
        lowerShield(body);
        if (retreating) {
            if (ctx.nav.state() == BotNavigator.State.WORKING) {
                cause = "finishing the retreat, nothing in sight";
                return;
            }
            ctx.nav.stop();
            retreating = false;
        }
        cause = "calming down";
        Steer.stand(body);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Retreating
    // ---------------------------------------------------------------------------------------------------------

    /**
     * Walks away from the threats. Returns false when there is nowhere to go, which makes the body stand and fight.
     * {@code hide} runs to a spot the threats cannot see rather than just far from them (a shooter that cannot be reached).
     */
    private boolean retreat(ReflexContext ctx, List<Threat> threats, String why, boolean hide) {
        BotPlayer body = ctx.body;
        BotNavigator nav = ctx.nav;
        if (hide != hiding) {
            // The reason for running changed: the spot picked for the old one is no good for this one.
            hiding = hide;
            retreating = false;
            nav.stop();
        }
        if (retreating) {
            switch (nav.state()) {
                case FAILED -> {
                    // That spot cannot be got to (across a wall, a river): remember it and try another at once.
                    if (retreatSpot != null) badSpots.add(retreatSpot);
                    retreating = false;
                    if (badSpots.size() > ReflexConstants.RETREAT_MAX_FAILED_SPOTS) {
                        corner(ctx, "no spot could be reached");
                        return false;
                    }
                }
                case DONE -> {
                    // Arrived, and still something is after the body: run again, a few times, then make a stand.
                    retreating = false;
                    if (++arrivals >= ReflexConstants.RETREAT_MAX_ARRIVALS) {
                        corner(ctx, "caught up with " + arrivals + " times");
                        return false;
                    }
                }
                default -> { }
            }
        }
        if (!retreating || ctx.time - retreatPickedAt >= ReflexConstants.RETREAT_REPICK_TICKS) {
            BlockPos spot = hide ? RetreatSpots.pickHidden(ctx, threats, badSpots) : null;
            // A hide that finds nowhere to hide runs to a far spot as any retreat does, and arrives as one does.
            boolean hiddenSpot = spot != null;
            if (spot == null) spot = RetreatSpots.pick(ctx, threats, badSpots);
            if (spot == null) {
                corner(ctx, "no spot to run to");
                return false;
            }
            // A far spot is arrived at when near it; a hiding place only when the body stands in it (a step short may be in sight).
            ctx.goTo(spot, hiddenSpot ? 0 : ReflexConstants.RETREAT_ARRIVE_RADIUS);
            retreating = true;
            retreatSpot = spot;
            retreatPickedAt = ctx.time;
        }
        lowerShield(body);
        retreated = true;
        cause = "retreating from " + describe(threats.get(0).mob()) + " (" + why + ")";
        return true;
    }

    private void corner(ReflexContext ctx, String why) {
        corneredUntil = ctx.time + ReflexConstants.CORNERED_TICKS;
        retreating = false;
        hiding = false;
        arrivals = 0;
        badSpots.clear();
        ctx.nav.stop();
        cause = "cornered (" + why + ")";
    }

    /**
     * The running start of a retreat: while the navigator has not put the body in motion yet, run straight away from the
     * nearest threat, if the ground a step ahead is there. The navigator's own input replaces it the tick it has any.
     */
    @Override
    public void afterNavigator(ReflexContext ctx) {
        if (!retreating || ctx.time - retreatPickedAt > ReflexConstants.RETREAT_RUNNING_START_TICKS) return;
        BotPlayer body = ctx.body;
        if (body.input().forward != 0.0F || body.input().strafe != 0.0F) return;
        List<Threat> threats = ctx.threats();
        if (threats.isEmpty()) return;
        Vec3 away = body.position().subtract(threats.get(0).mob().position());
        away = new Vec3(away.x, 0.0, away.z);
        if (away.lengthSqr() < 1.0E-6) return;
        away = away.normalize();
        Vec3 ahead = body.position().add(away.scale(1.5));
        BlockPos at = body.blockPosition();
        if (Cells.standableIn(ctx.level, (int) Math.floor(ahead.x), (int) Math.floor(ahead.z), at.getY() + 1, at.getY() - 1) == null) return;
        Steer.walkToward(body, body.position().add(away.scale(6.0)), true);
    }

    private boolean corneredNow(ReflexContext ctx) {
        return ctx.time < corneredUntil;
    }

    // ---------------------------------------------------------------------------------------------------------

    /** Engaged mobs that died count as kills; those that merely left are forgotten here and counted as gone at the end. */
    private void harvestKills(BotPlayer body) {
        engaged.values().removeIf(mob -> {
            if (!mob.isDeadOrDying() && !mob.isRemoved()) return false;
            LivingEntity credit = mob.getKillCredit();
            if (mob.isDeadOrDying() && (credit == body || mob.getLastHurtByMob() == body)) kills++;
            return true;
        });
    }

    private static String describe(Mob mob) {
        return mob.getType().getDescription().getString().toLowerCase(Locale.ROOT);
    }
}
