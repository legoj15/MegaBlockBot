package com.legoj15.megablockbot.game.plan;

import com.legoj15.megablockbot.core.ItemKey;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;

/** The bridge from a registered item to the planner's key for it. */
public final class ItemKeys {
    private ItemKeys() {}

    public static ItemKey of(Item item) {
        return ItemKey.of(BuiltInRegistries.ITEM.getKey(item).toString());
    }
}
