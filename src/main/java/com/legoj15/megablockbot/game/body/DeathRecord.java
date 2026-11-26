package com.legoj15.megablockbot.game.body;

import java.util.List;
import net.minecraft.core.BlockPos;

/**
 * Where and when a body died, and where the death put its items (design 3.1, "Death"). Plain values: nothing in it
 * points at a world object, so a brain can keep it across the respawn and write it into its own saved state.
 *
 * @param dimension     the dimension id string, e.g. {@code minecraft:overworld}
 * @param pos           the block the body died in
 * @param gameTime      the level's game time at death
 * @param dropPositions where each item entity the death created appeared (empty when nothing dropped)
 */
public record DeathRecord(String dimension, BlockPos pos, long gameTime, List<BlockPos> dropPositions) {
    public DeathRecord {
        dropPositions = List.copyOf(dropPositions);
    }
}
