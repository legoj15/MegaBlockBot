package com.legoj15.megablockbot.game.build;

import com.legoj15.megablockbot.core.ItemKey;
import com.legoj15.megablockbot.core.snapshot.StructureReport;
import com.legoj15.megablockbot.game.plan.ItemKeys;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/**
 * One blueprint (issue 17): a name and an immutable list of (position relative to the anchor, block state), air and
 * structure voids left out. The anchor is the min corner of the bounding box, so a blueprint saved from a box of the
 * world builds back with its corner at the anchor.
 *
 * <p>This brief builds plain blocks, and the constructor refuses anything else, naming the block: a block whose state
 * has no item that places it (a fluid, fire, a portal), a block with a block entity (a chest or a sign carries data the
 * blueprint would not put back), or the second block of a two-block thing (the upper half of a door, the head of a
 * bed), which the first block's item places on its own.
 */
public final class Blueprint {
    /** A block of the blueprint: where it stands relative to the anchor, and what stands there. */
    public record Entry(BlockPos pos, BlockState state) {}

    private final String name;
    private final List<Entry> blocks;
    private final int widthX;
    private final int heightY;
    private final int lengthZ;

    /**
     * @throws IllegalArgumentException naming the block when the blueprint holds one this brief cannot build (see the
     *                                  class doc); positions are taken relative to the min corner of their bounding box
     */
    public Blueprint(String name, List<Entry> entries) {
        this.name = name;
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (Entry entry : entries) {
            BlockPos pos = entry.pos();
            minX = Math.min(minX, pos.getX());
            minY = Math.min(minY, pos.getY());
            minZ = Math.min(minZ, pos.getZ());
            maxX = Math.max(maxX, pos.getX());
            maxY = Math.max(maxY, pos.getY());
            maxZ = Math.max(maxZ, pos.getZ());
        }
        List<Entry> normalized = new ArrayList<>(entries.size());
        for (Entry entry : entries) {
            BlockPos pos = entry.pos();
            BlockPos relative = new BlockPos(pos.getX() - minX, pos.getY() - minY, pos.getZ() - minZ);
            buildable(entry.state());
            normalized.add(new Entry(relative, entry.state()));
        }
        normalized.sort(Comparator.comparing(Entry::pos));
        this.blocks = List.copyOf(normalized);
        this.widthX = entries.isEmpty() ? 0 : maxX - minX + 1;
        this.heightY = entries.isEmpty() ? 0 : maxY - minY + 1;
        this.lengthZ = entries.isEmpty() ? 0 : maxZ - minZ + 1;
    }

    /** Names the first block this brief cannot build; throws for it (see the class doc). */
    private static void buildable(BlockState state) {
        String id = state.getBlock().toString();
        if (state.getBlock().asItem() == net.minecraft.world.item.Items.AIR) {
            throw new IllegalArgumentException("the blueprint holds " + id + ", which no item places");
        }
        if (state.getBlock() instanceof EntityBlock) {
            throw new IllegalArgumentException("the blueprint holds " + id + ", a block entity, whose data it cannot put back");
        }
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) {
            throw new IllegalArgumentException("the blueprint holds the upper half of " + id + "; the lower half places it");
        }
        if (state.hasProperty(BlockStateProperties.BED_PART)
                && state.getValue(BlockStateProperties.BED_PART) == BedPart.HEAD) {
            throw new IllegalArgumentException("the blueprint holds the head of a bed; the foot places it");
        }
    }

    public String name() {
        return name;
    }

    /** The blocks to place, sorted (min corner first), each relative to the anchor. */
    public List<Entry> blocks() {
        return blocks;
    }

    public int size() {
        return blocks.size();
    }

    public int widthX() {
        return widthX;
    }

    public int heightY() {
        return heightY;
    }

    public int lengthZ() {
        return lengthZ;
    }

    // ---- what stands at the anchor (the snapshot's report, the task's checks) -------------------------------

    /** How many of the blueprint's positions hold the blueprint's block already, matched by block, not by state. */
    public int matched(ServerLevel level, BlockPos anchor) {
        int matched = 0;
        for (Entry entry : blocks) {
            if (level.getBlockState(anchor.offset(entry.pos())).getBlock() == entry.state().getBlock()) matched++;
        }
        return matched;
    }

    /** True when every position holds its block: a log placed along another axis counts (the block, not the state). */
    public boolean fullyBuilt(ServerLevel level, BlockPos anchor) {
        return matched(level, anchor) == blocks.size();
    }

    /** The positions that do not hold their block, in blueprint order. */
    public List<BlockPos> wrong(ServerLevel level, BlockPos anchor) {
        List<BlockPos> wrong = new ArrayList<>();
        for (Entry entry : blocks) {
            BlockPos at = anchor.offset(entry.pos());
            if (level.getBlockState(at).getBlock() != entry.state().getBlock()) wrong.add(at);
        }
        return wrong;
    }

    /**
     * What is missing as items and counts: for each position still wrong, the item that places its block. What the body
     * holds of those items is not subtracted here; the report is what the world says, and the planner and the task
     * compare it with the inventory.
     */
    public Map<ItemKey, Integer> missing(ServerLevel level, BlockPos anchor) {
        Map<ItemKey, Integer> missing = new TreeMap<>();
        for (Entry entry : blocks) {
            BlockPos at = anchor.offset(entry.pos());
            if (level.getBlockState(at).getBlock() == entry.state().getBlock()) continue;
            Item item = entry.state().getBlock().asItem();
            missing.merge(ItemKeys.of(item), 1, Integer::sum);
        }
        return missing;
    }

    /**
     * The report the build goal's postcondition reads ({@code blueprint@anchor}): how much stands and what is missing
     * as items. {@code known} is false when the goal's dimension is not the level's (the anchor cannot be looked at) or
     * any position's chunk is not loaded: the world cannot be read from where the body is, and an unreadable report is
     * never "nothing missing".
     */
    public StructureReport report(ServerLevel level, String goalDimension, String levelDimension, BlockPos anchor) {
        if (!goalDimension.equals(levelDimension)) {
            return new StructureReport(false, 0, blocks.size(), Map.of());
        }
        boolean known = true;
        int matched = 0;
        Map<ItemKey, Integer> missing = new TreeMap<>();
        for (Entry entry : blocks) {
            BlockPos at = anchor.offset(entry.pos());
            if (!level.hasChunkAt(at)) {
                known = false;
                break;
            }
            if (level.getBlockState(at).getBlock() == entry.state().getBlock()) matched++;
            else missing.merge(ItemKeys.of(entry.state().getBlock().asItem()), 1, Integer::sum);
        }
        return new StructureReport(known, matched, blocks.size(), missing);
    }
}
