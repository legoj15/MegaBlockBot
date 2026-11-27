package com.legoj15.megablockbot.game.plan;

import com.legoj15.megablockbot.MegaBlockBot;
import com.legoj15.megablockbot.core.acquire.AcquisitionIndex;
import com.legoj15.megablockbot.core.snapshot.Snapshot;
import com.legoj15.megablockbot.game.resolver.AcquisitionIndexes;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * The sight (design 3.4, Snapshot): which of the blocks the acquisition index names as a mining or harvesting source
 * stand in the chunks already loaded around a body, and how far the nearest of each kind is. The cost model prices a
 * source whose block is in it below one whose block is not (design 4.1), so the planner picks the log and the plank
 * that are there instead of the one that is listed first.
 *
 * <p>A scan reads {@link #RADIUS_CHUNKS} chunks in every direction and {@link #BELOW} blocks below to {@link #ABOVE}
 * above the body. It asks the chunk source for chunks that are loaded now and skips the rest: it never loads or
 * generates one. Chunks are read nearest first and a section is walked only when its palette holds a wanted block
 * that could still be nearer than the one found so far (the air above the ground, and the stone of far chunks once the
 * body's own chunk has shown stone, are skipped that way); a walk keeps only the nearest block of each kind.
 *
 * <p>{@code SnapshotCollector.collect} runs several times in a burst when a node ends or a plan is made, and not at
 * all while a task runs. A scan is therefore kept per body (in its {@link BotMemory}, not saved) and taken again only
 * when it is older than {@link #MAX_AGE_TICKS} or the body is more than {@link #MOVE_BLOCKS} from where it was taken;
 * a snapshot in between reads the kept one, with each distance measured from where the body stands now. A tick that
 * only checks a postcondition never pays for a scan. Server thread only.
 */
final class BodySight {
    /**
     * Chunks, in each direction from the body's own, that a scan reads: 9 by 9 chunks, so every direction is seen to
     * at least 64 blocks and the far corner to 112. Chosen with the scan cost in mind, see {@code docs/plan-inputs.md}.
     */
    static final int RADIUS_CHUNKS = 4;
    /** Blocks below the body's block that a scan reads: trees on the slope under a mountain spawn. */
    static final int BELOW = 48;
    /** Blocks above the body's block that a scan reads. */
    static final int ABOVE = 32;
    /** A kept scan older than this many game ticks (10 seconds) is taken again: a felled tree is out of date by then. */
    static final long MAX_AGE_TICKS = 200;
    /**
     * A body that is farther than this many blocks from where a scan was taken (one chunk) takes a new one. Moving
     * less changes no distance by more than that, which the cost model does not tell apart from the next block.
     */
    static final int MOVE_BLOCKS = 16;

    private BodySight() {}

    /** The nearest block of a kind at the moment of a scan: column only, the height is not part of a distance. */
    record Spot(int x, int z) {
        /** Horizontal distance in blocks from {@code at}, rounded to the nearest block. */
        int distanceFrom(BlockPos at) {
            long dx = x - at.getX();
            long dz = z - at.getZ();
            return (int) Math.round(Math.sqrt(dx * dx + dz * dz));
        }
    }

    /** What a scan found, and what it cost. */
    record Scan(Map<String, Spot> nearest, long nanos, int chunksRead, int chunksNotLoaded, int sectionsWalked,
                int sectionsSkipped) {}

    /** A scan kept for one body, with what it needs to tell whether it is still good. */
    record Taken(String dimension, BlockPos at, long gameTime, Wanted wanted, Map<String, Spot> nearest) {
        boolean stillGoodFor(String dimension, BlockPos now, long gameTimeNow, Wanted wantedNow) {
            if (!this.dimension.equals(dimension) || this.wanted != wantedNow) return false;
            long age = gameTimeNow - gameTime;
            // A negative age is a clock from another world (a new server in this JVM): not this scan's.
            if (age < 0 || age > MAX_AGE_TICKS) return false;
            long dx = now.getX() - at.getX();
            long dz = now.getZ() - at.getZ();
            return dx * dx + dz * dz <= (long) MOVE_BLOCKS * MOVE_BLOCKS && Math.abs(now.getY() - at.getY()) <= MOVE_BLOCKS;
        }
    }

    /**
     * The blocks a scan looks for, from one acquisition index: each is a slot, so a scan keeps its findings in
     * arrays. Built once per index (an index is replaced on a reload), never changed.
     */
    static final class Wanted {
        private final Set<String> basis;
        private final String[] ids;
        private final Reference2IntOpenHashMap<Block> slots = new Reference2IntOpenHashMap<>();

        private Wanted(Set<String> basis) {
            this.basis = basis;
            slots.defaultReturnValue(-1);
            String[] found = new String[basis.size()];
            int count = 0;
            for (String id : basis) {
                ResourceLocation location = ResourceLocation.tryParse(id);
                // A block a mod removed, or one that is only air, is nothing to look for.
                Block block = location == null ? null : BuiltInRegistries.BLOCK.getOptional(location).orElse(null);
                if (block == null || block == Blocks.AIR || slots.containsKey(block)) continue;
                slots.put(block, count);
                found[count++] = id;
            }
            this.ids = Arrays.copyOf(found, count);
        }

        int size() {
            return ids.length;
        }

        /** The slot of the block, or -1 when no source names it. */
        int slot(Block block) {
            return slots.getInt(block);
        }

        String id(int slot) {
            return ids[slot];
        }

        boolean wants(BlockState state) {
            return slots.containsKey(state.getBlock());
        }
    }

    private static volatile Wanted wantedBuilt = new Wanted(Set.of());

    /** The wanted blocks of an index; rebuilt only when a new index is published (compared by its block set). */
    static Wanted wantedBy(AcquisitionIndex index) {
        Wanted cached = wantedBuilt;
        if (cached.basis == index.sourceBlocks()) return cached;
        Wanted built = new Wanted(index.sourceBlocks());
        wantedBuilt = built;
        return built;
    }

    /**
     * Puts the sight of the body at {@code at} into the snapshot: the kept scan when it is still good, else a new one,
     * which is kept. Nothing is added when no index has been built yet (there is nothing to look for).
     */
    static void fill(ServerLevel level, String dimension, BlockPos at, BotMemory memory, Snapshot.Builder builder) {
        Wanted wanted = wantedBy(AcquisitionIndexes.current());
        if (wanted.size() == 0) return;
        long now = level.getGameTime();
        Taken taken = memory.sight();
        if (taken == null || !taken.stillGoodFor(dimension, at, now, wanted)) {
            Scan scan = scan(level, at, RADIUS_CHUNKS, BELOW, ABOVE, wanted);
            taken = new Taken(dimension, at.immutable(), now, wanted, scan.nearest());
            memory.keepSight(taken);
        }
        for (Map.Entry<String, Spot> found : taken.nearest().entrySet()) {
            builder.nearby(found.getKey(), found.getValue().distanceFrom(at));
        }
    }

    /** A chunk to read and the least horizontal distance squared from the body to any block of it. */
    private record Near(int chunkX, int chunkZ, int minDistSq) {}

    /** The chunks within the radius, nearest first (ties by position, so equal worlds are read in equal order). */
    private static List<Near> nearestFirst(BlockPos at, int radiusChunks) {
        int centerX = at.getX() >> 4;
        int centerZ = at.getZ() >> 4;
        List<Near> order = new ArrayList<>();
        for (int chunkZ = centerZ - radiusChunks; chunkZ <= centerZ + radiusChunks; chunkZ++) {
            for (int chunkX = centerX - radiusChunks; chunkX <= centerX + radiusChunks; chunkX++) {
                long dx = gap(at.getX(), chunkX << 4);
                long dz = gap(at.getZ(), chunkZ << 4);
                order.add(new Near(chunkX, chunkZ, (int) (dx * dx + dz * dz)));
            }
        }
        order.sort(Comparator.comparingInt(Near::minDistSq).thenComparingInt(Near::chunkZ).thenComparingInt(Near::chunkX));
        return order;
    }

    /** Distance from a coordinate to the nearest block of the 16-wide chunk row starting at {@code from}. */
    private static int gap(int coordinate, int from) {
        if (coordinate < from) return from - coordinate;
        return Math.max(0, coordinate - (from + 15));
    }

    /** Whether a section's palette holds a block that this scan could still find nearer than its best so far. */
    private static final class CouldImprove implements Predicate<BlockState> {
        private final Wanted wanted;
        private final int[] bestSq;
        int minDistSq;

        CouldImprove(Wanted wanted, int[] bestSq) {
            this.wanted = wanted;
            this.bestSq = bestSq;
        }

        @Override
        public boolean test(BlockState state) {
            int slot = wanted.slot(state.getBlock());
            return slot >= 0 && bestSq[slot] > minDistSq;
        }
    }

    static Scan scan(ServerLevel level, BlockPos at, int radiusChunks, int below, int above, Wanted wanted) {
        return scan(level, at, radiusChunks, below, above, wanted, true);
    }

    /**
     * Reads the loaded chunks within {@code radiusChunks} of the one the body is in, from {@code below} blocks under it
     * to {@code above} over it, for the wanted blocks. Keeps nothing: {@link #fill} keeps what a snapshot's scan found.
     *
     * <p>Chunks are read nearest first, and a section is walked only when its palette holds a wanted block that could
     * still be nearer than the one found so far: once the chunk the body stands in has shown stone, dirt and grass at
     * distance 0, the sections of farther chunks that hold only those are not walked. {@code prune} false walks every
     * section that holds a wanted block, which finds the same blocks; it is what the tests compare against.
     */
    static Scan scan(ServerLevel level, BlockPos at, int radiusChunks, int below, int above, Wanted wanted,
                     boolean prune) {
        long started = System.nanoTime();
        int slots = wanted.size();
        int[] bestSq = new int[slots];
        Arrays.fill(bestSq, Integer.MAX_VALUE);
        int[] bestX = new int[slots];
        int[] bestZ = new int[slots];

        int minY = Math.max(level.getMinBuildHeight(), at.getY() - below);
        int maxY = Math.min(level.getMaxBuildHeight() - 1, at.getY() + above);
        ServerChunkCache chunks = level.getChunkSource();
        int[] dxSq = new int[16];
        int[] dzSq = new int[16];
        CouldImprove couldImprove = new CouldImprove(wanted, bestSq);
        Predicate<BlockState> wantsAny = wanted::wants;
        int read = 0;
        int missing = 0;
        int walked = 0;
        int skipped = 0;

        for (Near near : nearestFirst(at, radiusChunks)) {
            // getChunkNow answers only for a chunk that is loaded and complete, and loads nothing.
            LevelChunk chunk = chunks.getChunkNow(near.chunkX(), near.chunkZ());
            if (chunk == null) {
                missing++;
                continue;
            }
            read++;
            couldImprove.minDistSq = near.minDistSq();
            Predicate<BlockState> worthWalking = prune ? couldImprove : wantsAny;
            int baseX = near.chunkX() << 4;
            int baseZ = near.chunkZ() << 4;
            for (int i = 0; i < 16; i++) {
                long dx = baseX + i - at.getX();
                long dz = baseZ + i - at.getZ();
                dxSq[i] = (int) (dx * dx);
                dzSq[i] = (int) (dz * dz);
            }
            LevelChunkSection[] sections = chunk.getSections();
            int last = chunk.getSectionIndex(maxY);
            for (int index = chunk.getSectionIndex(minY); index <= last; index++) {
                LevelChunkSection section = sections[index];
                // The palette test first: a section that cannot hold a block worth finding is never walked.
                if (section.hasOnlyAir() || !section.maybeHas(worthWalking)) {
                    skipped++;
                    continue;
                }
                walked++;
                int sectionMinY = chunk.getMinBuildHeight() + (index << 4);
                int fromY = Math.max(minY, sectionMinY) - sectionMinY;
                int toY = Math.min(maxY, sectionMinY + 15) - sectionMinY;
                BlockState previous = null;
                int slot = -1;
                for (int y = fromY; y <= toY; y++) {
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            BlockState state = section.getBlockState(x, y, z);
                            // Runs of one state (stone, air) cost one lookup, not one per block.
                            if (state != previous) {
                                previous = state;
                                slot = wanted.slot(state.getBlock());
                            }
                            if (slot < 0) continue;
                            int distSq = dxSq[x] + dzSq[z];
                            if (distSq < bestSq[slot]) {
                                bestSq[slot] = distSq;
                                bestX[slot] = baseX + x;
                                bestZ[slot] = baseZ + z;
                            }
                        }
                    }
                }
            }
        }

        Map<String, Spot> nearest = new HashMap<>();
        for (int slot = 0; slot < slots; slot++) {
            if (bestSq[slot] != Integer.MAX_VALUE) nearest.put(wanted.id(slot), new Spot(bestX[slot], bestZ[slot]));
        }
        long nanos = System.nanoTime() - started;
        MegaBlockBot.LOGGER.debug("sight scan at {}: {} chunks read, {} not loaded, {} sections walked, {} skipped, {} kinds found, {} us",
            at, read, missing, walked, skipped, nearest.size(), nanos / 1000);
        return new Scan(Map.copyOf(nearest), nanos, read, missing, walked, skipped);
    }
}
