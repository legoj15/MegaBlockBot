package com.legoj15.megablockbot.core.goal;

import com.legoj15.megablockbot.core.EquipSlot;
import com.legoj15.megablockbot.core.ItemKey;
import com.legoj15.megablockbot.core.Pos;
import com.legoj15.megablockbot.core.ToolClass;
import com.legoj15.megablockbot.core.ToolTier;
import java.util.Map;
import java.util.Objects;

/**
 * A typed goal (design 3.4). Each maps to a postcondition through {@link Postconditions}; "done" is that
 * postcondition SATISFIED on a fresh snapshot, never "the task returned". Goals are plain data: they are saved
 * with the body and compared by value.
 */
public sealed interface Goal {

    /** Carry at least {@code count} of {@code key}. With a check, only stacks that pass it count. */
    record HaveItem(ItemKey key, int count, ComponentCheck check) implements Goal {
        public HaveItem {
            Objects.requireNonNull(key, "key");
            if (count < 1) throw new IllegalArgumentException("count must be positive");
        }

        public HaveItem(ItemKey key, int count) {
            this(key, count, null);
        }
    }

    /** Carry a tool of that class at that tier or better. */
    record HaveToolTier(ToolClass toolClass, ToolTier tier) implements Goal {}

    /** Wear or hold each listed item in its slot. */
    record Equip(Map<EquipSlot, ItemKey> slots) implements Goal {
        public Equip {
            slots = Map.copyOf(slots);
        }
    }

    /** The container at that position holds at least {@code count} of {@code key}. */
    record Stockpile(ItemKey key, int count, String dimension, Pos container) implements Goal {}

    /** The blueprint stands complete at the anchor. */
    record Build(String blueprint, String dimension, Pos anchor) implements Goal {}

    /** The body stands within {@code radius} blocks (horizontal) of the position, in that dimension. */
    record Reach(String dimension, Pos pos, int radius) implements Goal {}

    /**
     * A goal a strategy defines (design 3.4: "strategies add their own"). Its postcondition is registered under
     * {@code kind} in {@link Postconditions}; an unregistered kind evaluates UNKNOWN.
     */
    record Custom(String kind, Map<String, String> params) implements Goal {
        public Custom {
            Objects.requireNonNull(kind, "kind");
            params = Map.copyOf(params);
        }
    }
}
