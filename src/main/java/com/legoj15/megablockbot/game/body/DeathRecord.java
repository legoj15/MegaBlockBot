package com.legoj15.megablockbot.game.body;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;

/**
 * Where and when a body died, and where the death put its items (design 3.1, "Death"). Plain values: nothing in it
 * points at a world object, so a brain can keep it across the respawn and across a restart.
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

    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putString("dimension", dimension);
        tag.put("pos", NbtUtils.writeBlockPos(pos));
        tag.putLong("gameTime", gameTime);
        ListTag drops = new ListTag();
        for (BlockPos drop : dropPositions) drops.add(NbtUtils.writeBlockPos(drop));
        tag.put("drops", drops);
        return tag;
    }

    /** Reads what {@link #toTag} wrote; throws when the tag is not one, so the caller can drop the record. */
    public static DeathRecord fromTag(CompoundTag tag) {
        if (!tag.contains("dimension", Tag.TAG_STRING) || !tag.contains("gameTime", Tag.TAG_LONG)) {
            throw new IllegalArgumentException("not a death record: " + tag);
        }
        BlockPos pos = NbtUtils.readBlockPos(tag, "pos").orElseThrow(() -> new IllegalArgumentException("death record without a position"));
        List<BlockPos> drops = new ArrayList<>();
        ListTag list = tag.getList("drops", Tag.TAG_INT_ARRAY);
        for (int i = 0; i < list.size(); i++) {
            int[] xyz = list.getIntArray(i);
            if (xyz.length == 3) drops.add(new BlockPos(xyz[0], xyz[1], xyz[2]));
        }
        return new DeathRecord(tag.getString("dimension"), pos, tag.getLong("gameTime"), drops);
    }
}
