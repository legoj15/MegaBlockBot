package com.legoj15.megablockbot.core.acquire;

import com.legoj15.megablockbot.core.ItemKey;
import java.util.Objects;

/**
 * One way a survival player can obtain an item that no {@link Source} records, so the planner cannot choose it. The
 * obtainability lint lists such an item as obtainable with no strategy yet, not as unobtainable: the ways are held
 * apart from the sources ({@link AcquisitionIndex#knownWays}), and nothing in the planner reads them.
 *
 * <p>{@link #id()} is stable across restarts (built from registry ids, never from iteration order) and unique within
 * one item's list, in the style of {@link Source#id()}: {@code archaeology:minecraft:archaeology/desert_pyramid},
 * {@code code:wither}.
 */
public record KnownWay(String id, ItemKey item, String way, String from) {
    /** Brushing a suspicious block; {@link #from()} is the loot table's id. */
    public static final String ARCHAEOLOGY = "archaeology";
    /** A piglin barter; {@link #from()} is the loot table's id. */
    public static final String BARTER = "barter";
    /** A gift, such as a tamed cat's morning gift; {@link #from()} is the loot table's id. */
    public static final String GIFT = "gift";
    /** Shearing a mob; {@link #from()} is the loot table's id. */
    public static final String SHEARING = "shearing";
    /** Equipment a mob spawns wearing; {@link #from()} is the loot table's id. */
    public static final String EQUIPMENT = "equipment";
    /** A loot table of a type this list names no way for; {@link #from()} is the loot table's id. */
    public static final String OTHER_LOOT = "other_loot";
    /** A drop the game's own code makes, not any loot table; {@link #from()} is a short phrase saying where. */
    public static final String GAME_CODE = "game_code";

    public KnownWay {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(way, "way");
        Objects.requireNonNull(from, "from");
    }
}
