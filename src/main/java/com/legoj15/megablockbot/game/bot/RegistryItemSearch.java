package com.legoj15.megablockbot.game.bot;

import com.legoj15.megablockbot.core.ItemKey;
import com.legoj15.megablockbot.core.lang.ItemSearch;
import com.legoj15.megablockbot.game.plan.ItemKeys;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * The language layer's item lookup over the game's item registry (design 3.3, target resolution). The registry id is the
 * answer everywhere; a display name only helps the owner find it.
 *
 * <p>Scoring is the Dice coefficient over word sets: the typed words against the words of the item's id path and of its
 * display name together, a typed word counting as matched when it equals, starts, or is started by a name word.
 * {@code stone pick} reaches {@code stone_pickaxe} and {@code pickaxe} ties every pickaxe, which is the ambiguity the
 * grammar's margin rule exists to catch. Scores are 0..1.
 */
public final class RegistryItemSearch implements ItemSearch {

    @Override
    public Optional<ItemKey> byId(String id) {
        if (id == null || id.isBlank()) return Optional.empty();
        String text = id.strip().toLowerCase(Locale.ROOT);
        ResourceLocation location = ResourceLocation.tryParse(text.indexOf(':') >= 0 ? text : "minecraft:" + text);
        if (location == null) return Optional.empty();
        Optional<Item> item = BuiltInRegistries.ITEM.getOptional(location);
        // Air is in the registry as the "no item" item: never something to get.
        if (item.isEmpty() || item.get() == Items.AIR) return Optional.empty();
        return Optional.of(ItemKeys.of(item.get()));
    }

    @Override
    public List<ItemMatch> search(String text, int limit) {
        Set<String> query = words(text);
        if (query.isEmpty() || limit <= 0) return List.of();
        List<ItemMatch> matches = new ArrayList<>();
        for (Item item : BuiltInRegistries.ITEM) {
            if (item == Items.AIR) continue;
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
            String displayName = displayName(item, id);
            Set<String> name = words(id.getPath() + " " + displayName);
            int matched = 0;
            for (String typed : query) {
                for (String word : name) {
                    if (word.equals(typed) || word.startsWith(typed) || typed.startsWith(word)) {
                        matched++;
                        break;
                    }
                }
            }
            if (matched == 0) continue;
            double score = 2.0 * matched / (query.size() + name.size());
            matches.add(new ItemMatch(ItemKeys.of(item), displayName, score));
        }
        // Best first; equal scores by id, so the same words always give the same order.
        matches.sort((a, b) -> a.score() != b.score()
                ? Double.compare(b.score(), a.score())
                : a.key().id().compareTo(b.key().id()));
        return matches.size() <= limit ? matches : List.copyOf(matches.subList(0, limit));
    }

    /** The item's English name, or its id path when the game has no text for it (a modded item without a language file). */
    private static String displayName(Item item, ResourceLocation id) {
        try {
            String name = item.getDescription().getString();
            if (!name.isBlank() && !name.equals(item.getDescriptionId())) return name;
        } catch (RuntimeException e) {
            // A mod's item with a broken description: the id still names it.
        }
        return id.getPath().replace('_', ' ');
    }

    private static Set<String> words(String text) {
        Set<String> words = new LinkedHashSet<>();
        for (String word : text.toLowerCase(Locale.ROOT).strip().split("[\\s_]+")) {
            if (!word.isEmpty()) words.add(word);
        }
        return words;
    }
}
