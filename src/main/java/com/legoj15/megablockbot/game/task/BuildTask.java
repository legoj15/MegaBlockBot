package com.legoj15.megablockbot.game.task;

import com.legoj15.megablockbot.core.ItemKey;
import com.legoj15.megablockbot.core.Pos;
import com.legoj15.megablockbot.core.goal.Goal;
import com.legoj15.megablockbot.core.plan.NodeSpec;
import com.legoj15.megablockbot.core.task.Failure;
import com.legoj15.megablockbot.core.task.TickResult;
import com.legoj15.megablockbot.game.body.BotPlayer;
import com.legoj15.megablockbot.game.build.Blueprint;
import com.legoj15.megablockbot.game.build.Blueprints;
import com.legoj15.megablockbot.game.nav.BotNavigator;
import com.legoj15.megablockbot.game.nav.BreakFilter;
import com.legoj15.megablockbot.game.plan.ItemKeys;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockState;

/**
 * {@code build} (issue 17): place a blueprint's blocks at its anchor through the navigator's build command, which
 * drives the engine's own builder. The task arms the per-body break filter as the mine task does, starts the build and
 * watches it.
 *
 * <p>Parameters: {@code blueprint} (its name), {@code dimension} and {@code anchor}; the leaf's goal is the
 * {@link Goal.Build} itself, so the executor judges the goal on the snapshot's report of it.
 *
 * <p>Ends:
 * <ul>
 *   <li>{@code Completed} when the navigator is done and every blueprint position holds its block (the block, not every
 *       state property, as the report counts it);</li>
 *   <li>{@code MissingInput} naming the item and the count short, when the builder ran out of a block: the executor
 *       replans, and the planner gets what is missing from the next report;</li>
 *   <li>{@code Protected} naming the position when a block in the way may not be broken;</li>
 *   <li>{@code Unreachable} for the navigator's other failures. {@code NoRoom} never applies: nothing is picked
 *       up.</li>
 * </ul>
 *
 * <p>It pauses, resumes and aborts like the mine task: the builder is stopped and the body's input reset by
 * {@link BodyTask}'s release, and a resumed task starts the build again -- the builder skips what stands.
 */
public final class BuildTask extends BodyTask {
    /**
     * Ticks without a placement or a break before a working build is called unable to go on: twenty seconds, the mine
     * task's own stall bound ({@code MineTask.Search}), and longer than any one placement or break of a plain block
     * takes once the body is there.
     */
    private static final int NO_GAIN_TICKS = 400;
    /** Getting this much closer to the anchor, in blocks, counts as gain; less is the body swaying about. */
    private static final double CLOSER_BY = 1.0;

    private final String blueprintName;
    private final String dimension;
    private final Pos anchor;

    @Nullable
    private Blueprint blueprint;
    @Nullable
    private BlockPos at;
    /** The level the build is in, kept for the status line (progress has no body to ask). */
    @Nullable
    private ServerLevel level;
    /** Placements plus breaks, the closest the body has come to the anchor, and how long none moved: the stalled watch. */
    private int seenProgress;
    private double bestApproach = Double.POSITIVE_INFINITY;
    private int noGainTicks;

    public BuildTask(NodeSpec leaf) {
        this.blueprintName = required(leaf, "blueprint");
        this.dimension = required(leaf, "dimension");
        this.anchor = Pos.parse(required(leaf, "anchor"));
        // The goal carries the same three, and the leaf may be a reach leaf's sibling; the parameters are the plan's.
        if (!(leaf.goal() instanceof Goal.Build)) {
            throw new IllegalArgumentException(leaf.taskKind() + " leaf's goal is not a Build goal");
        }
    }

    @Override
    protected void begin(BotPlayer body) {
        blueprint = Blueprints.load(body.getServer(), blueprintName);
        if (blueprint == null) {
            fail(new Failure.TargetGone(blueprintName, "the blueprint '" + blueprintName
                    + "' is not known: no blueprint of that name is loaded"));
            return;
        }
        if (!dimension.equals(dimensionOf(body))) {
            fail(unreachable(anchor.encode(), "different dimension", "the body is in " + dimensionOf(body)
                    + ", the build in " + dimension + "; dimension travel is not built"));
            return;
        }
        at = blockPos(anchor);
        level = body.serverLevel();
        startBuild(body);
    }

    @Override
    protected void resumeWork(BotPlayer body) {
        // Everything is looked at again from the world as it is now; the builder places only what does not stand.
        startBuild(body);
    }

    private void startBuild(BotPlayer body) {
        nav(body).build(blueprint, at);
    }

    @Override
    protected TickResult onTick(BotPlayer body) {
        Blueprint built = blueprint;
        BlockPos place = at;
        if (built == null || place == null) return TickResult.RUNNING; // begin failed; its failure is the answer
        ServerLevel level = body.serverLevel();
        BotNavigator nav = nav(body);
        switch (nav.state()) {
            case WORKING -> {
                // A refused break does not pause the builder (it keeps trying the same spot), so the task watches
                // itself, as the mine task does: no placement or break for a while is a build that cannot go on.
                TickResult stalled = watchGain(body);
                if (stalled != null) return stalled;
                return TickResult.RUNNING;
            }
            case IDLE -> {
                // Something else stopped the navigator while this task still owns the body: the build goes on.
                startBuild(body);
                return TickResult.RUNNING;
            }
            case DONE, FAILED -> {
                // Completed only when the whole structure stands: the builder's own "done" is a hint, like any task's.
                if (built.fullyBuilt(level, place)) {
                    release(body);
                    return TickResult.COMPLETED;
                }
                if (nav.state() == BotNavigator.State.DONE) {
                    // The builder said it was finished and the world disagrees; starting again asks it to look once more.
                    startBuild(body);
                    return TickResult.RUNNING;
                }
                release(body);
                return TickResult.failed(translate(body, nav, built, place));
            }
        }
        return TickResult.RUNNING;
    }

    /**
     * Whether the build is getting anywhere: it is while the body places or breaks anything, and while it is still
     * walking toward the anchor (a build far away may take longer to reach than the bound below). When none of that
     * has happened for {@link #NO_GAIN_TICKS} ticks the task ends with the same failure a navigator's would (see
     * {@link #translate}), because a builder that cannot break what is in the way keeps trying it for ever instead of
     * pausing. Null while the build goes on.
     */
    @Nullable
    private TickResult watchGain(BotPlayer body) {
        int progress = 0;
        if (body.getInteractionManager() instanceof com.legoj15.megablockbot.game.nav.BodyInteractionManager manager) {
            progress = manager.blocksBroken() + manager.blocksPlaced();
        }
        double away = body.position().distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(at));
        if (progress != seenProgress || away < bestApproach - CLOSER_BY) {
            seenProgress = progress;
            bestApproach = Math.min(bestApproach, away);
            noGainTicks = 0;
            return null;
        }
        if (++noGainTicks < NO_GAIN_TICKS) return null;
        Blueprint built = blueprint;
        BlockPos place = at;
        release(body);
        return TickResult.failed(translate(body, nav(body), built, place));
    }

    /** The navigator's failure -- or a stalled build -- as this task's typed failure. */
    private Failure translate(BotPlayer body, BotNavigator nav, Blueprint built, BlockPos place) {
        BlockPos forbidden = forbiddenIntheWay(body, built, place);
        if (forbidden != null) {
            return new Failure.Protected(corePos(forbidden), "break rules", "the block at "
                    + forbidden.toShortString() + " is in the way of '" + blueprintName + "' and may not be broken");
        }
        Item missing = firstShortOf(body, built, place);
        if (missing != null && (nav.state() != BotNavigator.State.FAILED
                || nav.failKind() == BotNavigator.FailKind.MISSING_BLOCKS)) {
            int needed = 0;
            for (Blueprint.Entry entry : built.blocks()) {
                BlockPos p = place.offset(entry.pos());
                if (body.serverLevel().getBlockState(p).getBlock() != entry.state().getBlock()
                        && entry.state().getBlock().asItem() == missing) {
                    needed++;
                }
            }
            int shortBy = Math.max(1, needed - body.getInventory().countItem(missing));
            ItemKey key = ItemKeys.of(missing);
            String reason = nav.state() == BotNavigator.State.FAILED && nav.failure() != null
                    ? nav.failure() : "the build is short of " + key.id();
            return new Failure.MissingInput(key, shortBy, "the builder ran out of " + key.id()
                    + " with " + (needed - shortBy) + " of " + needed + " still to place: " + reason);
        }
        String reason = nav.state() == BotNavigator.State.FAILED && nav.failure() != null ? nav.failure()
                : "the builder made no progress in " + NO_GAIN_TICKS + " ticks";
        return unreachable(anchor.encode(), reason, "could not build '" + blueprintName + "' at "
                + anchor.encode() + ": " + reason);
    }

    /** The first item the build is short of, as the navigator saw it (the builder paused for want of it). */
    @Nullable
    private static Item firstShortOf(BotPlayer body, Blueprint built, BlockPos place) {
        ServerLevel level = body.serverLevel();
        Map<Item, Integer> needed = new LinkedHashMap<>();
        for (Blueprint.Entry entry : built.blocks()) {
            BlockPos p = place.offset(entry.pos());
            if (level.getBlockState(p).getBlock() == entry.state().getBlock()) continue;
            needed.merge(entry.state().getBlock().asItem(), 1, Integer::sum);
        }
        for (Map.Entry<Item, Integer> need : needed.entrySet()) {
            if (body.getInventory().countItem(need.getKey()) < need.getValue()) return need.getKey();
        }
        return null;
    }

    /**
     * The nearest position inside the blueprint that does not hold its block, is not air, and may not be broken by the
     * rules this body is armed with; null when there is none. The builder pauses on such a block, and the failure to
     * report is the protection, not the distance.
     */
    @Nullable
    private static BlockPos forbiddenIntheWay(BotPlayer body, Blueprint built, BlockPos place) {
        ServerLevel level = body.serverLevel();
        BreakFilter rules = BreakRules.filterFor(body);
        BlockPos here = body.blockPosition();
        BlockPos nearest = null;
        for (BlockPos wrong : built.wrong(level, place)) {
            BlockState state = level.getBlockState(wrong);
            if (state.isAir()) continue;
            boolean may;
            try {
                may = rules.mayBreak(level, wrong, state);
            } catch (RuntimeException e) {
                may = false; // a filter that throws is a no, as everywhere else (BreakFilter)
            }
            if (may) return null; // one may be broken: not a protection problem
            if (nearest == null || wrong.distSqr(here) < nearest.distSqr(here)) {
                nearest = wrong.immutable();
            }
        }
        return nearest;
    }

    /** How many of the blueprint's blocks stand, for the status line. */
    @Override
    public String progress() {
        Blueprint built = blueprint;
        BlockPos place = at;
        ServerLevel where = level;
        if (built == null || place == null || where == null) {
            return "looking up the blueprint '" + blueprintName + "'";
        }
        return built.matched(where, place) + " of " + built.size() + " blocks of '" + blueprintName + "' stand";
    }

    @Override
    public boolean waiting() {
        return false; // the builder's own placements and breaks are progress the executor's watchdog cannot see
    }

    @Override
    protected void letGo(BotPlayer body) {
        // Nothing is held besides the navigator, which release stops.
    }

    /** A checkpoint would only say where the build was; the builder skips what stands, so there is nothing to hint. */
    @Override
    public Map<String, String> checkpoint() {
        return Map.of();
    }
}
