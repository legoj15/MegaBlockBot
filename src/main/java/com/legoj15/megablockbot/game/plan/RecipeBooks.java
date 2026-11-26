package com.legoj15.megablockbot.game.plan;

import com.legoj15.megablockbot.core.ItemKey;
import com.legoj15.megablockbot.core.planner.RecipeBook;
import com.legoj15.megablockbot.core.planner.RecipeDef;
import com.legoj15.megablockbot.core.planner.SimpleRecipeBook;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The current recipe book. A rebuild on the server thread swaps in a new immutable book, so any thread that holds the
 * previous one keeps a consistent view. The book and the facts derived from it are published together, in one
 * reference ({@link Published}), so a reader never sees a book with the previous build's station set: a reader that
 * needs both takes them from one {@link #published()} call, not from {@link #current()} and {@link #stations()} in turn
 * (a rebuild can land between those two).
 */
public final class RecipeBooks {
    /**
     * One build's book and what is derived from it.
     *
     * @param book     the recipes and remainders
     * @param stations every station some recipe in the book names, sorted; precomputed at publish time because the
     *                 snapshot collector asks on every call and must not walk thousands of recipes for it. A new
     *                 instance per build, so a caller may cache what it derives from it by identity.
     */
    public record Published(RecipeBook book, Set<ItemKey> stations) {}

    private static final Published NOTHING = new Published(SimpleRecipeBook.EMPTY, Set.of());
    private static final AtomicReference<Published> CURRENT = new AtomicReference<>(NOTHING);

    private RecipeBooks() {}

    /** The latest build: its book and the station set that belongs to it, read together. */
    public static Published published() {
        return CURRENT.get();
    }

    /** The latest book; {@link SimpleRecipeBook#EMPTY} before the first build and after the server stops. */
    public static RecipeBook current() {
        return CURRENT.get().book();
    }

    /** The latest build's stations (see {@link Published#stations}), for a caller that needs nothing else. */
    public static Set<ItemKey> stations() {
        return CURRENT.get().stations();
    }

    static void publish(RecipeBook book) {
        Set<ItemKey> stations = new TreeSet<>();
        for (RecipeDef recipe : book.all()) {
            if (recipe.station() != null) stations.add(recipe.station());
        }
        CURRENT.set(new Published(book, Collections.unmodifiableSet(stations)));
    }

    static void clear() {
        CURRENT.set(NOTHING);
    }
}
