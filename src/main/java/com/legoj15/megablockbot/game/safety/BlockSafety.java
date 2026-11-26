package com.legoj15.megablockbot.game.safety;

import com.legoj15.megablockbot.MegaBlockBot;
import com.legoj15.megablockbot.game.body.BodyManager;
import com.legoj15.megablockbot.game.body.BotPlayer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Clearable;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/**
 * The one place every task and the navigation engine ask "may this body break that block / use that container"
 * (design 3.5). On in every profile; nothing turns it off. The rules, in the order {@link #mayBreak} applies them:
 *
 * <ol>
 *   <li>Protected radius ({@link SafetyConstants#PROTECTED_RADIUS}): around the owner's respawn point, and around the
 *       overworld's world spawn. Nothing passes it, not the bot's own blocks and not an {@link Allowance}.</li>
 *   <li>Claims: any registered {@link ClaimProvider} says yes. Same: nothing passes it.</li>
 *   <li>A block this body placed ({@link #noteBotPlaced}, or seen placing): allowed.</li>
 *   <li>Beds and villager workstations ({@link BlockKinds}) it did not place: denied. An allowance does not lift this.</li>
 *   <li>Containers it did not place, and blocks a player or another bot placed ({@link PlacedBlocks}): denied, unless an
 *       open {@link Allowance} of this body covers the position. These are the only two rules an allowance lifts.</li>
 *   <li>Anything else is terrain: allowed.</li>
 * </ol>
 *
 * <p><b>Threads.</b> {@link #mayBreak} may be called from any thread, so a path search can cost a break. Off the server
 * thread it reads only: the level's dimension; the block state it was handed; an immutable {@link Zones} snapshot the
 * server thread republishes every {@link SafetyConstants#ZONE_REFRESH_TICKS} ticks (the owner's bed is player and level
 * state, which only the server thread may read); the {@link PlacedBlocks} sets, under their read lock; the immutable
 * {@link BlockKinds} table; and the open allowances and registered claim providers, which are copy-on-write arrays. Its
 * allowed answer allocates nothing. When it cannot be sure it refuses: a body whose owner has no published snapshot
 * yet is refused with the protected-radius rule, and so is a position on a level whose placed-block record the server
 * thread has not loaded yet (a dimension made at runtime; the server thread loads it within a tick), with the
 * player-placed rule. The caller's {@code covers} predicate and every claim provider run on the asking thread, so they
 * must be thread-safe themselves.
 *
 * <p>On the server thread it also reads the level: the zones are recomputed fresh, and the block the level holds at
 * the position replaces the {@code state} the caller passed, because the level is the truth and a stale snapshot is not
 * (a block entity of a block the type table could not classify is looked at too). A placed-block record found to
 * describe a block the level no longer holds is pruned then, and only then.
 *
 * <p>{@link #mayUseContainer}, {@link #noteBotPlaced} and {@link #placedBlocks} read or write the level and are for the
 * server thread, and throw anywhere else.
 *
 * <p><b>Two-block things.</b> The loader reports a placement at one position, so the record holds one half. A door, a
 * tall plant and a bed answer for the half nobody recorded through its partner. A double chest is two block entities,
 * each recorded when its own half was placed: breaking is per half, but the halves share one inventory, so using either
 * half needs both to be the bot's own (or allowed).
 *
 * <p><b>What it cannot see.</b> See {@code docs/block-safety.md}: placements and breaks come from the loader's block
 * events, which report a player's own actions only.
 */
public final class BlockSafety {
    private static final Map<MinecraftServer, BlockSafety> INSTANCES = new ConcurrentHashMap<>();

    // Copy-on-write, so a path search iterates an array and allocates no iterator.
    private static volatile ClaimProvider[] claimProviders = new ClaimProvider[0];
    private static final Set<Class<?>> REPORTED_CLAIM_FAILURES = ConcurrentHashMap.newKeySet();

    /** Stands for "a body" when the debug command asks about an ownerless body: it owns no placed block. */
    private static final UUID ANONYMOUS_BODY = new UUID(0L, 0L);

    private static final Verdict CLAIM = Verdict.deny(SafetyConstants.RULE_CLAIM, "inside a region claimed by someone else");
    private static final Verdict PLAYER_PLACED = Verdict.deny(SafetyConstants.RULE_PLAYER_PLACED, "placed by a player");
    private static final Verdict OTHER_BOT_PLACED = Verdict.deny(SafetyConstants.RULE_PLAYER_PLACED, "placed by another bot");
    private static final Verdict CONTAINER = Verdict.deny(SafetyConstants.RULE_CONTAINER, "a container this bot did not place");
    private static final Verdict BED = Verdict.deny(SafetyConstants.RULE_BED, "a bed this bot did not place");
    private static final Verdict WORKSTATION = Verdict.deny(SafetyConstants.RULE_WORKSTATION, "a villager workstation this bot did not place");
    private static final Verdict ZONES_UNKNOWN = Verdict.deny(SafetyConstants.RULE_PROTECTED_RADIUS,
        "the protected zones of this body's owner are not published yet");
    private static final Verdict RECORDS_UNKNOWN = Verdict.deny(SafetyConstants.RULE_PLAYER_PLACED,
        "the placed-block record of this level is not loaded yet");
    private static final Verdict NOT_LOADED = Verdict.deny(SafetyConstants.RULE_CONTAINER, "the position is not loaded");

    private final MinecraftServer server;
    private final OwnerSpawns ownerSpawns;
    // Keyed by the level instance, not its dimension: a dimension removed and made again at runtime is a new level with
    // its own data storage, and a record cached from the old one would never be saved.
    private final Map<ServerLevel, PlacedBlocks> placedByLevel = new ConcurrentHashMap<>();
    private final Map<UUID, Zones> zonesByOwner = new ConcurrentHashMap<>();
    private volatile Zones ownerlessZones;
    private final Object grantLock = new Object();
    private volatile Grant[] grants = new Grant[0];
    private long ticks;

    private BlockSafety(MinecraftServer server) {
        this.server = server;
        this.ownerSpawns = new OwnerSpawns(server);
    }

    public static BlockSafety of(MinecraftServer server) {
        return INSTANCES.computeIfAbsent(server, BlockSafety::new);
    }

    /** Server stopping: the next server in this JVM starts from nothing. The level data is saved by vanilla. */
    static void discard(MinecraftServer server) {
        INSTANCES.remove(server);
    }

    /**
     * Registers a claims adapter, for the rest of this JVM. Any thread. A registered provider can only make the answer
     * stricter.
     */
    public static synchronized void registerClaimProvider(ClaimProvider provider) {
        ClaimProvider[] next = Arrays.copyOf(claimProviders, claimProviders.length + 1);
        next[next.length - 1] = provider;
        claimProviders = next;
    }

    public static synchronized void unregisterClaimProvider(ClaimProvider provider) {
        claimProviders = Arrays.stream(claimProviders).filter(p -> p != provider).toArray(ClaimProvider[]::new);
    }

    // ---------------------------------------------------------------------------------------------------------
    // The questions
    // ---------------------------------------------------------------------------------------------------------

    /**
     * May this body break the block now at {@code pos}? {@code state} is the caller's idea of that block. Off the server
     * thread it is all there is to go on; on the server thread the level's own block at {@code pos} is used instead.
     */
    public Verdict mayBreak(BotPlayer body, ServerLevel level, BlockPos pos, BlockState state) {
        return evaluateBreak(body.getUUID(), body.ownerId(), level, pos, state);
    }

    /** May this body take items out of, or put items into, the container at {@code pos}? Server thread. */
    public Verdict mayUseContainer(BotPlayer body, ServerLevel level, BlockPos pos) {
        requireServerThread("mayUseContainer");
        return evaluateUse(body.getUUID(), body.ownerId(), level, pos);
    }

    /** The bot placed this block itself (a crafting table, a bridge block): it may break it again. Server thread. */
    public void noteBotPlaced(BotPlayer body, ServerLevel level, BlockPos pos) {
        requireServerThread("noteBotPlaced");
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) return; // nothing there to own
        placedData(level).recordBot(pos.asLong(), state.getBlock(), body.getUUID());
    }

    /**
     * Opens an allowance for one goal: positions {@code covers} says yes to pass the player-placed and container rules
     * for this body, until the allowance is closed. Never the protected-radius, claim, bed or workstation rules.
     * {@code reason} is for logs.
     *
     * <p>A goal closes its allowance when it ends ({@link #closeAll} is the one call for that). One that is not closed
     * ends anyway when its body dies, despawns or is replaced by a respawn: it belongs to that body instance.
     */
    public Allowance allow(BotPlayer body, String reason, Predicate<BlockPos> covers) {
        Grant grant = new Grant(body, reason, covers);
        synchronized (grantLock) {
            Grant[] next = Arrays.copyOf(grants, grants.length + 1);
            next[next.length - 1] = grant;
            grants = next;
        }
        MegaBlockBot.LOGGER.debug("Block safety allowance opened for {}: {}", body.getGameProfile().getName(), reason);
        return grant;
    }

    /**
     * Ends every allowance opened for this body (by identity, so one opened for an earlier instance of it counts):
     * what the brain calls when a goal ends, so a goal that forgot to close its own cannot leave one behind. Any
     * thread; a {@link #mayBreak} running at the same moment sees the allowance or not, and never fails.
     */
    public void closeAll(BotPlayer body) {
        UUID id = body.getUUID();
        synchronized (grantLock) {
            List<Grant> keep = new ArrayList<>(grants.length);
            for (Grant grant : grants) {
                if (grant.bodyId.equals(id)) grant.closed = true;
                else keep.add(grant);
            }
            grants = keep.toArray(new Grant[0]);
        }
    }

    /** The placed-block record of a level. Server thread. */
    public PlacedBlocks placedBlocks(ServerLevel level) {
        requireServerThread("placedBlocks");
        return placedData(level);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Evaluation (the debug command asks the same questions of an ownerless body through these)
    // ---------------------------------------------------------------------------------------------------------

    Verdict evaluateBreak(UUID body, @Nullable UUID owner, ServerLevel level, BlockPos pos, BlockState given) {
        boolean onServer = server.isSameThread();
        Zones zones = onServer ? publishZones(owner) : owner == null ? ownerlessZones : zonesByOwner.get(owner);
        if (zones == null) return ZONES_UNKNOWN;
        Verdict zone = zones.covering(level.dimension(), pos.getX(), pos.getZ());
        if (zone != null) return zone;
        if (claimed(level, pos, owner != null ? owner : body)) return CLAIM;

        PlacedBlocks placed = placedByLevel.get(level);
        if (placed == null) {
            // Off the server thread nothing can load it, and "no records" would read as "terrain": refuse instead.
            if (!onServer) return RECORDS_UNKNOWN;
            placed = placedData(level);
        }
        // On the server thread the level holds the truth about this position; the caller's state may be a stale snapshot.
        boolean fromLevel = onServer && level.isLoaded(pos);
        BlockState state = fromLevel ? level.getBlockState(pos) : given;

        int recorded = recordedAt(placed, pos, state, body, fromLevel);
        if (recorded == PlacedBlocks.BOT_SELF) return Verdict.ALLOWED;
        int kind = BlockKinds.of(state.getBlock());
        if (kind == BlockKinds.NONE && fromLevel) kind = kindOfBlockEntity(level, pos, state);
        // A bed and a job site are not what an allowance is for: nothing lifts them.
        if (kind == BlockKinds.BED || kind == BlockKinds.WORKSTATION) return verdictOf(kind);

        boolean placedByOther = recorded == PlacedBlocks.PLAYER || recorded == PlacedBlocks.BOT_OTHER;
        if (kind != BlockKinds.CONTAINER && !placedByOther) return Verdict.ALLOWED;
        if (granted(body, pos, onServer)) return Verdict.ALLOWED;
        if (kind == BlockKinds.CONTAINER) return CONTAINER;
        return recorded == PlacedBlocks.PLAYER ? PLAYER_PLACED : OTHER_BOT_PLACED;
    }

    /**
     * Using a container is not breaking it, so the protected radius does not apply (it would only stop a bot from
     * using its own chest); the claim rule does, and so does the rest. A double chest is one inventory, so both of its
     * halves are asked. Server thread.
     */
    Verdict evaluateUse(UUID body, @Nullable UUID owner, ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) return NOT_LOADED;
        BlockState state = level.getBlockState(pos);
        Verdict here = useAt(body, owner, level, pos, state);
        if (!here.allowed()) return here;
        if (state.hasProperty(ChestBlock.TYPE) && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            BlockPos other = pos.relative(ChestBlock.getConnectedDirection(state));
            if (!level.isLoaded(other)) return NOT_LOADED;
            return useAt(body, owner, level, other, level.getBlockState(other));
        }
        return here;
    }

    private Verdict useAt(UUID body, @Nullable UUID owner, ServerLevel level, BlockPos pos, BlockState state) {
        if (claimed(level, pos, owner != null ? owner : body)) return CLAIM;
        int kind = BlockKinds.of(state.getBlock());
        if (kind == BlockKinds.NONE) kind = kindOfBlockEntity(level, pos, state);
        if (kind == BlockKinds.NONE) return Verdict.ALLOWED;
        if (recordedAt(placedData(level), pos, state, body, true) == PlacedBlocks.BOT_SELF) return Verdict.ALLOWED;
        // Only the container rule is an allowance's to lift; a bed or a workstation is not a container to take from.
        if (kind == BlockKinds.CONTAINER && granted(body, pos, true)) return Verdict.ALLOWED;
        return verdictOf(kind);
    }

    /** The debug command's body: no owner, so only the world spawn zone applies, and no block is its own. */
    Verdict evaluateBreakAsOwnerless(ServerLevel level, BlockPos pos, BlockState state) {
        return evaluateBreak(ANONYMOUS_BODY, null, level, pos, state);
    }

    Verdict evaluateUseAsOwnerless(ServerLevel level, BlockPos pos) {
        return evaluateUse(ANONYMOUS_BODY, null, level, pos);
    }

    private static Verdict verdictOf(int kind) {
        return switch (kind) {
            case BlockKinds.BED -> BED;
            case BlockKinds.WORKSTATION -> WORKSTATION;
            default -> CONTAINER;
        };
    }

    /**
     * What the type table could not say, the real block entity can (a mod's block whose default state builds no block
     * entity, or one that probing could not build): anything that holds items is a container, so when in doubt the answer
     * is no, for breaking as for using. Server thread, loaded position; costs nothing for a block with no block entity.
     */
    private static int kindOfBlockEntity(ServerLevel level, BlockPos pos, BlockState state) {
        if (state.hasBlockEntity() && level.getBlockEntity(pos) instanceof Clearable) return BlockKinds.CONTAINER;
        return BlockKinds.NONE;
    }

    /**
     * What the record says about this position, for this body, given the block that is there. A stale record (the
     * position holds another block) reads as nothing; and when {@code state} is the level's own block (not a caller's
     * claim about it), the record is removed, because the server thread owns the data and the level has settled it.
     * A door, a tall plant or a bed is recorded at the half the player placed, so the other half asks about its partner.
     */
    private static int recordedAt(PlacedBlocks placed, BlockPos pos, BlockState state, UUID body, boolean fromLevel) {
        long key = pos.asLong();
        int result = placed.lookup(key, state.getBlock(), body);
        if (result == PlacedBlocks.STALE) {
            if (fromLevel) placed.forget(key);
            result = PlacedBlocks.NONE;
        }
        if (result == PlacedBlocks.NONE) result = partnerRecord(placed, pos, state, body);
        return result;
    }

    /** What the record says about the other half of a two-block thing (the same block there), or nothing. */
    private static int partnerRecord(PlacedBlocks placed, BlockPos pos, BlockState state, UUID body) {
        long partner;
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
            int dy = state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER ? 1 : -1;
            partner = BlockPos.asLong(pos.getX(), pos.getY() + dy, pos.getZ());
        } else if (state.hasProperty(BedBlock.PART) && state.hasProperty(BedBlock.FACING)) {
            // The head is in front of the foot, in the direction the bed faces.
            Direction facing = state.getValue(BedBlock.FACING);
            Direction toPartner = state.getValue(BedBlock.PART) == BedPart.FOOT ? facing : facing.getOpposite();
            partner = BlockPos.asLong(pos.getX() + toPartner.getStepX(), pos.getY(), pos.getZ() + toPartner.getStepZ());
        } else {
            return PlacedBlocks.NONE;
        }
        int result = placed.lookup(partner, state.getBlock(), body);
        return result == PlacedBlocks.STALE ? PlacedBlocks.NONE : result;
    }

    private static boolean claimed(ServerLevel level, BlockPos pos, UUID actingFor) {
        for (ClaimProvider provider : claimProviders) {
            try {
                if (provider.isClaimed(level, pos, actingFor)) return true;
            } catch (RuntimeException e) {
                // A broken adapter must not become a hole: refuse, and say so once per adapter class.
                if (REPORTED_CLAIM_FAILURES.add(provider.getClass())) {
                    MegaBlockBot.LOGGER.error("Claim provider {} threw; treating every position as claimed while it does", provider.getClass().getName(), e);
                }
                return true;
            }
        }
        return false;
    }

    /**
     * Whether an open allowance of this body covers {@code pos}. An allowance closed meanwhile (another thread) is
     * skipped or not depending on who got there first; either is a fair answer. On the server thread one whose body is
     * gone is skipped at once, without waiting for the end-of-tick sweep to close it.
     */
    private boolean granted(UUID body, BlockPos pos, boolean onServer) {
        for (Grant grant : grants) {
            if (grant.closed || !grant.bodyId.equals(body)) continue;
            if (onServer && !grant.live()) continue;
            try {
                if (grant.covers.test(pos)) return true;
            } catch (RuntimeException e) {
                // A predicate that fails covers nothing.
                MegaBlockBot.LOGGER.warn("Allowance '{}' threw while checking {}: {}", grant.reason, pos, e.toString());
            }
        }
        return false;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Zones (what only the server thread can read, published for the other threads)
    // ---------------------------------------------------------------------------------------------------------

    /** Server thread: reads the world spawn and the owner's respawn point, publishes the zones if they changed. */
    private Zones publishZones(@Nullable UUID owner) {
        BlockPos worldSpawn = server.overworld().getSharedSpawnPos();
        OwnerSpawns.Spawn respawn = owner == null ? null : ownerSpawns.of(owner);
        Zones previous = owner == null ? ownerlessZones : zonesByOwner.get(owner);
        if (previous != null && previous.matches(worldSpawn, respawn)) return previous;
        Zones zones = Zones.of(worldSpawn, respawn);
        if (owner == null) ownerlessZones = zones;
        else zonesByOwner.put(owner, zones);
        return zones;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Hooks (SafetyFeature wires them to the loader)
    // ---------------------------------------------------------------------------------------------------------

    /** Server started: load every level's placed-block data now, so other threads find it there. */
    void onServerStarted() {
        syncLevels();
        publishZones(null);
    }

    /** End of every server tick. */
    void tick() {
        syncLevels();
        sweepGrants();
        if (++ticks % SafetyConstants.ZONE_REFRESH_TICKS != 0) return;
        Set<UUID> owners = new HashSet<>();
        for (BotPlayer body : BodyManager.of(server).all()) {
            UUID owner = body.ownerId();
            if (owner != null && owners.add(owner)) publishZones(owner);
        }
        publishZones(null);
        zonesByOwner.keySet().retainAll(owners);
        ownerSpawns.retain(owners);
    }

    /**
     * Gives every level the server has now its placed-block record, and drops the records of levels it no longer has.
     * A dimension created after the server started has no record until this runs; until then, a question about it from
     * another thread is refused, and one from the server thread loads it on the spot.
     */
    private void syncLevels() {
        int count = 0;
        for (ServerLevel level : server.getAllLevels()) {
            count++;
            if (!placedByLevel.containsKey(level)) placedData(level);
        }
        if (count != placedByLevel.size()) {
            Set<ServerLevel> current = new HashSet<>();
            for (ServerLevel level : server.getAllLevels()) current.add(level);
            placedByLevel.keySet().retainAll(current);
        }
    }

    /** Closes the allowances whose body is gone, so a goal that never closed its own cannot leak one. */
    private void sweepGrants() {
        for (Grant grant : grants) {
            if (grant.closed || grant.live()) continue;
            MegaBlockBot.LOGGER.warn("Block safety allowance '{}' of {} was still open when its body died, left or was replaced; closing it",
                grant.reason, grant.body.getGameProfile().getName());
            grant.close();
        }
    }

    /**
     * The loader's placement hook ({@code SafetyFeature} wires it); public so a GameTest can drive it with a cause the
     * item-use path cannot produce. Only a player's placement counts: a bot's body is recorded as that bot's own, and
     * anything else (an enderman setting down the block it carried, a cause the loader does not name) put terrain back,
     * which a mining bot must still be able to dig. Delivered at the end of the tick, so the block is read from the
     * level now. Server thread.
     */
    public void blockPlaced(ServerLevel level, BlockPos pos, @Nullable Entity cause) {
        if (!(cause instanceof Player) || !level.isLoaded(pos)) return;
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) return; // undone in the same tick
        PlacedBlocks placed = placedData(level);
        if (cause instanceof BotPlayer bot) placed.recordBot(pos.asLong(), state.getBlock(), bot.getUUID());
        else placed.recordPlayer(pos.asLong(), state.getBlock());
    }

    /**
     * The loader's break hook ({@code SafetyFeature} wires it); public for the same reason as {@link #blockPlaced}. A
     * player broke a block. The loader reports it before the block goes and cannot report a break that then fails, so
     * the record is dropped only when the position no longer holds the block that was broken. Server thread.
     */
    public void blockBroken(ServerLevel level, BlockPos pos, BlockState broken) {
        if (level.isLoaded(pos) && level.getBlockState(pos).getBlock() == broken.getBlock()) return;
        placedData(level).forget(pos.asLong());
    }

    // ---------------------------------------------------------------------------------------------------------

    /** The record of a level, loaded now if it is not yet. Server thread. */
    private PlacedBlocks placedData(ServerLevel level) {
        PlacedBlocks placed = placedByLevel.get(level);
        if (placed == null) {
            placed = PlacedBlocks.of(level);
            placedByLevel.put(level, placed);
        }
        return placed;
    }

    private void requireServerThread(String what) {
        if (!server.isSameThread()) {
            throw new IllegalStateException("BlockSafety." + what + " reads or writes the level and must run on the server thread");
        }
    }

    private final class Grant implements Allowance {
        final BotPlayer body;
        final UUID bodyId;
        final String reason;
        final Predicate<BlockPos> covers;
        // Set under grantLock, read anywhere: a question that sees it set skips the allowance.
        volatile boolean closed;

        Grant(BotPlayer body, String reason, Predicate<BlockPos> covers) {
            this.body = body;
            this.bodyId = body.getUUID();
            this.reason = reason;
            this.covers = covers;
        }

        /** Still open, and its body is the live instance under its name: not dead, not logged out, not replaced. Server thread. */
        boolean live() {
            return !closed && !body.hasDisconnected() && !body.isRemoved() && !body.isDeadOrDying()
                && server.getPlayerList().getPlayer(bodyId) == body;
        }

        @Override
        public void close() {
            synchronized (grantLock) {
                closed = true;
                grants = Arrays.stream(grants).filter(g -> g != this).toArray(Grant[]::new);
            }
        }
    }
}
