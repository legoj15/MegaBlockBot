package com.legoj15.megablockbot.core.exec;

import com.legoj15.megablockbot.core.Pos;
import com.legoj15.megablockbot.core.goal.Evaluation;
import com.legoj15.megablockbot.core.goal.Goal;
import com.legoj15.megablockbot.core.goal.Postconditions;
import com.legoj15.megablockbot.core.plan.Exclusions;
import com.legoj15.megablockbot.core.plan.NodeIdentity;
import com.legoj15.megablockbot.core.plan.NodeSpec;
import com.legoj15.megablockbot.core.plan.Plan;
import com.legoj15.megablockbot.core.plan.PlanOutcome;
import com.legoj15.megablockbot.core.plan.PlanRequest;
import com.legoj15.megablockbot.core.snapshot.Snapshot;
import com.legoj15.megablockbot.core.task.Failure;
import com.legoj15.megablockbot.core.task.Task;
import com.legoj15.megablockbot.core.task.TaskState;
import com.legoj15.megablockbot.core.task.TickResult;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The executor of design 3.4: one per body, running the goal tree's tasks on the server thread. Single
 * threaded and never blocking -- planning goes through {@link ExecutorHost#plan} and is polled. Everything
 * the executor needs from outside comes from the host; everything it keeps per goal is in {@link Run}.
 *
 * <p>Readings of 3.4 this class takes where the design is silent or where it was changed (the report lists
 * them too):
 * <ul>
 *   <li><b>Group nodes are evaluated.</b> When execution first reaches a non-leaf node, before its first
 *       pending leaf starts, its postcondition is checked: SATISFIED marks the group and its unfinished
 *       descendants done (a prerequisite the bot was handed is not gathered again); UNSATISFIED or UNKNOWN
 *       descends and the children decide. This replaces the rule that a non-leaf node whose postcondition is
 *       still UNKNOWN gives the goal up with TARGET_GONE: a group never gives up for being unreadable, its leaves
 *       fail or succeed on their own postconditions.</li>
 *   <li><b>The goal's evaluation dropping is a replan trigger.</b> After each node ends well the goal is
 *       evaluated: SATISFIED finishes the goal; a matched count lower than it was when the current plan was
 *       adopted replans (cause NORMAL, counted like any other). An UNKNOWN evaluation there is ignored until the
 *       tree runs out, because UNKNOWN carries no matched count to compare.</li>
 *   <li><b>A task is never left in a state the executor does not drive.</b> A task that is not RUNNING under
 *       an ACTIVE node (failed by a hook, completed between ticks, in a state nothing explains) ends the node
 *       the same tick; a throw or failure found after the executor's own pause, resume or abort call is
 *       routed or logged the same way.</li>
 *   <li><b>A host that throws gives the goal up.</b> A snapshot, planner or task factory that throws is logged
 *       with its stack trace and ends the goal with {@code Failure.Internal}; it never escapes a call and never
 *       leaves a goal with no ticket, no tree and no result. Every public call that can reach the host is guarded
 *       the same way, {@link #tick}, {@link #cancel}, {@link #cancelAll}, {@link #onDeath}, {@link #pause} and
 *       {@link #resume} alike.</li>
 *   <li><b>No task outlives its goal.</b> Every way a goal ends (a result, a give-up reached through the guard, a
 *       cancel, a {@link #load} over it) retires the active task first.</li>
 *   <li><b>Planning has a deadline.</b> The ticks spent waiting on one plan ticket count against
 *       {@link ExecutorConfig#planTimeoutTicks()}; past it the ticket is cancelled and the goal gives up with
 *       {@code Failure.TimedOut}. Ticks the owner paused or the reflex layer held do not count.</li>
 *   <li><b>A give-up, a cancel and a death cap report the present.</b> The goal is evaluated once at that moment
 *       when a live body is there to look at; the last evaluation stands when that throws, when the body is gone,
 *       and when the fresh answer is UNKNOWN while an earlier one was known (UNKNOWN is "cannot tell", not
 *       "nothing").</li>
 *   <li><b>Every death counts and is applied.</b> Deaths that arrive before one replan can run all wait for it;
 *       the replan excludes each death's area and puts a recover-drops node in front for each recoverable one,
 *       earliest death first (its drops despawn first). A death replaces the plan still owed from a start or a load:
 *       the death replan is then the only plan.</li>
 *   <li><b>Load restores what the restart replan keeps.</b> Only the executor-inserted nodes of a saved tree are
 *       read back; everything the planner made is rebuilt from live state, and a node's postcondition is checked
 *       by the pre-check before it starts, not at load. A load over a live goal ends that goal first.</li>
 * </ul>
 *
 * <p>For the host author:
 * <ul>
 *   <li>The host reports a body's death through {@link #onDeath}, and a task whose body died should not also
 *       return {@code Failed(Died)}: the executor would count that death a second time. A {@code Failed(Died)}
 *       is taken as a death with the body still there (counted, its area excluded, the replan at once), while
 *       {@code onDeath} waits for {@link #onRespawn} and a body to tick.</li>
 *   <li>The failure behind the last replan is not part of the save, so a budget give-up right after a {@link
 *       #load} reports {@code NoProgress}, not the failure that caused the replan.</li>
 * </ul>
 */
public final class GoalExecutor<B> {

    /** Radius of the Reach node inserted for an UNKNOWN evaluation: close enough to see the thing again. */
    static final int UNKNOWN_REACH_RADIUS = 4;

    /** The task kind of the executor-inserted Reach node for an UNKNOWN evaluation. */
    static final String REACH_KIND = "reach";

    /** Why a plan is in flight. Only plan-caused causes count against the budgets. */
    private enum PlanCause { INITIAL, NORMAL, REPAIR, RESTART, DEATH }

    private final ExecutorHost<B> host;
    private final Postconditions postconditions;
    private final ExecutorConfig config;

    /** The goal being run, with its tree, counters, exclusions and checkpoints. */
    private Run<B> run;

    private final ArrayDeque<Goal> queue = new ArrayDeque<>();

    private PlanTicket ticket;
    private PlanCause ticketCause = PlanCause.INITIAL;
    private int rerunsInARow;

    /** Ticks spent waiting on the current ticket: what {@link ExecutorConfig#planTimeoutTicks()} limits. */
    private int ticketWaitTicks;

    /** Whether the reflex layer holds the body, and the limits on how long and how often it may. */
    private final Preemption preemption;

    /** The death record waiting for a new body, the death cap, the death area and the recover-drops node. */
    private final DeathHandling<B> deaths;

    private boolean restartPending;
    private boolean initialPlanPending;

    /**
     * The body the last {@link #tick} was given. Calls that arrive between ticks (owner pause and resume, cancel,
     * a death) hand tasks this body; before the first tick there is no task, so nothing to call.
     */
    private B lastBody;

    /** The last throwable handed to the host log, so one exception reached through two paths is logged once. */
    private Throwable lastLogged;

    /**
     * Per-goal state. The goal, tree, counters, exclusions and checkpoints are saved with it; the rest (the plan
     * fingerprint, the replan marks, the last failure) is rebuilt by the restart replan.
     */
    private static final class Run<B> {
        final Goal goal;
        GoalStatus status = GoalStatus.RUNNING;
        List<ExecNode<B>> roots = new ArrayList<>();
        boolean hasTree;
        final ReplanCounters counters = new ReplanCounters();
        Exclusions exclusions = Exclusions.NONE;
        final Map<String, Map<String, String>> checkpoints = new HashMap<>();
        Evaluation lastEval;
        long lastPlanFingerprint;
        int planDeclaredTotal;
        /** The goal's matched count when the current plan was adopted: the mark a drop is measured against. */
        int matchedAtAdoption;
        boolean goalUnknownReachTried;
        /** The failure that caused the latest failure-driven replan: what a give-up on a budget reports. */
        Failure lastReplanFailure;

        Run(Goal goal) {
            this.goal = goal;
        }
    }

    public GoalExecutor(ExecutorHost<B> host, Postconditions postconditions, ExecutorConfig config) {
        this.host = host;
        this.postconditions = postconditions;
        this.config = config;
        this.preemption = new Preemption(config);
        this.deaths = new DeathHandling<>(host, config);
    }

    // --- Public surface ----------------------------------------------------------------------

    /** Starts the goal when idle, otherwise appends to the FIFO queue (design 3.4). */
    public void submit(Goal goal) {
        if (run == null && !preemption.held()) {
            startGoal(goal);
        } else {
            queue.addLast(goal);
        }
    }

    /**
     * Aborts the active task, discards the tree, publishes CANCELLED and forgets the goal at once, so a restart
     * cannot resume it; the queue then advances (never while a reflex holds the body).
     */
    public void cancel() {
        guarded("cancel", this::cancelGoal);
    }

    /**
     * Also clears the queue, and clears it first: ending the running goal advances the queue, and a goal started
     * by that would survive the clear.
     */
    public void cancelAll() {
        guarded("cancelAll", () -> {
            queue.clear();
            cancelGoal();
        });
    }

    private void cancelGoal() {
        if (run == null) return;
        abortActive(lastBody, "cancel");
        finish(liveBody(), GoalStatus.CANCELLED, null);
    }

    /**
     * The owner's pause command: the goal is PAUSED and still counts as active. Not reflex preemption. With no
     * goal running there is nothing to pause, so nothing is remembered for the next goal to inherit.
     */
    public void pause() {
        if (run == null || run.status != GoalStatus.RUNNING) return;
        run.status = GoalStatus.PAUSED;
        guarded("pause", () -> {
            ExecNode<B> active = findActive();
            if (active != null && active.task != null && active.task.state() == TaskState.RUNNING) {
                hookEndedNode(lastBody, active, "task.pause", () -> active.task.pause(lastBody, "owner paused"));
            }
        });
    }

    public void resume() {
        if (run == null || run.status != GoalStatus.PAUSED) return;
        run.status = GoalStatus.RUNNING;
        // While a reflex still holds the body its pause stays; releasing the hold resumes the task.
        if (preemption.held()) return;
        guarded("resume", () -> {
            ExecNode<B> active = findActive();
            if (active != null && active.task != null && active.task.state() == TaskState.PAUSED) {
                hookEndedNode(lastBody, active, "task.resume", () -> active.task.resume(lastBody));
            }
        });
    }

    /**
     * One server tick. {@code hold} says whether the reflex layer owns the body (design 3.4, Preemption).
     * {@code body} is always a live body: an executor restored by {@link #load} is only ever ticked once its body
     * exists again, so a saved death counts as respawned on the first tick.
     */
    public void tick(B body, ReflexHold hold) {
        lastBody = body;
        guarded("tick", () -> tickStep(body, hold == null ? ReflexHold.NONE : hold));
    }

    private void tickStep(B body, ReflexHold hold) {
        switch (preemption.update(hold)) {
            case STARTED -> pauseForReflex(body);
            case RELEASED -> {
                if (run != null && run.status == GoalStatus.RUNNING) releaseHold(body);
            }
            case NONE -> { }
        }
        if (preemption.held()) {
            // The gate is shut: the executor starts nothing. Responses that arrived wait.
            if (run != null && run.status == GoalStatus.RUNNING) {
                preemption.countHeldTick();
                if (preemption.exceeded()) stallPreempted(body);
            }
            return;
        }
        if (run == null) {
            maybeStartNext();
            return;
        }
        if (run.status == GoalStatus.PAUSED) return; // paused ticks count toward nothing
        if (ticket != null) {
            pollTicket(body);
            return;
        }
        if (deaths.waiting()) {
            if (deaths.replanDue()) deathReplan(body);
            return;
        }
        if (restartPending) {
            restartPending = false;
            doRestart(body);
            return;
        }
        if (initialPlanPending) {
            initialPlanPending = false;
            requestPlan(body, PlanCause.INITIAL);
            return;
        }
        if (!run.hasTree) return;
        stepTree(body);
    }

    /**
     * The body died (design 3.4, Failure DIED row): abort the active task, its node returns to PENDING, the
     * death count rises; the third death within the goal gives up with the {@link Failure.Died}. Deaths that
     * arrive before the replan can run all wait for it.
     */
    public void onDeath(Failure.Died death) {
        guarded("onDeath", () -> {
            if (run == null) return;
            clearTicket();
            ExecNode<B> active = findActive();
            if (active != null) {
                retireTask(lastBody, active, "died");
                active.state = NodeState.PENDING;
            }
            // The body that died is not one to look at or act on any more, and onRespawn does not bring the new
            // one: the next tick does. Until then a cancel or a give-up falls back to the last evaluation.
            lastBody = null;
            // The death replan is the one plan this goal needs now; a plan still owed from a start or a load
            // would be a second one, made right after it.
            initialPlanPending = false;
            restartPending = false;
            if (deaths.count(run.counters)) {
                // The body is dead, so there is nobody to look at: the result carries the last evaluation.
                finish(null, GoalStatus.GAVE_UP, death);
                return;
            }
            deaths.awaitRespawn(death);
        });
    }

    /** A new body instance will be passed to the next tick; the death replan runs after this. */
    public void onRespawn() {
        deaths.respawned();
    }

    /** True while a goal is RUNNING or PAUSED: drives chunk loading and respawn at server start. */
    public boolean active() {
        return run != null && (run.status == GoalStatus.RUNNING || run.status == GoalStatus.PAUSED);
    }

    /**
     * True while a goal waits in the queue behind the running one, or with nothing running (a reflex held the body when it
     * was submitted). Cheap: {@link #view()} copies the whole tree, and a question asked every tick must not.
     */
    public boolean hasQueuedGoal() {
        return !queue.isEmpty();
    }

    public ExecutorView view() {
        List<ExecutorView.NodeView> nodes = new ArrayList<>();
        int total = 0, noProgress = 0, repair = 0, deathCount = 0;
        Goal goal = null;
        GoalStatus status = null;
        if (run != null) {
            goal = run.goal;
            status = run.status;
            total = run.counters.totalReplans;
            noProgress = run.counters.consecutiveWithoutProgress;
            repair = run.counters.repairReplans;
            deathCount = run.counters.deaths;
            for (ExecNode<B> r : run.roots) collectView(r, 0, nodes);
        }
        return new ExecutorView(goal, status, nodes, total, noProgress, repair, deathCount,
                List.copyOf(queue), ticket != null, preemption.held());
    }

    // --- Save form (design 3.4, Persistence) ------------------------------------------------

    /** The save form: a JSON-like map Minecraft code turns into NBT. Never throws, never holds a null. */
    public Map<String, Object> save() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("v", 1L);
        // Derived, not remembered: a pause with no goal must not leave a flag the next goal inherits.
        m.put("paused", run != null && run.status == GoalStatus.PAUSED);
        List<Object> queued = new ArrayList<>();
        for (Goal g : queue) queued.add(SaveCodec.encodeGoal(g));
        m.put("queue", queued);
        if (run == null) return m;
        m.put("goal", SaveCodec.encodeGoal(run.goal));
        m.put("counters", SaveCodec.encodeCounters(run.counters));
        m.put("exclusions", SaveCodec.encodeExclusions(run.exclusions));
        if (deaths.waiting()) {
            List<Object> pendingDeaths = new ArrayList<>();
            for (Failure.Died d : deaths.pending()) pendingDeaths.add(SaveCodec.encodeDeath(d));
            m.put("deaths", pendingDeaths);
        }
        List<Object> tree = new ArrayList<>();
        for (ExecNode<B> r : run.roots) tree.add(SaveCodec.encodeNode(r));
        m.put("tree", tree);
        m.put("checkpoints", SaveCodec.encodeCheckpoints(checkpointsWithLiveTask()));
        return m;
    }

    /** The stored checkpoints, with the live task's own on top: it has not ended, so it has not been stored. */
    private Map<String, Map<String, String>> checkpointsWithLiveTask() {
        Map<String, Map<String, String>> all = new LinkedHashMap<>(run.checkpoints);
        ExecNode<B> active = findActive();
        if (active != null && active.task != null) {
            String id = NodeIdentity.of(active.spec);
            Map<String, String> live = Checkpoints.read(host, id, active.task, "save");
            if (!live.isEmpty()) all.put(id, live);
        }
        return all;
    }

    /**
     * Restores a save. A goal that is live first ends the way a cancel ends it (its task aborted, a CANCELLED
     * result published) and the preemption accounting starts over. A bad save drops the goal (reported through
     * {@code log} and {@code publish}), never throws, and leaves the executor idle and usable. Otherwise restores
     * counters, exclusions, queue, checkpoints, pending deaths, the executor-inserted nodes and the paused flag,
     * then replans from live state -- a restart replan, not counted. {@code body} is the live body the executor
     * will be ticked with, which a cancel or a give-up before the first tick evaluates the goal against.
     */
    public void load(Map<String, Object> saved, B body) {
        // The save replaces the queue, so it goes first: ending the live goal must not start one of its goals.
        queue.clear();
        guarded("load", this::cancelGoal);
        lastBody = body;
        try {
            doLoad(saved);
        } catch (RuntimeException | Error e) {
            // A bad save drops the goal, is reported, and never blocks other bodies (design 3.4).
            resetForLoad();
            host.log("goal state did not load; goal dropped", e);
            host.publish(new GoalResult(null, GoalResult.Status.FAILED, 0, 0, List.of(),
                    new Failure.Internal(e, "goal state did not load")));
        }
    }

    private void resetForLoad() {
        run = null;
        clearTicket();
        queue.clear();
        deaths.clear();
        restartPending = false;
        initialPlanPending = false;
        rerunsInARow = 0;
        preemption.resetForGoal();
    }

    private void doLoad(Map<String, Object> saved) {
        resetForLoad();

        Map<String, Object> m = SaveCodec.expectMap(saved, "save");
        if (!(m.get("v") instanceof Number v) || v.longValue() != 1L) {
            throw new IllegalArgumentException("unknown save version: " + m.get("v"));
        }
        if (m.containsKey("queue")) {
            for (Object o : SaveCodec.expectList(m.get("queue"), "queue")) {
                try {
                    queue.addLast(SaveCodec.decodeGoal(o));
                } catch (RuntimeException e) {
                    host.log("queued goal dropped: it did not decode", e);
                }
            }
        }
        // An idle executor saves no goal: its queue is all there is to restore, and nothing was lost.
        if (!m.containsKey("goal")) return;
        boolean paused = Boolean.TRUE.equals(m.get("paused"));
        Goal goal;
        try {
            goal = SaveCodec.decodeGoal(m.get("goal"));
        } catch (RuntimeException e) {
            // The whole save is bad: the executor is left idle, so the queue goes with the goal.
            queue.clear();
            host.log("root goal dropped: it did not decode", e);
            host.publish(new GoalResult(null, GoalResult.Status.FAILED, 0, 0, List.of(),
                    new Failure.Internal(e, "root goal did not decode")));
            return;
        }
        Run<B> restored = new Run<>(goal);
        if (m.get("counters") instanceof Map<?, ?> cm) SaveCodec.decodeCountersInto(cm, restored.counters);
        if (m.containsKey("exclusions")) {
            restored.exclusions = SaveCodec.decodeExclusions(m.get("exclusions"));
        }
        List<Failure.Died> pendingDeaths = new ArrayList<>();
        if (m.containsKey("deaths")) {
            for (Object o : SaveCodec.expectList(m.get("deaths"), "deaths")) pendingDeaths.add(SaveCodec.decodeDeath(o));
        }
        if (m.containsKey("tree")) {
            // The restart replan keeps only the executor-inserted nodes of the old tree, so those are all that
            // is read back. A task instance never survives a restart: a node saved mid-task starts it over.
            for (SaveCodec.SavedNode sn : SaveCodec.decodeInsertedNodes(m.get("tree"))) {
                ExecNode<B> node = ExecNode.of(sn.spec(), true, null);
                node.state = sn.state() == NodeState.ACTIVE ? NodeState.PENDING : sn.state();
                if (!node.terminal()) restored.roots.add(node);
            }
            restored.hasTree = !restored.roots.isEmpty();
        }
        if (m.containsKey("checkpoints")) {
            // A checkpoint that is not a string map is dropped alone and its node replans (design 3.4).
            restored.checkpoints.putAll(SaveCodec.decodeCheckpoints(m.get("checkpoints"),
                    (id, why) -> host.log("checkpoint dropped for " + id, why)));
        }
        restored.status = paused ? GoalStatus.PAUSED : GoalStatus.RUNNING;
        run = restored;
        if (!pendingDeaths.isEmpty()) {
            // The death replan is the one plan this goal needs, and it replaces the restart replan.
            deaths.restore(pendingDeaths);
        } else {
            restartPending = true;
        }
    }

    private void doRestart(B body) {
        List<ExecNode<B>> inserted = ExecTree.retainInserted(run.roots);
        run.roots = new ArrayList<>(inserted);
        run.hasTree = !inserted.isEmpty();
        requestPlan(body, PlanCause.RESTART);
    }

    // --- Guarding the host -------------------------------------------------------------------

    /**
     * Runs one executor step. Whatever the host throws (a snapshot collector, the planner, a task factory) is
     * logged with its stack trace and ends the goal with {@link Failure.Internal}, so a broken host call never
     * escapes into the server tick and never leaves a goal with no ticket, no tree and no result.
     */
    private void guarded(String what, Runnable step) {
        try {
            step.run();
        } catch (RuntimeException | Error e) {
            logOnce(what + " threw inside the executor; the goal gives up", e);
            if (run != null) {
                giveUp(liveBody(), new Failure.Internal(e, what + " threw " + e.getClass().getSimpleName()));
            }
        }
    }

    private void logOnce(String message, Throwable error) {
        if (error != null && error == lastLogged) return;
        lastLogged = error;
        host.log(message, error);
    }

    // --- Tree stepping -----------------------------------------------------------------------

    private void startGoal(Goal goal) {
        run = new Run<>(goal);
        preemption.resetForGoal();
        initialPlanPending = true;
    }

    private void maybeStartNext() {
        if (run == null && !preemption.held() && !queue.isEmpty()) startGoal(queue.pollFirst());
    }

    private void stepTree(B body) {
        ExecNode<B> active = findActive();
        if (active != null) {
            tickActive(body, active);
            return;
        }
        advance(body);
    }

    /**
     * Finds the next piece of work and does it: a group's first evaluation, or a pending leaf's pre-check and
     * start. Repeats while nodes pre-satisfy; stops when a task started, a plan is in flight or the goal ended.
     */
    private void advance(B body) {
        while (run != null) {
            ExecNode<B> next = ExecTree.nextWork(run.roots);
            if (next == null) {
                onTreeRanOut(body);
                return;
            }
            if (!next.isLeaf()) {
                // First time execution reaches this group: if its postcondition already holds (a prerequisite
                // the bot was handed) nothing under it is gathered again; otherwise the children decide.
                next.groupChecked = true;
                if (evaluateNode(body, next).isSatisfied()) {
                    ExecTree.markDone(next);
                    if (afterNodeDone(body)) continue;
                    return;
                }
                continue;
            }
            Evaluation ev = evaluateNode(body, next);
            if (ev.isSatisfied()) {
                // PENDING -> SATISFIED without running: items picked up on the way, a chest the owner filled.
                next.state = NodeState.SATISFIED;
                if (afterNodeDone(body)) continue;
                return;
            }
            if (ev.isUnknown()) {
                if (next.executorInserted) {
                    // The executor put it there (recover-drops); the snapshot cannot judge a custom kind, so
                    // the task runs and its best-effort flag deals with whatever the ending says.
                    startNode(body, next);
                    return;
                }
                if (handleUnknown(body, next)) continue;
                return;
            }
            startNode(body, next);
            return;
        }
    }

    /** Inserts the Reach node for an UNKNOWN. False when the caller should stop (already tried, or routing). */
    private boolean handleUnknown(B body, ExecNode<B> node) {
        if (node.unknownReachTried) {
            node.state = NodeState.FAILED;
            routeFailure(body, node, new Failure.TargetGone(NodeIdentity.target(node.spec.goal()),
                    "postcondition still unknown after reaching it"));
            return false;
        }
        Pos pos = positionFor(node.spec.goal());
        String dim = dimensionFor(node.spec.goal());
        if (pos == null || dim == null) {
            node.state = NodeState.FAILED;
            routeFailure(body, node, new Failure.TargetGone(NodeIdentity.target(node.spec.goal()),
                    "no position to reach"));
            return false;
        }
        node.unknownReachTried = true;
        insertReach(dim, pos);
        return true;
    }

    private void onTreeRanOut(B body) {
        Evaluation ev = evaluateGoalNow(body);
        if (ev.isSatisfied()) {
            finish(body, GoalStatus.SATISFIED, null);
            return;
        }
        if (ev.isUnknown()) {
            if (run.goalUnknownReachTried) {
                giveUp(body, new Failure.TargetGone(NodeIdentity.target(run.goal), "still unknown after reaching"));
                return;
            }
            Pos pos = positionFor(run.goal);
            String dim = dimensionFor(run.goal);
            if (pos == null || dim == null) {
                giveUp(body, new Failure.TargetGone(NodeIdentity.target(run.goal), "no position to reach"));
                return;
            }
            run.goalUnknownReachTried = true;
            insertReach(dim, pos);
            return;
        }
        // Tree ran out with the goal unsatisfied: a repair replan, capped and also counted in the totals.
        if (run.counters.repairReplans >= config.maxRepairReplans()) {
            giveUp(body, wall("repair replan budget exhausted (" + config.maxRepairReplans() + ")"));
            return;
        }
        run.counters.repairReplans++;
        replan(body, PlanCause.REPAIR, null);
    }

    /**
     * Evaluates the goal after a node ended well (satisfied, pre-satisfied, skipped, or a group marked done).
     * SATISFIED ends the goal even if nodes remain. A matched count below the one the current plan was adopted
     * with means something the plan relied on was lost: replan, counted like any other. UNKNOWN is ignored here
     * -- it carries no count to compare -- and left for the tree running out. False when stepping should stop.
     */
    private boolean afterNodeDone(B body) {
        Evaluation ev = evaluateGoalNow(body);
        if (ev.isSatisfied()) {
            finish(body, GoalStatus.SATISFIED, null);
            return false;
        }
        if (!ev.isUnknown() && ev.matched() < run.matchedAtAdoption) {
            replan(body, PlanCause.NORMAL, null);
            return false;
        }
        return true;
    }

    private void startNode(B body, ExecNode<B> node) {
        node.state = NodeState.ACTIVE;
        node.budgetTicksUsed = 0;
        node.stuckTicks = 0;
        node.lastSignature = host.progressSignature(body);
        Map<String, String> hints = run.checkpoints.getOrDefault(NodeIdentity.of(node.spec), Map.of());
        Task<B> task;
        try {
            task = host.createTask(node.spec, hints);
        } catch (RuntimeException | Error e) {
            node.state = NodeState.FAILED;
            routeFailure(body, node, new Failure.Internal(e, "createTask threw"));
            return;
        }
        if (task == null) {
            node.state = NodeState.FAILED;
            routeFailure(body, node, new Failure.Internal(null, "no task for kind " + node.spec.taskKind()));
            return;
        }
        node.task = task;
        try {
            task.start(body);
        } catch (RuntimeException | Error e) {
            endNode(body, node, "start threw", new Failure.Internal(e, "task.start threw"));
            return;
        }
        // A veto in onStart ends the node now; nothing waits for a tick to notice.
        if (task.state() == TaskState.FAILED) endFailedTask(body, node);
    }

    private void tickActive(B body, ExecNode<B> node) {
        Task<B> task = node.task;
        if (task == null) {
            endNode(body, node, "no task", new Failure.Internal(null, "the active node has no task instance"));
            return;
        }
        // A task that is not RUNNING under an ACTIVE node has ended, or is somewhere nothing explains. Either
        // way the node ends now: returning here would leave the goal running forever with nothing published.
        TaskState state = task.state();
        if (state == TaskState.FAILED) {
            endFailedTask(body, node);
            return;
        }
        if (state == TaskState.COMPLETED) {
            onTaskCompleted(body, node);
            return;
        }
        if (state != TaskState.RUNNING) {
            endNode(body, node, "unexpected state", new Failure.Internal(null,
                    "task is " + state + " under an active node"));
            return;
        }
        if (++node.budgetTicksUsed > config.budgetFor(node.spec.taskKind())) {
            endNode(body, node, "tick budget exceeded", new Failure.TimedOut(node.budgetTicksUsed, "tick budget"));
            return;
        }
        // Stuck watchdog: no signature change while RUNNING and not waiting. Paused ticks never get here.
        if (!task.waiting()) {
            long sig = host.progressSignature(body);
            if (sig != node.lastSignature) {
                node.lastSignature = sig;
                node.stuckTicks = 0;
            } else if (++node.stuckTicks >= config.stuckTicks()) {
                endNode(body, node, "stuck", new Failure.NoProgress(node.stuckTicks, "stuck"));
                return;
            }
        }
        TickResult result;
        try {
            result = task.tick(body);
        } catch (RuntimeException | Error e) {
            endNode(body, node, "tick threw", new Failure.Internal(e, "task.tick threw"));
            return;
        }
        if (result == null) {
            endNode(body, node, "no result", new Failure.Internal(null, "task.tick returned no result"));
            return;
        }
        switch (result) {
            case TickResult.Running running -> { }
            case TickResult.Completed completed -> onTaskCompleted(body, node);
            case TickResult.Failed failed -> {
                // The task ended itself: it has released what it held, so the executor does not abort it again.
                releaseTask(node);
                node.state = NodeState.FAILED;
                routeFailure(body, node, failed.failure() != null ? failed.failure()
                        : new Failure.Internal(null, "task failed without a failure"));
            }
        }
    }

    /** The task ended itself with COMPLETED: a hint only; the node is done when its postcondition holds. */
    private void onTaskCompleted(B body, ExecNode<B> node) {
        releaseTask(node); // it released what it held on its own: nothing to abort
        Evaluation ev = evaluateNode(body, node);
        if (ev.isSatisfied() || (ev.isUnknown() && node.executorInserted)) {
            // An executor-inserted node (recover-drops) has a postcondition the snapshot may never be able to
            // read; its task finishing is all there is to go on.
            node.state = NodeState.SATISFIED;
            afterNodeDone(body);
        } else if (ev.isUnknown()) {
            // The snapshot can no longer tell: the same route as an UNKNOWN pre-check, one Reach node and then
            // the node is judged again -- not a task that failed to make progress.
            node.state = NodeState.PENDING;
            handleUnknown(body, node);
        } else {
            // Completed is a hint; unmet counts as Failed(NO_PROGRESS).
            node.state = NodeState.FAILED;
            routeFailure(body, node, new Failure.NoProgress(node.budgetTicksUsed,
                    "completed with the postcondition unmet"));
        }
    }

    /** The task failed outside a tick result (a hook threw or vetoed, a callback failed it): route its failure. */
    private void endFailedTask(B body, ExecNode<B> node) {
        Failure failure = node.task.failure() != null ? node.task.failure()
                : new Failure.Internal(null, "task failed without a failure");
        releaseTask(node);
        node.state = NodeState.FAILED;
        routeFailure(body, node, failure);
    }

    /** Ends a node the executor is stopping: the task is aborted and released, the node fails and is routed. */
    private void endNode(B body, ExecNode<B> node, String abortReason, Failure failure) {
        retireTask(body, node, abortReason);
        node.state = NodeState.FAILED;
        routeFailure(body, node, failure);
    }

    /**
     * Runs a pause or resume call on a node's task. A throw from it, or a task that is FAILED afterwards
     * (an AbstractTask turns a throwing hook into FAILED), ends the node and routes the failure. True when it did.
     */
    private boolean hookEndedNode(B body, ExecNode<B> node, String what, Runnable call) {
        Task<B> task = node.task;
        try {
            call.run();
        } catch (RuntimeException | Error e) {
            endNode(body, node, what + " threw", new Failure.Internal(e, what + " threw"));
            return true;
        }
        if (task.state() == TaskState.FAILED) {
            endFailedTask(body, node);
            return true;
        }
        return false;
    }

    // --- Failure routing (design 3.4, Failure table) ----------------------------------------

    private void routeFailure(B body, ExecNode<B> node, Failure failure) {
        // An exception is logged with its stack trace whatever the node's flags say: nothing swallows a throw.
        if (failure instanceof Failure.Internal i && i.exception() != null) {
            logOnce("internal failure: " + failure.detail(), i.exception());
        }
        // A death is never dropped: it is counted, its area excluded and the recovery rule applied even when
        // the node is best-effort, so the best-effort test comes after it.
        if (node.spec.bestEffort() && failure.kind() != Failure.Kind.DIED) {
            node.state = NodeState.SKIPPED; // whatever the failure type says
            afterNodeDone(body);
            return;
        }
        switch (failure.kind()) {
            case UNREACHABLE, TARGET_GONE, TIMED_OUT, NO_PROGRESS -> {
                // One retry per node per failure kind, then a replan.
                if (node.retriedKinds.add(failure.kind())) {
                    node.state = NodeState.PENDING;
                    node.task = null;
                } else {
                    replan(body, PlanCause.NORMAL, failure);
                }
            }
            case MISSING_TOOL, MISSING_INPUT -> replan(body, PlanCause.NORMAL, failure);
            case NO_SOURCE -> {
                if (failure instanceof Failure.NoSource ns && !ns.source().isEmpty()) {
                    Set<String> sources = new HashSet<>(run.exclusions.sources());
                    sources.add(ns.source());
                    run.exclusions = new Exclusions(sources, run.exclusions.blocks(), run.exclusions.areas());
                }
                replan(body, PlanCause.NORMAL, failure);
            }
            case PROTECTED -> {
                if (failure instanceof Failure.Protected p) {
                    Set<Pos> blocks = new HashSet<>(run.exclusions.blocks());
                    blocks.add(p.pos());
                    run.exclusions = new Exclusions(run.exclusions.sources(), blocks, run.exclusions.areas());
                }
                replan(body, PlanCause.NORMAL, failure);
            }
            case HAZARD -> {
                if (failure instanceof Failure.Hazard h) {
                    run.exclusions = AreaExclusions.with(run.exclusions, h.dimension(), h.pos(),
                            config.hazardExclusionRadius(), h.hazard());
                }
                replan(body, PlanCause.NORMAL, failure);
            }
            case DIED -> {
                // Its node returns to PENDING, like the body-death path.
                node.state = NodeState.PENDING;
                death(body, (Failure.Died) failure);
            }
            // Nothing a replan can change would alter the answer: the reflex layer still owns the body (PREEMPT_STALLED),
            // the body found nowhere to do the work and the planner cannot see why (NO_PLACE), the inventory cannot take
            // what the step produces and the same plan would put it into the same full inventory (NO_ROOM), or the code
            // has a bug (INTERNAL). A retry or a replan would only repeat the failure.
            case PREEMPT_STALLED, NO_PLACE, NO_ROOM, INTERNAL -> giveUp(body, failure);
        }
    }

    // --- Replanning (design 3.4) ------------------------------------------------------------

    /** Charges the budgets for a plan-caused replan, then requests the plan. */
    private void replan(B body, PlanCause cause, Failure why) {
        if (cause == PlanCause.NORMAL || cause == PlanCause.REPAIR) {
            // The wall the bot hit is what a give-up reports; a replan with no failure behind it (a repair, a
            // drop in the goal) leaves the earlier one in place.
            if (why != null) run.lastReplanFailure = why;
            // A plan can only raise the total: a declared total below the default never lowers it.
            int cap = Math.max(config.maxTotalReplans(), Math.min(run.planDeclaredTotal, config.totalReplanCeiling()));
            if (run.counters.totalReplans >= cap) {
                giveUp(body, wall("total replan budget exhausted (" + cap + ")"));
                return;
            }
            if (run.counters.consecutiveWithoutProgress >= config.maxReplansWithoutProgress()) {
                giveUp(body, wall("replans without progress (" + config.maxReplansWithoutProgress() + ")"));
                return;
            }
            run.counters.totalReplans++;
        }
        requestPlan(body, cause);
    }

    /** What a give-up on a budget reports: the failure behind the latest replan, with the budget named. */
    private Failure wall(String budget) {
        Failure caused = run.lastReplanFailure;
        return caused == null ? new Failure.NoProgress(0, budget) : FailureNotes.with(caused, budget);
    }

    private void requestPlan(B body, PlanCause cause) {
        Snapshot snapshot = host.snapshot(body, run.goal);
        PlanRequest request = new PlanRequest(run.goal, snapshot, run.exclusions, host.profile(),
                Map.copyOf(run.checkpoints));
        PlanTicket newTicket = host.plan(request);
        if (newTicket == null) {
            // A null ticket would hang the goal; treat it as planner breakage.
            host.log("host.plan returned null", null);
            giveUp(body, new Failure.Internal(null, "host.plan returned null"));
            return;
        }
        ticket = newTicket;
        ticketCause = cause;
        ticketWaitTicks = 0;
    }

    private void clearTicket() {
        if (ticket != null) {
            try {
                ticket.cancel();
            } catch (RuntimeException | Error e) {
                host.log("ticket cancel threw", e);
            }
        }
        ticket = null;
    }

    private void pollTicket(B body) {
        if (!ticket.done()) {
            // A planner that never answers must not keep the goal RUNNING for ever: the ticks spent on this
            // ticket are counted, and past the deadline it is cancelled and the goal gives up.
            if (++ticketWaitTicks > config.planTimeoutTicks()) {
                int waited = ticketWaitTicks;
                clearTicket();
                giveUp(body, new Failure.TimedOut(waited, "planning did not finish within "
                        + config.planTimeoutTicks() + " ticks"));
            }
            return;
        }
        PlanCause cause = ticketCause;
        PlanOutcome outcome;
        try {
            outcome = ticket.outcome();
        } catch (Exception e) {
            clearTicket();
            host.log("planner threw", e);
            giveUp(body, new Failure.Internal(e, "planner threw"));
            return;
        }
        switch (outcome) {
            case PlanOutcome.Unplannable u -> {
                clearTicket();
                giveUp(body, u.failure());
            }
            case PlanOutcome.Planned p -> {
                Plan plan = p.plan();
                Snapshot fresh = host.snapshot(body, run.goal);
                long fp = host.inputFingerprint(run.goal, fresh);
                if (fp != plan.inputFingerprint() && rerunsInARow < config.planRerunLimit()) {
                    // Inputs moved under the planner: re-run, at most planRerunLimit in a row. The re-run keeps
                    // the cause that started it, so its result counts toward the budgets the same way.
                    rerunsInARow++;
                    clearTicket();
                    requestPlan(body, cause);
                    return;
                }
                rerunsInARow = 0;
                clearTicket();
                adoptPlan(body, plan, cause);
            }
        }
    }

    private void adoptPlan(B body, Plan plan, PlanCause cause) {
        run.planDeclaredTotal = plan.maxTotalReplans();
        // Executor-inserted nodes survive a replan, at the front, until they finish or fail.
        List<ExecNode<B>> roots = new ArrayList<>(ExecTree.retainInserted(run.roots));
        for (NodeSpec spec : plan.roots()) roots.add(ExecNode.of(spec, false, null));
        run.roots = roots;
        run.hasTree = true;

        long fingerprint = PlanFingerprint.of(run.roots);
        Evaluation ev = evaluateGoalNow(body);
        if (cause == PlanCause.NORMAL || cause == PlanCause.REPAIR) {
            boolean progress = ev.matched() > run.matchedAtAdoption || fingerprint != run.lastPlanFingerprint;
            if (progress) run.counters.consecutiveWithoutProgress = 0;
            else run.counters.consecutiveWithoutProgress++;
        }
        run.lastPlanFingerprint = fingerprint;
        run.matchedAtAdoption = ev.matched();

        // Checkpoints carry over to a new node with the same identity; the rest are dropped.
        Map<String, Map<String, String>> carried = new HashMap<>();
        Set<String> identities = new HashSet<>();
        for (ExecNode<B> r : run.roots) ExecTree.collectIdentities(r, identities);
        for (String id : identities) {
            Map<String, String> cp = run.checkpoints.get(id);
            if (cp != null) carried.put(id, cp);
        }
        run.checkpoints.clear();
        run.checkpoints.putAll(carried);

        if (ev.isSatisfied()) finish(body, GoalStatus.SATISFIED, null);
    }

    // --- Death ------------------------------------------------------------------------------

    /**
     * A task reported DIED and the body is still there: count it, then replan at once with the death area
     * excluded. (A body that died goes through {@link #onDeath} and waits for its respawn instead.)
     */
    private void death(B body, Failure.Died d) {
        abortActive(body, "died");
        ExecNode<B> active = findActive();
        if (active != null) active.state = NodeState.PENDING;
        if (deaths.count(run.counters)) {
            finish(body, GoalStatus.GAVE_UP, d);
            return;
        }
        clearTicket();
        planAfterDeath(body, List.of(d));
    }

    /** The respawned body is ticking: the death replan, uncounted against the plan's budgets. */
    private void deathReplan(B body) {
        planAfterDeath(body, deaths.takeAll());
    }

    /**
     * Replans from live state after one death or several: the area around each is excluded so the plan does not
     * walk back into the same shaft, and a recover-drops node goes in front of the new tree for each death whose
     * drops can still be reached. Earliest death first: its drops despawn first.
     */
    private void planAfterDeath(B body, List<Failure.Died> died) {
        Snapshot now = host.snapshot(body, run.goal);
        int at = 0;
        for (Failure.Died d : died) {
            ExecNode<B> recover = deaths.recoverDrops(d, now);
            if (recover != null) {
                run.roots.add(at++, recover);
                run.hasTree = true;
            }
            run.exclusions = deaths.excludeArea(run.exclusions, d);
        }
        requestPlan(body, PlanCause.DEATH);
    }

    private void insertReach(String dimension, Pos pos) {
        Goal.Reach reach = new Goal.Reach(dimension, pos, UNKNOWN_REACH_RADIUS);
        Map<String, String> params = Map.of("pos", pos.encode(), "dimension", dimension);
        NodeSpec spec = new NodeSpec(reach, REACH_KIND, params, "", false, List.of());
        run.roots.add(0, ExecNode.of(spec, true, null));
        run.hasTree = true;
    }

    /** Where an UNKNOWN evaluation tells the executor to walk: the container, the anchor. */
    private Pos positionFor(Goal goal) {
        return switch (goal) {
            case Goal.Stockpile g -> g.container();
            case Goal.Build g -> g.anchor();
            case Goal.Reach g -> g.pos();
            default -> null;
        };
    }

    private String dimensionFor(Goal goal) {
        return switch (goal) {
            case Goal.Stockpile g -> g.dimension();
            case Goal.Build g -> g.dimension();
            case Goal.Reach g -> g.dimension();
            default -> null;
        };
    }

    // --- Preemption (design 3.4) ------------------------------------------------------------

    /** The reflex layer took the body: its task is paused and kept. Counting is {@link Preemption}'s. */
    private void pauseForReflex(B body) {
        ExecNode<B> active = run == null ? null : findActive();
        if (run != null && run.status == GoalStatus.RUNNING && active != null && active.task != null
                && active.task.state() == TaskState.RUNNING) {
            String cause = preemption.cause();
            hookEndedNode(body, active, "task.pause", () -> active.task.pause(body, cause));
        }
    }

    /** The gate has been shut past a limit: the node fails PREEMPT_STALLED and the goal gives up at once. */
    private void stallPreempted(B body) {
        ExecNode<B> active = findActive();
        if (active != null) {
            retireTask(body, active, "preempt stalled");
            active.state = NodeState.FAILED;
        }
        // The reflex layer still owns the body: give up at once, no replan can run.
        giveUp(body, new Failure.PreemptStalled(preemption.cause(), "held past the preemption limit"));
    }

    private void releaseHold(B body) {
        ExecNode<B> active = findActive();
        if (active == null) return;
        Evaluation ev = evaluateNode(body, active);
        if (ev.isSatisfied()) {
            retireTask(body, active, "postcondition holds at resume");
            active.state = NodeState.SATISFIED;
            afterNodeDone(body);
        } else if (active.task != null && active.task.state() == TaskState.PAUSED) {
            hookEndedNode(body, active, "task.resume", () -> active.task.resume(body));
        }
    }

    // --- Evaluation, finish, helpers --------------------------------------------------------

    private Evaluation evaluateNode(B body, ExecNode<B> node) {
        Snapshot snapshot = host.snapshot(body, node.spec.goal());
        return postconditions.of(node.spec.goal()).evaluate(snapshot);
    }

    private Evaluation evaluateGoalNow(B body) {
        Snapshot snapshot = host.snapshot(body, run.goal);
        Evaluation ev = postconditions.of(run.goal).evaluate(snapshot);
        run.lastEval = ev;
        return ev;
    }

    /** A give-up is a finish: what it reports is worked out there. */
    private void giveUp(B body, Failure failure) {
        finish(body, GoalStatus.GAVE_UP, failure);
    }

    /**
     * The body the executor may look at: the last one it was ticked with, unless that one died and no replacement
     * has been created yet.
     */
    private B liveBody() {
        return deaths.awaitingRespawn() ? null : lastBody;
    }

    /**
     * Ends the goal: retires its active task, publishes the result exactly once, forgets the goal, and lets the
     * queue advance. {@code body} is the live body to evaluate the goal against, or null when there is none.
     */
    private void finish(B body, GoalStatus status, Failure failure) {
        if (run == null) return;
        // No task outlives its goal, however the goal ended: a give-up reached through the guard (a host call
        // that threw) arrives here with its task still RUNNING or PAUSED.
        abortActive(lastBody, "goal ended");
        clearTicket();
        // Nothing of this goal may be inherited by the next one.
        deaths.clear();
        restartPending = false;
        initialPlanPending = false;
        rerunsInARow = 0;
        GoalResult result;
        if (status == GoalStatus.SATISFIED) {
            Evaluation ev = run.lastEval == null ? new Evaluation(Evaluation.Status.UNSATISFIED, 0, 0, List.of())
                    : run.lastEval;
            result = new GoalResult(run.goal, GoalResult.Status.SATISFIED, ev.required(), ev.required(),
                    List.of(), null);
        } else {
            Evaluation ev = evaluationForResult(body);
            if (status == GoalStatus.CANCELLED) {
                result = new GoalResult(run.goal, GoalResult.Status.CANCELLED, ev.matched(), ev.required(),
                        ev.unmet(), null);
            } else {
                result = new GoalResult(run.goal,
                        ev.matched() > 0 ? GoalResult.Status.PARTIAL : GoalResult.Status.FAILED,
                        ev.matched(), ev.required(), ev.unmet(), failure);
            }
        }
        run = null;
        host.publish(result);
        maybeStartNext();
    }

    /**
     * What a result that is not SATISFIED says the bot has: the goal evaluated now when there is a live body, so a
     * goal that is 3 of 5 done is PARTIAL however stale the last evaluation was. The last evaluation stands when
     * there is no body, when evaluating throws, and when the fresh answer is UNKNOWN while the last one was known:
     * UNKNOWN is "cannot tell" and carries no count, not "nothing".
     */
    private Evaluation evaluationForResult(B body) {
        Evaluation last = run.lastEval;
        if (body != null) {
            try {
                Evaluation fresh = evaluateGoalNow(body);
                if (!fresh.isUnknown() || last == null || last.isUnknown()) return fresh;
            } catch (RuntimeException | Error e) {
                logOnce("final goal evaluation threw", e);
            }
        }
        return last == null ? new Evaluation(Evaluation.Status.UNSATISFIED, 0, 0, List.of()) : last;
    }

    private void abortActive(B body, String reason) {
        ExecNode<B> active = findActive();
        if (active != null) retireTask(body, active, reason);
    }

    /** Aborts the node's task, then lets go of it. For a task the executor is stopping, not one that ended. */
    private void retireTask(B body, ExecNode<B> node, String reason) {
        Task<B> task = node.task;
        if (task == null) return;
        try {
            task.abort(body, reason);
        } catch (RuntimeException | Error e) {
            host.log("task abort threw", e);
        }
        releaseTask(node);
    }

    /**
     * Lets go of a task that has ended, one way or another: keeps its checkpoint, logs whatever a hook threw
     * (even when a typed failure took the routing, so the stack trace is not lost), and drops the instance.
     * Never throws: a task is host code, and releasing it must not take {@code cancel}, a death or a routed
     * failure down with it.
     */
    private void releaseTask(ExecNode<B> node) {
        Task<B> task = node.task;
        if (task == null) return;
        node.task = null; // first, so nothing below can leave a half-released node behind
        String identity = NodeIdentity.of(node.spec);
        Map<String, String> cp = Checkpoints.read(host, identity, task, "release");
        if (!cp.isEmpty()) run.checkpoints.put(identity, cp);
        try {
            Throwable hookError = task.lastHookError();
            if (hookError != null) logOnce("a hook of the " + node.spec.taskKind() + " task threw", hookError);
        } catch (RuntimeException | Error e) {
            host.log("task lastHookError threw", e);
        }
    }

    private ExecNode<B> findActive() {
        return ExecTree.findActive(run.roots);
    }

    private void collectView(ExecNode<B> node, int depth, List<ExecutorView.NodeView> into) {
        into.add(new ExecutorView.NodeView(depth, node.spec.goal(), node.spec.taskKind(), node.spec.source(),
                ExecTree.derivedState(node), node.spec.bestEffort(), node.executorInserted));
        for (ExecNode<B> c : node.children) collectView(c, depth + 1, into);
    }
}
