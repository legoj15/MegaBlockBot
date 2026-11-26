package com.legoj15.megablockbot.game.safety;

import com.legoj15.megablockbot.MegaBlockBot;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.StampedLock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * The blocks players (and bots) placed in one level (design 3.5), so a mining bot can tell a build from terrain.
 * Saved with the level as {@code data/megablockbot_placed.dat}, written only when something changed.
 *
 * <p>Two sets, both position to block: the blocks a real player placed, and the blocks a bot's body placed, which also
 * remember which body. A bot's own block is the bot's to break; every other recorded block is off-limits. Each entry
 * stores the block it was recorded with, so a position that now holds something else (broken by an explosion, washed
 * away, replaced) is a stale record and counts as terrain.
 *
 * <p>Threading: reads ({@link #lookup}) are safe from any thread and allocate nothing; they take a read lock.
 * Everything that writes ({@code record*}, {@link #forget}) is for the server thread, which is also the only thread
 * that saves, so a writer never waits long. In memory the sets are flat open-addressing maps keyed by
 * {@link BlockPos#asLong}; on disk they are grouped by chunk and packed (below).
 *
 * <p>File format, {@link #SCHEMA} 1:
 * <pre>
 * { schema: 1,
 *   palette: ["minecraft:stone", ...],            block ids used by the entries
 *   bots:    [ [I; uuid...], ...],                owners of bot entries
 *   chunks:  [ { v: 1, x: chunkX, z: chunkZ,
 *                p: [L; ...],                     player-placed: local position | palette index &lt;&lt; 20
 *                b: [L; ...] } ] }                bot-placed: the same | owner index &lt;&lt; 44
 * </pre>
 * A local position is 4 bits x, 4 bits z, then y + 2048 in 12 bits, which is {@link BlockPos}'s own y range. A chunk
 * entry that does not decode is skipped and logged; so is an entry of another {@code v}, and a file of another
 * {@code schema} is read as empty. Nothing here ever fails the world load.
 */
public final class PlacedBlocks extends SavedData {
    public static final String ID = "megablockbot_placed";
    public static final int SCHEMA = 1;
    public static final SavedData.Factory<PlacedBlocks> FACTORY = new SavedData.Factory<>(PlacedBlocks::new, PlacedBlocks::load);

    /** {@link #lookup} results. */
    public static final int NONE = 0;
    /** Placed by a real player. */
    public static final int PLAYER = 1;
    /** Placed by the body that asked. */
    public static final int BOT_SELF = 2;
    /** Placed by another bot's body. */
    public static final int BOT_OTHER = 3;
    /** A record exists but the position holds a different block now: terrain. The server thread may {@link #forget} it. */
    public static final int STALE = 4;

    private static final int PALETTE_SHIFT = 20;
    private static final int OWNER_SHIFT = 44;
    private static final long LOCAL_MASK = (1L << PALETTE_SHIFT) - 1;
    private static final long PALETTE_MASK = (1L << (OWNER_SHIFT - PALETTE_SHIFT)) - 1;
    private static final long OWNER_MASK = (1L << (64 - OWNER_SHIFT)) - 1;
    private static final int Y_BIAS = 2048;

    private record BotPlacement(Block block, UUID bot) {}

    private final StampedLock lock = new StampedLock();
    private final Long2ObjectOpenHashMap<Block> player = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<BotPlacement> bots = new Long2ObjectOpenHashMap<>();

    public PlacedBlocks() {}

    /** The level's record, loaded or created. Server thread only: the level's data storage is not thread-safe. */
    public static PlacedBlocks of(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, ID);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Queries
    // ---------------------------------------------------------------------------------------------------------

    /**
     * What is recorded at {@code pos}, given the block that is there now. Any thread, no allocation.
     *
     * @param bot the id of the body asking; {@link #BOT_SELF} is returned only for its own entries
     * @return {@link #NONE}, {@link #PLAYER}, {@link #BOT_SELF}, {@link #BOT_OTHER} or {@link #STALE}
     */
    public int lookup(long pos, Block block, UUID bot) {
        long stamp = lock.readLock();
        try {
            Block placedBlock = player.get(pos);
            if (placedBlock != null) return placedBlock == block ? PLAYER : STALE;
            BotPlacement placement = bots.get(pos);
            if (placement == null) return NONE;
            if (placement.block != block) return STALE;
            return placement.bot.equals(bot) ? BOT_SELF : BOT_OTHER;
        } finally {
            lock.unlockRead(stamp);
        }
    }

    /** What is recorded at {@code pos}, in words, for the debug command. */
    public String describe(long pos) {
        long stamp = lock.readLock();
        try {
            Block placedBlock = player.get(pos);
            if (placedBlock != null) return "player-placed " + BuiltInRegistries.BLOCK.getKey(placedBlock);
            BotPlacement placement = bots.get(pos);
            if (placement != null) return "bot-placed by " + placement.bot + " " + BuiltInRegistries.BLOCK.getKey(placement.block);
            return "none";
        } finally {
            lock.unlockRead(stamp);
        }
    }

    public int size() {
        long stamp = lock.readLock();
        try {
            return player.size() + bots.size();
        } finally {
            lock.unlockRead(stamp);
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Changes (server thread)
    // ---------------------------------------------------------------------------------------------------------

    /** A real player placed {@code block} at {@code pos}. Replaces whatever was recorded there. */
    public void recordPlayer(long pos, Block block) {
        boolean changed;
        long stamp = lock.writeLock();
        try {
            changed = player.put(pos, block) != block;
            if (bots.remove(pos) != null) changed = true;
        } finally {
            lock.unlockWrite(stamp);
        }
        if (changed) setDirty();
    }

    /** The body {@code bot} placed {@code block} at {@code pos}. Replaces whatever was recorded there. */
    public void recordBot(long pos, Block block, UUID bot) {
        BotPlacement placement = new BotPlacement(block, bot);
        boolean changed;
        long stamp = lock.writeLock();
        try {
            changed = !placement.equals(bots.put(pos, placement));
            if (player.remove(pos) != null) changed = true;
        } finally {
            lock.unlockWrite(stamp);
        }
        if (changed) setDirty();
    }

    /** Drops the record at {@code pos}, whoever's it was. Returns whether there was one. */
    public boolean forget(long pos) {
        boolean removed;
        long stamp = lock.writeLock();
        try {
            removed = player.remove(pos) != null;
            if (bots.remove(pos) != null) removed = true;
        } finally {
            lock.unlockWrite(stamp);
        }
        if (removed) setDirty();
        return removed;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Saving and loading
    // ---------------------------------------------------------------------------------------------------------

    /** The entries of one chunk while a save groups them. */
    private static final class Group {
        final LongArrayList player = new LongArrayList();
        final LongArrayList bot = new LongArrayList();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        List<Block> palette = new ArrayList<>();
        Reference2IntOpenHashMap<Block> paletteIndex = new Reference2IntOpenHashMap<>();
        paletteIndex.defaultReturnValue(-1);
        List<UUID> owners = new ArrayList<>();
        Object2IntOpenHashMap<UUID> ownerIndex = new Object2IntOpenHashMap<>();
        ownerIndex.defaultReturnValue(-1);
        Long2ObjectOpenHashMap<Group> groups = new Long2ObjectOpenHashMap<>();

        long stamp = lock.readLock();
        try {
            for (Long2ObjectMap.Entry<Block> entry : Long2ObjectMaps.fastIterable(player)) {
                long pos = entry.getLongKey();
                long block = indexOf(entry.getValue(), palette, paletteIndex);
                group(groups, pos).player.add(localOf(pos) | (block << PALETTE_SHIFT));
            }
            for (Long2ObjectMap.Entry<BotPlacement> entry : Long2ObjectMaps.fastIterable(bots)) {
                long pos = entry.getLongKey();
                BotPlacement placement = entry.getValue();
                long block = indexOf(placement.block, palette, paletteIndex);
                int owner = ownerIndex.getInt(placement.bot);
                if (owner < 0) {
                    owner = owners.size();
                    owners.add(placement.bot);
                    ownerIndex.put(placement.bot, owner);
                }
                group(groups, pos).bot.add(localOf(pos) | (block << PALETTE_SHIFT) | ((long) owner << OWNER_SHIFT));
            }
        } finally {
            lock.unlockRead(stamp);
        }

        tag.putInt("schema", SCHEMA);
        ListTag paletteTag = new ListTag();
        for (Block block : palette) paletteTag.add(StringTag.valueOf(BuiltInRegistries.BLOCK.getKey(block).toString()));
        tag.put("palette", paletteTag);
        ListTag ownerTag = new ListTag();
        for (UUID owner : owners) ownerTag.add(NbtUtils.createUUID(owner));
        tag.put("bots", ownerTag);

        long[] chunkKeys = groups.keySet().toLongArray();
        Arrays.sort(chunkKeys); // stable output: the file compresses better and diffs readably
        ListTag chunks = new ListTag();
        for (long key : chunkKeys) {
            Group group = groups.get(key);
            CompoundTag chunk = new CompoundTag();
            chunk.putInt("v", SCHEMA);
            chunk.putInt("x", ChunkPos.getX(key));
            chunk.putInt("z", ChunkPos.getZ(key));
            if (!group.player.isEmpty()) chunk.putLongArray("p", sorted(group.player));
            if (!group.bot.isEmpty()) chunk.putLongArray("b", sorted(group.bot));
            chunks.add(chunk);
        }
        tag.put("chunks", chunks);
        return tag;
    }

    /** Reads what {@link #save} wrote. Whatever does not decode is skipped and logged; this never throws. */
    public static PlacedBlocks load(CompoundTag tag, HolderLookup.Provider registries) {
        PlacedBlocks data = new PlacedBlocks();
        int schema = tag.getInt("schema");
        if (schema != SCHEMA) {
            // Reading another schema as if it were ours could misread it, so start empty and say so.
            MegaBlockBot.LOGGER.error("Placed-block data has schema {} but this version reads {}; ignoring it", schema, SCHEMA);
            return data;
        }

        ListTag paletteTag = tag.getList("palette", Tag.TAG_STRING);
        Block[] palette = new Block[paletteTag.size()];
        for (int i = 0; i < palette.length; i++) {
            ResourceLocation id = ResourceLocation.tryParse(paletteTag.getString(i));
            Optional<Block> block = id == null ? Optional.empty() : BuiltInRegistries.BLOCK.getOptional(id);
            if (block.isEmpty() || block.get() == Blocks.AIR) {
                MegaBlockBot.LOGGER.warn("Placed-block data names block '{}', which does not exist; its entries are dropped", paletteTag.getString(i));
            }
            palette[i] = block.filter(b -> b != Blocks.AIR).orElse(null);
        }
        ListTag ownerTag = tag.getList("bots", Tag.TAG_INT_ARRAY);
        UUID[] owners = new UUID[ownerTag.size()];
        for (int i = 0; i < owners.length; i++) {
            try {
                owners[i] = NbtUtils.loadUUID(ownerTag.get(i));
            } catch (RuntimeException e) {
                MegaBlockBot.LOGGER.error("Placed-block data has a bot owner #{} that does not decode: {}", i, e.getMessage());
            }
        }

        ListTag chunks = tag.getList("chunks", Tag.TAG_COMPOUND);
        int skippedEntries = 0;
        for (int i = 0; i < chunks.size(); i++) {
            try {
                skippedEntries += data.loadChunk(chunks.getCompound(i), palette, owners);
            } catch (RuntimeException e) {
                MegaBlockBot.LOGGER.error("Skipping placed-block chunk record #{} that does not decode: {}", i, e.toString());
            }
        }
        if (skippedEntries > 0) {
            MegaBlockBot.LOGGER.warn("Dropped {} placed-block entries that name a missing block or bot owner", skippedEntries);
        }
        return data;
    }

    /** Adds one chunk's entries; returns how many it had to drop. Throws for a chunk it cannot read at all. */
    private int loadChunk(CompoundTag chunk, Block[] palette, UUID[] owners) {
        if (chunk.getInt("v") != SCHEMA) {
            throw new IllegalArgumentException("entry schema " + chunk.getInt("v") + " is not " + SCHEMA);
        }
        if (!chunk.contains("x", Tag.TAG_INT) || !chunk.contains("z", Tag.TAG_INT)) {
            throw new IllegalArgumentException("missing chunk position");
        }
        int chunkX = chunk.getInt("x");
        int chunkZ = chunk.getInt("z");
        int dropped = 0;
        for (long packed : chunk.getLongArray("p")) {
            Block block = paletteBlock(palette, packed);
            if (block == null) {
                dropped++;
                continue;
            }
            player.put(posOf(chunkX, chunkZ, packed), block);
        }
        for (long packed : chunk.getLongArray("b")) {
            Block block = paletteBlock(palette, packed);
            int owner = (int) ((packed >>> OWNER_SHIFT) & OWNER_MASK);
            if (block == null || owner >= owners.length || owners[owner] == null) {
                dropped++;
                continue;
            }
            bots.put(posOf(chunkX, chunkZ, packed), new BotPlacement(block, owners[owner]));
        }
        return dropped;
    }

    private static Block paletteBlock(Block[] palette, long packed) {
        long index = (packed >>> PALETTE_SHIFT) & PALETTE_MASK;
        return index < palette.length ? palette[(int) index] : null;
    }

    private static long indexOf(Block block, List<Block> palette, Reference2IntOpenHashMap<Block> index) {
        int found = index.getInt(block);
        if (found >= 0) return found;
        index.put(block, palette.size());
        palette.add(block);
        return palette.size() - 1;
    }

    private static Group group(Long2ObjectOpenHashMap<Group> groups, long pos) {
        long key = ChunkPos.asLong(BlockPos.getX(pos) >> 4, BlockPos.getZ(pos) >> 4);
        Group group = groups.get(key);
        if (group == null) {
            group = new Group();
            groups.put(key, group);
        }
        return group;
    }

    private static long[] sorted(LongArrayList list) {
        long[] array = list.toLongArray();
        Arrays.sort(array);
        return array;
    }

    /** x and z inside the chunk, 4 bits each, then y shifted to be non-negative, 12 bits. */
    private static long localOf(long pos) {
        long x = BlockPos.getX(pos) & 15;
        long z = BlockPos.getZ(pos) & 15;
        long y = BlockPos.getY(pos) + Y_BIAS;
        return x | (z << 4) | (y << 8);
    }

    private static long posOf(int chunkX, int chunkZ, long packed) {
        long local = packed & LOCAL_MASK;
        int x = (chunkX << 4) | (int) (local & 15);
        int z = (chunkZ << 4) | (int) ((local >> 4) & 15);
        int y = (int) (local >> 8) - Y_BIAS;
        return BlockPos.asLong(x, y, z);
    }
}
