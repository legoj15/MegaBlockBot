package com.legoj15.megablockbot.game.nav;

import com.legoj15.megablockbot.game.body.BodyInput;
import com.legoj15.megablockbot.game.body.BotPlayer;
import com.legoj15.megablockbot.game.build.Blueprint;
import com.legoj15.megablockbot.game.plan.BotMemory;
import com.legoj15.megablockbot.nav.NavRuntime;
import com.legoj15.megablockbot.nav.engine.Baritone;
import com.legoj15.megablockbot.nav.engine.api.IBaritone;
import com.legoj15.megablockbot.nav.engine.api.Settings;
import com.legoj15.megablockbot.nav.engine.api.cache.IWorldData;
import com.legoj15.megablockbot.nav.engine.api.pathing.calc.Avoidance;
import com.legoj15.megablockbot.nav.engine.api.pathing.calc.IPath;
import com.legoj15.megablockbot.nav.engine.api.pathing.goals.Goal;
import com.legoj15.megablockbot.nav.engine.api.pathing.goals.GoalBlock;
import com.legoj15.megablockbot.nav.engine.api.pathing.goals.GoalNear;
import com.legoj15.megablockbot.nav.engine.api.pathing.goals.GoalXZ;
import com.legoj15.megablockbot.nav.engine.api.pathing.goals.GoalYLevel;
import com.legoj15.megablockbot.nav.engine.api.selection.ISelectionManager;
import com.legoj15.megablockbot.nav.engine.api.schematic.AbstractSchematic;
import com.legoj15.megablockbot.nav.engine.api.schematic.IStaticSchematic;
import com.legoj15.megablockbot.nav.engine.api.utils.IInteractionController;
import com.legoj15.megablockbot.nav.engine.api.utils.IMineTargetPolicy;
import com.legoj15.megablockbot.nav.engine.behavior.PathingBehavior;
import com.legoj15.megablockbot.nav.engine.pathing.path.PathExecutor;
import com.legoj15.megablockbot.nav.engine.process.BuilderProcess;
import com.legoj15.megablockbot.nav.engine.process.MineProcess;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.Clearable;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * What tasks and reflexes call to move a body (design 2.2): one per body, plain commands, a state to poll. It owns
 * the navigation engine for its body and is the only main-mod class that drives it (the body and the {@code Body*}
 * adapters only implement the engine's entity interfaces); no engine type appears in its signatures.
 *
 * <p>Nothing drives the engine but {@link #tick}, which the body's brain calls once per body tick, before the body
 * applies its input. The engine moves the body only through that input (the body implements the engine's input
 * receiver), so every move is one a player could make.
 *
 * <p>The engine keys its state by entity UUID and drops it when the entity is removed, which happens on a respawn
 * (a new instance under the same UUID) and on a dimension change (the same instance, removed and re-added). The
 * navigator checks on every tick that its engine is still the one registered for the body it is handed; when not, a
 * running command fails and the next command builds a fresh engine for the body as it is now.
 *
 * <p>Server thread only, like the body.
 */
public final class BotNavigator {
    public enum State { IDLE, WORKING, DONE, FAILED }

    /** What a working command waits for, when it waits for anything: the body's air, or a second look at the world. */
    public enum Wait { NONE, FOR_AIR, TO_LOOK_AGAIN }

    /**
     * Why a command failed, for callers that must route on it (a task maps these to typed failures; the text from
     * {@link #failure()} is for logs only and may change).
     */
    public enum FailKind {
        /** The engine found no route to the goal. */
        NO_PATH,
        /** A mine command ended with the body short of its count: no block of the kind is known and reachable. */
        NOTHING_TO_MINE,
        /** A go-to made no progress toward its goal for {@link #STALL_TICKS} ticks. */
        STUCK,
        /** An explore command could not find a path toward any chunk it has not seen. */
        EXPLORE_FAILED,
        /** A follow command's entity is gone. */
        TARGET_GONE,
        /** The body was replaced, removed or moved to another dimension while the command ran. */
        BODY_LOST,
        /** A build ran out of a block: the builder paused with positions still to fill and none of their item held. */
        MISSING_BLOCKS,
        /** A build cannot go on for another reason (a block in the way it cannot break or reach). */
        BUILD_STUCK,
        /** The engine threw. */
        ENGINE_ERROR
    }

    /** Ticks a go-to may go without getting closer to its goal before it is failed as stuck. */
    static final int STALL_TICKS = 1200;
    /**
     * Ticks a mine command may head for one block without getting a block closer to it, or breaking anything, before
     * that block is set aside. Twenty seconds: about 85 blocks of walking, more than a detour around an obstacle costs,
     * and far longer than a break of anything the planner sends a body to.
     */
    public static final int TARGET_STALL_TICKS = 400;
    /**
     * Ticks the whole wait for air may take in one mine command, from its first tick to the air being full again: the
     * 300 air refill at 4 a tick with the eyes out, which is 75 ticks, plus room to get the head out first (a body
     * holding jump rises in water at about a block every ten ticks, and the place to breathe is at most the
     * {@link #SURFACE_RADIUS} search away). One deadline for the whole wait (see {@link #waitForAir}); past it the wet
     * targets are given up.
     */
    static final int AIR_WAIT_TICKS = 150;
    /**
     * Ticks the head may stay in the water while the wait for air brings it out. There is no walk whose failure would
     * say there is no way up (nothing moves the body but the wait's own input), so the time says it instead: rising
     * the {@link #SURFACE_RADIUS} blocks the search looks over takes about forty ticks, and this leaves room for a
     * body working its way along a roof.
     */
    static final int SURFACE_TICKS = 60;
    /**
     * How long a chunk the body has looked over stays out of an explore: ten minutes of game time. Long enough that a
     * search of many steps does not walk back over its own ground; short enough that a search for something else, or
     * for animals that have wandered in since, comes back to it.
     */
    private static final long SEEN_CHUNK_TICKS = 12_000;
    /**
     * Chunks each way around the body that count as looked over while it explores. One: the tasks that explore look
     * about 24 blocks around the body, and the body's chunk with its eight neighbours is at least 16 blocks each way.
     * The engine heads for the centre of the nearest chunk that is not marked, so this is also what makes an explore
     * move at all: with nothing marked it walks to the centre of the chunk it stands in and stays there (the engine
     * this was ported from had the marking done by a tracker outside it, which was not part of the port).
     */
    private static final int SEEN_CHUNK_RADIUS = 1;
    /** Ticks between a scan that found nothing and the second look (see {@link #lookAgain}). */
    static final int LOOK_AGAIN_TICKS = 10;
    /** How far up and around from the body the navigator looks for a place to get its head out of the water. */
    private static final int SURFACE_RADIUS = 4;
    /** Getting this much closer to a block, in blocks, counts as progress; less is the body swaying about. */
    private static final double CLOSER_BY = 1.0;
    /** Path cost per point of fall damage: about one second of walking per half heart, so a long fall is a last resort. */
    private static final double FALL_DAMAGE_PENALTY = 20.0;
    /**
     * What a cell inside a live danger zone costs, as a multiple of its usual cost (issue 29): high enough that a path
     * goes round a zone when there is a way round, and only a cost, never a wall, so a goal inside a zone that the
     * owner asked for by position is still walked to. Four makes crossing a zone of any width dearer than the way
     * round it, while a whole world of zones (which cannot happen: they lapse) would still be crossable.
     */
    private static final double DANGER_ZONE_COST = 4.0;
    /**
     * What the engine may bridge and pillar with (design 2.2's survival defaults): the stone and dirt a body in a cave
     * has to spare. A build command empties this for its own command, so nothing it does places a block the blueprint
     * does not name (see {@link #build}).
     */
    private static final List<Item> THROWAWAY = List.of(
        Items.DIRT, Items.COARSE_DIRT, Items.COBBLESTONE, Items.COBBLED_DEEPSLATE, Items.NETHERRACK,
        Items.ANDESITE, Items.DIORITE, Items.GRANITE, Items.TUFF, Items.BLACKSTONE, Items.BASALT);

    private static final Map<UUID, BotNavigator> NAVIGATORS = new HashMap<>();
    private static List<Block> avoidBreaking;

    private enum Kind { GOTO, MINE, FOLLOW, EXPLORE, BUILD }

    private BotPlayer body;
    @Nullable
    private Baritone engine;
    private State state = State.IDLE;
    @Nullable
    private String failure;
    @Nullable
    private FailKind failKind;
    /** The exception behind an engine-error failure: what a caller needs to fail its own task with the cause. */
    @Nullable
    private Throwable failureCause;
    private BreakFilter breakFilter = BreakFilter.ALLOW_ALL;

    @Nullable
    private Kind kind;
    @Nullable
    private Goal goal;
    private int mineTarget;
    private Set<Item> mineItems = Set.of();
    private List<Block> mineBlocks = List.of();
    /** Blocks the running mine command will not head for: the caller's set, read from search threads too. */
    private volatile Set<BlockPos> setAside = Set.of();
    private int targetStallTicks = TARGET_STALL_TICKS;
    private final Map<BlockPos, TargetWatch> watches = new HashMap<>();
    @Nullable
    private BlockPos focus;
    private int seenBroken;
    /** Game time the current wait for air began at (see {@link #waitForAir}); 0 while none is on. */
    private long airWaitStart;
    /** The engine gave up on exactly one block in this command and the command was started again once for it. */
    private boolean retriedGiveUp;
    /** How many times the engine, finding nothing, was asked again in this command, and the ticks waited for the next. */
    private int looksAgain;
    private int lookAgainTicks;
    /** Set by the target policy: blocks that are known but only taken under water are held back until the air is full. */
    private volatile boolean airWithheld;
    /** The blocks the policy is holding back for air: what a give-up on them sets aside. */
    private volatile List<BlockPos> withheldForAir = List.of();
    /**
     * The body's live danger zones ({@code BotMemory}, issue 29), as the engine prices them and as the target policy
     * withholds blocks for. A snapshot taken on the server thread, because the engine's search pool reads it.
     */
    private volatile List<BotMemory.DangerZone> dangerZones = List.of();
    /** The dimension the snapshot's zones are read against, as the running command's body is in. */
    private volatile String dimension = "";
    /** How many known blocks the last target choice held back for standing inside a danger zone (issue 29). */
    private volatile int zoneWithheld;
    /** Set by the target policy once it lets a block under water through, so that the air falling on the way does not stop it. */
    private volatile boolean wetCommitted;
    /** The blueprint the running build command places, and where its anchor is. */
    @Nullable
    private Blueprint blueprint;
    @Nullable
    private BlockPos buildAnchor;
    /** The box a running build may break in (the blueprint's own box); null when no build runs. Read from search threads. */
    private volatile Box buildBox;
    /** What the running command waits for, when the engine's own process has ended and the navigator bridges the gap. */
    private Wait waiting = Wait.NONE;
    private final IMineTargetPolicy targetPolicy = this::selectTargets;
    private double bestHeuristic;
    private int sinceProgress;
    private boolean inputDirty;
    /** Commands begun so far: advanced by every {@code goTo}, {@code mine}, {@code follow} and {@code explore}, never by a stop. */
    private long serial;
    /** Who the next command is for, as its giver said just before giving it ({@link #issuingFor}); gone when anyone else asks for this navigator. */
    @Nullable
    private Object claimant;
    /** Who the current command is for, when they said; null when nobody did, and after a stop. */
    @Nullable
    private Object issuer;
    /** Times the engine has been ticked for this body, whatever drove it; a test reads this to tell a double tick. */
    private long engineTicks;
    /** Chunks the body has looked over while exploring, by chunk key, with the game time it last did. */
    private final Map<Long, Long> seenChunks = new HashMap<>();
    /** The dimension {@link #seenChunks} is about: the keys mean nothing in another. */
    private String seenDimension = "";
    /** The chunk the body stood in when its surroundings were last marked; null before the first marking of an explore. */
    @Nullable
    private ChunkPos seenFrom;

    private BotNavigator(BotPlayer body) {
        this.body = body;
    }

    /** The navigator of the body with this one's UUID, created on first use; it follows the body across respawns. */
    public static BotNavigator of(BotPlayer body) {
        BotNavigator navigator = NAVIGATORS.computeIfAbsent(body.getUUID(), id -> new BotNavigator(body));
        navigator.follow(body);
        // A claim is good for the caller that made it and for nobody who comes after: see issuingFor.
        navigator.claimant = null;
        return navigator;
    }

    /** Forgets every navigator. Called when a server stops; the engines go with their entities. */
    public static void clearAll() {
        NAVIGATORS.clear();
    }

    // ---------------------------------------------------------------------------------------------------------
    // Commands. Each replaces whatever was running.
    // ---------------------------------------------------------------------------------------------------------

    /** Within {@code radius} blocks of {@code pos}; 0 means the block itself (feet in it). */
    public void goTo(BlockPos pos, int radius) {
        Goal g = radius <= 0 ? new GoalBlock(pos) : new GoalNear(pos, radius);
        startGoal(g);
    }

    /** Any height at this column. */
    public void goToXZ(int x, int z) {
        startGoal(new GoalXZ(x, z));
    }

    /** Any position with feet at this height, digging down or pillaring up if that is the way. */
    public void goToY(int y) {
        startGoal(new GoalYLevel(y));
    }

    /** Keeps within {@code distance} blocks of the entity until stopped, or until it is gone. */
    public void follow(Entity target, int distance) {
        Baritone e = begin(Kind.FOLLOW);
        e.settings().followRadius.set(Math.max(1, distance));
        e.getFollowProcess().follow(entity -> entity == target);
    }

    /**
     * Finds, reaches, breaks and picks up blocks of these kinds until the body holds {@code count} more of the
     * {@code wanted} items than it does now. The wanted items are not the blocks' own: stone drops cobblestone and an
     * ore drops a gem, so the caller names what the break yields. The command is done when that count is held; it
     * fails ({@link FailKind#NOTHING_TO_MINE}) when the engine runs out of blocks it knows and can reach first.
     */
    public void mine(Collection<Block> blocks, Collection<Item> wanted, int count) {
        mine(blocks, wanted, count, ConcurrentHashMap.newKeySet(), TARGET_STALL_TICKS);
    }

    /**
     * As {@link #mine(Collection, Collection, int)}, with the blocks to leave alone kept by the caller, so that a
     * later command (the caller starts one after exploring, say) does not go back to what this one gave up on.
     *
     * <p>A block the command has headed for, without getting a block closer to it or breaking anything, for
     * {@code stallTicks} ticks is added to {@code setAside}, and so is one the engine found no path to; the engine
     * goes on to the next block it knows. When every block it knows is set aside the command fails
     * ({@link FailKind#NOTHING_TO_MINE}), as it does when it knows none. Blocks the body would have to stand in or
     * under water to break are taken only when no other is known, and not while the body's air is below full; the wait
     * for that air is part of this command (see {@link #waitForAir}) -- the navigator holds the body afloat until the
     * air is full again, or gives the blocks up when it cannot be.
     *
     * @param setAside blocks to leave alone, filled by the navigator and the caller; must be safe to read from the
     *                 engine's search threads (a concurrent set)
     */
    public void mine(Collection<Block> blocks, Collection<Item> wanted, int count, Set<BlockPos> setAside, int stallTicks) {
        if (blocks.isEmpty() || wanted.isEmpty()) throw new IllegalArgumentException("mine needs blocks and wanted items");
        Baritone e = begin(Kind.MINE);
        mineItems = new LinkedHashSet<>(wanted);
        mineBlocks = List.copyOf(blocks);
        mineTarget = countHeld() + Math.max(1, count);
        this.setAside = Objects.requireNonNull(setAside, "setAside");
        targetStallTicks = Math.max(1, stallTicks);
        watches.clear();
        seenBroken = brokenCount();
        airWaitStart = 0;
        retriedGiveUp = false;
        looksAgain = 0;
        lookAgainTicks = 0;
        airWithheld = false;
        withheldForAir = List.of();
        wetCommitted = false;
        e.setMineTargetPolicy(targetPolicy);
        // The engine gets no count of its own (0 means mine until stopped): it would count a sample of the blocks'
        // drops, which is not what this command promises, and would stop early or never. tick() ends the command.
        e.getMineProcess().mine(0, blocks.toArray(Block[]::new));
    }

    /**
     * Builds {@code toBuild} at {@code anchor} with the engine's own builder: it walks, places from the inventory and
     * breaks what is in the way (issue 17). The engine's settings are set so that it
     * <ul>
     *   <li>places only from the inventory, and never with a block the blueprint does not name: the acceptable
     *       throwaway list is emptied for this command, so the search will not plan a bridge of dirt on the way and the
     *       builder's own placements are only the schematic's states, matched against what the body carries;</li>
     *   <li>breaks only inside the blueprint's box, and only what the break filter allows: the box is added to the
     *       filter for as long as this command runs, so a wall the body may not break is neither mined through nor
     *       left standing inside the hut either;</li>
     *   <li>builds layer by layer from the bottom, so a wall stands before a roof closes it off. Built nearest
     *       block first, a live run roofed a three-block hut before its walls were done and then stood outside
     *       it: the gaps left in the wall could only be filled from a cell the roof had made too low to stand
     *       in;</li>
     *   <li>no repeats, no orientation flips, the anchor the min corner.</li>
     * </ul>
     * Done when the builder finishes; failed when it cannot go on. The builder's own answer to running out of a block
     * is to pause and say what is missing ({@code BuilderProcess}: "Missing materials ... Unable to do it. Pausing."),
     * which would wait for ever here: this turns the pause into a failure the moment it is seen, naming the block the
     * body ran out of ({@link FailKind#MISSING_BLOCKS}), or {@link FailKind#BUILD_STUCK} when the hold-up is not
     * materials. A build that starts again skips what stands: the builder compares the world with the schematic
     * itself.
     */
    public void build(Blueprint toBuild, BlockPos anchor) {
        Baritone e = begin(Kind.BUILD);
        blueprint = toBuild;
        buildAnchor = anchor;
        buildBox = new Box(anchor, anchor.offset(toBuild.widthX() - 1, toBuild.heightY() - 1, toBuild.lengthZ() - 1));
        Settings s = e.settings();
        // Only what the body carries, and only where the blueprint says: see the javadoc.
        s.acceptableThrowawayItems.set(new ArrayList<>());
        s.allowInventory.set(true);
        s.buildInLayers.set(true);
        s.layerOrder.set(false); // bottom to top
        s.startAtLayer.set(0);
        // A layer that cannot be finished (a block the body has run out of) does not hold up the layers above it:
        // everything that can stand is built, and the end still says what is missing.
        s.skipFailedLayers.set(true);
        s.buildRepeat.set(new Vec3i(0, 0, 0));
        s.buildRepeatCount.set(-1);
        // The anchor is the min corner as it was given: none of the orientation flips upstream offers.
        s.schematicOrientationX.set(false);
        s.schematicOrientationY.set(false);
        s.schematicOrientationZ.set(false);
        s.mapArtMode.set(false);
        s.buildIgnoreExisting.set(false);
        applyBreakFilter(e);
        e.getBuilderProcess().build(toBuild.name(), schematicOf(toBuild),
                new Vec3i(anchor.getX(), anchor.getY(), anchor.getZ()));
    }

    /** The blueprint as the builder reads it: a static schematic whose gaps (air and structure void) hold null. */
    private static IStaticSchematic schematicOf(Blueprint blueprint) {
        BlockState[][][] states = new BlockState[blueprint.widthX()][blueprint.lengthZ()][blueprint.heightY()];
        for (Blueprint.Entry entry : blueprint.blocks()) {
            states[entry.pos().getX()][entry.pos().getZ()][entry.pos().getY()] = entry.state();
        }
        return new BlueprintSchematic(states);
    }

    /** A static array over the blueprint's box; a null state is a cell the blueprint does not name, left as it is. */
    private static final class BlueprintSchematic extends AbstractSchematic implements IStaticSchematic {
        private final BlockState[][][] states;

        BlueprintSchematic(BlockState[][][] states) {
            super(states.length, states.length == 0 ? 0 : states[0].length == 0 ? 0 : states[0][0].length,
                    states.length == 0 ? 0 : states[0].length);
            this.states = states;
        }

        @Override
        public BlockState desiredState(int x, int y, int z, BlockState current, List<BlockState> approxPlaceable) {
            return states[x][z][y];
        }

        @Override
        public BlockState getDirect(int x, int y, int z) {
            return states[x][z][y];
        }
    }

    /**
     * The blocks the running mine command knows of and may head for, nearest first; empty when none runs. Plain
     * positions, so that a caller can keep them in its own set and find them again (see {@link #plain}).
     */
    public List<BlockPos> knownTargets() {
        if (kind != Kind.MINE || state != State.WORKING || engine == null) return List.of();
        List<BlockPos> known = engine.getMineProcess().knownLocations();
        List<BlockPos> out = new ArrayList<>(known.size());
        for (BlockPos at : known) out.add(plain(at));
        return out;
    }

    /** What the working command waits for before it asks the engine again; {@link Wait#NONE} while the engine works. */
    public Wait waitState() {
        return state == State.WORKING ? waiting : Wait.NONE;
    }

    /** The block a running mine command is heading for, or null when it has none in mind yet. */
    @Nullable
    public BlockPos focusTarget() {
        return kind == Kind.MINE && state == State.WORKING ? focus : null;
    }

    /** Distance from the body to the nearest block a running mine command may head for; NaN when it knows none. */
    public double nearestTargetDistance() {
        double nearest = Double.NaN;
        for (BlockPos target : knownTargets()) {
            double away = distanceTo(target);
            if (Double.isNaN(nearest) || away < nearest) nearest = away;
        }
        return nearest;
    }

    /** Walks outward from {@code origin} over chunks not seen yet, at any height, until stopped. */
    public void explore(BlockPos origin) {
        startExplore(-1).getExploreProcess().explore(origin.getX(), origin.getZ());
    }

    /** As {@link #explore}, but keeps to the height {@code y} where the terrain allows (underground ore searches). */
    public void exploreAtY(BlockPos origin, int y) {
        startExplore(y).getExploreProcess().explore(origin.getX(), origin.getZ());
    }

    /**
     * Begins an explore with the engine told which chunks the body has looked over lately (see
     * {@link #SEEN_CHUNK_TICKS}) and the ones around it now, so the first place it heads for is ground it has not seen.
     */
    private Baritone startExplore(int maintainY) {
        Baritone e = begin(Kind.EXPLORE);
        e.settings().exploreMaintainY.set(maintainY);
        long now = body.level().getGameTime();
        String here = body.level().dimension().location().toString();
        if (!here.equals(seenDimension)) {
            seenChunks.clear();
            seenDimension = here;
        }
        seenChunks.values().removeIf(seen -> now - seen > SEEN_CHUNK_TICKS);
        IWorldData seen = e.getWorldProvider().getCurrentWorld();
        seen.forgetChunks();
        for (long key : seenChunks.keySet()) seen.addBlockPosToCache(ChunkPos.getX(key) << 4, ChunkPos.getZ(key) << 4);
        seenFrom = null;
        noteSeenChunks(e);
        return e;
    }

    /** Marks the chunks around the body as looked over, once for each chunk it steps into while it explores. */
    private void noteSeenChunks(Baritone e) {
        ChunkPos at = body.chunkPosition();
        if (at.equals(seenFrom)) return;
        seenFrom = at;
        long now = body.level().getGameTime();
        IWorldData seen = e.getWorldProvider().getCurrentWorld();
        for (int dx = -SEEN_CHUNK_RADIUS; dx <= SEEN_CHUNK_RADIUS; dx++) {
            for (int dz = -SEEN_CHUNK_RADIUS; dz <= SEEN_CHUNK_RADIUS; dz++) {
                seenChunks.put(ChunkPos.asLong(at.x + dx, at.z + dz), now);
                seen.addBlockPosToCache((at.x + dx) << 4, (at.z + dz) << 4);
            }
        }
    }

    /**
     * Says who the command the caller is about to give is for. A task calls this on its way to every command, so that when it
     * is paused or aborted it can tell its own command from one somebody else started since: the reflex layer runs before the
     * executor in a body tick, and can have begun a retreat by the time the executor pauses the task for it. The claim lasts
     * until the next command begins; anyone else who asks for this navigator with {@link #of} voids it, so a claim made and not
     * used cannot be taken by a command that is not the claimant's. A command given with no claim has no issuer.
     */
    public BotNavigator issuingFor(Object who) {
        claimant = Objects.requireNonNull(who);
        return this;
    }

    /** True when a command is running, done or failed here that {@code who} did not give: it is not theirs to stop. */
    public boolean runsCommandOfAnother(Object who) {
        return state != State.IDLE && issuer != who;
    }

    /** Cancels whatever runs and releases the body's input. The state goes back to IDLE. */
    public void stop() {
        if (engine != null) {
            if (kind == Kind.MINE) keepWhatTheEngineGaveUpOn(engine.getMineProcess());
            cancelEngine(engine);
            engine.setMineTargetPolicy(null);
        }
        issuer = null;
        kind = null;
        goal = null;
        focus = null;
        airWaitStart = 0;
        blueprint = null;
        buildAnchor = null;
        buildBox = null;
        state = State.IDLE;
        failure = null;
        failKind = null;
        failureCause = null;
        releaseInput();
    }

    public State state() {
        return state;
    }

    /** How many commands this navigator has begun; the number moves on whenever one begins, so it names the current command. */
    public long serial() {
        return serial;
    }

    /**
     * How many times the engine has been ticked for this body since the navigator was made. The brain ticks it once per
     * body tick at most; a GameTest compares this with the ticks the body has had to catch a second driver (a walk twice as
     * fast as a player's shows nowhere else).
     */
    public long engineTicks() {
        return engineTicks;
    }

    /** Why the last command failed, or null unless the state is FAILED. For logs: route on {@link #failKind()}. */
    @Nullable
    public String failure() {
        return state == State.FAILED ? failure : null;
    }

    /** What kind of failure ended the last command, or null unless the state is FAILED. */
    @Nullable
    public FailKind failKind() {
        return state == State.FAILED ? failKind : null;
    }

    /**
     * The exception the engine threw behind an {@link FailKind#ENGINE_ERROR} failure, or null in any other case: a
     * caller that reports the failure as its own can carry the cause instead of only the text.
     */
    @Nullable
    public Throwable failureCause() {
        return state == State.FAILED && failKind == FailKind.ENGINE_ERROR ? failureCause : null;
    }

    /** True while the body is working at a block (progress that its position and inventory do not show yet). */
    public boolean breaking() {
        return body.getInteractionManager().isMining();
    }

    /** True while a navigator exists for this body, whatever it is doing. For tests and the dev command. */
    public static boolean tracks(UUID bodyId) {
        return NAVIGATORS.containsKey(bodyId);
    }

    /**
     * Drops the navigators of bodies that are gone for good. A navigator follows its body through a respawn (same
     * UUID, new instance), so only a body that is no longer logged in at all, a despawn nothing replaced, makes its
     * navigator dead weight; left alone it would keep the removed body, and its engine, in memory for the life of the
     * process. Called periodically by {@link NavFeature}.
     */
    public static void sweep(MinecraftServer server) {
        NAVIGATORS.values().removeIf(navigator -> {
            if (navigator.body.getServer() != server) return false;
            if (server.getPlayerList().getPlayer(navigator.body.getUUID()) != null) return false;
            try {
                if (navigator.engine != null) navigator.dropEngine(navigator.engine);
            } catch (RuntimeException e) {
                // Called from the server tick: the engine of a body that is gone must not take the tick down.
                NavRuntime.LOGGER.error("Could not release the engine of a removed body", e);
            }
            navigator.engine = null;
            return true;
        });
    }

    /** Consulted when a path is costed (off thread) and before every break. Applies to the running engine at once. */
    public void setBreakFilter(BreakFilter filter) {
        this.breakFilter = Objects.requireNonNull(filter);
        if (engine != null) applyBreakFilter(engine);
    }

    /** The filter in force, so a caller that sets its own for a while (the reflex layer's retreat) can put the previous one back. */
    public BreakFilter breakFilter() {
        return breakFilter;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Tick
    // ---------------------------------------------------------------------------------------------------------

    /**
     * Once per body tick, from the brain, before the body applies its input. Runs the engine while a command is
     * working and decides when it is done; does nothing (and writes no input) otherwise.
     */
    public void tick(BotPlayer current) {
        follow(current);
        refreshDangerZones();
        if (state != State.WORKING || engine == null) return;
        if (!ownsBody(engine)) {
            fail(FailKind.BODY_LOST, body.isRemoved() ? "body removed" : "body changed dimension or was replaced");
            engine = null;
            return;
        }

        // The falls the body survives depend on its health, read fresh every tick (design 2.2).
        engine.settings().maxFallHeightNoWater.set(fallLimit(body.getHealth()));
        inputDirty = true;
        engineTicks++;
        try {
            engine.serverTick();
        } catch (RuntimeException e) {
            // A brain must not throw (BotBrain): nothing around the body tick catches it, so an engine bug would take
            // the server down. It fails this command instead, with the exception as the reason.
            NavRuntime.LOGGER.error("Navigation engine failed for {}", body.getGameProfile().getName(), e);
            failureCause = e;
            fail(FailKind.ENGINE_ERROR, "engine error: " + e);
            return;
        }

        switch (Objects.requireNonNull(kind)) {
            case GOTO -> {
                if (!engine.getCustomGoalProcess().isActive()) {
                    if (goal != null && goal.isInGoal(engine.getEntityContext().feetPos())) finish();
                    else fail(FailKind.NO_PATH, reason("no path"));
                } else {
                    watchProgress();
                }
            }
            case MINE -> tickMine();
            case FOLLOW -> {
                if (!engine.getFollowProcess().isActive()) fail(FailKind.TARGET_GONE, reason("target gone"));
            }
            case EXPLORE -> {
                noteSeenChunks(engine);
                if (!engine.getExploreProcess().isActive()) {
                    // The process also ends when it has nothing left to explore, which is a success. It records a
                    // failure reason only when its search for a path failed.
                    if (engine.getLastFailure() != null) fail(FailKind.EXPLORE_FAILED, reason("exploration failed"));
                    else finish();
                }
            }
            case BUILD -> tickBuild();
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Building
    // ---------------------------------------------------------------------------------------------------------

    /**
     * The builder's state, once per tick: done when its process has ended (it clears itself when nothing is wrong any
     * more, {@code BuilderProcess.onLostControl}); failed when it paused, which is upstream's answer to being unable to
     * go on -- with materials missing, or with something in the way it cannot break or reach. Nobody here calls
     * {@code pause()}, so a paused builder is always that.
     */
    private void tickBuild() {
        BuilderProcess process = engine.getBuilderProcess();
        if (!process.isActive()) {
            finish();
            return;
        }
        if (process.isPaused()) {
            Item missing = missingBuildBlock();
            if (missing != null) {
                int shortBy = shortOf(missing);
                fail(FailKind.MISSING_BLOCKS, reason("the builder is out of " + describeItem(missing) + " (" + shortBy
                        + " more needed)"));
            } else {
                fail(FailKind.BUILD_STUCK, reason("the builder cannot go on"));
            }
        }
    }

    /**
     * The first item the build is short of, or null when it is not short of any: for every position that does not hold
     * its block yet, what places that block is needed, and the body has to hold as many of them as there are
     * positions. Read from the world, as the builder itself does.
     */
    @Nullable
    private Item missingBuildBlock() {
        if (blueprint == null || buildAnchor == null) return null;
        ServerLevel level = body.serverLevel();
        Map<Item, Integer> needed = new LinkedHashMap<>();
        for (Blueprint.Entry entry : blueprint.blocks()) {
            BlockPos at = buildAnchor.offset(entry.pos());
            if (level.getBlockState(at).getBlock() == entry.state().getBlock()) continue;
            needed.merge(entry.state().getBlock().asItem(), 1, Integer::sum);
        }
        for (Map.Entry<Item, Integer> need : needed.entrySet()) {
            if (body.getInventory().countItem(need.getKey()) < need.getValue()) return need.getKey();
        }
        return null;
    }

    /** How many more of the item the build still needs than the body holds. */
    private int shortOf(Item item) {
        if (blueprint == null || buildAnchor == null) return 1;
        ServerLevel level = body.serverLevel();
        int needed = 0;
        for (Blueprint.Entry entry : blueprint.blocks()) {
            BlockPos at = buildAnchor.offset(entry.pos());
            if (level.getBlockState(at).getBlock() != entry.state().getBlock()
                    && entry.state().getBlock().asItem() == item) {
                needed++;
            }
        }
        return Math.max(1, needed - body.getInventory().countItem(item));
    }

    private static String describeItem(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

    /** Max fall without water: 3 is free, and each block more costs a point of health; keep at least half of what is left above 3 hearts. */
    static int fallLimit(float health) {
        return 3 + Math.max(0, (int) Math.floor((health - 6.0F) / 2.0F));
    }

    // ---------------------------------------------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------------------------------------------

    /** Points this navigator at the body as it is now. A command running on an earlier instance fails. */
    private void follow(BotPlayer current) {
        if (current == body) return;
        BotPlayer previous = body;
        body = current;
        if (engine != null && engine.getEntityContext().entity() == previous) {
            if (state == State.WORKING) fail(FailKind.BODY_LOST, "body replaced (respawn)");
            dropEngine(engine);
            engine = null;
        }
    }

    private void startGoal(Goal g) {
        Baritone e = begin(Kind.GOTO);
        goal = g;
        bestHeuristic = g.heuristic(e.getEntityContext().feetPos());
        sinceProgress = 0;
        e.getCustomGoalProcess().setGoalAndPath(g);
    }

    /** Cancels what runs, makes sure the engine is this body's own, and marks the new command as working. */
    private Baritone begin(Kind newKind) {
        Baritone e = engine();
        refreshDangerZones();
        if (kind == Kind.MINE) keepWhatTheEngineGaveUpOn(e.getMineProcess());
        cancelEngine(e);
        e.clearLastFailure();
        e.setMineTargetPolicy(null);
        serial++;
        issuer = claimant;
        claimant = null;
        kind = newKind;
        goal = null;
        focus = null;
        blueprint = null;
        buildAnchor = null;
        buildBox = null;
        // A build empties the throwaway list for its own command (see {@link #build}); whatever comes next gets the
        // survival list back, so a mine after a build can still bridge.
        e.settings().acceptableThrowawayItems.set(new ArrayList<>(THROWAWAY));
        waiting = Wait.NONE;
        failure = null;
        failKind = null;
        failureCause = null;
        state = State.WORKING;
        return e;
    }

    private Baritone engine() {
        if (engine != null && ownsBody(engine)) return engine;
        IBaritone existing = IBaritone.KEY.getNullable(body);
        if (existing != null && existing.getEntityContext().entity() != body) {
            // An earlier instance under this UUID whose removal never reached the engine: never drive it.
            dropEngine(existing);
            existing = null;
        }
        if (existing == null) {
            // The interaction controller is cached per UUID too, holding the entity it was made for.
            IInteractionController.KEY.remove(body);
            existing = IBaritone.KEY.get(body);
        }
        engine = (Baritone) existing;
        configure(engine);
        return engine;
    }

    /** True while the engine is the one registered for the body and drives that very instance. */
    private boolean ownsBody(Baritone e) {
        return IBaritone.KEY.getNullable(body) == e && e.getEntityContext().entity() == body && !body.isRemoved();
    }

    /** Stops a stale engine and unregisters it, but only if the registry still points at it. */
    private void dropEngine(IBaritone stale) {
        ((PathingBehavior) stale.getPathingBehavior()).shutdown();
        if (IBaritone.KEY.getNullable(body) == stale) {
            IBaritone.KEY.remove(body);
            IInteractionController.KEY.remove(body);
            ISelectionManager.KEY.remove(body);
        }
    }

    private static void cancelEngine(Baritone e) {
        e.getPathingBehavior().forceCancel();
    }

    /** The survival defaults (design 2.2); per engine, over the global settings. */
    private void configure(Baritone e) {
        Settings s = e.settings();
        s.allowBreak.set(true);
        s.allowPlace.set(true);
        s.allowParkour.set(false);
        s.allowParkourPlace.set(false);
        s.allowSprint.set(true);
        // Water (measured in BotNavGameTests): with swimming off the body crosses deep water at the surface, holding
        // jump, which is how a player swims across. Swimming on crossed the 3-deep pool faster (65 ticks against 96, still
        // at the surface) but stops the jump-holding and opens paths through water above water, and with ignoreBreath on
        // nothing keeps such a path from drowning the body, so it stays off. Flowing water is opened up: with it off the
        // flowing-water test ends "no path", a stream is a wall.
        s.allowSwimming.set(false);
        s.allowFlowingWater.set(true);
        // Air is the engine's own measure of how long a path may stay under water. Upstream's default ignores it, so
        // nothing stops a path that dives from running past what the body can hold (the first live run, issue 27, ended
        // with the body drowned in a flooded cave). Off here, a move that would spend more air than the body has is not a
        // move, and the air it has now is where the search starts from.
        s.ignoreBreath.set(false);
        s.randomLooking.set(0.0);
        s.randomLooking113.set(0.0);
        // Upstream keeps an explorer at height 64; exploring goes wherever the terrain lets it unless a command says
        // otherwise (exploreAtY).
        s.exploreMaintainY.set(-1);
        s.fallDamagePenalty.set(FALL_DAMAGE_PENALTY);
        s.maxFallHeightNoWater.set(fallLimit(body.getHealth()));
        s.acceptableThrowawayItems.set(new ArrayList<>(THROWAWAY));
        s.blocksToAvoidBreaking.set(new ArrayList<>(avoidBreaking()));
        // Danger zones (issue 29): the engine's own avoidance prices a zone's cells high, so a walk goes round one when
        // there is a way round and through it when there is not. The finder runs on the search pool, so it reads the
        // snapshot {@link #dangerZones} keeps, never the memory.
        s.avoidance.set(true);
        e.getEntityContext().setAvoidanceFinder(this::avoidedAreas);
        applyBreakFilter(e);
    }

    /** The body's live danger zones as the engine prices them ({@link Avoidance}); called from the search pool. */
    private List<Avoidance> avoidedAreas() {
        List<Avoidance> out = new ArrayList<>(dangerZones.size());
        for (BotMemory.DangerZone zone : dangerZones) {
            out.add(new Avoidance(zone.centre(), DANGER_ZONE_COST, (int) Math.ceil(zone.radius())));
        }
        return out;
    }

    /**
     * Reads the body's live danger zones into the snapshot the search pool and the target policy read, and drops the
     * ones that have lapsed from the memory while it is there. Server thread.
     */
    private void refreshDangerZones() {
        BotMemory memory = BotMemory.peek(body.getUUID());
        dangerZones = memory == null ? List.of() : List.copyOf(memory.liveDangerZones(body.level().getGameTime()));
        dimension = body.level().dimension().location().toString();
    }

    /** Whether a position stands inside one of the body's live danger zones; any thread, over the snapshot. */
    private boolean inADangerZone(BlockPos at) {
        for (BotMemory.DangerZone zone : dangerZones) {
            if (zone.covers(dimension, at)) return true;
        }
        return false;
    }

    /**
     * How many blocks the running mine command's last choice of targets held back because they stand inside a live
     * danger zone (issue 29). Not set aside: when the zone lapses they are targets again.
     */
    public int zoneWithheld() {
        return zoneWithheld;
    }

    private void applyBreakFilter(Baritone e) {
        BreakFilter filter = breakFilter;
        // The box is read on every question (search threads too), so a build command that starts or ends changes what
        // an already-installed filter answers without anyone re-installing it.
        e.setBreakFilter((level, pos, state) -> {
            if (!(level instanceof ServerLevel server)) return false;
            if (!filter.mayBreak(server, pos, state)) return false;
            Box box = buildBox;
            return box == null || box.inside(pos);
        });
    }

    /**
     * Upstream's list (crafting table, furnaces, campfire, chests) plus every bed and every block whose block entity
     * holds items (chests, barrels, shulker boxes, hoppers, brewing stands, lecterns...), and the ender chest, whose
     * contents live elsewhere. Built once from the registry, so modded containers are included.
     */
    private static synchronized List<Block> avoidBreaking() {
        if (avoidBreaking != null) return avoidBreaking;
        Set<Block> blocks = new LinkedHashSet<>(List.of(
            Blocks.CRAFTING_TABLE, Blocks.FURNACE, Blocks.CAMPFIRE, Blocks.SMOKER, Blocks.BLAST_FURNACE, Blocks.CHEST,
            Blocks.TRAPPED_CHEST, Blocks.ENDER_CHEST, Blocks.SOUL_CAMPFIRE));
        for (Block block : BuiltInRegistries.BLOCK) {
            if (block instanceof BedBlock) {
                blocks.add(block);
            } else if (block instanceof EntityBlock entityBlock) {
                try {
                    BlockEntity be = entityBlock.newBlockEntity(BlockPos.ZERO, block.defaultBlockState());
                    if (be instanceof Clearable) blocks.add(block);
                } catch (RuntimeException ignored) {
                    // A block entity that needs a level to construct: not knowable here, and not ours to guess.
                }
            }
        }
        avoidBreaking = List.copyOf(blocks);
        return avoidBreaking;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Mining: blocks the body cannot get at, blocks under water
    // ---------------------------------------------------------------------------------------------------------

    private void tickMine() {
        MineProcess process = engine.getMineProcess();
        if (countHeld() >= mineTarget) {
            // The engine runs with no count of its own (see mine): the count is reached here, and the engine
            // is told to let go of the blocks it still had in mind.
            cancelEngine(engine);
            finish();
            return;
        }
        if (!process.isActive()) {
            // The engine's process is ended (or waiting on the navigator): nothing is being headed for, whatever
            // the last block was, so the status must not keep naming it.
            focus = null;
            if (airWithheld) {
                if (waitForAir(process)) {
                    waiting = Wait.FOR_AIR;
                    return;
                }
                // The wet targets were given up: the command goes on with what is left, as it does below.
            } else {
                endAirWait(); // the withholding ended without the wait: whatever it held on the body is let go
            }
            if (lookAgain(process)) {
                waiting = Wait.TO_LOOK_AGAIN;
                return;
            }
            keepWhatTheEngineGaveUpOn(process);
            int aside = setAside.size();
            fail(FailKind.NOTHING_TO_MINE, reason("nothing to mine in range")
                + (aside == 0 ? "" : " (" + aside + " block" + (aside == 1 ? "" : "s") + " set aside)"));
            return;
        }
        waiting = Wait.NONE;
        endAirWait(); // the engine drives the body again: no wait of the navigator's is still on
        watchTargets(process);
    }

    /**
     * What the engine gave up on (no path to it) is set aside for good, not only until the next command forgets it. Asked
     * when a command ends or is replaced, not while it runs: the engine already leaves such a block alone for the rest of
     * the command, and a block the owner set aside would also be hidden from its rescans.
     */
    private void keepWhatTheEngineGaveUpOn(MineProcess process) {
        for (BlockPos given : process.blacklisted()) {
            if (setAside.add(plain(given))) {
                NavRuntime.LOGGER.debug("{}: no path to the block at {}; set aside",
                    body.getGameProfile().getName(), given.toShortString());
            }
        }
    }

    /**
     * The body is heading for the known block nearest to where its path ends (to itself when it has no path). That block
     * is set aside once the body has gone {@link #targetStallTicks} ticks without getting a block closer to it or
     * breaking anything.
     */
    private void watchTargets(MineProcess process) {
        List<BlockPos> known = process.knownLocations();
        watches.keySet().retainAll(known);
        if (known.isEmpty()) {
            focus = null;
            return;
        }
        BlockPos aim = pathEnd();
        BlockPos nearest = known.get(0);
        for (BlockPos target : known) {
            if (target.distSqr(aim) < nearest.distSqr(aim)) nearest = target;
        }
        focus = nearest;

        // A block being broken is progress too: a log takes a body with bare hands three seconds, and the body does not
        // get any closer to a block it is standing at while it works on it.
        int broken = brokenCount();
        boolean working = broken != seenBroken || breaking();
        seenBroken = broken;
        double away = distanceTo(nearest);
        TargetWatch watch = watches.computeIfAbsent(nearest, at -> new TargetWatch(away));
        if (working || away < watch.closest - CLOSER_BY) {
            watch.closest = Math.min(watch.closest, away);
            watch.ticksWithoutGain = 0;
        } else if (++watch.ticksWithoutGain >= targetStallTicks) {
            NavRuntime.LOGGER.info("{}: set aside the block at {}: no closer than {} blocks to it in {} ticks",
                body.getGameProfile().getName(), nearest.toShortString(), (int) Math.ceil(watch.closest), targetStallTicks);
            setAside.add(plain(nearest));
            watches.remove(nearest);
            focus = null;
        }
    }

    /**
     * A block position as the plain {@code BlockPos} its takers assume: the engine's own subclass computes its
     * {@code hashCode} its own way, so a position that crosses this unconverted sits unrecognized in a set of plain
     * positions (the set-aside blocks) however equal it looks. Every position on its way from the engine into such a
     * set crosses this -- including the copies {@link #knownTargets()} hands out, which is what lets a caller add them
     * straight in.
     */
    private static BlockPos plain(BlockPos pos) {
        return pos.getClass() == BlockPos.class ? pos : new BlockPos(pos.getX(), pos.getY(), pos.getZ());
    }

    /** Where the engine's current path ends, or where the body stands when it has none. */
    private BlockPos pathEnd() {
        PathExecutor current = engine.getPathingBehavior().getCurrent();
        IPath path = current == null ? null : current.getPath();
        return path == null || path.positions().isEmpty() ? body.blockPosition() : path.getDest();
    }

    private double distanceTo(BlockPos target) {
        return Math.sqrt(body.position().distanceToSqr(Vec3.atCenterOf(target)));
    }

    private int brokenCount() {
        return body.getInteractionManager() instanceof BodyInteractionManager manager ? manager.blocksBroken() : 0;
    }

    /**
     * The engine ended the command with nothing, or having given up on a single block; each is tried once more before the
     * command fails, because neither is always the answer it looks like. A scan that finds nothing may have missed a chunk
     * that was out of the engine's sight for a moment (the arenas of the GameTests come and go next to one another, and
     * the scan stops at the first ring of chunks it cannot read); it is repeated after {@link #LOOK_AGAIN_TICKS}. A single
     * give-up is often the engine blaming the wrong block: a path search already running when the targets changed fails,
     * and whatever block is nearest then takes the blame; it is repeated at once. A block the engine gives up on twice has
     * earned it. True while the command goes on.
     */
    private boolean lookAgain(MineProcess process) {
        int gaveUp = process.blacklisted().size();
        if (gaveUp > 1) return false;
        if (gaveUp == 1) {
            if (retriedGiveUp) return false;
            retriedGiveUp = true;
            process.mine(0, mineBlocks.toArray(Block[]::new));
            return true;
        }
        if (looksAgain >= 1) return false;
        if (++lookAgainTicks >= LOOK_AGAIN_TICKS) {
            looksAgain++;
            lookAgainTicks = 0;
            process.mine(0, mineBlocks.toArray(Block[]::new));
        }
        return true;
    }

    /**
     * The engine ended its command because every block it knows is one the policy holds back for want of air. The
     * wait for that air is part of this mine command, not a second one: the navigator keeps the command's kind, issuer
     * and serial, so the task that gave it can still stop the body (its release, pause and abort reach this wait),
     * and moves the body itself through its input -- jump held, which is how a player rises in a water column and
     * floats at the surface (the hazard reflex surfaces a body the same way), and forward toward the place to breathe
     * when it is not straight above. No {@code goTo} is issued by the navigator on its own, so no walk of its own can
     * fail the step or run on behind the owner's back.
     *
     * <p>One game-time deadline bounds the whole wait, set when it first runs for a withholding in this command and
     * moved by nothing the navigator does: {@link #AIR_WAIT_TICKS} for the air to be full again, and
     * {@link #SURFACE_TICKS} for the head to be out of the water at all. Past either, the wet targets are given up
     * (set aside, as a block the engine found no path to is) and the command goes on with what is left, so the step
     * ends as it does for any exhausted set of targets. Nowhere to breathe within {@link #SURFACE_RADIUS} blocks ends
     * the wait the same way, at once. The air full again, the engine's mine process is started afresh under this same
     * command. True while it waits.
     */
    private boolean waitForAir(MineProcess process) {
        long now = body.level().getGameTime();
        if (airWaitStart == 0) airWaitStart = now;
        if (body.isEyeInFluid(FluidTags.WATER)) {
            BlockPos out = placeToBreathe();
            if (out == null) {
                giveUpWetTargets("nowhere within " + SURFACE_RADIUS + " blocks to get the head out of the water");
                endAirWait();
                return false;
            }
            if (now - airWaitStart > SURFACE_TICKS) {
                giveUpWetTargets("the head did not come out of the water within " + SURFACE_TICKS + " ticks");
                endAirWait();
                return false;
            }
            holdAfloat(out);
        } else {
            holdAfloat(null);
        }
        if (body.getAirSupply() >= body.getMaxAirSupply()) {
            // The air is back: the wait is over (the jump is let go) and the engine takes the body again.
            endAirWait();
            airWithheld = false;
            process.mine(0, mineBlocks.toArray(Block[]::new));
            return true;
        }
        if (now - airWaitStart > AIR_WAIT_TICKS) {
            giveUpWetTargets("the air did not come back within " + AIR_WAIT_TICKS + " ticks of the wait starting");
            endAirWait();
            return false;
        }
        return true;
    }

    /**
     * What keeps the body up while it waits: jump held through the body's input while any of it is in water. Written
     * after the engine's own tick (this runs from {@link #tickMine}, below {@code engine.serverTick()} in
     * {@link #tick}), which writes the input every tick itself whatever drives it, so this is the body's last word
     * this tick; the engine's keys take over again the tick it next drives. {@code toward} is the place to breathe
     * when the head is still under and it is not straight above: the body swims for it, as a player would.
     */
    private void holdAfloat(@Nullable BlockPos toward) {
        BodyInput input = body.input();
        input.jump = body.isInWater();
        input.forward = 0.0F;
        input.strafe = 0.0F;
        input.sprint = false;
        if (toward == null) return;
        BlockPos feet = body.blockPosition();
        if (toward.getX() == feet.getX() && toward.getZ() == feet.getZ()) return;
        input.lookAt(Vec3.atBottomCenterOf(toward));
        input.forward = 1.0F;
    }

    /**
     * However the wait for air ended -- the air full again, the wet targets given up, the withholding gone -- the
     * hold it kept on the body's input is let go and the wait's clock cleared. Nothing else of the command's changes.
     */
    private void endAirWait() {
        if (airWaitStart == 0) return;
        airWaitStart = 0;
        BodyInput input = body.input();
        input.forward = 0.0F;
        input.strafe = 0.0F;
        input.jump = false;
        input.sprint = false;
        input.clearLook();
    }

    /**
     * The nearest place within {@link #SURFACE_RADIUS} blocks whose eye cell is air and that the body can stand or
     * float at, scanning upward and around from where it stands: straight up the water column it is in first (a body
     * floats with its feet in the top of the water and its eyes above it), then the rings around, where the ground
     * beside the water is. Null when there is no such place in range.
     */
    @Nullable
    private BlockPos placeToBreathe() {
        ServerLevel level = body.serverLevel();
        BlockPos feet = body.blockPosition();
        int highY = Math.min(feet.getY() + SURFACE_RADIUS, level.getMaxBuildHeight() - 2);
        for (int y = feet.getY(); y <= highY; y++) {
            for (int away = 0; away <= SURFACE_RADIUS; away++) {
                for (int dx = -away; dx <= away; dx++) {
                    for (int dz = -away; dz <= away; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != away) continue; // this ring only
                        BlockPos at = new BlockPos(feet.getX() + dx, y, feet.getZ() + dz);
                        if (!level.getBlockState(at.above()).isAir()) continue;
                        // Floating: feet in the top of the water, eyes in the air above it.
                        if (level.getFluidState(at).is(FluidTags.WATER)) return at;
                        // Standing: feet in the air on something with collision to stand on.
                        if (level.getBlockState(at).isAir()
                            && !level.getBlockState(at.below()).getCollisionShape(level, at.below()).isEmpty()) return at;
                    }
                }
            }
        }
        return null;
    }

    /**
     * The wet targets the policy was holding back are given up for this command: set aside like the ones the engine
     * found no path to, so that neither this task nor a later one goes back to them under water.
     */
    private void giveUpWetTargets(String why) {
        airWithheld = false;
        for (BlockPos target : withheldForAir) {
            if (setAside.add(plain(target))) {
                NavRuntime.LOGGER.info("{}: gave up the block at {}; held back for want of air and {}",
                    body.getGameProfile().getName(), target.toShortString(), why);
            }
        }
    }

    /**
     * The mine process's choice among the blocks it knows (asked every tick and on every rescan, on any thread): none
     * that are set aside; none that stand inside a live danger zone (issue 29); dry ones before any the body would take
     * standing in or under water; and those only when the body's air is full, or when it has already started for one
     * (the air falls on the way there).
     */
    private List<BlockPos> selectTargets(List<BlockPos> candidates, Function<BlockPos, BlockState> blocks) {
        Set<BlockPos> aside = setAside;
        List<BlockPos> open = new ArrayList<>(candidates.size());
        List<BlockPos> dry = new ArrayList<>(candidates.size());
        int inZones = 0;
        for (BlockPos candidate : candidates) {
            if (aside.contains(plain(candidate))) continue;
            // A block inside a live danger zone is not headed for (issue 29), and unlike a wet one it is not taken when
            // nothing else is known: the command then ends with nothing to mine and the task explores elsewhere. It is
            // never set aside for it, so the block comes back the moment the zone lapses.
            if (inADangerZone(candidate)) {
                inZones++;
                continue;
            }
            open.add(candidate);
            if (!wet(candidate, blocks)) dry.add(candidate);
        }
        zoneWithheld = inZones;
        if (!dry.isEmpty()) {
            wetCommitted = false;
            airWithheld = false;
            return dry;
        }
        if (open.isEmpty()) {
            airWithheld = false; // nothing is left to hold back: an old withholding must not start a wait
            return open;
        }
        if (wetCommitted || body.getAirSupply() >= body.getMaxAirSupply()) {
            wetCommitted = true;
            airWithheld = false;
            return open;
        }
        // The list before the flag: the tick thread reads the flag first, and must then find this withholding's list.
        withheldForAir = List.copyOf(open);
        airWithheld = true;
        return List.of();
    }

    /**
     * Water in the working column -- the block's own cell and the two cells above and the two below it, and no cell that
     * merely touches it from the side: the engine's mine goal puts the body's feet at the block or one or two below it,
     * so these are the cells its feet and head can be in, and side water reaches the body only through them, if at all,
     * which is why the column alone is asked about.
     * The engine itself already refuses to break a block with liquid directly above it or at its sides (its cost is
     * infinite, so such a block is never a target at all), so a block that gets this far is wet only from below or from
     * two cells up.
     */
    private static boolean wet(BlockPos pos, Function<BlockPos, BlockState> blocks) {
        for (int dy = -2; dy <= 2; dy++) {
            if (blocks.apply(pos.above(dy)).getFluidState().is(FluidTags.WATER)) return true;
        }
        return false;
    }

    /** An inclusive box of blocks, read from search threads while a build runs. */
    private record Box(BlockPos min, BlockPos max) {
        boolean inside(BlockPos at) {
            return at.getX() >= min.getX() && at.getX() <= max.getX()
                    && at.getY() >= min.getY() && at.getY() <= max.getY()
                    && at.getZ() >= min.getZ() && at.getZ() <= max.getZ();
        }
    }

    /** What a mine command knows about its approach to one block. */
    private static final class TargetWatch {
        double closest;
        int ticksWithoutGain;

        TargetWatch(double closest) {
            this.closest = closest;
        }
    }

    private void watchProgress() {
        double h = Objects.requireNonNull(goal).heuristic(engine.getEntityContext().feetPos());
        if (h < bestHeuristic - 1.0) {
            bestHeuristic = h;
            sinceProgress = 0;
        } else if (++sinceProgress > STALL_TICKS) {
            cancelEngine(engine);
            fail(FailKind.STUCK, reason("stuck: no progress for " + STALL_TICKS + " ticks"));
        }
    }

    private String reason(String fallback) {
        String r = engine == null ? null : engine.getLastFailure();
        if (r == null && engine != null) r = engine.getLastMessage();
        return r == null || r.isBlank() ? fallback : r;
    }

    private int countHeld() {
        int n = 0;
        for (Item item : mineItems) n += body.getInventory().countItem(item);
        return n;
    }

    private void finish() {
        state = State.DONE;
        failure = null;
        kind = null;
        releaseInput();
        NavRuntime.LOGGER.debug("{}: navigation done", body.getGameProfile().getName());
    }

    private void fail(FailKind why, String text) {
        if (engine != null && ownsBody(engine)) cancelEngine(engine);
        state = State.FAILED;
        failure = text;
        failKind = why;
        kind = null;
        releaseInput();
        NavRuntime.LOGGER.debug("{}: navigation failed: {}", body.getGameProfile().getName(), text);
    }

    /**
     * When the engine stops driving: a body keeps its last input until someone clears it, and a break it had begun
     * stays begun in the game mode (the engine is no longer ticked, so it will never send the abort a player's
     * released mouse button would).
     */
    private void releaseInput() {
        if (body.getInteractionManager() instanceof BodyInteractionManager breaking) breaking.abortBreak();
        if (!inputDirty) return;
        inputDirty = false;
        body.input().reset();
    }
}
