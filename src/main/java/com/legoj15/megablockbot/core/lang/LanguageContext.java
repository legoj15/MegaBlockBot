package com.legoj15.megablockbot.core.lang;

import com.legoj15.megablockbot.core.Pos;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * What a language layer may read while interpreting one message (design 3.3). All Minecraft-free: positions
 * are plain ints, dimensions and item ids are strings, and {@link #items()} is backed by the game's item
 * registry at the edge. Nothing here can act on the world.
 *
 * @param items         item lookup over the registry ({@link ItemSearch})
 * @param dimension     the dimension id the body stands in, e.g. {@code minecraft:overworld}
 * @param ownerPos      where the owner stands, for {@code ~} coordinates and "come here"
 * @param botPos        where the body stands
 * @param statusSummary the current goal's progress, already rendered as one paragraph
 * @param blueprints    the build blueprint names the body knows
 * @param enchantments  the namespaced enchantment ids the game knows ({@code minecraft:efficiency}); empty means
 *                      "not known yet", and a layer then takes an enchantment id as the owner typed it
 */
public record LanguageContext(ItemSearch items, String dimension, Pos ownerPos, Pos botPos,
                              String statusSummary, List<String> blueprints, Set<String> enchantments) {
    public LanguageContext {
        Objects.requireNonNull(items, "items");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(ownerPos, "ownerPos");
        Objects.requireNonNull(botPos, "botPos");
        Objects.requireNonNull(statusSummary, "statusSummary");
        Objects.requireNonNull(blueprints, "blueprints");
        Objects.requireNonNull(enchantments, "enchantments");
        blueprints = List.copyOf(blueprints);
        enchantments = Set.copyOf(enchantments);
    }
}
