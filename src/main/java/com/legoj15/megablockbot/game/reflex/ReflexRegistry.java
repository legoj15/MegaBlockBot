package com.legoj15.megablockbot.game.reflex;

import java.util.List;

/**
 * The one list of reflex behaviours, in priority order (design 3.2 and 3.4: "one registry lists the reflex behaviours";
 * AIBot kept two hand-written lists that disagreed). The layer asks them top to bottom and the first that wants the body
 * gets it, so a higher reflex preempts a lower one that holds it. A new reflex is added here and nowhere else.
 */
final class ReflexRegistry {
    private ReflexRegistry() {}

    /** Fresh reflexes for one body; they carry state, so each layer builds its own. */
    static List<Reflex> instantiate() {
        return List.of(
            new HazardReflex(),   // 1. lava, fire, drowning, suffocation
            new MobReflex(),      // 2. hostile mobs: fight or retreat
            new VitalsReflex(),   // 3. hunger and health: eat
            new NightReflex());   // 4. night and exposure: shelter, bed or nothing
    }
}
