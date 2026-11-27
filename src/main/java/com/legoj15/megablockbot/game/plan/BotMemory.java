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
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * What one body remembers between snapshots (design 3.4, 3.5): the blocks it placed, which it may break and take from
 * again, and the containers whose contents it has seen. A snapshot reads the world as it is now and nothing else, so
 * facts the world cannot tell (who placed a table, what was in a chest the bot walked away from) live here.
 *
 * <p>It also holds the containers the owner named as supply containers (design 3.5): ones the body may take from
 * although it did not place them. Those are the owner's word, not something the world can say, so they are saved with
 * the body like the rest. Naming a container records where it is and which block it is, and nothing of what is in it:
 * a player cannot see into a closed chest, so the contents are known only from a note the body makes when it opens it.
 *
 * <p>One instance per body UUID ({@link #of}); a respawned body has the same UUID and so the same memory. Server
 * thread only. The crafting task notes the blocks it places, and the tasks that open a container (withdraw, look
 * inside) note what they find; the brain persists it with {@link #save()} next to its own state, and at spawn
 * the body gets what it saved back with {@link #restore}.
 *
 * <p>The sets are not bounded. A bot that places a block per second for a day has some tens of thousands of longs;
 * the day that matters, the brain forgets what it broke ({@link #forgetPlaced}).
 */
public final class BotMemory {
    private static final int SAVE_SCHEMA = 1;
    private static final Map<UUID, BotMemory> BY_BODY = new ConcurrentHashMap<>();

    /**
     * A container the body has seen the inside of, as it was then: the note it made when it opened it. {@code pos} is the
     * half with the lower {@code BlockPos.asLong} when it is a double chest and {@code other} the second half, else
     * {@code other} is null: a note is per inventory, never per block, so a pair is one note.
     */
    public record RememberedContainer(String dimension, BlockPos pos, @Nullable BlockPos other, String block,
                                      Map<ItemKey, Integer> contents) {
        public RememberedContainer {
            pos = pos.immutable();
            other = other == null ? null : other.immutable();
            contents = Map.copyOf(contents);
        }

        /** Whether {@code at} in {@code in} is one of this inventory's blocks. */
        public boolean covers(String in, BlockPos at) {
            return dimension.equals(in) && (pos.equals(at) || at.equals(other));
        }
    }

    /**
     * A container its owner named for the body to take from. {@code pos} is the half with the lower
     * {@code BlockPos.asLong} when it is a double chest and {@code other} the second half, else {@code other} is null:
     * one entry is one inventory, and naming either half names both (design 3.5). {@code block} is the id of the block
     * that stood there when it was named (both halves of a double chest are one block): the owner's word is for that
     * block, and a different one at the position is something nobody named.
     */
    public record SupplyContainer(String dimension, BlockPos pos, @Nullable BlockPos other, String block) {
        public SupplyContainer {
            pos = pos.immutable();
            other = other == null ? null : other.immutable();
        }

        /** Whether {@code at} in {@code in} is one of this inventory's blocks. */
        public boolean covers(String in, BlockPos at) {
            return dimension.equals(in) && (pos.equals(at) || at.equals(other));
        }
    }

    private record ContainerKey(String dimension, long pos) {}

    // Dimension id to BlockPos.asLong() of every block placed there.
    private final Map<String, Set<Long>> placed = new HashMap<>();
    private final Map<ContainerKey, RememberedContainer> containers = new LinkedHashMap<>();
    private final Map<ContainerKey, SupplyContainer> supply = new LinkedHashMap<>();
    // The last scan of the blocks around the body (BodySight). A cache of the world, so it is never saved.
    private BodySight.Taken sight;
    private int sightScans;

    public static BotMemory of(UUID body) {
        return BY_BODY.computeIfAbsent(body, id -> new BotMemory());
    }

    /** One body's memory if it has one, without making one: for a question that must not leave a trace. */
    @Nullable
    public static BotMemory peek(UUID body) {
        return BY_BODY.get(body);
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

    /**
     * Records what the body saw in the container at that position, replacing what it saw there before. A note is per
     * inventory: whatever was noted of any block of this one (a lone chest that has since become half of a double chest
     * was noted under its own position) is replaced by this note, so one inventory is never counted twice.
     */
    public void noteContainer(String dimension, BlockPos pos, String block, Map<ItemKey, Integer> contents) {
        noteContainer(dimension, pos, null, block, contents);
    }

    /** As above, for an inventory of two blocks ({@code other} is the second half of a double chest, else null). */
    public void noteContainer(String dimension, BlockPos pos, @Nullable BlockPos other, String block,
                              Map<ItemKey, Integer> contents) {
        forgetContainersCovering(dimension, pos, other);
        containers.put(new ContainerKey(dimension, pos.asLong()), new RememberedContainer(dimension, pos, other, block, contents));
    }

    /** Everything remembered, in the order it was first noted. */
    public List<RememberedContainer> containers() {
        return List.copyOf(containers.values());
    }

    /** What was noted of the container one of whose blocks is at that position, or null. */
    @Nullable
    public RememberedContainer containerAt(String dimension, BlockPos pos) {
        for (RememberedContainer note : containers.values()) {
            if (note.covers(dimension, pos)) return note;
        }
        return null;
    }

    /** Forgets what was noted of the container one of whose blocks is at that position: it is gone, or no longer the body's to use. */
    public void forgetContainer(String dimension, BlockPos pos) {
        forgetContainersCovering(dimension, pos, null);
    }

    private void forgetContainersCovering(String dimension, BlockPos pos, @Nullable BlockPos other) {
        containers.values().removeIf(note -> note.covers(dimension, pos) || (other != null && note.covers(dimension, other)));
    }

    // ---- supply containers -------------------------------------------------------------------------------------

    /**
     * Records that the owner named this container, and forgets what was noted of its blocks: that was the container
     * before the owner's word, and the body knows what is in it only when it has been inside since. Naming it again
     * therefore sends the body to look again. An entry that shares a block with it is replaced (the owner named the other
     * half of a double chest, or a chest was joined to another since). Returns false when the very same entry was already
     * there.
     */
    public boolean addSupply(SupplyContainer entry) {
        boolean fresh = putSupply(entry);
        forgetContainersCovering(entry.dimension(), entry.pos(), entry.other());
        return fresh;
    }

    private boolean putSupply(SupplyContainer entry) {
        SupplyContainer same = supply.get(new ContainerKey(entry.dimension(), entry.pos().asLong()));
        if (entry.equals(same)) return false;
        supply.values().removeIf(old -> old.covers(entry.dimension(), entry.pos())
            || (entry.other() != null && old.covers(entry.dimension(), entry.other())));
        supply.put(new ContainerKey(entry.dimension(), entry.pos().asLong()), entry);
        return true;
    }

    /** Takes the container one of whose blocks is {@code at} off the list; false when none is there. */
    public boolean removeSupply(String dimension, BlockPos at) {
        return supply.values().removeIf(entry -> entry.covers(dimension, at));
    }

    /** The entry one of whose blocks is {@code at}, or null: the owner's word for that block. */
    @Nullable
    public SupplyContainer supplyAt(String dimension, BlockPos at) {
        for (SupplyContainer entry : supply.values()) {
            if (entry.covers(dimension, at)) return entry;
        }
        return null;
    }

    public boolean isSupply(String dimension, BlockPos at) {
        return supplyAt(dimension, at) != null;
    }

    /** The containers the owner named, in the order named. */
    public List<SupplyContainer> supply() {
        return List.copyOf(supply.values());
    }

    // ---- the sight ---------------------------------------------------------------------------------------------

    /** The scan {@link BodySight} kept for this body, or null when it has taken none. */
    BodySight.Taken sight() {
        return sight;
    }

    void keepSight(BodySight.Taken taken) {
        sight = taken;
        sightScans++;
    }

    /** How many scans this body has had kept; a snapshot that reads the kept one adds none. */
    int sightScans() {
        return sightScans;
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
            if (container.other() != null) one.putLong("other", container.other().asLong());
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

        // Absent when there is none, so a body that was never given one saves what it always did.
        if (!supply.isEmpty()) {
            ListTag supplyList = new ListTag();
            for (SupplyContainer entry : supply.values()) {
                CompoundTag one = new CompoundTag();
                one.putString("dimension", entry.dimension());
                one.putLong("pos", entry.pos().asLong());
                if (entry.other() != null) one.putLong("other", entry.other().asLong());
                one.putString("block", entry.block());
                supplyList.add(one);
            }
            tag.put("supply", supplyList);
        }
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
                BlockPos other = one.contains("other", Tag.TAG_LONG) ? BlockPos.of(one.getLong("other")) : null;
                memory.noteContainer(one.getString("dimension"), BlockPos.of(one.getLong("pos")), other, one.getString("block"), contents);
            }
        }
        if (tag.contains("supply", Tag.TAG_LIST)) {
            ListTag list = tag.getList("supply", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag one = list.getCompound(i);
                // An entry with no block is not a word about any block: skipped like any other damaged part.
                if (!one.contains("dimension", Tag.TAG_STRING) || !one.contains("pos", Tag.TAG_LONG)
                    || !one.contains("block", Tag.TAG_STRING)) continue;
                BlockPos other = one.contains("other", Tag.TAG_LONG) ? BlockPos.of(one.getLong("other")) : null;
                // Not addSupply: that forgets the notes of the container, which were just restored.
                memory.putSupply(new SupplyContainer(one.getString("dimension"), BlockPos.of(one.getLong("pos")), other,
                    one.getString("block")));
            }
        }
        return memory;
    }

    /** What the memory holds, for a log line or a failed assertion. */
    @Override
    public String toString() {
        return "BotMemory(" + placedCount() + " placed blocks, " + containers.size() + " containers, " + supply.size()
            + " supply containers)";
    }
}
