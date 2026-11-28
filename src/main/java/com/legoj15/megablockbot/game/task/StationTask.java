package com.legoj15.megablockbot.game.task;

import com.legoj15.megablockbot.MegaBlockBot;
import com.legoj15.megablockbot.core.Pos;
import com.legoj15.megablockbot.core.plan.NodeSpec;
import com.legoj15.megablockbot.core.task.Failure;
import com.legoj15.megablockbot.core.task.Task;
import com.legoj15.megablockbot.core.task.TickResult;
import com.legoj15.megablockbot.game.body.BotPlayer;
import com.legoj15.megablockbot.game.nav.BotNavigator;
import com.legoj15.megablockbot.game.plan.BotMemory;
import com.legoj15.megablockbot.game.plan.ItemKeys;
import com.legoj15.megablockbot.game.safety.BlockSafety;
import com.legoj15.megablockbot.game.safety.Verdict;
import com.legoj15.megablockbot.nav.engine.api.entity.IInteractionManager;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Everything about the station block a task works at (the craft task's table, the smelt task's furnace): finding one
 * to use where it stands or putting the carried one down the way a player does, opening it, and afterwards taking it
 * up again — breaking it as a client does and picking up its item. A subclass says which station it wants and does its
 * own work while the station's menu is open.
 *
 * <p><b>The station.</b> One within reach is used where it stands ({@link #mayUseStanding} says which). Otherwise the
 * station item is put down the way a player does it, then used and broken again. A spot is usable when a player could
 * place a block there ({@link TableSites}: replaceable, and a neighbour whose face the eyes can see and reach; leaves
 * will do) and block safety lets the body break the block again. When none is within reach the body walks, through
 * the navigator, to the nearest place with one beside it, within {@link TableSites#DEFAULT_RADIUS} blocks, and puts
 * the station down there; the click is made sneaking. When every candidate it found was one block safety refuses, the
 * body is inside a protected area and the way out is further than that: the search goes on out to
 * {@link TableSites#WIDE_RADIUS}, which is far enough to leave a protected square from its middle. The block is
 * broken as a player breaks it (start, wait out the break time, finish), and the task waits for the item to come
 * back. A station the body did not place is left standing. What the task placed is in the body's {@link BotMemory},
 * which is saved with the brain, so a task started after a restart finds the station it left and takes it up. A
 * remembered station found gone is forgotten.
 *
 * <p><b>Interruptions.</b> A reflex can hold the body and carry it several blocks off in the middle of the work or of
 * the take-up. On resume the task looks again from the top: a station of its own that stands out of reach is walked
 * back to ({@link #RETURN_RADIUS}) before the work or the break goes on, and one already broken is not forgotten: its
 * item is looked for within {@link #DROP_SEARCH_RADIUS} blocks of where it stood, and walked to wherever in that
 * radius it lies.
 *
 * <p><b>Exits.</b> The cursor is empty on every exit: after the work, on a failure, on completion, on pause and on
 * abort (every exit goes through {@link #release}). A paused or aborted task closes the station's menu and stops
 * breaking; the station it placed stays, remembered. A break the body may not make ends the task as a success all the
 * same when the work is done: the station stays, remembered as the body's, the log says why, and the tidy step or the
 * next task takes it up.
 *
 * <p><b>Failures.</b> {@code MissingInput} when the station is not carried ({@link #missingCarried} adds what else
 * must be carried before anything is put down), {@code TargetGone} for a station that is gone, {@code NoPlace} when
 * the body found nowhere to put the station down: nowhere within the radius, nowhere out of a protected area within
 * the wide radius, or no place it could get to in the walks it allows. Nothing a replan can change would alter that
 * (the planner does not see where a block fits), so the executor gives the goal up at once. {@code Protected} is the
 * answer for a station that stands and will not open: the executor excludes that block. {@code Unreachable} when the
 * game would not let the body put the station down or it could not walk back to the one it had put down,
 * {@code NoProgress} when the body would not sneak or the station could not be taken into the hand, {@code NoRoom}
 * when the station's item would not fit in the inventory. The executor adds its own budget.
 */
abstract class StationTask extends BodyTask {
    /**
     * How far from the body a standing station is looked for; reach decides whether it is used. Also how far the tidy
     * step ({@link #leftover}) looks for one this body left standing.
     */
    static final int STATION_SEARCH_RADIUS = 5;
    /**
     * Ticks the broken station's item may take to reach the inventory: it has a pickup delay of its own, and after an
     * interruption the body may have a dozen blocks to walk.
     */
    static final int PICKUP_WAIT_TICKS = 200;
    /**
     * Ticks after the break before the body steps toward a drop that did not reach it: the item has a pickup delay of
     * 10 ticks and hops about on its own for a few more, and most of the time that ends inside the body's pickup box.
     */
    static final int PICKUP_PATIENCE_TICKS = 20;
    /**
     * How far from where the station stood its dropped item is looked for, wherever the body is. Past
     * {@link #NEAR_DROP} the body goes to it through the navigator; the last steps are plain input, the way a player
     * walks over.
     */
    static final double DROP_SEARCH_RADIUS = 12.0;
    /** Within this many blocks of the body a drop is walked to by input; further off, by the navigator. */
    static final double NEAR_DROP = 2.5;
    /**
     * How far from where the station stood what it held is looked for once it is broken: a furnace drops its contents
     * beside the block, and they hop a little before they settle.
     */
    static final double SPILL_RADIUS = 3.0;
    /**
     * Ticks of walking at a near drop by input before the navigator is asked for the drop's own block instead: a
     * second is several times what the last steps take, so a drop still not taken lies where walking at it does not
     * bring the body (seen on a live server: in a dip one block deep, straight under the edge the body stood on, a
     * quarter block below its pickup box).
     */
    static final int INPUT_STEPS_ALLOWED = 20;
    /**
     * How far from the body a station of this task's that stands out of reach is still walked back to: a reflex's
     * retreat is a dozen blocks, not a hundred. A station further off is no station of this task's.
     */
    static final int RETURN_RADIUS = 24;
    /** How many times in a row the body walks back to a station and finds it still out of reach before the task gives up. */
    static final int RETURNS_ALLOWED = 3;
    /** The navigator's goal for a walk back to the station: within this many blocks of it. */
    static final int RETURN_STAND_RADIUS = 2;
    /** Ticks a break may take before the task gives it up; a table by hand takes about 75, a furnace about the same. */
    static final int BREAK_WAIT_TICKS = 400;
    /** How many times the game may refuse to open or start breaking before the task stops asking. */
    static final int REFUSALS_ALLOWED = 3;
    /** How many places the body walks to, one after another, looking for one with a usable spot beside it. */
    static final int WALKS_ALLOWED = 4;
    /** How many sites one tick of placing tries before it looks again from the top. */
    static final int SITES_TRIED = 6;
    /** How many times placing starts over before the task fails. */
    static final int PLACE_ROUNDS_ALLOWED = 3;
    /** Ticks the sneak may take to show on the body before the click is given up. */
    static final int SNEAK_WAIT_TICKS = 5;
    /** Ticks a walk may be found idle (something stopped the navigator) before the task stops asking it to go. */
    static final int WALK_RESTARTS_ALLOWED = 3;

    protected enum Phase { PREPARE, WALK, PLACE, OPEN, WORK, BREAK, PICKUP }

    protected final NodeSpec leaf;
    protected final Map<String, String> hints;
    /** True for the task made by {@link #takeUp}: it works nothing and only takes a station this body left standing. */
    protected final boolean takeUpOnly;

    Phase phase = Phase.PREPARE;
    int cooldown;
    int phaseTicks;
    int refusals;
    int sequence;
    @Nullable
    BlockPos station;
    boolean placedHere;
    int stationsBefore;
    /** The body is walking toward the station's drop on this task's input. */
    private boolean walking;
    /** The navigator would not take the body to the station's drop: the rest of the way is by input. */
    private boolean dropWalkFailed;
    /** Ticks this pick-up has walked at a near drop by input without taking it. */
    private int inputSteps;
    /** Picks up what fell out of the station besides its own item (what a furnace held), after that item is in. */
    private final DropPickup spillPickup = new DropPickup();
    /** Where the station whose spills are being picked up stood, kept for the search's centre. */
    @Nullable
    private BlockPos spillAt;
    /** The pick-up of what the station held is under way. */
    private boolean gathering;

    // Finding a place for the station, and walking to it or back to the station.
    /** Places the body stood at or walked to without finding a usable spot, so that they are not offered again. */
    private final Set<BlockPos> triedStands = new HashSet<>();
    private int walks;
    private int walkRestarts;
    @Nullable
    private BlockPos walkingTo;
    private int walkRadius;
    /** The walk in progress is back to the station this task has, not to a place for a new one. */
    private boolean returning;
    /** Walks back to the station in a row that ended with it still out of reach. */
    private int returns;
    /** Why the last walk to a place for the station failed; null once a walk has succeeded. */
    @Nullable
    private String walkFailure;
    /**
     * The widest radius a search for a place to stand has reached, kept across the walks: what a {@link Failure.NoPlace}
     * reports even when the walks, not the search, were what gave out.
     */
    private int lookedOut;
    /** This task holds the sneak key for the click. */
    private boolean sneaking;
    private int placeRounds;

    protected StationTask(NodeSpec leaf, Map<String, String> hints) {
        this.leaf = leaf;
        this.hints = hints;
        this.takeUpOnly = false;
    }

    protected StationTask(BlockPos standing) {
        this.leaf = null;
        this.hints = Map.of();
        this.takeUpOnly = true;
        this.station = standing.immutable();
    }

    // ---------------------------------------------------------------------------------------------------------
    // What a task says about its station and its work
    // ---------------------------------------------------------------------------------------------------------

    /** The item of the station this task works at and takes up again, or null when the work needs none. */
    @Nullable
    protected abstract Item stationItem();

    /** The block the station's item becomes when it is put down. */
    protected abstract Block stationBlock();

    /** The station as words say it ("crafting table", "furnace"): for failures and the log. */
    protected abstract String stationName();

    /** What a {@link Failure.NoPlace} says the body looked for a place for ("put a crafting table down"). */
    protected abstract String stationVerb();

    /** True while the station's own menu is the body's open one. */
    protected abstract boolean stationMenuOpen(BotPlayer body);

    /** Reads the leaf and the game in {@link #begin}; may veto with {@link #fail}. */
    protected abstract void beginStation(BotPlayer body);

    /**
     * Whether a station of this task's kind standing at {@code pos} is one this task may use where it stands. A table
     * is anyone's to work at; a furnace is a container, which block safety keeps the body out of unless it placed it,
     * so the smelt task asks the body's memory.
     */
    protected boolean mayUseStanding(BotPlayer body, ServerLevel level, BlockPos pos) {
        return true;
    }

    /**
     * Whether the station this task placed is to be taken up again now. One whose break would drop nothing (a furnace
     * by hand) is left standing, remembered, and the task completes all the same.
     */
    protected boolean takesUpStation(BotPlayer body) {
        return true;
    }

    /**
     * What must be carried before the station is put down, or null: asked of the smelt task, whose ingredient and
     * fuel are checked there so a smelt that cannot run puts nothing down.
     */
    @Nullable
    protected Failure missingCarried(BotPlayer body) {
        return null;
    }

    /** True when the work is finished, so the station can be taken up again and the task can end. */
    protected abstract boolean workDone(BotPlayer body);

    /** The work of this task, with the station's menu open (or with no menu, when the work needs no station). */
    protected abstract TickResult work(BotPlayer body);

    /** Gives back to the inventory what the work left in the inventory menu's own slots (a craft's 2x2 grid). */
    protected void giveBack(BotPlayer body, MenuHand own) {}

    /** True while the work waits on the game and not on the body (a furnace burning its run). */
    protected boolean waitingWork() {
        return false;
    }

    /**
     * Whether what the station held is gathered from the ground after its own item is in. The tidy step breaks a
     * furnace that still holds the body's ingredient, fuel or result, and what drops beside it is as much the body's
     * as the furnace: it is picked up ({@link DropPickup}, any item within {@link #SPILL_RADIUS} blocks of where the
     * station stood) before the task ends.
     */
    protected boolean gathersSpills() {
        return false;
    }

    // ---------------------------------------------------------------------------------------------------------
    // The take-up task, and what the tidy step looks for
    // ---------------------------------------------------------------------------------------------------------

    /**
     * A task that works nothing and only takes the station at {@code standing} up, which this body put down and was
     * stopped before it could take back (a restart, a cancel, a failure): the planner counts the station in the
     * inventory, not in the world, so one left standing would cost a goal its materials. It is the same break and
     * pickup the task ends with, so it ends the same way: the item back in the inventory, or a failure saying why not.
     * Which task it is depends on the block that stands there: one of the stations above, or — when the block is some
     * profession's job site — the job-site take-up, for a lectern a cancelled reroll left standing is as much this
     * body's to take back as its crafting table.
     */
    public static Task<BotPlayer> takeUp(ServerLevel level, BlockPos standing) {
        BlockState state = level.getBlockState(standing);
        if (state.is(Blocks.FURNACE)) return SmeltTask.takeUp(standing, Items.FURNACE, Blocks.FURNACE);
        if (state.is(Blocks.BLAST_FURNACE)) return SmeltTask.takeUp(standing, Items.BLAST_FURNACE, Blocks.BLAST_FURNACE);
        if (state.is(Blocks.SMOKER)) return SmeltTask.takeUp(standing, Items.SMOKER, Blocks.SMOKER);
        if (state.is(Blocks.STONECUTTER)) return StonecutTask.takeUp(standing);
        if (state.is(Blocks.SMITHING_TABLE)) return SmithTask.takeUp(standing);
        Item item = state.getBlock().asItem();
        if (item != Items.AIR && TradeTask.isJobSiteOfAProfession(state.getBlock())) {
            return new JobSiteTakeUp(standing, item, state.getBlock());
        }
        return CraftTask.takeUp(standing);
    }

    /**
     * The nearest block this body left standing that the tidy step may take up, or null: a station within reach that
     * its memory says it placed — a crafting table and a smithing table always (a break by hand drops them); a
     * furnace and a stonecutter while the body carries a tool that makes them drop, for one broken by hand drops
     * nothing and there would be no item to wait for, a furnace whether or not it holds anything, for breaking one
     * drops what it holds beside it and the take-up picks that up too — or a job site it put down for a reroll that
     * no villager is to keep ({@link #leftoverJobSite}), wherever in the dimension it stands. A position marked kept
     * in the body's memory is nobody's to take up, whatever block stands there. What {@link #takeUp} is for: a
     * runtime asks after a restart and when a goal ends.
     */
    @Nullable
    public static BlockPos leftover(BotPlayer body) {
        ServerLevel level = body.serverLevel();
        BotMemory memory = BotMemory.of(body.getUUID());
        String dimension = dimension(level);
        BlockPos centre = body.blockPosition();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(centre.offset(-STATION_SEARCH_RADIUS, -STATION_SEARCH_RADIUS, -STATION_SEARCH_RADIUS),
                centre.offset(STATION_SEARCH_RADIUS, STATION_SEARCH_RADIUS, STATION_SEARCH_RADIUS))) {
            if (!level.isLoaded(pos) || !memory.isPlaced(dimension, pos) || !body.canInteractWithBlock(pos, 0.0)) continue;
            if (memory.isKept(dimension, pos)) continue; // a job site kept for a villager is not a leftover
            if (!tidyMayTakeUp(level, body, pos)) continue;
            double distance = pos.distToCenterSqr(body.getEyePosition());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = pos.immutable();
            }
        }
        BlockPos site = leftoverJobSite(body, level, memory, dimension);
        if (site != null && (best == null || site.distToCenterSqr(body.getEyePosition()) < bestDistance)) best = site;
        return best;
    }

    /**
     * The nearest remembered placed block in the dimension that is some profession's job-site block and no villager is
     * to keep, or null. A reroll that was cancelled or aborted leaves its lectern standing and remembered, and the
     * planner knows only what the body carries, so the plan that follows would make another before the trade task
     * took the first up; the tidy step takes this one up first instead. A site marked kept is kept only while some
     * villager's brain names it as its job site — the villager it was kept for keeps the offer only while it keeps
     * the job — so one nobody works at any more is unmarked and taken up like any other. A villager that has traded
     * works at its site for good and is never taken from, and a block whose break the body has not the tool for is
     * left standing (breaking it would drop nothing), remembered for a body that carries one.
     */
    @Nullable
    private static BlockPos leftoverJobSite(BotPlayer body, ServerLevel level, BotMemory memory, String dimension) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos at : memory.placedIn(dimension)) {
            if (!level.isLoaded(at)) continue; // a read never loads a chunk
            BlockState state = level.getBlockState(at);
            if (!TradeTask.isJobSiteOfAProfession(state.getBlock())) continue;
            if (memory.isKept(dimension, at)) {
                if (TradeTask.aVillagerWorksAt(level, at)) continue;
                // Nobody's brain names it any more: the villager it was kept for is gone or has lost the profession,
                // and with it the offer the site was kept for. The mark is off before the block goes.
                memory.forgetKept(dimension, at);
                MegaBlockBot.LOGGER.info("{}: the job site at {} was kept for a villager that no longer works there;"
                        + " it is the body's to take up again", body.getGameProfile().getName(), at.toShortString());
            } else if (TradeTask.keptByATrader(level, at)) {
                continue;
            }
            if (state.getBlock().asItem() == Items.AIR) continue; // nothing would come of the break
            if (state.requiresCorrectToolForDrops() && !dropsFor(body, state)) continue;
            double distance = at.distToCenterSqr(body.getEyePosition());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = at.immutable();
            }
        }
        return best;
    }

    /**
     * The take-up for a block that is a villager's job site (the lectern of a reroll): the stations' own break and
     * pickup, working nothing, for a block that is nobody's station. The item and block come with it, for a job site
     * can be any profession's.
     */
    static final class JobSiteTakeUp extends StationTask {
        private final Item item;
        private final Block block;
        private final String name;

        JobSiteTakeUp(BlockPos standing, Item item, Block block) {
            super(standing);
            this.item = item;
            this.block = block;
            this.name = BuiltInRegistries.ITEM.getKey(item).getPath().replace('_', ' ');
        }

        @Override
        protected Item stationItem() {
            return item;
        }

        @Override
        protected Block stationBlock() {
            return block;
        }

        @Override
        protected String stationName() {
            return name;
        }

        @Override
        protected String stationVerb() {
            return "put a " + name + " down";
        }

        @Override
        protected boolean stationMenuOpen(BotPlayer body) {
            return false; // a job site is never opened: the take-up only breaks the block and picks its item up
        }

        @Override
        protected void beginStation(BotPlayer body) {
            // nothing to begin: the station's item and block came with the constructor, and there is no work
        }

        @Override
        protected boolean workDone(BotPlayer body) {
            return true; // there is no work: taking the block up is all this task does
        }

        @Override
        protected TickResult work(BotPlayer body) {
            return complete(body);
        }

        @Override
        protected boolean takesUpStation(BotPlayer body) {
            // The stonecut task's rule: a job site broken without the tool it needs drops nothing, so one the body
            // cannot make drop is left standing, remembered, for a body that carries the tool.
            if (station == null) return false;
            BlockState state = body.serverLevel().getBlockState(station);
            return !state.requiresCorrectToolForDrops() || dropsFor(body, state);
        }
    }

    private static boolean tidyMayTakeUp(ServerLevel level, BotPlayer body, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.is(Blocks.CRAFTING_TABLE) || state.is(Blocks.SMITHING_TABLE)) return true;
        // A stonecutter is stone and a furnace is a machine: both drop only for a tool, and a break by hand would
        // destroy the station the tidy step is there to recover.
        if (state.is(Blocks.STONECUTTER) || state.getBlock() instanceof AbstractFurnaceBlock) return dropsFor(body, state);
        return false;
    }

    /** Whether the body carries something that makes the block drop when broken: a furnace broken by hand drops nothing. */
    static boolean dropsFor(BotPlayer body, BlockState state) {
        for (int slot = 0; slot < MenuHand.CARRIED_SLOTS; slot++) {
            ItemStack stack = body.getInventory().getItem(slot);
            if (!stack.isEmpty() && stack.isCorrectToolForDrops(state)) return true;
        }
        return false;
    }

    // ─── What a run at a station shares: choosing an input, asking for one, taking the result ──────────────────

    /** The first of the items in their own order that the body carries, as a craft takes the first alternative held. */
    @Nullable
    static Item firstCarried(BotPlayer body, List<Item> items) {
        for (Item item : items) {
            if (MenuHand.carriedCount(body, item) > 0) return item;
        }
        return null;
    }

    /**
     * The missing-input failure for one input slot of a recipe, or null when the body carries enough of the slot's
     * items: it names the slot's first alternative and how many are short, for that is the item a gather would seek.
     */
    @Nullable
    static Failure missingInput(BotPlayer body, List<Item> alternatives, int wanted, String what) {
        int carried = 0;
        for (Item item : alternatives) carried += MenuHand.carriedCount(body, item);
        if (carried >= wanted) return null;
        return new Failure.MissingInput(ItemKeys.of(alternatives.get(0)), wanted - carried,
            "the " + what + " needs " + wanted + " of " + ItemKeys.of(alternatives.get(0)).id()
                + " and the body carries " + carried);
    }

    /**
     * Shift-clicks what the slot holds into the inventory and counts what arrived, the take every station task makes
     * of a result slot. The take is whole or not at all — the craft's rule, for a take in part would drop the rest at
     * the feet of a body that cannot pick it up — so the slot's whole stack must have room before anything moves.
     * Null when all of it arrived; otherwise why not. {@code where} names the station in the detail, where the task
     * knows it.
     */
    @Nullable
    protected Failure takeResult(BotPlayer body, AbstractContainerMenu menu, int menuSlot, String where) {
        Slot slot = menu.getSlot(menuSlot);
        if (!slot.hasItem()) return null;
        ItemStack offered = slot.getItem();
        // The slot's own stack: the move empties it, and an emptied stack answers air. What it was is read first.
        Item item = offered.getItem();
        int made = offered.getCount();
        int room = MenuHand.roomFor(body, offered);
        if (room < made) {
            return new Failure.NoRoom(ItemKeys.of(item), "there is no room in the inventory for the " + made + " "
                + ItemKeys.of(item).id() + " the slot holds (room for " + room + ")" + where);
        }
        int before = MenuHand.carriedCount(body, item);
        new MenuHand(body, menu).moveOut(menuSlot);
        int arrived = MenuHand.carriedCount(body, item) - before;
        if (arrived < made) {
            return new Failure.NoProgress(0, "only " + arrived + " of the " + made + " "
                + ItemKeys.of(item).id() + " taken from the slot reached the inventory; the rest was dropped at the feet"
                + where);
        }
        return null;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------------------------------------

    @Override
    protected final void begin(BotPlayer body) {
        beginStation(body);
    }

    @Override
    protected TickResult onTick(BotPlayer body) {
        if (cooldown > 0) {
            cooldown--;
            return TickResult.RUNNING;
        }
        return switch (phase) {
            case PREPARE -> prepare(body);
            case WALK -> walk(body);
            case PLACE -> place(body);
            case OPEN -> open(body);
            case WORK -> workPhase(body);
            case BREAK -> breakStation(body);
            case PICKUP -> pickUp(body);
        };
    }

    @Override
    protected void resumeWork(BotPlayer body) {
        // The world may have changed while paused, and a reflex may have carried the body off: everything is looked at
        // again from the top, and a station that stands out of reach is walked back to first.
        phase = Phase.PREPARE;
        cooldown = 0;
        phaseTicks = 0;
        dropWalkFailed = false;
    }

    /** True while the task waits on the game and not on the body: a station being broken, an item coming back, work waiting. */
    @Override
    public boolean waiting() {
        return phase == Phase.BREAK || phase == Phase.PICKUP || cooldown > 0 || waitingWork();
    }

    @Override
    public Map<String, String> checkpoint() {
        return station == null ? Map.of()
            : Map.of("station", new Pos(station.getX(), station.getY(), station.getZ()).encode());
    }

    // ---------------------------------------------------------------------------------------------------------
    // Phases
    // ---------------------------------------------------------------------------------------------------------

    private TickResult prepare(BotPlayer body) {
        if (!workDone(body)) {
            if (stationItem() == null) {
                phase = Phase.WORK;
                return workPhase(body);
            }
            BlockPos standing = findStation(body);
            if (standing != null) {
                station = standing;
                returns = 0;
                phase = Phase.OPEN;
                return TickResult.RUNNING;
            }
            TickResult back = walkBackToStation(body);
            return back != null ? back : seekSite(body);
        }
        // The work is done. A station this body put down comes up again; one it found is left standing, and so is one
        // whose break would drop nothing.
        if (stationItem() != null && station != null && isOurs(body)) {
            if (takesUpStation(body)) return takeStationUp(body);
            MegaBlockBot.LOGGER.info("{} leaves the {} at {} standing: the body carries no tool that makes it drop when broken",
                body.getGameProfile().getName(), stationName(), station.toShortString());
            return complete(body);
        }
        if (spillAt != null) {
            // The station is gone and its own item is in, but what it held lies on the ground yet: the pick-up's
            // gather finishes that before the task ends (a pause in the middle of it comes back here).
            phase = Phase.PICKUP;
            phaseTicks = 0;
            return TickResult.RUNNING;
        }
        if (stationItem() != null) {
            // No station of the body's to take up: one it found standing, or none it still knows of. Said, because a
            // task that ends here with its own station left behind has lost track of it.
            MegaBlockBot.LOGGER.info("{} ends its work at the {} without taking one up: {}", body.getGameProfile().getName(),
                stationName(), station == null ? "it knows of none" : "the one at " + station.toShortString() + " is not its own");
        }
        return complete(body);
    }

    private TickResult workPhase(BotPlayer body) {
        if (stationItem() != null) {
            if (!stationMenuOpen(body)) {
                // Closed under the task (the station went, or the body was moved away): open it again.
                phase = Phase.OPEN;
                return TickResult.RUNNING;
            }
        } else if (body.containerMenu != body.inventoryMenu) {
            body.closeContainer();
        }
        return work(body);
    }

    /**
     * The work is made and its station is the body's to take up. Gone already: broken by this task just before a pause
     * came between the break and the pickup, or by someone else; its item is picked up when one lies within reach of a
     * walk, and otherwise there is nothing to take. Standing: it is walked back to when the body was carried off, then
     * broken.
     */
    private TickResult takeStationUp(BotPlayer body) {
        ServerLevel level = body.serverLevel();
        if (!level.isLoaded(station)) {
            return stop(body, new Failure.Unreachable("the " + stationName() + " at " + station.toShortString(), "not loaded",
                "the " + stationName() + " to take up is in a chunk that is not loaded"));
        }
        if (!level.getBlockState(station).is(stationBlock())) {
            BotMemory.of(body.getUUID()).forgetPlaced(dimension(level), station);
            if (nearestDrop(body) != null) {
                stationsBefore = MenuHand.carriedCount(body, stationItem());
                phase = Phase.PICKUP;
                phaseTicks = 0;
                return TickResult.RUNNING;
            }
            // Gone before it could be taken up, and no item of it to be found: nothing to take, and nothing to remember.
            forgetStation(body);
            return complete(body);
        }
        if (!body.canInteractWithBlock(station, 0.0)) {
            TickResult back = walkBackToStation(body);
            if (back != null) return back;
            return stop(body, new Failure.Unreachable("the " + stationName() + " at " + station.toShortString(), "out of reach",
                "the " + stationName() + " to take up is more than " + RETURN_RADIUS + " blocks from the body"));
        }
        returns = 0;
        phase = Phase.BREAK;
        phaseTicks = 0;
        refusals = 0;
        return TickResult.RUNNING;
    }

    /**
     * A station of this task's stands out of the body's reach (a reflex carried the body off, or the station is the
     * checkpoint's and the body has moved since): walks back to it. Null when there is nothing to walk back to: no
     * station, a station that is gone or in a chunk that is not loaded, or one further than {@link #RETURN_RADIUS}
     * blocks off.
     */
    @Nullable
    private TickResult walkBackToStation(BotPlayer body) {
        ServerLevel level = body.serverLevel();
        if (station == null || !level.isLoaded(station) || !level.getBlockState(station).is(stationBlock())) return null;
        if (station.distSqr(body.blockPosition()) > (double) RETURN_RADIUS * RETURN_RADIUS) return null;
        if (++returns > RETURNS_ALLOWED) {
            return stop(body, new Failure.Unreachable("the " + stationName() + " at " + station.toShortString(), "out of reach",
                "walked back toward the " + stationName() + " " + RETURNS_ALLOWED + " times and it is still out of reach"));
        }
        returning = true;
        walkRestarts = 0;
        walkingTo = station;
        walkRadius = RETURN_STAND_RADIUS;
        nav(body).goTo(station, walkRadius);
        phase = Phase.WALK;
        phaseTicks = 0;
        return TickResult.RUNNING;
    }

    /**
     * No station stands within reach: either a spot beside the body will do, or the body walks to a place that has one, or
     * there is none to be had and the task fails saying how far it looked. A refusal by block safety of every candidate
     * widens the search once, to get out of a protected area; it is never the failure itself.
     */
    private TickResult seekSite(BotPlayer body) {
        ServerLevel level = body.serverLevel();
        Failure missing = missingCarried(body);
        if (missing != null) return stop(body, missing);
        Item item = stationItem();
        if (item != null && MenuHand.carriedCount(body, item) == 0) {
            return stop(body, new Failure.MissingInput(ItemKeys.of(item), 1,
                "no " + stationName() + " stands within reach and none is carried"));
        }
        TableSites.Refusal refusal = new TableSites.Refusal();
        BlockPos feet = body.blockPosition();
        if (!TableSites.sitesFrom(body, level, feet, TableSites.sneakingEye(body, feet, true), true, 1, refusal).isEmpty()) {
            // The click is made sneaking, which takes a tick to show on the body: see place().
            body.input().sneak = true;
            sneaking = true;
            phase = Phase.PLACE;
            phaseTicks = 0;
            return TickResult.RUNNING;
        }
        triedStands.add(feet.immutable());
        int radius = TableSites.radius(body.getUUID());
        int looked = radius;
        BlockPos stand = null;
        if (walks < WALKS_ALLOWED) {
            stand = TableSites.nearestStand(body, level, 1, radius, triedStands, refusal);
            int wide = TableSites.wideRadius(body.getUUID());
            if (stand == null && refusal.any() && wide > radius) {
                // Everything within the radius that a station could go on is a spot block safety refuses: the body is in a
                // protected area, and the way out is further than the radius. The shells already searched are not repeated.
                looked = wide;
                stand = TableSites.nearestStand(body, level, radius + 1, wide, triedStands, refusal);
            }
        }
        // The walks may have been to places a wide search found, so the failure names the radius actually searched, not
        // only this call's: giving out on the walks is not giving out on a narrower search.
        lookedOut = Math.max(lookedOut, looked);
        if (stand == null) return stop(body, noSite(lookedOut, refusal));
        walks++;
        walkRestarts = 0;
        returning = false;
        walkingTo = stand;
        walkRadius = 0;
        triedStands.add(stand);
        nav(body).goTo(stand, walkRadius);
        phase = Phase.WALK;
        phaseTicks = 0;
        return TickResult.RUNNING;
    }

    /**
     * Why there is nowhere to put the station, in the type that says what the executor should do about it: always a
     * {@link Failure.NoPlace}, because none of the reasons is something a plan can work around. {@code looked} is the
     * widest radius the search reached.
     */
    private Failure noSite(int looked, TableSites.Refusal refusal) {
        String why;
        if (walkFailure != null) {
            why = "walked to " + walks + " place(s) with a spot for a " + stationName() + " beside them and could not get to the last: "
                + walkFailure;
        } else if (walks >= WALKS_ALLOWED) {
            why = "walked to " + walks + " places and found no usable spot for a " + stationName() + " at any of them";
        } else if (refusal.any()) {
            why = "no spot within " + looked + " blocks that a " + stationName() + " could go on is one block safety lets the body break again ("
                + refusal.verdict.rule() + ": " + refusal.verdict.detail() + ", first refused at " + refusal.at.toShortString() + ")";
        } else {
            why = "nowhere to stand with a free spot beside it that a block can be placed on and the " + stationName() + " taken up from";
        }
        return new Failure.NoPlace(stationVerb(), looked, why);
    }

    private TickResult walk(BotPlayer body) {
        BotNavigator nav = nav(body);
        switch (nav.state()) {
            case DONE -> {
                nav.stop();
                if (!returning) walkFailure = null; // the last walk got there: an earlier one that did not is no longer why
                phase = Phase.PREPARE; // looked at again from where the body now is
            }
            case FAILED -> {
                String why = nav.failure();
                nav.stop();
                if (returning) return stop(body, unreachableStation(why == null ? "no path" : why));
                walkFailure = why;
                phase = Phase.PREPARE; // the next nearest place, or the failure
            }
            case IDLE -> {
                // Something else stopped the navigator: this task still owns the body.
                if (++walkRestarts > WALK_RESTARTS_ALLOWED) {
                    String why = "the navigator stopped " + walkRestarts + " times on the way to " + walkingTo.toShortString();
                    if (returning) return stop(body, unreachableStation(why));
                    walkFailure = why;
                    phase = Phase.PREPARE;
                    return TickResult.RUNNING;
                }
                nav.goTo(walkingTo, walkRadius);
            }
            case WORKING -> { }
        }
        return TickResult.RUNNING;
    }

    private Failure unreachableStation(String why) {
        return new Failure.Unreachable("the " + stationName() + " at " + station.toShortString(), why,
            "could not walk back to the " + stationName() + ": " + why);
    }

    /**
     * Puts the station item down on a spot beside the body, through the player's own item use, sneaking. The sites are
     * looked at again from the sneaking eye, because the world is the judge: the one clicked first that takes is the
     * station.
     */
    private TickResult place(BotPlayer body) {
        ServerLevel level = body.serverLevel();
        if (!body.isShiftKeyDown()) {
            // A condition of the world, not a bug: the retry and the replan the kind earns are the right answers.
            if (++phaseTicks > SNEAK_WAIT_TICKS) {
                return stop(body, new Failure.NoProgress(phaseTicks, "the body would not sneak to place the " + stationName()));
            }
            return TickResult.RUNNING;
        }
        Item item = stationItem();
        if (item == null || !hold(body, item)) {
            return stop(body, new Failure.NoProgress(0, "the " + stationName() + " could not be taken into the hand"));
        }
        BlockPos feet = body.blockPosition();
        // The eye of the look in seekSite, not whatever the pose has reached: the click is made sneaking.
        List<TableSites.Site> sites = TableSites.sitesFrom(body, level, feet, TableSites.sneakingEye(body, feet, true), true, SITES_TRIED,
            new TableSites.Refusal());
        for (TableSites.Site site : sites) {
            body.input().lookAt(site.click());
            body.getInteractionManager().interactBlock(body, level, body.getMainHandItem(), InteractionHand.MAIN_HAND,
                new BlockHitResult(site.click(), site.face(), site.against(), false));
            if (!level.getBlockState(site.spot()).is(stationBlock())) continue;
            station = site.spot().immutable();
            placedHere = true;
            stationsBefore = MenuHand.carriedCount(body, item);
            BotMemory.of(body.getUUID()).notePlaced(dimension(level), station);
            BlockSafety.of(level.getServer()).noteBotPlaced(body, level, station);
            letGoOfSneak(body);
            // The sneak is released a tick before the station is used: a click that sneaks would place another, not open.
            cooldown = 1;
            phase = Phase.OPEN;
            return TickResult.RUNNING;
        }
        // Nothing took (a look from the sneaking eye that found no site, or the game refused each): from the top again.
        letGoOfSneak(body);
        if (++placeRounds > PLACE_ROUNDS_ALLOWED) {
            return stop(body, new Failure.Unreachable("a place for the " + stationName(), "the game would not let the body put it down",
                "the " + stationName() + " was not placed from " + feet.toShortString() + " in " + placeRounds + " tries"));
        }
        phase = Phase.PREPARE;
        return TickResult.RUNNING;
    }

    private void letGoOfSneak(BotPlayer body) {
        if (!sneaking) return;
        body.input().sneak = false;
        body.input().clearLook();
        sneaking = false;
    }

    private TickResult open(BotPlayer body) {
        ServerLevel level = body.serverLevel();
        if (station == null || !level.getBlockState(station).is(stationBlock())) {
            forgetStation(body); // gone since it was chosen: the memory forgets it, and a station is chosen again
            phase = Phase.PREPARE;
            return TickResult.RUNNING;
        }
        if (!body.canInteractWithBlock(station, 0.0)) {
            phase = Phase.PREPARE; // carried off since the station was chosen: the way back is looked for from the top
            return TickResult.RUNNING;
        }
        if (body.isShiftKeyDown()) {
            // A use-click while sneaking with the station item in the hand would put a second one down, not open this one.
            body.input().sneak = false;
            cooldown = 1;
            return TickResult.RUNNING;
        }
        if (!stationMenuOpen(body)) {
            Vec3 top = new Vec3(station.getX() + 0.5, station.getY() + 1.0, station.getZ() + 0.5);
            body.getInteractionManager().interactBlock(body, level, body.getMainHandItem(), InteractionHand.MAIN_HAND,
                    new BlockHitResult(top, Direction.UP, station, false));
        }
        if (!stationMenuOpen(body)) {
            if (++refusals > REFUSALS_ALLOWED) {
                // Protected, not TargetGone: the executor excludes this block, and the next plan puts its own station
                // elsewhere instead of being replanned into the same unopenable one.
                return stop(body, new Failure.Protected(corePos(station), "the game would not open it",
                        "the game would not open the " + stationName() + " at " + station.toShortString()));
            }
            cooldown = 1;
            return TickResult.RUNNING;
        }
        phase = Phase.WORK;
        cooldown = 1;
        return TickResult.RUNNING;
    }

    private TickResult breakStation(BotPlayer body) {
        ServerLevel level = body.serverLevel();
        BlockState state = level.getBlockState(station);
        if (!state.is(stationBlock())) {
            // Broken, by this task or by someone else: either way what is left is picking the item up.
            BotMemory.of(body.getUUID()).forgetPlaced(dimension(level), station);
            phase = Phase.PICKUP;
            phaseTicks = 0;
            return TickResult.RUNNING;
        }
        if (++phaseTicks > BREAK_WAIT_TICKS) {
            // The work is made, and that is what the goal asked for, so the station's fate cannot fail it. The break
            // never finished, so it stands on; it stays remembered as the body's, and the tidy step or the next task
            // takes it up (the break in progress is stopped first, while the position is still known: a release after
            // the station is forgotten cannot stop it).
            MegaBlockBot.LOGGER.info("{} stops breaking the {} at {} after {} ticks and leaves it standing: the work is made",
                body.getGameProfile().getName(), stationName(), station.toShortString(), phaseTicks);
            abortBreaking(body);
            station = null;
            return complete(body);
        }
        IInteractionManager hand = body.getInteractionManager();
        if (!body.canInteractWithBlock(station, 0.0)) {
            // Pushed or carried away since the break began: it is let go of, and the body goes back to the station.
            if (hand.isMining()) {
                hand.processBlockBreakingAction(station, Action.ABORT_DESTROY_BLOCK, Direction.UP, level.getMaxBuildHeight(), sequence++);
            }
            phase = Phase.PREPARE;
            return TickResult.RUNNING;
        }
        Item item = stationItem();
        if (!hand.isMining()) {
            if (item != null && MenuHand.roomFor(body, new ItemStack(item)) == 0) {
                // The work is made, and that is what the goal asked for. The inventory cannot take the station's item,
                // so breaking it would leave it on the floor of a body that cannot pick it up: it stays standing and
                // remembered as the body's, and the tidy step or the next task takes it up once there is room.
                MegaBlockBot.LOGGER.info("{} leaves the {} at {} standing: the inventory has no room for its item",
                    body.getGameProfile().getName(), stationName(), station.toShortString());
                station = null;
                return complete(body);
            }
            Verdict verdict = BlockSafety.of(level.getServer()).mayBreak(body, level, station, state);
            if (!verdict.allowed()) {
                // The work is made, and that is what the goal asked for, so this is a success. The station stays where
                // it is, and stays in the body's memory as its own: the tidy step and the next task that finds it take
                // it up.
                MegaBlockBot.LOGGER.info("{} leaves the {} at {} standing: it may not break it ({}: {})",
                    body.getGameProfile().getName(), stationName(), station.toShortString(), verdict.rule(), verdict.detail());
                station = null;
                return complete(body);
            }
            if (phaseTicks == 1) stationsBefore = MenuHand.carriedCount(body, item);
            if (refusals > REFUSALS_ALLOWED) {
                return stop(body, new Failure.Unreachable("the " + stationName() + " at " + station.toShortString(),
                        "the game would not start breaking it", ""));
            }
            selectBreakingTool(body, state);
            hand.processBlockBreakingAction(station, Action.START_DESTROY_BLOCK, Direction.UP, level.getMaxBuildHeight(), sequence++);
            if (!hand.isMining() && level.getBlockState(station).is(stationBlock())) refusals++;
            return TickResult.RUNNING;
        }
        hand.continueBreaking(station, Direction.UP);
        // A client sends the finish once its own progress reaches 1; the engine reports progress in tenths.
        if (hand.isMining() && hand.getBlockBreakingProgress() >= 10) {
            hand.processBlockBreakingAction(hand.getMiningPos(), Action.STOP_DESTROY_BLOCK, Direction.UP, level.getMaxBuildHeight(), sequence++);
        }
        return TickResult.RUNNING;
    }

    private TickResult pickUp(BotPlayer body) {
        Item item = stationItem();
        if (item != null && MenuHand.carriedCount(body, item) > stationsBefore) {
            // The station's own item is in. What it held fell beside it — the tidy step breaks a furnace with the
            // body's ingredient, fuel or result still inside — and that is picked up too before the task ends.
            if (gathersSpills() && gatherSpills(body)) return TickResult.RUNNING;
            station = null;
            return complete(body);
        }
        if (++phaseTicks > PICKUP_WAIT_TICKS) {
            // The work is made, and that is what the goal asked for, so the station's fate cannot fail it. Its item
            // lies somewhere the body did not reach in time, and a drop is nothing the code can remember it by; the
            // log says what was given up on.
            MegaBlockBot.LOGGER.info("{} stops waiting for the broken {}'s item after {} ticks: the work is made",
                body.getGameProfile().getName(), stationName(), phaseTicks);
            station = null;
            return complete(body);
        }
        if (phaseTicks == 1) inputSteps = 0; // every pick-up counts its own steps
        if (phaseTicks > PICKUP_PATIENCE_TICKS) goToDrop(body);
        return TickResult.RUNNING;
    }

    /** Gathers what fell out of the station around where it stood; true while that goes on. */
    private boolean gatherSpills(BotPlayer body) {
        if (spillAt == null) spillAt = station;
        if (spillAt == null) return false;
        if (!gathering) {
            gathering = true;
            spillPickup.start();
        }
        return spillPickup.tick(this, body, drop -> true, new AABB(spillAt).inflate(SPILL_RADIUS));
    }

    /** The nearest dropped station item within {@link #DROP_SEARCH_RADIUS} blocks of where the station stood, or null. */
    @Nullable
    private ItemEntity nearestDrop(BotPlayer body) {
        Item item = stationItem();
        if (item == null || station == null) return null;
        ItemEntity nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (ItemEntity drop : body.serverLevel().getEntitiesOfClass(ItemEntity.class, new AABB(station).inflate(DROP_SEARCH_RADIUS),
                candidate -> !candidate.isRemoved() && candidate.getItem().is(item))) {
            double distance = drop.distanceToSqr(body);
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = drop;
            }
        }
        return nearest;
    }

    /**
     * A dropped item hops away from the block it came from, and the pickup box of a standing player is only a block
     * wide on each side, so now and then the station's item comes to rest just out of reach; and a reflex may have
     * carried the body a dozen blocks off before the item was taken. The body goes to it by the means a player has:
     * through the navigator while it is further than {@link #NEAR_DROP}, by plain input for the last steps (or all of
     * them, when the navigator will not go). A near drop that {@link #INPUT_STEPS_ALLOWED} ticks of walking at it did
     * not reach lies where input does not bring the body, below an edge or behind one: the navigator is then asked for
     * the drop's own block, which makes the body step down to it. Nothing is moved or teleported. The input is reset
     * the tick the item is in the inventory, and by {@link #letGo} on every other exit.
     */
    private void goToDrop(BotPlayer body) {
        ItemEntity nearest = nearestDrop(body);
        BotNavigator nav = nav(body);
        if (nearest == null) {
            if (walking) body.input().reset();
            walking = false;
            if (nav.state() == BotNavigator.State.WORKING) nav.stop();
            return;
        }
        boolean far = nearest.distanceToSqr(body) > NEAR_DROP * NEAR_DROP;
        boolean exact = !far && inputSteps > INPUT_STEPS_ALLOWED;
        if (!dropWalkFailed && (far || exact)) {
            if (walking) body.input().reset();
            walking = false;
            switch (nav.state()) {
                case WORKING -> { }
                case FAILED -> {
                    nav.stop();
                    dropWalkFailed = true; // plain steps from the next tick
                }
                // Within a block of a far drop is near enough for the last steps; a drop input could not reach is
                // gone to exactly, since a goal a block wide is met from the edge above it.
                case IDLE, DONE -> nav.goTo(nearest.blockPosition(), exact ? 0 : 1);
            }
            return;
        }
        if (nav.state() == BotNavigator.State.WORKING) nav.stop();
        body.input().lookAt(nearest.position());
        body.input().forward = 1.0F;
        walking = true;
        inputSteps++;
    }

    /**
     * A task that fails itself is never aborted afterwards, so it lets go of what it holds first: the station's menu, a
     * break in progress, the cursor and the menu's own slots. A station it already put down stays, remembered, and the
     * next task that needs one finds it.
     */
    protected TickResult stop(BotPlayer body, Failure failure) {
        release(body);
        return TickResult.failed(failure);
    }

    /** The task ends well: it lets go of the body like every other exit, because the executor does not abort it afterwards. */
    protected TickResult complete(BotPlayer body) {
        release(body);
        return TickResult.COMPLETED;
    }

    /** Lets go of everything: the break, the sneak, the open menu, the cursor, the work's slots and the pick-up of what fell. Never throws on a body in any state. */
    @Override
    protected void letGo(BotPlayer body) {
        phase = Phase.PREPARE;
        phaseTicks = 0;
        if (body == null) return;
        letGoOfSneak(body);
        if (walking) {
            body.input().reset();
            walking = false;
        }
        if (gathering) {
            gathering = false;
            spillPickup.rest();
            body.input().reset();
        }
        abortBreaking(body);
        // Closing a menu hands back its slots and cursor (AbstractContainerMenu.removed).
        if (body.containerMenu != body.inventoryMenu) body.closeContainer();
        MenuHand own = new MenuHand(body, body.inventoryMenu);
        own.putBack();
        giveBack(body, own);
    }

    /**
     * Stops the break of the station in progress, if one is. The abort names the position being broken, so it is made
     * while the station is still known: a task that forgets the station first leaves its break running unowned, and
     * whatever touches the body next inherits a half-broken block.
     */
    private void abortBreaking(BotPlayer body) {
        IInteractionManager hand = body.getInteractionManager();
        if (hand.isMining() && station != null && station.equals(hand.getMiningPos())) {
            hand.processBlockBreakingAction(station, Action.ABORT_DESTROY_BLOCK, Direction.UP,
                    body.level().getMaxBuildHeight(), sequence++);
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // The station
    // ---------------------------------------------------------------------------------------------------------

    /**
     * The nearest station of this task's kind the body can use from where it stands, or null. A remembered one is
     * tried first; a remembered one that is gone is forgotten; one {@link #mayUseStanding} refuses is not one to use.
     */
    @Nullable
    private BlockPos findStation(BotPlayer body) {
        ServerLevel level = body.serverLevel();
        if (station != null && level.isLoaded(station)) {
            if (!level.getBlockState(station).is(stationBlock())) forgetStation(body);
            else if (body.canInteractWithBlock(station, 0.0)) return station;
        }
        BlockPos centre = body.blockPosition();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(centre.offset(-STATION_SEARCH_RADIUS, -STATION_SEARCH_RADIUS, -STATION_SEARCH_RADIUS),
                centre.offset(STATION_SEARCH_RADIUS, STATION_SEARCH_RADIUS, STATION_SEARCH_RADIUS))) {
            if (!level.isLoaded(pos) || !level.getBlockState(pos).is(stationBlock())) continue;
            if (!mayUseStanding(body, level, pos.immutable())) continue;
            if (!body.canInteractWithBlock(pos, 0.0)) continue;
            double distance = pos.distToCenterSqr(body.getEyePosition());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = pos.immutable();
            }
        }
        return best;
    }

    /** The station this task had in mind is not there: the body's memory of having placed it goes too. */
    protected void forgetStation(BotPlayer body) {
        if (station != null) BotMemory.of(body.getUUID()).forgetPlaced(dimension(body.serverLevel()), station);
        station = null;
        placedHere = false;
    }

    /**
     * Takes the item into the main hand as a player would: it is selected when it is on the hotbar, otherwise the
     * inventory menu swaps it into the selected hotbar slot. Only carried storage is looked at (see {@link MenuHand}).
     */
    /** Package-private: the trade task puts a job site down the same way, from the hand. */
    static boolean hold(BotPlayer body, Item item) {
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

    /**
     * Takes into the hand what the block is to be broken with. A block that drops nothing without the right tool (a
     * furnace) gets that tool, the fastest carried, from anywhere in the inventory: putting the station into the hand
     * to place it swaps whatever was there out of the hotbar, often the pickaxe, and a furnace broken with what was
     * left in reach is a furnace destroyed. Any other block gets the hotbar slot that breaks it fastest, and of
     * equals one that cannot wear out, as a player reaches for an axe or an empty hand: a table broken with the
     * pickaxe just made would cost it durability for nothing.
     */
    static void selectBreakingTool(BotPlayer body, BlockState state) {
        var inventory = body.getInventory();
        if (state.requiresCorrectToolForDrops()) {
            int tool = -1;
            float toolSpeed = -1.0F;
            for (int slot = 0; slot < MenuHand.CARRIED_SLOTS; slot++) {
                ItemStack stack = inventory.getItem(slot);
                if (stack.isEmpty() || !stack.isCorrectToolForDrops(state)) continue;
                float speed = stack.getDestroySpeed(state);
                if (speed > toolSpeed) {
                    toolSpeed = speed;
                    tool = slot;
                }
            }
            if (tool >= 0) {
                Weapons.toMainHand(body, tool);
                return;
            }
        }
        int best = inventory.selected;
        float bestSpeed = -1.0F;
        boolean bestSafe = false;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = inventory.getItem(slot);
            float speed = stack.isEmpty() ? 1.0F : stack.getDestroySpeed(state);
            boolean safe = stack.isEmpty() || !stack.isDamageableItem();
            if (speed > bestSpeed || (speed == bestSpeed && safe && !bestSafe)) {
                best = slot;
                bestSpeed = speed;
                bestSafe = safe;
            }
        }
        inventory.selected = best;
    }

    /** The station is this body's to take up: this task put it down, or the body's memory says an earlier one did. */
    boolean isOurs(BotPlayer body) {
        return station != null && (placedHere || BotMemory.of(body.getUUID()).isPlaced(dimension(body.serverLevel()), station));
    }

    static String dimension(ServerLevel level) {
        return level.dimension().location().toString();
    }
}
