package com.legoj15.megablockbot.game.resolver;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;

/**
 * The blocks the world generator puts into the world: what a bot can go and look for. Every block has a loot table,
 * so every block is "a source" of what it drops, but a campfire, a bookshelf, a crafting table or a line of
 * redstone wire stands only inside a structure or where someone placed it, and a plan that sets out to find one
 * walks until its search is spent (found by live runs: furnace fuel was planned as charcoal from a campfire, and a
 * quarry's redstone as dust from redstone wire).
 *
 * <p>Read from the data the generator itself runs on, so a mod's ores and plants are covered without naming them:
 * every configured feature and every dimension's terrain settings is written out through its own codec, and each
 * block id that appears in the result counts. That over-counts a little (a feature also names the blocks it may
 * replace, which are generated blocks anyway). It misses what a feature places from its own code with nothing in its
 * configuration ({@link #PLACED_BY_CODE}). Structures are not read: a block that only a structure holds is not one to
 * go looking for, as a structure's chest is not.
 */
final class NaturalBlocks {
    private NaturalBlocks() {}

    /**
     * Blocks the world makes from code, with nothing in a feature's configuration to read, each with where it comes
     * from in the 1.21.1 sources. The planner does not go looking for a block that is neither read nor listed here,
     * so a line missing from this list costs a real source: add one when a goal says its item has none.
     */
    private static final Map<String, String> PLACED_BY_CODE = Map.ofEntries(
        Map.entry("minecraft:obsidian", "water running onto a lava source (LiquidBlock); EndSpikeFeature"),
        Map.entry("minecraft:ice", "SnowAndFreezeFeature (freeze_top_layer)"),
        Map.entry("minecraft:snow", "SnowAndFreezeFeature (freeze_top_layer), and snowfall"),
        Map.entry("minecraft:glowstone", "GlowstoneFeature (glowstone_blob)"),
        Map.entry("minecraft:vine", "VinesFeature, and the vine decorators of jungle and swamp trees"),
        Map.entry("minecraft:dripstone_block", "DripstoneClusterFeature and LargeDripstoneFeature"),
        Map.entry("minecraft:pointed_dripstone", "PointedDripstoneFeature and DripstoneClusterFeature"),
        Map.entry("minecraft:bone_block", "FossilFeature (its structure templates)"),
        Map.entry("minecraft:sculk", "SculkPatchFeature"),
        Map.entry("minecraft:kelp_plant", "KelpFeature (the stalk under the kelp it names)"),
        Map.entry("minecraft:tall_seagrass", "SeagrassFeature"),
        Map.entry("minecraft:chorus_flower", "ChorusPlantFeature"),
        Map.entry("minecraft:basalt", "BasaltPillarFeature and BasaltColumnsFeature"));

    /** The ids of every block the generator places, sorted. Never empty on a working server. */
    static Set<String> read(MinecraftServer server, BuildNotes notes) {
        Set<String> found = new TreeSet<>(PLACED_BY_CODE.keySet());
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, server.registryAccess());
        scan(server, Registries.CONFIGURED_FEATURE, ConfiguredFeature.DIRECT_CODEC, ops, found, notes, "configured features");
        scan(server, Registries.NOISE_SETTINGS, NoiseGeneratorSettings.DIRECT_CODEC, ops, found, notes, "terrain settings");
        found.remove(BuiltInRegistries.BLOCK.getKey(Blocks.AIR).toString());
        return found;
    }

    private static <T> void scan(MinecraftServer server, ResourceKey<Registry<T>> key, Codec<T> codec,
                                 RegistryOps<JsonElement> ops, Set<String> found, BuildNotes notes, String what) {
        Registry<T> registry = server.registryAccess().registryOrThrow(key);
        for (T entry : registry) {
            JsonElement written = codec.encodeStart(ops, entry).result().orElse(null);
            if (written == null) {
                notes.count(what + " that could not be written out to look for their blocks");
                continue;
            }
            collect(written, found);
        }
    }

    /** Every string anywhere in the tree that is the id of a registered block. */
    private static void collect(JsonElement element, Set<String> found) {
        if (element.isJsonPrimitive()) {
            if (!element.getAsJsonPrimitive().isString()) return;
            // A written id carries its namespace; a bare word is the value of a block property ("bell", an instrument).
            String text = element.getAsString();
            if (!text.contains(":")) return;
            ResourceLocation id = ResourceLocation.tryParse(text);
            if (id != null && BuiltInRegistries.BLOCK.containsKey(id)) found.add(id.toString());
        } else if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            for (JsonElement child : array) collect(child, found);
        } else if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            for (Map.Entry<String, JsonElement> child : object.entrySet()) collect(child.getValue(), found);
        }
    }
}
