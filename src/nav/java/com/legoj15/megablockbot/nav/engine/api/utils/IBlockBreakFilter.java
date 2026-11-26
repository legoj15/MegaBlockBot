// SPDX-License-Identifier: LGPL-3.0-or-later
// MegaBlockBot addition to the ported engine (see UPSTREAM.md): a per-engine veto on breaking a block, which the
// engine's owner sets. It is asked on path-search threads as well as the server thread, so it must be thread-safe
// and must only read the block state it is given and immutable data.
package com.legoj15.megablockbot.nav.engine.api.utils;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

@FunctionalInterface
public interface IBlockBreakFilter {
   boolean mayBreak(Level level, BlockPos pos, BlockState state);
}
