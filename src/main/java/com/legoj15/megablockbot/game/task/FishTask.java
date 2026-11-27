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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;

/**
 * {@code fish}: cast a fishing rod into water until the body holds the goal's count of the item (design 4.3, Fishing).
 * Everything goes through the game the way a player's cast does: the rod in the main hand, a look at the water's
 * surface, the item use that the packet handler ends in, and the hook the game spawns
 * ({@code net.minecraft.world.entity.projectile.FishingHook}, the player's {@code fishing} field).
 *
 * <p>Parameters from the planner: {@code item} and {@code count} (how many to bring in, used only when the goal is not
 * a {@link Goal.HaveItem} of the item: the goal's count is absolute and wins) and {@code table}, the loot table the
 * source was read from (named for the record; the game's own table decides the catch). The plan puts a rod in the
 * inventory first
 * ({@code SolverPlanner} plans it as it plans a mining source's tool); this task fails {@code MissingInput} naming the
 * rod when none is carried, because the rod is not a tool of any class the failure table knows.
 *
 * <p><b>Water.</b> The task needs a standable block with a water source 3 to 6 blocks away horizontally, at or below
 * the body's feet and with open air above it (a bobber under a roof waits longer). Standing in the water is not wanted:
 * the body stands on its bank. The water is looked for in the chunks already loaded within
 * {@link Search#scanRadius()} of the body; when there is none the body walks on, exploring as {@code MineTask} does when
 * it knows no target, for a bounded number of rounds, and past that the failure is {@code NoSource} carrying the leaf's
 * item and source id, so the executor excludes fishing and the next plan takes another source.
 *
 * <p><b>Casting and the bite.</b> The rod is taken into the main hand ({@link MenuHand}, as the tasks that hold a tool
 * do), the body looks at a point on the water's surface for two ticks (a look written this tick is only in the body's
 * rotation from the next, and the hook is aimed between the rotation and its predecessor), and the rod is used. A cast
 * whose hook is gone, or comes to rest out of the water, within {@link #LAND_WAIT_TICKS} ticks is reeled in and made
 * again at another water cell; {@link #BAD_CASTS_AT_A_STAND} bad casts at one stand move the body to another stand.
 *
 * <p>The bite itself is not public on the hook (1.21.1: the {@code biting} flag and the {@code nibble} countdown are
 * private, and {@code retrieve} awards the loot it is asked about, so it cannot be probed). What a player sees is the
 * float dip, and the game makes the dip: while a fish bites, the hook's bobbing state adds a downward impulse of up to
 * 0.1 blocks per tick to it each tick, which the bobbing of a settled float never reaches. So the task watches the hook
 * it cast, and once the hook has been in water long enough for its splash to settle, a vertical speed below
 * {@link #BITE_DY} is the bite: the rod is used again at once, which reels in, and the catch flies to the body.
 *
 * <p><b>The catch.</b> Whatever is caught is picked up like any item; everything caught is kept. Fishing is random, so
 * the task is {@link #waiting() waiting} while a hook is in the water (the executor's stuck watchdog leaves it alone)
 * and its tick budget is what ends a run of bad luck. A full inventory ends the step {@code NoRoom} as the mine task's
 * does. On pause, abort, failure and completion the hook is reeled in and the input is reset; a resumed task looks
 * again from the top.
 */
public final class FishTask extends BodyTask {
    public static final String KIND = "fish";

    /**
     * How hard the task looks for water when none is in sight. A step is one stretch of exploring followed by another
     * look at the chunks around the body.
     *
     * @param scanRadius        how far around the body, in blocks, water is looked for in the chunks already loaded
     * @param stepBlocks        horizontal distance after which an exploring step ends
     * @param stepTicks         ticks after which an exploring step ends whatever the body covered
     * @param maxExploredBlocks horizontal distance explored over all steps before the search gives up
     * @param maxAttempts       looks (and exploring steps) before the search gives up
     */
    public record Search(int scanRadius, int stepBlocks, int stepTicks, int maxExploredBlocks, int maxAttempts) {
        /** Ten seconds per block of exploring, 256 blocks in all, twelve rounds: as far as a mining search reaches. */
        public static final Search DEFAULT = new Search(WATER_SCAN_RADIUS, 32, 600, 256, 12);
    }

    /** How far around the body water is looked for, in the chunks that are loaded already. */
    private static final int WATER_SCAN_RADIUS = 32;
    /** How far below its feet, and above them, the water scan reads. */
    private static final int WATER_SCAN_DEPTH = 8;
    private static final int WATER_SCAN_RISE = 2;
    /** The nearest and farthest water a stand will do: nearer than 3 and the body would be fishing at its own feet. */
    private static final int WATER_MIN = 3;
    private static final int WATER_MAX = 6;
    /** How far above the water a stand may be: the bank, not a cliff. */
    private static final int STAND_ABOVE_WATER = 3;
    /**
     * Ticks a cast may take to come to rest before the hook is judged to have landed somewhere it cannot fish: a hook
     * flies for a second or two and settles well inside three.
     */
    private static final int LAND_WAIT_TICKS = 60;
    /**
     * Ticks the hook must be in water before its speed is read as a bite: the splash of the hook dropping in keeps its
     * vertical speed above the bite threshold for a moment, and the drag of the bobbing state brings it to rest within
     * a second and a half.
     */
    private static final int SETTLE_TICKS = 30;
    /**
     * Vertical speed, in blocks per tick, below which the hook is biting: while a fish bites the game pulls the hook
     * down with an impulse of up to 0.1 each tick, and a settled float bobs at a small fraction of that. The bite lasts
     * twenty to forty ticks, so a threshold this low is seen within a few of them.
     */
    private static final double BITE_DY = -0.04;
    /**
     * Ticks one cast may wait for a bite before the line is taken up and cast again: the game waits 5 to 30 seconds
     * (100 to 600 ticks; rain shortens it, a covered sky lengthens it), so ninety seconds covers the worst roll.
     */
    private static final int MAX_CAST_WAIT_TICKS = 1800;
    /** Bad casts at one stand before the body moves to another stand. */
    private static final int BAD_CASTS_AT_A_STAND = 3;
    /** Ticks the look at the water is held before the rod is used: see the class comment on casting. */
    private static final int AIM_TICKS = 2;
    /** Times the game may refuse the rod's use outright before the cast is counted bad and another cell tried. */
    private static final int CAST_REFUSALS = 3;
    /** Ticks the catch of one reel may take to reach the inventory: it flies to the body in a second or two. */
    private static final int LANDING_WAIT_TICKS = 100;

    /** The item every fishing source needs; the planner plans it first and it is never spent. */
    private static final ItemKey ROD = ItemKey.of("minecraft:fishing_rod");

    /** One place to fish from: where to stand on the bank, and the water the hook can be cast into from there. */
    private record Spot(BlockPos stand, List<BlockPos> water) {}

    private final Search search;
    private final String sourceId;
    private final ItemKey itemKey;
    private final int goalCount;
    private final int moreCount;

    // From an earlier task for the same node: hints for where to look, checked against the world before use.
    private final String hintDimension;
    private final int hintExplored;
    @Nullable
    private final Pos hintStand;

    private Item item;
    private String dimension = "";
    private int target;
    private boolean alreadyDone;
    private Phase phase = Phase.FIND;
    private BlockPos origin;
    private int attempts;
    private double exploredBlocks;
    private Vec3 stepFrom = Vec3.ZERO;
    private int stepTicks;
    /** Stands that did not work out (the navigator could not get there, or every cast at them was bad). */
    private final Set<BlockPos> triedStands = new HashSet<>();
    @Nullable
    private Spot spot;
    /** Which water cell of the stand's the hook was last cast at. */
    private int waterAt;
    private int badCasts;
    private int castRefusals;
    private int aimTicks;
    private int castTicks;
    private int waterTicks;
    private int landTicks;
    /** How many of the item the body held when the task last looked: what the status line says. */
    private int heldNow;
    private boolean looking;

    private enum Phase { FIND, EXPLORE, TO_STAND, AIM, CAST, WAITING, LANDING }

    public FishTask(NodeSpec leaf, Map<String, String> hints) {
        this(leaf, hints, Search.DEFAULT);
    }

    public FishTask(NodeSpec leaf, Map<String, String> hints, Search search) {
        this.search = search;
        this.sourceId = leaf.source();
        this.itemKey = ItemKey.of(required(leaf, "item"));
        this.goalCount = leaf.goal() instanceof Goal.HaveItem g && g.key().equals(itemKey) ? g.count() : -1;
        this.moreCount = intParam(leaf.params(), "count", 1);

        this.hintDimension = hints.getOrDefault("dim", "");
        this.hintExplored = intHint(hints.get("explored"));
        this.hintStand = hintPos(hints.get("stand"));
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
        heldNow = held(body);
        target = goalCount > 0 ? goalCount : heldNow + Math.max(1, moreCount);
        if (heldNow >= target) {
            // Nothing to do, and nothing is touched: the first tick says so.
            alreadyDone = true;
            return;
        }
        if (MenuHand.carriedCount(body, Items.FISHING_ROD) == 0) {
            fail(new Failure.MissingInput(ROD, 1, "fishing for " + itemKey.id() + " needs a " + ROD.id()
                + " and the body carries none"));
            return;
        }
        if (MenuHand.roomFor(body, new ItemStack(item)) == 0) {
            // No cast is made for a catch that could not be picked up: carried storage has no free slot and no stack of
            // the item with room, and a replan would plan the same step into the same full inventory.
            fail(new Failure.NoRoom(itemKey, "the inventory cannot take one more " + itemKey.id()
                + ": no free slot and no stack of it with room"));
            return;
        }
        origin = body.blockPosition();
        if (hintDimension.equals(dimension)) {
            exploredBlocks = Math.min(hintExplored, search.maxExploredBlocks());
            // A stand an earlier task for this node fished from is tried first; the world decides whether it still has
            // water beside it.
            if (hintStand != null) {
                List<BlockPos> water = waterFor(body.serverLevel(), blockPos(hintStand));
                if (!water.isEmpty()) {
                    spot = new Spot(blockPos(hintStand), water);
                    go(body, spot.stand());
                    return;
                }
            }
        }
    }

    @Override
    protected void resumeWork(BotPlayer body) {
        // Everything is looked at again: how much the body holds now, where the water is, which way it faces.
        spot = null;
        waterAt = 0;
        badCasts = 0;
        castRefusals = 0;
        phase = Phase.FIND;
    }

    private int held(BotPlayer body) {
        return item == null ? 0 : body.getInventory().countItem(item);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Tick
    // ---------------------------------------------------------------------------------------------------------

    @Override
    protected TickResult onTick(BotPlayer body) {
        heldNow = held(body);
        if (alreadyDone) return TickResult.COMPLETED;
        // The goal first, whatever got the item into the inventory (a catch of this task, a drop picked up on the way).
        if (heldNow >= target) return complete(body);
        return switch (phase) {
            case FIND -> find(body);
            case EXPLORE -> exploring(body);
            case TO_STAND -> toStand(body);
            case AIM -> aim(body);
            case CAST -> cast(body);
            case WAITING -> waitForBite(body);
            case LANDING -> landing(body);
        };
    }

    /** Looks for water around the body; none in sight, walks on and looks again from wherever it arrives. */
    private TickResult find(BotPlayer body) {
        Spot found = findSpot(body);
        if (found == null) {
            if (++attempts > search.maxAttempts() || exploredBlocks >= search.maxExploredBlocks()) return giveUp(body);
            phase = Phase.EXPLORE;
            stepFrom = body.position();
            stepTicks = 0;
            nav(body).explore(origin);
            return TickResult.RUNNING;
        }
        spot = found;
        waterAt = 0;
        go(body, found.stand());
        return TickResult.RUNNING;
    }

    private TickResult exploring(BotPlayer body) {
        BotNavigator nav = nav(body);
        stepTicks++;
        double moved = horizontal(body.position(), stepFrom);
        if (nav.state() == BotNavigator.State.FAILED) {
            // Only "explored and found none" says there is no water to fish in (BodyTask.exploreFailure).
            TickResult notTheSource = exploreFailure(body, nav, "water to fish in");
            if (notTheSource != null) return notTheSource;
            exploredBlocks += moved;
            return giveUp(body);
        }
        if (nav.state() == BotNavigator.State.WORKING && moved < search.stepBlocks() && stepTicks < search.stepTicks()) {
            return TickResult.RUNNING;
        }
        exploredBlocks += moved;
        phase = Phase.FIND; // look again from where the body now is
        return TickResult.RUNNING;
    }

    private void go(BotPlayer body, BlockPos stand) {
        phase = Phase.TO_STAND;
        nav(body).goTo(stand, 0);
    }

    private TickResult toStand(BotPlayer body) {
        switch (nav(body).state()) {
            case DONE -> {
                // Arrived: the rod comes out.
                badCasts = 0;
                castRefusals = 0;
                waterAt = 0;
                aimTicks = 0;
                phase = Phase.AIM;
            }
            case FAILED -> {
                // The stand cannot be got at; it is not offered again.
                triedStands.add(spot.stand().immutable());
                phase = Phase.FIND;
            }
            case IDLE -> go(body, spot.stand()); // something else stopped the navigator: this task still owns the body
            default -> { }
        }
        return TickResult.RUNNING;
    }

    /** Holds the rod and looks at the water's surface; the look needs a tick of its own before the cast is aimed by it. */
    private TickResult aim(BotPlayer body) {
        if (!holdRod(body)) {
            return stop(body, new Failure.MissingInput(ROD, 1, "the " + ROD.id() + " was carried and is gone (it broke)"));
        }
        BlockPos cell = spot.water().get(waterAt);
        // A hook does not fly straight: aimed at the water it comes down short of it (a block and a half short at six
        // blocks), so the look is raised to the pitch whose flight ends on the cell.
        Vec3 eyes = body.getEyePosition();
        double cx = cell.getX() + 0.5;
        double cz = cell.getZ() + 0.5;
        double distance = Math.hypot(cx - eyes.x, cz - eyes.z);
        double pitch = castPitch(distance, eyes.y - (cell.getY() + WATER_SURFACE));
        body.input().lookAt(new Vec3(cx, eyes.y - Math.tan(pitch) * distance, cz));
        looking = true;
        if (++aimTicks < AIM_TICKS) return TickResult.RUNNING;
        phase = Phase.CAST;
        return TickResult.RUNNING;
    }

    /** How high in its block a water source's surface lies. */
    private static final double WATER_SURFACE = 0.9;

    /**
     * How far from the body, in blocks, a hook cast at this pitch (radians, positive is down) meets a surface that
     * lies {@code drop} blocks below the eyes. The game's own flight ({@code FishingHook}): it starts 0.3 in front of
     * the eyes with a speed of 0.6 times the look's cosine plus 0.5 along the ground and that times the pitch's tangent
     * downward, and every tick falls 0.03 faster, moves, and keeps 0.92 of its speed.
     */
    static double castRange(double pitch, double drop) {
        double speed = 0.6 * Math.cos(pitch) + 0.5;
        double along = speed;
        double down = Math.tan(pitch) * speed;
        double range = 0.3;
        double fallen = 0.0;
        for (int tick = 0; tick < 80; tick++) {
            down += 0.03;
            if (down > 0.0 && fallen + down >= drop) return range + along * (drop - fallen) / down;
            range += along;
            fallen += down;
            along *= 0.92;
            down *= 0.92;
        }
        return range;
    }

    /**
     * The pitch (radians, positive is down) at which a cast comes down {@code distance} blocks away on a surface
     * {@code drop} below the eyes, closed in on from both sides. That takes a range that falls as the look goes down,
     * which holds for the cells the task casts at (three to six blocks out, at pitches from a little above level
     * downward) and not for the whole bracket: with a long drop the capped flight makes a level look outrange a raised
     * one. A distance past the farthest cast gives the farthest cast, which {@link #flightClear} then turns down.
     */
    static double castPitch(double distance, double drop) {
        double far = Math.toRadians(-20.0);
        double near = Math.toRadians(80.0);
        for (int step = 0; step < 24; step++) {
            double middle = (far + near) / 2.0;
            if (castRange(middle, drop) > distance) far = middle;
            else near = middle;
        }
        return (far + near) / 2.0;
    }

    /** Uses the rod: the game casts the hook (the player's {@code fishing} field points at it). */
    private TickResult cast(BotPlayer body) {
        if (!holdRod(body)) {
            return stop(body, new Failure.MissingInput(ROD, 1, "the " + ROD.id() + " was carried and is gone (it broke)"));
        }
        FishingHook out = body.fishing;
        if (out != null) {
            if (out.isAlive()) return badCast(body, "a hook was already out");
            // The last hook's removal has not reached the player's line yet: casting now would only take it up again.
            if (++castRefusals > CAST_REFUSALS) return badCast(body, "the player's line stayed out after the reel");
            return TickResult.RUNNING;
        }
        useRod(body);
        if (hookOf(body) == null) {
            // Refused (a cooldown, an event): tried again at once a few times, then as a bad cast at another cell.
            if (++castRefusals > CAST_REFUSALS) return badCast(body, "the game would not cast");
            return TickResult.RUNNING;
        }
        castTicks = 0;
        waterTicks = 0;
        phase = Phase.WAITING;
        return TickResult.RUNNING;
    }

    private TickResult waitForBite(BotPlayer body) {
        FishingHook hook = hookOf(body);
        castTicks++;
        if (hook == null) return badCast(body, "the hook was gone before it fished");
        if (hook.getHookedIn() != null) return badCast(body, "the hook caught " + hook.getHookedIn().getName().getString());
        if (!afloat(hook)) {
            if (castTicks >= LAND_WAIT_TICKS) return badCast(body, "the hook came to rest out of the water");
            return TickResult.RUNNING;
        }
        waterTicks++;
        if (waterTicks > SETTLE_TICKS && hook.getDeltaMovement().y < BITE_DY) {
            // The bite: reel in at once, within the bite's own window (the game gives the catch only then).
            useRod(body);
            badCasts = 0;
            landTicks = 0;
            phase = Phase.LANDING;
            return TickResult.RUNNING;
        }
        if (castTicks >= MAX_CAST_WAIT_TICKS) return badCast(body, "no bite in " + MAX_CAST_WAIT_TICKS + " ticks");
        return TickResult.RUNNING;
    }

    /**
     * Whether the hook rides on water. Asked of the water under it and not of the entity's own in-water flag: a
     * floating bobber rises and falls across the surface, and the flag is off on the ticks it rides high, which read
     * as a hook lying on land. A hook resting on something (a lily pad over the pool) has water under it too and is
     * not afloat: the game gives no bite there.
     */
    private static boolean afloat(FishingHook hook) {
        if (hook.isInWater()) return true;
        BlockPos under = BlockPos.containing(hook.getX(), hook.getY() - 0.2, hook.getZ());
        return !hook.onGround() && hook.level().getFluidState(under).is(FluidTags.WATER);
    }

    private TickResult landing(BotPlayer body) {
        landTicks++;
        if (held(body) >= target) return complete(body);
        if (MenuHand.roomFor(body, new ItemStack(item)) == 0) {
            return stop(body, new Failure.NoRoom(itemKey, "the inventory cannot take one more " + itemKey.id()
                + ": no free slot and no stack of it with room"));
        }
        if (landTicks < LANDING_WAIT_TICKS) return TickResult.RUNNING;
        // Whatever that catch was, it is not the item (junk, or another fish): the line goes back in.
        aimTicks = 0;
        phase = Phase.AIM;
        return TickResult.RUNNING;
    }

    /** A cast that will not fish: the line is taken up and made again, at another cell and then at another stand. */
    private TickResult badCast(BotPlayer body, String why) {
        reel(body);
        MegaBlockBot.LOGGER.info("{}: {} (cast {} of {} at {})", body.getGameProfile().getName(), why, badCasts + 1,
            BAD_CASTS_AT_A_STAND, spot.stand().toShortString());
        badCasts++;
        if (badCasts < BAD_CASTS_AT_A_STAND) {
            waterAt = (waterAt + 1) % spot.water().size();
            aimTicks = 0;
            phase = Phase.AIM;
            return TickResult.RUNNING;
        }
        triedStands.add(spot.stand().immutable());
        return find(body);
    }

    /**
     * The search is spent: {@code NoSource} carrying the leaf's item and source, saying how far it looked -- the
     * executor puts the source in the goal's exclusions, so the next plan gathers the item another way.
     */
    private TickResult giveUp(BotPlayer body) {
        release(body);
        String detail = String.format(Locale.ROOT,
            "found no water to fish %s from: looked about %d blocks around %s (explored %d blocks in %d attempts)",
            itemKey.id(), search.scanRadius(), origin.toShortString(), (int) exploredBlocks, attempts);
        MegaBlockBot.LOGGER.info("{}: gave up fishing for {}: {}", body.getGameProfile().getName(), itemKey.id(), detail);
        return TickResult.failed(new Failure.NoSource(itemKey, sourceId, detail));
    }

    // ---------------------------------------------------------------------------------------------------------
    // The rod and the hook
    // ---------------------------------------------------------------------------------------------------------

    /**
     * Takes the rod into the main hand as a player would: it is selected when it is on the hotbar, otherwise the
     * inventory menu swaps it into the selected hotbar slot (the way {@code CraftTask} takes its table in hand).
     */
    private static boolean holdRod(BotPlayer body) {
        if (body.getMainHandItem().is(Items.FISHING_ROD)) return true;
        var inventory = body.getInventory();
        for (int slot = 0; slot < 9; slot++) {
            if (inventory.getItem(slot).is(Items.FISHING_ROD)) {
                inventory.selected = slot;
                return true;
            }
        }
        if (body.containerMenu != body.inventoryMenu) body.closeContainer();
        MenuHand hand = new MenuHand(body, body.inventoryMenu);
        int from = hand.findCarried(Items.FISHING_ROD);
        if (from < 0) return false;
        hand.click(from, inventory.selected, ClickType.SWAP);
        return inventory.getItem(inventory.selected).is(Items.FISHING_ROD);
    }

    /** The item use a player's right click ends in: it casts when no hook is out and reels in when one is. */
    private static void useRod(BotPlayer body) {
        body.resetLastActionTime();
        body.gameMode.useItem(body, body.serverLevel(), body.getMainHandItem(), InteractionHand.MAIN_HAND);
    }

    /** The hook this body cast and still has out, or null when there is none (the player's {@code fishing} field). */
    @Nullable
    private static FishingHook hookOf(BotPlayer body) {
        FishingHook hook = body.fishing;
        return hook != null && hook.isAlive() ? hook : null;
    }

    /** Takes the line up when a hook is out, with the rod in hand as a player reels in. */
    private void reel(BotPlayer body) {
        if (hookOf(body) == null || !holdRod(body)) return;
        useRod(body);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Water and stands
    // ---------------------------------------------------------------------------------------------------------

    /**
     * The nearest place the body can fish from: a standable block on the bank of the nearest water the chunks around
     * the body hold, with the water cells that stand can cast into. Loaded chunks only: a read never loads one.
     */
    @Nullable
    private Spot findSpot(BotPlayer body) {
        ServerLevel level = body.serverLevel();
        BlockPos centre = body.blockPosition();
        List<BlockPos> water = waterSources(level, centre, search.scanRadius(),
            centre.getY() - WATER_SCAN_DEPTH, Math.min(level.getMaxBuildHeight() - 2, centre.getY() + WATER_SCAN_RISE));
        for (BlockPos cell : water) {
            BlockPos stand = standFor(level, centre, cell);
            if (stand == null) continue;
            return new Spot(stand, waterFor(level, stand));
        }
        return null;
    }

    /**
     * The water sources with air above them in the chunks already loaded within {@code radius} of {@code centre},
     * between {@code lowY} and {@code highY} inclusive, nearest to {@code centre} first.
     */
    private List<BlockPos> waterSources(ServerLevel level, BlockPos centre, int radius, int lowY, int highY) {
        List<BlockPos> found = new ArrayList<>();
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int chunkX = (centre.getX() - radius) >> 4; chunkX <= (centre.getX() + radius) >> 4; chunkX++) {
            for (int chunkZ = (centre.getZ() - radius) >> 4; chunkZ <= (centre.getZ() + radius) >> 4; chunkZ++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
                if (chunk == null) continue;
                int fromX = Math.max(chunkX << 4, centre.getX() - radius);
                int toX = Math.min((chunkX << 4) + 15, centre.getX() + radius);
                int fromZ = Math.max(chunkZ << 4, centre.getZ() - radius);
                int toZ = Math.min((chunkZ << 4) + 15, centre.getZ() + radius);
                for (int x = fromX; x <= toX; x++) {
                    for (int z = fromZ; z <= toZ; z++) {
                        for (int y = lowY; y <= highY; y++) {
                            at.set(x, y, z);
                            BlockState state = chunk.getBlockState(at);
                            if (!state.getFluidState().is(FluidTags.WATER) || !state.getFluidState().isSource()) continue;
                            // Open air above the water: the hook has to come down on it, and a covered bobber waits longer.
                            at.setY(y + 1);
                            if (!chunk.getBlockState(at).isAir()) continue;
                            at.setY(y);
                            found.add(at.immutable());
                        }
                    }
                }
            }
        }
        found.sort(Comparator.comparingDouble(cell -> cell.distSqr(centre)));
        return found;
    }

    /** The water a body standing at {@code stand} can cast into: 3 to 6 blocks away, at or below its feet. */
    private List<BlockPos> waterFor(ServerLevel level, BlockPos stand) {
        int lowY = Math.max(level.getMinBuildHeight(), stand.getY() - WATER_SCAN_DEPTH - WATER_MAX);
        List<BlockPos> cells = waterSources(level, stand, WATER_MAX, lowY, stand.getY());
        cells.removeIf(cell -> !castable(stand, cell.getX(), cell.getY(), cell.getZ()) || !flightClear(level, stand, cell));
        return cells;
    }

    /** The stand nearest the body that can fish the water at {@code water}: on its bank, 3 to 6 blocks from it. */
    @Nullable
    private BlockPos standFor(ServerLevel level, BlockPos centre, BlockPos water) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dy = 0; dy <= STAND_ABOVE_WATER; dy++) {
            for (int dx = -WATER_MAX; dx <= WATER_MAX; dx++) {
                for (int dz = -WATER_MAX; dz <= WATER_MAX; dz++) {
                    if (!withinCastRange(water.getX(), water.getZ(), water.getX() + dx, water.getZ() + dz)) continue;
                    BlockPos stand = water.offset(dx, dy, dz);
                    if (triedStands.contains(stand) || !TableSites.standable(level, stand)) continue;
                    // A bank the cast cannot clear is no stand for this water, however near it is.
                    if (!flightClear(level, stand, water)) continue;
                    double distance = stand.distSqr(centre);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = stand.immutable();
                    }
                }
            }
        }
        return best;
    }

    /** How high above its feet a standing body's eyes are: where a cast starts. */
    private static final double EYE_HEIGHT = 1.62;

    /**
     * Whether a cast from {@code stand} at the water cell comes down in it. The hook's flight is followed, a quarter of
     * a tick at a time: a block with a shape in its way ends it short of the water (the rim of a pool sunk below its
     * bank is the usual one: a hook aimed at the first cell past the rim strikes the rim's side), and so does a cell
     * past the farthest cast.
     */
    private static boolean flightClear(ServerLevel level, BlockPos stand, BlockPos cell) {
        double ex = stand.getX() + 0.5;
        double ey = stand.getY() + EYE_HEIGHT;
        double ez = stand.getZ() + 0.5;
        double distance = Math.hypot(cell.getX() + 0.5 - ex, cell.getZ() + 0.5 - ez);
        if (distance < 1.0) return false;
        double drop = ey - (cell.getY() + WATER_SURFACE);
        double pitch = castPitch(distance, drop);
        double ux = (cell.getX() + 0.5 - ex) / distance;
        double uz = (cell.getZ() + 0.5 - ez) / distance;
        double speed = 0.6 * Math.cos(pitch) + 0.5;
        double along = speed;
        double down = Math.tan(pitch) * speed;
        double range = 0.3;
        double fallen = 0.0;
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int tick = 0; tick < 80; tick++) {
            down += 0.03;
            for (int part = 1; part <= 4; part++) {
                double r = range + along * part / 4.0;
                double f = fallen + down * part / 4.0;
                int x = (int) Math.floor(ex + ux * r);
                int y = (int) Math.floor(ey - f);
                int z = (int) Math.floor(ez + uz * r);
                if (x == cell.getX() && z == cell.getZ() && y <= cell.getY() + 1) return true; // over the cell, down to it
                if (f > drop + 1.0) return false; // came down somewhere else
                at.set(x, y, z);
                BlockState state = level.getBlockState(at);
                if (!state.getCollisionShape(level, at).isEmpty()) return false;
                // Water met before the cell is water the hook stops in: fine when it is the cell's own level.
                if (state.getFluidState().is(FluidTags.WATER)) return y == cell.getY();
            }
            range += along;
            fallen += down;
            along *= 0.92;
            down *= 0.92;
        }
        return false;
    }

    /** Whether two cells are 3 to 6 blocks apart horizontally: the reach of a cast. */
    private static boolean withinCastRange(int ax, int az, int bx, int bz) {
        int ring = Math.max(Math.abs(ax - bx), Math.abs(az - bz));
        return ring >= WATER_MIN && ring <= WATER_MAX;
    }

    /** Whether a body standing at {@code stand} can cast into the water cell at {@code x, y, z}. */
    private static boolean castable(BlockPos stand, int x, int y, int z) {
        // At or below the body's feet: nobody fishes uphill.
        return withinCastRange(stand.getX(), stand.getZ(), x, z) && y <= stand.getY();
    }

    private static double horizontal(Vec3 a, Vec3 b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    // ---------------------------------------------------------------------------------------------------------

    private TickResult complete(BotPlayer body) {
        release(body);
        return TickResult.COMPLETED;
    }

    private TickResult stop(BotPlayer body, Failure failure) {
        release(body);
        return TickResult.failed(failure);
    }

    /** Lets go of the look and reels the line in; the navigator is stopped by {@link #release}. */
    @Override
    protected void letGo(BotPlayer body) {
        if (body == null) return;
        if (looking) {
            body.input().reset();
            looking = false;
        }
        reel(body);
    }

    /** Where it has looked for water, so a restart does not search from scratch: hints only, checked against the world. */
    @Override
    public Map<String, String> checkpoint() {
        Map<String, String> out = new LinkedHashMap<>();
        if (origin == null) return out;
        out.put("dim", dimension);
        out.put("explored", Integer.toString((int) exploredBlocks));
        if (spot != null) out.put("stand", corePos(spot.stand()).encode());
        else if (hintStand != null) out.put("stand", hintStand.encode());
        return out;
    }

    /** True while the task waits on the game (a hook out or in the water, a catch on its way), not on the body. */
    @Override
    public boolean waiting() {
        return phase == Phase.WAITING || phase == Phase.LANDING;
    }

    /** What the task is doing now, for the status line, and how many of the item it has. */
    @Override
    public String progress() {
        String what = switch (phase) {
            case FIND -> "looking for water to fish";
            case EXPLORE -> "exploring for water to fish";
            case TO_STAND -> "walking to the bank";
            case AIM, CAST -> "casting the line";
            case WAITING -> "waiting for a bite";
            case LANDING -> "bringing in what it caught";
        };
        if (item == null) return what;
        return what + "; has " + heldNow + " of " + target + " " + itemKey.id();
    }
}
