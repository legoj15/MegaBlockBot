package com.legoj15.megablockbot.game.task;

import com.legoj15.megablockbot.MegaBlockBot;
import com.legoj15.megablockbot.core.ItemKey;
import com.legoj15.megablockbot.core.Pos;
import com.legoj15.megablockbot.core.goal.Goal;
import com.legoj15.megablockbot.core.plan.NodeSpec;
import com.legoj15.megablockbot.core.planner.RecipeDef;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The {@code craft} task (design 4.2, {@link RecipeDef#CRAFT}): runs one crafting recipe {@code times} times the way a
 * player does, through the real menus with slot clicks, one craft every few ticks.
 *
 * <p>Leaf params: {@code recipe} (the game recipe's id), {@code times}, {@code station} (the item that must be at hand,
 * or empty) and optionally {@code layout} ({@link CraftLayouts}). With a layout the task puts the items the planner chose
 * in the slots and never re-decides; without one each slot takes the first alternative, in the recipe's own order, that
 * the body carries. The grid positions come from the game's recipe, found again by id: a shaped recipe's shape is
 * not in the planner's book.
 *
 * <p><b>What it draws from.</b> The main inventory and the hotbar ({@link MenuHand#CARRIED_SLOTS}); an item worn or held
 * in the off hand is in use, and is neither an ingredient nor the table to put down. The result goes to the same slots,
 * and a result that does not wholly fit is not taken at all: the ingredients are given back from the grid, so a craft
 * never turns part of its result into something on the floor.
 *
 * <p><b>Stations.</b> No station: the inventory's own 2x2 grid. A crafting table: one within reach is used where it
 * stands. Otherwise the table item is put down the way a player does it, then used and broken again. A spot is usable
 * when a player could place a block there ({@link TableSites}: replaceable, and a neighbour whose face the eyes can see
 * and reach; leaves will do) and block safety lets the body break the table again. When none is within reach the body
 * walks, through the navigator, to the nearest place with one beside it, within {@link TableSites#DEFAULT_RADIUS} blocks,
 * and puts the table down there; the click is made sneaking. When every candidate it found was one block safety refuses,
 * the body is inside a protected area and the way out is further than that: the search goes on out to
 * {@link TableSites#WIDE_RADIUS}, which is far enough to leave a protected square from its middle. The table is broken as a
 * player breaks it (start, wait out the break time, finish), and the task waits for the item to come back. A table the
 * body did not place is left standing. What the task placed is in the body's {@link BotMemory}, which is saved with the
 * brain, so a task started after a restart finds the table it left and takes it up. A remembered table found gone is
 * forgotten.
 *
 * <p><b>Interruptions.</b> A reflex can hold the body and carry it several blocks off in the middle of a craft or of the
 * table's take-up. On resume the task looks again from the top: a table of its own that stands out of reach is walked back
 * to ({@link #RETURN_RADIUS}) before the craft or the break goes on, and a table already broken is not forgotten: its
 * item is looked for within {@link #DROP_SEARCH_RADIUS} blocks of where the table stood, and walked to wherever in that
 * radius it lies.
 *
 * <p><b>Exits.</b> The cursor and the grid are empty on every exit: after each craft, on a failure, on completion, on
 * pause and on abort (every exit goes through {@link #release}). A paused or aborted task closes the table's menu and stops
 * breaking; the table it placed stays, remembered. A break the body may not make (a claim that arrived after the table was
 * put down) ends the craft as a success all the same, because the craft's result is what the goal asked for: the table
 * stays, remembered as the body's, the log says why, and the tidy step or the next craft takes it up.
 *
 * <p><b>Failures.</b> {@code MissingInput} when an ingredient or the table is not carried, {@code TargetGone} for a recipe
 * id the game does not have, {@code NoPlace} when the body found nowhere to put the table down: nowhere within the radius,
 * nowhere out of a protected area within the wide radius, or no place it could get to in the walks it allows. Nothing a
 * replan can change would alter that (the planner does not see where a table fits), so the executor gives the goal up at
 * once, and every other kind that does not give up is answered by the same plan again, three times, before the goal ends
 * all the same. {@code Protected} is never the answer for "nowhere to put my own table", but it is the answer for a table
 * that stands and will not open: the executor excludes that block, so the next plan's craft puts its own table elsewhere.
 * {@code Unreachable} when the game would not let the body put the table down or it could not walk back to the table it had
 * put down, {@code NoProgress} when the body would not sneak or the table could not be taken into the hand, {@code NoRoom}
 * when the result would not fit in the inventory. The craft is done once its result is in the inventory, so what happens
 * to the table afterwards cannot fail it: a break that runs out of time, a take-up the inventory has no room for and an
 * item that never comes back all end the task as completed, with the table (or its item) left and remembered where the
 * code can remember it, and a log line. The executor adds its own budget.
 */
public final class CraftTask extends BodyTask {
    public static final String KIND = RecipeDef.CRAFT;

    /** Ticks between two crafts: a few, like a player clicking through them. */
    static final int CRAFT_PERIOD_TICKS = 3;
    /** How far from the body a standing table is looked for; reach decides whether it is used. */
    static final int TABLE_SEARCH_RADIUS = 5;
    /**
     * Ticks the broken table's item may take to reach the inventory: it has a pickup delay of its own, and after an
     * interruption the body may have a dozen blocks to walk.
     */
    static final int PICKUP_WAIT_TICKS = 200;
    /**
     * Ticks after the break before the body steps toward a drop that did not reach it: the item has a pickup delay of 10
     * ticks and hops about on its own for a few more, and most of the time that ends inside the body's pickup box.
     */
    static final int PICKUP_PATIENCE_TICKS = 20;
    /**
     * How far from where the table stood its dropped item is looked for, wherever the body is. Past {@link #NEAR_DROP} the
     * body goes to it through the navigator; the last steps are plain input, the way a player walks over.
     */
    static final double DROP_SEARCH_RADIUS = 12.0;
    /** Within this many blocks of the body a drop is walked to by input; further off, by the navigator. */
    static final double NEAR_DROP = 2.5;
    /**
     * Ticks of walking at a near drop by input before the navigator is asked for the drop's own block instead: a second
     * is several times what the last steps take, so a drop still not taken lies where walking at it does not bring the
     * body (seen on a live server: in a dip one block deep, straight under the edge the body stood on, a quarter block
     * below its pickup box).
     */
    static final int INPUT_STEPS_ALLOWED = 20;
    /**
     * How far from the body a table of this task's that stands out of reach is still walked back to: a reflex's retreat
     * is a dozen blocks, not a hundred. A table further off is no table of this craft's.
     */
    static final int RETURN_RADIUS = 24;
    /** How many times in a row the body walks back to a table and finds it still out of reach before the task gives up. */
    static final int RETURNS_ALLOWED = 3;
    /** The navigator's goal for a walk back to the table: within this many blocks of it. */
    static final int RETURN_STAND_RADIUS = 2;
    /** Ticks a break may take before the task gives it up; a table by hand takes about 75. */
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

    private static final ItemKey CRAFTING_TABLE = ItemKey.of("minecraft:crafting_table");
    /** What a {@link Failure.NoPlace} from this task says it could not find a place for. */
    static final String PUT_TABLE_DOWN = "put a crafting table down";

    private enum Phase { PREPARE, WALK, PLACE, OPEN, CRAFT, BREAK, PICKUP }

    /** One ingredient position: where it goes in the grid and what the recipe accepts there. */
    private record Cell(int gridIndex, Ingredient ingredient) {}

    /** What goes into one cell for one craft. */
    private record Placement(Cell cell, Item item) {}

    private final NodeSpec leaf;
    private final Map<String, String> hints;
    /** True for the task made by {@link #takeUp}: it crafts nothing and only takes a table this body left standing. */
    private final boolean takeUpOnly;

    // Read from the leaf and the game in begin.
    private CraftingRecipe recipe;
    private boolean needsTable;
    private int gridWidth;
    private List<Cell> cells;
    private Item resultItem;
    private int resultCount;
    private Item tableItem;
    private int remaining;
    /** The planner's items for the crafts still to do, one list per craft in slot order; empty without a layout. */
    private List<List<ItemKey>> layout = List.of();

    private Phase phase = Phase.PREPARE;
    private int cooldown;
    private int phaseTicks;
    private int refusals;
    private int sequence;
    @Nullable
    private BlockPos table;
    private boolean placedHere;
    private int tablesBefore;
    /** The body is walking toward the table's drop on this task's input. */
    private boolean walking;
    /** The navigator would not take the body to the table's drop: the rest of the way is by input. */
    private boolean dropWalkFailed;
    /** Ticks this pick-up has walked at a near drop by input without taking it. */
    private int inputSteps;

    // Finding a place for the table, and walking to it or back to the table.
    /** Places the body stood at or walked to without finding a usable spot, so that they are not offered again. */
    private final Set<BlockPos> triedStands = new HashSet<>();
    private int walks;
    private int walkRestarts;
    @Nullable
    private BlockPos walkingTo;
    private int walkRadius;
    /** The walk in progress is back to the table this task has, not to a place for a new one. */
    private boolean returning;
    /** Walks back to the table in a row that ended with it still out of reach. */
    private int returns;
    /** Why the last walk to a place for the table failed; null once a walk has succeeded. */
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

    public CraftTask(NodeSpec leaf, Map<String, String> hints) {
        this.leaf = leaf;
        this.hints = hints;
        this.takeUpOnly = false;
    }

    private CraftTask(BlockPos standing) {
        this.leaf = null;
        this.hints = Map.of();
        this.takeUpOnly = true;
        this.table = standing;
    }

    /**
     * A task that crafts nothing and takes up the crafting table at {@code standing}, which this body put down and was
     * stopped before it could take back (a restart, a cancel, a failure): the planner counts the table in the inventory,
     * not in the world, so a table left standing would cost a goal its planks. It is the same break and pickup the craft
     * ends with, so it ends the same way: the item back in the inventory, or a failure saying why not.
     */
    public static Task<BotPlayer> takeUp(BlockPos standing) {
        return new CraftTask(standing.immutable());
    }

    /**
     * The nearest crafting table within reach that this body's memory says it placed, or null. What {@link #takeUp} is
     * for: a runtime asks after a restart and when a goal ends.
     */
    @Nullable
    public static BlockPos leftoverTable(BotPlayer body) {
        ServerLevel level = body.serverLevel();
        BotMemory memory = BotMemory.of(body.getUUID());
        String dimension = dimension(level);
        BlockPos centre = body.blockPosition();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(centre.offset(-TABLE_SEARCH_RADIUS, -TABLE_SEARCH_RADIUS, -TABLE_SEARCH_RADIUS),
                centre.offset(TABLE_SEARCH_RADIUS, TABLE_SEARCH_RADIUS, TABLE_SEARCH_RADIUS))) {
            if (!level.isLoaded(pos) || !level.getBlockState(pos).is(Blocks.CRAFTING_TABLE)) continue;
            if (!memory.isPlaced(dimension, pos) || !body.canInteractWithBlock(pos, 0.0)) continue;
            double distance = pos.distToCenterSqr(body.getEyePosition());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = pos.immutable();
            }
        }
        return best;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------------------------------------

    @Override
    protected void begin(BotPlayer body) {
        if (takeUpOnly) {
            needsTable = true;
            tableItem = Items.CRAFTING_TABLE;
            remaining = 0;
            return;
        }
        Map<String, String> params = leaf.params();
        String recipeId = params.getOrDefault("recipe", "");
        ServerLevel level = body.serverLevel();
        MinecraftServer server = body.getServer();
        ResourceLocation id = ResourceLocation.tryParse(recipeId);
        Optional<RecipeHolder<?>> holder = id == null || server == null ? Optional.empty() : server.getRecipeManager().byKey(id);
        if (holder.isEmpty() || !(holder.get().value() instanceof CraftingRecipe found)) {
            fail(new Failure.TargetGone(recipeId, "the game has no crafting recipe '" + recipeId + "'"));
            return;
        }
        recipe = found;
        ItemStack result = recipe.getResultItem(level.registryAccess());
        resultItem = result.getItem();
        resultCount = Math.max(1, result.getCount());

        int times;
        try {
            times = Integer.parseInt(params.getOrDefault("times", "1"));
        } catch (NumberFormatException e) {
            fail(new Failure.Internal(e, "the craft leaf's times is not a number: " + params.get("times")));
            return;
        }

        String station = params.getOrDefault("station", "");
        needsTable = !station.isEmpty();
        if (needsTable) {
            if (!ItemKey.of(station).equals(CRAFTING_TABLE)) {
                fail(new Failure.Internal(null, "the craft task can only use a crafting table as a station, not " + station));
                return;
            }
            tableItem = Items.CRAFTING_TABLE;
        }
        gridWidth = needsTable ? 3 : 2;
        try {
            cells = cellsOf(recipe, gridWidth);
        } catch (IllegalArgumentException e) {
            fail(new Failure.Internal(null, e.getMessage()));
            return;
        }

        // The leaf's goal is the absolute count after this step, so a task started again after a restart (some crafts
        // already done) makes only what is still missing. The count is of the slots a craft puts its result in.
        int needed = times;
        if (leaf.goal() instanceof Goal.HaveItem have) {
            int missing = have.count() - MenuHand.carriedCount(body, resultItem);
            needed = Math.floorDiv(missing + resultCount - 1, resultCount);
        }
        remaining = Math.max(0, Math.min(times, needed));

        List<List<ItemKey>> perCraft;
        try {
            perCraft = CraftLayouts.parse(params.get("layout"));
        } catch (IllegalArgumentException e) {
            fail(new Failure.Internal(e, "the craft leaf's layout is malformed: " + e.getMessage()));
            return;
        }
        if (!perCraft.isEmpty()) {
            if (perCraft.size() != times) {
                fail(new Failure.Internal(null, "the layout covers " + perCraft.size() + " crafts but the leaf asks for " + times));
                return;
            }
            // The layout lists every craft of the leaf; the crafts already done are the first ones.
            layout = perCraft.subList(times - remaining, times);
        }

        String hint = hints.get("table");
        if (hint != null) {
            try {
                table = pos(Pos.parse(hint));
            } catch (RuntimeException e) {
                // A hint is only where to look: a bad one costs a search.
            }
        }
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
            case CRAFT -> craft(body);
            case BREAK -> breakTable(body);
            case PICKUP -> pickUp(body);
        };
    }

    @Override
    protected void resumeWork(BotPlayer body) {
        // The world may have changed while paused, and a reflex may have carried the body off: everything is looked at
        // again from the top, and a table that stands out of reach is walked back to first.
        phase = Phase.PREPARE;
        cooldown = 0;
        phaseTicks = 0;
        dropWalkFailed = false;
    }

    /** True while the task waits on the game and not on the body: a table being broken, an item coming back. */
    @Override
    public boolean waiting() {
        return phase == Phase.BREAK || phase == Phase.PICKUP || cooldown > 0;
    }

    @Override
    public Map<String, String> checkpoint() {
        return table == null ? Map.of() : Map.of("table", new Pos(table.getX(), table.getY(), table.getZ()).encode());
    }

    // ---------------------------------------------------------------------------------------------------------
    // Phases
    // ---------------------------------------------------------------------------------------------------------

    private TickResult prepare(BotPlayer body) {
        if (remaining > 0) {
            if (!needsTable) {
                phase = Phase.CRAFT;
                return craft(body);
            }
            BlockPos standing = findTable(body);
            if (standing != null) {
                table = standing;
                returns = 0;
                phase = Phase.OPEN;
                return TickResult.RUNNING;
            }
            TickResult back = walkBackToTable(body);
            return back != null ? back : seekSite(body);
        }
        // Every craft is done. A table this body put down comes up again; one it found is left standing.
        if (needsTable && table != null && isOurs(body)) return takeTableUp(body);
        return complete(body);
    }

    /**
     * The craft is made and its table is the body's to take up. Gone already: broken by this task just before a pause came
     * between the break and the pickup, or by someone else; its item is picked up when one lies within reach of a walk,
     * and otherwise there is nothing to take. Standing: it is walked back to when the body was carried off, then broken.
     */
    private TickResult takeTableUp(BotPlayer body) {
        ServerLevel level = body.serverLevel();
        if (!level.isLoaded(table)) {
            return stop(body, new Failure.Unreachable("the crafting table at " + table.toShortString(), "not loaded",
                "the crafting table to take up is in a chunk that is not loaded"));
        }
        if (!level.getBlockState(table).is(Blocks.CRAFTING_TABLE)) {
            BotMemory.of(body.getUUID()).forgetPlaced(dimension(level), table);
            if (nearestDrop(body) != null) {
                tablesBefore = MenuHand.carriedCount(body, tableItem);
                phase = Phase.PICKUP;
                phaseTicks = 0;
                return TickResult.RUNNING;
            }
            // Gone before it could be taken up, and no item of it to be found: nothing to take, and nothing to remember.
            forgetTable(body);
            return complete(body);
        }
        if (!body.canInteractWithBlock(table, 0.0)) {
            TickResult back = walkBackToTable(body);
            if (back != null) return back;
            return stop(body, new Failure.Unreachable("the crafting table at " + table.toShortString(), "out of reach",
                "the crafting table to take up is more than " + RETURN_RADIUS + " blocks from the body"));
        }
        returns = 0;
        phase = Phase.BREAK;
        phaseTicks = 0;
        refusals = 0;
        return TickResult.RUNNING;
    }

    /**
     * A table of this task's stands out of the body's reach (a reflex carried the body off, or the table is the checkpoint's
     * and the body has moved since): walks back to it. Null when there is nothing to walk back to: no table, a table
     * that is gone or in a chunk that is not loaded, or one further than {@link #RETURN_RADIUS} blocks off.
     */
    @Nullable
    private TickResult walkBackToTable(BotPlayer body) {
        ServerLevel level = body.serverLevel();
        if (table == null || !level.isLoaded(table) || !level.getBlockState(table).is(Blocks.CRAFTING_TABLE)) return null;
        if (table.distSqr(body.blockPosition()) > (double) RETURN_RADIUS * RETURN_RADIUS) return null;
        if (++returns > RETURNS_ALLOWED) {
            return stop(body, new Failure.Unreachable("the crafting table at " + table.toShortString(), "out of reach",
                "walked back toward the crafting table " + RETURNS_ALLOWED + " times and it is still out of reach"));
        }
        returning = true;
        walkRestarts = 0;
        walkingTo = table;
        walkRadius = RETURN_STAND_RADIUS;
        nav(body).goTo(table, walkRadius);
        phase = Phase.WALK;
        phaseTicks = 0;
        return TickResult.RUNNING;
    }

    /**
     * No table stands within reach: either a spot beside the body will do, or the body walks to a place that has one, or
     * there is none to be had and the task fails saying how far it looked. A refusal by block safety of every candidate
     * widens the search once, to get out of a protected area; it is never the failure itself.
     */
    private TickResult seekSite(BotPlayer body) {
        ServerLevel level = body.serverLevel();
        if (MenuHand.carriedCount(body, tableItem) == 0) {
            return stop(body, new Failure.MissingInput(CRAFTING_TABLE, 1, "no crafting table stands within reach and none is carried"));
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
                // Everything within the radius that a table could go on is a spot block safety refuses: the body is in a
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
     * Why there is nowhere to put the table, in the type that says what the executor should do about it: always a
     * {@link Failure.NoPlace}, because none of the reasons is something a plan can work around. {@code looked} is the
     * widest radius the search reached.
     */
    private Failure noSite(int looked, TableSites.Refusal refusal) {
        String why;
        if (walkFailure != null) {
            why = "walked to " + walks + " place(s) with a spot for a crafting table beside them and could not get to the last: " + walkFailure;
        } else if (walks >= WALKS_ALLOWED) {
            why = "walked to " + walks + " places and found no usable spot for a crafting table at any of them";
        } else if (refusal.any()) {
            why = "no spot within " + looked + " blocks that a crafting table could go on is one block safety lets the body break again ("
                + refusal.verdict.rule() + ": " + refusal.verdict.detail() + ", first refused at " + refusal.at.toShortString() + ")";
        } else {
            why = "nowhere to stand with a free spot beside it that a block can be placed on and the table taken up from";
        }
        return new Failure.NoPlace(PUT_TABLE_DOWN, looked, why);
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
                if (returning) return stop(body, unreachableTable(why == null ? "no path" : why));
                walkFailure = why;
                phase = Phase.PREPARE; // the next nearest place, or the failure
            }
            case IDLE -> {
                // Something else stopped the navigator: this task still owns the body.
                if (++walkRestarts > WALK_RESTARTS_ALLOWED) {
                    String why = "the navigator stopped " + walkRestarts + " times on the way to " + walkingTo.toShortString();
                    if (returning) return stop(body, unreachableTable(why));
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

    private Failure unreachableTable(String why) {
        return new Failure.Unreachable("the crafting table at " + table.toShortString(), why,
            "could not walk back to the crafting table: " + why);
    }

    /**
     * Puts the table item down on a spot beside the body, through the player's own item use, sneaking. The sites are
     * looked at again from the sneaking eye, because the world is the judge: the one clicked first that takes is the table.
     */
    private TickResult place(BotPlayer body) {
        ServerLevel level = body.serverLevel();
        if (!body.isShiftKeyDown()) {
            // A condition of the world, not a bug: the retry and the replan the kind earns are the right answers.
            if (++phaseTicks > SNEAK_WAIT_TICKS) return stop(body, new Failure.NoProgress(phaseTicks, "the body would not sneak to place the crafting table"));
            return TickResult.RUNNING;
        }
        if (!hold(body, tableItem)) {
            return stop(body, new Failure.NoProgress(0, "the crafting table could not be taken into the hand"));
        }
        BlockPos feet = body.blockPosition();
        // The eye of the look in seekSite, not whatever the pose has reached: the click is made sneaking.
        List<TableSites.Site> sites = TableSites.sitesFrom(body, level, feet, TableSites.sneakingEye(body, feet, true), true, SITES_TRIED,
            new TableSites.Refusal());
        for (TableSites.Site site : sites) {
            body.input().lookAt(site.click());
            body.getInteractionManager().interactBlock(body, level, body.getMainHandItem(), InteractionHand.MAIN_HAND,
                new BlockHitResult(site.click(), site.face(), site.against(), false));
            if (!level.getBlockState(site.spot()).is(Blocks.CRAFTING_TABLE)) continue;
            table = site.spot().immutable();
            placedHere = true;
            tablesBefore = MenuHand.carriedCount(body, tableItem);
            BotMemory.of(body.getUUID()).notePlaced(dimension(level), table);
            BlockSafety.of(level.getServer()).noteBotPlaced(body, level, table);
            letGoOfSneak(body);
            // The sneak is released a tick before the table is used: a click that sneaks would place another, not open.
            cooldown = 1;
            phase = Phase.OPEN;
            return TickResult.RUNNING;
        }
        // Nothing took (a look from the sneaking eye that found no site, or the game refused each): from the top again.
        letGoOfSneak(body);
        if (++placeRounds > PLACE_ROUNDS_ALLOWED) {
            return stop(body, new Failure.Unreachable("a place for the crafting table", "the game would not let the body put it down",
                "the crafting table was not placed from " + feet.toShortString() + " in " + placeRounds + " tries"));
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
        if (table == null || !level.getBlockState(table).is(Blocks.CRAFTING_TABLE)) {
            forgetTable(body); // gone since it was chosen: the memory forgets it, and a table is chosen again
            phase = Phase.PREPARE;
            return TickResult.RUNNING;
        }
        if (!body.canInteractWithBlock(table, 0.0)) {
            phase = Phase.PREPARE; // carried off since the table was chosen: the way back is looked for from the top
            return TickResult.RUNNING;
        }
        if (body.isShiftKeyDown()) {
            // A use-click while sneaking with the table in the hand would put a second table down, not open this one.
            body.input().sneak = false;
            cooldown = 1;
            return TickResult.RUNNING;
        }
        if (!(body.containerMenu instanceof CraftingMenu)) {
            Vec3 top = new Vec3(table.getX() + 0.5, table.getY() + 1.0, table.getZ() + 0.5);
            body.getInteractionManager().interactBlock(body, level, body.getMainHandItem(), InteractionHand.MAIN_HAND,
                    new BlockHitResult(top, Direction.UP, table, false));
        }
        if (!(body.containerMenu instanceof CraftingMenu)) {
            if (++refusals > REFUSALS_ALLOWED) {
                // Protected, not TargetGone: the executor excludes this block, and the next plan's craft puts its own
                // table elsewhere instead of being replanned into the same unopenable one.
                return stop(body, new Failure.Protected(corePos(table), "the game would not open it",
                        "the game would not open the crafting table at " + table.toShortString()));
            }
            cooldown = 1;
            return TickResult.RUNNING;
        }
        phase = Phase.CRAFT;
        cooldown = 1;
        return TickResult.RUNNING;
    }

    private TickResult craft(BotPlayer body) {
        AbstractContainerMenu menu;
        if (needsTable) {
            if (!(body.containerMenu instanceof CraftingMenu)) {
                // Closed under the task (the table went, or the body was moved away): open it again.
                phase = Phase.OPEN;
                return TickResult.RUNNING;
            }
            menu = body.containerMenu;
        } else {
            if (body.containerMenu != body.inventoryMenu) body.closeContainer();
            menu = body.inventoryMenu;
        }
        Failure failure = craftOnce(body, menu);
        if (failure != null) return stop(body, failure);
        remaining--;
        if (remaining == 0) {
            if (!needsTable) return complete(body);
            body.closeContainer();
            phase = Phase.PREPARE;
        }
        cooldown = CRAFT_PERIOD_TICKS - 1;
        return TickResult.RUNNING;
    }

    private TickResult breakTable(BotPlayer body) {
        ServerLevel level = body.serverLevel();
        BlockState state = level.getBlockState(table);
        if (!state.is(Blocks.CRAFTING_TABLE)) {
            // Broken, by this task or by someone else: either way what is left is picking the item up.
            BotMemory.of(body.getUUID()).forgetPlaced(dimension(level), table);
            phase = Phase.PICKUP;
            phaseTicks = 0;
            return TickResult.RUNNING;
        }
        if (++phaseTicks > BREAK_WAIT_TICKS) {
            // The craft is made, and that is what the goal asked for, so the table's fate cannot fail it. The break never
            // finished, so the table stands on; it stays remembered as the body's, and the tidy step or the next craft
            // takes it up (complete below releases the break in progress).
            MegaBlockBot.LOGGER.info("{} stops breaking the crafting table at {} after {} ticks and leaves it standing: the craft is made",
                body.getGameProfile().getName(), table.toShortString(), phaseTicks);
            table = null;
            return complete(body);
        }
        IInteractionManager hand = body.getInteractionManager();
        if (!body.canInteractWithBlock(table, 0.0)) {
            // Pushed or carried away since the break began: it is let go of, and the body goes back to the table.
            if (hand.isMining()) {
                hand.processBlockBreakingAction(table, Action.ABORT_DESTROY_BLOCK, Direction.UP, level.getMaxBuildHeight(), sequence++);
            }
            phase = Phase.PREPARE;
            return TickResult.RUNNING;
        }
        if (!hand.isMining()) {
            if (MenuHand.roomFor(body, new ItemStack(tableItem)) == 0) {
                // The craft is made, and that is what the goal asked for. The inventory cannot take the table's item, so
                // breaking it would leave it on the floor of a body that cannot pick it up: the table stays standing and
                // remembered as the body's, and the tidy step or the next craft takes it up once there is room.
                MegaBlockBot.LOGGER.info("{} leaves the crafting table at {} standing: the inventory has no room for its item",
                    body.getGameProfile().getName(), table.toShortString());
                table = null;
                return complete(body);
            }
            Verdict verdict = BlockSafety.of(level.getServer()).mayBreak(body, level, table, state);
            if (!verdict.allowed()) {
                // The craft is made, and that is what the goal asked for, so this is a success. The table stays where it is,
                // and stays in the body's memory as its own: the tidy step and the next craft that finds it take it up.
                MegaBlockBot.LOGGER.info("{} leaves the crafting table at {} standing: it may not break it ({}: {})",
                    body.getGameProfile().getName(), table.toShortString(), verdict.rule(), verdict.detail());
                table = null;
                return complete(body);
            }
            if (phaseTicks == 1) tablesBefore = MenuHand.carriedCount(body, tableItem);
            if (refusals > REFUSALS_ALLOWED) {
                return stop(body, new Failure.Unreachable("the crafting table at " + table.toShortString(),
                        "the game would not start breaking it", ""));
            }
            selectBreakingTool(body, state);
            hand.processBlockBreakingAction(table, Action.START_DESTROY_BLOCK, Direction.UP, level.getMaxBuildHeight(), sequence++);
            if (!hand.isMining() && level.getBlockState(table).is(Blocks.CRAFTING_TABLE)) refusals++;
            return TickResult.RUNNING;
        }
        hand.continueBreaking(table, Direction.UP);
        // A client sends the finish once its own progress reaches 1; the engine reports progress in tenths.
        if (hand.isMining() && hand.getBlockBreakingProgress() >= 10) {
            hand.processBlockBreakingAction(hand.getMiningPos(), Action.STOP_DESTROY_BLOCK, Direction.UP, level.getMaxBuildHeight(), sequence++);
        }
        return TickResult.RUNNING;
    }

    private TickResult pickUp(BotPlayer body) {
        if (MenuHand.carriedCount(body, tableItem) > tablesBefore) {
            table = null;
            return complete(body);
        }
        if (++phaseTicks > PICKUP_WAIT_TICKS) {
            // The craft is made, and that is what the goal asked for, so the table's fate cannot fail it. Its item lies
            // somewhere the body did not reach in time, and a drop is nothing the code can remember it by; the log says
            // what was given up on.
            MegaBlockBot.LOGGER.info("{} stops waiting for the broken crafting table's item after {} ticks: the craft is made",
                body.getGameProfile().getName(), phaseTicks);
            table = null;
            return complete(body);
        }
        if (phaseTicks == 1) inputSteps = 0; // every pick-up counts its own steps
        if (phaseTicks > PICKUP_PATIENCE_TICKS) goToDrop(body);
        return TickResult.RUNNING;
    }

    /** The nearest dropped crafting table within {@link #DROP_SEARCH_RADIUS} blocks of where the table stood, or null. */
    @Nullable
    private ItemEntity nearestDrop(BotPlayer body) {
        ItemEntity nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (ItemEntity drop : body.serverLevel().getEntitiesOfClass(ItemEntity.class, new AABB(table).inflate(DROP_SEARCH_RADIUS),
                candidate -> !candidate.isRemoved() && candidate.getItem().is(tableItem))) {
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
     * wide on each side, so now and then the table's item comes to rest just out of reach; and a reflex may have carried
     * the body a dozen blocks off before the item was taken. The body goes to it by the means a player has: through the
     * navigator while it is further than {@link #NEAR_DROP}, by plain input for the last steps (or all of them, when the
     * navigator will not go). A near drop that {@link #INPUT_STEPS_ALLOWED} ticks of walking at it did not reach lies
     * where input does not bring the body, below an edge or behind one: the navigator is then asked for the drop's own
     * block, which makes the body step down to it. Nothing is moved or teleported. The input is reset the tick the item
     * is in the inventory, and by {@link #letGo} on every other exit.
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
     * A task that fails itself is never aborted afterwards, so it lets go of what it holds first: the table's menu, a
     * break in progress, the cursor and the grid. A table it already put down stays, remembered, and the next craft
     * that needs one finds it.
     */
    private TickResult stop(BotPlayer body, Failure failure) {
        release(body);
        return TickResult.failed(failure);
    }

    /** The task ends well: it lets go of the body like every other exit, because the executor does not abort it afterwards. */
    private TickResult complete(BotPlayer body) {
        release(body);
        return TickResult.COMPLETED;
    }

    /** Lets go of everything: the break, the sneak, the open menu, the cursor and the grid. Never throws on a body in any state. */
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
        IInteractionManager hand = body.getInteractionManager();
        if (hand.isMining() && table != null && table.equals(hand.getMiningPos())) {
            hand.processBlockBreakingAction(table, Action.ABORT_DESTROY_BLOCK, Direction.UP,
                    body.level().getMaxBuildHeight(), sequence++);
        }
        // Closing a menu hands back its grid and cursor (AbstractContainerMenu.removed).
        if (body.containerMenu != body.inventoryMenu) body.closeContainer();
        MenuHand own = new MenuHand(body, body.inventoryMenu);
        own.putBack();
        own.emptyGrid(1, 4);
    }

    // ---------------------------------------------------------------------------------------------------------
    // One craft, through the menu
    // ---------------------------------------------------------------------------------------------------------

    /** Null when the craft was made; otherwise why not. The cursor and the grid are empty either way. */
    @Nullable
    private Failure craftOnce(BotPlayer body, AbstractContainerMenu menu) {
        List<Placement> placements = new ArrayList<>();
        Failure unchosen = choose(body, placements);
        if (unchosen != null) return unchosen;

        MenuHand hand = new MenuHand(body, menu);
        try {
            for (Placement placement : placements) {
                if (!hand.pickUp(placement.item())) {
                    return new Failure.MissingInput(ItemKeys.of(placement.item()), 1, "it is not in the inventory");
                }
                hand.placeOne(1 + placement.cell().gridIndex());
            }
            hand.putBack();
            ItemStack offered = menu.getSlot(0).getItem();
            if (offered.isEmpty() || !offered.is(resultItem)) {
                return new Failure.Internal(null, "the " + gridWidth + "x" + gridWidth + " grid made "
                        + (offered.isEmpty() ? "nothing" : ItemKeys.of(offered.getItem()).id()) + " from the layout of "
                        + leaf.params().get("recipe") + ", not " + ItemKeys.of(resultItem).id());
            }
            // Taking the result takes the ingredients with it, and a result that does not wholly fit is dropped at the
            // feet by the game (a shift click moves what fits and drops the rest). So the result is taken only when all of
            // it has room; otherwise it stays in its slot, and the finally below gives the ingredients back.
            int made = offered.getCount();
            int room = MenuHand.roomFor(body, offered);
            if (room < made) {
                return new Failure.NoRoom(ItemKeys.of(resultItem), "there is no room in the inventory for the " + made + " "
                        + ItemKeys.of(resultItem).id() + " this craft makes (room for " + room + ")");
            }
            int before = MenuHand.carriedCount(body, resultItem);
            hand.moveOut(0);
            int arrived = MenuHand.carriedCount(body, resultItem) - before;
            if (arrived < made) {
                return new Failure.NoProgress(0, "only " + arrived + " of the " + made + " " + ItemKeys.of(resultItem).id()
                        + " this craft made reached the inventory; the rest was dropped at the feet");
            }
            return null;
        } finally {
            hand.putBack();
            hand.emptyGrid(1, gridWidth * gridWidth);
        }
    }

    /** Picks the item for every cell of this craft from what the body carries, or says what is missing. */
    @Nullable
    private Failure choose(BotPlayer body, List<Placement> into) {
        // What is left to draw from as the cells are filled: a recipe that wants three planks needs three.
        Map<Item, Integer> held = new HashMap<>();
        for (int slot = 0; slot < MenuHand.CARRIED_SLOTS; slot++) {
            ItemStack stack = body.getInventory().getItem(slot);
            if (!stack.isEmpty()) held.merge(stack.getItem(), stack.getCount(), Integer::sum);
        }
        List<ItemKey> planned = layout.isEmpty() ? null : layout.get(layout.size() - remaining);
        if (planned != null && planned.size() != cells.size()) {
            return new Failure.Internal(null, "the layout names " + planned.size() + " items for " + cells.size()
                    + " slots of " + leaf.params().get("recipe"));
        }
        for (int i = 0; i < cells.size(); i++) {
            Cell cell = cells.get(i);
            List<Item> alternatives = alternatives(cell.ingredient());
            Item chosen = null;
            if (planned != null) {
                chosen = itemOf(planned.get(i));
                if (chosen == null || !cell.ingredient().test(new ItemStack(chosen))) {
                    return new Failure.Internal(null, "the layout puts " + planned.get(i).id() + " in slot " + (i + 1)
                            + " of " + leaf.params().get("recipe") + ", which does not accept it");
                }
            } else {
                for (Item alternative : alternatives) {
                    if (held.getOrDefault(alternative, 0) > 0) {
                        chosen = alternative;
                        break;
                    }
                }
            }
            if (chosen == null || held.getOrDefault(chosen, 0) <= 0) {
                ItemKey wanted = chosen != null ? ItemKeys.of(chosen)
                        : alternatives.isEmpty() ? ItemKey.of("minecraft:air") : ItemKeys.of(alternatives.get(0));
                return new Failure.MissingInput(wanted, 1, "slot " + (i + 1) + " of " + leaf.params().get("recipe")
                        + " has nothing to take");
            }
            held.merge(chosen, -1, Integer::sum);
            into.add(new Placement(cell, chosen));
        }
        return null;
    }

    /**
     * The grid cells of a recipe in the order of its non-blank ingredients, which is the order of the planner's slots.
     * A shaped recipe keeps its shape, placed at the grid's top left; any other crafting recipe fills cells in order.
     */
    private static List<Cell> cellsOf(CraftingRecipe recipe, int gridWidth) {
        List<Ingredient> ingredients = recipe.getIngredients();
        boolean shaped = recipe instanceof ShapedRecipe;
        int width = shaped ? ((ShapedRecipe) recipe).getWidth() : gridWidth;
        int height = shaped ? ((ShapedRecipe) recipe).getHeight() : gridWidth;
        if (width > gridWidth || height > gridWidth) {
            throw new IllegalArgumentException("the recipe is " + width + "x" + height + " and does not fit the "
                    + gridWidth + "x" + gridWidth + " grid; it needs a crafting table");
        }
        List<Cell> cells = new ArrayList<>();
        for (int i = 0; i < ingredients.size(); i++) {
            Ingredient ingredient = ingredients.get(i);
            if (ingredient.isEmpty()) continue; // a blank cell of a shaped recipe
            int index = shaped ? (i / width) * gridWidth + (i % width) : cells.size();
            cells.add(new Cell(index, ingredient));
        }
        if (cells.size() > gridWidth * gridWidth) {
            throw new IllegalArgumentException("the recipe has " + cells.size() + " ingredients and the " + gridWidth + "x"
                    + gridWidth + " grid has " + gridWidth * gridWidth + " cells");
        }
        return cells;
    }

    /** The registered item for a key, or null when the game has none. */
    @Nullable
    private static Item itemOf(ItemKey key) {
        ResourceLocation id = ResourceLocation.tryParse(key.id());
        return id == null ? null : BuiltInRegistries.ITEM.getOptional(id).orElse(null);
    }

    /** The ingredient's distinct items in its own order, which is the order the planner's slot lists them in. */
    private static List<Item> alternatives(Ingredient ingredient) {
        Set<Item> items = new LinkedHashSet<>();
        for (ItemStack stack : ingredient.getItems()) {
            if (!stack.isEmpty() && stack.getItem() != Items.AIR) items.add(stack.getItem());
        }
        return List.copyOf(items);
    }

    // ---------------------------------------------------------------------------------------------------------
    // The table
    // ---------------------------------------------------------------------------------------------------------

    /**
     * The nearest crafting table the body can use from where it stands, or null. A remembered one is tried first; a
     * remembered one that is gone is forgotten.
     */
    @Nullable
    private BlockPos findTable(BotPlayer body) {
        ServerLevel level = body.serverLevel();
        if (table != null && level.isLoaded(table)) {
            if (!level.getBlockState(table).is(Blocks.CRAFTING_TABLE)) forgetTable(body);
            else if (body.canInteractWithBlock(table, 0.0)) return table;
        }
        BlockPos centre = body.blockPosition();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(centre.offset(-TABLE_SEARCH_RADIUS, -TABLE_SEARCH_RADIUS, -TABLE_SEARCH_RADIUS),
                centre.offset(TABLE_SEARCH_RADIUS, TABLE_SEARCH_RADIUS, TABLE_SEARCH_RADIUS))) {
            if (!level.isLoaded(pos) || !level.getBlockState(pos).is(Blocks.CRAFTING_TABLE)) continue;
            if (!body.canInteractWithBlock(pos, 0.0)) continue;
            double distance = pos.distToCenterSqr(body.getEyePosition());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = pos.immutable();
            }
        }
        return best;
    }

    /** The table this task had in mind is not there: the body's memory of having placed it goes too. */
    private void forgetTable(BotPlayer body) {
        if (table != null) BotMemory.of(body.getUUID()).forgetPlaced(dimension(body.serverLevel()), table);
        table = null;
        placedHere = false;
    }

    /**
     * Takes the item into the main hand as a player would: it is selected when it is on the hotbar, otherwise the
     * inventory menu swaps it into the selected hotbar slot. Only carried storage is looked at (see {@link MenuHand}).
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

    /**
     * Takes into the hand the hotbar slot that breaks the block fastest, and of equals one that cannot wear out, as a
     * player reaches for an axe or an empty hand: a table broken with the pickaxe just made would cost it durability for
     * nothing.
     */
    private static void selectBreakingTool(BotPlayer body, BlockState state) {
        var inventory = body.getInventory();
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

    /** The table is this body's to take up: this task put it down, or the body's memory says an earlier one did. */
    private boolean isOurs(BotPlayer body) {
        return table != null && (placedHere || BotMemory.of(body.getUUID()).isPlaced(dimension(body.serverLevel()), table));
    }

    private static String dimension(ServerLevel level) {
        return level.dimension().location().toString();
    }

    private static BlockPos pos(Pos pos) {
        return new BlockPos(pos.x(), pos.y(), pos.z());
    }
}
