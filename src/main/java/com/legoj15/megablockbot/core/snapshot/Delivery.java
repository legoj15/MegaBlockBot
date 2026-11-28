package com.legoj15.megablockbot.core.snapshot;

import com.legoj15.megablockbot.core.ItemKey;
import java.util.Objects;

/**
 * One handing-over the body made (issue #19): {@code count} of {@code item} dropped at the feet of the player
 * {@code recipient} (their UUID as text) for the deliver goal whose stamp is {@code gameTime}. What a player carries
 * is not the body's to read, so the body's own note is the only record, kept in its memory and carried by the
 * snapshots taken for a {@link com.legoj15.megablockbot.core.goal.Goal.Deliver} goal, which counts the notes that
 * carry its own stamp. One note holds everything handed over for one goal, however many stacks that took.
 *
 * @param recipient the player the items were dropped to, by UUID
 * @param item      what was dropped
 * @param count     how many were dropped
 * @param gameTime  the stamp of the goal the delivery was made for: the game time that goal was given, made
 *                  different from every other deliver goal's by the runtime
 */
public record Delivery(String recipient, ItemKey item, int count, long gameTime) {
    public Delivery {
        Objects.requireNonNull(recipient, "recipient");
        Objects.requireNonNull(item, "item");
        if (count < 1) throw new IllegalArgumentException("count must be positive");
    }
}
