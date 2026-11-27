package com.legoj15.megablockbot.core.acquire;

import com.legoj15.megablockbot.core.ItemKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The obtainability lint (design 4.3) over an {@link AcquisitionIndex}: which items a survival player cannot get at
 * all. An item with a source counts as with a source; else a craftable one counts as craftable only; else an item
 * with a {@link KnownWay known way} counts as obtainable with no strategy yet (a player can get it, the planner
 * cannot); only the rest is unobtainable. An item that is craftable only from unobtainable inputs is deliberately
 * not chased here; that is a recipe-graph question for the planner.
 *
 * @param namespace the namespace the counts and the id lists are limited to, or null for every namespace
 * @param noStrategy the ids of the in-scope items that have a known way but no source and no recipe, sorted
 * @param knownWays the ways of those items, ordered by item then by way id
 * @param unobtainable the ids of the unobtainable items in scope, sorted
 */
public record LintReport(String namespace, int total, int withSource, int craftableOnly, List<ItemKey> noStrategy,
                         List<ItemKey> unobtainable, List<KnownWay> knownWays, Map<String, Integer> unplannableRecipeTypes,
                         List<String> notes) {

    public static LintReport of(AcquisitionIndex index, String namespace) {
        int total = 0;
        int withSource = 0;
        int craftableOnly = 0;
        List<ItemKey> noStrategy = new ArrayList<>();
        List<KnownWay> ways = new ArrayList<>();
        List<ItemKey> unobtainable = new ArrayList<>();
        for (ItemKey item : index.items()) {
            if (namespace != null && !item.namespace().equals(namespace)) continue;
            total++;
            if (!index.sources(item).isEmpty()) withSource++;
            else if (index.craftable().contains(item)) craftableOnly++;
            else if (!index.knownWays(item).isEmpty()) {
                noStrategy.add(item);
                ways.addAll(index.knownWays(item));
            } else unobtainable.add(item);
        }
        return new LintReport(namespace, total, withSource, craftableOnly, List.copyOf(noStrategy),
            List.copyOf(unobtainable), List.copyOf(ways), index.unplannableRecipeTypes(), index.notes());
    }

    /** Counts, unplannable recipe types and notes: everything but the item lists. */
    public List<String> summaryLines() {
        List<String> lines = new ArrayList<>();
        lines.add("Obtainability lint (" + (namespace == null ? "all namespaces" : "namespace " + namespace) + "): "
            + total + " items, " + withSource + " with a source, " + craftableOnly + " craftable only, "
            + noStrategy.size() + " obtainable with no strategy yet, " + unobtainable.size() + " unobtainable");
        if (!unplannableRecipeTypes.isEmpty()) {
            StringBuilder types = new StringBuilder("Recipe types no adapter reads yet:");
            unplannableRecipeTypes.forEach((type, count) -> types.append(' ').append(type).append(" x").append(count));
            lines.add(types.toString());
        }
        for (String note : notes) lines.add("Note: " + note);
        return lines;
    }

    /** The unobtainable ids, one per line, capped at {@code maxItemLines} with an "and N more" line. */
    public List<String> itemLines(int maxItemLines) {
        List<String> lines = new ArrayList<>();
        int shown = Math.min(maxItemLines, unobtainable.size());
        for (int i = 0; i < shown; i++) lines.add(unobtainable.get(i).id());
        if (shown < unobtainable.size()) lines.add("and " + (unobtainable.size() - shown) + " more");
        return lines;
    }

    /** The whole report as a file: summary, then every no-strategy item with its ways, then every unobtainable id. */
    public String fullText() {
        StringBuilder out = new StringBuilder();
        for (String line : summaryLines()) out.append(line).append('\n');
        out.append("Obtainable with no strategy yet:\n");
        for (ItemKey item : noStrategy) {
            out.append(item.id()).append(':');
            boolean first = true;
            for (KnownWay way : knownWays) {
                if (!way.item().equals(item)) continue;
                out.append(first ? " " : ", ").append(way.way()).append(' ').append(way.from());
                first = false;
            }
            out.append('\n');
        }
        out.append("Unobtainable items:\n");
        for (ItemKey item : unobtainable) out.append(item.id()).append('\n');
        return out.toString();
    }
}
