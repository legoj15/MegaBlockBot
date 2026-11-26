package com.legoj15.megablockbot.core.planner;

import com.legoj15.megablockbot.core.ItemKey;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** A {@link RecipeBook} over a fixed list: what the game-side builder publishes and what planner tests build by hand. */
public final class SimpleRecipeBook implements RecipeBook {
    public static final SimpleRecipeBook EMPTY = new SimpleRecipeBook(List.of(), Map.of());

    private final List<RecipeDef> all;
    private final Map<String, RecipeDef> byId;
    private final Map<ItemKey, List<RecipeDef>> byResult;
    private final Map<ItemKey, ItemKey> remainders;

    /**
     * @param recipes    any order; kept sorted by id so that plans do not depend on registry iteration order
     * @param remainders item to what using it up leaves behind
     * @throws IllegalArgumentException when two recipes share an id
     */
    public SimpleRecipeBook(List<RecipeDef> recipes, Map<ItemKey, ItemKey> remainders) {
        List<RecipeDef> sorted = new ArrayList<>(recipes);
        sorted.sort(Comparator.comparing(RecipeDef::id));
        Map<String, RecipeDef> ids = new LinkedHashMap<>();
        Map<ItemKey, List<RecipeDef>> results = new LinkedHashMap<>();
        for (RecipeDef recipe : sorted) {
            if (ids.put(recipe.id(), recipe) != null) throw new IllegalArgumentException("two recipes share the id " + recipe.id());
            results.computeIfAbsent(recipe.result(), key -> new ArrayList<>()).add(recipe);
        }
        results.replaceAll((key, list) -> List.copyOf(list));
        this.all = List.copyOf(sorted);
        this.byId = Map.copyOf(ids);
        this.byResult = Map.copyOf(results);
        this.remainders = Map.copyOf(remainders);
    }

    @Override
    public List<RecipeDef> producers(ItemKey item) {
        return byResult.getOrDefault(item, List.of());
    }

    @Override
    public Optional<RecipeDef> byId(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public ItemKey remainder(ItemKey item) {
        return remainders.get(item);
    }

    @Override
    public List<RecipeDef> all() {
        return all;
    }
}
