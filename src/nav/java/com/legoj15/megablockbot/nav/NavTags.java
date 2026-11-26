// SPDX-License-Identifier: LGPL-3.0-or-later
package com.legoj15.megablockbot.nav;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;

/**
 * Item tags the engine reads, shipped under the module's namespace in {@code data/megablockbot_nav/tags/item/}.
 * They replace PlayerEngine's {@code EMPTY_BUCKETS} and {@code WATER_BUCKETS}; a datapack can extend them.
 */
public final class NavTags {
    public static final TagKey<Item> EMPTY_BUCKETS = TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath(NavRuntime.MOD_ID, "empty_buckets"));
    public static final TagKey<Item> WATER_BUCKETS = TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath(NavRuntime.MOD_ID, "water_buckets"));

    private NavTags() {}
}
