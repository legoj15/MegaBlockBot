package com.legoj15.megablockbot.game.task;

import com.legoj15.megablockbot.core.ItemKey;
import com.legoj15.megablockbot.core.Pos;
import com.legoj15.megablockbot.core.goal.Goal;
import com.legoj15.megablockbot.core.plan.NodeSpec;
import com.legoj15.megablockbot.core.task.Failure;
import com.legoj15.megablockbot.core.task.TickResult;
import com.legoj15.megablockbot.game.body.BotPlayer;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * {@code withdraw} (a planner leaf whose goal is {@link Goal.HaveItem}): take items out of a container as a player does
 * (design 3.5, supply containers). The body walks to it, opens it by using the block, moves the items to its inventory
 * with clicks on the menu's slots (a shift-click for a whole stack, one right-click per item for part of one), and
 * closes the menu. Nothing is taken any other way. The walk, the owner's-word and safety checks and the menu are
 * {@link ContainerTask}'s.
 *
 * <p>Leaf params: {@code container} (the position), {@code dimension}, {@code count} (how many to take), and the item is
 * the goal's. The goal's count is what the body should hold once the leaf is done, so a task started again after some of
 * it was taken takes only what is missing, and one whose goal already holds completes at once without opening anything.
 *
 * <p>Failures: {@code Unreachable} when the body cannot get to the container, the position is not loaded (nothing is
 * forgotten then), or it is in another dimension; {@code NoSource}, naming the leaf's own source, when the container is
 * there and may be used but will not open (blocked above, locked): the executor rules that source out, so the next plan
 * gathers or crafts what the container would have given; {@code TargetGone} when no container stands as it was named (the
 * owner's word and what the body remembered of it are dropped: a container put there later is not the one that was
 * named); {@code Protected} when block safety refuses to let the body use it (a claim, or a container nobody named for
 * this body); {@code MissingInput} when it holds fewer than the leaf needs, in which case nothing is taken, so that the
 * replan finds what is really there; {@code NoProgress} when the inventory has no room for what the leaf asked for (the
 * design's table has no row of its own for a full inventory, and {@code CraftTask} says it the same way).
 *
 * <p>Whatever it saw inside goes into the body's {@link com.legoj15.megablockbot.game.plan.BotMemory}, which the snapshot
 * reads, so the next plan counts what was really there.
 */
final class WithdrawTask extends ContainerTask {
    private final ItemKey itemKey;
    private final int count;
    private final int goalCount;
    /** The id the planner gave this leaf's source: what a NO_SOURCE failure names to have the container ruled out. */
    private final String source;

    private Item item;

    WithdrawTask(NodeSpec leaf) {
        super(required(leaf, "dimension"), Pos.parse(required(leaf, "container")));
        if (!(leaf.goal() instanceof Goal.HaveItem goal)) throw new IllegalArgumentException("withdraw leaf has no HaveItem goal");
        this.itemKey = goal.key();
        this.goalCount = goal.count();
        this.source = leaf.source();
        this.count = intParam(leaf.params(), "count", 0);
        if (count < 1) throw new IllegalArgumentException("withdraw count must be positive");
    }

    @Override
    protected boolean prepare(BotPlayer body) {
        ResourceLocation id = idOf(itemKey.id());
        item = id == null ? null : BuiltInRegistries.ITEM.getOptional(id).orElse(null);
        if (item == null) {
            fail(new Failure.MissingInput(itemKey, count, "'" + itemKey.id() + "' is not a registered item"));
            return false;
        }
        return true;
    }

    /** What is still to take: the leaf's count, less what the body already took of it (its goal is an absolute count). */
    private int toTake(BotPlayer body) {
        return Math.min(count, goalCount - body.getInventory().countItem(item));
    }

    @Override
    protected boolean alreadyDone(BotPlayer body) {
        return toTake(body) <= 0;
    }

    @Override
    protected Failure wouldNotOpen(BotPlayer body) {
        return new Failure.NoSource(itemKey, source, "using the container at " + containerPos.encode()
            + " opened no menu (blocked, locked, or out of reach), so it is no source of " + itemKey.id());
    }

    @Override
    protected TickResult inside(BotPlayer body, AbstractContainerMenu menu) {
        int want = toTake(body);
        Map<ItemKey, Integer> seen = contentsOf(body, menu);
        int there = seen.getOrDefault(itemKey, 0);
        if (there < want) {
            // Fewer than the plan counted on (somebody emptied it, or it was remembered wrong): take none, say what is there.
            note(body, seen);
            return end(body, TickResult.failed(new Failure.MissingInput(itemKey, want,
                "the container at " + containerPos.encode() + " holds " + there + " of " + itemKey.id() + ", " + want + " to take")));
        }
        int taken = takeItems(body, menu, want);
        note(body, contentsOf(body, menu));
        if (taken < want) {
            return end(body, TickResult.failed(new Failure.NoProgress(0, "there is no room in the inventory for "
                + (want - taken) + " more " + itemKey.id() + " from the container at " + containerPos.encode())));
        }
        return end(body, TickResult.COMPLETED);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Slot clicks
    // ---------------------------------------------------------------------------------------------------------

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
}
