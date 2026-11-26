package com.legoj15.megablockbot.game.body;

import com.legoj15.megablockbot.MegaBlockBot;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * The body record (design 3.4, Persistence): one server-level file, {@code data/megablockbot_bodies.dat} in the
 * overworld, saying which bodies exist, whose they are, where they were last and whether they want to stay loaded.
 * A body's inventory, health and brain state are NOT here: they are in the body's own player data, saved by vanilla.
 *
 * <p>Schema-versioned. A record that does not decode is skipped and logged; it never fails the whole file.
 */
final class BodyRecords extends SavedData {
    static final String ID = "megablockbot_bodies";
    static final int SCHEMA = 1;
    static final SavedData.Factory<BodyRecords> FACTORY = new SavedData.Factory<>(BodyRecords::new, BodyRecords::load);

    /**
     * One body. {@code stayLoaded} is what its brain said last (an active or queued goal): bodies with it set are
     * spawned again at server start, the others wait for their owner to ask.
     */
    record Entry(UUID uuid, String name, @Nullable UUID ownerUuid, @Nullable String ownerName, String dimension,
                 double x, double y, double z, boolean stayLoaded) {
        CompoundTag toTag() {
            CompoundTag tag = new CompoundTag();
            tag.putUUID("uuid", uuid);
            tag.putString("name", name);
            if (ownerUuid != null) tag.putUUID("ownerUuid", ownerUuid);
            if (ownerName != null) tag.putString("ownerName", ownerName);
            tag.putString("dimension", dimension);
            tag.putDouble("x", x);
            tag.putDouble("y", y);
            tag.putDouble("z", z);
            tag.putBoolean("stayLoaded", stayLoaded);
            return tag;
        }

        static Entry fromTag(CompoundTag tag) {
            if (!tag.hasUUID("uuid") || !tag.contains("name", Tag.TAG_STRING) || !tag.contains("dimension", Tag.TAG_STRING)
                || !tag.contains("x", Tag.TAG_DOUBLE) || !tag.contains("y", Tag.TAG_DOUBLE) || !tag.contains("z", Tag.TAG_DOUBLE)) {
                throw new IllegalArgumentException("missing field");
            }
            String name = tag.getString("name");
            if (!BodyManager.NAME.matcher(name).matches()) throw new IllegalArgumentException("bad name '" + name + "'");
            return new Entry(tag.getUUID("uuid"), name,
                tag.hasUUID("ownerUuid") ? tag.getUUID("ownerUuid") : null,
                tag.contains("ownerName", Tag.TAG_STRING) ? tag.getString("ownerName") : null,
                tag.getString("dimension"), tag.getDouble("x"), tag.getDouble("y"), tag.getDouble("z"),
                tag.getBoolean("stayLoaded"));
        }
    }

    private final Map<UUID, Entry> entries = new LinkedHashMap<>();

    BodyRecords() {}

    static BodyRecords of(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, ID);
    }

    private static BodyRecords load(CompoundTag tag, HolderLookup.Provider registries) {
        BodyRecords records = new BodyRecords();
        int schema = tag.getInt("schema");
        if (schema != SCHEMA) {
            // No other schema exists yet. Reading a newer one as if it were ours could misread it, so start empty and say so.
            MegaBlockBot.LOGGER.error("Body record has schema {} but this version reads {}; ignoring it", schema, SCHEMA);
            return records;
        }
        ListTag list = tag.getList("bodies", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            try {
                Entry entry = Entry.fromTag(list.getCompound(i));
                records.entries.put(entry.uuid(), entry);
            } catch (RuntimeException e) {
                MegaBlockBot.LOGGER.error("Skipping body record #{} that does not decode: {}", i, e.getMessage());
            }
        }
        return records;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("schema", SCHEMA);
        ListTag list = new ListTag();
        for (Entry entry : entries.values()) list.add(entry.toTag());
        tag.put("bodies", list);
        return tag;
    }

    Collection<Entry> all() {
        return List.copyOf(entries.values());
    }

    @Nullable
    Entry get(UUID uuid) {
        return entries.get(uuid);
    }

    /** Names compare like the game's own: ignoring case. */
    @Nullable
    Entry findByName(String name) {
        for (Entry entry : entries.values()) {
            if (entry.name().equalsIgnoreCase(name)) return entry;
        }
        return null;
    }

    /** Adds or replaces the entry; marks the file dirty only when something changed. */
    void put(Entry entry) {
        Entry previous = entries.put(entry.uuid(), entry);
        if (!entry.equals(previous)) setDirty();
    }

    void remove(UUID uuid) {
        if (entries.remove(uuid) != null) setDirty();
    }

    List<Entry> stayLoaded() {
        List<Entry> wanted = new ArrayList<>();
        for (Entry entry : entries.values()) {
            if (entry.stayLoaded()) wanted.add(entry);
        }
        return wanted;
    }
}
