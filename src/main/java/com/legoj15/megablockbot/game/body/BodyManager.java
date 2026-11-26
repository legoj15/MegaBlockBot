package com.legoj15.megablockbot.game.body;

import com.legoj15.megablockbot.MegaBlockBot;
import com.mojang.authlib.GameProfile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.ChatVisiblity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;

/**
 * Owns the bodies of one server: spawns them, despawns them, keeps the body record, applies the idle policy.
 * Everything runs on the server thread. The bodies themselves are the {@link BotPlayer}s in the player list; the
 * manager keeps no list of its own that could go stale when a body is replaced by a respawn.
 */
public final class BodyManager {
    /** A name a body may have: the vanilla player-name alphabet. */
    public static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");

    private static final Map<MinecraftServer, BodyManager> INSTANCES = new ConcurrentHashMap<>();

    private final MinecraftServer server;
    // Ticks a body has gone unneeded (see applyIdlePolicy), by UUID so a respawn does not reset it.
    private final Map<UUID, Integer> idleTicks = new HashMap<>();
    private final List<BotPlayer> pendingRespawns = new ArrayList<>();
    @Nullable
    private BodyRecords records;
    private long ticks;

    private BodyManager(MinecraftServer server) {
        this.server = server;
    }

    public static BodyManager of(MinecraftServer server) {
        return INSTANCES.computeIfAbsent(server, BodyManager::new);
    }

    /** The UUID of the body called {@code name}: offline-style and never a real account's. */
    public static UUID uuidFor(String name) {
        return UUID.nameUUIDFromBytes(("megablockbot:" + name).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Whether {@code source} may command a body owned by {@code owner} (null: no owner). The owner may; so may a
     * permission-level-2 source, which covers ops and command blocks, and the console, which runs at level 4
     * ({@code MinecraftServer.createCommandSourceStack}).
     */
    public static boolean mayCommand(@Nullable UUID owner, CommandSourceStack source) {
        if (source.hasPermission(2)) return true;
        return owner != null && source.getEntity() != null && owner.equals(source.getEntity().getUUID());
    }

    // ---------------------------------------------------------------------------------------------------------
    // Lookup
    // ---------------------------------------------------------------------------------------------------------

    public List<BotPlayer> all() {
        List<BotPlayer> bodies = new ArrayList<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player instanceof BotPlayer body) bodies.add(body);
        }
        return bodies;
    }

    /** The logged-in body with this name (compared ignoring case, like player names are), or null. */
    @Nullable
    public BotPlayer get(String name) {
        for (BotPlayer body : all()) {
            if (body.getGameProfile().getName().equalsIgnoreCase(name)) return body;
        }
        return null;
    }

    @Nullable
    public BotPlayer get(UUID id) {
        return server.getPlayerList().getPlayer(id) instanceof BotPlayer body ? body : null;
    }

    BodyRecords records() {
        if (records == null) records = BodyRecords.of(server);
        return records;
    }

    /** Every body ever spawned and not forgotten, logged in or not. */
    List<BodyRecords.Entry> recorded() {
        return new ArrayList<>(records().all());
    }

    @Nullable
    BodyRecords.Entry recordFor(String name) {
        return records().findByName(name);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Spawn and despawn
    // ---------------------------------------------------------------------------------------------------------

    /** The spawn point of a level as a player would be placed there: on the surface above the world spawn. */
    public static Vec3 surfaceSpawn(ServerLevel level) {
        BlockPos spawn = level.getSharedSpawnPos();
        return Vec3.atBottomCenterOf(level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, spawn));
    }

    public BotPlayer spawn(String name, @Nullable GameProfile owner, ServerLevel level, Vec3 pos) throws BodyException {
        return spawn(name, owner, level, pos, 0.0F, null);
    }

    /**
     * Spawns the body called {@code name} at {@code pos}, through the same {@code PlayerList.placeNewPlayer} a client
     * login uses. The position applies only on a first spawn: a body with saved player data comes back where it was,
     * with what it had (so does its owner: the saved one wins over {@code owner}).
     *
     * @param brainFactory makes this body's brain; null for the registered {@link BotBrains#factory()}. A test hook.
     */
    public BotPlayer spawn(String name, @Nullable GameProfile owner, ServerLevel level, Vec3 pos, float yaw,
                           @Nullable Supplier<BotBrain> brainFactory) throws BodyException {
        if (!NAME.matcher(name).matches()) {
            throw new BodyException("'" + name + "' is not a valid body name: use 3 to 16 letters, digits or underscores");
        }
        PlayerList players = server.getPlayerList();
        if (players.getPlayerByName(name) != null) {
            throw new BodyException(name + " is already online");
        }
        BodyRecords.Entry known = records().findByName(name);
        if (known != null && !known.name().equals(name)) {
            throw new BodyException("a body called " + known.name() + " already exists; names are not case-sensitive");
        }

        UUID id = uuidFor(name);
        boolean hasSavedData = hasSavedData(id);
        GameProfile profile = new GameProfile(id, name);
        BotPlayer body = new BotPlayer(server, level, profile, clientInformation(),
            brainFactory != null ? brainFactory : BotBrains.factory());
        if (owner != null) body.setOwner(owner.getId(), owner.getName());
        // On a first spawn nothing overwrites this; with saved data, PlayerList.load puts the body back where it was.
        body.moveTo(pos.x, pos.y, pos.z, yaw, 0.0F);

        players.placeNewPlayer(BodyConnection.create(), body, new CommonListenerCookie(profile, 0, body.clientInformation(), false));
        // The server's default game mode (a GameTest world is creative) and a saved one both lose: a body plays survival.
        body.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
        // placeNewPlayer puts a body without saved data into the overworld; a first spawn elsewhere has to move.
        if (!hasSavedData && body.level() != level) {
            body.teleportTo(level, pos.x, pos.y, pos.z, yaw, 0.0F);
        }

        idleTicks.remove(id);
        records().put(entryFor(body, body.brain().wantsToStayLoaded()));
        MegaBlockBot.LOGGER.info("Spawned body {} at {} in {} (owner: {}, {})", name, body.position(),
            body.level().dimension().location(), body.ownerName() != null ? body.ownerName() : "none",
            hasSavedData ? "from saved data" : "first spawn");
        return body;
    }

    /** Logs the body out through the normal player-removal path, so vanilla saves it. It will not respawn by itself. */
    public void despawn(BotPlayer body) {
        despawn(body, "despawned");
    }

    private void despawn(BotPlayer body, String why) {
        if (body.hasDisconnected()) return;
        // An explicit despawn means the owner does not want it back at the next server start, whatever the goal says.
        records().put(entryFor(body, false));
        idleTicks.remove(body.getUUID());
        pendingRespawns.remove(body);
        MegaBlockBot.LOGGER.info("Despawning body {} ({})", body.getGameProfile().getName(), why);
        body.logOut(Component.literal(why));
    }

    /**
     * Removes everything saved for an offline body: its player data, stats, advancements and its record. For tests
     * and for a future "delete" command; refuses while the body is logged in.
     */
    public void deleteSaved(UUID id) throws BodyException {
        if (get(id) != null) throw new BodyException("that body is logged in");
        try {
            Files.deleteIfExists(server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(id + ".dat"));
            Files.deleteIfExists(server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(id + ".dat_old"));
            Files.deleteIfExists(server.getWorldPath(LevelResource.PLAYER_STATS_DIR).resolve(id + ".json"));
            Files.deleteIfExists(server.getWorldPath(LevelResource.PLAYER_ADVANCEMENTS_DIR).resolve(id + ".json"));
        } catch (IOException e) {
            throw new BodyException("could not delete the saved data: " + e.getMessage());
        }
        records().remove(id);
    }

    private boolean hasSavedData(UUID id) {
        Path dir = server.getWorldPath(LevelResource.PLAYER_DATA_DIR);
        return Files.exists(dir.resolve(id + ".dat")) || Files.exists(dir.resolve(id + ".dat_old"));
    }

    /**
     * What a body tells the server about itself. View distance is small (a body only needs the chunks around it),
     * and it is listed, like a player, in the tab list and the server's player sample ({@code allowsListing}).
     */
    static ClientInformation clientInformation() {
        return new ClientInformation("en_us", BodyConstants.VIEW_DISTANCE, ChatVisiblity.FULL, true,
            0x7f /* every skin layer */, HumanoidArm.RIGHT, false, true);
    }

    private static BodyRecords.Entry entryFor(BotPlayer body, boolean stayLoaded) {
        return new BodyRecords.Entry(body.getUUID(), body.getGameProfile().getName(), body.ownerId(), body.ownerName(),
            body.level().dimension().location().toString(), body.getX(), body.getY(), body.getZ(), stayLoaded);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Death and respawn
    // ---------------------------------------------------------------------------------------------------------

    /** A body died. Its replacement is made at the end of the tick, not inside the damage that killed it. */
    void onDeath(BotPlayer body, DeathRecord death) {
        MegaBlockBot.LOGGER.info("Body {} died at {} in {} (game time {}), {} item stack(s) dropped",
            body.getGameProfile().getName(), death.pos().toShortString(), death.dimension(), death.gameTime(), death.dropPositions().size());
        queueRespawn(body);
    }

    void queueRespawn(BotPlayer body) {
        if (!pendingRespawns.contains(body)) pendingRespawns.add(body);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Lifecycle hooks (BodyFeature wires them to the loader)
    // ---------------------------------------------------------------------------------------------------------

    /** Server start: every body recorded as wanting to stay loaded is spawned again; the others wait to be asked. */
    void onServerStarted() {
        for (BodyRecords.Entry entry : records().stayLoaded()) {
            try {
                ResourceLocation id = ResourceLocation.tryParse(entry.dimension());
                ServerLevel level = id == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
                if (level == null) {
                    MegaBlockBot.LOGGER.warn("Body {} was in unknown dimension {}; using the overworld", entry.name(), entry.dimension());
                    level = server.overworld();
                }
                GameProfile owner = entry.ownerUuid() == null ? null : new GameProfile(entry.ownerUuid(), entry.ownerName() != null ? entry.ownerName() : "unknown");
                spawn(entry.name(), owner, level, new Vec3(entry.x(), entry.y(), entry.z()));
            } catch (BodyException | RuntimeException e) {
                // One body that will not come back must not keep the others from it.
                MegaBlockBot.LOGGER.error("Could not respawn body {} at server start", entry.name(), e);
            }
        }
    }

    /** Server stopping: bring the record up to date while the bodies are still there. Vanilla's shutdown saves them. */
    void onServerStopping() {
        refreshRecords();
        INSTANCES.remove(server);
    }

    /** End of every server tick. */
    void tick() {
        ticks++;
        if (!pendingRespawns.isEmpty()) {
            List<BotPlayer> batch = new ArrayList<>(pendingRespawns);
            pendingRespawns.clear();
            for (BotPlayer body : batch) {
                body.performRespawn();
                BotPlayer replacement = get(body.getUUID());
                if (replacement != null) records().put(entryFor(replacement, replacement.brain().wantsToStayLoaded()));
            }
        }
        if (ticks % BodyConstants.IDLE_CHECK_INTERVAL_TICKS == 0) applyIdlePolicy();
        if (ticks % BodyConstants.RECORD_REFRESH_TICKS == 0) refreshRecords();
    }

    /**
     * Design 3.1: chunks stay loaded only while a goal needs them. A body whose brain does not want to stay loaded
     * and whose owner is not around (online, same dimension, within {@link BodyConstants#OWNER_NEAR_BLOCKS}) is
     * despawned once that has held for {@link BodyConstants#IDLE_GRACE_TICKS}; the counter restarts whenever it
     * stops holding. Meanwhile the body loads chunks like any player.
     */
    private void applyIdlePolicy() {
        for (BotPlayer body : all()) {
            if (body.hasDisconnected() || body.isDeadOrDying()) continue;
            if (body.brain().wantsToStayLoaded() || ownerNear(body)) {
                idleTicks.remove(body.getUUID());
                continue;
            }
            int idle = idleTicks.merge(body.getUUID(), BodyConstants.IDLE_CHECK_INTERVAL_TICKS, Integer::sum);
            if (idle >= BodyConstants.IDLE_GRACE_TICKS) despawn(body, "idle: no goal and its owner is not nearby");
        }
    }

    private boolean ownerNear(BotPlayer body) {
        UUID owner = body.ownerId();
        if (owner == null) return false;
        ServerPlayer player = server.getPlayerList().getPlayer(owner);
        if (player == null || player.level() != body.level()) return false;
        double limit = BodyConstants.OWNER_NEAR_BLOCKS;
        return player.distanceToSqr(body) <= limit * limit;
    }

    private void refreshRecords() {
        for (BotPlayer body : all()) {
            if (body.hasDisconnected() || body.isDeadOrDying()) continue;
            records().put(entryFor(body, body.brain().wantsToStayLoaded()));
        }
    }

    /** The overworld, for callers that have no level of their own (the console). */
    public ServerLevel overworld() {
        return server.overworld();
    }
}
