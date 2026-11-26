package com.legoj15.megablockbot.core.exec;

import com.legoj15.megablockbot.core.Pos;
import com.legoj15.megablockbot.core.goal.Goal;
import com.legoj15.megablockbot.core.plan.Exclusions;
import com.legoj15.megablockbot.core.plan.NodeSpec;
import com.legoj15.megablockbot.core.snapshot.Snapshot;
import com.legoj15.megablockbot.core.task.Failure;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The death rules of design 3.4 (Failure DIED, Replanning from live state): the death record waiting for a new
 * body, the per-goal death cap, the area a death excludes, and the recover-drops node that goes in front of the
 * new tree when the drops can still be reached. The executor decides when each applies; this holds the state and
 * builds the pieces. Package-private, like {@link ReplanCounters}.
 */
final class DeathHandling<B> {

    /** The task kind of the recover-drops node inserted after a death. */
    static final String RECOVER_DROPS_KIND = "recover_drops";

    private final ExecutorHost<B> host;
    private final ExecutorConfig config;

    /**
     * Body deaths waiting for their replan, oldest first; saved with the goal (design 3.4, Persistence). More than
     * one can wait: a new body can die before its first tick, and every death counts and is applied.
     */
    private final List<Failure.Died> pending = new ArrayList<>();
    private boolean respawned;

    DeathHandling(ExecutorHost<B> host, ExecutorConfig config) {
        this.host = host;
        this.config = config;
    }

    // --- The record of a body death ----------------------------------------------------------

    /** The body died: the replan waits until a new body is passed to a tick. */
    void awaitRespawn(Failure.Died death) {
        pending.add(death);
        respawned = false;
    }

    /** The host created the new body. */
    void respawned() {
        respawned = true;
    }

    /**
     * The saved death records on load. A loaded executor is only ever ticked with a live body, so the deaths count
     * as respawned: the replan runs on the first tick.
     */
    void restore(List<Failure.Died> deaths) {
        pending.clear();
        pending.addAll(deaths);
        respawned = true;
    }

    /** A death record is waiting, whether or not the body is back yet. */
    boolean waiting() {
        return !pending.isEmpty();
    }

    /** The body is dead and its replacement has not been created: there is no live body to look at. */
    boolean awaitingRespawn() {
        return !pending.isEmpty() && !respawned;
    }

    /** The waiting deaths' replan can run: the body is back. */
    boolean replanDue() {
        return !pending.isEmpty() && respawned;
    }

    /** The waiting death records, oldest first, for the save form; empty when none. */
    List<Failure.Died> pending() {
        return List.copyOf(pending);
    }

    /** Hands over every waiting record, oldest first, and forgets them. */
    List<Failure.Died> takeAll() {
        List<Failure.Died> deaths = List.copyOf(pending);
        clear();
        return deaths;
    }

    void clear() {
        pending.clear();
        respawned = false;
    }

    // --- What a death does to the goal -------------------------------------------------------

    /** Counts a death within the goal; true when it was the last one allowed, so the goal gives up. */
    boolean count(ReplanCounters counters) {
        counters.deaths++;
        return counters.deaths >= config.maxDeathsPerGoal();
    }

    /** The area around the death position the planner must keep out of, the way a HAZARD does. */
    Exclusions excludeArea(Exclusions base, Failure.Died death) {
        return AreaExclusions.with(base, death.dimension(), death.deathPos(), config.deathExclusionRadius(),
                "death");
    }

    /**
     * The recover-drops node for the front of the new tree: a best-effort leaf whose goal carries the dimension
     * and the drop positions. Null when the host says the drops can no longer be reached before they despawn --
     * the death record is then simply dropped.
     */
    ExecNode<B> recoverDrops(Failure.Died death, Snapshot now) {
        if (!host.dropsRecoverable(death, now)) return null;
        StringBuilder drops = new StringBuilder();
        for (Pos p : death.dropPositions()) {
            if (!drops.isEmpty()) drops.append(';');
            drops.append(p.encode());
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("dimension", death.dimension());
        params.put("drops", drops.toString());
        params.put("deathPos", death.deathPos().encode());
        NodeSpec spec = new NodeSpec(new Goal.Custom(RECOVER_DROPS_KIND, params), RECOVER_DROPS_KIND, params, "",
                true, List.of());
        return ExecNode.of(spec, true, null);
    }
}
