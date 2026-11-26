package com.legoj15.megablockbot.game.safety;

import com.legoj15.megablockbot.MegaBlockBot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Where an owner respawns (their bed or respawn anchor), whether or not they are online. Server thread only.
 *
 * <p>Online, it is read from the player on every call. Offline it is read from their saved player data (a bot keeps
 * working while its owner is away, and that is exactly when their bed needs protecting), which vanilla writes when they
 * log out. So a cached answer can only be wrong in the ways a cache can: it is never trusted across a login or a logout
 * (the first look after a stretch online re-reads the file, whatever was cached), and an offline answer is checked
 * against the file's modification time and size every {@link #RECHECK_TICKS} ticks, which catches an owner who logged
 * in, moved their bed and logged out again between two looks.
 */
final class OwnerSpawns {
    record Spawn(ResourceKey<Level> dimension, BlockPos pos) {}

    /** Cached "has no respawn point": distinguishes it from "not looked at yet" (a null {@link Known#spawn}). */
    private static final Spawn NONE = new Spawn(Level.OVERWORLD, BlockPos.ZERO);

    /** How often an offline owner's file is looked at again for a change. */
    private static final int RECHECK_TICKS = SafetyConstants.ZONE_REFRESH_TICKS;

    /** What is known about one owner. Mutable and reused, so asking again allocates nothing. */
    private static final class Known {
        @Nullable
        Spawn spawn;
        boolean wasOnline;
        long checkedAt;
        // Of the saved data the offline answer was read from; null modified time: there was no file.
        @Nullable
        FileTime modified;
        long size = -1;
    }

    private final MinecraftServer server;
    private final Map<UUID, Known> known = new HashMap<>();

    OwnerSpawns(MinecraftServer server) {
        this.server = server;
    }

    /** The owner's respawn point, or null when they have none. */
    @Nullable
    Spawn of(UUID owner) {
        Known state = known.computeIfAbsent(owner, id -> new Known());
        ServerPlayer online = server.getPlayerList().getPlayer(owner);
        if (online != null) {
            BlockPos pos = online.getRespawnPosition();
            state.spawn = pos == null ? NONE : same(state.spawn, online.getRespawnDimension(), pos);
            state.wasOnline = true;
        } else {
            long now = server.getTickCount();
            // Never seen, or just seen online (their data was written when they left): read it. Otherwise only on a timer.
            boolean mustRead = state.spawn == null || state.wasOnline;
            if (mustRead || now - state.checkedAt >= RECHECK_TICKS) {
                refreshFromSavedData(owner, state, mustRead);
                state.wasOnline = false;
                state.checkedAt = now;
            }
        }
        return state.spawn == NONE ? null : state.spawn;
    }

    /** Forgets every owner not in {@code owners}: nothing needs their respawn point while they have no body. */
    void retain(Set<UUID> owners) {
        known.keySet().retainAll(owners);
    }

    /** Keeps the cached instance when nothing changed, so a zone refresh that finds no change allocates no zone. */
    private static Spawn same(@Nullable Spawn cached, ResourceKey<Level> dimension, BlockPos pos) {
        if (cached != null && cached != NONE && cached.dimension().equals(dimension) && cached.pos().equals(pos)) return cached;
        return new Spawn(dimension, pos.immutable());
    }

    /** Re-reads the owner's player data when it changed since last read, or when {@code force}. */
    private void refreshFromSavedData(UUID owner, Known state, boolean force) {
        Path file = server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(owner + ".dat");
        BasicFileAttributes attributes = null;
        try {
            attributes = Files.readAttributes(file, BasicFileAttributes.class);
        } catch (NoSuchFileException e) {
            // Never saved: no respawn point yet.
        } catch (IOException e) {
            MegaBlockBot.LOGGER.warn("Could not look at the player data of owner {} at {}: {}", owner, file, e.toString());
        }
        FileTime modified = attributes != null ? attributes.lastModifiedTime() : null;
        long size = attributes != null ? attributes.size() : -1;
        if (!force && state.spawn != null && Objects.equals(modified, state.modified) && size == state.size) return;
        state.modified = modified;
        state.size = size;
        state.spawn = attributes != null && attributes.isRegularFile() ? readSaved(owner, file) : NONE;
    }

    private static Spawn readSaved(UUID owner, Path file) {
        try {
            CompoundTag tag = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            // The same test vanilla's own load makes: any numeric tag will do.
            if (!tag.contains("SpawnX", Tag.TAG_ANY_NUMERIC) || !tag.contains("SpawnY", Tag.TAG_ANY_NUMERIC)
                || !tag.contains("SpawnZ", Tag.TAG_ANY_NUMERIC)) {
                return NONE;
            }
            ResourceLocation dimension = tag.contains("SpawnDimension", Tag.TAG_STRING)
                ? ResourceLocation.tryParse(tag.getString("SpawnDimension")) : Level.OVERWORLD.location();
            if (dimension == null) return NONE;
            return new Spawn(ResourceKey.create(Registries.DIMENSION, dimension),
                new BlockPos(tag.getInt("SpawnX"), tag.getInt("SpawnY"), tag.getInt("SpawnZ")));
        } catch (IOException | RuntimeException e) {
            // Unreadable player data: the owner's bed goes unprotected, and that is said, not hidden.
            MegaBlockBot.LOGGER.warn("Could not read the respawn point of owner {} from {}: {}", owner, file, e.toString());
            return NONE;
        }
    }
}
