package com.legoj15.megablockbot.core;

import java.util.Objects;

/**
 * An item (or block-as-item) by registry id, e.g. {@code minecraft:diamond}. The solver's key type {@code K}.
 * Identity only: components (enchantments, potion) are checked separately through
 * {@link com.legoj15.megablockbot.core.goal.ComponentCheck}.
 */
public record ItemKey(String id) implements Comparable<ItemKey> {
    public ItemKey {
        Objects.requireNonNull(id, "id");
    }

    /** {@code of("diamond")} is {@code minecraft:diamond}; a namespaced id is kept as written. */
    public static ItemKey of(String id) {
        return new ItemKey(id.indexOf(':') >= 0 ? id : "minecraft:" + id);
    }

    public String namespace() {
        return id.substring(0, id.indexOf(':'));
    }

    public String path() {
        return id.substring(id.indexOf(':') + 1);
    }

    @Override
    public int compareTo(ItemKey other) {
        return id.compareTo(other.id);
    }

    @Override
    public String toString() {
        return id;
    }
}
