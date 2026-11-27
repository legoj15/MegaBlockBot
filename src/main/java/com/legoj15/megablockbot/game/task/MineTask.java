package com.legoj15.megablockbot.game.task;

import com.legoj15.megablockbot.MegaBlockBot;
import com.legoj15.megablockbot.core.ItemKey;
import com.legoj15.megablockbot.core.Pos;
import com.legoj15.megablockbot.core.ToolClass;
import com.legoj15.megablockbot.core.ToolTier;
import com.legoj15.megablockbot.core.goal.Goal;
import com.legoj15.megablockbot.core.plan.NodeSpec;
import com.legoj15.megablockbot.core.task.Failure;
import com.legoj15.megablockbot.core.task.TickResult;
import com.legoj15.megablockbot.game.body.BotPlayer;
import com.legoj15.megablockbot.game.nav.BodyInteractionManager;
import com.legoj15.megablockbot.game.nav.BotNavigator;
import com.legoj15.megablockbot.game.nav.BreakFilter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;

/**
 * {@code mine}: break blocks until the body holds the goal's count of an item (design 4.3, Mine block). The count is
 * the item's, not the block's: stone drops cobblestone and an ore drops a gem.
 *
 * <p>Parameters from the planner: {@code item} and {@code block} (a registry id; several blocks as a comma-separated
 * list), {@code count} (how many more, used only when the goal is not a {@link Goal.HaveItem} of the item: the goal's
 * count is absolute and wins); when the source has them {@code minY}, {@code maxY}, {@code bestY} (where the block
 * generates and where it is densest) and, written only when the tool is required, {@code toolClass} and
 * {@code minTier}. The leaf's source id rides along in {@code NoSource} so a replan excludes exactly that source.
 *
 * <p>The task:
 * <ol>
 *   <li>is done at once when the body already holds the count, before it touches anything;</li>
 *   <li>fails {@code MissingTool} when a tool is required and the body carries none good enough;</li>
 *   <li>mines through the navigator. When the navigator reports nothing to mine in range it looks further: first at the
 *       best height when the block has a height range (digging down is allowed), then outward from where the task
 *       started, in steps, mining again after each. The search is bounded by {@link Search}: past it the failure is
 *       {@code NoSource} saying how far it looked;</li>
 *   <li>fails {@code Protected} when what the navigator could not mine is blocks of the kind the break rules forbid for
 *       this body (checked in the loaded area around the body, only when the navigator found nothing), and {@code Unreachable}
 *       for any other failure of the navigator.</li>
 * </ol>
 *
 * <p><b>Blocks it cannot get at.</b> The navigator sets a block aside when the body has headed for it for
 * {@link Search#targetStallTicks} ticks without getting closer, or when the engine finds no path to it. The task keeps
 * those blocks for its whole life, whatever commands it gives the navigator (and in its checkpoint, so a retry does not
 * go back to them). The task also watches itself: when neither the count held nor the best distance to a known block
 * has improved for {@link Search#noGainTicks} ticks it sets every block the navigator knows aside (a stalled round) and
 * looks elsewhere, as it does when the navigator finds nothing. After {@link Search#maxStalledRounds} stalled rounds, or
 * when the search is spent, it fails {@code NoSource} either way: with blocks set aside (there were some, and none could
 * be got at -- blocks withheld for want of air are set aside with them) the failure says how many were found and carries
 * the leaf's source, which the executor puts in the goal's exclusions so the next plan gathers the item another way;
 * with none ever known it says how far it looked. {@code Unreachable} stays for a place the body could not get to, the
 * descent to {@code bestY}.
 *
 * <p>There is no clock of the task's own beyond that: a step of the search is bounded in ticks only so that it ends,
 * and the executor's budgets decide how long the whole task may take.
 */
public final class MineTask extends BodyTask {

    /**
     * How hard the task looks for the block when the navigator finds none in range. A step is one stretch of exploring
     * followed by another try at mining.
     *
     * @param stepBlocks         horizontal distance after which an exploring step ends
     * @param stepTicks          ticks after which an exploring step ends whatever the body covered
     * @param maxExploredBlocks  horizontal distance explored over all steps before the search gives up
     * @param maxAttempts        steps (and detours) before the search gives up
     * @param targetStallTicks   ticks the navigator may head for one block without getting closer before it sets it aside
     * @param noGainTicks        ticks without a gain in the count held or in the best distance to a known block before
     *                           the task sets every block it knows aside (a stalled round)
     * @param maxStalledRounds   stalled rounds after which the task fails
     */
    public record Search(int stepBlocks, int stepTicks, int maxExploredBlocks, int maxAttempts, int targetStallTicks,
                         int noGainTicks, int maxStalledRounds) {
        /** 20 s per block, 30 s per round (a cluster of blocks is set aside together), three rounds. */
        public static final Search DEFAULT = new Search(32, 600, 256, 12, BotNavigator.TARGET_STALL_TICKS, 600, 3);

        /** The search bounds with the stall bounds of {@link #DEFAULT}. */
        public Search(int stepBlocks, int stepTicks, int maxExploredBlocks, int maxAttempts) {
            this(stepBlocks, stepTicks, maxExploredBlocks, maxAttempts, BotNavigator.TARGET_STALL_TICKS, 600, 3);
        }
    }

    /** How far around the body the task looks for blocks the break rules forbid, when the navigator found none. */
    private static final int PROTECTED_SCAN_RADIUS = 32;
    /** Within this many blocks of the best height the body does not bother to go to it. */
    private static final int BEST_Y_SLACK = 2;
    /** How close to the place it last mined a restarted task goes first. */
    private static final int LAST_MINED_RADIUS = 3;
    /** Getting this much closer to a block, in blocks, counts as a gain; less is the body swaying about. */
    private static final double CLOSER_BY = 1.0;
    /** The most set-aside blocks a checkpoint keeps (those nearest to where the task began). */
    private static final int MAX_SAVED_SET_ASIDE = 64;
    /** The most set-aside blocks a failure's text names. */
    private static final int MAX_NAMED_SET_ASIDE = 3;

    private enum Phase { MINING, TO_LAST_MINED, TO_BEST_Y, EXPLORING }

    private final Search search;
    private final String sourceId;
    private final ItemKey itemKey;
    private final String blockLabel;
    private final List<ResourceLocation> blockIds = new ArrayList<>();
    private final int goalCount;
    private final int moreCount;
    @Nullable private final Integer minY;
    @Nullable private final Integer maxY;
    @Nullable private final Integer bestY;
    @Nullable private final ToolClass toolClass;
    private final ToolTier minTier;

    // From an earlier task for the same node: hints for where to look, checked against the world before use.
    private final String hintDimension;
    @Nullable private final Pos hintOrigin;
    private final int hintExplored;
    @Nullable private final Pos hintLastMined;
    private final List<Pos> hintSetAside;

    private Item item;
    private final List<Block> blocks = new ArrayList<>();
    private String dimension = "";
    private int target;
    private boolean alreadyDone;
    private Phase phase = Phase.MINING;
    private BlockPos origin;
    private int attempts;
    private double exploredBlocks;
    private double lookedBlocks;
    private boolean triedLastMined;
    private boolean triedBestY;
    /** Blocks the task will not head for again: the navigator reads it (from search threads too) and adds to it. */
    private final Set<BlockPos> setAside = ConcurrentHashMap.newKeySet();
    private int bestHeld;
    private double bestTargetDistance = Double.POSITIVE_INFINITY;
    private int noGainTicks;
    private int stalledRounds;
    @Nullable private BlockPos headingFor;
    /** What the navigator is waiting for, read at the start of each tick for the status phrase. */
    private BotNavigator.Wait navWait = BotNavigator.Wait.NONE;
    @Nullable private BlockPos lastMined;
    private int seenBroken;
    private Vec3 stepFrom = Vec3.ZERO;
    private int stepTicks;
    private boolean waiting;

    public MineTask(NodeSpec leaf, Map<String, String> hints, Search search) {
        this.search = search;
        this.sourceId = leaf.source();
        this.itemKey = ItemKey.of(required(leaf, "item"));
        String blockText = required(leaf, "block");
        this.blockLabel = blockText;
        for (String part : blockText.split(",")) {
            ResourceLocation id = idOf(part);
            if (id != null) blockIds.add(id);
        }
        this.goalCount = leaf.goal() instanceof Goal.HaveItem g && g.key().equals(itemKey) ? g.count() : -1;
        this.moreCount = intParam(leaf.params(), "count", 1);
        this.minY = optionalInt(leaf.params().get("minY"));
        this.maxY = optionalInt(leaf.params().get("maxY"));
        this.bestY = optionalInt(leaf.params().get("bestY"));
        String toolText = leaf.params().get("toolClass");
        this.toolClass = toolText == null || toolText.isBlank() ? null : ToolClass.valueOf(toolText.trim().toUpperCase(Locale.ROOT));
        String tierText = leaf.params().get("minTier");
        this.minTier = tierText == null || tierText.isBlank() ? ToolTier.HAND : ToolTier.valueOf(tierText.trim().toUpperCase(Locale.ROOT));

        this.hintDimension = hints.getOrDefault("dim", "");
        this.hintOrigin = hintPos(hints.get("origin"));
        this.hintExplored = Math.max(0, intHint(hints.get("explored")));
        this.hintLastMined = hintPos(hints.get("lastMined"));
        this.hintSetAside = hintPositions(hints.get("setAside"));
    }

    public MineTask(NodeSpec leaf, Map<String, String> hints) {
        this(leaf, hints, Search.DEFAULT);
    }

    @Nullable
    private static Integer optionalInt(@Nullable String text) {
        if (text == null || text.isBlank()) return null;
        return Integer.valueOf(text.trim());
    }

    private static int intHint(@Nullable String text) {
        try {
            return text == null ? 0 : Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return 0; // a checkpoint that does not validate is dropped, not trusted
        }
    }

    @Nullable
    private static Pos hintPos(@Nullable String text) {
        if (text == null) return null;
        try {
            return Pos.parse(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Positions as {@code x,y,z} joined by semicolons; what does not read is dropped (a checkpoint is only a hint). */
    private static List<Pos> hintPositions(@Nullable String text) {
        List<Pos> out = new ArrayList<>();
        if (text == null) return out;
        for (String part : text.split(";")) {
            Pos pos = hintPos(part);
            if (pos != null) out.add(pos);
        }
        return out;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Start
    // ---------------------------------------------------------------------------------------------------------

    @Override
    protected void begin(BotPlayer body) {
        dimension = dimensionOf(body);
        ResourceLocation itemId = idOf(itemKey.id());
        item = itemId == null ? null : BuiltInRegistries.ITEM.getOptional(itemId).orElse(null);
        if (item == null) {
            fail(new Failure.NoSource(itemKey, sourceId, "'" + itemKey.id() + "' is not a registered item"));
            return;
        }
        for (ResourceLocation id : blockIds) BuiltInRegistries.BLOCK.getOptional(id).ifPresent(blocks::add);
        if (blocks.isEmpty()) {
            fail(new Failure.NoSource(itemKey, sourceId, "no registered block in '" + blockLabel + "' to mine " + itemKey.id() + " from"));
            return;
        }

        int held = held(body);
        target = goalCount > 0 ? goalCount : held + Math.max(1, moreCount);
        if (held >= target) {
            // Nothing to do, and nothing is touched: the first tick says so.
            alreadyDone = true;
            return;
        }
        if (toolClass != null && !HeldTools.has(body, toolClass, minTier)) {
            fail(new Failure.MissingTool(toolClass, minTier, "mining " + blockLabel + " needs a " + minTier + " " + toolClass
                + " or better, and the body carries none"));
            return;
        }

        boolean sameWorld = hintDimension.equals(dimension);
        origin = sameWorld && hintOrigin != null ? blockPos(hintOrigin) : body.blockPosition();
        if (sameWorld) {
            exploredBlocks = Math.min(hintExplored, search.maxExploredBlocks());
            // What an earlier task for this node could not get at is not tried again. The navigator looks at the world
            // all the same, so a block that is no longer there costs nothing.
            for (Pos aside : hintSetAside) setAside.add(blockPos(aside));
        }
        bestHeld = held;
        startMining(body);
    }

    @Override
    protected void resumeWork(BotPlayer body) {
        // Everything is looked at again: how much the body holds now, where the blocks are, which way it faces.
        startMining(body);
    }

    private int held(BotPlayer body) {
        return body.getInventory().countItem(item);
    }

    private void startMining(BotPlayer body) {
        phase = Phase.MINING;
        // A new command is a new set of blocks to measure the approach to.
        bestTargetDistance = Double.POSITIVE_INFINITY;
        noGainTicks = 0;
        nav(body).mine(blocks, List.of(item), Math.max(1, target - held(body)), setAside, search.targetStallTicks());
    }

    // ---------------------------------------------------------------------------------------------------------
    // Tick
    // ---------------------------------------------------------------------------------------------------------

    @Override
    protected TickResult onTick(BotPlayer body) {
        if (alreadyDone) return TickResult.COMPLETED;
        // The goal first, whatever got the items into the inventory (a block this task mined, a drop picked up on the way).
        if (held(body) >= target) {
            release(body);
            return TickResult.COMPLETED;
        }
        noteProgress(body);
        BotNavigator nav = nav(body);
        waiting = nav.breaking();
        navWait = nav.waitState();
        headingFor = phase == Phase.MINING ? nav.focusTarget() : null;
        if (phase == Phase.MINING && nav.state() == BotNavigator.State.WORKING) {
            TickResult stalled = watchGain(body, nav);
            if (stalled != null) return stalled;
        }
        return switch (phase) {
            case MINING -> tickMining(body, nav);
            case TO_LAST_MINED, TO_BEST_Y -> tickGoingTo(body, nav);
            case EXPLORING -> tickExploring(body, nav);
        };
    }

    private TickResult tickMining(BotPlayer body, BotNavigator nav) {
        switch (nav.state()) {
            case DONE, IDLE -> {
                // The navigator's count is reached or it was stopped from elsewhere while this task still wants more.
                startMining(body);
                return TickResult.RUNNING;
            }
            case FAILED -> {
                return miningFailed(body, nav);
            }
            default -> {
                return TickResult.RUNNING;
            }
        }
    }

    private TickResult miningFailed(BotPlayer body, BotNavigator nav) {
        BotNavigator.FailKind kind = nav.failKind();
        String reason = nav.failure();
        if (kind != BotNavigator.FailKind.NOTHING_TO_MINE) {
            release(body);
            return TickResult.failed(unreachable(blockLabel, reason, "could not mine " + blockLabel + ": " + reason));
        }
        MegaBlockBot.LOGGER.info("{}: the navigator found nothing to mine of {}: {} ({} block(s) set aside)",
            body.getGameProfile().getName(), blockLabel, reason, setAside.size());
        BlockPos forbidden = forbiddenTarget(body);
        if (forbidden != null) {
            release(body);
            return TickResult.failed(new Failure.Protected(corePos(forbidden), "break rules",
                "the " + blockLabel + " near " + forbidden.toShortString() + " may not be broken"));
        }
        return nextStep(body);
    }

    /** The navigator found nothing to mine in range: look further, or say there is nothing to be found. */
    private TickResult nextStep(BotPlayer body) {
        attempts++;
        if (attempts > search.maxAttempts() || exploredBlocks >= search.maxExploredBlocks()) return giveUp(body);
        BlockPos here = body.blockPosition();
        if (hintLastMined != null && !triedLastMined && hintDimension.equals(dimension)
            && hintLastMined.distSqXZ(corePos(here)) > (long) LAST_MINED_RADIUS * LAST_MINED_RADIUS) {
            triedLastMined = true;
            phase = Phase.TO_LAST_MINED;
            nav(body).goTo(blockPos(hintLastMined), LAST_MINED_RADIUS);
            return TickResult.RUNNING;
        }
        Integer wantY = targetY();
        if (wantY != null && !triedBestY && Math.abs(here.getY() - wantY) > BEST_Y_SLACK) {
            triedBestY = true;
            phase = Phase.TO_BEST_Y;
            nav(body).goToY(wantY);
            return TickResult.RUNNING;
        }
        phase = Phase.EXPLORING;
        stepFrom = body.position();
        stepTicks = 0;
        if (wantY != null) nav(body).exploreAtY(origin, wantY);
        else nav(body).explore(origin);
        return TickResult.RUNNING;
    }

    /** Where to dig to for a block that generates in a height range, or null when there is no range to go by. */
    @Nullable
    private Integer targetY() {
        if (bestY != null) return bestY;
        if (minY != null && maxY != null) return (minY + maxY) / 2;
        return null;
    }

    private TickResult tickGoingTo(BotPlayer body, BotNavigator nav) {
        switch (nav.state()) {
            case DONE, IDLE -> {
                startMining(body);
                return TickResult.RUNNING;
            }
            case FAILED -> {
                if (phase == Phase.TO_LAST_MINED) {
                    // Only a hint of where it was; the search goes on from here.
                    startMining(body);
                    return TickResult.RUNNING;
                }
                String reason = nav.failure();
                release(body);
                return TickResult.failed(unreachable("y=" + targetY(), reason,
                    "could not get to y=" + targetY() + " to mine " + blockLabel + ": " + reason));
            }
            default -> {
                return TickResult.RUNNING;
            }
        }
    }

    private TickResult tickExploring(BotPlayer body, BotNavigator nav) {
        stepTicks++;
        double moved = horizontal(body.position(), stepFrom);
        if (nav.state() == BotNavigator.State.FAILED) {
            // No path toward anything unexplored: there is nowhere further to look.
            exploredBlocks += moved;
            return giveUp(body);
        }
        if (nav.state() == BotNavigator.State.WORKING && moved < search.stepBlocks() && stepTicks < search.stepTicks()) {
            return TickResult.RUNNING;
        }
        exploredBlocks += moved;
        startMining(body);
        return TickResult.RUNNING;
    }

    /**
     * Whether the body is getting anywhere: it is while the count it holds, or the best distance it has reached to a
     * block it knows of, keeps improving, or while it is breaking a block (progress that neither of those shows yet --
     * the navigator's own watch counts it too). When none of that has happened for {@link Search#noGainTicks} ticks,
     * whatever the body is doing (one that keeps climbing out of a cave and falling back in moves all the time, so the
     * executor's watchdog never fires), the blocks it knows of are set aside and it looks elsewhere. Null while it is
     * getting somewhere.
     */
    @Nullable
    private TickResult watchGain(BotPlayer body, BotNavigator nav) {
        boolean gain = nav.breaking();
        int now = held(body);
        if (now > bestHeld) {
            bestHeld = now;
            gain = true;
        }
        double nearest = nav.nearestTargetDistance();
        if (!Double.isNaN(nearest) && nearest < bestTargetDistance - CLOSER_BY) {
            bestTargetDistance = nearest;
            gain = true;
        }
        if (gain) {
            noGainTicks = 0;
            return null;
        }
        if (++noGainTicks < search.noGainTicks()) return null;

        List<BlockPos> known = nav.knownTargets();
        setAside.addAll(known);
        stalledRounds++;
        MegaBlockBot.LOGGER.info("{}: mining {} made no gain in {} ticks (round {} of {}); set aside the {} block(s) it knew of, {} in all",
            body.getGameProfile().getName(), blockLabel, search.noGainTicks(), stalledRounds, search.maxStalledRounds(), known.size(),
            setAside.size());
        if (stalledRounds >= search.maxStalledRounds()) return giveUp(body);
        return nextStep(body);
    }

    /**
     * The search is spent. Blocks of the kind were known and none could be got at: {@code NoSource} carrying the leaf's
     * item and source, saying how many were found and where, and what was tried -- the executor puts the source in the
     * goal's exclusions, so the next plan gathers the item another way instead of walking the same leaf again. None was
     * ever known: {@code NoSource} too, saying how far it looked.
     */
    private TickResult giveUp(BotPlayer body) {
        release(body);
        if (setAside.isEmpty()) {
            String detail = String.format(Locale.ROOT, "found no %s to get %s from: looked about %d blocks around %s (explored %d blocks in %d attempts)",
                blockLabel, itemKey.id(), (int) Math.ceil(lookedBlocks), origin.toShortString(), (int) exploredBlocks, attempts);
            MegaBlockBot.LOGGER.info("{}: gave up mining {}: {}", body.getGameProfile().getName(), blockLabel, detail);
            return TickResult.failed(new Failure.NoSource(itemKey, sourceId, detail));
        }
        List<BlockPos> named = setAside.stream().sorted(Comparator.comparingDouble(origin::distSqr)).limit(MAX_NAMED_SET_ASIDE).toList();
        StringBuilder where = new StringBuilder();
        for (BlockPos at : named) {
            if (where.length() > 0) where.append("; ");
            where.append(at.getX()).append(' ').append(at.getY()).append(' ').append(at.getZ());
        }
        String detail = String.format(Locale.ROOT,
            "found %d %s block(s) and could not get at any of them (nearest: %s); a block was set aside after %d ticks without getting closer to it"
                + ", when the engine found no path to it, or when it could only be taken under water and the air would not come back;"
                + " %d stalled round(s) of %d ticks; explored %d blocks in %d attempts around %s and found no other",
            setAside.size(), blockLabel, where, search.targetStallTicks(), stalledRounds, search.noGainTicks(), (int) exploredBlocks, attempts,
            origin.toShortString());
        MegaBlockBot.LOGGER.info("{}: gave up mining {}: {}", body.getGameProfile().getName(), blockLabel, detail);
        return TickResult.failed(new Failure.NoSource(itemKey, sourceId, detail));
    }

    private void noteProgress(BotPlayer body) {
        if (body.getInteractionManager() instanceof BodyInteractionManager manager && manager.blocksBroken() != seenBroken) {
            seenBroken = manager.blocksBroken();
            if (manager.lastBrokenPos() != null) lastMined = manager.lastBrokenPos();
        }
        lookedBlocks = Math.max(lookedBlocks, horizontal(body.position(), Vec3.atBottomCenterOf(origin)));
    }

    private static double horizontal(Vec3 a, Vec3 b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Protected targets
    // ---------------------------------------------------------------------------------------------------------

    /**
     * The nearest block of the kind that the break rules forbid for this body, when the loaded area around the body holds
     * such blocks and none it may break; null otherwise. The per-body rules, as the navigator is armed with them
     * ({@code BreakRules.filterFor}), not only the general ones: a block that is a player's is refused for this body
     * alone, and that is as protected as any the general rules refuse. Asked only when the navigator found nothing to
     * mine, because that is when "there are blocks, but not for us" is the answer to give. Loaded chunks only: a read
     * never loads one.
     */
    @Nullable
    private BlockPos forbiddenTarget(BotPlayer body) {
        ServerLevel level = body.serverLevel();
        BreakFilter rules = BreakRules.filterFor(body);
        Set<Block> kinds = new HashSet<>(blocks);
        BlockPos center = body.blockPosition();
        int r = PROTECTED_SCAN_RADIUS;
        int lowY = Math.max(level.getMinBuildHeight(), center.getY() - r);
        int highY = Math.min(level.getMaxBuildHeight() - 1, center.getY() + r);
        BlockPos forbidden = null;
        double nearest = Double.MAX_VALUE;
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int chunkX = (center.getX() - r) >> 4; chunkX <= (center.getX() + r) >> 4; chunkX++) {
            for (int chunkZ = (center.getZ() - r) >> 4; chunkZ <= (center.getZ() + r) >> 4; chunkZ++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
                if (chunk == null) continue;
                int fromX = Math.max(chunkX << 4, center.getX() - r);
                int toX = Math.min((chunkX << 4) + 15, center.getX() + r);
                int fromZ = Math.max(chunkZ << 4, center.getZ() - r);
                int toZ = Math.min((chunkZ << 4) + 15, center.getZ() + r);
                for (int x = fromX; x <= toX; x++) {
                    for (int z = fromZ; z <= toZ; z++) {
                        for (int y = lowY; y <= highY; y++) {
                            at.set(x, y, z);
                            BlockState state = chunk.getBlockState(at);
                            if (!kinds.contains(state.getBlock())) continue;
                            if (mayBreak(rules, level, at, state)) return null; // one may be broken: not a protection problem
                            double away = at.distSqr(center);
                            if (away < nearest) {
                                nearest = away;
                                forbidden = at.immutable();
                            }
                        }
                    }
                }
            }
        }
        return forbidden;
    }

    private static boolean mayBreak(BreakFilter rules, ServerLevel level, BlockPos pos, BlockState state) {
        try {
            return rules.mayBreak(level, pos, state);
        } catch (RuntimeException e) {
            return false; // a filter that throws is a no, as everywhere else (BreakFilter)
        }
    }

    // ---------------------------------------------------------------------------------------------------------

    /**
     * The blocks this task will not head for again, as plain positions equal to the blocks' (what its checkpoint
     * writes, and what it hands a restart); a copy. For tests and status.
     */
    public Set<BlockPos> setAside() {
        return Set.copyOf(setAside);
    }

    @Override
    public boolean waiting() {
        return waiting;
    }

    @Override
    protected void letGo(BotPlayer body) {
        waiting = false;
    }

    /**
     * Where it last mined and how far it has looked, so a restart does not search from scratch: the dimension, the
     * centre of the search, the distance explored so far and the last block broken. Hints only; a restarted task checks
     * them against the world.
     */
    @Override
    public Map<String, String> checkpoint() {
        Map<String, String> out = new LinkedHashMap<>();
        if (origin == null) return out;
        out.put("dim", dimension);
        out.put("origin", corePos(origin).encode());
        out.put("explored", Integer.toString((int) exploredBlocks));
        if (lastMined != null) out.put("lastMined", corePos(lastMined).encode());
        else if (hintLastMined != null) out.put("lastMined", hintLastMined.encode());
        if (!setAside.isEmpty()) {
            StringBuilder aside = new StringBuilder();
            setAside.stream().sorted(Comparator.comparingDouble(origin::distSqr)).limit(MAX_SAVED_SET_ASIDE).forEach(at -> {
                if (aside.length() > 0) aside.append(';');
                aside.append(corePos(at).encode());
            });
            out.put("setAside", aside.toString());
        }
        return out;
    }

    /** What the task is doing now, for the status line: where it is heading, what it waits for, and how many blocks it has given up on. */
    @Override
    public String progress() {
        String block = blockLabel.replace("minecraft:", "");
        String what = switch (phase) {
            case MINING -> switch (navWait) {
                // Truthful while the navigator bridges a gap the engine left: no block is being headed for just now.
                case FOR_AIR -> "waiting for its air to come back";
                case TO_LOOK_AGAIN -> "waiting to look again";
                case NONE -> headingFor != null
                    ? "heading for a block at " + headingFor.getX() + " " + headingFor.getY() + " " + headingFor.getZ()
                    : "looking for " + block;
            };
            case TO_LAST_MINED -> "going back to where it last mined";
            case TO_BEST_Y -> "going to y=" + targetY() + " to look for " + block;
            case EXPLORING -> "exploring for " + block;
        };
        int aside = setAside.size();
        return aside == 0 ? what : what + "; " + aside + " block" + (aside == 1 ? "" : "s") + " set aside as out of reach";
    }
}
