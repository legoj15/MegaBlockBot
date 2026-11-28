package com.legoj15.megablockbot.game.resolver;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

/**
 * Where a block generates: the biomes whose own placed features place it, so an ore the mountain biomes keep to
 * themselves is theirs and a mod's biome modifier that adds one counts without being named. Only <b>bound</b> blocks
 * are held (see {@link #BIND_SHARE}); the map feeds the planner's charge for a mining source whose block the body's
 * own biome does not place.
 *
 * <p>Read the way {@link NaturalBlocks} reads what the generator places: every biome's placed features are flattened
 * through the game's own walk of their random selectors and patches, and each configured feature among them is
 * written out through its codec and every block id in the result collected. That over-counts the same way (a feature
 * also names the blocks it may replace), which the terrain's own blocks make harmless: they are never bound. It
 * misses what a structure places and what a feature's code places with nothing in its configuration — the gaps
 * {@code NaturalBlocks} fills by hand only for whether a block generates at all.
 */
final class BlockBiomes {
    // The same split as the ore ranges: the group a biome's tags say, the overworld's when none does.
    private enum Dimension { OVERWORLD, NETHER, END }

    /**
     * A block is bound unless every dimension group places it in at least this share of its biomes. Emerald ore is
     * placed in about ten of the overworld's sixty and is the mountains'; iron in all but the void, and in no biome
     * of the nether or the end, so it is the overworld's, which is all a body standing in the overworld needs to
     * know and what one standing in the nether must be told.
     */
    private static final double BIND_SHARE = 0.5;

    private BlockBiomes() {}

    /**
     * Block id to the sorted ids of the biomes whose features place it, holding only the bound blocks. A block the
     * terrain settings or the game's own code places is never bound; one no biome's features name is not here at all.
     */
    static Map<String, Set<String>> read(MinecraftServer server, BuildNotes notes) {
        RegistryAccess access = server.registryAccess();
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, access);
        Registry<Biome> biomes = access.registryOrThrow(Registries.BIOME);

        // The blocks that stand anywhere the world is made are not bound to any biome.
        Set<String> neverBound = NaturalBlocks.terrainPlaced(server, notes);
        neverBound.addAll(NaturalBlocks.placedByCode());

        Map<Dimension, Map<String, Set<String>>> placedBy = new EnumMap<>(Dimension.class);
        Map<Dimension, Integer> groupSizes = new EnumMap<>(Dimension.class);
        // Sixty biomes share most of their features, so each configured feature is written out once.
        Map<ConfiguredFeature<?, ?>, Set<String>> blocksOfFeature = new HashMap<>();

        for (Holder.Reference<Biome> biome : sortedBiomes(biomes)) {
            Dimension dimension = biome.is(BiomeTags.IS_NETHER) ? Dimension.NETHER
                : biome.is(BiomeTags.IS_END) ? Dimension.END : Dimension.OVERWORLD;
            groupSizes.merge(dimension, 1, Integer::sum);
            String biomeId = biome.key().location().toString();
            Map<String, Set<String>> inGroup = placedBy.computeIfAbsent(dimension, d -> new HashMap<>());
            for (HolderSet<PlacedFeature> step : biome.value().getGenerationSettings().features()) {
                for (Holder<PlacedFeature> holder : step) {
                    String placedId = holder.unwrapKey().map(k -> k.location().toString()).orElse("(inline feature)");
                    try {
                        for (ConfiguredFeature<?, ?> configured : (Iterable<ConfiguredFeature<?, ?>>) holder.value().getFeatures()::iterator) {
                            Set<String> blocks = blocksOf(configured, ops, blocksOfFeature, notes);
                            if (blocks == null) continue;
                            for (String block : blocks) inGroup.computeIfAbsent(block, k -> new TreeSet<>()).add(biomeId);
                        }
                    } catch (RuntimeException e) {
                        notes.skipped("placed features whose blocks could not be read", placedId);
                    }
                }
            }
        }

        // Every block some biome places, with all the biomes that do.
        Map<String, Set<String>> out = new TreeMap<>();
        for (Map<String, Set<String>> group : placedBy.values()) {
            for (Map.Entry<String, Set<String>> placing : group.entrySet()) {
                if (neverBound.contains(placing.getKey())) continue;
                out.computeIfAbsent(placing.getKey(), k -> new TreeSet<>()).addAll(placing.getValue());
            }
        }
        // A block that most biomes of every dimension place is to be had wherever a body stands: not bound.
        out.keySet().removeIf(block -> {
            for (Map.Entry<Dimension, Integer> group : groupSizes.entrySet()) {
                Set<String> placing = placedBy.getOrDefault(group.getKey(), Map.of()).get(block);
                if (placing == null || placing.size() < group.getValue() * BIND_SHARE) return false;
            }
            return true;
        });
        return out;
    }

    /** The registry's biomes by id, so the read walks them the same way whatever the registry's own order is. */
    private static List<Holder.Reference<Biome>> sortedBiomes(Registry<Biome> biomes) {
        List<Holder.Reference<Biome>> sorted = new ArrayList<>(biomes.holders().toList());
        sorted.sort(Comparator.comparing(h -> h.key().location().toString()));
        return sorted;
    }

    /**
     * The block ids a configured feature names, from its written-out form, cached per feature. Null when the feature
     * cannot be written out or throws, which costs that feature (a note here), never the section.
     */
    private static Set<String> blocksOf(ConfiguredFeature<?, ?> feature, RegistryOps<JsonElement> ops,
                                        Map<ConfiguredFeature<?, ?>, Set<String>> cache, BuildNotes notes) {
        if (cache.containsKey(feature)) return cache.get(feature);
        Set<String> blocks = null;
        try {
            JsonElement written = ConfiguredFeature.DIRECT_CODEC.encodeStart(ops, feature).result().orElse(null);
            if (written != null) {
                blocks = new TreeSet<>();
                NaturalBlocks.collect(written, blocks);
                // The air a feature's configuration may name places nothing a body can mine.
                blocks.remove(BuiltInRegistries.BLOCK.getKey(Blocks.AIR).toString());
            }
        } catch (RuntimeException e) {
            blocks = null;
        }
        if (blocks == null) notes.count("configured features that could not be written out to read which biomes place their blocks");
        cache.put(feature, blocks);
        return blocks;
    }
}
