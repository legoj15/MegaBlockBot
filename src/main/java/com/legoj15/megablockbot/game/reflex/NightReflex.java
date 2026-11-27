package com.legoj15.megablockbot.game.reflex;

import com.legoj15.megablockbot.MegaBlockBot;
import com.legoj15.megablockbot.game.body.BotPlayer;
import com.legoj15.megablockbot.game.nav.BotNavigator;
import com.legoj15.megablockbot.game.reflex.ReflexConstants.NightPolicy;
import com.legoj15.megablockbot.game.reflex.ReflexLayer.NightResult;
import com.legoj15.megablockbot.game.safety.BlockSafety;
import com.legoj15.megablockbot.game.safety.PlacedBlocks;
import com.legoj15.megablockbot.game.safety.Verdict;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Priority 4 (design 3.2): night and exposure. The policy is {@link NightPolicy}: {@code IGNORE} does nothing;
 * {@code SHELTER} (the default) builds a closed one-by-two shelter from the blocks the body carries at dusk, waits in it
 * until morning and takes it down again, picking its blocks back up; {@code BED} sleeps in a bed this body owns nearby
 * and falls back to the shelter when there is none (or it cannot sleep).
 *
 * <p>The shelter is the body's own cell and the one above it (the "inside"), walled on its four sides at both heights and
 * capped. It is built the way a player builds it, from inside, bottom up: each block is placed by clicking a face of a
 * neighbour that the eyes can see and reach. The four walls at foot level stand on the floor; the four at head level
 * stand on those; the cap has nothing to stand on, so a block is first put on top of one of the head-level walls (from
 * the top of a jump, when the eyes are above it) and the cap is then placed against that one's inner face. A cell that
 * is already solid (a hillside, a tree) needs no block, so the shelter takes as many blocks as the spot needs, ten at
 * most. Every spot is checked with {@link BlockSafety} first, because the shelter has to be taken down again and a
 * protected spot would trap the body in it.
 *
 * <p>Only a body under the open sky is exposed: monsters spawn in the dark at any hour underground, so a night shelter
 * there changes nothing. A body that holds nothing to build with does not take the body at all; it keeps working and the
 * mob reflex fights what comes.
 *
 * <p><b>Nights.</b> The night a hold is for is recorded when the hold starts ({@link #holdNight}), and that is the night
 * marked done when it ends: a hold ends after midnight of the clock too (a bed skips to morning, a takedown runs on), when
 * the night index of "now" already names the next night. A night is marked done only when it was spent: a shelter that was
 * closed, a bed that was slept in. A policy changed under a walk to the bed spends nothing.
 *
 * <p><b>The blocks it placed</b> are recorded in the layer ({@link ShelterBlocks}), not here, and leave the record only when
 * the world no longer holds them. A takedown that cannot finish (the time ran out, the body was carried off by a fight,
 * the break rules refused a block) ends and is tried again by a later calm tick in daylight; the walk back to a block that
 * is out of reach is part of it.
 */
final class NightReflex implements Reflex {
    private enum Phase { IDLE, GO_TO_SPOT, GO_TO_BED, SLEEP, BUILD, WAIT, TAKEDOWN, RETURN, COLLECT }

    private static final int DAY = 24000;

    private Phase phase = Phase.IDLE;
    private long phaseSince;
    private long retryAt;
    private long doneNight = Long.MIN_VALUE;
    private long bedFailedNight = Long.MIN_VALUE;
    /** The night the current hold is for, taken when it started. See the class comment. */
    private long holdNight = Long.MIN_VALUE;
    /** The body was seen asleep in this hold. */
    private boolean slept;
    /** This hold built (or tried to build) a shelter, so that its end says how the night went. */
    private boolean shelterNight;
    private String cause = "";
    private String outcome = "";

    // The shelter.
    @Nullable
    private BlockPos feet;
    private Vec3 middle = Vec3.ZERO;
    /** The recorded block being taken down now. */
    @Nullable
    private ShelterBlocks.Entry target;
    /** Blocks of this takedown that could not be reached: not asked again until the next attempt. */
    private final Set<BlockPos> skipped = new HashSet<>();
    private long takedownSince;
    private long lastPlacedAt = -1_000_000L;
    private long lastJumpAt = -1_000_000L;
    private int placements;
    // The shelter was closed at some point this night: only then is the night done. One that never closed is tried again.
    private boolean closedOnce;
    private int blocksAtStart;
    private long lastCheck;

    // Looking for a spot that will do: where the body walks to, the spots that turned out no good, and what to say meanwhile.
    @Nullable
    private BlockPos spot;
    private final List<BlockPos> badSpots = new ArrayList<>();
    private String waiting = "";

    // The bed.
    @Nullable
    private BlockPos bed;

    @Override
    public String name() {
        return "night";
    }

    @Override
    public String cause() {
        return cause;
    }

    @Override
    public String outcome() {
        return outcome;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Time and sky
    // ---------------------------------------------------------------------------------------------------------

    /** Dusk to just before sunrise, in a dimension that has a day. */
    static boolean isNight(ServerLevel level) {
        DimensionType type = level.dimensionType();
        if (!type.hasSkyLight() || type.hasFixedTime()) return false;
        long t = Math.floorMod(level.getDayTime(), (long) DAY);
        return t >= ReflexConstants.NIGHT_BEGIN && t < ReflexConstants.NIGHT_END;
    }

    private static long nightIndex(ServerLevel level) {
        return Math.floorDiv(level.getDayTime(), (long) DAY);
    }

    private static boolean exposed(ReflexContext ctx) {
        return ctx.level.getBrightness(LightLayer.SKY, ctx.body.blockPosition()) >= ReflexConstants.SKY_EXPOSURE_MIN_LIGHT;
    }

    // ---------------------------------------------------------------------------------------------------------
    // The question
    // ---------------------------------------------------------------------------------------------------------

    @Override
    public boolean wants(ReflexContext ctx) {
        if (phase != Phase.IDLE) return true;
        NightPolicy policy = ctx.layer.nightPolicy();
        ServerLevel level = ctx.level;
        long night = nightIndex(level);
        // A calm tick in daylight: shelter blocks that still stand (a takedown that could not finish, a shelter that was
        // abandoned) are never forgotten while they stand, so they are taken down now.
        ShelterBlocks shelter = ctx.layer.shelterBlocks();
        if (shelter.size() > 0 && !isNight(level) && ctx.time >= retryAt) {
            shelter.prune(level);
            if (!shelter.due(level, ctx.time, ctx.body).isEmpty()) {
                beginTakeDown(ctx, "shelter blocks of an earlier night still stand");
                return true;
            }
        }
        if (policy == NightPolicy.IGNORE || !isNight(level) || night == doneNight) return false;
        if (ctx.time < retryAt) {
            // Still waiting to look again: the reason is worth saying every tick, not only on the one that found it.
            if (!waiting.isEmpty()) ctx.layer.note(waiting);
            return false;
        }
        if (!exposed(ctx)) return false;

        if (policy == NightPolicy.BED && bedFailedNight != night) {
            BlockPos owned = ownedBed(ctx);
            if (owned != null) {
                bed = owned;
                startNight(ctx);
                enter(Phase.GO_TO_BED, ctx);
                cause = "going to bed at " + owned.toShortString();
                return true;
            }
        }
        if (Hands.countShelterBlocks(ctx.body) == 0) {
            ctx.layer.note("night, and nothing to build a shelter with");
            return false;
        }
        boolean grounded = ctx.body.onGround() && !ctx.body.isInWater();
        String problem = grounded ? shelterProblem(ctx, ctx.body.blockPosition()) : "the body is not standing on the ground";
        if (problem == null) {
            startNight(ctx);
            beginShelter(ctx);
            return true;
        }
        // Not here: somewhere near that will do, if there is such a place.
        BlockPos better = grounded ? findSpot(ctx) : null;
        if (better != null) {
            spot = better;
            startNight(ctx);
            enter(Phase.GO_TO_SPOT, ctx);
            cause = "walking to a better spot for a shelter at " + better.toShortString() + " (" + problem + " here)";
            return true;
        }
        waiting = "night, but " + problem + ", and no better spot near";
        ctx.layer.note(waiting);
        retryAt = ctx.time + ReflexConstants.SHELTER_RETRY_TICKS;
        return false;
    }

    @Override
    public void release(ReflexContext ctx) {
        // Handed to a higher reflex (a fight): the plan, and the blocks already placed, are kept for when it is over.
        ctx.layer.work().abort(ctx.body);
        if (ctx.body.isSleeping()) ctx.body.stopSleepInBed(true, true);
        if (phase == Phase.GO_TO_BED || phase == Phase.GO_TO_SPOT || phase == Phase.RETURN) ctx.nav.stop();
        target = null;
    }

    /** The hold for a night starts: which night it is, and nothing slept yet. */
    private void startNight(ReflexContext ctx) {
        holdNight = nightIndex(ctx.level);
        slept = false;
        shelterNight = false;
    }

    // ---------------------------------------------------------------------------------------------------------
    // The action
    // ---------------------------------------------------------------------------------------------------------

    @Override
    public void act(ReflexContext ctx) {
        boolean night = isNight(ctx.level);
        NightPolicy policy = ctx.layer.nightPolicy();
        ctx.body.input().sneak = false;
        switch (phase) {
            case BUILD -> build(ctx, night, policy);
            case WAIT -> waitInside(ctx, night, policy);
            case GO_TO_SPOT -> goToSpot(ctx, night, policy);
            case GO_TO_BED -> goToBed(ctx, night, policy);
            case SLEEP -> sleep(ctx, night, policy);
            case TAKEDOWN -> takeDown(ctx);
            case RETURN -> returnToTarget(ctx);
            case COLLECT -> collect(ctx);
            case IDLE -> Steer.stand(ctx.body);
        }
    }

    private void enter(Phase next, ReflexContext ctx) {
        phase = next;
        phaseSince = ctx.time;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Shelter: choosing the spot
    // ---------------------------------------------------------------------------------------------------------

    /** Null when a shelter can be built with its inside at {@code at}; otherwise why not, in a few words. */
    @Nullable
    private String shelterProblem(ReflexContext ctx, BlockPos at) {
        BotPlayer body = ctx.body;
        ServerLevel level = ctx.level;
        if (!Cells.standable(level, at)) return "there is no clear solid floor to build on";
        int needed = 0;
        for (BlockPos cell : shelterCells(at)) {
            if (filled(level, cell)) continue;
            BlockState state = level.getBlockState(cell);
            if (!state.getFluidState().isEmpty()) return "there is water or lava next to the spot";
            // Grass is built over; a flower or a sapling is picked first (see build); anything with a body to it is in the way.
            if (!state.canBeReplaced() && !state.getCollisionShape(level, cell).isEmpty()) return "something is in the way at " + cell.toShortString();
            // A foot-level wall stands on the ground: with a drop under it there is nothing to place it against.
            if (cell.getY() == at.getY() && !level.getBlockState(cell.below()).isFaceSturdy(level, cell.below(), Direction.UP)) {
                return "there is no ground under the wall at " + cell.toShortString();
            }
            // The shelter has to come down again: ask now whether the block there could be broken, as the answer for a
            // position does not depend on what is in it (the protected radius and the claims).
            Verdict verdict = BlockSafety.of(level.getServer()).mayBreak(body, level, cell, Blocks.DIRT.defaultBlockState());
            if (!verdict.allowed()) return "the spot is protected (" + verdict.rule() + ")";
            needed++;
        }
        BlockPos cap = at.above(2);
        if (!filled(level, cap) && !capHasAnchor(level, cap, at)) {
            BlockPos scaffold = scaffoldCell(level, cap);
            if (scaffold == null) return "the cap has nothing to be placed against";
            Verdict verdict = BlockSafety.of(level.getServer()).mayBreak(body, level, scaffold, Blocks.DIRT.defaultBlockState());
            if (!verdict.allowed()) return "the spot is protected (" + verdict.rule() + ")";
            needed++;
        }
        int have = Hands.countShelterBlocks(body);
        if (needed > have) return "only " + have + " blocks to build with, " + needed + " needed here";
        return null;
    }

    /**
     * The nearest standable cell within {@link ReflexConstants#SPOT_SEARCH_RADIUS} blocks where a shelter can be built,
     * leaving out the spots that already turned out no good; null when there is none.
     */
    @Nullable
    private BlockPos findSpot(ReflexContext ctx) {
        BlockPos origin = ctx.body.blockPosition();
        int r = ReflexConstants.SPOT_SEARCH_RADIUS;
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx == 0 && dz == 0) continue;
                BlockPos cell = Cells.standableIn(ctx.level, origin.getX() + dx, origin.getZ() + dz, origin.getY() + 2, origin.getY() - 3);
                if (cell == null || badSpots.stream().anyMatch(b -> b.closerThan(cell, 1.5))) continue;
                double distance = cell.distSqr(origin);
                if (distance >= bestDistance) continue;
                if (shelterProblem(ctx, cell) != null) continue;
                bestDistance = distance;
                best = cell;
            }
        }
        return best;
    }

    /** Walks to the spot {@link #findSpot} chose, and starts building when the body is in it and it still will do. */
    private void goToSpot(ReflexContext ctx, boolean night, NightPolicy policy) {
        BotPlayer body = ctx.body;
        if (!night || policy == NightPolicy.IGNORE || spot == null) {
            ctx.nav.stop();
            phase = Phase.IDLE;
            return;
        }
        if (body.blockPosition().equals(spot) && body.onGround()) {
            ctx.nav.stop();
            if (shelterProblem(ctx, spot) == null) {
                beginShelter(ctx);
            } else {
                badSpots.add(spot);
                phase = Phase.IDLE;
                retryAt = ctx.time;
            }
            return;
        }
        if (ctx.nav.state() == BotNavigator.State.FAILED || ctx.time - phaseSince > ReflexConstants.SPOT_WALK_TICKS) {
            // Cannot be got to, or too slow: not that one.
            badSpots.add(spot);
            ctx.nav.stop();
            phase = Phase.IDLE;
            retryAt = ctx.time;
            return;
        }
        if (ctx.nav.state() != BotNavigator.State.WORKING) ctx.goTo(spot, 0);
    }

    private void beginShelter(ReflexContext ctx) {
        feet = ctx.body.blockPosition().immutable();
        middle = Vec3.atBottomCenterOf(feet);
        waiting = "";
        badSpots.clear();
        target = null;
        placements = 0;
        closedOnce = false;
        shelterNight = true;
        blocksAtStart = Hands.countShelterBlocks(ctx.body);
        enter(Phase.BUILD, ctx);
        cause = "building a shelter at " + feet.toShortString();
        MegaBlockBot.LOGGER.info("reflex {}: night shelter started at {} with {} blocks", ctx.body.getGameProfile().getName(), feet, blocksAtStart);
    }

    /** The eight walls and the cap around the inside. */
    private static List<BlockPos> shelterCells(BlockPos feet) {
        List<BlockPos> cells = new ArrayList<>();
        for (Direction d : Direction.Plane.HORIZONTAL) cells.add(feet.relative(d));
        for (Direction d : Direction.Plane.HORIZONTAL) cells.add(feet.above().relative(d));
        cells.add(feet.above(2));
        return cells;
    }

    private static boolean filled(ServerLevel level, BlockPos cell) {
        return level.getBlockState(cell).isCollisionShapeFullBlock(level, cell);
    }

    /** Some neighbour of the cap (not the inside below it) already has a solid face toward it, so a block can be placed against that. */
    private static boolean capHasAnchor(ServerLevel level, BlockPos cap, BlockPos feet) {
        for (Direction d : Direction.values()) {
            BlockPos neighbour = cap.relative(d);
            if (neighbour.equals(feet.above())) continue; // the inside itself
            if (level.getBlockState(neighbour).isFaceSturdy(level, neighbour, d.getOpposite())) return true;
        }
        return false;
    }

    /**
     * The cell, beside the cap and on top of a head-level wall, to put a block in first when the cap has nothing to be
     * placed against: that block becomes the cap's neighbour. Null when no side of the cap is free to take one.
     */
    @Nullable
    private static BlockPos scaffoldCell(ServerLevel level, BlockPos cap) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos candidate = cap.relative(d);
            if (level.getBlockState(candidate).canBeReplaced() && level.getFluidState(candidate).isEmpty()) return candidate;
        }
        return null;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Shelter: building
    // ---------------------------------------------------------------------------------------------------------

    private void build(ReflexContext ctx, boolean night, NightPolicy policy) {
        BotPlayer body = ctx.body;
        if (!night || policy == NightPolicy.IGNORE) {
            beginTakeDown(ctx, !night ? "morning" : "policy changed");
            return;
        }
        if (feet == null || body.position().distanceTo(middle) > 2.0) {
            abandon(ctx, "the body was moved away from the spot");
            return;
        }
        if (ctx.time - phaseSince > ReflexConstants.BUILD_TIMEOUT_TICKS) {
            retryAt = ctx.time + ReflexConstants.SHELTER_RETRY_TICKS;
            beginTakeDown(ctx, "the shelter could not be closed in time, cells still open: " + missingCells(ctx).stream().map(BlockPos::toShortString).toList());
            return;
        }

        Steer.stand(body);
        body.input().sneak = true;
        // The walls must not touch the body's box, and the cap is placed against a wall's inner face: stand in the middle.
        Vec3 off = new Vec3(middle.x - body.getX(), 0.0, middle.z - body.getZ());
        if (off.length() > ReflexConstants.CENTER_TOLERANCE) {
            Steer.walkToward(body, new Vec3(middle.x, body.getEyeY(), middle.z), false);
            body.input().sneak = true;
            cause = "building a shelter: taking the middle of the spot";
            return;
        }

        List<BlockPos> missing = missingCells(ctx);
        if (missing.isEmpty()) {
            closedOnce = true;
            long took = ctx.time - phaseSince;
            enter(Phase.WAIT, ctx);
            cause = "in the shelter, waiting for morning";
            MegaBlockBot.LOGGER.info("reflex {}: night shelter closed at {} after {} ticks, {} blocks placed", body.getGameProfile().getName(), feet,
                took, placements);
            return;
        }
        cause = "building a shelter: " + missing.size() + " more cells to fill";
        for (BlockPos cell : missing) {
            BlockState there = ctx.level.getBlockState(cell);
            if (there.isAir() || there.canBeReplaced()) continue;
            // A flower or a sapling is in the way: a player picks it first (and the break rules are asked, as for any break).
            BlockWork.Outcome cleared = ctx.layer.work().breakBlock(ctx, cell);
            if (cleared == BlockWork.Outcome.WORKING || cleared == BlockWork.Outcome.DONE) {
                cause = "building a shelter: clearing " + cell.toShortString();
                return;
            }
            retryAt = ctx.time + ReflexConstants.SHELTER_RETRY_TICKS;
            beginTakeDown(ctx, "something in the way at " + cell.toShortString() + " cannot be cleared");
            return;
        }
        int slot = Hands.buildingBlock(body);
        if (slot < 0) {
            retryAt = ctx.time + ReflexConstants.SHELTER_RETRY_TICKS;
            beginTakeDown(ctx, "out of blocks to build with");
            return;
        }
        if (!Hands.isShelterBlock(body.getMainHandItem())) Hands.toMainHand(body, slot);
        if (ctx.time - lastPlacedAt < ReflexConstants.PLACE_DELAY_TICKS) return;
        for (BlockPos cell : missing) {
            if (ctx.layer.work().place(ctx, cell)) {
                ctx.layer.shelterBlocks().add(ctx.level, cell, ctx.level.getBlockState(cell).getBlock());
                lastPlacedAt = ctx.time;
                placements++;
                return;
            }
        }
        // Nothing can be reached from the ground: what is left is the cap and its scaffold, which want eyes above the
        // head-level walls, so jump (and try again on the way up).
        boolean onlyHigh = true;
        for (BlockPos cell : missing) onlyHigh &= cell.getY() >= feet.getY() + 2;
        if (onlyHigh && body.onGround() && ctx.time - lastJumpAt > 15) {
            body.input().jump = true;
            lastJumpAt = ctx.time;
        }
    }

    /** The cells still to be filled, in building order: foot-level walls, head-level walls, then the scaffold and the cap. */
    private List<BlockPos> missingCells(ReflexContext ctx) {
        ServerLevel level = ctx.level;
        List<BlockPos> missing = new ArrayList<>();
        BlockPos at = feet;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos cell = at.relative(d);
            if (!filled(level, cell)) missing.add(cell);
        }
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos cell = at.above().relative(d);
            if (!filled(level, cell)) missing.add(cell);
        }
        BlockPos cap = at.above(2);
        if (!filled(level, cap)) {
            if (!capHasAnchor(level, cap, at)) {
                BlockPos scaffold = scaffoldCell(level, cap);
                if (scaffold != null && !filled(level, scaffold)) missing.add(scaffold);
            }
            missing.add(cap);
        }
        return missing;
    }

    private void waitInside(ReflexContext ctx, boolean night, NightPolicy policy) {
        BotPlayer body = ctx.body;
        Steer.stand(body);
        if (!night || policy == NightPolicy.IGNORE) {
            beginTakeDown(ctx, !night ? "morning" : "policy changed");
            return;
        }
        if (feet == null || body.position().distanceTo(middle) > 1.5) {
            abandon(ctx, "the body is outside the shelter");
            return;
        }
        long left = ReflexConstants.NIGHT_END - Math.floorMod(ctx.level.getDayTime(), (long) DAY);
        cause = "in the shelter, waiting for morning (" + left + " ticks to go)";
        if (ctx.time - lastCheck >= 10) {
            lastCheck = ctx.time;
            if (!missingCells(ctx).isEmpty()) {
                // Something broke a wall (a creeper, an enderman): close it again.
                enter(Phase.BUILD, ctx);
                cause = "repairing the shelter";
            }
        }
    }

    /** The spot is no use any more (the body was driven off it): forget it, leave its blocks where they are, and look for a new spot. */
    private void abandon(ReflexContext ctx, String why) {
        // Its blocks stay recorded in the layer: a later calm tick in daylight takes them down.
        MegaBlockBot.LOGGER.info("reflex {}: night shelter at {} abandoned ({}); {} recorded blocks stand until they are taken down",
            ctx.body.getGameProfile().getName(), feet, why, ctx.layer.shelterBlocks().size());
        target = null;
        feet = null;
        phase = Phase.IDLE;
        retryAt = ctx.time;
        cause = "shelter abandoned: " + why;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Shelter: taking it down
    // ---------------------------------------------------------------------------------------------------------

    private void beginTakeDown(ReflexContext ctx, String why) {
        target = null;
        skipped.clear();
        takedownSince = ctx.time;
        enter(Phase.TAKEDOWN, ctx);
        cause = "taking the shelter down (" + why + ")";
        MegaBlockBot.LOGGER.info("reflex {}: night shelter coming down ({}), {} blocks recorded", ctx.body.getGameProfile().getName(), why,
            ctx.layer.shelterBlocks().size());
    }

    private void takeDown(ReflexContext ctx) {
        BotPlayer body = ctx.body;
        Steer.stand(body);
        ShelterBlocks shelter = ctx.layer.shelterBlocks();
        // A block that is gone, or is something else now, is not ours to break.
        shelter.prune(ctx.level);
        if (target != null && !shelter.isRecorded(target)) target = null;
        List<ShelterBlocks.Entry> due = shelter.due(ctx.level, ctx.time, body);
        due.removeIf(entry -> skipped.contains(entry.pos));
        if (due.isEmpty() || ctx.time - takedownSince > ReflexConstants.TAKEDOWN_TIMEOUT_TICKS) {
            endTakeDown(ctx);
            return;
        }
        cause = "taking the shelter down: " + due.size() + " blocks left";
        if (target == null || !due.contains(target)) target = due.get(0);
        BlockWork.Outcome result = ctx.layer.work().breakBlock(ctx, target.pos);
        switch (result) {
            case WORKING -> { }
            case DONE -> {
                shelter.remove(target);
                target = null;
            }
            case REFUSED -> {
                // The break rules say no (the shelter was put somewhere that later became protected): it stays, recorded,
                // and is asked about again much later.
                MegaBlockBot.LOGGER.warn("reflex {}: the break rules refuse the shelter block at {}; it stays recorded",
                    body.getGameProfile().getName(), target.pos);
                target.retryAt = ctx.time + ReflexConstants.REFUSED_RETRY_TICKS;
                target = null;
            }
            case UNREACHABLE -> {
                if (body.getEyePosition().distanceTo(Vec3.atCenterOf(target.pos)) > body.blockInteractionRange() - 0.5) {
                    // Too far to see a face of it: the body walks back to it (a fight may have carried it off).
                    enter(Phase.RETURN, ctx);
                    cause = "walking back to the shelter block at " + target.pos.toShortString();
                } else {
                    // Near, and no face can be seen from here: the others first, and this one at the next attempt.
                    skipped.add(target.pos);
                    target = null;
                }
            }
        }
    }

    /** Walks to within reach of the block being taken down, with the navigator (which breaks nothing: the filter is "break nothing"). */
    private void returnToTarget(ReflexContext ctx) {
        BotPlayer body = ctx.body;
        Steer.stand(body);
        ShelterBlocks shelter = ctx.layer.shelterBlocks();
        shelter.prune(ctx.level);
        if (target == null || !shelter.isRecorded(target)) {
            ctx.nav.stop();
            target = null;
            phase = Phase.TAKEDOWN;
            return;
        }
        if (body.getEyePosition().distanceTo(Vec3.atCenterOf(target.pos)) <= body.blockInteractionRange() - 1.0) {
            ctx.nav.stop();
            phase = Phase.TAKEDOWN;
            return;
        }
        boolean stuck = ctx.nav.state() == BotNavigator.State.FAILED || ctx.nav.state() == BotNavigator.State.DONE
            || ctx.time - phaseSince > ReflexConstants.SPOT_WALK_TICKS;
        if (stuck) {
            // Cannot be got to from here, or too slow: not this one now; it stays recorded for the next attempt.
            skipped.add(target.pos);
            ctx.nav.stop();
            target = null;
            phase = Phase.TAKEDOWN;
            return;
        }
        cause = "walking back to the shelter block at " + target.pos.toShortString();
        if (ctx.nav.state() != BotNavigator.State.WORKING) ctx.goTo(target.pos, 3);
    }

    /**
     * The takedown ends: everything is down, or the time is up, or what is left cannot be reached from here. What still
     * stands stays recorded and is put off for a while; a later calm tick in daylight comes back to it.
     */
    private void endTakeDown(ReflexContext ctx) {
        ShelterBlocks shelter = ctx.layer.shelterBlocks();
        ctx.nav.stop();
        target = null;
        if (shelter.size() > 0) {
            MegaBlockBot.LOGGER.warn("reflex {}: {} shelter blocks still stand; they are tried again in {} ticks",
                ctx.body.getGameProfile().getName(), shelter.size(), ReflexConstants.SHELTER_RETRY_TICKS);
            shelter.deferDue(ctx.level, ctx.time, ctx.time + ReflexConstants.SHELTER_RETRY_TICKS);
            retryAt = Math.max(retryAt, ctx.time + ReflexConstants.SHELTER_RETRY_TICKS);
        }
        enter(Phase.COLLECT, ctx);
        cause = "picking the shelter's blocks up";
    }

    private void collect(ReflexContext ctx) {
        BotPlayer body = ctx.body;
        Steer.stand(body);
        List<ItemEntity> items = ctx.level.getEntitiesOfClass(ItemEntity.class, body.getBoundingBox().inflate(6.0, 3.0, 6.0),
            item -> item.isAlive() && Hands.isShelterBlock(item.getItem()));
        if (items.isEmpty() || ctx.time - phaseSince > ReflexConstants.COLLECT_TICKS) {
            finish(ctx);
            return;
        }
        ItemEntity nearest = items.stream().min(Comparator.comparingDouble(body::distanceToSqr)).orElseThrow();
        cause = "picking the shelter's blocks up";
        if (body.distanceTo(nearest) > 0.5) Steer.walkToward(body, nearest.position(), false);
    }

    private void finish(ReflexContext ctx) {
        int now = Hands.countShelterBlocks(ctx.body);
        int standing = ctx.layer.shelterBlocks().size();
        outcome = String.format(Locale.ROOT, "shelter: %d blocks placed, carrying %d of the %d it had before%s", placements, now, blocksAtStart,
            standing == 0 ? "" : ", " + standing + " recorded blocks still stand");
        phase = Phase.IDLE;
        if (shelterNight) {
            // The night this hold was for, not the one the clock names now: the hold can end after midnight.
            if (closedOnce) doneNight = holdNight;
            else retryAt = Math.max(retryAt, ctx.time + ReflexConstants.SHELTER_RETRY_TICKS);
            ctx.layer.setNightResult(closedOnce ? NightResult.SHELTERED : NightResult.NO_SHELTER);
            shelterNight = false;
        }
        closedOnce = false;
        feet = null;
        target = null;
        cause = "done";
    }

    // ---------------------------------------------------------------------------------------------------------
    // Bed
    // ---------------------------------------------------------------------------------------------------------

    /** The head half of the nearest bed within reach of the body that this body placed or set its respawn point at, or null. */
    @Nullable
    private BlockPos ownedBed(ReflexContext ctx) {
        BotPlayer body = ctx.body;
        ServerLevel level = ctx.level;
        BlockPos origin = body.blockPosition();
        int r = ReflexConstants.BED_SEARCH_RADIUS;
        PlacedBlocks record = BlockSafety.of(level.getServer()).placedBlocks(level);
        BlockPos respawn = body.getRespawnDimension() == level.dimension() ? body.getRespawnPosition() : null;
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos scan : BlockPos.betweenClosed(origin.offset(-r, -4, -r), origin.offset(r, 4, r))) {
            BlockState state = level.getBlockState(scan);
            if (!(state.getBlock() instanceof BedBlock)) continue;
            BlockPos pos = scan.immutable();
            Direction facing = state.getValue(BedBlock.FACING);
            BlockPos other = pos.relative(state.getValue(BedBlock.PART) == BedPart.FOOT ? facing : facing.getOpposite());
            boolean owned = record.lookup(pos.asLong(), state.getBlock(), body.getUUID()) == PlacedBlocks.BOT_SELF
                || record.lookup(other.asLong(), state.getBlock(), body.getUUID()) == PlacedBlocks.BOT_SELF
                || pos.equals(respawn) || other.equals(respawn);
            if (!owned) continue;
            double distance = pos.distSqr(origin);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = state.getValue(BedBlock.PART) == BedPart.HEAD ? pos : other;
            }
        }
        return best;
    }

    private void goToBed(ReflexContext ctx, boolean night, NightPolicy policy) {
        BotPlayer body = ctx.body;
        if (!night) {
            ctx.nav.stop();
            endBedNight(ctx, "morning came before the body slept");
            return;
        }
        if (policy != NightPolicy.BED) {
            ctx.nav.stop();
            policyChanged(ctx, policy);
            return;
        }
        if (bed == null || !(ctx.level.getBlockState(bed).getBlock() instanceof BedBlock)) {
            fallBackToShelter(ctx, "the bed is gone");
            return;
        }
        if (body.position().distanceTo(Vec3.atCenterOf(bed)) <= 2.5) {
            ctx.nav.stop();
            Steer.stand(body);
            trySleep(ctx);
            enter(Phase.SLEEP, ctx);
            cause = "going to sleep";
            return;
        }
        if (ctx.nav.state() == BotNavigator.State.FAILED || ctx.time - phaseSince > ReflexConstants.BED_ATTEMPT_TICKS) {
            fallBackToShelter(ctx, "cannot reach the bed");
            return;
        }
        cause = "going to bed at " + bed.toShortString();
        if (ctx.nav.state() != BotNavigator.State.WORKING) ctx.goTo(bed, 1);
    }

    private void sleep(ReflexContext ctx, boolean night, NightPolicy policy) {
        BotPlayer body = ctx.body;
        Steer.stand(body);
        if (body.isSleeping()) {
            slept = true;
            cause = "sleeping";
            return;
        }
        if (!night || slept && policy != NightPolicy.BED) {
            endBedNight(ctx, "morning came before the body slept");
            return;
        }
        if (policy != NightPolicy.BED) {
            policyChanged(ctx, policy);
            return;
        }
        // Awake at night: the first try was too early (sleep is possible from tick 12542), or something woke the body.
        if (ctx.time - phaseSince > ReflexConstants.BED_ATTEMPT_TICKS) {
            fallBackToShelter(ctx, "cannot sleep");
            return;
        }
        cause = "trying to sleep";
        if ((ctx.time - phaseSince) % 20 == 0) trySleep(ctx);
    }

    private void trySleep(ReflexContext ctx) {
        if (bed == null) return;
        BotPlayer body = ctx.body;
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(bed).add(0.0, 0.3, 0.0), Direction.UP, bed, false);
        body.getInteractionManager().interactBlock(body, ctx.level, body.getMainHandItem(), InteractionHand.MAIN_HAND, hit);
    }

    private void fallBackToShelter(ReflexContext ctx, String why) {
        MegaBlockBot.LOGGER.info("reflex {}: no bed tonight ({}); building a shelter instead", ctx.body.getGameProfile().getName(), why);
        ctx.nav.stop();
        bedFailedNight = holdNight;
        phase = Phase.IDLE;
        retryAt = ctx.time;
        cause = "no bed (" + why + ")";
    }

    /**
     * The night is over for this hold: the body slept (and says so) or it did not (and says that, and why). Either way it
     * is the night the hold began in that is done, not the one the clock names after a skip to morning.
     */
    private void endBedNight(ReflexContext ctx, String why) {
        outcome = slept ? "slept in a bed" : "did not sleep: " + why;
        ctx.layer.setNightResult(slept ? NightResult.SLEPT : NightResult.DID_NOT_SLEEP);
        phase = Phase.IDLE;
        doneNight = holdNight;
        cause = "done";
    }

    /**
     * The policy was changed under a walk to the bed (or a wait to sleep): the bed is dropped, nothing was slept, and the
     * night is not done. Whatever the new policy says (a shelter, nothing) gets its turn next tick.
     */
    private void policyChanged(ReflexContext ctx, NightPolicy now) {
        outcome = "did not sleep: the night policy changed to " + now.name().toLowerCase(Locale.ROOT);
        ctx.layer.setNightResult(NightResult.DID_NOT_SLEEP);
        phase = Phase.IDLE;
        retryAt = ctx.time;
        cause = "policy changed to " + now.name().toLowerCase(Locale.ROOT);
    }
}
