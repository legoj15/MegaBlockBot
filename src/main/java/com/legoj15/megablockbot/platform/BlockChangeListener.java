package com.legoj15.megablockbot.platform;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Loader-neutral observer of a block change; see {@link LoaderServices#onBlockBroken} and
 * {@link LoaderServices#onBlockPlaced}. It sees the change after the fact, server side only, and cannot cancel it.
 */
@FunctionalInterface
public interface BlockChangeListener {
    /**
     * @param level the level the block is in
     * @param pos the changed position; the caller may keep it
     * @param state the state that was removed (for a break) or the state now at the position (for a placement)
     * @param cause the entity behind the change, or null when the loader does not report one
     */
    void changed(ServerLevel level, BlockPos pos, BlockState state, @Nullable Entity cause);
}
