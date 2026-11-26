package com.legoj15.megablockbot.core.goal;

import com.legoj15.megablockbot.core.snapshot.StackFacts;
import java.util.Map;

/**
 * What a stack must carry beyond its identity (design 3.4, Component goals): a mending book is
 * {@code enchanted_book} with {@code minecraft:mending >= 1}. Data, not a lambda, so it saves with the goal.
 *
 * @param minEnchantments enchantment id to the lowest acceptable level; all must be present
 * @param potion          required potion id, or empty for any
 */
public record ComponentCheck(Map<String, Integer> minEnchantments, String potion) {
    public ComponentCheck {
        minEnchantments = Map.copyOf(minEnchantments);
        potion = potion == null ? "" : potion;
    }

    public static ComponentCheck enchantment(String id, int minLevel) {
        return new ComponentCheck(Map.of(id, minLevel), "");
    }

    public boolean test(StackFacts facts) {
        for (Map.Entry<String, Integer> e : minEnchantments.entrySet()) {
            if (facts.enchantments().getOrDefault(e.getKey(), 0) < e.getValue()) return false;
        }
        return potion.isEmpty() || potion.equals(facts.potion());
    }
}
