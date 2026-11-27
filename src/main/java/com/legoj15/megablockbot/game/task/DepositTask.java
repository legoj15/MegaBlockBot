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
import com.legoj15.megablockbot.game.plan.SupplyChests;
import com.legoj15.megablockbot.game.safety.Verdict;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.CompoundContainer;
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
 * the menu's slots, and closes the menu. A menu that was already open when the task got to the container is closed
 * first: the task works only in the menu its own use-click opened, and only while that menu is the container's own
 * (its kind checked, and the block entity at the position where the game serves it from one).
 *
 * <p>The goal names the item, the container and its dimension; the {@code count} parameter is how many to move (the
 * goal's count is what the container should end up holding, which may include what it held already).
 *
 * <p>Failures: {@code Unreachable} when the body cannot get to the container, the container will not open (blocked
 * above, locked, in another dimension), or its position is not loaded; {@code TargetGone} when no container stands at
 * the position, or another block stands where the container the goal was judged against was recorded (what the body
 * holds of it is then dropped, as it is for a withdraw: {@link ContainerTask});
 * {@code Protected} when block safety refuses (a claim, a bed, a job site); {@code MissingInput} when the body does not
 * hold the count. A container with no room takes what it can and the task completes: the postcondition then says what
 * is missing, and judging that is the executor's business.
 *
 * <p><b>Block safety.</b> Block safety is asked before the lid is touched, as it is for every container the body uses
 * ({@link WithdrawTask}). The goal names this container, and that is the owner's word for it for this goal, so the
 * question is asked with an allowance that covers exactly that container (both halves of a double chest) open for
 * the length of the question ({@link ContainerAccess#askAsGoalsWord}), which passes the container rule for those blocks. A claim, and a
 * villager job site such as a barrel that the body did not place, still refuse: the allowance never lifts those, so a
 * barrel is stocked after the owner names it with {@code use chest at}. The allowance is closed before the lid is
 * touched, so no path search can take it for leave to break the chest.
 *
 * <p><b>What it saw.</b> When the items are in, the body notes what the container holds, as a withdraw does
 * ({@link ContainerAccess#note}): what it put in is remembered, and a container it had never been inside has a note, so
 * the goal's postcondition can be read from then on. (A stockpile goal's plan looks into a container the body has no
 * sight of before it deposits anything: {@code LookInside}.)
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
        BotMemory memory = BotMemory.of(body.getUUID());
        // The container the goal was judged against is the one to put into, so the records of it are held to the block
        // that stands here, as they are when the body takes items out (ContainerTask). A position nothing is recorded
        // for stands, and what is there decides, as below.
        switch (SupplyChests.stand(level, memory, pos)) {
            case NOT_LOADED -> {
                // Nothing is known of an unloaded position, so nothing is forgotten: the executor goes there again.
                return end(body, TickResult.failed(unreachable(containerPos.encode(), "not loaded",
                    "the position of the container at " + containerPos.encode() + " is not loaded, so I cannot tell what stands there")));
            }
            case CHANGED -> {
                return end(body, TickResult.failed(gone(body, memory, pos)));
            }
            case STANDS -> { }
        }
        BlockEntity entity = level.isLoaded(pos) ? level.getBlockEntity(pos) : null;
        if (!(entity instanceof Container) && !(entity instanceof MenuProvider)) {
            return end(body, TickResult.failed(new Failure.TargetGone("container at " + containerPos.encode(),
                "no container stands at " + containerPos.encode() + " in " + dimension)));
        }
        Verdict verdict = ContainerAccess.askAsGoalsWord(body, level, pos);
        if (!verdict.allowed()) return end(body, TickResult.failed(verdict.toFailure(pos)));
        if (body.containerMenu != body.inventoryMenu) {
            // A menu that was open before the task got here (a container used before this step) is not this
            // container's, whatever it is. Without closing it first, a container whose use opens nothing (blocked,
            // locked) would leave it in the body's hands, and the items would go into it as though it were the goal's.
            body.closeContainer();
        }
        BodyInteractionManager use = (BodyInteractionManager) body.getInteractionManager();
        use.interactBlock(body, level, body.getMainHandItem(), InteractionHand.MAIN_HAND, hitOn(body, pos));
        AbstractContainerMenu menu = body.containerMenu;
        if (menu == body.inventoryMenu || !menuOfTheContainer(body, level, pos, menu)) {
            return end(body, TickResult.failed(unreachable(containerPos.encode(), "container would not open",
                "using the container at " + containerPos.encode() + " opened no menu of the container's own"
                    + " (blocked, locked, or out of reach)")));
        }
        openMenu = menu;
        phase = Phase.TRANSFERRING;
        return TickResult.RUNNING;
    }

    /**
     * Whether {@code menu} is the menu of the container at {@code pos}: the task works only in the menu its own
     * use-click there opened. The game serves a container's menu slots from its block entity, so where the block
     * entity is a container the menu must serve its container slots from it (a double chest serves them from the
     * compound of its halves, which is why that counts as the block's own); a container the game gives no such handle
     * for has at least to have opened a menu that is not the body's own inventory menu.
     */
    private static boolean menuOfTheContainer(BotPlayer body, ServerLevel level, BlockPos pos, AbstractContainerMenu menu) {
        if (!(level.getBlockEntity(pos) instanceof Container at)) return true;
        for (Slot slot : menu.slots) {
            if (slot.container == body.getInventory() || !(slot.container instanceof Container served)) continue;
            if (served == at || served instanceof CompoundContainer) return true;
        }
        return false;
    }

    /**
     * What the body holds of this position (the owner's word, the note of what is inside, its claim on the spot) is
     * dropped: another block stands where the container the goal was counted against was, so the deposit would go into
     * something the plan never asked about.
     */
    private Failure gone(BotPlayer body, BotMemory memory, BlockPos pos) {
        if (SupplyChests.forget(memory, dimension, pos)) {
            MegaBlockBot.LOGGER.info("{} found another block where a container was named at {} in {} and no longer puts anything in it",
                body.getGameProfile().getName(), pos.toShortString(), dimension);
        }
        return new Failure.TargetGone("container at " + containerPos.encode(), "another block stands where the container was "
            + "recorded at " + containerPos.encode() + " in " + dimension);
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
        ContainerAccess.note(body, dimension, containerPos, ContainerAccess.contentsOf(body, openMenu));
        return end(body, TickResult.COMPLETED);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Slot clicks
    // ---------------------------------------------------------------------------------------------------------

    /**
     * Moves up to {@code want} of the item from the body's slots into the menu's container slots, with the clicks a
     * player makes: shift-click for a whole stack, and for part of one lift the stack, set the items down one at a time
     * with the right button, and put the rest back. What a click moved is the fall in the body's carried count of the
     * item (the count a withdraw trusts for a take), not what the click was meant to move, so a container that takes
     * only part of a stack is credited with that part alone. Stops when the container takes no more.
     */
    private void moveItems(BotPlayer body, AbstractContainerMenu menu, int want) {
        int remaining = want;
        for (Slot source : menu.slots) {
            if (remaining <= 0) break;
            if (source.container != body.getInventory() || !source.getItem().is(item)) continue;
            int before = body.getInventory().countItem(item);
            if (source.getItem().getCount() <= remaining) {
                menu.clicked(source.index, 0, ClickType.QUICK_MOVE, body);
            } else {
                movePart(body, menu, source, remaining);
            }
            int moved = before - body.getInventory().countItem(item);
            if (moved <= 0) break; // the container takes none of it
            remaining -= moved;
        }
    }

    /** Part of a stack: lift it, set the items down one at a time with the right button, and put the rest back where
     *  it came from (which the lift emptied). What moved is the caller's to count, by what the body no longer holds. */
    private void movePart(BotPlayer body, AbstractContainerMenu menu, Slot source, int want) {
        menu.clicked(source.index, 0, ClickType.PICKUP, body); // lift the stack
        int set = 0;
        while (set < want) {
            Slot target = roomFor(body, menu, menu.getCarried());
            if (target == null) break;
            int carried = menu.getCarried().getCount();
            menu.clicked(target.index, 1, ClickType.PICKUP, body); // set one down
            if (menu.getCarried().getCount() >= carried) break; // nothing was set down
            set++;
        }
        // What is left on the cursor goes back where it came from, which the lift emptied.
        menu.clicked(source.index, 0, ClickType.PICKUP, body);
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
