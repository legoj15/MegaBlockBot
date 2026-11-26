package com.legoj15.megablockbot.core.snapshot;

import com.legoj15.megablockbot.core.ItemKey;
import com.legoj15.megablockbot.core.Pos;
import java.util.Map;

/**
 * A container the snapshot looked at.
 *
 * @param block         the container block's id
 * @param contentsKnown false when it is out of range or its chunk is not loaded; a postcondition that needs
 *                      the contents then answers UNKNOWN, never "empty"
 */
public record ContainerSight(Pos pos, String block, boolean contentsKnown, boolean placedByBot,
                             Map<ItemKey, Integer> contents) {
    public ContainerSight {
        contents = Map.copyOf(contents);
    }

    public int count(ItemKey key) {
        return contents.getOrDefault(key, 0);
    }
}
