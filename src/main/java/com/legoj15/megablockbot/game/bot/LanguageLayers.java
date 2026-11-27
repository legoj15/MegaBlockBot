package com.legoj15.megablockbot.game.bot;

import com.legoj15.megablockbot.core.lang.FixedGrammar;
import com.legoj15.megablockbot.core.lang.LanguageLayer;
import java.util.Objects;

/**
 * The language layer every body's runtime talks through (design 3.3). The fixed grammar until the model-backed layer
 * replaces it with {@link #set}; a body cannot tell which one it is talking to.
 */
public final class LanguageLayers {
    private static volatile LanguageLayer current = new FixedGrammar();

    private LanguageLayers() {}

    public static LanguageLayer current() {
        return current;
    }

    public static void set(LanguageLayer layer) {
        current = Objects.requireNonNull(layer, "layer");
    }
}
