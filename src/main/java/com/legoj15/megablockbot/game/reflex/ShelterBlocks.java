package com.legoj15.megablockbot.game.reflex;

import com.legoj15.megablockbot.game.body.BotPlayer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

/**
 * The blocks a body placed for a night shelter and that still stand. It belongs to the body's {@link ReflexLayer}, not to
 * the night reflex or to a hold: a takedown that a fight, a timeout or a death broke off must not make the layer forget
 * what it put into the world. An entry leaves the list only when the world no longer holds that block at that position
 * (it was taken down, or something else broke it); the break rules refusing it, the block being out of reach and the time
 * running out only make the entry wait.
 *
 * <p>Server thread only.
 */
final class ShelterBlocks {
    /** One placed block: where, what it was, and the game time before which it is not asked about again. */
    static final class Entry {
        final ResourceKey<Level> dimension;
        final BlockPos pos;
        final Block block;
        long retryAt;
        /** Takedown attempts at this block that failed; each doubles the wait before the next ({@link ShelterBlocks#fail}). */
        int failures;

        Entry(ResourceKey<Level> dimension, BlockPos pos, Block block) {
            this.dimension = dimension;
            this.pos = pos;
            this.block = block;
        }
    }

    /** Blocks this close to each other (a shelter is one cell wide and three high; a second one is farther off than this) belong to one shelter. */
    private static final double SHELTER_SPREAD = 6.0;

    private final List<Entry> entries = new ArrayList<>();

    void add(ServerLevel level, BlockPos pos, Block block) {
        BlockPos at = pos.immutable();
        for (Entry entry : entries) {
            if (entry.pos.equals(at) && entry.dimension == level.dimension()) return;
        }
        entries.add(new Entry(level.dimension(), at, block));
    }

    /** How many placed blocks are recorded as standing, in any dimension. */
    int size() {
        return entries.size();
    }

    /** Every recorded block, in any dimension: for the layer's save, which writes where they are and nothing else. */
    List<Entry> recorded() {
        return List.copyOf(entries);
    }

    /**
     * Forgets the entries of this level whose block is gone or is something else now: they are not the body's to break any
     * more. A position in a chunk that is not loaded is not looked at (that would load it) and stays.
     */
    void prune(ServerLevel level) {
        Iterator<Entry> it = entries.iterator();
        while (it.hasNext()) {
            Entry entry = it.next();
            if (entry.dimension != level.dimension() || !level.isLoaded(entry.pos)) continue;
            if (level.getBlockState(entry.pos).getBlock() != entry.block) it.remove();
        }
    }

    /**
     * The recorded blocks to take down next: of this level, not waiting for a retry, within
     * {@link ReflexConstants#TAKEDOWN_PURSUE_RADIUS} of the body on the ground plane, and of one shelter at a time (the one
     * nearest the body: the blocks within {@link #SHELTER_SPREAD} of its nearest block), highest first because a shelter comes
     * down cap first, nearest first among equals. Empty when there is nothing to do.
     */
    List<Entry> due(ServerLevel level, long now, BotPlayer body) {
        double limit = (double) ReflexConstants.TAKEDOWN_PURSUE_RADIUS * ReflexConstants.TAKEDOWN_PURSUE_RADIUS;
        List<Entry> candidates = new ArrayList<>();
        Entry nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (Entry entry : entries) {
            if (entry.dimension != level.dimension() || entry.retryAt > now) continue;
            double dx = entry.pos.getX() + 0.5 - body.getX();
            double dz = entry.pos.getZ() + 0.5 - body.getZ();
            double flat = dx * dx + dz * dz;
            if (flat > limit) continue;
            candidates.add(entry);
            double distance = flat + Math.pow(entry.pos.getY() + 0.5 - body.getY(), 2);
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = entry;
            }
        }
        if (nearest == null) return candidates;
        BlockPos anchor = nearest.pos;
        candidates.removeIf(entry -> entry.pos.distSqr(anchor) > SHELTER_SPREAD * SHELTER_SPREAD);
        BlockPos here = body.blockPosition();
        candidates.sort(Comparator.comparingInt((Entry e) -> e.pos.getY()).reversed().thenComparingDouble(e -> e.pos.distSqr(here)));
        return candidates;
    }

    /** Whether the entry is still on the list (it is taken off when its block is gone). */
    boolean isRecorded(Entry entry) {
        return entries.contains(entry);
    }

    /**
     * A takedown attempt at this block failed (it could not be seen, reached or broken): it is asked about again after
     * {@link ReflexConstants#TAKEDOWN_RETRY_TICKS}, twice that after the next failure, and so on up to a day. The block stays
     * recorded. {@code floor} is a wait it must have at least (a block the break rules refused: the protected zone does not
     * move in a few minutes).
     */
    void fail(Entry entry, long now, long floor) {
        entry.failures++;
        long wait = ReflexConstants.TAKEDOWN_RETRY_TICKS;
        for (int i = 1; i < entry.failures && wait < ReflexConstants.TAKEDOWN_MAX_WAIT_TICKS; i++) wait *= 2;
        entry.retryAt = now + Math.min(ReflexConstants.TAKEDOWN_MAX_WAIT_TICKS, Math.max(wait, floor));
    }

    /** Puts every entry of this level that is due off until {@code until}: an attempt that could not finish them is not repeated at once. */
    void deferDue(ServerLevel level, long now, long until) {
        for (Entry entry : entries) {
            if (entry.dimension == level.dimension() && entry.retryAt <= now) entry.retryAt = until;
        }
    }

    void remove(Entry entry) {
        entries.remove(entry);
    }
}
