package com.legoj15.megablockbot.core;

/**
 * Mining tiers in vanilla order. Gold mines like wood, so the resolver reports golden tools as {@link #WOOD}.
 * A modded tier is mapped to the highest vanilla tier whose {@code incorrect_for_*_tool} tag it clears.
 */
public enum ToolTier {
    HAND, WOOD, STONE, IRON, DIAMOND, NETHERITE;

    public boolean atLeast(ToolTier required) {
        return ordinal() >= required.ordinal();
    }

    public static ToolTier max(ToolTier a, ToolTier b) {
        return a.ordinal() >= b.ordinal() ? a : b;
    }
}
