package com.legoj15.megablockbot.game.reflex;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Questions about single cells of the world that more than one reflex asks: can a body stand here, would it be hurt here. */
final class Cells {
    private Cells() {}

    /** Nothing solid and no fluid in the cell: a body can be in it. */
    static boolean passable(ServerLevel level, BlockPos pos, BlockState state) {
        return state.getCollisionShape(level, pos).isEmpty() && state.getFluidState().isEmpty();
    }

    /** A block that hurts a body standing in it or on it. */
    static boolean harmful(BlockState state) {
        return state.is(BlockTags.FIRE) || state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.CACTUS) || state.is(Blocks.CAMPFIRE)
            || state.is(Blocks.SOUL_CAMPFIRE) || state.is(Blocks.SWEET_BERRY_BUSH) || state.is(Blocks.WITHER_ROSE) || state.is(Blocks.POWDER_SNOW)
            || state.getFluidState().is(FluidTags.LAVA);
    }

    /** A body with its feet in {@code feet} would stand on a solid floor, with room for its head, and nothing around it burns. */
    static boolean standable(ServerLevel level, BlockPos feet) {
        if (!level.isLoaded(feet) || !level.isLoaded(feet.above())) return false;
        BlockState at = level.getBlockState(feet);
        BlockState head = level.getBlockState(feet.above());
        BlockPos floorPos = feet.below();
        BlockState floor = level.getBlockState(floorPos);
        if (!passable(level, feet, at) || !passable(level, feet.above(), head)) return false;
        if (!floor.isFaceSturdy(level, floorPos, Direction.UP)) return false;
        return !harmful(at) && !harmful(head) && !harmful(floor);
    }

    /** The highest standable cell in the column of {@code x} and {@code z} between {@code top} and {@code bottom}, or null. */
    static BlockPos standableIn(ServerLevel level, int x, int z, int top, int bottom) {
        for (int y = top; y >= bottom; y--) {
            BlockPos feet = new BlockPos(x, y, z);
            if (standable(level, feet)) return feet;
        }
        return null;
    }
}
