package com.legoj15.megablockbot.core.snapshot;

import com.legoj15.megablockbot.core.ItemKey;
import java.util.Map;

/**
 * How much of a blueprint stands at its anchor.
 *
 * @param known   false when part of the footprint is unloaded; the build postcondition then answers UNKNOWN
 * @param matched blocks already correct
 * @param required blocks the blueprint has (air excluded)
 * @param missing what is still to place, by the item that places each block
 */
public record StructureReport(boolean known, int matched, int required, Map<ItemKey, Integer> missing) {
    public StructureReport {
        missing = Map.copyOf(missing);
    }
}
