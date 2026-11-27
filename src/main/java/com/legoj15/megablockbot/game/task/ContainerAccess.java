package com.legoj15.megablockbot.game.task;

import com.legoj15.megablockbot.core.ItemKey;
import com.legoj15.megablockbot.core.Pos;
import com.legoj15.megablockbot.game.body.BotPlayer;
import com.legoj15.megablockbot.game.plan.BotMemory;
import com.legoj15.megablockbot.game.plan.ItemKeys;
import com.legoj15.megablockbot.game.plan.SupplyChests;
import com.legoj15.megablockbot.game.safety.Allowance;
import com.legoj15.megablockbot.game.safety.BlockSafety;
import com.legoj15.megablockbot.game.safety.Verdict;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * What every task that works inside a container shares (design 3.5, supply containers): reading what the open menu holds,
 * noting it in the body's memory, and asking block safety on the strength of a goal's own word. {@link ContainerTask}
 * (withdraw, look inside) and {@link DepositTask} use it, so that whichever of them last opened a container leaves the
 * same note of it.
 */
final class ContainerAccess {
    private ContainerAccess() {}

    /** What the menu's container slots hold now (the body's own slots are not the container's), by item. */
    static Map<ItemKey, Integer> contentsOf(BotPlayer body, AbstractContainerMenu menu) {
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
     * task was given; whatever was noted under any of its blocks before is replaced.
     */
    static void note(BotPlayer body, String dimension, Pos containerPos, Map<ItemKey, Integer> contents) {
        ServerLevel level = body.serverLevel();
        BlockPos pos = new BlockPos(containerPos.x(), containerPos.y(), containerPos.z());
        SupplyChests.Spot spot = SupplyChests.find(level, pos);
        BlockPos at = spot == null ? pos : spot.pos();
        BlockPos other = spot == null ? null : spot.other();
        String block = spot == null ? SupplyChests.blockId(level.getBlockState(pos).getBlock()) : spot.block();
        BotMemory.of(body.getUUID()).noteContainer(dimension, at, other, block, contents);
    }

    /**
     * May the body use the container at {@code pos}, when a goal names it? A goal that names a container (a stockpile
     * goal's target) is the owner's word for it for that goal, so the question is asked with an {@link Allowance} that
     * covers exactly that container (both halves of a double chest) open for the length of the question, which passes the
     * container rule for those blocks. A claim, a bed and a villager job site such as a barrel the body did not place still
     * refuse: the allowance never lifts those. It is closed before anything is touched, so no path search can take it for
     * leave to break the chest.
     */
    static Verdict askAsGoalsWord(BotPlayer body, ServerLevel level, BlockPos pos) {
        SupplyChests.Spot spot = SupplyChests.find(level, pos);
        BlockSafety safety = BlockSafety.of(level.getServer());
        try (Allowance goalsWord = safety.allow(body, "stockpile target",
            at -> at.equals(pos) || (spot != null && (at.equals(spot.pos()) || at.equals(spot.other()))))) {
            return safety.mayUseContainer(body, level, pos);
        }
    }
}
