package com.legoj15.megablockbot.game.task;

import com.legoj15.megablockbot.MegaBlockBot;
import com.legoj15.megablockbot.core.ItemKey;
import com.legoj15.megablockbot.core.Pos;
import com.legoj15.megablockbot.core.goal.Goal;
import com.legoj15.megablockbot.core.plan.NodeSpec;
import com.legoj15.megablockbot.core.planner.RecipeDef;
import com.legoj15.megablockbot.core.task.Failure;
import com.legoj15.megablockbot.core.task.TaskState;
import com.legoj15.megablockbot.core.task.TickResult;
import com.legoj15.megablockbot.game.body.BotPlayer;
import com.legoj15.megablockbot.game.nav.BodyInteractionManager;
import com.legoj15.megablockbot.game.nav.BotNavigator;
import com.legoj15.megablockbot.game.plan.BotMemory;
import com.legoj15.megablockbot.game.plan.ItemKeys;
import com.legoj15.megablockbot.game.plan.RecipeBooks;
import com.legoj15.megablockbot.game.safety.BlockSafety;
import com.legoj15.megablockbot.game.safety.Verdict;
import com.legoj15.megablockbot.nav.engine.api.entity.IInteractionManager;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;

/**
 * The {@code assemble} task (issue #18): runs one recipe of BuildCraft's assembly table — a redstone chipset, which no
 * crafting recipe makes — in the smallest rig that can power it. The table makes nothing without laser power, and a
 * laser makes none without an engine, so the task puts the whole rig up itself: table, laser, Stirling engine and a
 * lever to switch the engine on, fuel in the engine, the inputs in the table's buffer, the recipe queued by the menu
 * button that chooses it, the product picked up off the ground, and the rig taken down again on the way out.
 *
 * <p>Leaf params: {@code recipe} (a {@link RecipeDef} id in the bot's own book; the recipe's last slot is the fuel and
 * the others the inputs), {@code times} (runs), {@code station} ({@code buildcraftunofficial:assembly_table}),
 * {@code held} (the rig's other pieces as registry ids, comma separated and in the rig's order: the laser, the Stirling
 * engine, the lever) and {@code layout} (empty). The body carries the station, the held pieces, the inputs and the fuel
 * when the leaf starts; anything missing is {@code MissingInput} naming it, and nothing is put down for an assemble
 * that cannot run. As in the craft and smelt tasks, the leaf's goal is the absolute count after the step, so a task
 * started again makes only what is still missing.
 *
 * <p><b>The rig.</b> Three cells in a straight level line — table, laser, engine — with the lever in a fourth cell on
 * the floor beside the engine, on solid ground with air above, near the body, none of them a cell the rules refuse a
 * block of this body's in. The body stands two cells before the table end of the line, which is far enough that the
 * look at the floor under the laser's cell is more level than steep: a laser faces away from where its placer looks and
 * nothing rotates it later, so it is placed first, from that look, and its facing is read back — it must point back
 * along the line at the table's cell, or the laser comes up again and the task fails {@code Internal} saying what it
 * faced. Then the table, between the stance and the laser; then the engine beside the laser, which turns itself to
 * the laser on its first tick; then the lever beside the engine, and a use-click on it switches it on. Every block placed is noted in the body's
 * memory and with block safety, so it may be broken again.
 *
 * <p><b>BuildCraft is not compiled in.</b> Blocks and items are reached by registry id; the table's tile and menu are
 * not vanilla, so its recipe queue ({@code recipesStates} and each key's {@code output}) and the power it holds
 * ({@code power}, a long of micro-JJ declared in the tile's superclass) are read by member name, and so is the
 * micro-JJ its active recipe still needs ({@code getTarget()}). A member the work depends on that is not there is a
 * logged line and the task's own clean failure ({@code Internal} naming it); the two the work can do without — the
 * status line's {@code getTarget()} and the engine's {@code burnTime} in a stall's diagnosis — are read when they can
 * be and said to be unreadable when they cannot. Everything else goes through the game the way a player's click does:
 * the menu's own slots, and {@code clickMenuButton}, the call a client's click on the button row ends in.
 *
 * <p><b>The wait.</b> A redstone chipset costs 10,000 MJ at about one MJ a tick — over eight minutes — so the task is
 * {@code waiting()} while the table works, standing clear of the line, its status line saying the power the table holds
 * against what it needs. A table whose power has not risen for sixty seconds is diagnosed (the lever's state, the
 * laser's facing, the fuel left in the engine) and one whose power has not risen for a second such time fails
 * {@code NoProgress} saying what was read. The product is pushed into an inventory beside the table or dropped at it,
 * and picked up from wherever it lies ({@link DropPickup}).
 *
 * <p><b>Taking down.</b> On the way out — the completion's and every failure's after a block was placed — the lever,
 * the engine, the laser and the table are broken each with the tool that breaks it best and its item picked up, and
 * what was still in the table's buffer or the engine drops with them and is picked up too. A piece the rules refuse,
 * one whose break would drop nothing, one whose item the inventory has no room for and one that will not come up
 * within a named time is left standing, remembered as the body's, and named in the log. The task is done when the body
 * holds the goal's count.
 *
 * <p><b>Interruptions.</b> A pause leaves the rig running, for it needs no body: the menu is closed, the cursor
 * emptied, the input reset, and the engine goes on burning. A resume walks back and goes on from what the world shows —
 * the blocks that stand, the power the table holds, the count the body carries — placing only what is missing and
 * queueing only what is not queued. An abort closes any menu, lets go of the input and leaves the rig standing and
 * remembered; nothing else is left running.
 *
 * <p><b>Failures.</b> {@code TargetGone} for a recipe id the book has not got or a table that stood and is gone;
 * {@code MissingInput} for the station, a held piece, an input or the fuel not carried; {@code NoRoom} before anything
 * is put down; {@code NoPlace} when the body found nowhere to put the rig; {@code Unreachable} when the game would not
 * let a block down or open a menu, or the body could not get to its stance; {@code NoProgress} when the table never
 * listed the recipe, the lever would not switch or the power never rose; {@code Internal} for a BuildCraft member that
 * was not where the task looked, a laser that faced the wrong way, and the rest of the bug class.
 */
public final class AssembleTask extends BodyTask {
    /** The kind this task answers to, registered in {@code TaskFeature} beside the build and trade kinds. */
    public static final String KIND = "assemble";

    // --- the rig's geometry, in cells along its line from the stance --------------------------------------------

    /** Cells from the stance to the table: two, so the laser is three off and the look at its floor is the level one. */
    static final int TABLE_CELLS = 2;
    /** Cells from the stance to the laser: three, where a look down onto its floor still counts as looking along. */
    static final int LASER_CELLS = 3;

    // --- bounds, each with its reason -----------------------------------------------------------------------------

    /** How far from the body a stance for the rig is looked for: the rig is put up where the body stands, not walked to. */
    static final int SITE_RADIUS = 8;
    /**
     * How many stances the body walks to, one after another, when a walk fails: the same number the station tasks
     * allow themselves for the same walk.
     */
    static final int WALKS_ALLOWED = 4;
    /** Ticks the body is turned to the click before it is made: the game aims a placement by the rotation the turn ends in. */
    static final int TURN_TICKS = 1;
    /** How often the game may refuse to place one of the rig's blocks before the task stops asking. */
    static final int PLACE_REFUSALS_ALLOWED = 3;
    /** How often the game may refuse to open the engine's or the table's menu before the task stops asking. */
    static final int OPEN_REFUSALS_ALLOWED = 3;
    /** How often the lever may be clicked without its POWERED state turning true before the task gives up on it. */
    static final int LEVER_CLICKS_ALLOWED = 3;
    /**
     * Ticks the table may take to list the recipe after the inputs are in its buffer: it works them out on its own
     * tick, a few ticks after they arrive, and a hundred is past that without being a wait worth a failure of its own.
     */
    static final int QUEUE_WAIT_TICKS = 100;
    /**
     * Ticks the table's power may stand still before the task says why it thinks the rig is not working: sixty
     * seconds, which is past the engine's redstone poll (ten ticks), the laser's target rescan (forty to sixty) and
     * the soft start of its battery, and short against the eight minutes a chipset takes.
     */
    static final int STALL_DIAGNOSE_TICKS = 1200;
    /** A second such time with the power still unmoved: the rig is not working, and the failure says what was read. */
    static final int STALL_FAIL_TICKS = 2400;
    /** Ticks between two lines of the power the table holds: a live run is the only test this task has. */
    static final int POWER_LOG_TICKS = 1000;
    /** Blocks from the table the product is looked for in: it is dropped there or pushed into a chest beside it. */
    static final double PRODUCT_RADIUS = 4.0;
    /** Blocks around the rig what fell out of a piece taken down is picked up from: a broken table spills beside itself. */
    static final double SPILL_RADIUS = 3.0;
    /** Ticks one piece of the rig may take to come up: about what a table by hand takes, as the station tasks allow. */
    static final int TAKE_DOWN_WAIT_TICKS = 400;
    /** Blocks the body steps aside from the line to wait the craft out: clear of the rig, near enough to watch it. */
    static final int CLEAR_RADIUS = 3;
    /**
     * How much grace the aim's reach check is given over the body's block-interaction range: the game's own check of a
     * click pads by one ({@code canInteractWithBlock}), and the body stands anywhere in its cell rather than exactly
     * on its centre, so the engine's cell four away and the lever's diagonally beside it still get their click.
     */
    static final double AIM_GRACE = 1.0;
    /** How far above and below the body a stance is looked for: a rig goes on the level the body can reach. */
    static final int STANCE_SEARCH_HEIGHT = 2;

    // --- the menus -------------------------------------------------------------------------------------------------

    /** The assembly table menu's own slots, in the order it adds them: the buffer, twelve of them. */
    static final int BUFFER_SLOTS = 12;
    /** The engine menu's own fuel slot, the first slot it adds. */
    static final int ENGINE_FUEL_SLOT = 0;

    // --- BuildCraft's members, read by name ------------------------------------------------------------------------

    /** The table tile's map of the recipes it could run to the state each is in; a key's position in it is the button. */
    static final String RECIPES_STATES = "recipesStates";
    /** The output stack of one key of that map: how the task tells one recipe from another. */
    static final String OUTPUT = "output";
    /** The power the table holds, a long of micro-JJ, declared in the tile's superclass. */
    static final String POWER = "power";
    /** The method that says what the active recipe still costs, for the status line; read when it can be. */
    static final String TARGET = "getTarget";
    /** The engine's remaining burn time, for a stall's diagnosis; read when it can be. */
    static final String BURN_TIME = "burnTime";

    /** Where on the top face of a floor block the eyes aim: the centre first, then four points near its edges. */
    private static final double[][] AIM = {{0, 0}, {0.4, 0}, {-0.4, 0}, {0, 0.4}, {0, -0.4}};
    /** The horizontal directions a rig's line can run along. */
    private static final Direction[] HORIZONTALS = {Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};

    /** The rig: where the body stands, which way the line runs, and which side of the engine the lever goes on. */
    private record Rig(BlockPos stance, Direction along, Direction side) {
        BlockPos table() { return stance.relative(along, TABLE_CELLS); }

        BlockPos laser() { return stance.relative(along, LASER_CELLS); }

        /**
         * Beside the laser, not beyond it. Every click of the rig is made from the stance, and a cell beyond the
         * laser is hidden from there by the laser itself (seen on the host: the engine found no face the eyes
         * could see). An engine turns itself to whatever takes power beside it, so the side serves as well.
         */
        BlockPos engine() { return laser().relative(side); }

        BlockPos lever() { return engine().relative(side); }
    }

    /** One block of the rig as the take-down sees it: where it stands, what should stand there, what its item is. */
    private record Piece(BlockPos at, Block block, Item item, String name) {}

    private enum Phase { SITE, WALK, BUILD, FUEL, INPUTS, QUEUE, WAIT, FETCH, TAKEDOWN }

    private final NodeSpec leaf;
    /** The rig a checkpoint spoke of, tried before a search: a hint only, checked against the world. */
    @Nullable
    private final Rig hint;

    // Read from the leaf and the game in begin.
    private Item resultItem;
    private ItemKey resultKey;
    private int resultCount;
    private Item stationItem;
    private Block stationBlock;
    private Item laserItem;
    private Block laserBlock;
    private Item engineItem;
    private Block engineBlock;
    private Item leverItem;
    private Block leverBlock;
    private String laserName;
    private String engineName;
    private String leverName;
    /** Each input slot's distinct items in the recipe's own order, and how many one run takes of them. */
    private final List<List<Item>> inputs = new ArrayList<>();
    private final List<Integer> inputCounts = new ArrayList<>();
    /** The fuel slot's items and the count one run takes. */
    private List<Item> fuels = List.of();
    private int fuelCount = 1;
    private int runs = 1;
    private int targetCount;

    private Phase phase = Phase.SITE;
    @Nullable
    private Rig rig;
    /** The first cell a search for a site found that block safety refuses, when one did: what a NoPlace says. */
    @Nullable
    private TableSites.Refusal refusal;
    /** Stances a walk has already been tried from, so a failed walk offers another. */
    private final Set<BlockPos> triedStances = new HashSet<>();
    private int walks;
    private int walkRestarts;
    /** The sub-step of the build: laser, table, engine, lever, and the lever's use-click. */
    private int step;
    private int turnTicks;
    private int placeRefusals;
    private int openRefusals;
    private int leverClicks;
    private int queueTicks;
    private int waitTicks;
    /** The power the table held when the wait last looked, and what its active recipe then still needed. */
    private long lastPower;
    @Nullable
    private Long lastTarget;
    private int stallTicks;
    private boolean diagnosed;
    /** The take-down: what is left of it, and the answer that waits for it. */
    private final List<Piece> pieces = new ArrayList<>();
    private int pieceIndex;
    private int pieceTicks;
    private boolean gathering;
    @Nullable
    private TickResult pending;
    private boolean placedAny;
    private final DropPickup pickup = new DropPickup();
    private final DropPickup spill = new DropPickup();
    /** The number of the next block-breaking action the body is sent, so the game mode keeps them apart. */
    private int sequence;
    private int lastHeld;

    public AssembleTask(NodeSpec leaf, Map<String, String> hints) {
        this.leaf = leaf;
        this.hint = rigOf(hints.get("stance"), hints.get("along"), hints.get("side"));
    }

    // ---------------------------------------------------------------------------------------------------------
    // Start
    // ---------------------------------------------------------------------------------------------------------

    @Override
    protected void begin(BotPlayer body) {
        Map<String, String> params = leaf.params();
        String recipeId = params.getOrDefault("recipe", "");
        Optional<RecipeDef> found = RecipeBooks.current().byId(recipeId);
        if (found.isEmpty()) {
            fail(new Failure.TargetGone(recipeId, "the recipe book has no assemble recipe '" + recipeId + "'"));
            return;
        }
        RecipeDef recipe = found.get();
        resultKey = recipe.result();
        resultItem = itemOf(resultKey);
        if (resultItem == null) {
            fail(new Failure.TargetGone(recipeId, "'" + resultKey.id() + "' is not a registered item"));
            return;
        }
        resultCount = Math.max(1, recipe.resultCount());

        ItemKey stationKey = ItemKey.of(params.getOrDefault("station", ""));
        stationItem = itemOf(stationKey);
        stationBlock = blockOf(stationKey);
        if (stationItem == null || stationBlock == null) {
            fail(new Failure.Internal(null, "no registered item or block for the station " + params.get("station")));
            return;
        }
        String[] held = params.getOrDefault("held", "").split(",");
        if (held.length < 3 || held[0].isBlank() || held[1].isBlank() || held[2].isBlank()) {
            fail(new Failure.Internal(null, "the assemble leaf names fewer than the rig's three held pieces"
                + " (laser, engine, lever): " + params.get("held")));
            return;
        }
        // The rig's pieces in the leaf's order: the laser, the engine, the lever.
        laserItem = piece(held[0]);
        engineItem = piece(held[1]);
        leverItem = piece(held[2]);
        laserBlock = blockOfItem(laserItem);
        engineBlock = blockOfItem(engineItem);
        leverBlock = blockOfItem(leverItem);
        laserName = held[0].trim();
        engineName = held[1].trim();
        leverName = held[2].trim();
        if (laserBlock == null || engineBlock == null || leverBlock == null) {
            fail(new Failure.Internal(null, "a held piece of the rig is not a block item: " + params.get("held")));
            return;
        }

        // The recipe's last slot is the fuel, the others the inputs.
        List<RecipeDef.Slot> slots = recipe.slots();
        for (int i = 0; i < slots.size() - 1; i++) {
            inputs.add(itemsOf(slots.get(i).alternatives()));
            inputCounts.add(slots.get(i).count());
        }
        fuels = itemsOf(slots.get(slots.size() - 1).alternatives());
        fuelCount = slots.get(slots.size() - 1).count();
        if (inputs.isEmpty() || fuels.isEmpty()) {
            fail(new Failure.TargetGone(recipeId, "a slot of '" + recipeId + "' has no registered item"));
            return;
        }

        int times;
        try {
            times = Integer.parseInt(params.getOrDefault("times", "1"));
        } catch (NumberFormatException e) {
            fail(new Failure.Internal(e, "the assemble leaf's times is not a number: " + params.get("times")));
            return;
        }
        // The leaf's goal is the absolute count after this step, so a task started again makes only what is still
        // missing — rounded up, for a run is whole — and never more than the leaf's times (the craft task's rule).
        int carried = MenuHand.carriedCount(body, resultItem);
        runs = times;
        if (leaf.goal() instanceof Goal.HaveItem have && have.key().equals(resultKey)) {
            runs = Math.floorDiv(have.count() - carried + resultCount - 1, resultCount);
        }
        runs = Math.max(0, Math.min(times, runs));
        targetCount = carried + runs * resultCount;

        // Everything must be carried before anything is put down, so an assemble that cannot run leaves the rig in
        // the inventory: the station, the rig's pieces, and every input and the fuel for the runs still to do.
        if (MenuHand.carriedCount(body, stationItem) < 1) {
            fail(new Failure.MissingInput(stationKey, 1, "no assembly table is carried"));
            return;
        }
        for (Item heldPiece : new Item[] {laserItem, engineItem, leverItem}) {
            if (MenuHand.carriedCount(body, heldPiece) < 1) {
                fail(new Failure.MissingInput(ItemKeys.of(heldPiece), 1,
                    "the rig needs a " + ItemKeys.of(heldPiece).id() + " and none is carried"));
                return;
            }
        }
        for (int i = 0; i < inputs.size(); i++) {
            Failure missing = StationTask.missingInput(body, inputs.get(i), runs * inputCounts.get(i), "assemble");
            if (missing != null) {
                fail(missing);
                return;
            }
        }
        Failure fuelMissing = StationTask.missingInput(body, fuels, runs * fuelCount, "assemble");
        if (fuelMissing != null) {
            fail(fuelMissing);
            return;
        }
        int shortOf = targetCount - carried;
        int room = MenuHand.roomFor(body, new ItemStack(resultItem));
        if (room < shortOf) {
            fail(new Failure.NoRoom(resultKey, "there is no room in the inventory for the " + shortOf + " "
                + resultKey.id() + " this step makes (room for " + room + ")"));
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Tick
    // ---------------------------------------------------------------------------------------------------------

    @Override
    protected TickResult onTick(BotPlayer body) {
        lastHeld = MenuHand.carriedCount(body, resultItem);
        if (runs <= 0) return tidy(body, TickResult.COMPLETED);
        try {
            return switch (phase) {
                case SITE -> site(body);
                case WALK -> walk(body);
                case BUILD -> build(body);
                case FUEL -> fuel(body);
                case INPUTS -> inputs(body);
                case QUEUE -> queue(body);
                case WAIT -> waitPower(body);
                case FETCH -> fetch(body);
                case TAKEDOWN -> takeDown(body);
            };
        } catch (MissingMember e) {
            // BuildCraft was not where the task looked for it: a fact about the host, logged where it was read.
            return tidy(body, TickResult.failed(new Failure.Internal(null, e.getMessage())));
        }
    }

    /** Finds a place for the rig and the stance the body works from; the chosen site gets the step's first line. */
    private TickResult site(BotPlayer body) {
        if (rig == null) rig = findRig(body);
        if (rig == null) {
            String why = refusal.any()
                ? "no cell within " + SITE_RADIUS + " blocks that the rig could go on is one block safety lets this"
                    + " body build on (" + refusal.verdict.rule() + ": " + refusal.verdict.detail() + ", first"
                    + " refused at " + refusal.at.toShortString() + ")"
                : "found no level line of three free cells on solid ground with air above, with a cell beside the"
                    + " engine for the lever, within " + SITE_RADIUS + " blocks";
            return stop(body, new Failure.NoPlace("put an assembly rig down", SITE_RADIUS, why));
        }
        if (body.blockPosition().equals(rig.stance())) {
            // The walk's own command is done with: every click of the rig is made standing still, aimed by hand.
            BotNavigator nav = BotNavigator.of(body);
            if (nav.state() == BotNavigator.State.WORKING && !nav.runsCommandOfAnother(this)) nav.stop();
            MegaBlockBot.LOGGER.info("{}: the assembly rig goes up on the line {} to {}, standing at {} (table {},"
                    + " laser {}, engine {}, lever {})", body.getGameProfile().getName(),
                rig.stance().toShortString(), rig.engine().toShortString(), rig.stance().toShortString(),
                rig.table().toShortString(), rig.laser().toShortString(), rig.engine().toShortString(),
                rig.lever().toShortString());
            step = 0;
            turnTicks = 0;
            placeRefusals = 0;
            phase = Phase.BUILD;
            return TickResult.RUNNING;
        }
        if (walks >= WALKS_ALLOWED) {
            return stop(body, new Failure.NoPlace("put an assembly rig down", SITE_RADIUS,
                "walked to " + walks + " stances for the assembly rig and could not get to the last"));
        }
        walkRestarts = 0;
        nav(body).goTo(rig.stance(), 0);
        phase = Phase.WALK;
        return TickResult.RUNNING;
    }

    private TickResult walk(BotPlayer body) {
        BotNavigator nav = nav(body);
        switch (nav.state()) {
            case DONE -> {
                nav.stop();
                phase = Phase.SITE; // looked at again from where the body now is
            }
            case FAILED -> {
                nav.stop();
                if (rig != null) triedStances.add(rig.stance());
                walks++;
                rig = null;
                phase = Phase.SITE;
            }
            case IDLE -> {
                // Something else stopped the navigator: this task still owns the body.
                if (++walkRestarts > WALKS_ALLOWED) {
                    return stop(body, unreachable("the stance for the assembly rig", "the navigator stopped",
                        "the navigator stopped " + walkRestarts + " times on the way to the rig's stance"));
                }
                if (rig != null) nav.goTo(rig.stance(), 0);
            }
            case WORKING -> { }
        }
        return TickResult.RUNNING;
    }

    /**
     * Puts the rig up, one block at a time, in the order that aims the laser: the laser first, read back for the way
     * it faces; then the table; then the engine; then the lever, switched on by a use-click. A block that already
     * stands (a resumed task) is left where it is, so the build is gone through again from the top without harm.
     */
    private TickResult build(BotPlayer body) {
        ServerLevel level = body.serverLevel();
        if (rig == null) {
            phase = Phase.SITE;
            return TickResult.RUNNING;
        }
        if (!body.blockPosition().equals(rig.stance())) {
            // Carried off since the build began: the stance is the one place every click of the rig is made from.
            BotNavigator nav = nav(body);
            if (nav.state() != BotNavigator.State.WORKING) nav.goTo(rig.stance(), 0);
            return TickResult.RUNNING;
        }
        return switch (step) {
            case 0 -> placed(body, rig.laser(), laserItem, laserBlock, laserName) ? readLaserFacing(body)
                : TickResult.RUNNING;
            case 1 -> placed(body, rig.table(), stationItem, stationBlock, "assembly table") ? next(body)
                : TickResult.RUNNING;
            case 2 -> placed(body, rig.engine(), engineItem, engineBlock, engineName) ? next(body) : TickResult.RUNNING;
            case 3 -> placed(body, rig.lever(), leverItem, leverBlock, leverName) ? next(body) : TickResult.RUNNING;
            default -> switchOn(body);
        };
    }

    /** Advances the build to the next block, or to the work when the rig is up. */
    private TickResult next(BotPlayer body) {
        step++;
        turnTicks = 0;
        placeRefusals = 0;
        // Past the lever's placing the build is not over: the next tick switches it on (switchOn), and that is
        // what moves the task on to the fuel. Moving on here left the lever off and the engine cold all run.
        return TickResult.RUNNING;
    }

    /**
     * Puts one block of the rig down: the item is taken into the hand, the body is turned to the click and left a tick
     * to finish the turn, and the click is made on the top face of the floor under the cell. True when the cell holds
     * the block this tick (placed now, or standing already). The task has already stopped itself when the click cannot
     * be made or the game refuses it past its bound.
     */
    private boolean placed(BotPlayer body, BlockPos cell, Item item, Block block, String name) {
        ServerLevel level = body.serverLevel();
        if (level.getBlockState(cell).is(block)) return true;
        if (MenuHand.carriedCount(body, item) < 1) {
            stop(body, new Failure.MissingInput(ItemKeys.of(item), 1, "the " + name + " was carried and is gone"));
            return false;
        }
        Vec3 click = aimAt(body, eyeAt(body, rig.stance()), level, cell, body.blockInteractionRange() + AIM_GRACE);
        if (click == null) {
            if (++placeRefusals > PLACE_REFUSALS_ALLOWED) {
                stop(body, unreachable("a place for the " + name, "no face the eyes can see and reach",
                    "found nowhere on the floor under " + cell.toShortString() + " to click the " + name + " onto"));
            }
            return false;
        }
        if (++turnTicks <= TURN_TICKS) {
            // The game aims a placement by the rotation the turn ends in, and a look written to the input is only in
            // that rotation from the next tick (BodyInput.applyTo, after the body's own tick).
            body.input().lookAt(click);
            body.lookAt(EntityAnchorArgument.Anchor.EYES, click);
            return false;
        }
        turnTicks = 0;
        if (!StationTask.hold(body, item)) {
            stop(body, new Failure.NoProgress(0, "the " + name + " could not be taken into the hand"));
            return false;
        }
        body.getInteractionManager().interactBlock(body, level, body.getMainHandItem(), InteractionHand.MAIN_HAND,
            new BlockHitResult(click, Direction.UP, cell.below(), false));
        if (!level.getBlockState(cell).is(block)) {
            if (++placeRefusals > PLACE_REFUSALS_ALLOWED) {
                stop(body, unreachable("a place for the " + name, "the game would not let the body put it down",
                    "the " + name + " was not placed into " + cell.toShortString() + " in " + placeRefusals + " tries"));
            }
            return false;
        }
        BotMemory.of(body.getUUID()).notePlaced(dimensionOf(body), cell);
        BlockSafety.of(level.getServer()).noteBotPlaced(body, level, cell);
        placedAny = true;
        MegaBlockBot.LOGGER.info("{}: the {} goes down at {}", body.getGameProfile().getName(), name,
            cell.toShortString());
        return true;
    }

    /**
     * The laser's facing, read back from the block the game just made of the click: a laser faces away from where its
     * placer looked and nothing rotates it later, so one that does not point back along the line at the table's cell
     * is a rig that would never power it. Such a laser comes up again and the task fails saying what it faced.
     */
    private TickResult readLaserFacing(BotPlayer body) {
        ServerLevel level = body.serverLevel();
        BlockState state = level.getBlockState(rig.laser());
        Direction facing = state.hasProperty(BlockStateProperties.FACING)
            ? state.getValue(BlockStateProperties.FACING) : null;
        if (facing != null) {
            MegaBlockBot.LOGGER.info("{}: the laser at {} faces {}", body.getGameProfile().getName(),
                rig.laser().toShortString(), facing.name());
        }
        if (facing == rig.along().getOpposite()) return next(body);
        return takeLaserUp(body, new Failure.Internal(null, "the laser at " + rig.laser().toShortString()
            + " faces " + (facing == null ? "no way at all" : facing.name()) + ", not "
            + rig.along().getOpposite().name() + " back along the line at the table at "
            + rig.table().toShortString() + ", so the rig would never power the table"));
    }

    /** The lever is switched on by a use-click, as a player flips one; POWERED true is read back off the block. */
    private TickResult switchOn(BotPlayer body) {
        ServerLevel level = body.serverLevel();
        BlockState state = level.getBlockState(rig.lever());
        if (!state.is(leverBlock)) {
            step = 3; // the lever is gone since it was placed: the build goes back to it
            return TickResult.RUNNING;
        }
        if (state.hasProperty(BlockStateProperties.POWERED) && state.getValue(BlockStateProperties.POWERED)) {
            MegaBlockBot.LOGGER.info("{}: the lever at {} is on", body.getGameProfile().getName(),
                rig.lever().toShortString());
            phase = Phase.FUEL;
            return TickResult.RUNNING;
        }
        if (++leverClicks > LEVER_CLICKS_ALLOWED) {
            return stop(body, new Failure.NoProgress(leverClicks, "the lever at " + rig.lever().toShortString()
                + " would not switch on in " + leverClicks + " clicks"));
        }
        body.input().sneak = false; // a click made sneaking would put a block down, not use the lever
        body.getInteractionManager().interactBlock(body, level, body.getMainHandItem(), InteractionHand.MAIN_HAND,
            new BlockHitResult(Vec3.atCenterOf(rig.lever()), Direction.UP, rig.lever(), false));
        return TickResult.RUNNING;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Fuel, inputs, the queue
    // ---------------------------------------------------------------------------------------------------------

    /** The engine's fuel goes in through its own menu, slot 0, the way a player fills one. */
    private TickResult fuel(BotPlayer body) {
        TickResult back = atTheRig(body);
        if (back != null) return back;
        body.input().sneak = false;
        Item fuel = StationTask.firstCarried(body, fuels);
        if (fuel == null) {
            // Nothing is carried: the fuel went in before an interruption, and the engine holds it.
            phase = Phase.INPUTS;
            return TickResult.RUNNING;
        }
        AbstractContainerMenu menu = open(body, rig.engine(), engineBlock);
        if (menu == null) return TickResult.RUNNING;
        MenuHand hand = new MenuHand(body, menu);
        if (hand.pickUp(fuel)) {
            int in = 0;
            for (int i = 0; i < runs * fuelCount && !menu.getCarried().isEmpty(); i++) {
                Slot slot = menu.getSlot(ENGINE_FUEL_SLOT);
                ItemStack there = slot.getItem();
                if (!there.isEmpty() && there.getCount() >= slot.getMaxStackSize(there)) break;
                int before = menu.getCarried().getCount();
                hand.placeOne(ENGINE_FUEL_SLOT);
                if (menu.getCarried().getCount() >= before) break; // the slot took no more
                in++;
            }
            hand.putBack();
            MegaBlockBot.LOGGER.info("{}: {} {} in the engine at {}", body.getGameProfile().getName(), in,
                ItemKeys.of(fuel).id(), rig.engine().toShortString());
        }
        body.closeContainer();
        phase = Phase.INPUTS;
        return TickResult.RUNNING;
    }

    /** The inputs for all the runs go into the table's buffer, menu slots 0 to 11, one right-clicked item at a time. */
    private TickResult inputs(BotPlayer body) {
        TickResult back = atTheRig(body);
        if (back != null) return back;
        body.input().sneak = false;
        AbstractContainerMenu menu = open(body, rig.table(), stationBlock);
        if (menu == null) return TickResult.RUNNING;
        StringBuilder what = new StringBuilder();
        for (int i = 0; i < inputs.size(); i++) {
            Item item = StationTask.firstCarried(body, inputs.get(i));
            if (item == null) continue; // already in the buffer, before an interruption
            int wanted = runs * inputCounts.get(i);
            int in = 0;
            MenuHand hand = new MenuHand(body, menu);
            if (!hand.pickUp(item)) continue;
            for (int slot = 0; slot < BUFFER_SLOTS && in < wanted; slot++) {
                while (in < wanted && !menu.getCarried().isEmpty()) {
                    Slot into = menu.getSlot(slot);
                    ItemStack there = into.getItem();
                    if (!there.isEmpty() && (!there.is(item) || there.getCount() >= into.getMaxStackSize(there))) break;
                    int before = menu.getCarried().getCount();
                    hand.placeOne(slot);
                    if (menu.getCarried().getCount() >= before) break; // that slot is full: the next one
                    in++;
                }
            }
            hand.putBack();
            if (!what.isEmpty()) what.append(", ");
            what.append(in).append(' ').append(ItemKeys.of(item).id());
        }
        body.closeContainer();
        MegaBlockBot.LOGGER.info("{}: {} in the assembly table at {}", body.getGameProfile().getName(), what,
            rig.table().toShortString());
        queueTicks = 0;
        phase = Phase.QUEUE;
        return TickResult.RUNNING;
    }

    /**
     * The recipe is queued by the button the table's own catalogue gives it: the position, in the map's own order, of
     * the key whose output is the leaf's item, pressed through {@code clickMenuButton} — the call a client's click on
     * the button row ends in. The position is read again each time, for it shifts when other recipes appear, and the
     * state is read with it: a recipe already queued is left alone, for the button that queues a recipe also pauses it.
     */
    private TickResult queue(BotPlayer body) {
        TickResult back = atTheRig(body);
        if (back != null) return back;
        body.input().sneak = false;
        ServerLevel level = body.serverLevel();
        if (!level.getBlockState(rig.table()).is(stationBlock)) {
            return tidy(body, TickResult.failed(new Failure.TargetGone("the assembly table at "
                + rig.table().toShortString(), "it stood when the inputs went in and it is gone now")));
        }
        Object tile = level.getBlockEntity(rig.table());
        if (tile == null) {
            return tidy(body, TickResult.failed(new Failure.Internal(null, "the assembly table at "
                + rig.table().toShortString() + " has no block entity to queue the recipe in")));
        }
        AbstractContainerMenu menu = open(body, rig.table(), stationBlock);
        if (menu == null) return TickResult.RUNNING;
        int button = buttonFor(tile, resultItem);
        if (button < 0) {
            body.closeContainer();
            if (++queueTicks > QUEUE_WAIT_TICKS) {
                return tidy(body, TickResult.failed(new Failure.NoProgress(queueTicks,
                    "the assembly table at " + rig.table().toShortString() + " listed no recipe making "
                        + resultKey.id() + " within " + QUEUE_WAIT_TICKS + " ticks of the inputs arriving ("
                        + listed(tile) + " listed)")));
            }
            return TickResult.RUNNING;
        }
        String state = stateAt(tile, button);
        if (state.equals("POSSIBLE") || state.equals("PAUSED")) {
            menu.clickMenuButton(body, button);
            MegaBlockBot.LOGGER.info("{}: queued '{}' at button {} (state {})", body.getGameProfile().getName(),
                leaf.params().get("recipe"), button, state);
        } else {
            MegaBlockBot.LOGGER.info("{}: '{}' is already queued at button {} (state {})",
                body.getGameProfile().getName(), leaf.params().get("recipe"), button, state);
        }
        body.closeContainer();
        waitTicks = 0;
        stallTicks = 0;
        diagnosed = false;
        lastPower = powerOf(tile);
        standClear(body);
        phase = Phase.WAIT;
        return TickResult.RUNNING;
    }

    // ---------------------------------------------------------------------------------------------------------
    // The wait and the product
    // ---------------------------------------------------------------------------------------------------------

    /**
     * The table works at its own pace and needs no body, so the task waits: the power it holds is read every tick and
     * said every thousand, and a table whose power has stood still for a named time is diagnosed and then given up on.
     * The product is dropped at the table or pushed into a chest beside it, so it is looked for there and picked up.
     */
    private TickResult waitPower(BotPlayer body) {
        ServerLevel level = body.serverLevel();
        if (!level.getBlockState(rig.table()).is(stationBlock)) {
            return tidy(body, TickResult.failed(new Failure.TargetGone("the assembly table at "
                + rig.table().toShortString(), "it stood when the recipe was queued and it is gone now")));
        }
        Object tile = level.getBlockEntity(rig.table());
        long power = powerOf(tile);
        if (++waitTicks % POWER_LOG_TICKS == 0) {
            lastTarget = targetOf(tile); // kept for the status line, which asks more often than this says it
            Long target = lastTarget;
            MegaBlockBot.LOGGER.info("{}: the assembly table at {} holds {} of the {} micro-MJ it needs; the body"
                    + " holds {} of {} {}", body.getGameProfile().getName(), rig.table().toShortString(), power,
                target == null ? "unknown" : target, held(body), targetCount, resultKey.id());
        }
        if (power > lastPower) {
            lastPower = power;
            stallTicks = 0;
        } else if (++stallTicks >= STALL_FAIL_TICKS) {
            return tidy(body, TickResult.failed(new Failure.NoProgress(stallTicks,
                "the assembly table at " + rig.table().toShortString() + " held " + power + " micro-MJ for "
                    + stallTicks + " ticks without rising: " + diagnose(body))));
        } else if (stallTicks == STALL_DIAGNOSE_TICKS && !diagnosed) {
            diagnosed = true;
            MegaBlockBot.LOGGER.info("{}: the assembly table's power has not risen for {} ticks: {}",
                body.getGameProfile().getName(), stallTicks, diagnose(body));
        }
        if (held(body) >= targetCount) return tidy(body, TickResult.COMPLETED);
        if (product(body) != null) {
            pickup.start();
            phase = Phase.FETCH;
        }
        return TickResult.RUNNING;
    }

    /** Picks the product up from wherever it lies; with the count met the rig comes down, and another run is queued. */
    private TickResult fetch(BotPlayer body) {
        if (pickup.tick(this, body, drop -> drop.getItem().is(resultItem),
                new AABB(rig.table()).inflate(PRODUCT_RADIUS))) {
            return TickResult.RUNNING;
        }
        pickup.rest();
        if (held(body) >= targetCount) {
            MegaBlockBot.LOGGER.info("{}: the {} is taken; the body holds {} of {}", body.getGameProfile().getName(),
                resultKey.id(), held(body), targetCount);
            return tidy(body, TickResult.COMPLETED);
        }
        if (product(body) == null) {
            // Another run is owed and nothing lies by the table: the recipe is queued again, for the craft that
            // finished may have left the queue, and the table lists it afresh while its inputs last.
            queueTicks = 0;
            phase = Phase.QUEUE;
        } else {
            // A drop the pick-up gave up on: the wait goes on beside it, and the stall watch ends the step if the
            // table never makes anything of what is left.
            phase = Phase.WAIT;
        }
        return TickResult.RUNNING;
    }

    /** The nearest drop of the product within {@link #PRODUCT_RADIUS} of the table, or null when none lies there. */
    @Nullable
    private ItemEntity product(BotPlayer body) {
        ItemEntity nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (ItemEntity drop : body.serverLevel().getEntitiesOfClass(ItemEntity.class,
                new AABB(rig.table()).inflate(PRODUCT_RADIUS),
                candidate -> candidate.isAlive() && candidate.getItem().is(resultItem))) {
            double distance = drop.distanceToSqr(body);
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = drop;
            }
        }
        return nearest;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Taking the rig down
    // ---------------------------------------------------------------------------------------------------------

    /**
     * Every exit goes through here. What the task put up comes down before the answer is given — the completion's and
     * every failure's after a block was placed — for the planner counts the rig's pieces in the inventory, not in the
     * world, and a rig left standing would cost the next goal its obsidian and its diamonds.
     */
    private TickResult tidy(BotPlayer body, TickResult answer) {
        if (body.containerMenu != body.inventoryMenu) body.closeContainer();
        if (placedAny || !pieces.isEmpty()) {
            pending = answer;
            beginTakeDown(false);
            return TickResult.RUNNING;
        }
        release(body);
        return answer;
    }

    private TickResult stop(BotPlayer body, Failure failure) {
        return tidy(body, TickResult.failed(failure));
    }

    /** The take-down of a laser that faced the wrong way: the one piece comes up, then the failure is the answer. */
    private TickResult takeLaserUp(BotPlayer body, Failure why) {
        if (body.containerMenu != body.inventoryMenu) body.closeContainer();
        pending = TickResult.failed(why);
        beginTakeDown(true);
        return TickResult.RUNNING;
    }

    private void beginTakeDown(boolean laserOnly) {
        pieces.clear();
        if (rig != null) {
            if (!laserOnly) {
                pieces.add(new Piece(rig.lever(), leverBlock, leverItem, leverName));
                pieces.add(new Piece(rig.engine(), engineBlock, engineItem, engineName));
            }
            pieces.add(new Piece(rig.laser(), laserBlock, laserItem, laserName));
            if (!laserOnly) pieces.add(new Piece(rig.table(), stationBlock, stationItem, "assembly table"));
        }
        pieceIndex = 0;
        pieceTicks = 0;
        gathering = false;
        phase = Phase.TAKEDOWN;
    }

    /**
     * One piece at a time, lever first (so the engine stops), then engine, laser and table: each is broken with the
     * tool that breaks it best and what falls out of it is picked up — its own item and whatever the table's buffer or
     * the engine still held. A piece the rules refuse, one whose break would drop nothing, one whose item the
     * inventory has no room for and one that will not come up in time is left standing, remembered as the body's, and
     * named in the log.
     */
    private TickResult takeDown(BotPlayer body) {
        if (pieceIndex >= pieces.size()) {
            TickResult answer = pending == null ? TickResult.COMPLETED : pending;
            pending = null;
            pieces.clear();
            placedAny = false;
            release(body);
            return answer;
        }
        Piece piece = pieces.get(pieceIndex);
        ServerLevel level = body.serverLevel();
        if (gathering) {
            // The piece is gone and what fell out of it is being picked up: that ends before the next one is broken.
            if (spill.tick(this, body, drop -> true, rigBox())) return TickResult.RUNNING;
            spill.rest();
            pieceIndex++;
            pieceTicks = 0;
            gathering = false;
            return TickResult.RUNNING;
        }
        BlockState state = level.getBlockState(piece.at());
        if (!state.is(piece.block())) return pieceDone(body, piece);
        if (++pieceTicks > TAKE_DOWN_WAIT_TICKS) {
            leave(body, piece, "it would not come up in " + TAKE_DOWN_WAIT_TICKS + " ticks");
            return pieceDone(body, piece);
        }
        if (!body.canInteractWithBlock(piece.at(), 0.0)) {
            BotNavigator nav = nav(body);
            if (nav.state() != BotNavigator.State.WORKING) nav.goTo(piece.at(), 1);
            return TickResult.RUNNING;
        }
        IInteractionManager hand = body.getInteractionManager();
        if (!hand.isMining()) {
            Verdict verdict = BlockSafety.of(level.getServer()).mayBreak(body, level, piece.at(), state);
            if (!verdict.allowed()) {
                leave(body, piece, verdict.rule() + ": " + verdict.detail());
                return pieceDone(body, piece);
            }
            if (state.requiresCorrectToolForDrops() && !StationTask.dropsFor(body, state)) {
                leave(body, piece, "the body carries no tool that makes it drop when broken");
                return pieceDone(body, piece);
            }
            if (MenuHand.roomFor(body, new ItemStack(piece.item())) == 0) {
                leave(body, piece, "the inventory has no room for its item");
                return pieceDone(body, piece);
            }
            StationTask.selectBreakingTool(body, state);
            hand.processBlockBreakingAction(piece.at(), Action.START_DESTROY_BLOCK, Direction.UP,
                level.getMaxBuildHeight(), sequence++);
        }
        hand.continueBreaking(piece.at(), Direction.UP);
        // A client sends the finish once its own progress reaches 1; the engine reports progress in tenths.
        if (hand.isMining() && hand.getBlockBreakingProgress() >= 10) {
            hand.processBlockBreakingAction(hand.getMiningPos(), Action.STOP_DESTROY_BLOCK, Direction.UP,
                level.getMaxBuildHeight(), sequence++);
        }
        if (!level.getBlockState(piece.at()).is(piece.block())) {
            MegaBlockBot.LOGGER.info("{}: the {} at {} comes up", body.getGameProfile().getName(), piece.name(),
                piece.at().toShortString());
            forget(body, piece);
            gathering = true;
            spill.start();
        }
        return TickResult.RUNNING;
    }

    /**
     * The piece is out of the way — taken up, or left standing and remembered — so it is on to the next, and to the
     * answer when the rig is down.
     */
    private TickResult pieceDone(BotPlayer body, Piece piece) {
        if (!body.serverLevel().getBlockState(piece.at()).is(piece.block())) forget(body, piece);
        pieceIndex++;
        pieceTicks = 0;
        gathering = false;
        return TickResult.RUNNING;
    }

    /** The box the rig stands in, widened by {@link #SPILL_RADIUS}: what a piece drops beside it lands in here. */
    private AABB rigBox() {
        BlockPos from = rig.stance();
        BlockPos to = rig.engine().relative(rig.side());
        return new AABB(Math.min(from.getX(), to.getX()), Math.min(from.getY(), to.getY()), Math.min(from.getZ(), to.getZ()),
            Math.max(from.getX(), to.getX()) + 1.0, Math.max(from.getY(), to.getY()) + 1.0,
            Math.max(from.getZ(), to.getZ()) + 1.0).inflate(SPILL_RADIUS);
    }

    private void forget(BotPlayer body, Piece piece) {
        BotMemory.of(body.getUUID()).forgetPlaced(dimensionOf(body), piece.at());
    }

    /** A piece that stays: it is still the body's own in its memory, and the log says what and why. */
    private void leave(BotPlayer body, Piece piece, String why) {
        MegaBlockBot.LOGGER.info("{} leaves the {} at {} standing: {}", body.getGameProfile().getName(), piece.name(),
            piece.at().toShortString(), why);
    }

    // ---------------------------------------------------------------------------------------------------------
    // The site
    // ---------------------------------------------------------------------------------------------------------

    /**
     * The rig this task will build: the checkpoint's hint first, then one this task already had (a resume), then the
     * nearest stance whose line fits.
     */
    @Nullable
    private Rig findRig(BotPlayer body) {
        ServerLevel level = body.serverLevel();
        refusal = new TableSites.Refusal();
        if (hint != null && fits(body, level, hint, refusal)) return hint;
        if (rig != null && fits(body, level, rig, refusal)) return rig;
        return search(body, level, SITE_RADIUS, refusal);
    }

    /**
     * The stances near the body, nearest first, each with the four ways the line can run and the two the lever can
     * sit: the first whose four cells fit. A stance the body could not walk to is not offered again.
     */
    @Nullable
    private Rig search(BotPlayer body, ServerLevel level, int radius, TableSites.Refusal refusal) {
        BlockPos centre = body.blockPosition();
        List<BlockPos> stances = new ArrayList<>();
        for (BlockPos candidate : BlockPos.betweenClosed(centre.offset(-radius, -STANCE_SEARCH_HEIGHT, -radius),
                centre.offset(radius, STANCE_SEARCH_HEIGHT, radius))) {
            if (triedStances.contains(candidate) || !TableSites.standable(level, candidate)) continue;
            stances.add(candidate.immutable());
        }
        stances.sort(Comparator.comparingDouble(stand -> stand.distSqr(centre)));
        for (BlockPos stand : stances) {
            for (Direction along : HORIZONTALS) {
                for (Direction side : new Direction[] {along.getClockWise(), along.getCounterClockWise()}) {
                    Rig candidate = new Rig(stand, along, side);
                    if (fits(body, level, candidate, refusal)) return candidate;
                }
            }
        }
        return null;
    }

    private boolean fits(BotPlayer body, ServerLevel level, Rig candidate, TableSites.Refusal refusal) {
        if (!TableSites.standable(level, candidate.stance())) return false;
        if (inDangerZone(body, candidate.stance())) return false; // not somewhere to stand for eight minutes
        return cell(body, level, candidate, candidate.table(), stationBlock, false, refusal)
            && cell(body, level, candidate, candidate.laser(), laserBlock, false, refusal)
            && cell(body, level, candidate, candidate.engine(), engineBlock, false, refusal)
            && cell(body, level, candidate, candidate.lever(), leverBlock, true, refusal);
    }

    /**
     * One cell of the rig: built on already (a resume leaves it be), or free — replaceable, dry, air above, solid
     * ground under it, nobody standing in it, and a place the rules let this body build on — with the floor under it
     * offering the top face the click is made on. The lever's cell asks one thing more: a floor whose top face is
     * sturdy, for a lever will not sit on anything else.
     */
    private boolean cell(BotPlayer body, ServerLevel level, Rig of, BlockPos at, Block block, boolean lever,
                         TableSites.Refusal refusal) {
        BlockState state = level.getBlockState(at);
        if (state.is(block)) return true;
        if (!level.isLoaded(at) || level.isOutsideBuildHeight(at) || !state.canBeReplaced()
            || !state.getFluidState().isEmpty()) return false;
        if (!lever && !level.getBlockState(at.above()).isAir()) return false;
        BlockPos under = at.below();
        BlockState below = level.getBlockState(under);
        if (below.getCollisionShape(level, under).isEmpty()) return false;
        if (lever && !below.isFaceSturdy(level, under, Direction.UP)) return false;
        if (!level.isUnobstructed(body, Shapes.block().move(at.getX(), at.getY(), at.getZ()))) return false;
        Verdict verdict = BlockSafety.of(level.getServer()).mayBreak(body, level, at, state);
        if (!verdict.allowed()) {
            refusal.note(verdict, at);
            return false;
        }
        return aimAt(body, eyeAt(body, of.stance()), level, at, body.blockInteractionRange() + AIM_GRACE) != null;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Reading the world
    // ---------------------------------------------------------------------------------------------------------

    /**
     * True when the body is where the rig's work is done from — near enough to reach the lever, the piece of the rig
     * that stands furthest from the stance, by the game's own padded measure — and otherwise it is walked back there.
     * Null when it is.
     */
    @Nullable
    private TickResult atTheRig(BotPlayer body) {
        if (rig == null) {
            phase = Phase.SITE;
            return TickResult.RUNNING;
        }
        if (body.canInteractWithBlock(rig.lever(), 1.0)) return null;
        BotNavigator nav = nav(body);
        if (nav.state() != BotNavigator.State.WORKING) nav.goTo(rig.stance(), 0);
        return TickResult.RUNNING;
    }

    /** Steps the body aside off the rig's line, where it waits the craft out; nowhere to step is not a failure. */
    private void standClear(BotPlayer body) {
        ServerLevel level = body.serverLevel();
        BlockPos feet = body.blockPosition();
        if (!onLine(feet)) return;
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos candidate : BlockPos.betweenClosed(feet.offset(-CLEAR_RADIUS, -1, -CLEAR_RADIUS),
                feet.offset(CLEAR_RADIUS, 1, CLEAR_RADIUS))) {
            if (onLine(candidate) || !TableSites.standable(level, candidate)) continue;
            double distance = candidate.distSqr(feet);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate.immutable();
            }
        }
        if (best != null) nav(body).goTo(best, 0);
    }

    /** Whether the cell lies on the rig's line, stance to engine: the body waits the craft out clear of it. */
    private boolean onLine(BlockPos pos) {
        if (rig == null || pos.getY() != rig.stance().getY()) return false;
        return switch (rig.along().getAxis()) {
            case X -> pos.getZ() == rig.stance().getZ() && between(pos.getX(), rig.stance().getX(), rig.laser().getX());
            case Z -> pos.getX() == rig.stance().getX() && between(pos.getZ(), rig.stance().getZ(), rig.laser().getZ());
            case Y -> false;
        };
    }

    private static boolean between(int value, int from, int to) {
        return value >= Math.min(from, to) && value <= Math.max(from, to);
    }

    /** Where the top face of the floor under {@code cell} is clicked from {@code eye}, or null when the eyes cannot. */
    @Nullable
    private static Vec3 aimAt(BotPlayer body, Vec3 eye, ServerLevel level, BlockPos cell, double reach) {
        BlockPos floor = cell.below();
        if (!level.isLoaded(floor) || level.isOutsideBuildHeight(floor)) return null;
        for (double[] aim : AIM) {
            Vec3 target = new Vec3(floor.getX() + 0.5 + aim[0], floor.getY() + 1.0, floor.getZ() + 0.5 + aim[1]);
            if (eye.distanceToSqr(target) > reach * reach) continue;
            // A hair past the face, so the ray surely crosses it.
            Vec3 through = target.add(0.0, -0.02, 0.0);
            BlockHitResult hit = level.clip(new ClipContext(eye, through, ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE, body));
            if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(floor)
                && hit.getDirection() == Direction.UP) {
                return hit.getLocation();
            }
        }
        return null;
    }

    /** The eyes of a body standing at {@code stance}: where the rig's clicks are aimed from. */
    private static Vec3 eyeAt(BotPlayer body, BlockPos stance) {
        return new Vec3(stance.getX() + 0.5, stance.getY() + body.getEyeHeight(), stance.getZ() + 0.5);
    }

    /**
     * The menu a use-click on the block at {@code at} opened, or null while none opens: the game's own answer, asked
     * again until its bound. A block that is gone sends the task back to the top, where the world is read afresh.
     */
    @Nullable
    private AbstractContainerMenu open(BotPlayer body, BlockPos at, Block block) {
        ServerLevel level = body.serverLevel();
        if (!level.getBlockState(at).is(block)) {
            phase = Phase.SITE;
            return null;
        }
        if (body.containerMenu != body.inventoryMenu) body.closeContainer();
        if (body.isShiftKeyDown()) {
            body.input().sneak = false; // a click made sneaking would put a block down, not open the menu
            return null;
        }
        if (body.containerMenu == body.inventoryMenu) {
            body.getInteractionManager().interactBlock(body, level, body.getMainHandItem(), InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(at), Direction.UP, at, false));
        }
        if (body.containerMenu == body.inventoryMenu) {
            if (++openRefusals > OPEN_REFUSALS_ALLOWED) {
                stop(body, new Failure.Unreachable("the menu of the block at " + at.toShortString(),
                    "the game would not open it", "the game would not open the menu of the block at "
                    + at.toShortString() + " in " + openRefusals + " tries"));
            }
            return null;
        }
        openRefusals = 0;
        return body.containerMenu;
    }

    private int held(BotPlayer body) {
        return MenuHand.carriedCount(body, resultItem);
    }

    /** What a stall says, read from the world: the lever's state, the laser's facing, and the engine's fuel if it says. */
    private String diagnose(BotPlayer body) {
        ServerLevel level = body.serverLevel();
        BlockState leverState = level.getBlockState(rig.lever());
        String lever = leverState.hasProperty(BlockStateProperties.POWERED)
            ? (leverState.getValue(BlockStateProperties.POWERED) ? "on" : "off") : "no lever";
        BlockState laserState = level.getBlockState(rig.laser());
        String laser = laserState.hasProperty(BlockStateProperties.FACING)
            ? "facing " + laserState.getValue(BlockStateProperties.FACING).name() : "no laser";
        Object engine = level.getBlockEntity(rig.engine());
        Long burn = engine == null ? null : longOfOrNull(engine, BURN_TIME);
        return "the lever at " + rig.lever().toShortString() + " is " + lever + ", the laser at "
            + rig.laser().toShortString() + " is " + laser + ", and the engine at " + rig.engine().toShortString()
            + " has " + (burn == null ? "a burn time it will not say" : burn + " ticks of fuel left");
    }

    // ---------------------------------------------------------------------------------------------------------
    // BuildCraft, read by member name
    // ---------------------------------------------------------------------------------------------------------

    /** A BuildCraft member that was not where the task looked for it: a fact about the host, and the end of the work. */
    private static final class MissingMember extends RuntimeException {
        MissingMember(String member) {
            super("BuildCraft's " + member + " was not where the assemble task looked for it");
        }
    }

    /**
     * The position, in the table's own order, of the recipe whose output is the leaf's item, or -1 when the table
     * lists none: that position is the button that queues it.
     */
    private static int buttonFor(Object tile, Item output) {
        int index = 0;
        for (Object key : recipes(tile).keySet()) {
            Object stack = member(key, OUTPUT);
            if (stack instanceof ItemStack offered && offered.is(output)) return index;
            index++;
        }
        return -1;
    }

    /** The state the table holds the recipe at {@code button} in, as the enum's own name; nothing says nothing. */
    private static String stateAt(Object tile, int button) {
        int index = 0;
        for (Object key : recipes(tile).keySet()) {
            if (index++ == button) {
                Object state = recipes(tile).get(key);
                return state == null ? "nothing" : String.valueOf(state);
            }
        }
        return "nothing";
    }

    /** How many recipes the table lists just now, for the failure that says it listed none of the leaf's. */
    private static int listed(Object tile) {
        return recipes(tile).size();
    }

    private static Map<?, ?> recipes(Object tile) {
        Object map = member(tile, RECIPES_STATES);
        if (!(map instanceof Map<?, ?> recipes)) {
            throw new MissingMember(tile.getClass().getSimpleName() + "." + RECIPES_STATES);
        }
        return recipes;
    }

    /** The power the table holds, in micro-JJ, read from its own field; a tile that says nothing ends the work. */
    private static long powerOf(@Nullable Object tile) {
        if (tile == null) throw new MissingMember("assembly table tile");
        Object value = member(tile, POWER);
        if (!(value instanceof Number number)) throw new MissingMember(tile.getClass().getSimpleName() + "." + POWER);
        return number.longValue();
    }

    /** The micro-JJ the active recipe still needs, or null when the tile will not say: the status line only. */
    @Nullable
    private static Long targetOf(@Nullable Object tile) {
        if (tile == null) return null;
        try {
            Object answer = tile.getClass().getMethod(TARGET).invoke(tile);
            return answer instanceof Number number ? number.longValue() : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            MegaBlockBot.LOGGER.warn("Could not call {}() on {}: {}", TARGET, tile.getClass().getName(),
                e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage()));
            return null;
        }
    }

    /** A number the member holds, or null when the member is not there or holds no number: read when it can be. */
    @Nullable
    private static Long longOfOrNull(Object on, String name) {
        try {
            Object value = member(on, name);
            return value instanceof Number number ? number.longValue() : null;
        } catch (MissingMember e) {
            return null;
        }
    }

    /**
     * Reads the field {@code name} of {@code on}, public anywhere in its class hierarchy first and however it is
     * declared otherwise. BuildCraft is not on this build's classpath, so its tile is only reachable by name, and a
     * member that is not there is a logged line and {@link MissingMember}.
     */
    private static Object member(Object on, String name) {
        try {
            return fieldOf(on.getClass(), name).get(on);
        } catch (ReflectiveOperationException | RuntimeException e) {
            MegaBlockBot.LOGGER.warn("Could not read the field {}.{}: {}", on.getClass().getName(), name,
                e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage()));
            throw new MissingMember(on.getClass().getSimpleName() + "." + name);
        }
    }

    @Nullable
    private static Field fieldOf(Class<?> type, String name) throws ReflectiveOperationException {
        try {
            return type.getField(name);
        } catch (NoSuchFieldException notPublic) {
            for (Class<?> up = type; up != null; up = up.getSuperclass()) {
                try {
                    Field found = up.getDeclaredField(name);
                    found.setAccessible(true);
                    return found;
                } catch (NoSuchFieldException missing) {
                    // the class above, then
                }
            }
            throw notPublic;
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Reading the leaf's items
    // ---------------------------------------------------------------------------------------------------------

    @Nullable
    private static Item itemOf(ItemKey key) {
        ResourceLocation id = ResourceLocation.tryParse(key.id());
        return id == null ? null : BuiltInRegistries.ITEM.getOptional(id).orElse(null);
    }

    @Nullable
    private static Block blockOf(ItemKey key) {
        ResourceLocation id = ResourceLocation.tryParse(key.id());
        return id == null ? null : BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
    }

    /** The registered item a held piece names, or null when the game has none. */
    @Nullable
    private static Item piece(String name) {
        return itemOf(ItemKey.of(name.trim()));
    }

    @Nullable
    private static Block blockOfItem(@Nullable Item item) {
        return item instanceof BlockItem blockItem ? blockItem.getBlock() : null;
    }

    private static List<Item> itemsOf(List<ItemKey> keys) {
        List<Item> items = new ArrayList<>();
        for (ItemKey key : keys) {
            Item item = itemOf(key);
            if (item != null) items.add(item);
        }
        return List.copyOf(items);
    }

    @Nullable
    private static Rig rigOf(@Nullable String stance, @Nullable String along, @Nullable String side) {
        if (stance == null || along == null || side == null) return null;
        Direction way = directionOf(along);
        Direction hand = directionOf(side);
        if (way == null || hand == null) return null;
        try {
            Pos pos = Pos.parse(stance);
            return new Rig(new BlockPos(pos.x(), pos.y(), pos.z()), way, hand);
        } catch (RuntimeException e) {
            return null; // a hint is only where to look: a bad one costs a search
        }
    }

    /** The direction a checkpoint named, or null when it named none. */
    @Nullable
    private static Direction directionOf(String name) {
        try {
            return Direction.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------------------------------------

    @Override
    protected void resumeWork(BotPlayer body) {
        // The rig needs no body, so it went on running while the task was paused. Everything is read again from the
        // world: the blocks that stand, the power the table holds, the count the body carries. The take-down of a rig
        // already going down goes on from the piece it was on, for its answer waits for it.
        if (phase == Phase.TAKEDOWN) return;
        turnTicks = 0;
        placeRefusals = 0;
        openRefusals = 0;
        leverClicks = 0;
        queueTicks = 0;
        phase = Phase.SITE;
    }

    /**
     * True while the game, not the body, is who must act next: the table working at its own pace, a product being
     * picked up, a piece of the rig coming up — so the stuck watchdog leaves the task alone.
     */
    @Override
    public boolean waiting() {
        return phase == Phase.WAIT || phase == Phase.FETCH || phase == Phase.TAKEDOWN;
    }

    @Override
    protected void letGo(BotPlayer body) {
        pickup.rest();
        spill.rest();
        if (body == null) return;
        if (body.containerMenu != body.inventoryMenu) body.closeContainer();
        new MenuHand(body, body.inventoryMenu).putBack();
        abortBreak(body);
        body.input().reset();
        if (state() != TaskState.PAUSED && rig != null && anyStands(body)) {
            // An end that is not a pause leaves the rig standing and remembered, and the log says where it is: the
            // pieces are the next plan's to gather again, and nothing else in this code knows how to take them up.
            MegaBlockBot.LOGGER.warn("{} ends its assemble with the assembly rig standing: table {}, laser {}, engine"
                    + " {}, lever {} (it stays remembered as the body's)", body.getGameProfile().getName(),
                rig.table().toShortString(), rig.laser().toShortString(), rig.engine().toShortString(),
                rig.lever().toShortString());
        }
    }

    /** Whether any block of the rig still stands: what an end that is not a pause leaves behind. */
    private boolean anyStands(BotPlayer body) {
        ServerLevel level = body.serverLevel();
        return level.getBlockState(rig.table()).is(stationBlock) || level.getBlockState(rig.laser()).is(laserBlock)
            || level.getBlockState(rig.engine()).is(engineBlock) || level.getBlockState(rig.lever()).is(leverBlock);
    }

    /** Stops the break of a rig piece in progress, if one is: the abort names the position being broken. */
    private void abortBreak(BotPlayer body) {
        if (!(body.getInteractionManager() instanceof BodyInteractionManager hand)) return;
        if (pieceIndex >= pieces.size()) return;
        BlockPos at = pieces.get(pieceIndex).at();
        if (hand.isMining() && at.equals(hand.getMiningPos())) hand.abortBreak();
    }

    @Override
    public Map<String, String> checkpoint() {
        Map<String, String> out = new LinkedHashMap<>();
        if (rig != null) {
            out.put("stance", corePos(rig.stance()).encode());
            out.put("along", rig.along().name());
            out.put("side", rig.side().name());
        }
        return out;
    }

    /** What the task is doing now, for the status line: the wait says the power the table holds against what it needs. */
    @Override
    public String progress() {
        String what = switch (phase) {
            case SITE -> "looking for a place to put the assembly rig down";
            case WALK -> "walking to a place for the assembly rig";
            case BUILD -> "putting the assembly rig up";
            case FUEL -> "putting fuel in the engine";
            case INPUTS -> "putting the inputs in the assembly table";
            case QUEUE -> "queueing the recipe in the assembly table";
            case WAIT -> "waiting for the assembly table to make " + resultKey.id() + " (holds " + lastPower
                + " of " + (lastTarget == null ? "unknown" : lastTarget) + " micro-MJ)";
            case FETCH -> "picking up what the assembly table made";
            case TAKEDOWN -> "taking the assembly rig down";
        };
        return what + "; holds " + lastHeld + " of " + targetCount + " " + resultKey.id();
    }
}
