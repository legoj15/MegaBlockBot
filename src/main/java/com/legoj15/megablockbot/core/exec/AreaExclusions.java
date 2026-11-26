package com.legoj15.megablockbot.core.exec;

import com.legoj15.megablockbot.core.Pos;
import com.legoj15.megablockbot.core.plan.Exclusions;
import java.util.ArrayList;
import java.util.List;

/**
 * Adds a sphere to a goal's exclusions (design 3.4, Failure HAZARD and DIED): the one way a hazard and a death
 * both keep the planner out of an area. Package-private.
 */
final class AreaExclusions {

    private AreaExclusions() {}

    static Exclusions with(Exclusions base, String dimension, Pos center, int radius, String reason) {
        List<Exclusions.Area> areas = new ArrayList<>(base.areas());
        areas.add(new Exclusions.Area(dimension, center, radius, reason));
        return new Exclusions(base.sources(), base.blocks(), areas);
    }
}
