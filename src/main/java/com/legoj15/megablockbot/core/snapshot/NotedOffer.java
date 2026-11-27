package com.legoj15.megablockbot.core.snapshot;

import com.legoj15.megablockbot.core.ItemKey;
import com.legoj15.megablockbot.core.Pos;
import java.util.Map;
import java.util.UUID;

/**
 * One offer of one villager, as the body saw it in that villager's trade window (issue #15). A snapshot's offers are
 * the body's own notes, never the world's state, exactly as a container's contents are: a real villager's price
 * differs from the sampled one in the index (an enchanted book costs 5 to 64 emeralds), so the planner prices and
 * plans the payment of a trade by the offers the body has noted, where it has noted any.
 *
 * @param villager the villager whose window showed the offer
 * @param pos      where that villager stood
 * @param result   what one use of the offer hands over
 * @param facts    the result's component facts, read as the snapshot reads a carried stack
 * @param charge   what one use costs, by item
 * @param gameTime when the window was open, so a note older than a named time is dropped
 */
public record NotedOffer(UUID villager, Pos pos, ItemKey result, StackFacts facts, Map<ItemKey, Integer> charge,
                         long gameTime) implements Observation {
    public NotedOffer {
        charge = Map.copyOf(charge);
    }
}
