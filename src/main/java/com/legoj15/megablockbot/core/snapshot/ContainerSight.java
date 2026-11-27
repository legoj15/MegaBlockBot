package com.legoj15.megablockbot.core.snapshot;

import com.legoj15.megablockbot.core.ItemKey;
import com.legoj15.megablockbot.core.Pos;
import java.util.Map;

/**
 * A container the snapshot looked at. A double chest is one inventory and so one sight, at the half with the lower
 * position hash ({@code BlockPos.asLong}); the snapshot is of the body's own dimension, so a sight carries none.
 *
 * <p>The body does only what a player can, and a player cannot see into a closed container: what a sight says is in a
 * container is a note the body made the last time it opened it, never what the world holds now. A container the body
 * has not opened (one its owner named and it has not looked in yet) has no note, so its sight carries no contents and
 * is not {@code remembered}: its contents are unknown, and unknown counts as nothing.
 *
 * @param block         the container block's id
 * @param contentsKnown false when it is out of range, its chunk is not loaded, or the body has never opened it; a
 *                      postcondition that needs the contents then answers UNKNOWN, never "empty"
 * @param placedByBot   true when this body placed it
 * @param mayTakeFrom   whether the body may take items out of it (design 3.5): it placed it, or its owner named it as
 *                      a supply container. The planner counts what such a container holds toward a goal; one the
 *                      body may not take from changes no plan
 * @param remembered    true when {@code contents} (possibly none) is what the body noted when it last opened the
 *                      container, as opposed to unknown because it never has. A note stays "remembered" whether the
 *                      container is within sight or not, and until the body opens it again (every take refreshes it): an
 *                      owner or another player may have changed the container since. The contents of a container it
 *                      may take from are shown with the note; those of any other container are withheld (an empty map)
 *                      when it is out of range or its chunk is not loaded, as {@code contentsKnown} says
 * @param contents      item counts: what the body noted in it, never more
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
