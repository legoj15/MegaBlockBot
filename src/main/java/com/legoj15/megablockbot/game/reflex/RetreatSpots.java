package com.legoj15.megablockbot.game.reflex;

import com.legoj15.megablockbot.game.body.BotPlayer;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Where to retreat to: a standable cell some distance off, picked so that it is farther from every threat than the
 * body is now. A retreat is a walk (the navigator takes the body there), so the spot has to be one a body can stand on;
 * it is never a position the body is moved to.
 */
final class RetreatSpots {
    private RetreatSpots() {}

    private static final int ANGLES = 16;

    /** Spots within this many blocks of one that the navigator could not reach are not tried again. */
    private static final double BAD_SPOT_RADIUS = 3.0;
    /** A run that passes a threat this close, and closer than the body is now, is not taken. */
    private static final double PASS_CLEARANCE = 4.5;
    /** Score taken off a spot for each shooter that could see it. */
    private static final double RANGED_EXPOSURE_COST = 8.0;

    /**
     * The best spot, or null when there is none. {@code bad} holds spots the navigator has already failed to reach.
     * Spots the body can see in a straight line are strongly preferred: they are nearly always walkable, while one the
     * other side of a wall or a river is a path search that ends in "no path" some seconds later.
     */
    @Nullable
    static BlockPos pick(ReflexContext ctx, List<Threat> threats, List<BlockPos> bad) {
        BotPlayer body = ctx.body;
        Vec3 here = body.position();
        double hereMin = minDistance(here, threats);
        BlockPos best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        int by = body.blockPosition().getY();
        for (int ring = 0; ring < 2; ring++) {
            double radius = ring == 0 ? ReflexConstants.RETREAT_DISTANCE : ReflexConstants.RETREAT_DISTANCE * 0.6;
            for (int i = 0; i < ANGLES; i++) {
                double angle = Math.PI * 2.0 * i / ANGLES;
                int x = (int) Math.floor(here.x + Math.cos(angle) * radius);
                int z = (int) Math.floor(here.z + Math.sin(angle) * radius);
                BlockPos spot = Cells.standableIn(ctx.level, x, z, by + 3, by - 5);
                if (spot == null || isBad(spot, bad)) continue;
                double away = minDistance(Vec3.atBottomCenterOf(spot), threats);
                if (away < hereMin + 3.0) continue;
                // Not a run that goes past the threat: a path that comes noticeably closer than the body is now, and
                // within a creeper's fuse range, is a way to be caught.
                double pass = minDistanceAlong(here, Vec3.atBottomCenterOf(spot), threats);
                if (pass < hereMin - 1.0 && pass < PASS_CLEARANCE) continue;
                // Farther from the threats is better; a long climb or drop to get there is worse; a far ring is a little better.
                double score = away - 0.4 * Math.abs(spot.getY() - by) + (ring == 0 ? 1.0 : 0.0) + (clear(ctx, spot) ? 10.0 : 0.0) - shotAt(ctx, spot, threats);
                if (score > bestScore) {
                    bestScore = score;
                    best = spot;
                }
            }
        }
        return best;
    }

    /**
     * The nearest standable spot that none of the threats can see, or null when there is none within
     * {@link ReflexConstants#HIDE_SEARCH_RADIUS}. This is the retreat from a shooter that cannot be reached: far from it
     * is no help while it has a line of fire, and standing at the foot of what it stands on is what a body does by
     * itself and gets shot for. A spot is hidden when no threat's eyes can see the body's eyes or its middle there (the
     * rule the threat scan uses, and a little stricter, since arrows are aimed low). Rings of growing distance are
     * searched, so the first hidden spot found is the nearest; a run that passes close to a threat is not taken.
     */
    @Nullable
    static BlockPos pickHidden(ReflexContext ctx, List<Threat> threats, List<BlockPos> bad) {
        BotPlayer body = ctx.body;
        BlockPos origin = body.blockPosition();
        Vec3 here = body.position();
        double hereMin = minDistance(here, threats);
        for (int ring = 1; ring <= ReflexConstants.HIDE_SEARCH_RADIUS; ring++) {
            BlockPos best = null;
            double bestScore = Double.NEGATIVE_INFINITY;
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    BlockPos spot = Cells.standableIn(ctx.level, origin.getX() + dx, origin.getZ() + dz, origin.getY() + 3, origin.getY() - 4);
                    if (spot == null || isBad(spot, bad) || !hiddenFromAll(ctx, spot, threats)) continue;
                    Vec3 at = Vec3.atBottomCenterOf(spot);
                    double pass = minDistanceAlong(here, at, threats);
                    if (pass < hereMin - 1.0 && pass < PASS_CLEARANCE) continue;
                    double score = -0.4 * Math.abs(spot.getY() - origin.getY()) - at.distanceTo(here) * 0.1 + (clear(ctx, spot) ? 4.0 : 0.0);
                    if (score > bestScore) {
                        bestScore = score;
                        best = spot;
                    }
                }
            }
            if (best != null) return best;
        }
        return null;
    }

    /** No threat sees the body's eyes, or its middle, with its feet at {@code spot}. */
    private static boolean hiddenFromAll(ReflexContext ctx, BlockPos spot, List<Threat> threats) {
        Vec3 base = Vec3.atBottomCenterOf(spot);
        Vec3 eyes = base.add(0.0, ctx.body.getEyeHeight(), 0.0);
        Vec3 middle = base.add(0.0, ctx.body.getBbHeight() * 0.5, 0.0);
        for (Threat threat : threats) {
            Mob mob = threat.mob();
            if (sees(ctx, mob, eyes) || sees(ctx, mob, middle)) return false;
        }
        return true;
    }

    private static boolean sees(ReflexContext ctx, Mob mob, Vec3 point) {
        return ctx.level.clip(new ClipContext(mob.getEyePosition(), point, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mob)).getType()
            == HitResult.Type.MISS;
    }

    /**
     * What it costs to stand at the spot in sight of a mob that shoots (a skeleton): running in the open from one is
     * running into its arrows, and a hill between them ends it.
     */
    private static double shotAt(ReflexContext ctx, BlockPos spot, List<Threat> threats) {
        double cost = 0.0;
        Vec3 head = Vec3.atBottomCenterOf(spot).add(0.0, ctx.body.getEyeHeight(), 0.0);
        for (Threat threat : threats) {
            if (!(threat.mob() instanceof RangedAttackMob)) continue;
            Mob shooter = threat.mob();
            ClipContext line = new ClipContext(shooter.getEyePosition(), head, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, shooter);
            if (ctx.level.clip(line).getType() == HitResult.Type.MISS) cost += RANGED_EXPOSURE_COST;
        }
        return cost;
    }

    private static boolean isBad(BlockPos spot, List<BlockPos> bad) {
        for (BlockPos b : bad) {
            if (b.distSqr(spot) <= BAD_SPOT_RADIUS * BAD_SPOT_RADIUS) return true;
        }
        return false;
    }

    /** Nothing solid between the body's eyes and a point a head's height above the spot's floor. */
    private static boolean clear(ReflexContext ctx, BlockPos spot) {
        Vec3 from = ctx.body.getEyePosition();
        Vec3 to = Vec3.atBottomCenterOf(spot).add(0.0, ctx.body.getEyeHeight(), 0.0);
        return ctx.level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, ctx.body)).getType() == HitResult.Type.MISS;
    }

    /** How near the straight line from {@code a} to {@code b} (on the ground plane) comes to any threat. */
    private static double minDistanceAlong(Vec3 a, Vec3 b, List<Threat> threats) {
        double abx = b.x - a.x;
        double abz = b.z - a.z;
        double lengthSqr = abx * abx + abz * abz;
        double min = Double.MAX_VALUE;
        for (Threat threat : threats) {
            Vec3 p = threat.mob().position();
            double t = lengthSqr < 1.0E-9 ? 0.0 : Math.max(0.0, Math.min(1.0, ((p.x - a.x) * abx + (p.z - a.z) * abz) / lengthSqr));
            double dx = a.x + abx * t - p.x;
            double dz = a.z + abz * t - p.z;
            min = Math.min(min, Math.sqrt(dx * dx + dz * dz));
        }
        return min;
    }

    private static double minDistance(Vec3 from, List<Threat> threats) {
        double min = Double.MAX_VALUE;
        for (Threat threat : threats) min = Math.min(min, from.distanceTo(threat.mob().position()));
        return min;
    }
}
