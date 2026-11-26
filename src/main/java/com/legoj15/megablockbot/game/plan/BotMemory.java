package com.legoj15.megablockbot.game.plan;

import com.legoj15.megablockbot.core.ItemKey;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * What one body remembers between snapshots (design 3.4, 3.5): the blocks it placed, which it may break and take from
 * again, and the containers whose contents it has seen. A snapshot reads the world as it is now and nothing else, so
 * facts the world cannot tell (who placed a table, what was in a chest the bot walked away from) live here.
 *
 * <p>One instance per body UUID ({@link #of}); a respawned body has the same UUID and so the same memory. Server
 * thread only. Nothing records into it yet except tests: the brain's placement hook and the container tasks will
 * call the {@code note} methods, and persist it with {@link #save()} next to the brain's own state; at spawn the body
 * gets what it saved back with {@link #restore}.
 *
 * <p>The sets are not bounded. A bot that places a block per second for a day has some tens of thousands of longs;
 * the day that matters, the brain forgets what it broke ({@link #forgetPlaced}).
 */
public final class BotMemory {
    private static final int SAVE_SCHEMA = 1;
    private static final Map<UUID, BotMemory> BY_BODY = new ConcurrentHashMap<>();

    /** A container the body has seen the inside of, as it was then. */
    public record RememberedContainer(String dimension, BlockPos pos, String block, Map<ItemKey, Integer> contents) {
        public RememberedContainer {
            pos = pos.immutable();
            contents = Map.copyOf(contents);
        }
    }

    private record ContainerKey(String dimension, long pos) {}

    // Dimension id to BlockPos.asLong() of every block placed there.
    private final Map<String, Set<Long>> placed = new HashMap<>();
    private final Map<ContainerKey, RememberedContainer> containers = new LinkedHashMap<>();

    public static BotMemory of(UUID body) {
        return BY_BODY.computeIfAbsent(body, id -> new BotMemory());
    }

    /** Drops one body's memory, for a body that is deleted for good. A despawn keeps it: the body comes back. */
    public static void forget(UUID body) {
        BY_BODY.remove(body);
    }

    /** Drops every memory: they belong to the server that made them. */
    static void clearAll() {
        BY_BODY.clear();
    }

    // ---- placed blocks -----------------------------------------------------------------------------------------

    public void notePlaced(String dimension, BlockPos pos) {
        placed.computeIfAbsent(dimension, d -> new HashSet<>()).add(pos.asLong());
    }

    /** For a block the body broke again, or one it saw gone. */
    public void forgetPlaced(String dimension, BlockPos pos) {
        Set<Long> inDimension = placed.get(dimension);
        if (inDimension == null) return;
        inDimension.remove(pos.asLong());
        if (inDimension.isEmpty()) placed.remove(dimension);
    }

    public boolean isPlaced(String dimension, BlockPos pos) {
        Set<Long> inDimension = placed.get(dimension);
        return inDimension != null && inDimension.contains(pos.asLong());
    }

    public int placedCount() {
        int count = 0;
        for (Set<Long> inDimension : placed.values()) count += inDimension.size();
        return count;
    }

    // ---- containers --------------------------------------------------------------------------------------------

    /** Records what the body saw in the container at that position, replacing what it saw there before. */
    public void noteContainer(String dimension, BlockPos pos, String block, Map<ItemKey, Integer> contents) {
        containers.put(new ContainerKey(dimension, pos.asLong()), new RememberedContainer(dimension, pos, block, contents));
    }

    /** Everything remembered, in the order it was first noted. */
    public List<RememberedContainer> containers() {
        return List.copyOf(containers.values());
    }

    // ---- persistence -------------------------------------------------------------------------------------------

    /** The compact form for the body's save data: schema, placed positions per dimension, containers with contents. */
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("schema", SAVE_SCHEMA);

        CompoundTag placedTag = new CompoundTag();
        // Sorted so that two saves of one memory are equal and a diff of the player data stays readable.
        for (Map.Entry<String, Set<Long>> entry : new TreeMap<>(placed).entrySet()) {
            long[] positions = entry.getValue().stream().mapToLong(Long::longValue).sorted().toArray();
            placedTag.putLongArray(entry.getKey(), positions);
        }
        tag.put("placed", placedTag);

        ListTag containerList = new ListTag();
        for (RememberedContainer container : containers.values()) {
            CompoundTag one = new CompoundTag();
            one.putString("dimension", container.dimension());
            one.putLong("pos", container.pos().asLong());
            one.putString("block", container.block());
            ListTag items = new ListTag();
            for (Map.Entry<ItemKey, Integer> item : new TreeMap<>(container.contents()).entrySet()) {
                CompoundTag entry = new CompoundTag();
                entry.putString("id", item.getKey().id());
                entry.putInt("count", item.getValue());
                items.add(entry);
            }
            one.put("items", items);
            containerList.add(one);
        }
        tag.put("containers", containerList);
        return tag;
    }

    /**
     * Reads what {@link #save()} wrote and makes it the memory of that body, replacing any it had: {@link #of} answers
     * with the restored one from then on. A missing or malformed part is skipped, never thrown: a bad save must not
     * keep a body from starting (design 3.4, Persistence), and losing a memory only costs the bot a second look.
     *
     * @return the installed memory
     */
    public static BotMemory restore(UUID body, CompoundTag tag) {
        BotMemory memory = load(tag);
        BY_BODY.put(body, memory);
        return memory;
    }

    private static BotMemory load(CompoundTag tag) {
        BotMemory memory = new BotMemory();
        if (tag.contains("placed", Tag.TAG_COMPOUND)) {
            CompoundTag placedTag = tag.getCompound("placed");
            for (String dimension : placedTag.getAllKeys()) {
                if (!placedTag.contains(dimension, Tag.TAG_LONG_ARRAY)) continue;
                Set<Long> positions = memory.placed.computeIfAbsent(dimension, d -> new HashSet<>());
                for (long pos : placedTag.getLongArray(dimension)) positions.add(pos);
            }
        }
        if (tag.contains("containers", Tag.TAG_LIST)) {
            ListTag list = tag.getList("containers", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag one = list.getCompound(i);
                if (!one.contains("dimension", Tag.TAG_STRING) || !one.contains("pos", Tag.TAG_LONG)
                    || !one.contains("block", Tag.TAG_STRING)) continue;
                Map<ItemKey, Integer> contents = new HashMap<>();
                ListTag items = one.getList("items", Tag.TAG_COMPOUND);
                for (int j = 0; j < items.size(); j++) {
                    CompoundTag entry = items.getCompound(j);
                    if (entry.contains("id", Tag.TAG_STRING) && entry.getInt("count") > 0) {
                        contents.merge(ItemKey.of(entry.getString("id")), entry.getInt("count"), Integer::sum);
                    }
                }
                memory.noteContainer(one.getString("dimension"), BlockPos.of(one.getLong("pos")), one.getString("block"), contents);
            }
        }
        return memory;
    }

    /** What the memory holds, for a log line or a failed assertion. */
    @Override
    public String toString() {
        return "BotMemory(" + placedCount() + " placed blocks, " + containers.size() + " containers)";
    }
}
