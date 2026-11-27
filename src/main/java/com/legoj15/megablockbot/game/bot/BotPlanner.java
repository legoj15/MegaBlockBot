package com.legoj15.megablockbot.game.bot;

import com.legoj15.megablockbot.core.acquire.AcquisitionIndex;
import com.legoj15.megablockbot.core.exec.PlanTicket;
import com.legoj15.megablockbot.core.goal.Goal;
import com.legoj15.megablockbot.core.plan.PlanOutcome;
import com.legoj15.megablockbot.core.plan.PlanRequest;
import com.legoj15.megablockbot.core.plan.Planner;
import com.legoj15.megablockbot.core.planner.LookInside;
import com.legoj15.megablockbot.core.planner.PlannerConfig;
import com.legoj15.megablockbot.core.planner.RecipeBook;
import com.legoj15.megablockbot.core.planner.SolverPlanner;
import com.legoj15.megablockbot.core.snapshot.Snapshot;
import com.legoj15.megablockbot.game.plan.RecipeBooks;
import com.legoj15.megablockbot.game.resolver.AcquisitionIndexes;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * The planner every body shares, and the one thread it runs on (design 3.4, Threading: planning runs off the server
 * thread on immutable inputs). One daemon thread, {@code MegaBlockBot planner}, serves every bot: a plan is a few
 * milliseconds of pure work, so a queue of one thread is enough and bounds what bots cost the machine. It is started
 * on first use and shut down when the server stops, so the next server in this JVM starts its own.
 *
 * <p>The {@link SolverPlanner} reads the recipe book and the acquisition index, both published as immutable instances;
 * it is rebuilt whenever either publishes a new one (a reload), and keeps no state of its own between plans.
 */
final class BotPlanner {
    private static final Object LOCK = new Object();
    private static ExecutorService pool;
    private static Planner planner;
    private static RecipeBook plannedBook;
    private static AcquisitionIndex plannedIndex;

    private BotPlanner() {}

    /** The planner over the recipe book and index that are current now. */
    static Planner planner() {
        RecipeBook book = RecipeBooks.current();
        AcquisitionIndex index = AcquisitionIndexes.current();
        synchronized (LOCK) {
            // By identity: a rebuild publishes a new instance, which is the only change a reader has to notice.
            if (planner == null || plannedBook != book || plannedIndex != index) {
                planner = new SolverPlanner(book, index, PlannerConfig.defaults(), List.of(new LookInside()));
                plannedBook = book;
                plannedIndex = index;
            }
            return planner;
        }
    }

    /** Starts planning on the shared thread. The planner and the request are both immutable, so nothing races. */
    static PlanTicket plan(PlanRequest request) {
        Planner current = planner();
        return new Ticket(pool().submit(() -> current.plan(request)));
    }

    static long inputFingerprint(Goal goal, Snapshot snapshot) {
        return planner().inputFingerprint(goal, snapshot);
    }

    /** Server stopping: ends the planner thread; whatever was queued is dropped (its goals die with the server). */
    static void shutdown() {
        synchronized (LOCK) {
            if (pool != null) pool.shutdownNow();
            pool = null;
            planner = null;
            plannedBook = null;
            plannedIndex = null;
        }
    }

    private static ExecutorService pool() {
        synchronized (LOCK) {
            if (pool == null) {
                pool = Executors.newSingleThreadExecutor(task -> {
                    Thread thread = new Thread(task, "MegaBlockBot planner");
                    thread.setDaemon(true);
                    return thread;
                });
            }
            return pool;
        }
    }

    /** A plan in flight: the executor polls {@link #done()}, and {@link #outcome()} rethrows what the planner threw. */
    private record Ticket(Future<PlanOutcome> future) implements PlanTicket {
        @Override
        public boolean done() {
            return future.isDone();
        }

        @Override
        public PlanOutcome outcome() throws Exception {
            try {
                return future.get();
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof Exception exception) throw exception;
                throw new IllegalStateException("the planner failed with " + cause, cause);
            } catch (CancellationException e) {
                throw new IllegalStateException("the plan was cancelled", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            }
        }

        @Override
        public void cancel() {
            future.cancel(true);
        }
    }
}
