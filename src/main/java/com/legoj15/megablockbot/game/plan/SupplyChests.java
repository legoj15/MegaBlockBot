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
     * the position, and for a double chest at the second half too. With no word the position being loaded is all that can
     * be asked here. A position that is not loaded is {@link Stand#NOT_LOADED}, never "gone": the owner's word and what
     * the body noted stay until the body can see the position.
     */
    public static Stand stand(ServerLevel level, BlockPos pos, @Nullable BotMemory.SupplyContainer word) {
        if (!level.isLoaded(pos)) return Stand.NOT_LOADED;
        if (word == null) return Stand.STANDS;
        return standing(level, word.pos(), word.other(), word.block());
    }

    /**
     * The one rule for every record of a container (design 3.5): the owner's word for it and the body's note of what is
     * inside count, for a task, a snapshot and block safety alike, only while the block that stands at the position is
     * the one recorded, both halves of a double chest included. The word and the note are each held to it, so a
     * container the body placed and has been inside (a note, no word) is its own only while it stands as it was, and a
     * position nothing is recorded for stands. A record that fails the rule is dropped by {@link #forget}, by whoever
     * is in a place to change the memory; reading never does.
     */
    public static Stand stand(ServerLevel level, BotMemory memory, BlockPos pos) {
        if (!level.isLoaded(pos)) return Stand.NOT_LOADED;
        String dimension = level.dimension().location().toString();
        BotMemory.SupplyContainer word = memory.supplyAt(dimension, pos);
        BotMemory.RememberedContainer note = memory.containerAt(dimension, pos);
        Stand named = word == null ? Stand.STANDS : standing(level, word.pos(), word.other(), word.block());
        Stand noted = note == null ? Stand.STANDS : standing(level, note.pos(), note.other(), note.block());
        if (named == Stand.CHANGED || noted == Stand.CHANGED) return Stand.CHANGED;
        return named == Stand.NOT_LOADED || noted == Stand.NOT_LOADED ? Stand.NOT_LOADED : Stand.STANDS;
    }

    private static Stand standing(ServerLevel level, BlockPos pos, @Nullable BlockPos other, String block) {
        // What is loaded and differs is "changed" even when the other half is not loaded: a half that is not there to ask
        // cannot make what was seen unchanged.
        boolean loaded = level.isLoaded(pos) && (other == null || level.isLoaded(other));
        if (level.isLoaded(pos) && !blockId(level.getBlockState(pos).getBlock()).equals(block)) return Stand.CHANGED;
        if (other != null && level.isLoaded(other) && !blockId(level.getBlockState(other).getBlock()).equals(block)) return Stand.CHANGED;
        return loaded ? Stand.STANDS : Stand.NOT_LOADED;
    }

    /**
     * Drops what the memory holds of the container one of whose blocks is at {@code pos}: the owner's word, the note of
     * what is inside, and the claim that the body placed the block, for the halves of a double chest too. For a container
     * that no longer stands as recorded ({@link Stand#CHANGED}): nothing the owner or the body saw there is of the block
     * that stands there now.
     *
     * @return whether the owner's word for it was among what was dropped
     */
    public static boolean forget(BotMemory memory, String dimension, BlockPos pos) {
        BotMemory.SupplyContainer word = memory.supplyAt(dimension, pos);
        BotMemory.RememberedContainer note = memory.containerAt(dimension, pos);
        memory.forgetPlaced(dimension, pos);
        if (word != null) {
            memory.forgetPlaced(dimension, word.pos());
            if (word.other() != null) memory.forgetPlaced(dimension, word.other());
        }
        if (note != null) {
            memory.forgetPlaced(dimension, note.pos());
            if (note.other() != null) memory.forgetPlaced(dimension, note.other());
        }
        memory.forgetContainer(dimension, pos);
        return memory.removeSupply(dimension, pos);
    }

    public static String blockId(Block block) {
        return BuiltInRegistries.BLOCK.getKey(block).toString();
    }
}
