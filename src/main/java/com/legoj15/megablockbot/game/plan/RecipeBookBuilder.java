package com.legoj15.megablockbot.game.plan;

import com.legoj15.megablockbot.MegaBlockBot;
import com.legoj15.megablockbot.core.ItemKey;
import com.legoj15.megablockbot.core.planner.RecipeDef;
import com.legoj15.megablockbot.core.planner.SimpleRecipeBook;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;

/**
 * Builds the recipe book from every registered {@link RecipeSource} (design 4.2). Server thread only. One broken source
 * costs its own recipes and nothing else: each source writes into a buffer of its own, and the buffer is merged only
 * when the source returned normally.
 */
public final class RecipeBookBuilder {
    private static final int NAMES_SHOWN = 5;
    private static final int NOTES_AT_INFO = 5;

    private RecipeBookBuilder() {}

    /** What one build produced. */
    public record Built(SimpleRecipeBook book, Map<String, Integer> recipesBySource, int remainders, List<String> notes,
                 Map<String, Integer> unreadTypes) {}

    /** The data-loaded hook: builds from the registered sources, publishes, logs one summary line. */
    static void rebuild(MinecraftServer server) {
        try {
            long start = System.nanoTime();
            Built built = build(server, RecipeSources.all());
            RecipeBooks.publish(built.book());
            long millis = (System.nanoTime() - start) / 1_000_000;
            MegaBlockBot.LOGGER.info("Recipe book: {} recipes by source {}, {} remainders, {} notes, recipe types not read {}, built in {} ms",
                built.book().all().size(), built.recipesBySource(), built.remainders(), built.notes().size(),
                built.unreadTypes(), millis);
            // The first few at INFO, so that an operator can see why a recipe is missing without turning on debug.
            List<String> notes = built.notes();
            for (int i = 0; i < notes.size(); i++) {
                if (i < NOTES_AT_INFO) MegaBlockBot.LOGGER.info("Recipe book note: {}", notes.get(i));
                else MegaBlockBot.LOGGER.debug("Recipe book note: {}", notes.get(i));
            }
            if (notes.size() > NOTES_AT_INFO) {
                MegaBlockBot.LOGGER.info("Recipe book: {} more notes at debug level", notes.size() - NOTES_AT_INFO);
            }
        } catch (RuntimeException e) {
            // build() isolates every source; this is for a bug in the build itself. Keep the previous book.
            MegaBlockBot.LOGGER.error("Recipe book build failed; keeping the previous book", e);
        }
    }

    /** Builds a book from the given sources. Does not publish and does not log a summary: tests call it with fakes. */
    public static Built build(MinecraftServer server, List<RecipeSource> sources) {
        Map<String, RecipeDef> byId = new LinkedHashMap<>();
        Map<ItemKey, ItemKey> remainders = new LinkedHashMap<>();
        Map<String, Integer> perSource = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();
        Set<String> readTypes = new HashSet<>();

        for (RecipeSource source : sources) {
            String id = source.id();
            Buffer buffer = new Buffer();
            try {
                source.collect(server, buffer);
            } catch (RuntimeException | LinkageError e) {
                // LinkageError: a mod's source built against a class that is not there must not take the book down.
                MegaBlockBot.LOGGER.error("Recipe source '{}' failed; its recipes are left out of the book", id, e);
                notes.add(id + ": failed (" + e.getClass().getSimpleName() + "), none of its recipes are in the book");
                perSource.put(id, 0);
                continue;
            }
            readTypes.addAll(source.recipeTypes());

            int kept = 0;
            List<String> dropped = new ArrayList<>();
            for (RecipeDef recipe : buffer.recipes) {
                if (byId.putIfAbsent(recipe.id(), recipe) == null) kept++;
                else dropped.add(recipe.id());
            }
            perSource.put(id, kept);
            if (!dropped.isEmpty()) {
                notes.add(id + ": " + dropped.size() + " recipes dropped because an earlier one has the same id " + named(dropped));
            }

            List<String> conflicts = new ArrayList<>();
            buffer.remainders.forEach((item, left) -> {
                ItemKey earlier = remainders.putIfAbsent(item, left);
                if (earlier != null && !earlier.equals(left)) conflicts.add(item.toString());
            });
            if (!conflicts.isEmpty()) {
                notes.add(id + ": " + conflicts.size() + " items have a different remainder than an earlier source gave; the earlier one stays " + named(conflicts));
            }
            for (String note : buffer.notes) notes.add(id + ": " + note);
        }

        return new Built(new SimpleRecipeBook(new ArrayList<>(byId.values()), remainders), perSource, remainders.size(), notes,
            unreadTypes(server, readTypes));
    }

    // Every recipe of a type no working source reads, counted by type: what the planner cannot see.
    private static Map<String, Integer> unreadTypes(MinecraftServer server, Set<String> readTypes) {
        Map<String, Integer> unread = new TreeMap<>();
        for (RecipeHolder<?> holder : server.getRecipeManager().getRecipes()) {
            Recipe<?> recipe = holder.value();
            ResourceLocation key = BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType());
            String type = key == null ? "(unregistered recipe type)" : key.toString();
            if (!readTypes.contains(type)) unread.merge(type, 1, Integer::sum);
        }
        return unread;
    }

    private static String named(List<String> names) {
        StringBuilder text = new StringBuilder("(");
        for (int i = 0; i < names.size() && i < NAMES_SHOWN; i++) {
            if (i > 0) text.append(", ");
            text.append(names.get(i));
        }
        if (names.size() > NAMES_SHOWN) text.append(", and ").append(names.size() - NAMES_SHOWN).append(" more");
        return text.append(')').toString();
    }

    private static final class Buffer implements RecipeSource.Collector {
        final List<RecipeDef> recipes = new ArrayList<>();
        final Map<ItemKey, ItemKey> remainders = new LinkedHashMap<>();
        final List<String> notes = new ArrayList<>();

        @Override
        public void recipe(RecipeDef recipe) {
            recipes.add(Objects.requireNonNull(recipe, "recipe"));
        }

        @Override
        public void remainder(ItemKey item, ItemKey leftBehind) {
            remainders.putIfAbsent(Objects.requireNonNull(item, "item"), Objects.requireNonNull(leftBehind, "leftBehind"));
        }

        @Override
        public void note(String what) {
            notes.add(Objects.requireNonNull(what, "what"));
        }
    }
}
