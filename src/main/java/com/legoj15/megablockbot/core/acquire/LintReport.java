package com.legoj15.megablockbot.core.acquire;

import com.legoj15.megablockbot.core.ItemKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The obtainability lint (design 4.3) over an {@link AcquisitionIndex}: which items have no source and no recipe. An
 * item that is craftable only from unobtainable inputs is deliberately not chased here; that is a recipe-graph question
 * for the planner.
 *
 * @param namespace the namespace the counts and the id list are limited to, or null for every namespace
 * @param unobtainable the ids of the unobtainable items in scope, sorted
 */
public record LintReport(String namespace, int total, int withSource, int craftableOnly, List<ItemKey> unobtainable,
                         Map<String, Integer> unplannableRecipeTypes, List<String> notes) {

    public static LintReport of(AcquisitionIndex index, String namespace) {
        int total = 0;
        int withSource = 0;
        int craftableOnly = 0;
        List<ItemKey> unobtainable = new ArrayList<>();
        for (ItemKey item : index.items()) {
            if (namespace != null && !item.namespace().equals(namespace)) continue;
            total++;
            if (!index.sources(item).isEmpty()) withSource++;
            else if (index.craftable().contains(item)) craftableOnly++;
            else unobtainable.add(item);
        }
        return new LintReport(namespace, total, withSource, craftableOnly, List.copyOf(unobtainable),
            index.unplannableRecipeTypes(), index.notes());
    }

    /** Counts, unplannable recipe types and notes: everything but the item list. */
    public List<String> summaryLines() {
        List<String> lines = new ArrayList<>();
        lines.add("Obtainability lint (" + (namespace == null ? "all namespaces" : "namespace " + namespace) + "): "
            + total + " items, " + withSource + " with a source, " + craftableOnly + " craftable only, "
            + unobtainable.size() + " unobtainable");
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

    /** The whole report as a file: summary, then every unobtainable id. */
    public String fullText() {
        StringBuilder out = new StringBuilder();
        for (String line : summaryLines()) out.append(line).append('\n');
        out.append("Unobtainable items:\n");
        for (ItemKey item : unobtainable) out.append(item.id()).append('\n');
        return out.toString();
    }
}
