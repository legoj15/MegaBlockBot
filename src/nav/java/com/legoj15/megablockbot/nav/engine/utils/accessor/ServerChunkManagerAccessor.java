/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.legoj15.megablockbot.nav.engine.utils.accessor;

import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.Nullable;

/**
 * Was a mixin duck interface on {@code ServerChunkCache}. {@code getVisibleChunkIfPresent} is made public by the
 * module's access transformer instead, so this is a static helper. Unlike {@code ServerChunkCache.getChunkNow} it
 * also answers off the server thread (the path search runs on a pool thread).
 */
public final class ServerChunkManagerAccessor {
   private ServerChunkManagerAccessor() {
   }

   @Nullable
   public static LevelChunk getChunkNow(ServerChunkCache chunkCache, int chunkX, int chunkZ) {
      ChunkHolder chunkHolder = chunkCache.getVisibleChunkIfPresent(ChunkPos.asLong(chunkX, chunkZ));
      return chunkHolder == null ? null : chunkHolder.getTickingChunk();
   }
}
