package com.legoj15.megablockbot.core.snapshot;

import com.legoj15.megablockbot.core.ItemKey;
import com.legoj15.megablockbot.core.Pos;

/**
 * A station seen within the snapshot radius.
 *
 * @param station    the station's key: the item that places it (e.g. {@code minecraft:furnace})
 * @param placedByBot true when this body placed it, so it may break it and take from it (design 3.5)
 */
public record StationSight(ItemKey station, Pos pos, boolean placedByBot) {}
