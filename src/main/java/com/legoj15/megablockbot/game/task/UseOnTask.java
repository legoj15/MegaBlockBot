package com.legoj15.megablockbot.game.task;

import com.legoj15.megablockbot.core.acquire.Source;
import net.minecraft.world.phys.shapes.CollisionContext;
import com.legoj15.megablockbot.MegaBlockBot;
import com.legoj15.megablockbot.core.ItemKey;
import com.legoj15.megablockbot.core.Pos;
import com.legoj15.megablockbot.core.goal.Goal;
import com.legoj15.megablockbot.core.plan.NodeSpec;
import com.legoj15.megablockbot.core.task.Failure;
import com.legoj15.megablockbot.core.task.TickResult;
import com.legoj15.megablockbot.game.body.BotPlayer;
import com.legoj15.megablockbot.game.nav.BotNavigator;
import com.legoj15.megablockbot.game.nav.BreakFilter;
import com.legoj15.megablockbot.game.nav.BodyInteractionManager;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * {@code use_on}: hold a tool and use it on a fluid, a block or a mob until the body holds the goal's count of the
 * item (issue #28: a lava bucket scooped from a lava source, a milk bucket from a cow, a salmon caught into a water
 * bucket, wool sheared from a sheep). Everything goes through the game the way a player's click does: the tool in the
 * main hand, a look at the target, one use, then the count.
 *
 * <p>Parameters from the planner: {@code item}, {@code count} (how many more; the goal's absolute
 * {@link Goal.HaveItem} count wins), {@code tool} (what is held and used), {@code spent} ({@code true} when the tool
 * becomes the result, as a bucket does), {@code targetKind} ({@code fluid}, {@code block} or {@code entity}),
 * {@code target} (the registry id) and {@code variant} (a sheep's dye colour, only when there is one). The leaf's
 * source id rides along in {@code NoSource} so a replan excludes exactly that source.
 *
 * <p>The task:
 * <ol>
 *   <li>fails {@code MissingInput} naming the tool when the body holds none of it (for a spent tool, at least one: the
 *       plan provides one per unit as it goes). A full inventory the result would not fit is {@code NoRoom}: with
 *       several of a spent tool stacked in the slot the hand takes one from the filled one cannot stack onto the rest
 *       and would be dropped, and a kept tool's result (wool) lies where it fell until it is picked up;</li>
 *   <li>finds a target within the search radius the kill and harvest tasks use: a source cell of the fluid (a cell of
 *       the block) with a standable cell beside it that the use reaches -- asked of the same ray-cast the use makes, so
 *       a source walled in behind a rim is only taken from a stand whose line gets over that rim -- or a living mob of
 *       the type that is ready (not a baby; a sheep ready for shearing and of the variant's colour; any adult for
 *       milking, which takes nothing). A mob, a cell or a stand inside a live danger zone is not a candidate (issue 29),
 *       and how many were left for that reason is said in the {@code NoSource} detail. Cells this body has used the tool
 *       on for nothing are read too, and not chosen again. None: it explores with the kill task's bounds and looks
 *       again, and past them fails {@code NoSource} with the leaf's item and source id
 *       ({@link BodyTask#exploreFailure} decides what an exploring command's failure says);</li>
 *   <li>scooping a fluid removes a block, so the body's break rules are asked for the cell and a refused one is set
 *       aside; when only refused cells are left the failure is {@code Protected}. When only cells that gave nothing are
 *       left the failure is {@code NoProgress}, since the target is there and the rules allow it and the body stood
 *       beside it -- the use is what came to nothing. Catching and shearing take something from a mob, so a named or
 *       leashed one is left alone (the kill task's eligibility test, shared: {@link KillTask#takeable});</li>
 *   <li>goes to the target through the navigator: to a standable cell beside the fluid, never one that is the fluid
 *       itself and never one whose floor would not hold the body, so the lava being scooped is neither stood in nor
 *       pathed through -- and a cell the body is sent back to that many times without the use being made from it is set
 *       aside, as one whose uses gave nothing is; or, for a mob, a follow that closes on it as the kill task does,
 *       re-aiming as it moves, with the kill task's own watch on the chase: a mob that is neither come closer to nor
 *       within reach for a named time is set aside and another taken, for a follow command never fails for want of a
 *       path;</li>
 *   <li>uses the tool once: the tool in the main hand, the body turned at the target -- into the fluid cell a little
 *       below its surface, at the mob's eyes -- and left one tick to finish the turn before the use is given, because
 *       the game aims its own ray-cast by the rotation the turn ends in. That use is the item use for a fluid or a
 *       block and the body's own {@code interactOn} for a mob, which runs the mob's own {@code interact} and then the
 *       held item's {@code interactLivingEntity}. One use per attempt; a few ticks later the count is read, and a
 *       target that has not paid out after a named number of attempts is set aside;</li>
 *   <li>picks up what a use dropped (wool) with {@link DropPickup}, the one walk to dropped items the kill, harvest
 *       and recover tasks use.</li>
 * </ol>
 *
 * <p>Pause, resume, abort and {@code release} leave nothing running: the navigator command the task issued is stopped
 * by the task that issued it, and no use is held (an item use is one tick long; nothing keeps a key down).
 */
public final class UseOnTask extends BodyTask {
    public static final String KIND = "use_on";

    /** How far from the body a mob target is looked for: the kill task's own radius. */
    public static final double MOB_SEARCH_RADIUS = KillTask.SEARCH_RADIUS;
    /** How far around the body, in the chunks that are loaded already, a fluid or block target is looked for. */
    private static final int SCAN_RADIUS = 32;
    /** How far from the target cell a stand is looked for: within the reach of a use on it. */
    private static final int STAND_SEARCH = 4;
    /** How high above the feet of a standing body its eyes are: where a use is aimed from. */
    private static final double EYE_HEIGHT = 1.62;
    /**
     * How far below the top of its cell a use on it is aimed, into the cell. A fluid source whose cell is open to the
     * air has its surface eight ninths of the way up it, so a line ending on the top face's own plane never enters the
     * box the game's ray-cast tests for the fluid and answers with the air above it; a solid cell's boundary is
     * answered by whichever of the two cells the walk reaches first. Below the boundary by more than the fluid's own
     * margin, and well above its bottom, is the answer both cases agree on.
     */
    private static final double AIM_BELOW_TOP = 0.2;
    /**
     * How far from the eyes a use may be aimed. The game's own item use ray-casts 4.5 blocks along the look and no
     * further; half a block less leaves room for a body that stands a little off the middle of its cell. A stand
     * further off than this sees the target and cannot use anything on it (seen on the host: a diagonal stand 5.2
     * blocks from the aim, a ray that ended in the air and then, a step closer, on the rim of the basin).
     */
    private static final double USE_REACH = 4.0;
    /**
     * How far toward the side of a fluid cell away from the body that aim point lies: a source stands behind the rim of
     * the blocks it is walled in, and the farther in the line runs before it dips to the surface, the more of that rim
     * it clears on the way.
     */
    private static final double FLUID_AIM_ACROSS = 0.35;
    /**
     * Ticks the body is turned before the use is given: the game's item use ray-casts along the body's real rotation
     * ({@code BucketItem.use} through {@code Level.clip}), and a look written to the input is only in that rotation
     * from the next tick ({@code BodyInput.applyTo}, applied after the brain's tick). The fish task waits two ticks for
     * its cast for the same reason; one is enough here, because the body stands still while it turns.
     */
    private static final int TURN_TICKS = 1;
    /** The navigator keeps the body within this many blocks of the mob it follows: inside the reach of a use. */
    private static final int FOLLOW_DISTANCE = 2;
    /**
     * Within this many blocks of the mob the body closes on it by its own steps, as the kill task does: a follow
     * command holds the body at its follow radius, which is not the same thing as the reach a use needs.
     */
    private static final double CLOSE_ON = 4.0;
    /** Ticks after a use before the count is read: a scoop is at once, a shearing's drops take a moment to lie still. */
    private static final int RESULT_WAIT_TICKS = 10;
    /** Uses one target is given before it is set aside and another taken. */
    private static final int USES_PER_TARGET = 4;
    /**
     * Times one target sends the body back to its stand without the use being made before the cell is set aside, as a
     * cell whose uses gave nothing is: the navigator's own idea of "at the stand" is not always where the use needs it.
     */
    private static final int RETURNS_PER_TARGET = 3;
    /**
     * Ticks a mob is neither come closer to nor within reach of before it is set aside: the navigator's own stall
     * bound for one block ({@code BotNavigator.TARGET_STALL_TICKS}), as the kill task's chase watch takes it.
     */
    private static final int CHASE_STALL_TICKS = BotNavigator.TARGET_STALL_TICKS;
    /** Getting this much closer to a mob counts as a gain; less is the body swaying about (the kill task's number). */
    private static final double CLOSER_BY = 0.5;
    /** How far from the target the drops of the leaf's item are walked to: the kill task's own radius. */
    private static final double LOOT_RADIUS = 8.0;
    /**
     * A search step takes at least this many ticks even when the exploring command failed at once (a sealed arena has
     * nowhere unexplored to walk to): the kill task's own number, for the same reason.
     */
    private static final int MIN_STEP_TICKS = 20;
    /** The most set-aside targets a checkpoint keeps. */
    private static final int MAX_SAVED_ASIDE = 64;

    /**
     * How hard the task looks for a target when none is known: one stretch of exploring followed by another look, and
     * the whole search gives up after {@code maxAttempts} of them. The kill task's bounds.
     *
     * @param stepTicks   ticks an exploring step lasts
     * @param maxAttempts steps before the search gives up
     */
    public record Search(int stepTicks, int maxAttempts) {
        /** 30 seconds per step, twelve steps: as far as the kill task's default search walks. */
        public static final Search DEFAULT = new Search(600, 12);
    }

    private enum Phase { FIND, EXPLORE, USE, WAIT, LOOT }

    private final Search search;
    private final String sourceId;
    private final ItemKey itemKey;
    private final ItemKey toolKey;
    private final boolean toolSpent;
    private final String targetKind;
    private final String targetLabel;
    @Nullable
    private final String variant;
    private final int goalCount;
    private final int moreCount;

    // From an earlier task for the same node: hints for where to look, checked against the world before use.
    private final String hintDimension;
    private final int hintAttempts;
    private final Set<Pos> hintAside;

    private Item item;
    private Item tool;
    private Block block;
    @Nullable
    private Fluid fluid;
    @Nullable
    private EntityType<?> type;
    private String dimension = "";
    private int targetCount;
    private boolean alreadyDone;
    private Phase phase = Phase.FIND;
    private BlockPos origin;
    private int attempts;
    /** Cells the body could not get to: the navigator failed on them. Kept for the task's life and its checkpoint. */
    private final Set<BlockPos> setAside = new HashSet<>();
    /**
     * Cells the body stood at and used the tool on, and got nothing from: not cells the rules refuse, and not a source
     * that is not there -- the cell holds what the leaf is after and the body was next to it. For the life of the task.
     */
    private final Set<BlockPos> unusable = new HashSet<>();
    /** Mobs the task will not go after again (it could not get at them, or they would not pay out). */
    private final Set<UUID> asideMobs = new HashSet<>();
    /** How many targets the last look left alone for standing inside a live danger zone (issue 29). */
    private int zonedTargets;
    @Nullable
    private BlockPos at;
    @Nullable
    private BlockPos stand;
    @Nullable
    private Mob quarry;
    /** The serial of the command this task gave last, or -1: how it tells its own command's fate from anybody else's. */
    private long serial = -1;
    private int uses;
    /** How many times this target has sent the body back to its stand without the use being made from it. */
    private int returns;
    /** Ticks this attempt has spent turning the body to the target: the use is made once the turn is through. */
    private int turnTicks;
    /** The chase's own watch: which mob the body is after, how close it has come, and for how long none of that. */
    @Nullable
    private UUID chasing;
    private double bestDistance = Double.MAX_VALUE;
    private int noProgress;
    private int waitTicks;
    /** How many of the item the body held when the last use was made: what the wait compares against. */
    private int heldAtUse;
    /** Where the last use was made: the box the drops of it are looked for in. */
    @Nullable
    private BlockPos lootAt;
    /** The walk to what a use dropped, shared with the kill and harvest tasks. */
    private final DropPickup pickup = new DropPickup();
    private boolean waiting;
    private int stepTicks;
    /** The held count as of the last tick: what the status line says while the task runs. */
    private int lastHeld;

    public UseOnTask(NodeSpec leaf, Map<String, String> hints) {
        this(leaf, hints, Search.DEFAULT);
    }

    public UseOnTask(NodeSpec leaf, Map<String, String> hints, Search search) {
        this.search = search;
        this.sourceId = leaf.source();
        this.itemKey = ItemKey.of(required(leaf, "item"));
        this.toolKey = ItemKey.of(required(leaf, "tool"));
        this.toolSpent = Boolean.parseBoolean(leaf.params().getOrDefault("spent", "false"));
        this.targetKind = required(leaf, "targetKind");
        this.targetLabel = required(leaf, "target");
        this.variant = leaf.params().get("variant");
        this.goalCount = leaf.goal() instanceof Goal.HaveItem g && g.key().equals(itemKey) ? g.count() : -1;
        this.moreCount = intParam(leaf.params(), "count", 1);

        this.hintDimension = hints.getOrDefault("dim", "");
        this.hintAttempts = parseInt(hints.get("attempts"));
        this.hintAside = new HashSet<>();
        for (String text : hints.getOrDefault("aside", "").split(";")) {
            try {
                hintAside.add(Pos.parse(text.trim()));
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
        item = itemOf(itemKey);
        if (item == null) {
            fail(new Failure.NoSource(itemKey, sourceId, "'" + itemKey.id() + "' is not a registered item"));
            return;
        }
        tool = itemOf(toolKey);
        if (tool == null) {
            fail(new Failure.NoSource(itemKey, sourceId, "'" + toolKey.id() + "' is not a registered item"));
            return;
        }
        switch (targetKind) {
            case Source.UseOn.FLUID -> fluid = fluidOf(targetLabel);
            case Source.UseOn.BLOCK -> block = blockOf(targetLabel);
            case Source.UseOn.ENTITY -> type = entityOf(targetLabel);
            default -> {
                fail(new Failure.NoSource(itemKey, sourceId, "'" + targetKind + "' is not a target kind"));
                return;
            }
        }
        if (fluid == null && block == null && type == null) {
            fail(new Failure.NoSource(itemKey, sourceId, "'" + targetLabel + "' is not registered"));
            return;
        }

        lastHeld = held(body);
        targetCount = goalCount > 0 ? goalCount : lastHeld + Math.max(1, moreCount);
        if (lastHeld >= targetCount) {
            // Nothing to do, and nothing is touched: the first tick says so.
            alreadyDone = true;
            return;
        }
        if (MenuHand.carriedCount(body, tool) < 1) {
            fail(new Failure.MissingInput(toolKey, 1, "using a " + toolKey.id() + " on " + what() + " for "
                + itemKey.id() + " needs a " + toolKey.id() + " and the body carries none"));
            return;
        }
        if (roomProblem(body)) {
            // A spent tool held as one cannot take the filled one (it would be dropped), and a kept tool's result lies
            // where it fell until picked up: neither is worth the walk. A replan would plan the same step into the
            // same full inventory.
            fail(new Failure.NoRoom(itemKey, "the inventory cannot take one more " + itemKey.id()
                + ": no free slot and no stack of it with room"));
            return;
        }
        origin = body.blockPosition();
        if (hintDimension.equals(dimension)) {
            attempts = Math.min(hintAttempts, search.maxAttempts());
            // What an earlier task for this node could not get at is not tried again. The world is looked at all the
            // same, so a cell that is gone costs nothing.
            for (Pos aside : hintAside) setAside.add(blockPos(aside));
        }
        // The first tick looks for the first target: begin only decides what is wanted.
    }

    /** Whether the result of a use cannot be taken: no room at all for a kept tool's drop, none for a stacked spent one. */
    private boolean roomProblem(BotPlayer body) {
        if (MenuHand.roomFor(body, new ItemStack(item)) > 0) return false;
        // A single spent tool is replaced in the hand by what it becomes: that always fits. What counts is the stack
        // the use takes the tool from, not how many of it the body carries in all -- two in two slots are two uses
        // that each replace their own.
        return !toolSpent || toolStackSize(body) > 1;
    }

    /**
     * How many of the tool are in the stack the use takes it from: the first stack of it on the hotbar, else the
     * first in carried storage (the one a {@code MenuHand} swap brings into the hand whole). That stack is what the
     * filled tool has to live beside; another stack of the tool elsewhere is another use's problem.
     */
    private int toolStackSize(BotPlayer body) {
        var inventory = body.getInventory();
        for (int slot = 0; slot < MenuHand.CARRIED_SLOTS; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.is(tool)) return stack.getCount();
        }
        return 0;
    }

    @Override
    protected void resumeWork(BotPlayer body) {
        // Everything is looked at again from the world: the target may be gone, the mob may have moved, and the count
        // the body holds is counted anew.
        at = null;
        stand = null;
        lootAt = null;
        quarry = null;
        serial = -1;
        uses = 0;
        returns = 0;
        turnTicks = 0;
        chasing = null;
        phase = Phase.FIND;
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
        // The goal first, whatever got the items into the inventory (a use of this task, a drop picked up on the way).
        lastHeld = held(body);
        if (lastHeld >= targetCount) {
            release(body);
            return TickResult.COMPLETED;
        }
        waiting = false; // only the tick that waits on the game says it is
        return switch (phase) {
            case FIND -> find(body);
            case EXPLORE -> explore(body);
            case USE -> use(body);
            case WAIT -> waitForResult(body);
            case LOOT -> loot(body);
        };
    }

    private TickResult find(BotPlayer body) {
        if (type != null) return findMob(body);
        return findCell(body);
    }

    /** A mob target: the nearest one that is ready, followed until the body is within reach of a use. */
    private TickResult findMob(BotPlayer body) {
        BotNavigator nav = nav(body);
        if (quarry == null || !ready(quarry)) {
            quarry = findQuarry(body);
            serial = -1;
            uses = 0;
            turnTicks = 0;
            chasing = null;
            if (quarry != null) {
                MegaBlockBot.LOGGER.info("{}: going to the {} at {} to use the {} on it",
                    body.getGameProfile().getName(), describe(quarry), quarry.blockPosition().toShortString(),
                    toolKey.id());
            }
        }
        if (quarry == null) return nextStep(body);
        if (nav.state() == BotNavigator.State.FAILED && nav.serial() == serial) {
            // The body could not get to that one: it is set aside, as an unreachable block is, and another is taken.
            asideMobs.add(quarry.getUUID());
            MegaBlockBot.LOGGER.info("{}: could not get at the {} at {}; set it aside", body.getGameProfile().getName(),
                describe(quarry), quarry.blockPosition().toShortString());
            quarry = null;
            chasing = null;
            return nextStep(body);
        }
        TickResult stalled = watchTheChase(body);
        if (stalled != null) return stalled;
        if (inReach(body, quarry)) {
            stopOurCommand(nav);
            phase = Phase.USE;
            return use(body);
        }
        if (quarry.distanceTo(body) < CLOSE_ON) {
            // The follow command has brought the body near enough that a straight walk at the mob is a route a player
            // would take: the body's own steps from here (the engine is stopped for them, or it would write its own
            // input over them in its tick), re-aiming as the mob moves about.
            stopOurCommand(nav);
            walkAt(body, quarry.getEyePosition());
            return TickResult.RUNNING;
        }
        if (nav.state() != BotNavigator.State.WORKING) {
            nav.follow(quarry, FOLLOW_DISTANCE);
            serial = nav.serial();
        }
        return TickResult.RUNNING;
    }

    /**
     * Whether the chase is going anywhere: it is while the body keeps getting closer to the mob, or while it is close
     * enough to use on it. When neither has happened for {@link #CHASE_STALL_TICKS} ticks the mob is set aside and
     * another is taken -- a follow command never fails for want of a path (it keeps trying), so this watch is what
     * keeps a mob behind a wall from holding the body until the step's budget is spent while a mob it could reach is
     * never tried. Null while the chase goes on.
     */
    @Nullable
    private TickResult watchTheChase(BotPlayer body) {
        if (!quarry.getUUID().equals(chasing)) {
            chasing = quarry.getUUID();
            bestDistance = quarry.distanceTo(body);
            noProgress = 0;
            return null;
        }
        // A gain is measured against where the last gain left the body, not against the tick before: a walk covers
        // a fifth of a block a tick, which is never a gain on its own and is one every three ticks. Measured tick
        // to tick the watch was a plain timeout, and a body closing steadily on a far quarry gave it up.
        double distance = quarry.distanceTo(body);
        if (distance < bestDistance - CLOSER_BY) {
            bestDistance = distance;
            noProgress = 0;
            return null;
        }
        if (inReach(body, quarry)) {
            noProgress = 0;
            return null;
        }
        if (++noProgress <= CHASE_STALL_TICKS) return null;
        asideMobs.add(quarry.getUUID());
        MegaBlockBot.LOGGER.info("{}: no closer to the {} at {} in {} ticks; set it aside", body.getGameProfile().getName(),
            describe(quarry), quarry.blockPosition().toShortString(), CHASE_STALL_TICKS);
        quarry = null;
        chasing = null;
        return nextStep(body);
    }

    /** A fluid or block target: the nearest cell the rules allow, with a stand beside it, walked to. */
    private TickResult findCell(BotPlayer body) {
        if (at != null && stand != null && isTarget(body.serverLevel(), at)) {
            BotNavigator nav = nav(body);
            switch (nav.state()) {
                case DONE -> {
                    phase = Phase.USE;
                    return use(body);
                }
                case FAILED -> {
                    // The body could not get to that cell: set it aside, as the mine task does, and take another.
                    setAside.add(at.immutable());
                    return findNext(body);
                }
                case IDLE -> {
                    nav.goTo(stand, 0);
                    return TickResult.RUNNING;
                }
                default -> {
                    return TickResult.RUNNING;
                }
            }
        }
        return findNext(body);
    }

    /**
     * Scans the loaded chunks around the body for the nearest cell of the target that the rules allow and that has a
     * stand beside it, and goes to it. The cells are collected and sorted first, and the break rules and the stands
     * are asked only of the ones the scan actually considers, nearest first: a basin thirty blocks off is not asked
     * about while one five blocks off serves. A cell or a stand inside a live danger zone is not a candidate
     * (issue 29), as the kill, harvest and fish tasks skip theirs, and how many were left for that reason is said in
     * the {@code NoSource} detail. Nothing zoned is set aside: it comes back when the zone lapses.
     */
    private TickResult findNext(BotPlayer body) {
        quarry = null;
        chasing = null;
        at = null;
        stand = null;
        ServerLevel level = body.serverLevel();
        BreakFilter rules = BreakRules.filterFor(body);
        BlockPos center = body.blockPosition();
        int r = SCAN_RADIUS;
        int lowY = Math.max(level.getMinBuildHeight(), center.getY() - r);
        int highY = Math.min(level.getMaxBuildHeight() - 1, center.getY() + r);
        List<BlockPos> cells = new ArrayList<>();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        // The nearest cell the body has stood at and used the tool on to no end: what that leaves as the answer when
        // nothing else can be taken.
        BlockPos spent = null;
        double spentDistance = Double.MAX_VALUE;
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
                            if (!isTarget(level, cursor, state)) continue;
                            // A plain position: a mutable one is not equal to the plain blocks in the set.
                            BlockPos here = cursor.immutable();
                            if (setAside.contains(here)) continue;
                            if (unusable.contains(here)) {
                                double distance = here.distSqr(center);
                                if (distance < spentDistance) {
                                    spentDistance = distance;
                                    spent = here;
                                }
                                continue;
                            }
                            cells.add(here);
                        }
                    }
                }
            }
        }
        cells.sort(Comparator.comparingDouble(cell -> cell.distSqr(center)));
        int zoned = 0;
        BlockPos refused = null;
        for (BlockPos here : cells) {
            if (inDangerZone(body, here)) {
                zoned++;
                continue;
            }
            // Asked here and not in the scan, so that only the cells nearest the body are asked at all.
            if (!mayTake(rules, level, here)) {
                if (refused == null) refused = here; // the nearest one the rules refuse
                continue;
            }
            BlockPos aStand = standFor(level, center, here);
            if (aStand == null) continue; // nowhere to stand beside it: not a target for this body
            if (inDangerZone(body, aStand)) {
                zoned++;
                continue;
            }
            zonedTargets = zoned;
            return goToCell(body, here, aStand);
        }
        zonedTargets = zoned;
        if (refused != null) {
            release(body);
            return TickResult.failed(new Failure.Protected(corePos(refused), "break rules",
                "the " + what() + " at " + refused.toShortString() + " may not be taken"));
        }
        if (spent != null) {
            // A cell that is there, that the body reached and stood at, and that gave nothing to the tool: not a cell
            // the rules refuse (Protected), and not a source that is not there (NoSource, which would have the planner
            // exclude this one). What is wrong is the use, and only the game knows why.
            release(body);
            String detail = "using a " + toolKey.id() + " on the " + what() + " at " + spent.toShortString() + " "
                + USES_PER_TARGET + " times gave nothing, and no other " + what() + " is to be reached";
            MegaBlockBot.LOGGER.info("{}: gave up on the {} at {}: {}", body.getGameProfile().getName(), what(),
                spent.toShortString(), detail);
            return TickResult.failed(new Failure.NoProgress(0, detail));
        }
        return nextStep(body);
    }

    private TickResult goToCell(BotPlayer body, BlockPos cell, BlockPos aStand) {
        at = cell;
        stand = aStand;
        uses = 0;
        returns = 0;
        turnTicks = 0;
        MegaBlockBot.LOGGER.info("{}: going to {} at {} to use the {} on it from {}", body.getGameProfile().getName(),
            what(), cell.toShortString(), toolKey.id(), aStand.toShortString());
        phase = Phase.FIND; // findCell walks the body to the stand it chose and moves it on to the use
        nav(body).goTo(stand, 0);
        return TickResult.RUNNING;
    }

    /** No target is known: look further, or say there is none to be found. */
    private TickResult nextStep(BotPlayer body) {
        if (++attempts > search.maxAttempts()) return giveUp(body);
        MegaBlockBot.LOGGER.info("{}: no {} to get {} from; exploring, look {} of {}",
            body.getGameProfile().getName(), what(), itemKey.id(), attempts, search.maxAttempts());
        phase = Phase.EXPLORE;
        stepTicks = 0;
        nav(body).explore(origin);
        return TickResult.RUNNING;
    }

    private TickResult explore(BotPlayer body) {
        stepTicks++;
        BotNavigator nav = nav(body);
        if (nav.state() == BotNavigator.State.FAILED) {
            // Only "explored and found none" says the source has nothing to give (BodyTask.exploreFailure); that ends
            // this step and never the search, for one tick is not a search and a target may appear a moment later.
            TickResult notTheSource = exploreFailure(body, nav, what());
            if (notTheSource != null) return notTheSource;
            if (stepTicks < MIN_STEP_TICKS) return TickResult.RUNNING;
        } else if (stepTicks < search.stepTicks() && nav.state() == BotNavigator.State.WORKING) {
            return TickResult.RUNNING;
        }
        // The step's own command ends with the step, whatever its state, so the look that follows is not made under it.
        stopOurCommand(nav);
        phase = Phase.FIND;
        return TickResult.RUNNING;
    }

    /**
     * The search is spent: {@code NoSource} carrying the leaf's item and source, saying how far it looked -- the
     * executor puts the source in the goal's exclusions, so the next plan gathers the item another way.
     */
    private TickResult giveUp(BotPlayer body) {
        release(body);
        String detail = String.format(Locale.ROOT, "found no %s to get %s from within %d blocks in %d attempts",
            what(), itemKey.id(), type != null ? (int) MOB_SEARCH_RADIUS : SCAN_RADIUS, attempts);
        if (zonedTargets > 0) {
            detail += "; " + zonedTargets + " of them stood inside a danger zone this body keeps out of";
        }
        MegaBlockBot.LOGGER.info("{}: gave up using a {} on {}: {}", body.getGameProfile().getName(),
            toolKey.id(), what(), detail);
        return TickResult.failed(new Failure.NoSource(itemKey, sourceId, detail));
    }

    // ---------------------------------------------------------------------------------------------------------
    // The use
    // ---------------------------------------------------------------------------------------------------------

    /**
     * One attempt on the target: the tick that comes here first turns the body to it and stops it there, and the tick
     * that follows makes the use. The game's own item use ray-casts along the body's real rotation, and a look written
     * to the input is only in that rotation from the next tick ({@code BodyInput.applyTo}).
     */
    private TickResult use(BotPlayer body) {
        if (MenuHand.carriedCount(body, tool) < 1) {
            return stop(body, new Failure.MissingInput(toolKey, 1, "the " + toolKey.id() + " was carried and is gone"));
        }
        if (uses >= USES_PER_TARGET) {
            // The target has been used the named number of times and not paid out: another is taken.
            setThisAside(body);
            return TickResult.RUNNING;
        }
        ServerLevel level = body.serverLevel();
        Vec3 aim;
        if (type != null) {
            if (quarry == null || !ready(quarry) || !inReach(body, quarry)) {
                phase = Phase.FIND; // gone, no longer ready, or moved away: after it (or another) again
                return TickResult.RUNNING;
            }
            aim = quarry.getEyePosition();
        } else {
            if (at == null || !isTarget(level, at)) {
                // Gone since it was chosen.
                at = null;
                phase = Phase.FIND;
                return TickResult.RUNNING;
            }
            if (!body.canInteractWithBlock(at, 0.0)) {
                // The target is still there and the body cannot use anything on it from where the navigator left it:
                // back to the stand, and after a named number of such returns for one target the cell is set aside,
                // as a cell whose uses gave nothing is -- otherwise the body walks to the same stand for ever.
                if (++returns > RETURNS_PER_TARGET) {
                    setThisAside(body);
                    return TickResult.RUNNING;
                }
                nav(body).goTo(stand, 0);
                phase = Phase.FIND;
                return TickResult.RUNNING;
            }
            aim = aimFor(body.getEyePosition(), at);
        }
        waiting = true; // the turn and the use are the game's timing: the body moves nothing and holds nothing new yet
        if (!holdTool(body)) {
            return stop(body, new Failure.MissingInput(toolKey, 1, "the " + toolKey.id()
                + " was carried and could not be taken into the hand"));
        }
        stand(body);
        if (++turnTicks <= TURN_TICKS) {
            body.input().lookAt(aim);
            body.lookAt(EntityAnchorArgument.Anchor.EYES, aim);
            return TickResult.RUNNING;
        }
        turnTicks = 0;
        return useOnce(body, level);
    }

    /** Makes the one use the leaf is about, and waits out its result: the tool is in the hand and the body is turned. */
    private TickResult useOnce(BotPlayer body, ServerLevel level) {
        uses++;
        heldAtUse = held(body);
        body.resetLastActionTime();
        MegaBlockBot.LOGGER.info("{}: uses the {} on {}", body.getGameProfile().getName(), toolKey.id(), where());
        if (type != null) {
            // The player's own use on an entity: the mob's interact first, then the held item's interactLivingEntity.
            body.interactOn(quarry, InteractionHand.MAIN_HAND);
        } else {
            // The item use a player's right click ends in: the game ray-casts from the eyes along the look.
            ((BodyInteractionManager) body.getInteractionManager())
                .interactItem(body, level, body.getMainHandItem(), InteractionHand.MAIN_HAND);
        }
        waitTicks = 0;
        phase = Phase.WAIT;
        return TickResult.RUNNING;
    }

    private TickResult waitForResult(BotPlayer body) {
        waiting = true;
        if (++waitTicks < RESULT_WAIT_TICKS) return TickResult.RUNNING;
        int now = held(body);
        MegaBlockBot.LOGGER.info("{}: the use on {} gave {}; the body holds {} of the {} it is after",
            body.getGameProfile().getName(), where(),
            now > heldAtUse ? (now - heldAtUse) + " " + itemKey.id() : "nothing", now, itemKey.id());
        // The drops lie where the use was made, not where the target wanders next: the centre is taken now.
        lootAt = type != null && quarry != null ? quarry.blockPosition().immutable() : at;
        // What the use dropped (wool lies beside the sheep) is picked up; with nothing lying, that ends at once.
        pickup.start();
        phase = Phase.LOOT;
        return loot(body);
    }

    private TickResult loot(BotPlayer body) {
        BlockPos around = lootAt;
        if (around != null && pickup.tick(this, body, drop -> drop.getItem().is(item), new AABB(around).inflate(LOOT_RADIUS))) {
            return TickResult.RUNNING;
        }
        pickup.rest();
        if (held(body) < targetCount && roomProblem(body)) {
            release(body);
            return TickResult.failed(new Failure.NoRoom(itemKey, "the inventory cannot take one more " + itemKey.id()
                + ": no free slot and no stack of it with room"));
        }
        if (held(body) > heldAtUse) uses = 0; // a use paid out: the target's count of tries starts again
        if (type != null) {
            if (quarry != null && ready(quarry) && inReach(body, quarry)) {
                phase = Phase.USE;
                return TickResult.RUNNING;
            }
            quarry = null;
            phase = Phase.FIND;
            return TickResult.RUNNING;
        }
        if (at != null && isTarget(body.serverLevel(), at)) {
            phase = Phase.USE;
            return TickResult.RUNNING;
        }
        at = null;
        stand = null;
        phase = Phase.FIND;
        return TickResult.RUNNING;
    }

    /**
     * The target this task was working is done with, whichever way it went. A mob is left for another of its kind; a
     * cell the tool did nothing to is remembered as one the body could not use the tool on, apart from the cells it
     * could not get at and from those the rules refuse.
     */
    private void setThisAside(BotPlayer body) {
        if (type != null) {
            if (quarry != null) {
                asideMobs.add(quarry.getUUID());
                MegaBlockBot.LOGGER.info("{}: the {} at {} gave nothing to {} uses of the {}; it is left for another",
                    body.getGameProfile().getName(), describe(quarry), quarry.blockPosition().toShortString(),
                    USES_PER_TARGET, toolKey.id());
            }
            quarry = null;
            chasing = null;
        } else {
            if (at != null) {
                unusable.add(at.immutable());
                MegaBlockBot.LOGGER.info("{}: the {} at {} gave nothing to {} uses of the {}; it is set aside",
                    body.getGameProfile().getName(), what(), at.toShortString(), USES_PER_TARGET, toolKey.id());
            }
            at = null;
            stand = null;
        }
        turnTicks = 0;
        phase = Phase.FIND;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Targets
    // ---------------------------------------------------------------------------------------------------------

    /** Whether the cell still holds what the leaf is about: a source of the fluid, or the block. */
    private boolean isTarget(ServerLevel level, BlockPos pos) {
        return isTarget(level, pos, level.getBlockState(pos));
    }

    private boolean isTarget(ServerLevel level, BlockPos pos, BlockState state) {
        if (fluid != null) {
            // A source cell is the still fluid; a flowing one scoops nothing. A waterlogged block holds a source too.
            return state.getFluidState().isSource() && state.getFluidState().getType() == fluid;
        }
        return state.is(block);
    }

    /** Whether the break rules let the body take this cell: scooping a fluid removes a block. */
    private static boolean mayTake(BreakFilter rules, ServerLevel level, BlockPos pos) {
        try {
            return rules.mayBreak(level, pos, level.getBlockState(pos));
        } catch (RuntimeException e) {
            return false; // a filter that throws is a no, as everywhere else (BreakFilter)
        }
    }

    /**
     * The cell nearest the body to use the tool on {@code target} from: standable (never a cell of the fluid itself nor
     * one whose floor would not hold the body, by {@link TableSites#standable}), and asked of the same ray-cast the use
     * itself will make ({@link #reaches}) -- the nearest stand is no use if the line from it answers a block other than
     * the target, which is how a source walled in by rim blocks reads from level with its surface. A target with no
     * stand the use reaches is taken by the nearest stand with nothing in the way of the look, so a target the body can
     * only partly see still gets its turn. Either way the stand is within {@link #USE_REACH} of the aim, measured as the
     * other is: a stand further off sees the target and cannot use anything on it.
     */
    @Nullable
    private BlockPos standFor(ServerLevel level, BlockPos center, BlockPos target) {
        BlockPos reached = null;
        double reachedDistance = Double.MAX_VALUE;
        BlockPos seen = null;
        double seenDistance = Double.MAX_VALUE;
        for (int dy = -STAND_SEARCH; dy <= STAND_SEARCH; dy++) {
            for (int dx = -STAND_SEARCH; dx <= STAND_SEARCH; dx++) {
                for (int dz = -STAND_SEARCH; dz <= STAND_SEARCH; dz++) {
                    BlockPos candidate = target.offset(dx, dy, dz);
                    if (!TableSites.standable(level, candidate)) continue;
                    Vec3 eyes = eyesAt(candidate);
                    Vec3 aim = aimFor(eyes, target);
                    double distance = candidate.distSqr(center);
                    // The stand the use is surest from is the one nearest its aim, not the one nearest the body: the
                    // line is steepest there and clears the rim of whatever the target sits in by the most.
                    double toAim = eyes.distanceTo(aim);
                    if (toAim <= USE_REACH && reaches(level, eyes, aim, target)) {
                        if (toAim >= reachedDistance) continue;
                        reachedDistance = toAim;
                        reached = candidate.immutable();
                    } else if (fluid == null && toAim <= USE_REACH && distance < seenDistance && eyesSee(level, eyes, aim)) {
                        seenDistance = distance;
                        seen = candidate.immutable();
                    }
                }
            }
        }
        return reached != null ? reached : seen;
    }

    /** Whether a use aimed at {@code aim} from {@code eyes} lands on {@code target}, asked of the game's own clip in
     * the shapes and fluid mode an item use reads it in: the outline of the blocks, and the sources for a fluid. */
    private boolean reaches(ServerLevel level, Vec3 eyes, Vec3 aim, BlockPos target) {
        BlockHitResult hit = level.clip(new ClipContext(eyes, aim, ClipContext.Block.OUTLINE,
            fluid != null ? ClipContext.Fluid.SOURCE_ONLY : ClipContext.Fluid.NONE, CollisionContext.empty()));
        return hit.getType() == HitResult.Type.BLOCK && target.equals(hit.getBlockPos());
    }

    /** Whether a body standing at {@code eyes} has a clear line to {@code aim}: nothing of any block in between. */
    private static boolean eyesSee(ServerLevel level, Vec3 eyes, Vec3 aim) {
        BlockHitResult hit = level.clip(new ClipContext(eyes, aim, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));
        return hit.getType() == HitResult.Type.MISS;
    }

    /** The eyes of a body standing in {@code stand}. */
    private static Vec3 eyesAt(BlockPos stand) {
        return new Vec3(stand.getX() + 0.5, stand.getY() + EYE_HEIGHT, stand.getZ() + 0.5);
    }

    /**
     * Where a use on a cell is aimed from {@code eyes}: inside the cell, a little below its top face, so the line ends
     * on the target rather than on the boundary between its cell and the ones above and beside it. A fluid cell is
     * entered as far from the body as the cell allows, since the source is behind the rim of the blocks it stands in.
     */
    private Vec3 aimFor(Vec3 eyes, BlockPos cell) {
        double x = cell.getX() + 0.5;
        double z = cell.getZ() + 0.5;
        if (fluid != null) {
            x += away(eyes.x, x);
            z += away(eyes.z, z);
        }
        return new Vec3(x, cell.getY() + 1.0 - AIM_BELOW_TOP, z);
    }

    /** How far to move an aim point away from where the body stands, along the axis it lies off on: nothing when the
     * two are level, so the point stays at the centre of that face of the cell. */
    private static double away(double from, double point) {
        double off = point - from;
        return off == 0.0 ? 0.0 : Math.copySign(Math.min(FLUID_AIM_ACROSS, Math.abs(off)), off);
    }

    /**
     * The nearest mob of the leaf's type that is ready to be used on, or null: one the kill task's rule lets a player
     * take from ({@link KillTask#takeable}), not one this task set aside, and ready -- a sheep that has its wool and is
     * of the variant's colour. Anything else (any adult, for milking) is ready by being an adult, which the shared
     * rule has already asked for. A mob that stands inside a live danger zone is not a candidate either (issue 29),
     * and it is not set aside: the zone lapses, and the mob with it becomes one again.
     */
    @Nullable
    private Mob findQuarry(BotPlayer body) {
        ServerLevel level = body.serverLevel();
        Vec3 at = body.position();
        AABB box = new AABB(at, at).inflate(MOB_SEARCH_RADIUS);
        Mob nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        int zoned = 0;
        for (Mob mob : level.getEntitiesOfClass(Mob.class, box, candidate -> candidate.getType() == type)) {
            if (!ready(mob)) continue;
            if (inDangerZone(body, mob.blockPosition())) {
                zoned++;
                continue;
            }
            double distance = mob.distanceToSqr(body);
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = mob;
            }
        }
        zonedTargets = zoned;
        return nearest;
    }

    private boolean ready(Mob mob) {
        if (!KillTask.takeable(mob) || asideMobs.contains(mob.getUUID())) return false;
        if (mob instanceof Sheep sheep) {
            if (!sheep.readyForShearing()) return false;
            return variant == null || sheep.getColor().getName().equals(variant);
        }
        return true;
    }

    private static boolean inReach(BotPlayer body, Mob mob) {
        return body.canInteractWithEntity(mob, 0.0);
    }

    // ---------------------------------------------------------------------------------------------------------
    // The body's hands
    // ---------------------------------------------------------------------------------------------------------

    /**
     * Takes the tool into the main hand as a player would: it is selected when it is on the hotbar, otherwise the
     * inventory menu swaps it into the selected hotbar slot (as {@code FishTask} takes its rod in hand).
     */
    private boolean holdTool(BotPlayer body) {
        if (body.getMainHandItem().is(tool)) return true;
        var inventory = body.getInventory();
        for (int slot = 0; slot < 9; slot++) {
            if (inventory.getItem(slot).is(tool)) {
                inventory.selected = slot;
                return true;
            }
        }
        if (body.containerMenu != body.inventoryMenu) body.closeContainer();
        MenuHand hand = new MenuHand(body, body.inventoryMenu);
        int from = hand.findCarried(tool);
        if (from < 0) return false;
        hand.click(from, inventory.selected, ClickType.SWAP);
        return inventory.getItem(inventory.selected).is(tool);
    }

    private static void stand(BotPlayer body) {
        body.input().forward = 0.0F;
        body.input().strafe = 0.0F;
        body.input().jump = false;
        body.input().sprint = false;
    }

    /**
     * Walks straight at a point while looking at it: the body's own steps, not the navigator's, and walking (not
     * running as the kill task closes on a quarry: nothing here is hurt and fleeing).
     */
    private static void walkAt(BotPlayer body, Vec3 at) {
        body.input().lookAt(at);
        body.lookAt(EntityAnchorArgument.Anchor.EYES, at);
        body.input().forward = 1.0F;
        body.input().strafe = 0.0F;
        body.input().sprint = false;
        body.input().jump = body.horizontalCollision && body.onGround();
    }

    /** Stops the command this task gave, and nobody else's: the reflex layer's is not this task's to stop. */
    private void stopOurCommand(BotNavigator nav) {
        if (!nav.runsCommandOfAnother(this)) nav.stop();
        serial = -1;
    }

    private static String describe(@Nullable Mob mob) {
        if (mob != null) return mob.getType().getDescription().getString().toLowerCase(Locale.ROOT);
        return "mob";
    }

    @Nullable
    private static Item itemOf(ItemKey key) {
        ResourceLocation id = idOf(key.id());
        return id == null ? null : BuiltInRegistries.ITEM.getOptional(id).orElse(null);
    }

    @Nullable
    private static Block blockOf(String label) {
        ResourceLocation id = idOf(label);
        return id == null ? null : BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
    }

    @Nullable
    private static Fluid fluidOf(String label) {
        ResourceLocation id = idOf(label);
        return id == null ? null : BuiltInRegistries.FLUID.getOptional(id).orElse(null);
    }

    @Nullable
    private static EntityType<?> entityOf(String label) {
        ResourceLocation id = idOf(label);
        return id == null ? null : BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null);
    }

    /** The target as the status line and the failures name it: "lava", "a sheep". */
    private String what() {
        String name = targetLabel.replace("minecraft:", "").replace('_', ' ');
        return type != null ? "a " + name : name;
    }

    /** The target a use is made on and where it stands, for the lines that name one use: "lava at (8, 2, 8)". */
    private String where() {
        BlockPos place = type != null ? (quarry == null ? null : quarry.blockPosition()) : at;
        return what() + (place == null ? "" : " at " + place.toShortString());
    }

    // ---------------------------------------------------------------------------------------------------------

    private TickResult stop(BotPlayer body, Failure failure) {
        release(body);
        return TickResult.failed(failure);
    }

    @Override
    public boolean waiting() {
        // A use's result and a pick-up are the game's timing: position and inventory show nothing while they go on,
        // and the stuck watchdog would cut the task short.
        return waiting || pickup.waiting();
    }

    @Override
    protected void letGo(BotPlayer body) {
        waiting = false;
        pickup.rest();
        body.input().reset();
    }

    /**
     * The dimension, how many of its attempts the search has spent, and the cells it could not get at, so a restart
     * does not look from scratch. Hints only; a restarted task checks them against the world. A mob moves, so no mob
     * is named.
     */
    @Override
    public Map<String, String> checkpoint() {
        Map<String, String> out = new LinkedHashMap<>();
        if (origin == null) return out;
        out.put("dim", dimension);
        out.put("attempts", Integer.toString(attempts));
        if (!setAside.isEmpty()) {
            StringBuilder aside = new StringBuilder();
            setAside.stream().sorted(Comparator.comparingDouble(origin::distSqr)).limit(MAX_SAVED_ASIDE).forEach(pos -> {
                if (aside.length() > 0) aside.append(';');
                aside.append(corePos(pos).encode());
            });
            out.put("aside", aside.toString());
        }
        return out;
    }

    /** What the task is doing now, for the status line: what it is after, and how many of the item it holds. */
    @Override
    public String progress() {
        String tool = toolKey.id().replace("minecraft:", "");
        String item = itemKey.id().replace("minecraft:", "");
        String plain = plain(targetLabel);
        String what = switch (phase) {
            case FIND -> {
                if (type != null) {
                    yield quarry != null ? "walking to a " + describe(quarry) : "looking for a " + plain;
                }
                if (at != null) {
                    yield "walking to the " + plain + " at " + at.getX() + " " + at.getY() + " " + at.getZ();
                }
                yield "looking for " + plain + " to use the " + tool + " on";
            }
            case EXPLORE -> "exploring for " + what();
            case USE, WAIT -> "using the " + tool + " on " + what();
            case LOOT -> "picking up the " + item + " that dropped";
        };
        return what + "; holds " + lastHeld + " of " + targetCount + " " + item;
    }

    /** A registry id as the status line says it: "lava", "powder snow". */
    private static String plain(String id) {
        return id.replace("minecraft:", "").replace('_', ' ');
    }
}
