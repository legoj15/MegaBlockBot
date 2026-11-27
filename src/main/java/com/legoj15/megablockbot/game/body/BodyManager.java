package com.legoj15.megablockbot.game.body;

import com.legoj15.megablockbot.MegaBlockBot;
import com.mojang.authlib.GameProfile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
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
import net.minecraft.server.players.GameProfileCache;
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
    // Bodies that died (or won the game) and wait for their respawn at the end of the tick, by UUID because the
    // instance is replaced by the respawn; the value is how many attempts have failed so far.
    private final Map<UUID, Integer> pendingRespawns = new LinkedHashMap<>();
    // Where names the server knows are looked up: its own profile cache, unless a test supplies one.
    private Supplier<GameProfileCache> profileCache;
    // Questions about the server that no GameTest can make come out the other way: it is hardcore (the world's flag
    // cannot be set from a test) and it is in its final stop (a GameTest runs inside the server it would have to stop).
    private BooleanSupplier hardcore;
    private BooleanSupplier serverStopped;
    // Bodies whose respawn a test makes fail without a throw (vanilla gives no way to), by UUID.
    private final Set<UUID> respawnsFailing = new HashSet<>();
    // Bodies whose next so many restores a test makes throw after the copy (the halfway of a failed respawn: the
    // replacement is built, restoreFrom has run, the listing has not), by UUID, with what is left of the count.
    private final Map<UUID, Integer> restoresFailing = new HashMap<>();
    @Nullable
    private BodyRecords records;
    private long ticks;

    private BodyManager(MinecraftServer server) {
        this.server = server;
        this.profileCache = server::getProfileCache;
        this.hardcore = server::isHardcore;
        this.serverStopped = server::isStopped;
    }

    public static BodyManager of(MinecraftServer server) {
        return INSTANCES.computeIfAbsent(server, BodyManager::new);
    }

    /** The server's manager if it has one, without making one: for callers that may run after the server stopped. */
    @Nullable
    static BodyManager existing(MinecraftServer server) {
        return INSTANCES.get(server);
    }

    /**
     * The server has stopped, whichever way it ended: drops its manager, so that the map does not keep the server, its
     * levels and its bodies for as long as the JVM lives. The stopping hook is not where this belongs, since a crash
     * skips it; this one runs from the {@code finally} that every exit goes through, after the stop itself, which is
     * also why nothing that needs the server alive (the bodies' logout) is done here.
     */
    static void onServerStopped(MinecraftServer server) {
        if (INSTANCES.remove(server) != null) MegaBlockBot.LOGGER.info("Body manager of the stopped server dropped");
    }

    /** The UUID of the body called {@code name}: offline-style and never a real account's. */
    public static UUID uuidFor(String name) {
        return UUID.nameUUIDFromBytes(("megablockbot:" + name).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Whether {@code source} may command a body owned by {@code owner} (null: no owner). The owner may, as a player
     * (any other kind of entity can be given any UUID, the owner's included); so may a permission-level-2 source, which
     * covers ops and command blocks, and the console, which runs at level 4 ({@code MinecraftServer.createCommandSourceStack}).
     */
    public static boolean mayCommand(@Nullable UUID owner, CommandSourceStack source) {
        if (source.hasPermission(BodyConstants.PRIVILEGED_PERMISSION_LEVEL)) return true;
        ServerPlayer player = source.getPlayer();
        return owner != null && player != null && owner.equals(player.getUUID());
    }

    /**
     * Whether {@code source} may bring the body called {@code name} into being; throws the reason when not. A source at
     * permission level 2 or above (ops, command blocks, the console) is never held back. Below that: only a player
     * can own a body, may own at most {@link BodyConstants#MAX_BODIES_PER_OWNER} of them (spawned or not: a body does
     * not stop being theirs by being logged out), and may not give one the name of a player the server's profile cache
     * knows, since that player is just offline. A body that already exists is not new, so it is not held to the
     * owner limit again, but its name is checked all the same: a player of its name may have joined since, and the
     * body gave way to them ({@link #onPlayerJoined}), which only holds while it is not brought back beside them.
     */
    public void checkMaySpawn(CommandSourceStack source, String name) throws BodyException {
        if (source.hasPermission(BodyConstants.PRIVILEGED_PERMISSION_LEVEL)) return;
        if (records().findByName(name) == null) {
            ServerPlayer caller = source.getPlayer();
            if (caller == null) throw new BodyException("only a player or an operator may spawn a body");
            int owned = 0;
            for (BodyRecords.Entry entry : records().all()) {
                // A body that died in hardcore is gone for good, so it no longer takes a slot.
                if (!entry.dead() && caller.getUUID().equals(entry.ownerUuid())) owned++;
            }
            if (owned >= BodyConstants.MAX_BODIES_PER_OWNER) {
                throw new BodyException("you already own " + owned + " bodies, the most a player without operator permission may have");
            }
        }
        if (isKnownPlayerName(name)) {
            throw new BodyException(name + " is the name of a player this server knows: a body may not take it");
        }
    }

    /**
     * True when the profile cache holds a profile of this name that is not a body's own. Asked of the cache's
     * contents directly: every public lookup of a name it does not hold goes to Mojang's service (and in offline mode
     * makes a profile up). Spawning a body puts it in the cache too, under the body's own UUID; that is not a player.
     */
    private boolean isKnownPlayerName(String name) {
        return knownPlayerProfile(name) != null;
    }

    /**
     * The profile of the player the cache holds under {@code name}, or null when it holds none or holds the body's
     * own. Asked of the cache's contents for the reason {@link #isKnownPlayerName} gives; {@code spawn} keeps this to
     * put the name back the way it found it.
     */
    @Nullable
    private GameProfile knownPlayerProfile(String name) {
        GameProfileCache cache = profileCache.get();
        if (cache == null) return null;
        GameProfileCache.GameProfileInfo info = cache.profilesByName.get(name.toLowerCase(Locale.ROOT));
        return info != null && !info.getProfile().getId().equals(uuidFor(name)) ? info.getProfile() : null;
    }

    /**
     * Where {@link #checkMaySpawn} finds the names the server knows; null restores the server's own profile cache. A
     * test hook: the GameTest server has no profile cache, so a test supplies one.
     */
    public void useProfileCache(@Nullable Supplier<GameProfileCache> cache) {
        this.profileCache = cache != null ? cache : server::getProfileCache;
    }

    /**
     * Where the manager reads whether the world is hardcore; null restores the server's own answer. A test hook: the
     * flag is the world's and a GameTest world is not hardcore. Set it only inside one synchronous step of a test: the
     * manager is the server's, so every body on it sees the answer meanwhile.
     */
    public void useHardcore(@Nullable BooleanSupplier hardcore) {
        this.hardcore = hardcore != null ? hardcore : server::isHardcore;
    }

    /**
     * Where the manager reads whether the server is in its final stop (see {@link #keepLoggedInAtShutdown}); null
     * restores the server's own answer. A test hook, used like {@link #useHardcore}.
     */
    public void useServerStopped(@Nullable BooleanSupplier stopped) {
        this.serverStopped = stopped != null ? stopped : server::isStopped;
    }

    /**
     * Makes every respawn attempt of the body with this UUID fail without a throw, or stops doing so. A test hook:
     * vanilla's respawn does not fail on demand, and the give-up path ({@link BodyConstants#MAX_RESPAWN_FAILURES}) needs
     * one that does. Per body, because bodies of other tests die and respawn beside it.
     */
    public void failRespawnsOf(UUID id, boolean fail) {
        if (fail) respawnsFailing.add(id);
        else respawnsFailing.remove(id);
    }

    /**
     * Makes the next {@code times} restores of the body with this UUID throw after the brain has been copied onto
     * the replacement, which is where a throw from vanilla or a mod's event handler leaves a respawn halfway; the
     * manager then retries it and finally gives up on it. A test hook in the style of {@link #failRespawnsOf}; a
     * {@code times} of zero stops it.
     */
    public void failRestoresOf(UUID id, int times) {
        if (times > 0) restoresFailing.put(id, times);
        else restoresFailing.remove(id);
    }

    /** At the end of every restore between two bodies of one id; throws while a test has armed {@link #failRestoresOf}. */
    void afterRestoreCopy(BotPlayer previous) {
        Integer armed = restoresFailing.get(previous.getUUID());
        if (armed == null) return;
        if (armed <= 1) restoresFailing.remove(previous.getUUID());
        else restoresFailing.put(previous.getUUID(), armed - 1);
        throw new IllegalStateException("test hook: the restore of body " + previous.getGameProfile().getName()
            + " fails halfway (after the copy, before the listing)");
    }

    /** What the body record says about one body, for tests and for whatever reports it. */
    public record RecordSummary(boolean stayLoaded, boolean dead) {}

    /** The record of the body called {@code name} (ignoring case), or null when the record has none. */
    @Nullable
    public RecordSummary recordSummary(String name) {
        BodyRecords.Entry entry = records().findByName(name);
        return entry == null ? null : new RecordSummary(entry.stayLoaded(), entry.dead());
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
        if (known != null && known.dead()) {
            throw new BodyException(known.name() + " died in this hardcore world and cannot be spawned again");
        }

        UUID id = uuidFor(name);
        // The name may be a real player's (an operator is not held back from spawning a body under it), and
        // placeNewPlayer is about to put the body's own profile into the cache under that name; the player's is put
        // back once the body is placed, below, so the name keeps meaning them.
        GameProfile playersProfile = knownPlayerProfile(name);
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
        // The body is placed, so the cache now holds its profile under the name; the player's goes back over it. The
        // supplier is asked for again rather than the cache held from above, so the write lands in the cache as it
        // stands now that the body is placed (the supplier answers for the server's cache, or a test's stand-in).
        if (playersProfile != null) profileCache.get().add(playersProfile);

        idleTicks.remove(id);
        records().put(entryFor(body, body.brain().wantsToStayLoaded(), false));
        MegaBlockBot.LOGGER.info("Spawned body {} at {} in {} (owner: {}, {})", name, body.position(),
            body.level().dimension().location(), body.ownerName() != null ? body.ownerName() : "none",
            hasSavedData ? "from saved data" : "first spawn");
        if (body.isDeadOrDying()) {
            // Saved between its death and its respawn (the server stopped or crashed then). Its client does not exist to
            // press the respawn button, so without this it would stay logged in, dead, for ever.
            MegaBlockBot.LOGGER.warn("Body {} was saved dead; respawning it", name);
            queueRespawn(body);
        }
        return body;
    }

    /** Logs the body out through the normal player-removal path, so vanilla saves it. It will not respawn by itself. */
    public void despawn(BotPlayer body) {
        despawn(body, "despawned");
    }

    private void despawn(BotPlayer body, String why) {
        logOut(body, why, false, false);
    }

    /**
     * Logs the body out and records it as not wanting to stay loaded: whoever despawns a body does not want it back
     * at the next server start, whatever its goal says, unless {@code keepsGoal}: the server's own decision to take a
     * body out (its respawn kept failing) is not its owner's, and a body with a goal then still comes back with the
     * server. What it wanted is read from the brain and from the record, so a want the brain has since dropped is
     * not lost (the brain itself is still the body's even after a respawn that failed halfway; it is copied, not
     * taken). {@code died} marks the record as that of a body that
     * died in a hardcore world, which is never spawned again; a body found dead in one is marked whoever asked, since
     * a despawn in the tick it died in comes before the end-of-tick respawn that would have retired it.
     */
    private void logOut(BotPlayer body, String why, boolean died, boolean keepsGoal) {
        if (body.hasDisconnected()) return;
        boolean stay = false;
        if (keepsGoal) {
            BodyRecords.Entry before = records().get(body.getUUID());
            stay = body.brain().wantsToStayLoaded() || (before != null && before.stayLoaded());
        }
        boolean dead = died || diedInHardcore(body);
        records().put(entryFor(body, stay, dead));
        idleTicks.remove(body.getUUID());
        pendingRespawns.remove(body.getUUID());
        MegaBlockBot.LOGGER.info("Despawning body {} ({}{})", body.getGameProfile().getName(), why,
            dead && !died ? "; it died in a hardcore world" : "");
        body.logOut(Component.literal(why));
    }

    /** True for a body that is dead in a hardcore world: vanilla's respawn would make it a spectator, so it is never brought back. */
    private boolean diedInHardcore(BotPlayer body) {
        return hardcore.getAsBoolean() && !body.wonGame && body.getHealth() <= 0.0F;
    }

    /**
     * Removes everything saved for an offline body: its player data, stats, advancements and its record (which also
     * frees the name of a body that died in hardcore). For tests and for a future "delete" command; refuses while the
     * body is logged in.
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

    private static BodyRecords.Entry entryFor(BotPlayer body, boolean stayLoaded, boolean died) {
        return new BodyRecords.Entry(body.getUUID(), body.getGameProfile().getName(), body.ownerId(), body.ownerName(),
            body.level().dimension().location().toString(), body.getX(), body.getY(), body.getZ(), stayLoaded, died);
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
        pendingRespawns.putIfAbsent(body.getUUID(), 0);
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

    /**
     * Server stopping. First the record is brought up to date while the bodies are still logged in (what each wants
     * to stay loaded for is read off the bodies, so this comes before they leave). Then every body is logged out through
     * the normal removal path, which is also what saves it ({@code PlayerList.remove} saves the player).
     *
     * <p>Vanilla's {@code PlayerList.removeAll}, which follows the save of all players, walks the player list by index
     * and disconnects each entry. A body's disconnect takes it out of that list at once, so the entry after it would be
     * skipped and a real player after a body would never get its disconnect. With the bodies gone before it runs,
     * {@code removeAll} meets real players only.
     */
    void onServerStopping() {
        refreshRecords();
        for (BotPlayer body : all()) {
            try {
                body.logOut(Component.translatable("multiplayer.disconnect.server_shutdown"));
            } catch (RuntimeException e) {
                // One body that will not log out must not keep the others from being saved.
                MegaBlockBot.LOGGER.error("Body {} could not be logged out at server stop", body.getGameProfile().getName(), e);
            }
        }
    }

    /**
     * Keeps a body listed through the server's final stop instead of carrying out its disconnect: returns true when
     * the disconnect is not to be run. That is how a crash reaches the bodies: the stopping hook above never ran, so
     * {@code PlayerList.removeAll} meets them, and each disconnect that removed its body at once made the walk jump
     * over the entry after it, a real player's perhaps. In its stop the server has already saved every player
     * ({@code saveAll} comes first), and the bodies go with the process like a real player whose channel has not
     * closed yet, still listed. What a logout would have brought up to date in the record is done here instead, since
     * the level save that follows writes the file. {@code isStopped} is set before {@code stopServer} on every path;
     * on a normal stop the hook above has already taken the bodies out by then, so this meets none.
     */
    boolean keepLoggedInAtShutdown(BotPlayer body) {
        if (!serverStopped.getAsBoolean()) return false;
        try {
            if (!body.isDeadOrDying()) {
                // As in logOut: the record may know a want the brain has since dropped, and a brain that throws here
                // (the crash may be its own doing) must not cost the body its flag.
                BodyRecords.Entry before = records().get(body.getUUID());
                boolean stay = body.brain().wantsToStayLoaded() || (before != null && before.stayLoaded());
                records().put(entryFor(body, stay, false));
            }
        } catch (RuntimeException e) {
            // A brain that throws here is what may have crashed the server; the record keeps its last entry.
            MegaBlockBot.LOGGER.error("Record of body {} could not be brought up to date at server stop", body.getGameProfile().getName(), e);
        }
        MegaBlockBot.LOGGER.info("Body {} stays listed at the server's final stop; it was saved with the players", body.getGameProfile().getName());
        return true;
    }

    /**
     * A player finished joining. A real player takes the name of a body that has it: vanilla's duplicate-login rule
     * (the newest login wins, the older session of the same profile is disconnected) compares UUIDs, and a body's is its
     * own, so it would never meet the body. The body gives way instead: logged out through the normal path, which saves
     * it and keeps its record, and its owner is told why when they are on. Whatever goes wrong here must not fail the
     * joining player's login, so nothing is thrown.
     */
    void onPlayerJoined(ServerPlayer player) {
        if (player instanceof BotPlayer) return;
        try {
            String name = player.getGameProfile().getName();
            BotPlayer body = get(name);
            if (body == null) return;
            UUID ownerId = body.ownerId();
            String bodyName = body.getGameProfile().getName();
            logOut(body, "a player named " + name + " joined", false, false);
            MegaBlockBot.LOGGER.warn("Body {} gave way to the player {} who joined under its name", bodyName, name);
            ServerPlayer owner = ownerId == null ? null : server.getPlayerList().getPlayer(ownerId);
            if (owner != null && !(owner instanceof BotPlayer)) {
                owner.sendSystemMessage(Component.literal("Your body " + bodyName + " was despawned: a player named " + name
                    + " joined, and a player keeps their name. The body is kept, but you cannot spawn it again while this server knows that player."));
            }
        } catch (RuntimeException e) {
            MegaBlockBot.LOGGER.error("A body that shares the name of the joining player {} could not give way", player.getGameProfile().getName(), e);
        }
    }

    /** Where the loader's login hook lands: the manager of the player's server, if it has one (a server without one has no bodies). */
    static void playerJoined(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        BodyManager manager = server == null ? null : INSTANCES.get(server);
        if (manager != null) manager.onPlayerJoined(player);
    }

    /** End of every server tick. */
    void tick() {
        ticks++;
        processRespawns();
        if (ticks % BodyConstants.IDLE_CHECK_INTERVAL_TICKS == 0) applyIdlePolicy();
        if (ticks % BodyConstants.RECORD_REFRESH_TICKS == 0) refreshRecords();
    }

    /**
     * Respawns the bodies waiting for it, one at a time: an entry leaves the queue only once its respawn has happened
     * (or turned out not to be needed), so one that throws costs neither itself nor the entries after it. A failing
     * one is tried again next tick, and after {@link BodyConstants#MAX_RESPAWN_FAILURES} failures its body is despawned.
     */
    private void processRespawns() {
        // A snapshot: a respawn changes the player list, and a body given up on is removed from the queue.
        for (UUID id : new ArrayList<>(pendingRespawns.keySet())) {
            BotPlayer body = get(id);
            if (body == null) {
                pendingRespawns.remove(id); // logged out some other way meanwhile
                continue;
            }
            RuntimeException failure = null;
            boolean done = false;
            try {
                done = respawnOrRetire(body);
            } catch (RuntimeException e) {
                failure = e;
            }
            if (done) {
                pendingRespawns.remove(id);
                BotPlayer replacement = get(id);
                if (replacement != null && replacement != body) {
                    // The respawn happened: another instance stands under the id. restoreFrom only copied the brain
                    // onto the replacement, so the hand-over ends here: the body that died lets go of it. While
                    // both held it, nothing ticked or saved the one brain twice: the copy and this are one
                    // synchronous step of this tick, and after an attempt that threw in between, the body that died
                    // is dead (its own tick and the record refresh skip a dead body) while the replacement never
                    // made it into the player list, so nothing ticks or logs that one out either.
                    body.setBrain(BotBrain.NONE);
                }
                if (replacement != null && !replacement.hasDisconnected()) {
                    records().put(entryFor(replacement, replacement.brain().wantsToStayLoaded(), false));
                }
                continue;
            }
            int failures = pendingRespawns.merge(id, 1, Integer::sum);
            String name = body.getGameProfile().getName();
            if (failures >= BodyConstants.MAX_RESPAWN_FAILURES) {
                MegaBlockBot.LOGGER.error("Body {} could not be respawned {} times; despawning it", name, failures, failure);
                // The server's decision, not the owner's: a body with a goal still comes back with the server.
                logOut(body, "its respawn kept failing", false, true);
            } else {
                MegaBlockBot.LOGGER.warn("Respawn of body {} failed (attempt {} of {}); trying again next tick", name, failures,
                    BodyConstants.MAX_RESPAWN_FAILURES, failure);
            }
        }
    }

    /**
     * Brings a waiting body back, except in a hardcore world: vanilla's respawn there turns the player into a
     * spectator, and a spectator body is of no use, so a body that dies in one is taken out of the world and its record
     * says it died. Returns whether nothing is left to do for this body.
     */
    private boolean respawnOrRetire(BotPlayer body) {
        if (!body.isAwaitingRespawn()) return true;
        if (diedInHardcore(body)) {
            logOut(body, "died in a hardcore world", true, false);
            return true;
        }
        if (respawnsFailing.contains(body.getUUID())) return false;
        return body.performRespawn();
    }

    /**
     * Design 3.1: chunks stay loaded only while a goal needs them. A body whose brain does not want to stay loaded
     * and whose owner is not around (online, same dimension, within {@link BodyConstants#OWNER_NEAR_BLOCKS}) is
     * despawned once that has held for {@link BodyConstants#IDLE_GRACE_TICKS}; the counter restarts whenever it
     * stops holding. A body with no owner (spawned from the console) has no owner to be near, so it gets the longer
     * {@link BodyConstants#OWNERLESS_IDLE_GRACE_TICKS}. Meanwhile the body loads chunks like any player.
     */
    private void applyIdlePolicy() {
        for (BotPlayer body : all()) applyIdlePolicy(body, BodyConstants.IDLE_CHECK_INTERVAL_TICKS);
    }

    /**
     * The idle policy for one body, as if {@code elapsedTicks} had passed since its last evaluation. The periodic check
     * calls it with the check interval; a test calls it with a long one to cross the grace period without waiting for
     * it, and for one body only so that it cannot despawn the bodies of tests running beside it.
     */
    public void applyIdlePolicy(BotPlayer body, int elapsedTicks) {
        if (body.hasDisconnected() || body.isDeadOrDying()) return;
        if (body.brain().wantsToStayLoaded() || ownerNear(body)) {
            idleTicks.remove(body.getUUID());
            return;
        }
        int idle = idleTicks.merge(body.getUUID(), elapsedTicks, Integer::sum);
        int grace = body.ownerId() == null ? BodyConstants.OWNERLESS_IDLE_GRACE_TICKS : BodyConstants.IDLE_GRACE_TICKS;
        if (idle >= grace) despawn(body, "idle: no goal and its owner is not nearby");
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
            records().put(entryFor(body, body.brain().wantsToStayLoaded(), false));
        }
    }

    /** The overworld, for callers that have no level of their own (the console). */
    public ServerLevel overworld() {
        return server.overworld();
    }
}
