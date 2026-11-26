package com.legoj15.megablockbot.core.lang;

import com.legoj15.megablockbot.core.ItemKey;
import java.util.List;
import java.util.Optional;

/**
 * Item lookup for the language layer (design 3.3, target resolution). Implemented later by Minecraft code over
 * the item registry; in tests it is backed by a fake. The registry id is the lookup key everywhere: an
 * {@link ItemKey}, never a display name.
 */
public interface ItemSearch {

    /** Exact id, with or without namespace ({@code "stone_pickaxe"}, {@code "minecraft:stone_pickaxe"}). */
    Optional<ItemKey> byId(String id);

    /** Best matches for free text ({@code "stone pick"}, {@code "Stone Pickaxe"}), best first, at most limit. */
    List<ItemMatch> search(String text, int limit);

    /**
     * One candidate from a free-text search.
     *
     * @param key         the item the layer would commit to
     * @param displayName what the owner typed, back to them by display name
     * @param score       ranking score, best first; layers compare scores between candidates to spot ambiguity
     */
    record ItemMatch(ItemKey key, String displayName, double score) {}
}
