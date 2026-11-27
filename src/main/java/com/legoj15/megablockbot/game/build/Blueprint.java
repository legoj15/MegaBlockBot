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
import net.minecraft.world.level.block.state.properties.SlabType;

/**
 * One blueprint (issue 17): a name and an immutable list of (position relative to the anchor, block state), air and
 * structure voids left out. The anchor is the min corner of the box the blueprint was saved from, so a blueprint saved
 * from a box of the world builds back with that box's corner at the anchor; a box with an empty margin keeps it and
 * builds at the same offsets. A blueprint made of a list of blocks by hand (see {@link Blueprints#register}) anchors at
 * the min corner of the blocks themselves.
 *
 * <p>This brief builds plain blocks, and the constructor refuses anything else, naming the block: a block whose state
 * has no item that places it (a fluid, fire, a portal), a block with a block entity (a chest or a sign carries data the
 * blueprint would not put back), the second block of a two-block thing (the upper half of a door, the head of a bed),
 * which the first block's item places on its own, or a state that is more than one item's worth of the block (a double
 * slab, snow of more than one layer, candles, sea pickles or turtle eggs of more than one), which one item can never
 * finish and the builder would count as standing. Which way a block faces is left free: one item places any facing.
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
        this(name, anchoredToMinCorner(entries), span(entries, 0), span(entries, 1), span(entries, 2));
    }

    /**
     * A blueprint that keeps the box it was saved from: the entries are relative to that box's own minimum corner
     * already (a structure template's are) and the sides are the box's declared ones, air margin included, so a box
     * saved with an empty margin builds back at the same offsets from the anchor instead of shifting to its lowest
     * block. The sides are what was saved, so an entry outside them is refused before this is called.
     *
     * @throws IllegalArgumentException naming the block when the blueprint holds none at all or one this brief cannot
     *                                  build (see the class doc)
     */
    Blueprint(String name, List<Entry> entries, int widthX, int heightY, int lengthZ) {
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("the blueprint holds no block");
        }
        this.name = name;
        List<Entry> sorted = new ArrayList<>(entries.size());
        for (Entry entry : entries) {
            String why = unbuildable(entry.state());
            if (why != null) throw new IllegalArgumentException("the blueprint holds " + why);
            sorted.add(entry);
        }
        sorted.sort(Comparator.comparing(Entry::pos));
        this.blocks = List.copyOf(sorted);
        this.widthX = widthX;
        this.heightY = heightY;
        this.lengthZ = lengthZ;
    }

    /** The entries taken relative to the min corner of their own bounding box. */
    private static List<Entry> anchoredToMinCorner(List<Entry> entries) {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        for (Entry entry : entries) {
            BlockPos pos = entry.pos();
            minX = Math.min(minX, pos.getX());
            minY = Math.min(minY, pos.getY());
            minZ = Math.min(minZ, pos.getZ());
        }
        List<Entry> anchored = new ArrayList<>(entries.size());
        for (Entry entry : entries) {
            BlockPos pos = entry.pos();
            anchored.add(new Entry(new BlockPos(pos.getX() - minX, pos.getY() - minY, pos.getZ() - minZ), entry.state()));
        }
        return anchored;
    }

    /** How far the entries span along one axis (0 x, 1 y, 2 z); zero when there are none. */
    private static int span(List<Entry> entries, int axis) {
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (Entry entry : entries) {
            int at = axis == 0 ? entry.pos().getX() : axis == 1 ? entry.pos().getY() : entry.pos().getZ();
            min = Math.min(min, at);
            max = Math.max(max, at);
        }
        return entries.isEmpty() ? 0 : max - min + 1;
    }

    /**
     * Why one item cannot place this state (see the class doc), or null when one item places it: no item places the
     * block, a block entity carries data the blueprint would not put back, the state is the second block of a
     * two-block thing, or it is more than one item's worth of the block. Which way a block faces is left free, so
     * any facing of a placeable state is buildable. The save command asks this of a box before it writes a file.
     */
    public static String unbuildable(BlockState state) {
        String id = state.getBlock().toString();
        if (state.getBlock().asItem() == net.minecraft.world.item.Items.AIR) {
            return id + ", which no item places";
        }
        if (state.getBlock() instanceof EntityBlock) {
            return id + ", a block entity, whose data it cannot put back";
        }
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) {
            return "the upper half of " + id + "; the lower half places it";
        }
        if (state.hasProperty(BlockStateProperties.BED_PART)
                && state.getValue(BlockStateProperties.BED_PART) == BedPart.HEAD) {
            return "the head of a bed; the foot places it";
        }
        if (state.hasProperty(BlockStateProperties.SLAB_TYPE)
                && state.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.DOUBLE) {
            return "a double slab of " + id + "; two slabs are two items";
        }
        if (state.hasProperty(BlockStateProperties.LAYERS) && state.getValue(BlockStateProperties.LAYERS) > 1) {
            return id + " of " + state.getValue(BlockStateProperties.LAYERS)
                    + " layers; one snow item places one layer";
        }
        if (state.hasProperty(BlockStateProperties.CANDLES) && state.getValue(BlockStateProperties.CANDLES) > 1) {
            return id + " of " + state.getValue(BlockStateProperties.CANDLES) + " candles; one candle item places one";
        }
        if (state.hasProperty(BlockStateProperties.PICKLES) && state.getValue(BlockStateProperties.PICKLES) > 1) {
            return id + " of " + state.getValue(BlockStateProperties.PICKLES) + " sea pickles; one places one";
        }
        if (state.hasProperty(BlockStateProperties.EGGS) && state.getValue(BlockStateProperties.EGGS) > 1) {
            return id + " of " + state.getValue(BlockStateProperties.EGGS) + " eggs; one egg places one";
        }
        return null;
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
