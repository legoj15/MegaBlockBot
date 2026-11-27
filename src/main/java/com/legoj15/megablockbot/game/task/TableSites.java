package com.legoj15.megablockbot.game.task;

import com.legoj15.megablockbot.game.body.BotPlayer;
import com.legoj15.megablockbot.game.safety.BlockSafety;
import com.legoj15.megablockbot.game.safety.Verdict;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Where a crafting table can be put down, and where the body has to stand to put it there (design 3.4, the craft task).
 *
 * <p>The rule is the one a player is held to, read from {@code BlockItem.useOn} and {@code BlockPlaceContext}: the spot
 * is replaceable, no entity that blocks building stands in it, and a neighbouring block that is not itself replaceable
 * gives a face to click that the eyes can see and reach. Nothing else is asked of that neighbour: a table goes on
 * leaves, on a pillar's side, against a wall. (A sturdy face is not required; the rule the task used to have, that the
 * block below must be sturdy, refused every spot in a tree canopy.)
 *
 * <p>One thing is asked on top of the game's rule, and only because the task takes the table up again: something below
 * the spot has a collision shape, so the table's item comes to rest beside the body when the table is broken. A table
 * on a ledge over a drop would give its item to the drop.
 *
 * <p>The click is made while sneaking, as a player builds against a chest or a door (the click would otherwise open it),
 * so every look uses the sneaking eye height.
 *
 * <p>Server thread only: it reads the level and asks block safety.
 */
public final class TableSites {
    /**
     * How far, in blocks, the search for a place to stand reaches in every direction from the body when no spot beside
     * it will do. A cube of this half-width around the body's feet.
     */
    public static final int DEFAULT_RADIUS = 12;

    /** Spots are looked at this far from the standing place sideways and this far above and below it. */
    private static final int REACH_SIDEWAYS = 3;
    private static final int REACH_VERTICAL = 2;

    private static final BlockState TABLE = Blocks.CRAFTING_TABLE.defaultBlockState();

    /** Where on a face the eyes aim, in blocks from its centre: the centre first, then four points near its edges. */
    private static final double[][] AIM = {{0, 0}, {0.3, 0}, {-0.3, 0}, {0, 0.3}, {0, -0.3}};
    /** The faces tried for one spot: the block below first (a table on the floor), the ceiling last. */
    private static final Direction[] FACES = {Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST,
        Direction.UP};

    /** Spots around a standing place, in the order they are tried: level with the feet first, near before far. */
    private static final List<int[]> OFFSETS = offsets();

    /** A body's own radius, for a test that has to make "nowhere" small enough to build. */
    private static final Map<UUID, Integer> RADII = new ConcurrentHashMap<>();

    private TableSites() {}

    /** One way to put the table down: click {@code face} of {@code against} at {@code click}, and it appears at {@code spot}. */
    record Site(BlockPos spot, BlockPos against, Direction face, Vec3 click) {}

    /** The first spot a look found that the body could place on but may not break again, kept for the failure. */
    static final class Refusal {
        @Nullable
        Verdict verdict;
        @Nullable
        BlockPos at;

        void note(Verdict why, BlockPos where) {
            if (verdict == null) {
                verdict = why;
                at = where;
            }
        }

        boolean any() {
            return verdict != null;
        }
    }

    /** The search radius for this body: {@link #DEFAULT_RADIUS} unless a test narrowed it. */
    static int radius(UUID body) {
        return RADII.getOrDefault(body, DEFAULT_RADIUS);
    }

    /** Narrows (or widens) the search for this body only, so that a test arena can hold a place with nowhere to go. */
    public static void setRadius(UUID body, int radius) {
        if (radius < 1) throw new IllegalArgumentException("a search radius is at least 1, not " + radius);
        RADII.put(body, radius);
    }

    public static void clearRadius(UUID body) {
        RADII.remove(body);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Looking from one place
    // ---------------------------------------------------------------------------------------------------------

    /** The eye of a body that stands where it is and sneaks: the eye the click is made from. */
    static Vec3 sneakingEye(BotPlayer body, BlockPos stand, boolean exactly) {
        double eye = body.getDimensions(Pose.CROUCHING).eyeHeight();
        return exactly ? new Vec3(body.getX(), body.getY() + eye, body.getZ())
            : new Vec3(stand.getX() + 0.5, stand.getY() + eye, stand.getZ() + 0.5);
    }

    /**
     * The sites a body could use from {@code stand}, best first, at most {@code limit}. {@code here} says the body is
     * really there, so its own box also counts as something in the way; for a place it has yet to walk to only the cells
     * it will stand in are ruled out.
     */
    static List<Site> sitesFrom(BotPlayer body, ServerLevel level, BlockPos stand, Vec3 eye, boolean here, int limit,
                                Refusal refusal) {
        List<Site> found = new ArrayList<>();
        // Every block read below, and every ray, stays within this box: none of it may load a chunk.
        if (!level.hasChunksAt(stand.offset(-REACH_SIDEWAYS - 2, -REACH_VERTICAL - 3, -REACH_SIDEWAYS - 2),
            stand.offset(REACH_SIDEWAYS + 2, REACH_VERTICAL + 3, REACH_SIDEWAYS + 2))) {
            return found;
        }
        BlockSafety safety = BlockSafety.of(level.getServer());
        double reach = body.blockInteractionRange();
        for (int[] offset : OFFSETS) {
            BlockPos spot = stand.offset(offset[0], offset[1], offset[2]);
            if (spot.equals(stand) || spot.equals(stand.above())) continue; // where the body will be
            if (!level.isLoaded(spot) || level.isOutsideBuildHeight(spot)) continue;
            if (!level.getBlockState(spot).canBeReplaced()) continue;
            BlockPos under = spot.below();
            if (level.getBlockState(under).getCollisionShape(level, under).isEmpty()) continue; // the item would fall away
            if (!unobstructed(body, level, spot, here)) continue;

            Site site = faceFor(body, level, spot, eye, reach);
            if (site == null) continue;
            Verdict verdict = safety.mayBreak(body, level, spot, TABLE);
            if (!verdict.allowed()) {
                refusal.note(verdict, spot);
                continue;
            }
            found.add(site);
            if (found.size() >= limit) break;
        }
        return found;
    }

    private static boolean unobstructed(BotPlayer body, ServerLevel level, BlockPos spot, boolean here) {
        VoxelShape cell = Shapes.block().move(spot.getX(), spot.getY(), spot.getZ());
        // Passing the body skips it (it is a passenger of its own vehicle): everyone else who blocks building counts.
        if (!level.isUnobstructed(body, cell)) return false;
        // The game's own question, which also counts the body where it stands.
        return !here || level.isUnobstructed(TABLE, spot, CollisionContext.of(body));
    }

    /** A face of a neighbour of {@code spot} that the eyes can see and reach, as a click, or null. */
    @Nullable
    private static Site faceFor(BotPlayer body, ServerLevel level, BlockPos spot, Vec3 eye, double reach) {
        for (Direction toward : FACES) {
            BlockPos against = spot.relative(toward);
            if (!level.isLoaded(against) || level.isOutsideBuildHeight(against)) continue;
            BlockState state = level.getBlockState(against);
            // A replaceable neighbour is replaced by the click, not built against: that is another spot's business.
            if (state.canBeReplaced() || state.getShape(level, against).isEmpty()) continue;
            if (!level.mayInteract(body, against)) continue;
            Direction face = toward.getOpposite();
            Vec3 click = aim(body, level, against, face, eye, reach);
            if (click != null) return new Site(spot, against, face, click);
        }
        return null;
    }

    /** Where on {@code face} of {@code block} a ray from the eye lands on that very face within reach, or null. */
    @Nullable
    private static Vec3 aim(BotPlayer body, ServerLevel level, BlockPos block, Direction face, Vec3 eye, double reach) {
        Vec3 centre = Vec3.atCenterOf(block).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        for (double[] aim : AIM) {
            double u = aim[0];
            double v = aim[1];
            Vec3 target = switch (face.getAxis()) {
                case X -> centre.add(0, u, v);
                case Y -> centre.add(u, 0, v);
                case Z -> centre.add(u, v, 0);
            };
            if (eye.distanceToSqr(target) > reach * reach) continue;
            // A hair past the face, so the ray surely crosses it.
            Vec3 through = target.subtract(face.getStepX() * 0.02, face.getStepY() * 0.02, face.getStepZ() * 0.02);
            BlockHitResult hit = level.clip(new ClipContext(eye, through, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, body));
            if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(block) && hit.getDirection() == face) {
                return hit.getLocation();
            }
        }
        return null;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Looking for a place to stand
    // ---------------------------------------------------------------------------------------------------------

    /**
     * The nearest place the body can stand that has a usable spot beside it, or null when there is none within
     * {@code radius} blocks of its feet. Searched shell by shell outward, so the first shell with a place holds the
     * nearest; {@code skip} names places not to offer again (where the body already is, where a walk already failed).
     */
    @Nullable
    static BlockPos nearestStand(BotPlayer body, ServerLevel level, int radius, Set<BlockPos> skip, Refusal refusal) {
        BlockPos centre = body.blockPosition();
        for (int r = 1; r <= radius; r++) {
            BlockPos best = null;
            double bestDistance = Double.MAX_VALUE;
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    boolean edge = Math.abs(dx) == r || Math.abs(dz) == r;
                    for (int dy = -r; dy <= r; dy += edge ? 1 : 2 * r) {
                        BlockPos stand = centre.offset(dx, dy, dz);
                        if (skip.contains(stand) || !standable(level, stand)) continue;
                        double distance = stand.distSqr(centre);
                        if (distance >= bestDistance) continue;
                        if (sitesFrom(body, level, stand, sneakingEye(body, stand, false), false, 1, refusal).isEmpty()) continue;
                        best = stand.immutable();
                        bestDistance = distance;
                    }
                }
            }
            if (best != null) return best;
        }
        return null;
    }

    /** Feet and head cell free to stand in, and a floor that holds a body up at its full height. */
    static boolean standable(ServerLevel level, BlockPos feet) {
        if (!level.isLoaded(feet) || level.isOutsideBuildHeight(feet) || !level.getWorldBorder().isWithinBounds(feet)) return false;
        BlockPos floor = feet.below();
        if (!level.isLoaded(floor) || !level.isLoaded(feet.above())) return false;
        if (!passable(level, feet) || !passable(level, feet.above())) return false;
        BlockState below = level.getBlockState(floor);
        VoxelShape top = below.getCollisionShape(level, floor);
        // Full height: a body stands in the cell above it. Magma burns and a campfire is not a floor.
        return !top.isEmpty() && top.max(Direction.Axis.Y) >= 1.0 && !below.is(Blocks.MAGMA_BLOCK)
            && !below.is(BlockTags.CAMPFIRES);
    }

    private static boolean passable(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.getCollisionShape(level, pos).isEmpty() && state.getFluidState().isEmpty() && !state.is(BlockTags.FIRE)
            && !state.is(Blocks.COBWEB) && !state.is(Blocks.SWEET_BERRY_BUSH) && !state.is(Blocks.POWDER_SNOW);
    }

    private static List<int[]> offsets() {
        List<int[]> all = new ArrayList<>();
        for (int dx = -REACH_SIDEWAYS; dx <= REACH_SIDEWAYS; dx++) {
            for (int dz = -REACH_SIDEWAYS; dz <= REACH_SIDEWAYS; dz++) {
                for (int dy = -REACH_VERTICAL; dy <= REACH_VERTICAL; dy++) all.add(new int[] {dx, dy, dz});
            }
        }
        all.sort(Comparator.<int[]>comparingInt(o -> Math.abs(o[1]))
            .thenComparingInt(o -> Math.max(Math.abs(o[0]), Math.abs(o[2])))
            .thenComparingInt(o -> Math.abs(o[0]) + Math.abs(o[2]))
            .thenComparingInt(o -> o[1])
            .thenComparingInt(o -> o[0])
            .thenComparingInt(o -> o[2]));
        return List.copyOf(all);
    }
}
