package com.legoj15.megablockbot.game.nav;

import com.legoj15.megablockbot.game.body.BotPlayer;
import com.legoj15.megablockbot.nav.NavRuntime;
import com.legoj15.megablockbot.nav.engine.Baritone;
import com.legoj15.megablockbot.nav.engine.api.IBaritone;
import com.legoj15.megablockbot.nav.engine.api.Settings;
import com.legoj15.megablockbot.nav.engine.api.pathing.calc.IPath;
import com.legoj15.megablockbot.nav.engine.api.pathing.goals.Goal;
import com.legoj15.megablockbot.nav.engine.api.pathing.goals.GoalBlock;
import com.legoj15.megablockbot.nav.engine.api.pathing.goals.GoalNear;
import com.legoj15.megablockbot.nav.engine.api.pathing.goals.GoalXZ;
import com.legoj15.megablockbot.nav.engine.api.pathing.goals.GoalYLevel;
import com.legoj15.megablockbot.nav.engine.api.selection.ISelectionManager;
import com.legoj15.megablockbot.nav.engine.api.utils.IInteractionController;
import com.legoj15.megablockbot.nav.engine.api.utils.IMineTargetPolicy;
import com.legoj15.megablockbot.nav.engine.behavior.PathingBehavior;
import com.legoj15.megablockbot.nav.engine.pathing.path.PathExecutor;
import com.legoj15.megablockbot.nav.engine.process.MineProcess;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.Clearable;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
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
     * Ticks a mine command waits for the body's air to fill again when the only blocks it knows are ones it would take
     * standing in or under water. Filling from empty takes about 75 ticks once the eyes are out of the water, and a
     * body still in it loses air all the while, so the wait is counted only from the tick the eyes are out (see
     * {@link #waitForAir}); this leaves room for the air to be coming back from a fall as well.
     */
    static final int AIR_WAIT_TICKS = 200;
    /** Ticks between a scan that found nothing and the second look (see {@link #lookAgain}). */
    static final int LOOK_AGAIN_TICKS = 10;
    /** How far up and around from the body the navigator looks for a place to get its head out of the water. */
    private static final int SURFACE_RADIUS = 4;
    /** Getting this much closer to a block, in blocks, counts as progress; less is the body swaying about. */
    private static final double CLOSER_BY = 1.0;
    /** Path cost per point of fall damage: about one second of walking per half heart, so a long fall is a last resort. */
    private static final double FALL_DAMAGE_PENALTY = 20.0;

    private static final Map<UUID, BotNavigator> NAVIGATORS = new HashMap<>();
    private static List<Block> avoidBreaking;

    private enum Kind { GOTO, MINE, FOLLOW, EXPLORE }

    private BotPlayer body;
    @Nullable
    private Baritone engine;
    private State state = State.IDLE;
    @Nullable
    private String failure;
    @Nullable
    private FailKind failKind;
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
    private int airWaitTicks;
    /** The engine gave up on exactly one block in this command and the command was started again once for it. */
    private boolean retriedGiveUp;
    /** How many times the engine, finding nothing, was asked again in this command, and the ticks waited for the next. */
    private int looksAgain;
    private int lookAgainTicks;
    /** Set by the target policy: blocks that are known but only taken under water are held back until the air is full. */
    private volatile boolean airWithheld;
    /** The blocks the policy is holding back for air: what a give-up on them sets aside. */
    private volatile List<BlockPos> withheldForAir = List.of();
    /** Set by the target policy once it lets a block under water through, so that the air falling on the way does not stop it. */
    private volatile boolean wetCommitted;
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
     * for that air is made out of the water (see {@link #waitForAir}), and the blocks are set aside when it cannot be.
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
        airWaitTicks = 0;
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
        configureExplore(begin(Kind.EXPLORE), -1).getExploreProcess().explore(origin.getX(), origin.getZ());
    }

    /** As {@link #explore}, but keeps to the height {@code y} where the terrain allows (underground ore searches). */
    public void exploreAtY(BlockPos origin, int y) {
        configureExplore(begin(Kind.EXPLORE), y).getExploreProcess().explore(origin.getX(), origin.getZ());
    }

    private static Baritone configureExplore(Baritone e, int maintainY) {
        e.settings().exploreMaintainY.set(maintainY);
        return e;
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
        state = State.IDLE;
        failure = null;
        failKind = null;
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
                if (!engine.getExploreProcess().isActive()) {
                    // The process also ends when it has nothing left to explore, which is a success. It records a
                    // failure reason only when its search for a path failed.
                    if (engine.getLastFailure() != null) fail(FailKind.EXPLORE_FAILED, reason("exploration failed"));
                    else finish();
                }
            }
        }
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
        waiting = Wait.NONE;
        failure = null;
        failKind = null;
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
        s.acceptableThrowawayItems.set(new ArrayList<>(List.of(
            Items.DIRT, Items.COARSE_DIRT, Items.COBBLESTONE, Items.COBBLED_DEEPSLATE, Items.NETHERRACK,
            Items.ANDESITE, Items.DIORITE, Items.GRANITE, Items.TUFF, Items.BLACKSTONE, Items.BASALT)));
        s.blocksToAvoidBreaking.set(new ArrayList<>(avoidBreaking()));
        applyBreakFilter(e);
    }

    private void applyBreakFilter(Baritone e) {
        BreakFilter filter = breakFilter;
        e.setBreakFilter((level, pos, state) -> level instanceof ServerLevel server && filter.mayBreak(server, pos, state));
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
            if (airWithheld && waitForAir(process)) {
                waiting = Wait.FOR_AIR;
                return;
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
     * The engine ended its command because every block it knows is one the policy holds back for want of air. The body
     * waits for its air and then starts the engine's mine process afresh, for up to {@link #AIR_WAIT_TICKS} ticks --
     * but out of the water: a body whose eyes are in it loses air all the while and can drown while it "waits for
     * air", so the head is got out first, with a short {@code goTo} to the nearest place it can breathe at (the surface
     * of the water column it is in is the usual answer), and the wait is counted only from the tick the eyes are out.
     * The {@code goTo} ends this command; the task that gave it starts the mine again once the body is where it can
     * breathe, and the wait picks up from there. Nowhere in range to get the head out, or air that does not come back
     * in the bound, and the wet targets are given up for this command. True while it waits.
     */
    private boolean waitForAir(MineProcess process) {
        if (body.isEyeInFluid(FluidTags.WATER)) {
            BlockPos out = placeToBreathe();
            if (out == null) {
                giveUpWetTargets("nowhere within " + SURFACE_RADIUS + " blocks to get the head out of the water");
                return false;
            }
            goTo(out, 0);
            return true;
        }
        if (++airWaitTicks > AIR_WAIT_TICKS) {
            giveUpWetTargets("the air did not come back within " + AIR_WAIT_TICKS + " ticks of getting out of the water");
            return false;
        }
        if (body.getAirSupply() >= body.getMaxAirSupply()) {
            airWithheld = false;
            process.mine(0, mineBlocks.toArray(Block[]::new));
        }
        return true;
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
     * that are set aside; dry ones before any the body would take standing in or under water; and those only when the
     * body's air is full, or when it has already started for one (the air falls on the way there).
     */
    private List<BlockPos> selectTargets(List<BlockPos> candidates, Function<BlockPos, BlockState> blocks) {
        Set<BlockPos> aside = setAside;
        List<BlockPos> open = new ArrayList<>(candidates.size());
        List<BlockPos> dry = new ArrayList<>(candidates.size());
        for (BlockPos candidate : candidates) {
            if (aside.contains(plain(candidate))) continue;
            open.add(candidate);
            if (!wet(candidate, blocks)) dry.add(candidate);
        }
        if (!dry.isEmpty()) {
            wetCommitted = false;
            airWithheld = false;
            return dry;
        }
        if (open.isEmpty()) return open;
        if (wetCommitted || body.getAirSupply() >= body.getMaxAirSupply()) {
            wetCommitted = true;
            airWithheld = false;
            return open;
        }
        airWithheld = true;
        withheldForAir = List.copyOf(open);
        return List.of();
    }

    /**
     * Water in the working column -- the block's own cell and the two cells above and the two below it, and no cell that
     * merely touches it from the side: the engine's mine goal puts the body's feet at the block or one or two below it,
     * so these are the cells its feet and head can be in, while water beside the block never touches the body at all.
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
