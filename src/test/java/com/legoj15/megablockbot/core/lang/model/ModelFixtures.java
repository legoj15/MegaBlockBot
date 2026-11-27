package com.legoj15.megablockbot.core.lang.model;

import com.legoj15.megablockbot.core.ItemKey;
import com.legoj15.megablockbot.core.Pos;
import com.legoj15.megablockbot.core.lang.ItemSearch;
import com.legoj15.megablockbot.core.lang.LanguageContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** The fake game the layer tests (and the live probe) interpret messages in. */
final class ModelFixtures {
    private ModelFixtures() {}

    static final String DIMENSION = "minecraft:overworld";
    static final Pos OWNER_POS = new Pos(100, 64, -200);
    static final Pos BOT_POS = new Pos(103, 64, -198);
    static final String STATUS = "Working on: mine stone. 12 of 64 cobblestone so far.";
    static final List<String> BLUEPRINTS = List.of("cottage", "watch tower");
    static final Set<String> ENCHANTMENTS = Set.of("minecraft:efficiency", "minecraft:mending", "minecraft:sharpness",
            "minecraft:unbreaking", "minecraft:fortune", "minecraft:silk_touch", "minecraft:protection");

    /** The API key every test configures; it must reach the server's headers and nowhere else. */
    static final String SECRET = "sk-test-SECRET-9f8e7d6c5b4a";

    /** About fifty real registry ids with their display names. */
    static final Map<String, String> ITEMS = new LinkedHashMap<>();

    static {
        for (String tier : List.of("wooden", "stone", "golden", "iron", "diamond", "netherite")) {
            String name = Character.toUpperCase(tier.charAt(0)) + tier.substring(1);
            for (String tool : List.of("pickaxe", "axe", "shovel", "sword", "hoe")) {
                if (tier.equals("netherite") && !tool.equals("pickaxe")) continue;
                ITEMS.put(tier + "_" + tool, name + " " + Character.toUpperCase(tool.charAt(0)) + tool.substring(1));
            }
        }
        for (String piece : List.of("helmet", "chestplate", "leggings", "boots")) {
            ITEMS.put("iron_" + piece, "Iron " + Character.toUpperCase(piece.charAt(0)) + piece.substring(1));
            ITEMS.put("diamond_" + piece, "Diamond " + Character.toUpperCase(piece.charAt(0)) + piece.substring(1));
        }
        ITEMS.put("shield", "Shield");
        ITEMS.put("elytra", "Elytra");
        ITEMS.put("cobblestone", "Cobblestone");
        ITEMS.put("stone", "Stone");
        ITEMS.put("oak_log", "Oak Log");
        ITEMS.put("oak_planks", "Oak Planks");
        ITEMS.put("stick", "Stick");
        ITEMS.put("crafting_table", "Crafting Table");
        ITEMS.put("furnace", "Furnace");
        ITEMS.put("chest", "Chest");
        ITEMS.put("torch", "Torch");
        ITEMS.put("coal", "Coal");
        ITEMS.put("iron_ingot", "Iron Ingot");
        ITEMS.put("diamond", "Diamond");
        ITEMS.put("redstone", "Redstone Dust");
        ITEMS.put("bread", "Bread");
        ITEMS.put("cooked_beef", "Steak");
        ITEMS.put("white_bed", "White Bed");
        ITEMS.put("bucket", "Bucket");
        ITEMS.put("water_bucket", "Water Bucket");
        ITEMS.put("enchanted_book", "Enchanted Book");
        ITEMS.put("bow", "Bow");
        ITEMS.put("arrow", "Arrow");
    }

    /**
     * Item lookup over {@link #ITEMS}: exact ids with or without namespace, and free text scored as the Dice
     * coefficient over the id's and display name's words, a word counting as matched when it equals or starts
     * with a query word (the same shape the grammar's own test fake has).
     */
    static final class Items implements ItemSearch {
        @Override
        public Optional<ItemKey> byId(String id) {
            if (id == null) return Optional.empty();
            String path = id.toLowerCase(Locale.ROOT).strip();
            if (path.startsWith("minecraft:")) path = path.substring("minecraft:".length());
            return ITEMS.containsKey(path) ? Optional.of(ItemKey.of(path)) : Optional.empty();
        }

        @Override
        public List<ItemMatch> search(String text, int limit) {
            Set<String> query = words(text);
            List<ItemMatch> matches = new ArrayList<>();
            for (Map.Entry<String, String> entry : ITEMS.entrySet()) {
                Set<String> name = words(entry.getKey() + " " + entry.getValue());
                int matched = 0;
                for (String q : query) {
                    for (String n : name) {
                        if (n.equals(q) || n.startsWith(q)) {
                            matched++;
                            break;
                        }
                    }
                }
                double score = query.isEmpty() ? 0 : 2.0 * matched / (query.size() + name.size());
                if (score > 0) matches.add(new ItemMatch(ItemKey.of(entry.getKey()), entry.getValue(), score));
            }
            matches.sort((a, b) -> a.score() != b.score()
                    ? Double.compare(b.score(), a.score())
                    : a.key().id().compareTo(b.key().id()));
            return matches.size() <= limit ? matches : matches.subList(0, limit);
        }

        private static Set<String> words(String text) {
            Set<String> words = new LinkedHashSet<>();
            for (String word : text.toLowerCase(Locale.ROOT).strip().split("[\\s_]+")) {
                if (!word.isEmpty()) words.add(word);
            }
            return words;
        }
    }

    static LanguageContext context() {
        return context(ENCHANTMENTS);
    }

    static LanguageContext context(Set<String> enchantments) {
        return new LanguageContext(new Items(), DIMENSION, OWNER_POS, BOT_POS, STATUS, BLUEPRINTS, enchantments);
    }
}
