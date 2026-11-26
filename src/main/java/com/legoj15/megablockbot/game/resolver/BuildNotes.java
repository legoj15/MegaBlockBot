package com.legoj15.megablockbot.game.resolver;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * What an index build could not cover. A bad table or feature is skipped, counted and named here; a build never throws
 * for bad data. Server thread only.
 */
final class BuildNotes {
    private static final int NAMES_SHOWN = 5;

    private final Map<String, Integer> counted = new TreeMap<>();
    private final Map<String, TreeSet<String>> named = new TreeMap<>();
    private final List<String> fixed = new ArrayList<>();

    /** Counts one occurrence of a limitation: "loot function furnace_smelt ignored" becomes one line with its total. */
    void count(String message) {
        counted.merge(message, 1, Integer::sum);
    }

    /** Names something that was skipped under a category: "loot tables that failed to encode: a, b, c (and 4 more)". */
    void skipped(String category, String name) {
        named.computeIfAbsent(category, k -> new TreeSet<>()).add(name);
    }

    /** A line that is always shown, such as a standing limitation of the whole approach. */
    void always(String line) {
        fixed.add(line);
    }

    List<String> lines() {
        List<String> lines = new ArrayList<>(fixed);
        counted.forEach((message, count) -> lines.add(message + " (x" + count + ")"));
        named.forEach((category, names) -> {
            StringBuilder line = new StringBuilder(category).append(": ").append(names.size()).append(" (");
            int shown = 0;
            for (String name : names) {
                if (shown == NAMES_SHOWN) break;
                if (shown++ > 0) line.append(", ");
                line.append(name);
            }
            if (names.size() > NAMES_SHOWN) line.append(", and ").append(names.size() - NAMES_SHOWN).append(" more");
            lines.add(line.append(')').toString());
        });
        return lines;
    }
}
