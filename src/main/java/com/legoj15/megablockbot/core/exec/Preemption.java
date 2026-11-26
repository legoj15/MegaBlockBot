package com.legoj15.megablockbot.core.exec;

import java.util.ArrayDeque;

/**
 * The reflex-preemption accounting of design 3.4 (Preemption): whether the reflex layer holds the body and why,
 * how many ticks it has held a running goal, and how often it took the body within a window. Pure counting --
 * what to do about a hold (pause the task, resume it, give the goal up) stays in {@link GoalExecutor}, which
 * owns the tree. Package-private, like {@link ReplanCounters}.
 */
final class Preemption {

    /** What this tick's hold did to the one before. */
    enum Change { NONE, STARTED, RELEASED }

    private final ExecutorConfig config;
    private boolean held;
    private String cause = "";
    /** Ticks the body stayed with the reflex layer while a goal was RUNNING, for this goal. */
    private int heldTicks;
    /** The ticks of this goal's preemptions that are still inside the window. */
    private final ArrayDeque<Long> startedAt = new ArrayDeque<>();
    private long now;

    Preemption(ExecutorConfig config) {
        this.config = config;
    }

    /** A goal starts: the paused-tick and preemption-count limits are per goal. */
    void resetForGoal() {
        heldTicks = 0;
        startedAt.clear();
    }

    /** Advances the clock one tick and applies the hold the reflex layer reports for it. */
    Change update(ReflexHold hold) {
        now++;
        if (hold.held() == held) {
            if (held) cause = hold.cause();
            return Change.NONE;
        }
        if (hold.held()) {
            held = true;
            cause = hold.cause();
            startedAt.addLast(now);
            while (!startedAt.isEmpty() && startedAt.getFirst() <= now - config.preemptWindowTicks()) {
                startedAt.removeFirst();
            }
            return Change.STARTED;
        }
        held = false;
        cause = "";
        return Change.RELEASED;
    }

    boolean held() {
        return held;
    }

    /** Why the reflex layer holds the body; empty when it does not. */
    String cause() {
        return cause;
    }

    /** One more tick the reflex layer kept the body while a goal was running. */
    void countHeldTick() {
        heldTicks++;
    }

    /** Past the paused-tick limit, or too many preemptions inside the window: the goal gives up. */
    boolean exceeded() {
        return heldTicks > config.preemptMaxPausedTicks() || startedAt.size() > config.preemptMaxCount();
    }
}
