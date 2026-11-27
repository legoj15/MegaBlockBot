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
 * so every block is "a source" of what it drops, but a campfire, a bookshelf or a crafting table stands only inside
 * a structure or where someone placed it, and a plan that sets out to find one walks until its search is spent
 * (found by a live run: furnace fuel was planned as charcoal from a campfire).
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
     * Blocks a feature places from its own code, with no block id in its configuration to read, each with the
     * feature. Only blocks that a recipe also makes need a line here (the planner asks about generation only for
     * those, see {@code GatherCosts}), which keeps this to what the 1.21.1 sources show.
     */
    private static final Map<String, String> PLACED_BY_CODE = Map.of(
        "minecraft:glowstone", "GlowstoneFeature (glowstone_blob)",
        "minecraft:snow", "SnowAndFreezeFeature (freeze_top_layer), and snowfall",
        "minecraft:dripstone_block", "DripstoneClusterFeature and LargeDripstoneFeature",
        "minecraft:bone_block", "FossilFeature (its structure templates)");

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
