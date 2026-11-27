package com.legoj15.megablockbot.game.task;

import com.legoj15.megablockbot.MegaBlockBot;
import com.legoj15.megablockbot.core.ItemKey;
import com.legoj15.megablockbot.core.Pos;
import com.legoj15.megablockbot.core.goal.Goal;
import com.legoj15.megablockbot.core.plan.NodeSpec;
import com.legoj15.megablockbot.core.task.Failure;
import com.legoj15.megablockbot.core.task.TickResult;
import com.legoj15.megablockbot.game.body.BotPlayer;
import com.legoj15.megablockbot.game.nav.BodyInteractionManager;
import com.legoj15.megablockbot.game.nav.BotNavigator;
import com.legoj15.megablockbot.game.plan.BotMemory;
import com.legoj15.megablockbot.game.plan.ItemKeys;
import com.legoj15.megablockbot.game.safety.BlockSafety;
import com.legoj15.megablockbot.game.safety.Verdict;
import java.util.Map;
import java.util.TreeMap;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * {@code withdraw} (a planner leaf whose goal is {@link Goal.HaveItem}): take items out of a container as a player does
 * (design 3.5, supply containers). The body walks to it, opens it by using the block, moves the items to its inventory
 * with clicks on the menu's slots (a shift-click for a whole stack, one right-click per item for part of one), and
 * closes the menu. Nothing is taken any other way.
 *
 * <p>Leaf params: {@code container} (the position), {@code dimension}, {@code count} (how many to take), and the item is
 * the goal's. The goal's count is what the body should hold once the leaf is done, so a task started again after some of
 * it was taken takes only what is missing, and one whose goal already holds completes at once without opening anything.
 *
 * <p>Failures: {@code Unreachable} when the body cannot get to the container or it will not open (blocked above, locked,
 * in another dimension); {@code TargetGone} when no container stands at the position (what the body remembered of it, and
 * the owner's naming it, are dropped: a container put there later is not the one that was named); {@code Protected} when
 * block safety refuses to let the body use it (a claim, or a container nobody named for this body: checked when it is
 * about to open it, as a position far away is not loaded); {@code MissingInput} when it holds fewer than the leaf needs,
 * in which case nothing is taken, so that the replan finds what is really there; {@code NoProgress} when the inventory
 * has no room for what the leaf asked for (the design's table has no row of its own for a full inventory, and
 * {@code CraftTask} says it the same way).
 *
 * <p>Whatever it saw inside goes into the body's {@link BotMemory}, which the snapshot reads, so the next plan counts
 * what was really there. Whichever way the task ends, the menu is closed, which hands whatever the cursor held back to the
 * inventory; the task itself never leaves anything on the cursor.
 */
final class WithdrawTask extends BodyTask {
    /** Close enough to open it: the use-block reach is 4.5 plus a block of tolerance, this keeps well inside. */
    private static final int REACH_RADIUS = 2;
    /** How often a menu that was closed under the task is opened again before it gives up. */
    private static final int MAX_REOPENS = 3;

    private enum Phase { GOING, FACING, OPENING, TRANSFERRING }

    private final String dimension;
    private final Pos containerPos;
    private final ItemKey itemKey;
    private final int count;
    private final int goalCount;

    private Item item;
    private Phase phase = Phase.GOING;
    private int reopens;
    /** The goal already holds: nothing to open. */
    private boolean satisfied;
    @Nullable private AbstractContainerMenu openMenu;

    WithdrawTask(NodeSpec leaf) {
        if (!(leaf.goal() instanceof Goal.HaveItem goal)) throw new IllegalArgumentException("withdraw leaf has no HaveItem goal");
        this.itemKey = goal.key();
        this.goalCount = goal.count();
        this.containerPos = Pos.parse(required(leaf, "container"));
        this.dimension = required(leaf, "dimension");
        this.count = intParam(leaf.params(), "count", 0);
        if (count < 1) throw new IllegalArgumentException("withdraw count must be positive");
    }

    @Override
    protected void begin(BotPlayer body) {
        ResourceLocation id = idOf(itemKey.id());
        item = id == null ? null : BuiltInRegistries.ITEM.getOptional(id).orElse(null);
        if (item == null) {
            fail(new Failure.MissingInput(itemKey, count, "'" + itemKey.id() + "' is not a registered item"));
            return;
        }
        if (!dimension.equals(dimensionOf(body))) {
            fail(unreachable(containerPos.encode(), "different dimension",
                "the body is in " + dimensionOf(body) + ", the container in " + dimension + "; dimension travel is not built"));
            return;
        }
        if (toTake(body) <= 0) {
            satisfied = true;
            return;
        }
        go(body);
    }

    @Override
    protected void resumeWork(BotPlayer body) {
        // The menu was closed by the pause; start again from the walk, from wherever the body is now.
        if (!satisfied) go(body);
    }

    private void go(BotPlayer body) {
        phase = Phase.GOING;
        nav(body).goTo(blockPos(containerPos), REACH_RADIUS);
    }

    /** What is still to take: the leaf's count, less what the body already took of it (its goal is an absolute count). */
    private int toTake(BotPlayer body) {
        return Math.min(count, goalCount - body.getInventory().countItem(item));
    }

    // ---------------------------------------------------------------------------------------------------------

    @Override
    protected TickResult onTick(BotPlayer body) {
        if (satisfied) return end(body, TickResult.COMPLETED);
        return switch (phase) {
            case GOING -> tickGoing(body);
            case FACING -> {
                // A tick to face it, as a player does before using what is in front of them.
                phase = Phase.OPENING;
                yield TickResult.RUNNING;
            }
            case OPENING -> open(body);
            case TRANSFERRING -> transfer(body);
        };
    }

    private TickResult tickGoing(BotPlayer body) {
        BotNavigator nav = nav(body);
        switch (nav.state()) {
            case DONE -> {
                // The walk is over; the navigator's own tick is done with the body.
                nav.stop();
                body.input().lookAt(Vec3.atCenterOf(blockPos(containerPos)));
                phase = Phase.FACING;
                return TickResult.RUNNING;
            }
            case FAILED -> {
                String reason = nav.failure();
                return end(body, TickResult.failed(unreachable(containerPos.encode(), reason,
                    "no way to the container at " + containerPos.encode() + ": " + reason)));
            }
            case IDLE -> {
                go(body);
                return TickResult.RUNNING;
            }
            default -> {
                return TickResult.RUNNING;
            }
        }
    }

    private TickResult open(BotPlayer body) {
        ServerLevel level = body.serverLevel();
        BlockPos pos = blockPos(containerPos);
        BlockEntity entity = level.isLoaded(pos) ? level.getBlockEntity(pos) : null;
        if (!(entity instanceof Container) && !(entity instanceof MenuProvider)) {
            // Gone, and so is the owner's word for it: what is put there later is not what was named.
            BotMemory memory = BotMemory.of(body.getUUID());
            memory.forgetContainer(dimension, pos);
            if (memory.removeSupply(dimension, pos)) {
                MegaBlockBot.LOGGER.info("{} found no container at {} in {} and no longer takes from it",
                    body.getGameProfile().getName(), pos.toShortString(), dimension);
            }
            return end(body, TickResult.failed(new Failure.TargetGone("container at " + containerPos.encode(),
                "no container stands at " + containerPos.encode() + " in " + dimension)));
        }
        Verdict verdict = BlockSafety.of(level.getServer()).mayUseContainer(body, level, pos);
        if (!verdict.allowed()) return end(body, TickResult.failed(verdict.toFailure(pos)));
        BodyInteractionManager use = (BodyInteractionManager) body.getInteractionManager();
        use.interactBlock(body, level, body.getMainHandItem(), InteractionHand.MAIN_HAND, hitOn(body, pos));
        if (body.containerMenu == body.inventoryMenu) {
            return end(body, TickResult.failed(unreachable(containerPos.encode(), "container would not open",
                "using the container at " + containerPos.encode() + " opened no menu (blocked, locked, or out of reach)")));
        }
        openMenu = body.containerMenu;
        phase = Phase.TRANSFERRING;
        return TickResult.RUNNING;
    }

    /** A use-block click on the face of the container that looks toward the body's eyes. */
    private static BlockHitResult hitOn(BotPlayer body, BlockPos pos) {
        Vec3 center = Vec3.atCenterOf(pos);
        Vec3 toEyes = body.getEyePosition().subtract(center);
        Direction face = Direction.getNearest(toEyes.x, toEyes.y, toEyes.z);
        Vec3 at = center.add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        return new BlockHitResult(at, face, pos, false);
    }

    private TickResult transfer(BotPlayer body) {
        if (body.containerMenu != openMenu) {
            // Closed under it (the body moved off, the container went away): open it again, a few times.
            openMenu = null;
            if (++reopens > MAX_REOPENS) {
                return end(body, TickResult.failed(unreachable(containerPos.encode(), "container kept closing",
                    "the menu of the container at " + containerPos.encode() + " closed " + reopens + " times")));
            }
            phase = Phase.OPENING;
            return TickResult.RUNNING;
        }
        int want = toTake(body);
        Map<ItemKey, Integer> seen = inside(body, openMenu);
        int there = seen.getOrDefault(itemKey, 0);
        if (there < want) {
            // Fewer than the plan counted on (somebody emptied it, or it was remembered wrong): take none, say what is there.
            remember(body, seen);
            return end(body, TickResult.failed(new Failure.MissingInput(itemKey, want,
                "the container at " + containerPos.encode() + " holds " + there + " of " + itemKey.id() + ", " + want + " to take")));
        }
        int taken = takeItems(body, openMenu, want);
        remember(body, inside(body, openMenu));
        if (taken < want) {
            return end(body, TickResult.failed(new Failure.NoProgress(0, "there is no room in the inventory for "
                + (want - taken) + " more " + itemKey.id() + " from the container at " + containerPos.encode())));
        }
        return end(body, TickResult.COMPLETED);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Slot clicks
    // ---------------------------------------------------------------------------------------------------------

    /** What the menu's container slots hold now (the body's own slots are not the container's), by item. */
    private static Map<ItemKey, Integer> inside(BotPlayer body, AbstractContainerMenu menu) {
        Map<ItemKey, Integer> contents = new TreeMap<>();
        for (Slot slot : menu.slots) {
            if (slot.container == body.getInventory() || !slot.hasItem()) continue;
            ItemStack stack = slot.getItem();
            contents.merge(ItemKeys.of(stack.getItem()), stack.getCount(), Integer::sum);
        }
        return contents;
    }

    /** Notes what the container held when the body last looked, so that the next snapshot, and plan, say the same. */
    private void remember(BotPlayer body, Map<ItemKey, Integer> contents) {
        BlockPos pos = blockPos(containerPos);
        String block = BuiltInRegistries.BLOCK.getKey(body.serverLevel().getBlockState(pos).getBlock()).toString();
        BotMemory.of(body.getUUID()).noteContainer(dimension, pos, block, contents);
    }

    /**
     * Moves up to {@code want} of the item from the menu's container slots into the body's inventory with the clicks a
     * player makes: a shift-click for a whole stack, and for part of one lift the stack, set the items down one at a
     * time with the right button, and put the rest back. Stops when the inventory takes no more.
     *
     * @return how many moved
     */
    private int takeItems(BotPlayer body, AbstractContainerMenu menu, int want) {
        int taken = 0;
        for (Slot source : menu.slots) {
            if (taken >= want) break;
            if (source.container == body.getInventory() || !source.hasItem() || !source.getItem().is(item)) continue;
            int remaining = want - taken;
            int moved;
            if (source.getItem().getCount() <= remaining) {
                int before = body.getInventory().countItem(item);
                menu.clicked(source.index, 0, ClickType.QUICK_MOVE, body);
                moved = body.getInventory().countItem(item) - before;
            } else {
                moved = takePart(body, menu, source, remaining);
            }
            if (moved <= 0) break; // the inventory takes none of it
            taken += moved;
        }
        return taken;
    }

    private int takePart(BotPlayer body, AbstractContainerMenu menu, Slot source, int want) {
        menu.clicked(source.index, 0, ClickType.PICKUP, body); // lift the stack
        int moved = 0;
        while (moved < want) {
            Slot target = roomFor(body, menu, menu.getCarried());
            if (target == null) break;
            int carried = menu.getCarried().getCount();
            menu.clicked(target.index, 1, ClickType.PICKUP, body); // set one down
            if (menu.getCarried().getCount() >= carried) break; // nothing was set down
            moved++;
        }
        // What is left on the cursor goes back where it came from, which the lift emptied.
        menu.clicked(source.index, 0, ClickType.PICKUP, body);
        return moved;
    }

    /** A slot of the body's own inventory that takes one more of the carried item: a partial stack first. */
    @Nullable
    private static Slot roomFor(BotPlayer body, AbstractContainerMenu menu, ItemStack carried) {
        if (carried.isEmpty()) return null;
        Slot empty = null;
        for (Slot slot : menu.slots) {
            if (slot.container != body.getInventory() || !slot.mayPlace(carried)) continue;
            ItemStack there = slot.getItem();
            if (there.isEmpty()) {
                if (empty == null) empty = slot;
            } else if (ItemStack.isSameItemSameComponents(there, carried) && there.getCount() < slot.getMaxStackSize(there)) {
                return slot;
            }
        }
        return empty;
    }

    // ---------------------------------------------------------------------------------------------------------

    /** Lets go of the body and the menu, then reports. The executor does not abort a task that ended itself. */
    private TickResult end(BotPlayer body, TickResult result) {
        release(body);
        return result;
    }

    /** Closes the menu this task opened, which also hands back anything left on the cursor, and stops facing the container. */
    @Override
    protected void letGo(BotPlayer body) {
        if (openMenu != null && body.containerMenu == openMenu) body.closeContainer();
        openMenu = null;
        body.input().clearLook();
    }
}
