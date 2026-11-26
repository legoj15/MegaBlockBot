package com.legoj15.megablockbot.core.snapshot;

import java.util.Map;

/**
 * Component facts of one carried stack, for goals that care about more than identity.
 *
 * @param count        stack size
 * @param enchantments enchantment id to level; for an enchanted book these are its stored enchantments
 * @param potion       potion id, or empty
 * @param extra        anything else a check needs, by a key the collector and the check agree on
 */
public record StackFacts(int count, Map<String, Integer> enchantments, String potion, Map<String, String> extra) {
    public StackFacts {
        enchantments = Map.copyOf(enchantments);
        potion = potion == null ? "" : potion;
        extra = Map.copyOf(extra);
    }

    public static StackFacts plain(int count) {
        return new StackFacts(count, Map.of(), "", Map.of());
    }
}
