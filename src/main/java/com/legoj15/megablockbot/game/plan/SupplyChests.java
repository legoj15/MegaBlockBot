package com.legoj15.megablockbot.game.plan;

import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;

/**
 * Where a container the owner names is in the world (design 3.5, supply containers): one inventory, which for a double
 * chest is both halves. Server thread only, and read-only: it never opens a menu, moves an item or reads what is inside.
 * What is inside a closed container is not something a player can see, so it is not something this class offers: the
 * body learns it by opening the container ({@code LookTask}, {@code WithdrawTask}) and notes it in {@link BotMemory}.
 */
public final class SupplyChests {
    private SupplyChests() {}

    /**
     * One inventory as the world has it now.
     *
     * @param pos   the block that stands for it: the half with the lower {@code BlockPos.asLong} of a double chest
     * @param other the second half of a double chest, else null
     * @param block the block's id (both halves of a double chest are the same block)
     */
    public record Spot(BlockPos pos, @Nullable BlockPos other, String block) {}

    /** Whether the container the owner named still stands as named. */
    public enum Stand {
        /** The block, and for a double chest both of its blocks, is what was named. */
        STANDS,
        /** A position that is not loaded cannot be told about: nothing is known of it, so nothing is dropped. */
        NOT_LOADED,
        /** Another block stands there, or the second half of the pair is gone: the owner's word was for something else. */
        CHANGED
    }

    /**
     * The inventory the block at {@code at} belongs to, or null when the position is not loaded or no container with
     * slots stands there. A double chest named by either half answers with both.
     */
    @Nullable
    public static Spot find(ServerLevel level, BlockPos at) {
        if (!level.isLoaded(at)) return null;
        if (!(level.getBlockEntity(at) instanceof Container container) || container.getContainerSize() == 0) return null;
        BlockState state = level.getBlockState(at);
        BlockPos other = null;
        if (state.getBlock() instanceof ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            BlockPos partner = at.relative(ChestBlock.getConnectedDirection(state));
            // The partner counts only when it is the other half of this very chest.
            if (level.isLoaded(partner) && level.getBlockState(partner).is(state.getBlock())
                && level.getBlockState(partner).getValue(ChestBlock.TYPE) == state.getValue(ChestBlock.TYPE).getOpposite()) {
                other = partner;
            }
        }
        BlockPos pos = at;
        if (other != null && other.asLong() < at.asLong()) {
            pos = other;
            other = at;
        }
        return new Spot(pos.immutable(), other == null ? null : other.immutable(), blockId(level.getBlockState(pos).getBlock()));
    }

    /**
     * Whether the container at {@code pos} is still what its owner named: the block recorded when it was named stands at
     * the position, and for a double chest at the second half too. With no word (a container the body placed) the
     * position being loaded is all that can be asked here. A position that is not loaded is {@link Stand#NOT_LOADED},
     * never "gone": the owner's word and what the body noted stay until the body can see the position.
     */
    public static Stand stand(ServerLevel level, BlockPos pos, @Nullable BotMemory.SupplyContainer word) {
        if (!level.isLoaded(pos)) return Stand.NOT_LOADED;
        if (word == null) return Stand.STANDS;
        if (!level.isLoaded(word.pos()) || (word.other() != null && !level.isLoaded(word.other()))) return Stand.NOT_LOADED;
        if (!blockId(level.getBlockState(word.pos()).getBlock()).equals(word.block())) return Stand.CHANGED;
        if (word.other() != null && !blockId(level.getBlockState(word.other()).getBlock()).equals(word.block())) return Stand.CHANGED;
        return Stand.STANDS;
    }

    public static String blockId(Block block) {
        return BuiltInRegistries.BLOCK.getKey(block).toString();
    }
}
