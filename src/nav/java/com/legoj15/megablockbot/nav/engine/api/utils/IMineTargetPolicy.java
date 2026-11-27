// SPDX-License-Identifier: LGPL-3.0-or-later
// MegaBlockBot addition to the ported engine (see UPSTREAM.md): the engine owner's say over which known blocks a mine
// command may head for. It is applied wherever the mine process narrows its targets (every tick and on every rescan),
// on the server thread and on the search pool, so it must be thread-safe and read the world only through the reader it is
// handed.
package com.legoj15.megablockbot.nav.engine.api.utils;

import java.util.List;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

@FunctionalInterface
public interface IMineTargetPolicy {
   /**
    * @param candidates the blocks the process knows of that it could mine, nearest to the entity first
    * @param blocks     the engine's own view of the world, safe to read from whichever thread is asking
    * @return the ones the process may head for, in the order it should prefer them; empty when none may be taken now.
    *         Not null, and not a list the caller keeps changing.
    */
   List<BlockPos> select(List<BlockPos> candidates, Function<BlockPos, BlockState> blocks);
}
