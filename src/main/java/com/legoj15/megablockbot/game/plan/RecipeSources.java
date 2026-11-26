package com.legoj15.megablockbot.game.plan;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The registry other features add their {@link RecipeSource}s to during init. The book is built from these in
 * registration order, which is also the order that decides which of two recipes with one id survives.
 */
public final class RecipeSources {
    private static final List<RecipeSource> SOURCES = new ArrayList<>();

    private RecipeSources() {}

    /** @throws IllegalStateException when a source with that id is already registered: two would fight over its recipes */
    public static synchronized void register(RecipeSource source) {
        Objects.requireNonNull(source, "source");
        for (RecipeSource existing : SOURCES) {
            if (existing.id().equals(source.id())) {
                throw new IllegalStateException("a recipe source called '" + source.id() + "' is already registered");
            }
        }
        SOURCES.add(source);
    }

    /** A copy, in registration order. */
    static synchronized List<RecipeSource> all() {
        return List.copyOf(SOURCES);
    }
}
