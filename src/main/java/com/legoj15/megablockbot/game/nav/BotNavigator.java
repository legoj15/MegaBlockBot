package com.legoj15.megablockbot.game.nav;

import com.legoj15.megablockbot.game.body.BotPlayer;
import com.legoj15.megablockbot.nav.NavRuntime;
import com.legoj15.megablockbot.nav.engine.Baritone;
import com.legoj15.megablockbot.nav.engine.api.IBaritone;
import com.legoj15.megablockbot.nav.engine.api.Settings;
import com.legoj15.megablockbot.nav.engine.api.pathing.goals.Goal;
import com.legoj15.megablockbot.nav.engine.api.pathing.goals.GoalBlock;
import com.legoj15.megablockbot.nav.engine.api.pathing.goals.GoalNear;
import com.legoj15.megablockbot.nav.engine.api.pathing.goals.GoalXZ;
import com.legoj15.megablockbot.nav.engine.api.pathing.goals.GoalYLevel;
import com.legoj15.megablockbot.nav.engine.api.selection.ISelectionManager;
import com.legoj15.megablockbot.nav.engine.api.utils.IInteractionController;
import com.legoj15.megablockbot.nav.engine.behavior.PathingBehavior;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Clearable;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;

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
    private double bestHeuristic;
    private int sinceProgress;
    private boolean inputDirty;

    private BotNavigator(BotPlayer body) {
        this.body = body;
    }

    /** The navigator of the body with this one's UUID, created on first use; it follows the body across respawns. */
    public static BotNavigator of(BotPlayer body) {
        BotNavigator navigator = NAVIGATORS.computeIfAbsent(body.getUUID(), id -> new BotNavigator(body));
        navigator.follow(body);
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
        if (blocks.isEmpty() || wanted.isEmpty()) throw new IllegalArgumentException("mine needs blocks and wanted items");
        Baritone e = begin(Kind.MINE);
        mineItems = new LinkedHashSet<>(wanted);
        mineTarget = countHeld() + Math.max(1, count);
        // The engine gets no count of its own (0 means mine until stopped): it would count a sample of the blocks'
        // drops, which is not what this command promises, and would stop early or never. tick() ends the command.
        e.getMineProcess().mine(0, blocks.toArray(Block[]::new));
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

    /** Cancels whatever runs and releases the body's input. The state goes back to IDLE. */
    public void stop() {
        if (engine != null) cancelEngine(engine);
        kind = null;
        goal = null;
        state = State.IDLE;
        failure = null;
        failKind = null;
        releaseInput();
    }

    public State state() {
        return state;
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
            case MINE -> {
                if (countHeld() >= mineTarget) {
                    // The engine runs with no count of its own (see mine): the count is reached here, and the engine
                    // is told to let go of the blocks it still had in mind.
                    cancelEngine(engine);
                    finish();
                } else if (!engine.getMineProcess().isActive()) {
                    fail(FailKind.NOTHING_TO_MINE, reason("nothing to mine in range"));
                }
            }
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
        cancelEngine(e);
        e.clearLastFailure();
        kind = newKind;
        goal = null;
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
