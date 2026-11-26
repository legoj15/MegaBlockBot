package com.legoj15.megablockbot.game.resolver;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.legoj15.megablockbot.core.acquire.Source.OreRange;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
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
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;

/**
 * Where ore blocks generate, read from the placed features every biome carries (so ores that a mod's biome modifier
 * added count too). A placed feature is encoded to JSON through its placement codec and its {@code height_range}
 * placement is read from there, because the height provider's fields are private; the target blocks come from the
 * ore configuration, whose fields are public.
 */
final class OreRanges {
    private enum Dimension { OVERWORLD, NETHER, END }

    // The build limits an anchor resolves against: the dimension's own and its noise settings', as the game does.
    private record Limits(int minY, int depth) {}

    private record Contribution(String feature, int veinSize, OreRange range) {}

    private OreRanges() {}

    /** Ore range by block id. A block no readable ore feature generates is absent. */
    static Map<String, OreRange> read(MinecraftServer server, BuildNotes notes) {
        RegistryAccess access = server.registryAccess();
        DynamicOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, access);
        Registry<Biome> biomes = access.registryOrThrow(Registries.BIOME);

        Map<Dimension, Limits> limits = new HashMap<>();
        for (Dimension d : Dimension.values()) limits.put(d, limitsOf(access, d, notes));

        List<Holder.Reference<Biome>> sorted = new ArrayList<>(biomes.holders().toList());
        sorted.sort(Comparator.comparing(h -> h.key().location().toString()));

        Map<String, List<Contribution>> byBlock = new TreeMap<>();
        Set<String> seen = new HashSet<>();
        Set<PlacedFeature> seenInline = new HashSet<>();
        for (Holder.Reference<Biome> biome : sorted) {
            Dimension dimension = biome.is(BiomeTags.IS_NETHER) ? Dimension.NETHER
                : biome.is(BiomeTags.IS_END) ? Dimension.END : Dimension.OVERWORLD;
            for (HolderSet<PlacedFeature> step : biome.value().getGenerationSettings().features()) {
                for (Holder<PlacedFeature> holder : step) {
                    String id = holder.unwrapKey().map(k -> k.location().toString()).orElse(null);
                    boolean first = id != null ? seen.add(id + "@" + dimension) : seenInline.add(holder.value());
                    if (!first) continue;
                    try {
                        readFeature(id != null ? id : "(inline feature)", holder.value(), limits.get(dimension), ops, byBlock, notes);
                    } catch (RuntimeException e) {
                        notes.skipped("placed features that failed to read", id != null ? id : "(inline feature)");
                    }
                }
            }
        }

        Map<String, OreRange> out = new TreeMap<>();
        for (Map.Entry<String, List<Contribution>> e : byBlock.entrySet()) {
            int min = Integer.MAX_VALUE;
            int max = Integer.MIN_VALUE;
            for (Contribution c : e.getValue()) {
                min = Math.min(min, c.range().minY());
                max = Math.max(max, c.range().maxY());
            }
            // The best Y is the best of the largest feature: the biggest veins, then the widest range, then the id.
            Contribution largest = e.getValue().stream()
                .max(Comparator.comparingInt(Contribution::veinSize)
                    .thenComparingInt(c -> c.range().maxY() - c.range().minY())
                    .thenComparing(Contribution::feature, Comparator.reverseOrder()))
                .orElseThrow();
            out.put(e.getKey(), new OreRange(min, max, largest.range().bestY()));
        }

        // Blocks the ore tag lists but no feature produced: noted, so a lint reader knows the gap is ours.
        TagKey<Block> ores = TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath("c", "ores"));
        for (Holder<Block> holder : BuiltInRegistries.BLOCK.getTagOrEmpty(ores)) {
            String id = BuiltInRegistries.BLOCK.getKey(holder.value()).toString();
            if (!out.containsKey(id)) notes.skipped("ore blocks (tag c:ores) with no readable placed feature", id);
        }
        return out;
    }

    private static void readFeature(String id, PlacedFeature feature, Limits limits, DynamicOps<JsonElement> ops,
                                    Map<String, List<Contribution>> byBlock, BuildNotes notes) {
        Set<String> blocks = new TreeSet<>();
        int veinSize = 0;
        for (ConfiguredFeature<?, ?> configured : (Iterable<ConfiguredFeature<?, ?>>) feature.getFeatures()::iterator) {
            if (!(configured.config() instanceof OreConfiguration ore)) continue;
            veinSize = Math.max(veinSize, ore.size);
            for (OreConfiguration.TargetBlockState target : ore.targetStates) {
                blocks.add(BuiltInRegistries.BLOCK.getKey(target.state.getBlock()).toString());
            }
        }
        if (blocks.isEmpty()) return; // not an ore-type feature

        JsonElement placement = PlacementModifier.CODEC.listOf().encodeStart(ops, feature.placement()).result().orElse(null);
        OreRange range = placement == null ? null : heightRange(placement, limits);
        if (range == null) {
            notes.skipped("ore features without a readable height range", id);
            return;
        }
        Contribution contribution = new Contribution(id, veinSize, range);
        for (String block : blocks) byBlock.computeIfAbsent(block, k -> new ArrayList<>()).add(contribution);
    }

    private static OreRange heightRange(JsonElement placement, Limits limits) {
        if (!placement.isJsonArray()) return null;
        for (JsonElement m : placement.getAsJsonArray()) {
            if (!m.isJsonObject()) continue;
            JsonObject modifier = m.getAsJsonObject();
            if (!LootEval.strip(LootEval.string(modifier, "type")).equals("height_range")) continue;
            JsonElement height = modifier.get("height");
            return height != null && height.isJsonObject() ? provider(height.getAsJsonObject(), limits) : null;
        }
        return null;
    }

    // A height provider is either a bare anchor (a constant) or an object with a type.
    private static OreRange provider(JsonObject height, Limits limits) {
        if (!height.has("type")) {
            Integer y = anchor(height, limits);
            return y == null ? null : range(y, y, y, limits);
        }
        switch (LootEval.strip(LootEval.string(height, "type"))) {
            case "constant": {
                Integer y = anchor(height.get("value"), limits);
                return y == null ? null : range(y, y, y, limits);
            }
            case "uniform":
            case "trapezoid": {
                // Both peak in the middle of the range (a trapezoid's plateau is centred), which is the best Y.
                Integer min = anchor(height.get("min_inclusive"), limits);
                Integer max = anchor(height.get("max_inclusive"), limits);
                if (min == null || max == null || min > max) return null;
                return range(min, max, Math.floorDiv(min + max, 2), limits);
            }
            case "biased_to_bottom":
            case "very_biased_to_bottom": {
                Integer min = anchor(height.get("min_inclusive"), limits);
                Integer max = anchor(height.get("max_inclusive"), limits);
                if (min == null || max == null || min > max) return null;
                return range(min, max, min, limits);
            }
            default:
                return null;
        }
    }

    // A feature may be written wider than the world (a triangle from y=-144, emeralds up to y=480); the range a miner
    // can use stops at the build limits. The best Y is the feature's own peak, pulled inside the world.
    private static OreRange range(int min, int max, int best, Limits limits) {
        int floor = limits.minY();
        int ceiling = limits.minY() + limits.depth() - 1;
        int clampedMin = Math.max(min, floor);
        int clampedMax = Math.min(max, ceiling);
        if (clampedMin > clampedMax) return null;
        return new OreRange(clampedMin, clampedMax, Math.max(clampedMin, Math.min(clampedMax, best)));
    }

    private static Integer anchor(JsonElement e, Limits limits) {
        if (e == null || !e.isJsonObject()) return null;
        JsonObject o = e.getAsJsonObject();
        if (o.has("absolute")) return o.get("absolute").getAsInt();
        if (o.has("above_bottom")) return limits.minY() + o.get("above_bottom").getAsInt();
        if (o.has("below_top")) return limits.depth() - 1 + limits.minY() - o.get("below_top").getAsInt();
        return null;
    }

    private static Limits limitsOf(RegistryAccess access, Dimension d, BuildNotes notes) {
        ResourceKey<DimensionType> type = switch (d) {
            case OVERWORLD -> BuiltinDimensionTypes.OVERWORLD;
            case NETHER -> BuiltinDimensionTypes.NETHER;
            case END -> BuiltinDimensionTypes.END;
        };
        ResourceKey<NoiseGeneratorSettings> noise = switch (d) {
            case OVERWORLD -> NoiseGeneratorSettings.OVERWORLD;
            case NETHER -> NoiseGeneratorSettings.NETHER;
            case END -> NoiseGeneratorSettings.END;
        };
        DimensionType dimension = access.registryOrThrow(Registries.DIMENSION_TYPE).get(type);
        NoiseGeneratorSettings settings = access.registryOrThrow(Registries.NOISE_SETTINGS).get(noise);
        if (dimension == null || settings == null) {
            notes.count("build limits of the " + d.name().toLowerCase() + " not found, the overworld's assumed");
            return new Limits(-64, 384);
        }
        // WorldGenerationContext takes the higher floor and the smaller height of the dimension and its generator.
        return new Limits(Math.max(dimension.minY(), settings.noiseSettings().minY()),
            Math.min(dimension.height(), settings.noiseSettings().height()));
    }
}
