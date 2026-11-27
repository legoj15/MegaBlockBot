package com.legoj15.megablockbot.game.reflex;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.world.phys.Vec3;

/**
 * Which mobs are coming closer: a few ticks of where each was, so that "a creeper that targets the body from afar" can be
 * told from "a creeper that is walking at it". Only the mob's own move counts, measured against where the body stands now
 * (the body walking toward a mob is the body's business, and the mob is a threat in its own right once it is near).
 *
 * <p>Samples are taken only when the threat scan runs and the mob is in sight, so a mob out of sight for a few ticks starts
 * again from nothing. Server thread only.
 */
final class ApproachWatch {
    private record Sample(long time, Vec3 position) {}

    /** Samples older than this are forgotten at the next prune, whatever their mob did. */
    private static final long FORGET_AFTER_TICKS = 200L;

    private final Map<UUID, ArrayDeque<Sample>> tracks = new HashMap<>();

    /**
     * Records where {@code mob} is at {@code now} and says whether it has come closer to {@code body} by at least
     * {@link ReflexConstants#CLOSING_MIN_BLOCKS} since the oldest sample within the last
     * {@link ReflexConstants#CLOSING_WINDOW_TICKS} ticks. False on the first sight of a mob: nothing to compare yet.
     */
    boolean closing(UUID mob, long now, Vec3 position, Vec3 body) {
        ArrayDeque<Sample> track = tracks.computeIfAbsent(mob, id -> new ArrayDeque<>());
        while (!track.isEmpty() && now - track.peekFirst().time() > ReflexConstants.CLOSING_WINDOW_TICKS) track.pollFirst();
        boolean closing = !track.isEmpty()
            && body.distanceTo(track.peekFirst().position()) - body.distanceTo(position) >= ReflexConstants.CLOSING_MIN_BLOCKS;
        track.addLast(new Sample(now, position));
        return closing;
    }

    /** Drops the tracks of mobs not seen for a while (gone, dead, or never back in sight). */
    void prune(long now) {
        tracks.values().removeIf(track -> track.isEmpty() || now - track.peekLast().time() > FORGET_AFTER_TICKS);
    }

    void clear() {
        tracks.clear();
    }
}
