package com.legoj15.megablockbot.game.task;

import com.legoj15.megablockbot.MegaBlockBot;
import com.legoj15.megablockbot.core.ItemKey;
import com.legoj15.megablockbot.core.Pos;
import com.legoj15.megablockbot.core.task.Failure;
import com.legoj15.megablockbot.core.task.TickResult;
import com.legoj15.megablockbot.game.body.BotPlayer;
import com.legoj15.megablockbot.game.nav.BodyInteractionManager;
import com.legoj15.megablockbot.game.nav.BotNavigator;
import com.legoj15.megablockbot.game.plan.BotMemory;
import com.legoj15.megablockbot.game.plan.ItemKeys;
import com.legoj15.megablockbot.game.plan.SupplyChests;
import com.legoj15.megablockbot.game.safety.BlockSafety;
import com.legoj15.megablockbot.game.safety.Verdict;
import java.util.Map;
import java.util.TreeMap;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * A task that works inside one container the way a player does (design 3.5, supply containers): walk to it, look at it,
 * check block safety, use the block so that the container's own rules decide whether it opens, work in the menu, close
 * it. {@link WithdrawTask} takes items out; {@link LookTask} only notes what is there. What is shared is everything up
 * to and including the menu, and the rules about the owner's word:
 *
 * <ul>
 *   <li>The container that is used is the one that was named. For a supply container the block recorded when it was named
 *       has to stand at the position (both halves of a double chest); another block there means the owner's word is
 *       gone, so the word and what the body noted are dropped, nothing is opened, and the failure is {@code TargetGone}.</li>
 *   <li>A position that is not loaded is "cannot tell", never "gone": {@code Unreachable}, and nothing is dropped, so the
 *       executor answers by going there.</li>
 *   <li>Block safety is asked when the body is about to open it, as a position far away is not loaded: {@code Protected}.</li>
 *   <li>A container that is there and may be used but gives no menu (blocked above, locked) is the subclass's failure
 *       ({@link #wouldNotOpen}): what the executor does with it differs by task.</li>
 * </ul>
 *
 * <p>Whichever way the task ends, the menu is closed, which hands whatever the cursor held back to the inventory.
 */
abstract class ContainerTask extends BodyTask {
    /** Close enough to open it: the use-block reach is 4.5 plus a block of tolerance, this keeps well inside. */
    private static final int REACH_RADIUS = 2;
    /** How often a menu that was closed under the task is opened again before it gives up. */
    private static final int MAX_REOPENS = 3;

    private enum Phase { GOING, FACING, OPENING, INSIDE }

    protected final String dimension;
    protected final Pos containerPos;

    private Phase phase = Phase.GOING;
    private int reopens;
    /** There is nothing to open the container for: the task completes at its first tick. */
    private boolean nothingToDo;
    @Nullable private AbstractContainerMenu openMenu;

    protected ContainerTask(String dimension, Pos containerPos) {
        this.dimension = dimension;
        this.containerPos = containerPos;
    }

    // ---------------------------------------------------------------------------------------------------------
    // What a subclass says
    // ---------------------------------------------------------------------------------------------------------

    /** Checks what only the world can answer about the leaf (an item that exists). False when it has called {@code fail}. */
    protected abstract boolean prepare(BotPlayer body);

    /** True when the leaf is already met and the container need not be opened (a restart after the work was done). */
    protected boolean alreadyDone(BotPlayer body) {
        return false;
    }

    /** The work in the open menu. It ends the task with {@link #end}. */
    protected abstract TickResult inside(BotPlayer body, AbstractContainerMenu menu);

    /** The failure for a container that is there, may be used, and opened no menu. */
    protected abstract Failure wouldNotOpen(BotPlayer body);

    // ---------------------------------------------------------------------------------------------------------

    @Override
    protected final void begin(BotPlayer body) {
        if (!prepare(body)) return;
        if (!dimension.equals(dimensionOf(body))) {
            fail(unreachable(containerPos.encode(), "different dimension",
                "the body is in " + dimensionOf(body) + ", the container in " + dimension + "; dimension travel is not built"));
            return;
        }
        if (alreadyDone(body)) {
            nothingToDo = true;
            return;
        }
        go(body);
    }

    @Override
    protected final void resumeWork(BotPlayer body) {
        // The menu was closed by the pause; start again from the walk, from wherever the body is now.
        if (!nothingToDo) go(body);
    }

    private void go(BotPlayer body) {
        phase = Phase.GOING;
        nav(body).goTo(blockPos(containerPos), REACH_RADIUS);
    }

    @Override
    protected final TickResult onTick(BotPlayer body) {
        if (nothingToDo) return end(body, TickResult.COMPLETED);
        return switch (phase) {
            case GOING -> tickGoing(body);
            case FACING -> {
                // A tick to face it, as a player does before using what is in front of them.
                phase = Phase.OPENING;
                yield TickResult.RUNNING;
            }
            case OPENING -> open(body);
            case INSIDE -> tickInside(body);
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
        BotMemory.SupplyContainer word = memory.supplyAt(dimension, pos);
        switch (SupplyChests.stand(level, pos, word)) {
            case NOT_LOADED -> {
                // Nothing is known of an unloaded position, so nothing is forgotten: the executor goes there again.
                return end(body, TickResult.failed(unreachable(containerPos.encode(), "not loaded",
                    "the position of the container at " + containerPos.encode() + " is not loaded, so I cannot tell what stands there")));
            }
            case CHANGED -> {
                return end(body, TickResult.failed(gone(body, memory, pos, "another block stands where the owner named one")));
            }
            case STANDS -> { }
        }
        BlockEntity entity = level.getBlockEntity(pos);
        if (!(entity instanceof Container) && !(entity instanceof MenuProvider)) {
            return end(body, TickResult.failed(gone(body, memory, pos, "no container stands there")));
        }
        Verdict verdict = BlockSafety.of(level.getServer()).mayUseContainer(body, level, pos);
        if (!verdict.allowed()) return end(body, TickResult.failed(verdict.toFailure(pos)));
        BodyInteractionManager use = (BodyInteractionManager) body.getInteractionManager();
        use.interactBlock(body, level, body.getMainHandItem(), InteractionHand.MAIN_HAND, hitOn(body, pos));
        if (body.containerMenu == body.inventoryMenu) return end(body, TickResult.failed(wouldNotOpen(body)));
        openMenu = body.containerMenu;
        phase = Phase.INSIDE;
        return TickResult.RUNNING;
    }

    /** The owner's word for this position, and what the body noted there, are dropped: what stands there is not what was named. */
    private Failure gone(BotPlayer body, BotMemory memory, BlockPos pos, String why) {
        memory.forgetContainer(dimension, pos);
        if (memory.removeSupply(dimension, pos)) {
            MegaBlockBot.LOGGER.info("{} found {} at {} in {} and no longer takes from it", body.getGameProfile().getName(),
                why, pos.toShortString(), dimension);
        }
        return new Failure.TargetGone("container at " + containerPos.encode(), why + " at " + containerPos.encode() + " in " + dimension);
    }

    /** A use-block click on the face of the container that looks toward the body's eyes. */
    private static BlockHitResult hitOn(BotPlayer body, BlockPos pos) {
        Vec3 center = Vec3.atCenterOf(pos);
        Vec3 toEyes = body.getEyePosition().subtract(center);
        Direction face = Direction.getNearest(toEyes.x, toEyes.y, toEyes.z);
        Vec3 at = center.add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        return new BlockHitResult(at, face, pos, false);
    }

    private TickResult tickInside(BotPlayer body) {
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
        return inside(body, openMenu);
    }

    // ---------------------------------------------------------------------------------------------------------
    // What a subclass reads and writes
    // ---------------------------------------------------------------------------------------------------------

    /** What the menu's container slots hold now (the body's own slots are not the container's), by item. */
    protected static Map<ItemKey, Integer> contentsOf(BotPlayer body, AbstractContainerMenu menu) {
        Map<ItemKey, Integer> contents = new TreeMap<>();
        for (Slot slot : menu.slots) {
            if (slot.container == body.getInventory() || !slot.hasItem()) continue;
            ItemStack stack = slot.getItem();
            contents.merge(ItemKeys.of(stack.getItem()), stack.getCount(), Integer::sum);
        }
        return contents;
    }

    /**
     * Notes what the container held when the body last looked, so that the next snapshot, and plan, say the same. The note
     * is per inventory: for a double chest it is one note, at the half with the lower position hash, whichever half the
     * leaf named; whatever was noted under any of its blocks before is replaced.
     */
    protected final void note(BotPlayer body, Map<ItemKey, Integer> contents) {
        ServerLevel level = body.serverLevel();
        BlockPos pos = blockPos(containerPos);
        SupplyChests.Spot spot = SupplyChests.find(level, pos);
        BlockPos at = spot == null ? pos : spot.pos();
        BlockPos other = spot == null ? null : spot.other();
        String block = spot == null ? SupplyChests.blockId(level.getBlockState(pos).getBlock()) : spot.block();
        BotMemory.of(body.getUUID()).noteContainer(dimension, at, other, block, contents);
    }

    /** Lets go of the body and the menu, then reports. The executor does not abort a task that ended itself. */
    protected final TickResult end(BotPlayer body, TickResult result) {
        release(body);
        return result;
    }

    /** Closes the menu this task opened, which also hands back anything left on the cursor, and stops facing the container. */
    @Override
    protected final void letGo(BotPlayer body) {
        if (openMenu != null && body.containerMenu == openMenu) body.closeContainer();
        openMenu = null;
        body.input().clearLook();
    }
}
