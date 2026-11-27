package com.legoj15.megablockbot.game.plan;

import com.legoj15.megablockbot.MegaBlockBot;
import com.legoj15.megablockbot.core.snapshot.Snapshot;
import com.legoj15.megablockbot.game.body.BodyException;
import com.legoj15.megablockbot.game.body.BodyManager;
import com.legoj15.megablockbot.game.body.BotBrain;
import com.legoj15.megablockbot.game.body.BotPlayer;
import com.legoj15.megablockbot.game.resolver.AcquisitionIndexes;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * The sight the snapshot collector takes of the blocks around a body ({@link BodySight}). What they protect: that
 * the planner is told the nearest block of each kind it can mine or harvest, that a chunk nobody loaded is neither
 * read nor loaded by looking, and that a snapshot taken again at once, or after a few steps, does not scan the world
 * again. They sit in this package, not in {@code game.gametest}, because they read the scan counter that is not public.
 *
 * <p>Every body has a name unique to this run and is despawned and forgotten when its test ends. Each test places only
 * block kinds no other GameTest uses, so a neighbouring arena cannot be the nearest one.
 */
public final class SightGameTests {
    private static final String RUN_ID = Integer.toString((int) (System.nanoTime() & 0xFFFFF), 36);

    private SightGameTests() {}

    private static BotPlayer spawn(GameTestHelper helper, String tag) {
        try {
            BotPlayer body = BodyManager.of(helper.getLevel().getServer()).spawn("sg_" + tag + "_" + RUN_ID, null,
                helper.getLevel(), helper.absoluteVec(new Vec3(8.5, 2.0, 8.5)), 0.0F, () -> BotBrain.NONE);
            // The tests that wait must find the body where they left it: no gravity, and a brain that does nothing (the
            // real one has reflexes, and a mob of a test in the next arena made it move and scan again).
            body.setNoGravity(true);
            // And nothing may kill it: tests in the arenas next door spawn zombies and skeletons, a body that stands
            // here for two hundred ticks is their nearest player, and one that died came back at the world spawn and
            // scanned again. An invulnerable player is not a target at all.
            body.setInvulnerable(true);
            return body;
        } catch (BodyException e) {
            throw new IllegalStateException("could not spawn the body of " + tag + ": " + e.getMessage(), e);
        }
    }

    private static void despawn(GameTestHelper helper, BotPlayer body) {
        BodyManager manager = BodyManager.of(helper.getLevel().getServer());
        UUID id = body.getUUID();
        manager.despawn(body);
        try {
            manager.deleteSaved(id);
        } catch (BodyException ignored) {
            // Still logged in only if the despawn did not take; the unique name keeps it from mattering.
        }
        BotMemory.forget(id);
    }

    private static void needsIndex(GameTestHelper helper) {
        helper.assertTrue(!AcquisitionIndexes.current().sourceBlocks().isEmpty(), "the acquisition index names no source block");
    }

    // ---------------------------------------------------------------------------------------------------------
    // What a scan finds
    // ---------------------------------------------------------------------------------------------------------

    /**
     * The body stands at (8, 2, 8) of the arena. Two emerald ores, one 5 blocks east and a farther one; a cherry log
     * 3 west and 4 south (a 3-4-5 triangle) and another straight above the body, in its own column (distance 0: the
     * height of a block plays no part in its distance); a block no source names; and nothing of lapis.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 40)
    public static void theSightHoldsTheNearestBlockOfEachKindAtItsHorizontalDistance(GameTestHelper helper) {
        needsIndex(helper);
        ServerLevel level = helper.getLevel();
        BotPlayer body = spawn(helper, "near");
        BlockPos at = body.blockPosition();
        BlockPos[] placed = {
            at.offset(5, 0, 0), at.offset(7, 0, -6), at.offset(-3, 1, 4), at.offset(0, 6, 0), at.offset(2, 0, 2)};
        try {
            level.setBlockAndUpdate(placed[0], Blocks.EMERALD_ORE.defaultBlockState());
            level.setBlockAndUpdate(placed[1], Blocks.EMERALD_ORE.defaultBlockState());
            level.setBlockAndUpdate(placed[2], Blocks.CHERRY_LOG.defaultBlockState());
            level.setBlockAndUpdate(placed[3], Blocks.CHERRY_LOG.defaultBlockState());
            level.setBlockAndUpdate(placed[4], Blocks.BARRIER.defaultBlockState());

            Snapshot snapshot = SnapshotCollector.collect(body);
            helper.assertTrue(Integer.valueOf(5).equals(snapshot.nearbyBlocks().get("minecraft:emerald_ore")),
                "the nearer of two emerald ores is 5 away: " + snapshot.nearbyBlocks().get("minecraft:emerald_ore"));
            helper.assertTrue(Integer.valueOf(0).equals(snapshot.nearbyBlocks().get("minecraft:cherry_log")),
                "a cherry log in the body's own column is 0 away: " + snapshot.nearbyBlocks().get("minecraft:cherry_log"));
            helper.assertTrue(!snapshot.nearbyBlocks().containsKey("minecraft:lapis_ore"), "lapis out of nowhere");
            helper.assertTrue(!snapshot.nearbyBlocks().containsKey("minecraft:barrier"), "a block no source names is in the sight");
            helper.assertTrue(!snapshot.nearbyBlocks().containsKey("minecraft:air"), "air is in the sight");

            // Without the log above, the next is the 3-4-5 one.
            level.setBlockAndUpdate(placed[3], Blocks.AIR.defaultBlockState());
            BodySight.Wanted wanted = BodySight.wantedBy(AcquisitionIndexes.current());
            BodySight.Scan scan = BodySight.scan(level, at, BodySight.RADIUS_CHUNKS, BodySight.BELOW, BodySight.ABOVE, wanted);
            BodySight.Spot log = scan.nearest().get("minecraft:cherry_log");
            helper.assertTrue(log != null && log.distanceFrom(at) == 5, "the log at 3 west and 4 south is 5 away: " + log);
            helper.assertTrue(scan.chunksRead() > 0 && scan.sectionsWalked() > 0, "the scan read " + scan.chunksRead() + " chunks");
            helper.succeed();
        } finally {
            for (BlockPos pos : placed) level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
            despawn(helper, body);
        }
    }

    /**
     * Skipping the sections that cannot hold anything nearer must find what walking every section finds. Forty blocks
     * of four kinds are scattered through the arena with a fixed seed, and scans from five centres (the body's and some
     * far enough to put the nearest chunk elsewhere) are compared.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 60)
    public static void theScanThatSkipsSectionsFindsWhatTheFullScanFinds(GameTestHelper helper) {
        needsIndex(helper);
        ServerLevel level = helper.getLevel();
        BotPlayer body = spawn(helper, "prune");
        BlockPos at = body.blockPosition();
        net.minecraft.world.level.block.Block[] kinds = {
            Blocks.EMERALD_ORE, Blocks.CHERRY_LOG, Blocks.LAPIS_ORE, Blocks.REDSTONE_ORE};
        java.util.List<BlockPos> placed = new java.util.ArrayList<>();
        try {
            java.util.Random random = new java.util.Random(27);
            for (int i = 0; i < 40; i++) {
                BlockPos pos = helper.absolutePos(new BlockPos(random.nextInt(16), 1 + random.nextInt(12), random.nextInt(16)));
                level.setBlockAndUpdate(pos, kinds[random.nextInt(kinds.length)].defaultBlockState());
                placed.add(pos);
            }
            BodySight.Wanted wanted = BodySight.wantedBy(AcquisitionIndexes.current());
            BlockPos[] centres = {at, at.offset(20, 0, 0), at.offset(-33, 5, 17), at.offset(0, -20, 40), at.offset(-70, 0, -70)};
            for (BlockPos centre : centres) {
                BodySight.Scan pruned = BodySight.scan(level, centre, BodySight.RADIUS_CHUNKS, BodySight.BELOW, BodySight.ABOVE, wanted, true);
                BodySight.Scan full = BodySight.scan(level, centre, BodySight.RADIUS_CHUNKS, BodySight.BELOW, BodySight.ABOVE, wanted, false);
                helper.assertTrue(pruned.nearest().equals(full.nearest()),
                    "from " + centre + " the pruned scan found " + pruned.nearest() + " and the full one " + full.nearest());
                helper.assertTrue(pruned.sectionsWalked() <= full.sectionsWalked(),
                    "from " + centre + " the pruned scan walked " + pruned.sectionsWalked() + " sections, the full one " + full.sectionsWalked());
            }
            BodySight.Scan pruned = BodySight.scan(level, at, BodySight.RADIUS_CHUNKS, BodySight.BELOW, BodySight.ABOVE, wanted, true);
            BodySight.Scan full = BodySight.scan(level, at, BodySight.RADIUS_CHUNKS, BodySight.BELOW, BodySight.ABOVE, wanted, false);
            helper.assertTrue(pruned.sectionsWalked() < full.sectionsWalked(),
                "the pruning saved nothing: " + pruned.sectionsWalked() + " sections of " + full.sectionsWalked());
            helper.succeed();
        } finally {
            for (BlockPos pos : placed) level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
            despawn(helper, body);
        }
    }

    /**
     * A block found in a chunk that was loaded is gone from the sight once that chunk is not, and looking does not load
     * it. The chunk is 30 chunks east of the arena, inside the radius this scan is given (the radius a body uses is
     * smaller, but no chunk within a body's reach can be made to unload while the body holds it).
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 200)
    public static void aBlockInAChunkThatIsNotLoadedIsNotSeenAndLookingDoesNotLoadIt(GameTestHelper helper) {
        needsIndex(helper);
        ServerLevel level = helper.getLevel();
        BotPlayer body = spawn(helper, "far");
        BlockPos at = body.blockPosition();
        int chunkX = (at.getX() >> 4) + 30;
        int chunkZ = at.getZ() >> 4;
        // In the body's own row of chunks and at its z, so that the distance is the difference of the x coordinates.
        BlockPos debris = new BlockPos((chunkX << 4) + 4, at.getY(), at.getZ());
        BodySight.Wanted wanted = BodySight.wantedBy(AcquisitionIndexes.current());
        try {
            level.setChunkForced(chunkX, chunkZ, true);
            level.setBlockAndUpdate(debris, Blocks.ANCIENT_DEBRIS.defaultBlockState());
            BodySight.Scan loaded = BodySight.scan(level, at, 40, 48, 32, wanted);
            BodySight.Spot spot = loaded.nearest().get("minecraft:ancient_debris");
            helper.assertTrue(spot != null && spot.distanceFrom(at) == debris.getX() - at.getX(),
                "while the chunk is loaded the debris 30 chunks away is seen at " + (debris.getX() - at.getX()) + ": " + spot);
            level.setChunkForced(chunkX, chunkZ, false);
        } catch (RuntimeException | Error e) {
            level.setChunkForced(chunkX, chunkZ, false);
            despawn(helper, body);
            throw e;
        }

        helper.onEachTick(() -> {
            if (level.getChunkSource().getChunkNow(chunkX, chunkZ) != null) return;
            int loadedBefore = level.getChunkSource().getLoadedChunksCount();
            BodySight.Scan unloaded = BodySight.scan(level, at, 40, 48, 32, wanted);
            try {
                helper.assertTrue(!unloaded.nearest().containsKey("minecraft:ancient_debris"),
                    "the debris in a chunk nobody has loaded is seen: " + unloaded.nearest().get("minecraft:ancient_debris"));
                helper.assertTrue(unloaded.chunksNotLoaded() > 0, "no chunk in range was left unread");
                helper.assertTrue(level.getChunkSource().getChunkNow(chunkX, chunkZ) == null, "looking loaded the chunk");
                helper.assertTrue(level.getChunkSource().getLoadedChunksCount() == loadedBefore,
                    "looking changed the loaded chunks from " + loadedBefore + " to " + level.getChunkSource().getLoadedChunksCount());
                helper.succeed();
            } finally {
                despawn(helper, body);
            }
        });
    }

    // ---------------------------------------------------------------------------------------------------------
    // How often it scans
    // ---------------------------------------------------------------------------------------------------------

    /**
     * Snapshots in a row, or after a few steps, read the scan already kept; one after a long walk takes a new one, and
     * the distances of the kept scan are measured from where the body is now.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 40)
    public static void snapshotsWithinTheBoundsShareOneScanAndAFarStepScansAgain(GameTestHelper helper) {
        needsIndex(helper);
        ServerLevel level = helper.getLevel();
        BotPlayer body = spawn(helper, "once");
        BlockPos at = body.blockPosition();
        BlockPos ore = at.offset(5, 0, 0);
        try {
            level.setBlockAndUpdate(ore, Blocks.EMERALD_ORE.defaultBlockState());
            BotMemory memory = BotMemory.of(body.getUUID());
            helper.assertTrue(memory.sightScans() == 0, "a body that has taken no snapshot has scans: " + memory.sightScans());

            Snapshot first = SnapshotCollector.collect(body, memory);
            Snapshot second = SnapshotCollector.collect(body, memory);
            helper.assertTrue(memory.sightScans() == 1, "two snapshots in a row took " + memory.sightScans() + " scans");
            helper.assertTrue(first.nearbyBlocks().equals(second.nearbyBlocks()), "the second snapshot saw something else");
            helper.assertTrue(Integer.valueOf(5).equals(first.nearbyBlocks().get("minecraft:emerald_ore")), "the ore is not 5 away: " + first.nearbyBlocks());

            // A few steps: the same scan, with the distance measured from where the body stands now.
            int step = BodySight.MOVE_BLOCKS - 4;
            body.setPos(body.getX() + step, body.getY(), body.getZ());
            Snapshot stepped = SnapshotCollector.collect(body, memory);
            helper.assertTrue(memory.sightScans() == 1, "a step of " + step + " blocks took a scan");
            helper.assertTrue(Integer.valueOf(step - 5).equals(stepped.nearbyBlocks().get("minecraft:emerald_ore")),
                "the ore is " + stepped.nearbyBlocks().get("minecraft:emerald_ore") + " from the body, expected " + (step - 5));

            // Past the bound: a new scan, taken from here.
            body.setPos(body.getX() + 8, body.getY(), body.getZ());
            Snapshot far = SnapshotCollector.collect(body, memory);
            helper.assertTrue(memory.sightScans() == 2, "a walk of " + (step + 8) + " blocks took " + (memory.sightScans() - 1) + " scans");
            helper.assertTrue(Integer.valueOf(step + 8 - 5).equals(far.nearbyBlocks().get("minecraft:emerald_ore")),
                "the ore after the walk: " + far.nearbyBlocks().get("minecraft:emerald_ore"));

            // Going up or down counts too: the scan reads a band of heights around the body.
            body.setPos(body.getX(), body.getY() + BodySight.MOVE_BLOCKS + 1, body.getZ());
            SnapshotCollector.collect(body, memory);
            helper.assertTrue(memory.sightScans() == 3, "a climb took " + (memory.sightScans() - 2) + " scans");
            helper.succeed();
        } finally {
            level.setBlockAndUpdate(ore, Blocks.AIR.defaultBlockState());
            despawn(helper, body);
        }
    }

    /**
     * A kept scan is good for {@link BodySight#MAX_AGE_TICKS} ticks, a body that stands still included. In a batch of
     * its own: the test failed in full runs and never alone, and what its neighbours do within sight of the body is
     * the only thing a full run adds.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 300, batch = "sight_age")
    public static void aScanKeptForTooLongIsTakenAgain(GameTestHelper helper) {
        needsIndex(helper);
        BotPlayer body = spawn(helper, "age");
        BotMemory memory = BotMemory.of(body.getUUID());
        try {
            SnapshotCollector.collect(body, memory);
            helper.assertTrue(memory.sightScans() == 1, "the first snapshot took " + memory.sightScans() + " scans");
        } catch (RuntimeException | Error e) {
            despawn(helper, body);
            throw e;
        }
        helper.runAfterDelay(BodySight.MAX_AGE_TICKS - 20, () -> {
            try {
                SnapshotCollector.collect(body, memory);
                helper.assertTrue(memory.sightScans() == 1, "a scan " + (BodySight.MAX_AGE_TICKS - 20) + " ticks old was taken again");
            } catch (RuntimeException | Error e) {
                despawn(helper, body);
                throw e;
            }
        });
        helper.runAfterDelay(BodySight.MAX_AGE_TICKS + 10, () -> {
            try {
                SnapshotCollector.collect(body, memory);
                helper.assertTrue(memory.sightScans() == 2, "a scan " + (BodySight.MAX_AGE_TICKS + 10) + " ticks old was kept: " + memory.sightScans());
                helper.succeed();
            } finally {
                despawn(helper, body);
            }
        });
    }
}
