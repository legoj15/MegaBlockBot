package com.legoj15.megablockbot.game.safety;

import com.legoj15.megablockbot.MegaBlockBot;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Clearable;
import net.minecraft.world.Container;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Which blocks hold a player's things or a villager's job: beds, containers, workstations. Decided per block TYPE, once,
 * into an immutable table, so the answer needs no level and is safe to ask from any thread (a path search asks it for
 * every block it might break).
 *
 * <ul>
 *   <li>bed: a {@link BedBlock}, or anything in the {@code beds} tag.</li>
 *   <li>container: a block whose block entity is a vanilla {@link Container}, found by building one probe block entity
 *       per block type. A block entity that holds items without being a Container (a campfire's food) counts too, via
 *       {@link Clearable}, the interface vanilla uses for "has contents that /setblock must clear". Inventories a mod
 *       exposes only through the loader's capabilities are invisible here: the game package may not name loader types.</li>
 *   <li>workstation: a villager job site, taken from the game's point-of-interest data: every POI type that some
 *       profession holds or can acquire as its job site, and every block state that POI type matches. No block list.</li>
 * </ul>
 *
 * A barrel, a smoker and a brewing stand are both a container and a job site; they report as containers, and the
 * lectern (a job site whose block entity is not a Container) reports as a workstation.
 *
 * <p>The table is rebuilt after every data load, because tags can change on {@code /reload}.
 */
final class BlockKinds {
    static final int NONE = 0;
    static final int BED = 1;
    static final int CONTAINER = 2;
    static final int WORKSTATION = 3;

    private static volatile Reference2IntOpenHashMap<Block> table;

    private BlockKinds() {}

    /** The kind of {@code block}: one of the constants above. Allocation-free; any thread. */
    static int of(Block block) {
        Reference2IntOpenHashMap<Block> kinds = table;
        if (kinds == null) kinds = rebuild();
        return kinds.getInt(block);
    }

    /** Builds the table from the registries as they are now. Safe to call from any thread; the last one wins. */
    static synchronized Reference2IntOpenHashMap<Block> rebuild() {
        Reference2IntOpenHashMap<Block> kinds = new Reference2IntOpenHashMap<>();
        kinds.defaultReturnValue(NONE);
        Set<Block> jobSites = jobSiteBlocks();
        int beds = 0;
        int containers = 0;
        int workstations = 0;
        for (Block block : BuiltInRegistries.BLOCK) {
            int kind = classify(block, jobSites);
            if (kind == NONE) continue;
            kinds.put(block, kind);
            if (kind == BED) beds++;
            else if (kind == CONTAINER) containers++;
            else workstations++;
        }
        table = kinds;
        MegaBlockBot.LOGGER.debug("Block safety kinds: {} bed, {} container and {} workstation block types", beds, containers, workstations);
        return kinds;
    }

    private static int classify(Block block, Set<Block> jobSites) {
        if (block instanceof BedBlock || block.defaultBlockState().is(BlockTags.BEDS)) return BED;
        // A job site first: a barrel, smoker, blast furnace or brewing stand is also a container, and an allowance
        // lifts the container rule but never the workstation rule.
        if (jobSites.contains(block)) return WORKSTATION;
        BlockEntity probe = probe(block);
        if (probe instanceof Container) return CONTAINER;
        if (probe instanceof Clearable) return CONTAINER;
        return NONE;
    }

    /** A throwaway block entity of this block's type, or null for a block with none (or one that will not build). */
    private static BlockEntity probe(Block block) {
        if (!(block instanceof EntityBlock holder)) return null;
        try {
            return holder.newBlockEntity(BlockPos.ZERO, block.defaultBlockState());
        } catch (RuntimeException e) {
            // A mod's block entity that cannot be built outside a world: it stays unclassified, and is not fatal.
            MegaBlockBot.LOGGER.warn("Could not probe the block entity of {}: {}", BuiltInRegistries.BLOCK.getKey(block), e.toString());
            return null;
        }
    }

    private static Set<Block> jobSiteBlocks() {
        List<VillagerProfession> professions = BuiltInRegistries.VILLAGER_PROFESSION.stream().toList();
        Set<Block> blocks = new HashSet<>();
        BuiltInRegistries.POINT_OF_INTEREST_TYPE.holders().forEach(poi -> {
            for (VillagerProfession profession : professions) {
                if (profession.heldJobSite().test(poi) || profession.acquirableJobSite().test(poi)) {
                    for (BlockState state : poi.value().matchingStates()) blocks.add(state.getBlock());
                    return;
                }
            }
        });
        return blocks;
    }
}
