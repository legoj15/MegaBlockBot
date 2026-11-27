package com.legoj15.megablockbot.game.reflex;

import com.legoj15.megablockbot.MegaBlockBot;
import com.legoj15.megablockbot.game.body.BotPlayer;
import com.legoj15.megablockbot.game.nav.BotNavigator;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Priority 1 (design 3.2): immediate hazards. On fire or in lava, the body gets out and reaches water if there is any
 * near; when its air runs low under water it surfaces; when its head is inside a block it gets out of it. About to walk
 * off a deadly drop is the navigator's job and is not handled here.
 *
 * <p>A body that is on fire with no water near and nothing burning under it is left alone: there is nothing to do but
 * wait, and holding the body would only stop its goal for no gain. Moves are plain inputs (jumping to swim up out of lava
 * or water) or a navigator command to the water; a body in lava is not given a path search.
 */
final class HazardReflex implements Reflex {
    private enum Kind { LAVA, FIRE, DROWNING, SUFFOCATION }

    private boolean active;
    private Kind kind = Kind.FIRE;
    private int clearTicks;
    /** Ticks the hold has lasted on the current kind of hazard: the give-up clock, restarted when the kind changes. */
    private int heldTicks;
    /** Per kind, the game time until which it is given up on. See {@link ReflexConstants#HAZARD_GIVE_UP_TICKS}. */
    private final EnumMap<Kind, Long> giveUpUntil = new EnumMap<>(Kind.class);
    private boolean gaveUp;
    /** The kinds this hold gave up on before the one it ends on, in order: a handover to another hazard must not lose them. */
    private final List<Kind> givenUpKinds = new ArrayList<>();
    private String cause = "";
    private String outcome = "";
    private boolean reachedWater;
    @Nullable
    private BlockPos waterTarget;
    private boolean navFailed;

    @Override
    public String name() {
        return "hazard";
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
        Kind now = detect(ctx);
        if (!active) {
            if (now == null) return false;
            active = true;
            kind = now;
            clearTicks = 0;
            heldTicks = 0;
            reachedWater = false;
            navFailed = false;
            waterTarget = null;
            outcome = "";
            givenUpKinds.clear();
            return true;
        }
        if (++heldTicks > ReflexConstants.HAZARD_GIVE_UP_TICKS) {
            // This kind has held the body long enough: it is left alone for a while, and a hazard of another kind that is
            // there (the air running out while a fire could not be put out) is looked for at once.
            giveUpUntil.put(kind, ctx.time + ReflexConstants.HAZARD_GIVE_UP_TICKS);
            MegaBlockBot.LOGGER.info("reflex {}: gave up on {} after {} ticks; it is left alone for {} ticks", ctx.body.getGameProfile().getName(),
                kind.name().toLowerCase(Locale.ROOT), heldTicks, ReflexConstants.HAZARD_GIVE_UP_TICKS);
            gaveUp = true;
            // The story of the kind that was given up on stays in the outcome even though the hold goes on against the next.
            givenUpKinds.add(kind);
            now = detect(ctx);
            if (now == null) return false;
            switchKind(now);
            return true;
        }
        if (now != null) {
            if (now != kind) switchKind(now);
            clearTicks = 0;
            return true;
        }
        BotPlayer body = ctx.body;
        // Out of the water is not yet out of danger: the air has to come back before the body goes back to work.
        if (kind == Kind.DROWNING && (body.isEyeInFluid(FluidTags.WATER) || body.getAirSupply() < ReflexConstants.AIR_RECOVERED)) return true;
        return ++clearTicks < ReflexConstants.HAZARD_CLEAR_TICKS;
    }

    @Override
    public void release(ReflexContext ctx) {
        // A give-up that handed straight over to another hazard keeps both stories: the first kind is as much what the
        // hold came to as the one it ended on (issue 29).
        StringBuilder earlier = new StringBuilder();
        for (Kind given : givenUpKinds) earlier.append(", ").append(given.name().toLowerCase(Locale.ROOT)).append(" given up on earlier");
        outcome = String.format(Locale.ROOT, "%s %s after %d ticks%s%s", kind.name().toLowerCase(Locale.ROOT), gaveUp ? "given up on" : "over",
            heldTicks, reachedWater ? ", reached water" : "", earlier);
        gaveUp = false;
        givenUpKinds.clear();
        active = false;
        waterTarget = null;
    }

    /** The hold goes on against another kind of hazard: its own give-up clock, and nothing of the last one's route. */
    private void switchKind(Kind next) {
        gaveUp = false;
        kind = next;
        heldTicks = 0;
        waterTarget = null;
        navFailed = false;
        reachedWater = false;
    }

    // ---------------------------------------------------------------------------------------------------------

    /** The hazard the body is in now, in priority order, leaving out the kinds that are given up on for the moment. */
    @Nullable
    private Kind detect(ReflexContext ctx) {
        BotPlayer body = ctx.body;
        if (body.isInLava() && allowed(Kind.LAVA, ctx)) return Kind.LAVA;
        BlockState feet = ctx.level.getBlockState(body.blockPosition());
        boolean inFire = feet.is(BlockTags.FIRE) || feet.is(Blocks.CAMPFIRE) && feet.getValue(CampfireBlock.LIT);
        if (allowed(Kind.FIRE, ctx)) {
            if (inFire && !body.fireImmune() && !body.hasEffect(MobEffects.FIRE_RESISTANCE)) return Kind.FIRE;
            if (body.isOnFire() && !body.fireImmune() && !body.hasEffect(MobEffects.FIRE_RESISTANCE) && findWater(ctx) != null) return Kind.FIRE;
        }
        boolean breathes = body.canBreatheUnderwater() || body.hasEffect(MobEffects.WATER_BREATHING) || body.hasEffect(MobEffects.CONDUIT_POWER);
        if (!breathes && body.isEyeInFluid(FluidTags.WATER) && body.getAirSupply() < ReflexConstants.AIR_LOW && allowed(Kind.DROWNING, ctx)) return Kind.DROWNING;
        if (body.isInWall() && allowed(Kind.SUFFOCATION, ctx)) return Kind.SUFFOCATION;
        return null;
    }

    private boolean allowed(Kind candidate, ReflexContext ctx) {
        Long until = giveUpUntil.get(candidate);
        return until == null || ctx.time >= until;
    }

    @Override
    public void act(ReflexContext ctx) {
        BotPlayer body = ctx.body;
        switch (kind) {
            case LAVA, FIRE -> burning(ctx, body);
            case DROWNING -> surface(ctx, body);
            case SUFFOCATION -> breakOut(ctx, body);
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Fire and lava
    // ---------------------------------------------------------------------------------------------------------

    private void burning(ReflexContext ctx, BotPlayer body) {
        if (body.isInWater()) {
            reachedWater = true;
            cause = "in water, fire out";
            Steer.stand(body);
            body.input().jump = true;
            return;
        }
        BlockPos water = waterTarget != null && ctx.level.getFluidState(waterTarget).is(FluidTags.WATER) ? waterTarget : findWater(ctx);
        waterTarget = water;
        if (water != null) {
            cause = (kind == Kind.LAVA ? "in lava" : "on fire") + ", going to water at " + water.toShortString();
            // A body in lava is not given a path search: it swims up and out by hand. Otherwise the navigator takes it there.
            boolean byHand = body.isInLava() || navFailed;
            if (!byHand && ctx.nav.state() == BotNavigator.State.FAILED) {
                navFailed = true;
                byHand = true;
            }
            if (byHand) {
                Steer.walkToward(body, Vec3.atBottomCenterOf(water), false);
            } else if (ctx.nav.state() != BotNavigator.State.WORKING) {
                ctx.goTo(water, 0);
            }
            return;
        }
        // No water near: get out of what is burning, to the nearest safe cell.
        BlockPos safe = nearestSafe(ctx);
        cause = (kind == Kind.LAVA ? "in lava" : "in fire") + ", getting out" + (safe == null ? " (no safe cell near)" : "");
        if (safe != null) Steer.walkToward(body, Vec3.atBottomCenterOf(safe), true);
        else body.input().jump = true;
    }

    /** The nearest water cell with open air above it within the search radius (and 3 up or down): a place to put the fire out. */
    @Nullable
    private BlockPos findWater(ReflexContext ctx) {
        BotPlayer body = ctx.body;
        BlockPos origin = body.blockPosition();
        int r = ReflexConstants.WATER_SEARCH_RADIUS;
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dy = 3; dy >= -3; dy--) {
                    BlockPos pos = origin.offset(dx, dy, dz);
                    if (!ctx.level.isLoaded(pos) || !ctx.level.getFluidState(pos).is(FluidTags.WATER)) continue;
                    BlockPos above = pos.above();
                    if (!ctx.level.getFluidState(above).isEmpty() || !ctx.level.getBlockState(above).getCollisionShape(ctx.level, above).isEmpty()) continue;
                    double distance = pos.distSqr(origin);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = pos;
                    }
                    break;
                }
            }
        }
        return best;
    }

    /** The nearest cell a body can stand in that is not on fire or in lava, within the safe-spot radius. */
    @Nullable
    private BlockPos nearestSafe(ReflexContext ctx) {
        BlockPos origin = ctx.body.blockPosition();
        int r = ReflexConstants.SAFE_SPOT_RADIUS;
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                BlockPos spot = Cells.standableIn(ctx.level, origin.getX() + dx, origin.getZ() + dz, origin.getY() + 2, origin.getY() - 3);
                if (spot == null) continue;
                double distance = spot.distSqr(origin);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = spot;
                }
            }
        }
        return best;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Drowning
    // ---------------------------------------------------------------------------------------------------------

    /** Swims up: holds jump, and moves sideways first when the water above is roofed over. */
    private void surface(ReflexContext ctx, BotPlayer body) {
        cause = String.format(Locale.ROOT, "air %d, surfacing", body.getAirSupply());
        BlockPos hole = nearestOpenWater(ctx);
        Steer.stand(body);
        if (hole != null) {
            Vec3 target = Vec3.atBottomCenterOf(hole);
            Vec3 flat = new Vec3(target.x - body.getX(), 0.0, target.z - body.getZ());
            if (flat.lengthSqr() > 0.09) {
                Steer.walkToward(body, new Vec3(target.x, body.getEyeY(), target.z), false);
            }
        }
        // Holding jump is what keeps a player swimming up, and afloat once the head is out.
        body.input().jump = body.isInWater();
    }

    /**
     * The nearest cell whose water column, straight up from the body's height, reaches open air: the way to the
     * surface. Straight above the body when that is open (the usual case).
     */
    @Nullable
    private BlockPos nearestOpenWater(ReflexContext ctx) {
        BlockPos origin = ctx.body.blockPosition();
        int top = origin.getY() + 24;
        for (int r = 0; r <= 6; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    BlockPos column = origin.offset(dx, 0, dz);
                    for (int y = origin.getY(); y <= top; y++) {
                        BlockPos pos = new BlockPos(column.getX(), y, column.getZ());
                        if (!ctx.level.isLoaded(pos)) break;
                        if (!ctx.level.getFluidState(pos).is(FluidTags.WATER)) break;
                        BlockPos above = pos.above();
                        if (ctx.level.getFluidState(above).isEmpty()) {
                            if (ctx.level.getBlockState(above).getCollisionShape(ctx.level, above).isEmpty()) return pos;
                            break;
                        }
                    }
                }
            }
        }
        return null;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Suffocation
    // ---------------------------------------------------------------------------------------------------------

    /** The head is inside a block: break it, if the break rules allow, and the body can breathe again. */
    private void breakOut(ReflexContext ctx, BotPlayer body) {
        Steer.stand(body);
        BlockPos head = BlockPos.containing(body.getEyePosition());
        BlockState state = ctx.level.getBlockState(head);
        if (state.isAir()) {
            cause = "in a wall";
            return;
        }
        BlockWork.Outcome result = ctx.layer.work().breakBlock(ctx, head);
        cause = "in a wall, breaking out through " + head.toShortString() + " (" + result.name().toLowerCase(Locale.ROOT) + ")";
        if (result == BlockWork.Outcome.REFUSED) ctx.layer.note("walled in at " + head.toShortString() + " and may not break it");
    }
}
