package com.legoj15.megablockbot.core.plan;

import com.legoj15.megablockbot.core.Pos;
import java.util.List;
import java.util.Set;

/**
 * What earlier failures of this goal ruled out (design 3.4, Failure table). Grows over a goal's life and is
 * saved with it.
 *
 * @param sources source ids a NO_SOURCE failure excluded ({@link NodeSpec#source()} values)
 * @param blocks  single positions a PROTECTED or UNREACHABLE failure excluded
 * @param areas   regions to stay out of: a hazard, a protected region, the surroundings of a death
 */
public record Exclusions(Set<String> sources, Set<Pos> blocks, List<Area> areas) {
    public static final Exclusions NONE = new Exclusions(Set.of(), Set.of(), List.of());

    public Exclusions {
        sources = Set.copyOf(sources);
        blocks = Set.copyOf(blocks);
        areas = List.copyOf(areas);
    }

    /** A sphere to avoid. {@code reason} is for logs. */
    public record Area(String dimension, Pos center, int radius, String reason) {
        public boolean contains(String dim, Pos p) {
            return dimension.equals(dim) && center.distSq(p) <= (long) radius * radius;
        }
    }

    public boolean excludes(String dim, Pos p) {
        if (blocks.contains(p)) return true;
        for (Area a : areas) {
            if (a.contains(dim, p)) return true;
        }
        return false;
    }
}
