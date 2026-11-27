package com.legoj15.megablockbot.core.snapshot;

import com.legoj15.megablockbot.core.ItemKey;
import com.legoj15.megablockbot.core.Pos;
import java.util.Map;

/**
 * A container the snapshot looked at. A double chest is one inventory and so one sight, at the half with the lower
 * position hash ({@code BlockPos.asLong}); the snapshot is of the body's own dimension, so a sight carries none.
 *
 * @param block         the container block's id
 * @param contentsKnown false when it is out of range or its chunk is not loaded; a postcondition that needs
 *                      the contents then answers UNKNOWN, never "empty"
 * @param placedByBot   true when this body placed it
 * @param mayTakeFrom   whether the body may take items out of it (design 3.5): it placed it, or its owner named it as
 *                      a supply container. The planner counts what such a container holds toward a goal; one the
 *                      body may not take from changes no plan
 * @param remembered    true when {@code contents} is what the body noted the last time it saw the inside, for a
 *                      container it cannot see now (out of range, or its chunk is not loaded). The contents of a
 *                      container it may take from are shown then, marked; those of any other container are withheld
 *                      (an empty map), as {@code contentsKnown} says. A body never looks inside a container without
 *                      opening it, so within sight the contents are still the ones it noted
 * @param contents      item counts: what the body saw (or remembers) in it, never more
 */
public record ContainerSight(Pos pos, String block, boolean contentsKnown, boolean placedByBot, boolean mayTakeFrom,
                             boolean remembered, Map<ItemKey, Integer> contents) {
    public ContainerSight {
        contents = Map.copyOf(contents);
    }

    /** A container the body may take from only when it placed it, with its contents seen now or withheld. */
    public ContainerSight(Pos pos, String block, boolean contentsKnown, boolean placedByBot, Map<ItemKey, Integer> contents) {
        this(pos, block, contentsKnown, placedByBot, placedByBot, false, contents);
    }

    public int count(ItemKey key) {
        return contents.getOrDefault(key, 0);
    }
}
