package com.legoj15.megablockbot.game.plan;

import com.legoj15.megablockbot.core.ItemKey;
import java.util.Map;
import java.util.TreeMap;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;

/**
 * What a container the owner names is in the world (design 3.5, supply containers): one inventory, which for a double
 * chest is both halves. Server thread only, and read-only: it never opens a menu or moves an item.
 */
public final class SupplyChests {
    private SupplyChests() {}

    /**
     * One inventory as the world has it now.
     *
     * @param pos      the block that stands for it: the half with the lower {@code BlockPos.asLong} of a double chest
     * @param other    the second half of a double chest, else null
     * @param block    the block's id
     * @param contents what is in it, whole (a double chest's two halves together)
     */
    public record Spot(BlockPos pos, @Nullable BlockPos other, String block, Map<ItemKey, Integer> contents) {}

    /**
     * The inventory the block at {@code at} belongs to, or null when the position is not loaded or no container with
     * slots stands there. A double chest named by either half answers with both: {@code ChestBlock.getContainer} with
     * {@code override} set, which looks past a block that would stop the lid from opening, because what is in the chest
     * is not changed by that (the task that opens it finds out whether it can).
     */
    @Nullable
    public static Spot look(ServerLevel level, BlockPos at) {
        if (!level.isLoaded(at)) return null;
        BlockState state = level.getBlockState(at);
        Container container;
        BlockPos other = null;
        if (state.getBlock() instanceof ChestBlock chest) {
            container = ChestBlock.getContainer(chest, state, level, at, true);
            if (state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
                BlockPos partner = at.relative(ChestBlock.getConnectedDirection(state));
                // The partner counts only when it is the other half of this very chest.
                if (level.isLoaded(partner) && level.getBlockState(partner).is(state.getBlock())
                    && level.getBlockState(partner).getValue(ChestBlock.TYPE) == state.getValue(ChestBlock.TYPE).getOpposite()) {
                    other = partner;
                }
            }
        } else {
            container = level.getBlockEntity(at) instanceof Container single ? single : null;
        }
        if (container == null || container.getContainerSize() == 0) return null;
        BlockPos pos = at;
        if (other != null && other.asLong() < at.asLong()) {
            pos = other;
            other = at;
        }
        String block = BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString();
        return new Spot(pos.immutable(), other == null ? null : other.immutable(), block, contentsOf(container));
    }

    /** What the container's slots hold, by item. Stacks with components count as their item: the planner plans by identity. */
    public static Map<ItemKey, Integer> contentsOf(Container container) {
        Map<ItemKey, Integer> contents = new TreeMap<>();
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (!stack.isEmpty()) contents.merge(ItemKeys.of(stack.getItem()), stack.getCount(), Integer::sum);
        }
        return contents;
    }
}
