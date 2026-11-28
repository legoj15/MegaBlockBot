package com.legoj15.megablockbot.game.task;

import com.legoj15.megablockbot.MegaBlockBot;
import com.legoj15.megablockbot.core.ItemKey;
import com.legoj15.megablockbot.core.Pos;
import com.legoj15.megablockbot.core.goal.Goal;
import com.legoj15.megablockbot.core.plan.NodeSpec;
import com.legoj15.megablockbot.core.task.Failure;
import com.legoj15.megablockbot.core.task.TickResult;
import com.legoj15.megablockbot.game.body.BotPlayer;
import com.legoj15.megablockbot.game.nav.BotNavigator;
import com.legoj15.megablockbot.game.plan.BotMemory;
import com.legoj15.megablockbot.game.nav.BreakFilter;
import com.legoj15.megablockbot.game.nav.BodyInteractionManager;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.level.block.BambooStalkBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CactusBlock;
import net.minecraft.world.level.block.KelpBlock;
import net.minecraft.world.level.block.KelpPlantBlock;
import net.minecraft.world.level.block.SugarCaneBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * {@code harvest}: take grown growables until the body holds the goal's count of the item (design 4.3, Harvest: wheat,
 * sugar cane, sweet berries). The count is the item's: a wheat crop drops wheat and seeds, and what this task is after
 * is the leaf's item.
 *
 * <p>Parameters from the planner: {@code item}, {@code count} (how many more, used only when the goal is not a
 * {@link Goal.HaveItem} of the item: the goal's count is absolute and wins) and {@code block} (a registry id). The
 * leaf's source id rides along in {@code NoSource} so a replan excludes exactly that source.
 *
 * <p><b>What counts as grown</b> is decided from the block's own state properties, in one place: {@link #grown}. The
 * planner has no wait-for-it-to-grow yet, so an unripe crop is not a target at all -- the task answers {@code NoSource}
 * for a field of unripe wheat, which the executor answers by excluding the source and replanning, not by waiting.
 *
 * <p>The task:
 * <ol>
 *   <li>is done at once when the body already holds the count, before it touches anything;</li>
 *   <li>never takes a block the break rules would not let the body break (a player's farm inside a claim, a block a
 *       player placed): the per-body rules, as the mine task's scan asks them. A forbidden grown block is skipped, and
 *       when only forbidden ones are known the task fails {@code Protected} naming one;</li>
 *   <li>goes to the block through the navigator and takes the harvest as a player does: it breaks what a player breaks
 *       (crops, cane) through the body's own game mode, and uses what a player uses without breaking (a berry bush, a
 *       cave vine) with an empty hand where there is one;</li>
 *   <li>picks up the drops: it waits and walks to the drops of the leaf's item that lie within a few blocks of the
 *       block, as {@code recover_drops} walks to drops;</li>
 *   <li>replants a crop it broke when the body holds the plant's item (the seed, the carrot) and the block below is
 *       farmland, by a use-item click on it. Not replanting is not a failure;</li>
 *   <li>sets a block it cannot get at aside (as the mine task does) and takes another; when no grown block is known it
 *       explores for a bounded number of rounds, then fails {@code NoSource} with the leaf's item and source id. No
 *       room for the item is {@code NoRoom}, as in the mine task.</li>
 * </ol>
 *
 * <p><b>Why it does its own work instead of the engine's farm process.</b> The navigator's engine has one
 * ({@code FarmProcess}), but it is a whole farm in one command: it scans for every crop kind there is, replants open
 * farmland, bonemeals what can be bonemealed and left-clicks everything, which a berry bush must not be. This leaf is
 * about one block kind and its item, and the taking must ask the break rules for each target and go through the body's
 * own inputs; so the task scans the loaded chunks itself (as the mine task's protected scan does), walks with
 * {@link BotNavigator#goTo}, and breaks and uses through {@link BodyInteractionManager}.
 */
public final class HarvestTask extends BodyTask {
    /**
     * How far around the body the loaded chunks are scanned for a grown block of the kind: the mine task's own scan
     * radius for the blocks it may not break.
     */
    private static final int SCAN_RADIUS = 32;
    /**
     * Blocks an exploring step walks before the body looks again: three quarters of the scan radius, so two looks
     * overlap and nothing between them goes unseen. The step's ticks are the bound for a body that gets nowhere.
     */
    private static final double STEP_BLOCKS = 24.0;
    /**
     * Ticks the walk back to where the plant was last seen may take: two minutes, which is five hundred blocks at a
     * walk and more than a body strays from a place while it gathers the rest of a plan.
     */
    private static final int RETURN_WALK_TICKS = 2400;
    /** The navigator's goal for a walk to the harvest: within this many blocks of it, well inside the use-block reach. */
    private static final int REACH_RADIUS = 2;
    /** How far from the harvested block the drops of the leaf's item are walked to. */
    private static final double LOOT_RADIUS = 8.0;
    /** Ticks a break may take before the block is set aside and another taken; a crop breaks at once, a cane too. */
    private static final int BREAK_WAIT_TICKS = 200;
    /** Ticks a use may take to change the block before it is given up on; a click is one tick, so this is generous. */
    private static final int USE_WAIT_TICKS = 100;
    /** Ticks between two use clicks, as a client's right-click repeat. */
    private static final int USE_CLICK_PERIOD = 4;
    /**
     * A berry bush carries its pickable berries from this age: the bush's own loot table has one pool for age 3 and
     * one for age 2, and its use gives berries from age 2.
     */
    private static final int SWEET_BERRY_READY_AGE = 2;
    /** The most set-aside blocks a checkpoint keeps (those nearest to where the task began). */
    private static final int MAX_SAVED_SET_ASIDE = 64;

    /**
     * How hard the task looks for a grown block when none is known: one stretch of exploring followed by another
     * scan, and the whole search gives up after {@code maxAttempts} of them.
     *
     * @param stepTicks   ticks an exploring step lasts
     * @param maxAttempts steps before the search gives up
     */
    public record Search(int stepTicks, int maxAttempts) {
        /** 30 seconds per step, twelve steps: as far as the mine task's default search walks. */
        public static final Search DEFAULT = new Search(600, 12);
    }

    private enum Phase { GOING, BREAKING, USING, REPLANT, LOOTING, EXPLORING, RETURNING }

    private final Search search;
    private final String sourceId;
    private final ItemKey itemKey;
    private final String blockLabel;

    // From an earlier task for the same node: hints for where to look, checked against the world before use.
    private final String hintDimension;
    private final int hintAttempts;
    private final Set<Pos> hintSetAside;

    private Item item;
    private Block block;
    @Nullable
    private Item plant;
    private String dimension = "";
    private int target;
    private final int moreCount;
    private boolean alreadyDone;
    private Phase phase = Phase.GOING;
    private BlockPos origin;
    /** Whether this task has already gone back to where the plant was last seen: once is all it does. */
    private boolean wentBack;
    /** The column the walk back is to, while it goes on. */
    @Nullable
    private int[] seenAt;
    /** Where the body stood when the exploring step it is on began; null before the first. */
    @Nullable
    private Vec3 stepFrom;
    private int attempts;
    /** Blocks the task will not head for again, kept for its life and its checkpoint. */
    private final Set<BlockPos> setAside = new HashSet<>();
    /** How many grown blocks the last scan left alone for standing inside a live danger zone (issue 29). */
    private int zonedBlocks;
    /** Kinds with no rule for ripeness, read by the fallback as grown in a non-default state; reported once each. */
    private final Set<String> assumedGrown = new HashSet<>();
    @Nullable
    private BlockPos at;
    /**
     * The block this harvest owes to the world (a berry bush it is picking, not breaking): the navigator may not
     * break it while the task works it, and not while it picks up what that block dropped.
     */
    @Nullable
    private BlockPos preserving;
    /** The composite filter {@link #preserve} installed over the one in force, so {@link #unpreserve} can tell
     *  whether that is still the one the navigator holds. */
    @Nullable
    private BreakFilter preserveFilter;
    private boolean pendingReplant;
    /** The walk to what a harvest dropped, shared with the kill task. */
    private final DropPickup pickup = new DropPickup();
    private boolean working;
    private int phaseTicks;
    private int cooldown;
    private int sequence;
    /** The held count as of the last tick: what the status line says while the task runs. */
    private int lastHeld;

    public HarvestTask(NodeSpec leaf, Map<String, String> hints) {
        this(leaf, hints, Search.DEFAULT);
    }

    public HarvestTask(NodeSpec leaf, Map<String, String> hints, Search search) {
        this.search = search;
        this.sourceId = leaf.source();
        this.itemKey = ItemKey.of(required(leaf, "item"));
        this.blockLabel = required(leaf, "block");
        int goalCount = leaf.goal() instanceof Goal.HaveItem g && g.key().equals(itemKey) ? g.count() : -1;
        this.target = goalCount;
        this.moreCount = intParam(leaf.params(), "count", 1);

        this.hintDimension = hints.getOrDefault("dim", "");
        this.hintAttempts = parseInt(hints.get("attempts"));
        this.hintSetAside = new HashSet<>();
        for (String text : hints.getOrDefault("setAside", "").split(";")) {
            try {
                hintSetAside.add(Pos.parse(text.trim()));
            } catch (IllegalArgumentException e) {
                // A checkpoint that does not validate is dropped, not trusted.
            }
        }
    }

    private static int parseInt(@Nullable String text) {
        try {
            return text == null ? 0 : Math.max(0, Integer.parseInt(text.trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
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
        ResourceLocation blockId = idOf(blockLabel);
        block = blockId == null ? null : BuiltInRegistries.BLOCK.getOptional(blockId).orElse(null);
        if (block == null) {
            fail(new Failure.NoSource(itemKey, sourceId, "'" + blockLabel + "' is not a registered block"));
            return;
        }
        plant = plantOf(body.serverLevel(), block);

        int held = held(body);
        if (target <= 0) target = held + Math.max(1, moreCount);
        if (held >= target) {
            // Nothing to do, and nothing is touched: the first tick says so.
            alreadyDone = true;
            return;
        }
        if (MenuHand.roomFor(body, new ItemStack(item)) == 0) {
            // No block is broken for a drop that would be lost: carried storage has no free slot and no stack of the
            // item with room, and a replan would plan the same step into the same full inventory.
            fail(new Failure.NoRoom(itemKey, "the inventory cannot take one more " + itemKey.id()
                + ": no free slot and no stack of it with room"));
            return;
        }
        boolean sameWorld = hintDimension.equals(dimension);
        origin = body.blockPosition();
        if (sameWorld) {
            attempts = Math.min(hintAttempts, search.maxAttempts());
            // What an earlier task for this node could not get at is not tried again. The world is looked at all the
            // same, so a block that is gone costs nothing.
            for (Pos aside : hintSetAside) setAside.add(blockPos(aside));
        }
        // The first tick looks for the first target (see going): begin only decides what is wanted.
    }

    @Override
    protected void resumeWork(BotPlayer body) {
        // Everything is looked at again from the world: what stands where, what is grown, what the body holds. A
        // block the task was working is owed what that work left -- the drops of a taking may lie in its cell, and
        // a break owes the spot its replant -- so the pick-up around it is started again and the world decides
        // whether anything is still owed (nothing lying there ends it at once, and the search for the next block
        // follows). A block the task was only going to is let go and looked for again. The pause already let the
        // preserve go (letGo); when the block still standing is one a player uses, it is preserved again for the
        // walk back to its drops.
        boolean owed = at != null && phase != Phase.GOING && phase != Phase.EXPLORING;
        preserving = null;
        preserveFilter = null;
        if (!owed) {
            at = null;
            pendingReplant = false;
            phase = Phase.GOING;
        } else {
            if (harvestedByUse(body.serverLevel().getBlockState(at))) preserve(body, at);
            pickup.start();
            phase = Phase.LOOTING;
        }
        phaseTicks = 0;
        cooldown = 0;
    }

    private int held(BotPlayer body) {
        return body.getInventory().countItem(item);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Tick
    // ---------------------------------------------------------------------------------------------------------

    @Override
    protected TickResult onTick(BotPlayer body) {
        if (alreadyDone) return TickResult.COMPLETED;
        lastHeld = held(body);
        // The goal first, whatever got the items into the inventory -- but a replant this task owes is finished first:
        // a harvest that took the crop owes the field its seed back, and the count does not cancel that.
        if (lastHeld >= target && !pendingReplant) {
            release(body);
            return TickResult.COMPLETED;
        }
        working = false; // only the tick that works at a block says it is waiting on one
        if (cooldown > 0) {
            cooldown--;
            return TickResult.RUNNING;
        }
        return switch (phase) {
            case GOING -> going(body);
            case BREAKING -> breaking(body);
            case USING -> using(body);
            case REPLANT -> replant(body);
            case LOOTING -> loot(body);
            case EXPLORING -> explore(body);
            case RETURNING -> goBack(body);
        };
    }

    private TickResult going(BotPlayer body) {
        if (at == null) return findNext(body);
        BotNavigator nav = nav(body);
        switch (nav.state()) {
            case DONE -> {
                return atTheBlock(body);
            }
            case FAILED -> {
                // The body could not get to that block: set it aside, as the mine task does, and take another.
                setAside.add(at.immutable());
                return findNext(body);
            }
            case IDLE -> {
                nav(body).goTo(at, REACH_RADIUS);
                return TickResult.RUNNING;
            }
            default -> {
                return TickResult.RUNNING;
            }
        }
    }

    /** The body is at the block: it takes the harvest the way the block asks for it. */
    private TickResult atTheBlock(BotPlayer body) {
        ServerLevel level = body.serverLevel();
        BlockState state = level.getBlockState(at);
        if (!state.is(block)) return findNext(body); // gone since it was chosen
        if (!grown(level, at, state)) return findNext(body); // not ripe any more: someone else took the harvest
        if (!body.canInteractWithBlock(at, 0.0)) return goTo(body, at); // pushed or carried off on the way
        if (!mayBreak(body, level, at, state)) {
            // The rules changed under the task (a claim arrived): skip it, and say which when only these are left.
            setAside.add(at.immutable());
            return findNext(body);
        }
        phaseTicks = 0;
        if (harvestedByUse(state)) {
            preserve(body, at);
            phase = Phase.USING;
        } else {
            phase = Phase.BREAKING;
        }
        return TickResult.RUNNING;
    }

    /**
     * A block the harvest takes by use must still stand once it is taken, and what it drops lies in the block's own
     * cell (the berries pop where the bush is), so the walk to the drops would have the navigator break the block to
     * stand in it. While this task owes that block, the navigator's break rules refuse it and nothing else changes.
     */
    private void preserve(BotPlayer body, BlockPos pos) {
        unpreserve(body);
        preserving = pos.immutable();
        BreakFilter rules = nav(body).breakFilter();
        preserveFilter = (level, spot, state) -> !spot.equals(preserving) && mayBreak(rules, level, spot, state);
        nav(body).setBreakFilter(preserveFilter);
    }

    /**
     * The task no longer owes a block its survival: the break rules go back to the standard one -- but only while
     * the navigator still holds the filter the preserve installed. Someone else may have put their own in since
     * (the reflex layer's "break nothing", on taking the body over before this task was paused), and that one is
     * not this task's to replace: whoever installed it gives the rules back themselves.
     */
    private void unpreserve(BotPlayer body) {
        if (preserving == null) return;
        preserving = null;
        if (preserveFilter != null && nav(body).breakFilter() == preserveFilter) {
            nav(body).setBreakFilter(BreakRules.filterFor(body));
        }
        preserveFilter = null;
    }

    private TickResult breaking(BotPlayer body) {
        working = true;
        ServerLevel level = body.serverLevel();
        BlockPos here = at;
        BlockState state = level.getBlockState(here);
        if (state.isAir()) return afterHarvest(body);
        if (!state.is(block) || !grown(level, here, state)) return findNext(body);
        if (++phaseTicks > BREAK_WAIT_TICKS) {
            setAside.add(here.immutable());
            return findNext(body);
        }
        if (!body.canInteractWithBlock(here, 0.0)) return goTo(body, here);
        if (MenuHand.roomFor(body, new ItemStack(item)) == 0) {
            // No block is broken for a drop that would be lost.
            release(body);
            return TickResult.failed(new Failure.NoRoom(itemKey, "the inventory cannot take one more " + itemKey.id()
                + ": no free slot and no stack of it with room"));
        }
        Direction face = faceTowardEyes(body, here);
        BodyInteractionManager hand = (BodyInteractionManager) body.getInteractionManager();
        if (!hand.isMining()) {
            StationTask.selectBreakingTool(body, state);
            hand.processBlockBreakingAction(here, Action.START_DESTROY_BLOCK, face, level.getMaxBuildHeight(), sequence++);
        } else {
            hand.continueBreaking(here, face);
            if (hand.getBlockBreakingProgress() >= 10) {
                hand.processBlockBreakingAction(hand.getMiningPos(), Action.STOP_DESTROY_BLOCK, face,
                    level.getMaxBuildHeight(), sequence++);
            }
        }
        if (level.getBlockState(here).isAir()) return afterHarvest(body); // an instant break: a crop, a cane
        return TickResult.RUNNING;
    }

    private TickResult using(BotPlayer body) {
        working = true;
        ServerLevel level = body.serverLevel();
        BlockPos here = at;
        BlockState state = level.getBlockState(here);
        if (!state.is(block)) return findNext(body);
        if (!grown(level, here, state)) return startLooting(body); // picked: the berries are on the ground
        if (++phaseTicks > USE_WAIT_TICKS) {
            setAside.add(here.immutable());
            return findNext(body);
        }
        if (!body.canInteractWithBlock(here, 0.0)) return goTo(body, here);
        // An empty hand where there is one: using a bush with a usable item would use the item (bone meal grows it).
        selectEmptyHand(body);
        BlockHitResult hit = hitTowardEyes(body, here);
        body.input().lookAt(hit.getLocation());
        body.getInteractionManager().interactBlock(body, level, body.getMainHandItem(), InteractionHand.MAIN_HAND, hit);
        cooldown = USE_CLICK_PERIOD;
        return TickResult.RUNNING;
    }

    /** The block is taken: what is left of it is picking up the drops, and a crop that was broken owes a replant. */
    private TickResult afterHarvest(BotPlayer body) {
        pendingReplant = true;
        return startLooting(body);
    }

    /**
     * Puts the plant back where the crop was broken, when the body holds the plant's item and the block below is
     * farmland: a use-item click on it, as a player replants. Not replanting is not a failure.
     */
    private TickResult replant(BotPlayer body) {
        pendingReplant = false;
        ServerLevel level = body.serverLevel();
        BlockPos here = at;
        if (here != null && plant != null && level.getBlockState(here).isAir()
            && level.getBlockState(here.below()).is(Blocks.FARMLAND) && MenuHand.carriedCount(body, plant) > 0
            && hold(body, plant)) {
            Vec3 click = Vec3.atCenterOf(here.below()).add(0.0, 0.5, 0.0);
            body.input().lookAt(click);
            body.getInteractionManager().interactBlock(body, level, body.getMainHandItem(), InteractionHand.MAIN_HAND,
                new BlockHitResult(click, Direction.UP, here.below(), false));
        }
        // The replant is done (or the world refused it, which is not a failure): the count is asked before the
        // next block is looked for. A harvest that holds its goal is done, and a look now can only fail -- the
        // one ripe block there was is taken, and what is left may be forbidden or nothing at all.
        if (held(body) >= target) {
            release(body);
            return TickResult.COMPLETED;
        }
        return findNext(body);
    }

    /** Picks up what the harvest dropped: the drops of the leaf's item near the block ({@link DropPickup}). */
    private TickResult startLooting(BotPlayer body) {
        if (!nav(body).runsCommandOfAnother(this)) nav(body).stop();
        pickup.start();
        phase = Phase.LOOTING;
        return loot(body);
    }

    private TickResult loot(BotPlayer body) {
        if (at != null && pickup.tick(this, body, drop -> drop.getItem().is(item), new AABB(at).inflate(LOOT_RADIUS))) return TickResult.RUNNING;
        return lootDone(body);
    }

    /** The drops around the block are taken or lost: on to the next block, unless there is no room for the item at all. */
    private TickResult lootDone(BotPlayer body) {
        pickup.rest();
        if (held(body) < target && MenuHand.roomFor(body, new ItemStack(item)) == 0) {
            release(body);
            return TickResult.failed(new Failure.NoRoom(itemKey, "the inventory cannot take one more " + itemKey.id()
                + ": no free slot and no stack of it with room"));
        }
        if (pendingReplant) {
            phase = Phase.REPLANT;
            return TickResult.RUNNING;
        }
        return findNext(body);
    }

    private TickResult goTo(BotPlayer body, BlockPos pos) {
        at = pos;
        phase = Phase.GOING;
        phaseTicks = 0;
        nav(body).goTo(pos, REACH_RADIUS);
        return TickResult.RUNNING;
    }

    /** No grown block is known: look further, or say there is none to be found. */
    private TickResult nextStep(BotPlayer body) {
        // Before anywhere new: where the body last saw the plant, when that is not where it stands. A plan gathers
        // in its own order, and a body that hunted its cows first had walked three hundred blocks from the river
        // bank where the sugar cane stood, and searched the forest it ended up in.
        if (!wentBack) {
            wentBack = true;
            int[] seen = BotMemory.of(body.getUUID()).lastSeen(dimension, blockLabel);
            if (seen != null && Math.hypot(seen[0] + 0.5 - body.getX(), seen[1] + 0.5 - body.getZ()) > SCAN_RADIUS) {
                seenAt = seen;
                phase = Phase.RETURNING;
                phaseTicks = 0;
                nav(body).goToXZ(seen[0], seen[1]);
                MegaBlockBot.LOGGER.info("{}: no ripe {} here; going back to where one was last seen, {} {}",
                    body.getGameProfile().getName(), blockLabel, seen[0], seen[1]);
                return TickResult.RUNNING;
            }
        }
        if (++attempts > search.maxAttempts()) return giveUp(body);
        phase = Phase.EXPLORING;
        phaseTicks = 0;
        stepFrom = body.position();
        nav(body).explore(origin);
        return TickResult.RUNNING;
    }

    /**
     * The walk back to where the plant was last seen. Arrived (within the scan's reach of the column), or the walk
     * failed or ran out of its time: the body looks from where it stands, and the search goes on around there.
     */
    private TickResult goBack(BotPlayer body) {
        phaseTicks++;
        BotNavigator nav = nav(body);
        boolean there = seenAt != null
            && Math.hypot(seenAt[0] + 0.5 - body.getX(), seenAt[1] + 0.5 - body.getZ()) <= SCAN_RADIUS / 2.0;
        if (!there && phaseTicks < RETURN_WALK_TICKS && nav.state() == BotNavigator.State.WORKING) return TickResult.RUNNING;
        if (nav.state() == BotNavigator.State.WORKING && !nav.runsCommandOfAnother(this)) nav.stop();
        // Whatever stands there now, the memory has done its work: a second trip to the same column finds the same.
        BotMemory.of(body.getUUID()).forgetSeen(dimension, blockLabel);
        origin = body.blockPosition();
        return findNext(body);
    }

    private TickResult explore(BotPlayer body) {
        phaseTicks++;
        BotNavigator nav = nav(body);
        if (nav.state() == BotNavigator.State.FAILED) {
            // Nowhere further to look from here: the search is spent -- when that is what the failure says
            // (BodyTask.exploreFailure).
            TickResult notTheSource = exploreFailure(body, nav, blockLabel);
            if (notTheSource != null) return notTheSource;
            return giveUp(body);
        }
        boolean walkedAStep = stepFrom != null && body.position().distanceTo(stepFrom) >= STEP_BLOCKS;
        if (!walkedAStep && phaseTicks < search.stepTicks() && nav.state() == BotNavigator.State.WORKING) return TickResult.RUNNING;
        return findNext(body);
    }

    /**
     * The search is spent. Grown blocks were known and none could be got at: {@code NoSource} carrying the leaf's item
     * and source, saying how many were found; none was ever known: {@code NoSource} too, saying how far it looked. The
     * executor puts the source in the goal's exclusions, so the next plan gathers the item another way.
     */
    private TickResult giveUp(BotPlayer body) {
        release(body);
        String detail = setAside.isEmpty()
            ? String.format(Locale.ROOT, "found no ripe %s to get %s from: looked within %d blocks in %d attempts",
                blockLabel, itemKey.id(), SCAN_RADIUS, attempts)
            : String.format(Locale.ROOT, "found %d ripe %s block(s) and could not get at any of them in %d attempts",
                setAside.size(), blockLabel, attempts);
        if (zonedBlocks > 0) detail += "; " + zonedBlocks + " of them stood inside a danger zone this body keeps out of";
        if (!assumedGrown.isEmpty()) detail += "; read as ripe by its state, with no rule of its own: " + String.join(", ", assumedGrown);
        MegaBlockBot.LOGGER.info("{}: gave up harvesting {}: {}", body.getGameProfile().getName(), blockLabel, detail);
        return TickResult.failed(new Failure.NoSource(itemKey, sourceId, detail));
    }

    // ---------------------------------------------------------------------------------------------------------
    // Finding the next block
    // ---------------------------------------------------------------------------------------------------------

    /**
     * Scans the loaded chunks around the body for the nearest grown block of the kind the rules allow, and goes to it.
     * A block inside a live danger zone is not a candidate (issue 29): the body does not go back to where it was driven
     * off or killed for a harvest, and the block is not set aside, so it comes back when the zone lapses. None allowed
     * and some forbidden: {@code Protected} naming the nearest, for the executor to exclude it. None at all: a step of
     * exploring, and past the search's bound {@code NoSource}.
     */
    private TickResult findNext(BotPlayer body) {
        unpreserve(body); // whatever block the last harvest owed the world, this task is done with it
        ServerLevel level = body.serverLevel();
        BreakFilter rules = BreakRules.filterFor(body);
        BlockPos center = body.blockPosition();
        int r = SCAN_RADIUS;
        int lowY = Math.max(level.getMinBuildHeight(), center.getY() - r);
        int highY = Math.min(level.getMaxBuildHeight() - 1, center.getY() + r);
        BlockPos nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        BlockPos forbidden = null;
        double forbiddenDistance = Double.MAX_VALUE;
        int zoned = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int chunkX = (center.getX() - r) >> 4; chunkX <= (center.getX() + r) >> 4; chunkX++) {
            for (int chunkZ = (center.getZ() - r) >> 4; chunkZ <= (center.getZ() + r) >> 4; chunkZ++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
                if (chunk == null) continue; // a read never loads a chunk
                int fromX = Math.max(chunkX << 4, center.getX() - r);
                int toX = Math.min((chunkX << 4) + 15, center.getX() + r);
                int fromZ = Math.max(chunkZ << 4, center.getZ() - r);
                int toZ = Math.min((chunkZ << 4) + 15, center.getZ() + r);
                for (int x = fromX; x <= toX; x++) {
                    for (int z = fromZ; z <= toZ; z++) {
                        for (int y = lowY; y <= highY; y++) {
                            cursor.set(x, y, z);
                            BlockState state = chunk.getBlockState(cursor);
                            if (!state.is(block)) continue;
                            // A plain position: a mutable one is not equal to the plain blocks in the set, and the
                            // plants that stack are asked about the block below them.
                            BlockPos here = cursor.immutable();
                            if (!grown(level, here, state)) continue;
                            if (setAside.contains(here)) continue;
                            if (inDangerZone(body, here)) {
                                zoned++;
                                continue;
                            }
                            double away = here.distSqr(center);
                            if (mayBreak(rules, level, here, state)) {
                                if (away < nearestDistance) {
                                    nearestDistance = away;
                                    nearest = here;
                                }
                            } else if (away < forbiddenDistance) {
                                forbiddenDistance = away;
                                forbidden = here;
                            }
                        }
                    }
                }
            }
        }
        zonedBlocks = zoned;
        if (nearest != null) return goTo(body, nearest);
        if (forbidden != null) {
            release(body);
            return TickResult.failed(new Failure.Protected(corePos(forbidden), "break rules",
                "the ripe " + blockLabel + " near " + forbidden.toShortString() + " may not be taken"));
        }
        return nextStep(body);
    }

    private boolean mayBreak(BotPlayer body, ServerLevel level, BlockPos pos, BlockState state) {
        try {
            return BreakRules.filterFor(body).mayBreak(level, pos, state);
        } catch (RuntimeException e) {
            return false; // a filter that throws is a no, as everywhere else (BreakFilter)
        }
    }

    private static boolean mayBreak(BreakFilter rules, ServerLevel level, BlockPos pos, BlockState state) {
        try {
            return rules.mayBreak(level, pos, state);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Whether the block at {@code pos} is ripe for the taking, from its own state (and, for the plants that stack, the
     * block below it): the one place that decides. A block kind with no rule of its own is read as ripe when it is not
     * in its default state, and the kind is named in the report; nothing else in this task guesses.
     *
     * <ul>
     *   <li>A berry bush or a cave vine: a state with berries (the cave vine's own {@code berries} property; a bush
     *       carries its pickable berries from age 2 -- its loot table has a pool for age 2 and one for age 3).</li>
     *   <li>Sugar cane, cactus, bamboo and kelp: any block of the plant that has another of it below, so the lowest
     *       block stays and the plant regrows.</li>
     *   <li>A crop with an age (wheat, carrots, nether wart, cocoa): its last age.</li>
     * </ul>
     */
    private boolean grown(ServerLevel level, BlockPos pos, BlockState state) {
        if (state.hasProperty(BlockStateProperties.BERRIES)) return state.getValue(BlockStateProperties.BERRIES);
        Block actual = state.getBlock();
        if (actual instanceof SweetBerryBushBlock) return age(state) >= SWEET_BERRY_READY_AGE;
        if (actual instanceof SugarCaneBlock || actual instanceof CactusBlock || actual instanceof BambooStalkBlock
            || actual instanceof KelpBlock || actual instanceof KelpPlantBlock) {
            return level.getBlockState(pos.below()).is(actual);
        }
        Integer age = age(state);
        if (age != null) return age == ageMaximum(state);
        boolean ripe = state != actual.defaultBlockState();
        if (ripe && assumedGrown.add(BuiltInRegistries.BLOCK.getKey(actual).toString())) {
            MegaBlockBot.LOGGER.info("harvest: no rule for when {} is ripe; taking it as ripe in a non-default state",
                BuiltInRegistries.BLOCK.getKey(actual));
        }
        return ripe;
    }

    /** Whether the harvest is a use of the block rather than a break of it: what a player right-clicks. */
    private static boolean harvestedByUse(BlockState state) {
        return state.hasProperty(BlockStateProperties.BERRIES) || state.getBlock() instanceof SweetBerryBushBlock;
    }

    /** The block's {@code age} property, or null when it has none. */
    @Nullable
    private static Integer age(BlockState state) {
        for (Property<?> property : state.getProperties()) {
            if (property.getName().equals("age") && property instanceof IntegerProperty integer) {
                return state.getValue(integer);
            }
        }
        return null;
    }

    private static int ageMaximum(BlockState state) {
        for (Property<?> property : state.getProperties()) {
            if (property.getName().equals("age") && property instanceof IntegerProperty integer) {
                return integer.getPossibleValues().stream().mapToInt(Integer::intValue).max().orElse(0);
            }
        }
        return 0;
    }

    /** What the player plants to grow this block, as the resolver reads it ({@code plantOf}): the block's own item, or what pick-block gives. */
    @Nullable
    private static Item plantOf(ServerLevel level, Block block) {
        Item own = block.asItem();
        if (own != Items.AIR) return own;
        try {
            ItemStack clone = block.getCloneItemStack(level, BlockPos.ZERO, block.defaultBlockState());
            if (!clone.isEmpty()) return clone.getItem();
        } catch (RuntimeException e) {
            // A block whose pick item cannot be read: nothing to replant with, and replanting is never a failure.
        }
        return null;
    }

    // ---------------------------------------------------------------------------------------------------------
    // The body's hands
    // ---------------------------------------------------------------------------------------------------------

    /** The face of the block that looks toward the body's eyes, and a click point on it. */
    private static BlockHitResult hitTowardEyes(BotPlayer body, BlockPos pos) {
        Vec3 center = Vec3.atCenterOf(pos);
        Vec3 toEyes = body.getEyePosition().subtract(center);
        Direction face = Direction.getNearest(toEyes.x, toEyes.y, toEyes.z);
        Vec3 at = center.add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        return new BlockHitResult(at, face, pos, false);
    }

    private static Direction faceTowardEyes(BotPlayer body, BlockPos pos) {
        return hitTowardEyes(body, pos).getDirection();
    }

    /** Selects an empty hotbar slot, so a use of the block is not a use of whatever the hand held. */
    private static void selectEmptyHand(BotPlayer body) {
        var inventory = body.getInventory();
        for (int slot = 0; slot < 9; slot++) {
            if (inventory.getItem(slot).isEmpty()) {
                inventory.selected = slot;
                return;
            }
        }
    }

    /**
     * Takes the item into the main hand as a player would: it is selected when it is on the hotbar, otherwise the
     * inventory menu swaps it into the selected hotbar slot (as {@code CraftTask} takes its table item).
     */
    private static boolean hold(BotPlayer body, Item item) {
        var inventory = body.getInventory();
        for (int slot = 0; slot < 9; slot++) {
            if (inventory.getItem(slot).is(item)) {
                inventory.selected = slot;
                return true;
            }
        }
        if (body.containerMenu != body.inventoryMenu) body.closeContainer();
        MenuHand hand = new MenuHand(body, body.inventoryMenu);
        int from = hand.findCarried(item);
        if (from < 0) return false;
        hand.click(from, inventory.selected, ClickType.SWAP);
        return inventory.getItem(inventory.selected).is(item);
    }

    // ---------------------------------------------------------------------------------------------------------

    /** The blocks this task will not head for again, as plain positions. For tests and the status. */
    public Set<BlockPos> setAside() {
        return Set.copyOf(setAside);
    }

    @Override
    public boolean waiting() {
        // A break, a use and a pickup are the game's timing: position and inventory show nothing while they go on,
        // and the stuck watchdog would cut a harvest short.
        return working || pickup.waiting();
    }

    @Override
    protected void letGo(BotPlayer body) {
        working = false;
        pickup.rest();
        unpreserve(body);
        if (body.getInteractionManager() instanceof BodyInteractionManager manager
            && manager.isMining() && at != null && at.equals(manager.getMiningPos())) {
            manager.abortBreak();
        }
        body.input().reset();
    }

    /**
     * The dimension and the blocks it could not get at, so a restart does not search from scratch. Hints only; a
     * restarted task checks them against the world.
     */
    @Override
    public Map<String, String> checkpoint() {
        Map<String, String> out = new LinkedHashMap<>();
        if (origin == null) return out;
        out.put("dim", dimension);
        out.put("attempts", Integer.toString(attempts));
        if (!setAside.isEmpty()) {
            StringBuilder aside = new StringBuilder();
            setAside.stream().sorted(Comparator.comparingDouble(origin::distSqr)).limit(MAX_SAVED_SET_ASIDE).forEach(pos -> {
                if (aside.length() > 0) aside.append(';');
                aside.append(corePos(pos).encode());
            });
            out.put("setAside", aside.toString());
        }
        return out;
    }

    /** What the task is doing now, for the status line: what it is heading for, and how many of the item it holds. */
    @Override
    public String progress() {
        String what = switch (phase) {
            case GOING -> at != null
                ? "heading for a " + blockLabel.replace("minecraft:", "") + " at " + at.getX() + " " + at.getY() + " " + at.getZ()
                : "looking for a ripe " + blockLabel.replace("minecraft:", "");
            case BREAKING -> "taking a " + blockLabel.replace("minecraft:", "") + " at " + at;
            case USING -> "picking a " + blockLabel.replace("minecraft:", "") + " at " + at;
            case REPLANT -> "replanting what it took at " + at;
            case LOOTING -> "picking up the " + itemKey.id().replace("minecraft:", "") + " that dropped";
            case EXPLORING -> "exploring for a ripe " + blockLabel.replace("minecraft:", "");
            case RETURNING -> "going back to where a " + blockLabel.replace("minecraft:", "") + " was last seen";
        };
        return what + "; holds " + lastHeld + " of " + target + " " + itemKey.id().replace("minecraft:", "");
    }
}
