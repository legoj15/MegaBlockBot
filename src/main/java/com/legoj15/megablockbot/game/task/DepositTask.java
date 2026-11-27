package com.legoj15.megablockbot.game.task;

import com.legoj15.megablockbot.core.ItemKey;
import com.legoj15.megablockbot.core.Pos;
import com.legoj15.megablockbot.core.goal.Goal;
import com.legoj15.megablockbot.core.plan.NodeSpec;
import com.legoj15.megablockbot.core.task.Failure;
import com.legoj15.megablockbot.core.task.TickResult;
import com.legoj15.megablockbot.game.body.BotPlayer;
import com.legoj15.megablockbot.game.nav.BodyInteractionManager;
import com.legoj15.megablockbot.game.nav.BotNavigator;
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
 * {@code deposit} (goal {@link Goal.Stockpile}): put items into a container as a player does. The body walks to it,
 * opens it by using the block (so the container's own rules decide whether it opens), moves the items in with clicks on
 * the menu's slots, and closes the menu.
 *
 * <p>The goal names the item, the container and its dimension; the {@code count} parameter is how many to move (the
 * goal's count is what the container should end up holding, which may include what it held already).
 *
 * <p>Failures: {@code Unreachable} when the body cannot get to the container, or the container will not open (blocked
 * above, locked, in another dimension); {@code TargetGone} when no container stands at the position;
 * {@code MissingInput} when the body does not hold the count. A container with no room takes what it can and the task
 * completes: the postcondition then says what is missing, and judging that is the executor's business.
 *
 * <p>Whichever way the task ends, the menu is closed, which hands whatever the cursor held back to the inventory.
 */
final class DepositTask extends BodyTask {
    /** Close enough to open it: the use-block reach is 4.5 plus a block of tolerance, this keeps well inside. */
    private static final int REACH_RADIUS = 2;

    private enum Phase { GOING, FACING, OPENING, TRANSFERRING }

    private final String dimension;
    private final Pos containerPos;
    private final ItemKey itemKey;
    private final int count;

    private Item item;
    private Phase phase = Phase.GOING;
    @Nullable private AbstractContainerMenu openMenu;

    DepositTask(NodeSpec leaf) {
        Goal.Stockpile goal = leaf.goal() instanceof Goal.Stockpile g ? g : null;
        if (goal == null) throw new IllegalArgumentException("deposit leaf has no Stockpile goal");
        this.dimension = goal.dimension();
        this.containerPos = goal.container();
        this.itemKey = goal.key();
        this.count = intParam(leaf.params(), "count", goal.count());
        if (count < 1) throw new IllegalArgumentException("deposit count must be positive");
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
        if (body.getInventory().countItem(item) < count) {
            fail(missing(body));
            return;
        }
        go(body);
    }

    @Override
    protected void resumeWork(BotPlayer body) {
        // The menu was closed by the pause; start again from the walk, from wherever the body is now.
        go(body);
    }

    private void go(BotPlayer body) {
        phase = Phase.GOING;
        nav(body).goTo(blockPos(containerPos), REACH_RADIUS);
    }

    private Failure missing(BotPlayer body) {
        int held = body.getInventory().countItem(item);
        return new Failure.MissingInput(itemKey, count, "the body holds " + held + " of " + itemKey.id() + ", " + count + " to deposit");
    }

    // ---------------------------------------------------------------------------------------------------------

    @Override
    protected TickResult onTick(BotPlayer body) {
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
            return end(body, TickResult.failed(new Failure.TargetGone("container at " + containerPos.encode(),
                "no container stands at " + containerPos.encode() + " in " + dimension)));
        }
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
            // Closed under it (the body moved off, the container went away): open it again.
            openMenu = null;
            phase = Phase.OPENING;
            return TickResult.RUNNING;
        }
        if (body.getInventory().countItem(item) < count) return end(body, TickResult.failed(missing(body)));
        moveItems(body, openMenu, count);
        return end(body, TickResult.COMPLETED);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Slot clicks
    // ---------------------------------------------------------------------------------------------------------

    /**
     * Moves up to {@code want} of the item from the body's slots into the menu's container slots, with the clicks a
     * player makes: shift-click for a whole stack, and for part of one lift the stack, set the items down one at a time
     * with the right button, and put the rest back. Stops when the container takes no more.
     */
    private void moveItems(BotPlayer body, AbstractContainerMenu menu, int want) {
        int remaining = want;
        for (Slot source : menu.slots) {
            if (remaining <= 0) break;
            if (source.container != body.getInventory() || !source.getItem().is(item)) continue;
            int before = source.getItem().getCount();
            int moved;
            if (before <= remaining) {
                menu.clicked(source.index, 0, ClickType.QUICK_MOVE, body);
                moved = before - (source.getItem().is(item) ? source.getItem().getCount() : 0);
            } else {
                moved = movePart(body, menu, source, remaining);
            }
            if (moved <= 0) break; // the container takes none of it
            remaining -= moved;
        }
    }

    private int movePart(BotPlayer body, AbstractContainerMenu menu, Slot source, int want) {
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

    /** A slot of the container (not the body's own) that takes one more of the carried item: a partial stack first. */
    @Nullable
    private static Slot roomFor(BotPlayer body, AbstractContainerMenu menu, ItemStack carried) {
        if (carried.isEmpty()) return null;
        Slot empty = null;
        for (Slot slot : menu.slots) {
            if (slot.container == body.getInventory() || !slot.mayPlace(carried)) continue;
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
