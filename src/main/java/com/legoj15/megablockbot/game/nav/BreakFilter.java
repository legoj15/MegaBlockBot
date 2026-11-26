package com.legoj15.megablockbot.game.nav;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * May the navigator break this block? Asked when a path is costed (on a path-search thread, so it must be
 * thread-safe and read only the state it is given and immutable data) and again on the server thread before every
 * break. A filter that throws is taken as a no.
 */
@FunctionalInterface
public interface BreakFilter {
    BreakFilter ALLOW_ALL = (level, pos, state) -> true;

    boolean mayBreak(ServerLevel level, BlockPos pos, BlockState state);
}
