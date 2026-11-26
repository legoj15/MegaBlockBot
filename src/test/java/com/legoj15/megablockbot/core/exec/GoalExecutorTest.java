package com.legoj15.megablockbot.core.exec;

import static org.junit.jupiter.api.Assertions.*;

import com.legoj15.megablockbot.core.ItemKey;
import com.legoj15.megablockbot.core.Pos;
import com.legoj15.megablockbot.core.ToolClass;
import com.legoj15.megablockbot.core.ToolTier;
import com.legoj15.megablockbot.core.goal.ComponentCheck;
import com.legoj15.megablockbot.core.goal.Goal;
import com.legoj15.megablockbot.core.goal.Postconditions;
import com.legoj15.megablockbot.core.plan.NodeSpec;
import com.legoj15.megablockbot.core.plan.Plan;
import com.legoj15.megablockbot.core.plan.PlanOutcome;
import com.legoj15.megablockbot.core.snapshot.Snapshot;
import com.legoj15.megablockbot.core.task.Failure;
import com.legoj15.megablockbot.core.task.TaskState;
import com.legoj15.megablockbot.core.task.TickResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The rules of design 3.4, one named test per rule, driven through the {@link Fakes} scripted host: a
 * settable snapshot, queued plan tickets, queued task behaviours, a recording publish.
 */
class GoalExecutorTest {

    private static final Object BODY = new Object();

    private final ItemKey diamond = ItemKey.of("diamond");
    private final Goal.HaveItem haveDiamond = new Goal.HaveItem(diamond, 1);

    private Fakes.FakeHost host;
    private Postconditions postconditions;
    private GoalExecutor<Object> exec;
    private ExecutorConfig config;

    @BeforeEach
    void setUp() {
        host = new Fakes.FakeHost();
        postconditions = new Postconditions();
        config = ExecutorConfig.defaults();
        exec = new GoalExecutor<>(host, postconditions, config);
    }

    private void rebuild() {
        exec = new GoalExecutor<>(host, postconditions, config);
    }

    private void tick() {
        exec.tick(BODY, ReflexHold.NONE);
    }

    private void ticks(int n) {
        for (int i = 0; i < n; i++) tick();
    }

    private NodeSpec diamondLeaf(String source) {
        return NodeSpec.leaf(haveDiamond, "mine", Map.of("target", diamond.id()), source);
    }

    /** Submits the goal, offers one plan with the given roots and runs until the plan is adopted. */
    private void adopt(Goal goal, NodeSpec... roots) {
        host.offerPlan(roots);
        exec.submit(goal);
        ticks(3);
    }

    private void runToActive(NodeSpec... roots) {
        adopt(haveDiamond, roots);
        tick();
    }

    private ExecutorView.NodeView node(int i) {
        return exec.view().nodes().get(i);
    }

    private Fakes.FakeTask taskOf(int i) {
        return (Fakes.FakeTask) host.createdTasks.get(i);
    }

    // --- Node lifecycle ----------------------------------------------------------------------

    @Test
    void postconditionHoldingBeforeStartMarksNodeSatisfiedWithoutRunningIt() {
        Goal.HaveItem sticks = new Goal.HaveItem(ItemKey.of("stick"), 1);
        NodeSpec torch = NodeSpec.leaf(new Goal.HaveItem(ItemKey.of("torch"), 1), "craft", Map.of(), "");
        host.snapshot = Snapshot.builder().item(ItemKey.of("torch"), 1).build();
        adopt(sticks, torch);
        tick(); // advance: the torch node pre-satisfies, then the tree runs out and a repair replan starts
        assertEquals(0, host.taskRequests.size()); // PENDING -> SATISFIED without running
        assertEquals(NodeState.SATISFIED, node(0).state());
        assertEquals(2, host.planRequests.size()); // the goal itself is still unsatisfied
    }

    @Test
    void pendingToActiveStartsANewTaskInstance() {
        runToActive(diamondLeaf("mine:ore"));
        assertEquals(1, host.taskRequests.size());
        assertEquals(NodeState.ACTIVE, node(0).state());
        assertEquals(TaskState.RUNNING, taskOf(0).state());
        assertTrue(taskOf(0).calls.contains("start"));
    }

    @Test
    void activeToSatisfiedWhenTaskCompletedAndPostconditionHolds() {
        Goal.HaveItem sticks = new Goal.HaveItem(ItemKey.of("stick"), 1);
        host.tasks.add(Fakes.FakeTask.completing());
        host.offerPlan(diamondLeaf("mine:ore"));
        exec.submit(sticks);
        ticks(3); // the diamond leaf is ACTIVE
        host.snapshot = Snapshot.builder().item(diamond, 1).build();
        tick(); // completes and the postcondition holds
        assertEquals(NodeState.SATISFIED, node(0).state());
        assertTrue(host.published.isEmpty()); // the root goal (sticks) is still running
    }

    @Test
    void activeToFailedWhenTaskCompletedWithPostconditionUnmetCountsAsNoProgress() {
        host.tasks.add(Fakes.FakeTask.completing());
        runToActive(diamondLeaf("mine:ore")); // the completing task ran with an empty snapshot
        tick(); // the retry starts a fresh task instance for the same node
        assertEquals(2, host.taskRequests.size());
        assertNotSame(taskOf(0), taskOf(1)); // failed task instances are never restarted
        assertEquals(NodeState.ACTIVE, node(0).state()); // PENDING -> ACTIVE again on the retry
    }

    @Test
    void failedTaskFailsTheNodeAndOneRetryIsGrantedPerFailureKind() {
        host.tasks.add(Fakes.FakeTask.failing(new Failure.Unreachable("x", "y", "no path")));
        host.tasks.add(Fakes.FakeTask.failing(new Failure.Unreachable("x", "y", "no path")));
        runToActive(diamondLeaf("mine:ore"));
        tick(); // the retry's fresh task is running
        assertEquals(2, host.taskRequests.size());
        assertNotSame(taskOf(0), taskOf(1));
        assertEquals(NodeState.ACTIVE, node(0).state());
    }

    @Test
    void bestEffortFailureIsSkippedWithNoRetryOrReplanForIt() {
        NodeSpec torch = NodeSpec.leaf(new Goal.HaveItem(ItemKey.of("torch"), 1), "craft", Map.of(), "")
                .asBestEffort();
        host.tasks.add(Fakes.FakeTask.failing(new Failure.Unreachable("t", "r", "blocked")));
        runToActive(NodeSpec.group(haveDiamond, List.of(torch)));
        tick();
        assertEquals(1, host.taskRequests.size()); // no retry
        assertEquals(NodeState.SKIPPED, node(1).state());
        assertTrue(host.published.isEmpty()); // a node-level failure handled by the executor
    }

    @Test
    void nonLeafStateIsDerivedNotStored() {
        NodeSpec leafB = NodeSpec.leaf(new Goal.HaveItem(ItemKey.of("stick"), 1), "craft", Map.of(), "");
        runToActive(NodeSpec.group(haveDiamond, List.of(diamondLeaf("a"), leafB)));
        assertEquals(NodeState.ACTIVE, node(0).state()); // the group derives ACTIVE from its child
        assertEquals(NodeState.ACTIVE, node(1).state());
        assertEquals(NodeState.PENDING, node(2).state());
    }

    @Test
    void satisfiedNodesAreNotRecheckedContinuously() {
        Goal.HaveItem sticks = new Goal.HaveItem(ItemKey.of("stick"), 1);
        NodeSpec torch = NodeSpec.leaf(new Goal.HaveItem(ItemKey.of("torch"), 1), "craft", Map.of(), "");
        NodeSpec afterwards = NodeSpec.leaf(new Goal.HaveItem(ItemKey.of("stone"), 1), "mine", Map.of(), "");
        host.snapshot = Snapshot.builder().item(ItemKey.of("torch"), 1).build(); // torch pre-satisfies
        adopt(sticks, torch, afterwards);
        tick(); // the torch node is SATISFIED without running; the stone node starts
        assertEquals(NodeState.SATISFIED, node(0).state());
        host.snapshot = Snapshot.builder().build(); // the torch postcondition no longer holds
        tick();
        tick();
        assertEquals(NodeState.SATISFIED, node(0).state()); // re-checked only at load and on replan
    }

    @Test
    void aReplanReplacesTheSubtreeOfNonTerminalNodes() {
        NodeSpec dropped = NodeSpec.leaf(new Goal.HaveItem(ItemKey.of("stick"), 1), "craft", Map.of(), "");
        host.tasks.add(Fakes.FakeTask.failing(new Failure.MissingInput(diamond, 1, "")));
        host.offerPlan(NodeSpec.group(haveDiamond, List.of(diamondLeaf("mine:ore"), dropped)));
        host.offerPlan(diamondLeaf("mine:oreB")); // the replan emits only the mining leaf
        exec.submit(haveDiamond);
        ticks(6); // adopt, start, fail, replan, adopt the new tree, start its node
        assertEquals(1, exec.view().nodes().size()); // the group and the stick leaf are gone
        assertEquals("mine:oreB", node(0).source());
    }

    // --- Goal evaluation ---------------------------------------------------------------------

    @Test
    void satisfiedGoalEndsEvenIfNodesRemain() {
        NodeSpec second = NodeSpec.leaf(new Goal.HaveItem(ItemKey.of("stick"), 1), "craft", Map.of(), "");
        host.tasks.add(Fakes.FakeTask.completing());
        runToActive(diamondLeaf("mine:ore"), second);
        host.snapshot = Snapshot.builder().item(diamond, 1).build();
        tick();
        assertEquals(1, host.taskRequests.size()); // the second node never started
        assertEquals(GoalResult.Status.SATISFIED, host.published.get(0).status());
    }

    @Test
    void treeRunsOutUnsatisfiedTriggersRepairReplan() {
        Goal root = new Goal.HaveItem(ItemKey.of("stick"), 1);
        NodeSpec other = NodeSpec.leaf(new Goal.HaveItem(ItemKey.of("torch"), 1), "craft", Map.of(), "");
        host.snapshot = Snapshot.builder().item(ItemKey.of("torch"), 1).build();
        adopt(root, other);
        tick(); // node pre-satisfies, tree runs out, goal unsatisfied -> repair replan
        assertEquals(2, host.planRequests.size());
        assertEquals(1, exec.view().repairReplans());
        assertTrue(exec.view().totalReplans() >= 1); // counted in the totals too
    }

    // --- UNKNOWN -----------------------------------------------------------------------------

    @Test
    void unknownInsertsOneReachNodeInFrontAndEvaluatesAgainAfterIt() {
        Goal.Stockpile stock = new Goal.Stockpile(diamond, 1, "minecraft:overworld", new Pos(100, 64, 100));
        host.offerPlan(NodeSpec.leaf(stock, "deposit", Map.of(), ""));
        exec.submit(stock);
        ticks(3); // adopt, then the leaf's pre-check is UNKNOWN: a Reach goes in front and runs next
        assertEquals(2, exec.view().nodes().size());
        ExecutorView.NodeView reach = node(0);
        assertEquals("reach", reach.taskKind());
        assertTrue(reach.executorInserted());
        assertEquals(new Pos(100, 64, 100), ((Goal.Reach) reach.goal()).pos());
        assertEquals(1, host.taskRequests.size());
        assertEquals("reach", host.taskRequests.get(0).taskKind());
    }

    @Test
    void stillUnknownAfterReachingFailsTheLeafWithTargetGoneThenReplans() {
        Goal.Stockpile stock = new Goal.Stockpile(diamond, 1, "minecraft:overworld", new Pos(100, 64, 100));
        // The body already stands at the container, but the snapshot holds no sight of it.
        host.snapshot = Snapshot.builder().pos(new Pos(100, 64, 100)).build();
        host.offerPlan(NodeSpec.leaf(stock, "deposit", Map.of(), ""));
        exec.submit(stock);
        ticks(4); // insert reach, reach pre-satisfies, leaf still UNKNOWN -> TARGET_GONE, retry, TARGET_GONE again
        assertEquals(2, host.planRequests.size()); // retry exhausted: replan
    }

    @Test
    void goalUnknownWithoutAPositionGivesUpWithTargetGoneAtOnce() {
        Goal.HaveItem mending = new Goal.HaveItem(ItemKey.of("enchanted_book"), 1,
                ComponentCheck.enchantment("minecraft:mending", 1));
        NodeSpec other = NodeSpec.leaf(new Goal.HaveItem(ItemKey.of("book"), 1), "craft", Map.of(), "");
        // The book is carried but the snapshot carries no stack facts: UNKNOWN, and nothing to reach.
        host.snapshot = Snapshot.builder().item(ItemKey.of("enchanted_book"), 1).item(ItemKey.of("book"), 1).build();
        adopt(mending, other);
        tick(); // the leaf pre-satisfies, the tree runs out, the goal is UNKNOWN with no position
        assertEquals(GoalResult.Status.FAILED, host.published.get(0).status());
        assertEquals(Failure.Kind.TARGET_GONE, host.published.get(0).failure().kind());
    }

    // --- Failure routing ---------------------------------------------------------------------

    @Test
    void unreachableRetriesOnceThenReplans() {
        retryOnceThenReplans(new Failure.Unreachable("t", "no path", ""));
    }

    @Test
    void targetGoneRetriesOnceThenReplans() {
        retryOnceThenReplans(new Failure.TargetGone("diamond_ore", ""));
    }

    @Test
    void timedOutRetriesOnceThenReplans() {
        retryOnceThenReplans(new Failure.TimedOut(10, ""));
    }

    @Test
    void noProgressRetriesOnceThenReplans() {
        retryOnceThenReplans(new Failure.NoProgress(10, ""));
    }

    private void retryOnceThenReplans(Failure failure) {
        host.tasks.add(Fakes.FakeTask.failing(failure));
        host.tasks.add(Fakes.FakeTask.failing(failure));
        host.offerPlan(diamondLeaf("mine:ore"));
        exec.submit(haveDiamond);
        ticks(6); // adopt, start, fail, retry, fail again, replan requested
        assertEquals(2, host.taskRequests.size());
        assertEquals(2, host.planRequests.size());
    }

    @Test
    void missingToolReplansWithoutRetry() {
        replansWithoutRetry(new Failure.MissingTool(ToolClass.PICKAXE, ToolTier.IRON, ""));
    }

    @Test
    void missingInputReplansWithoutRetry() {
        replansWithoutRetry(new Failure.MissingInput(diamond, 1, ""));
    }

    private void replansWithoutRetry(Failure failure) {
        host.tasks.add(Fakes.FakeTask.failing(failure));
        runToActive(diamondLeaf("mine:ore"));
        tick();
        assertEquals(1, host.taskRequests.size());
        assertEquals(2, host.planRequests.size());
    }

    @Test
    void noSourceExcludesThatSourceAndReplans() {
        host.tasks.add(Fakes.FakeTask.failing(new Failure.NoSource(diamond, "mine:ore", "")));
        host.offerPlan(diamondLeaf("mine:ore"));
        exec.submit(haveDiamond);
        ticks(5);
        assertTrue(host.planRequests.get(1).exclusions().sources().contains("mine:ore"));
    }

    @Test
    void protectedFailureExcludesThePositionAndReplans() {
        Pos pos = new Pos(10, 64, 10);
        host.tasks.add(Fakes.FakeTask.failing(new Failure.Protected(pos, "owner bed", "")));
        host.offerPlan(diamondLeaf("mine:ore"));
        exec.submit(haveDiamond);
        ticks(5);
        assertTrue(host.planRequests.get(1).exclusions().blocks().contains(pos));
    }

    @Test
    void hazardFailureExcludesAnAreaOfConfiguredRadiusAndReplans() {
        Pos pos = new Pos(10, 64, 10);
        host.tasks.add(Fakes.FakeTask.failing(new Failure.Hazard("lava", "minecraft:overworld", pos, "")));
        host.offerPlan(diamondLeaf("mine:ore"));
        exec.submit(haveDiamond);
        ticks(5);
        var areas = host.planRequests.get(1).exclusions().areas();
        assertEquals(1, areas.size());
        assertEquals(8, areas.get(0).radius()); // hazardExclusionRadius
        assertEquals(pos, areas.get(0).center());
    }

    @Test
    void taskReportedDeathReturnsTheNodeToPendingAndReplansWithTheDeathArea() {
        Failure.Died died = new Failure.Died("minecraft:overworld", new Pos(0, 40, 0),
                List.of(new Pos(1, 40, 1)), 100L, "");
        host.tasks.add(Fakes.FakeTask.failing(died));
        host.offerPlan(diamondLeaf("mine:ore"));
        exec.submit(haveDiamond);
        ticks(5);
        assertEquals(1, exec.view().deaths());
        assertEquals(0, exec.view().totalReplans()); // a death replan is not counted
        var areas = host.planRequests.get(1).exclusions().areas();
        assertEquals(16, areas.get(0).radius()); // deathExclusionRadius
        assertEquals(new Pos(0, 40, 0), areas.get(0).center());
        assertTrue(exec.view().nodes().stream().noneMatch(n -> "recover_drops".equals(n.taskKind())));
    }

    @Test
    void internalFailureGivesUpTheGoalAtOnce() {
        host.tasks.add(Fakes.FakeTask.failing(new Failure.Internal(null, "bug")));
        runToActive(diamondLeaf("mine:ore"));
        tick();
        assertEquals(1, host.taskRequests.size());
        assertEquals(GoalResult.Status.FAILED, host.published.get(0).status());
        assertEquals(Failure.Kind.INTERNAL, host.published.get(0).failure().kind());
    }

    @Test
    void aThrowingTaskFailsWithInternalThatCarriesTheExceptionAndGivesUp() {
        host.tasks.add(Fakes.FakeTask.throwingOnTick());
        runToActive(diamondLeaf("mine:ore"));
        tick();
        assertEquals(GoalResult.Status.FAILED, host.published.get(0).status());
        Failure.Internal internal = (Failure.Internal) host.published.get(0).failure();
        assertNotNull(internal.exception()); // carried, never swallowed
    }

    // --- Replanning --------------------------------------------------------------------------

    @Test
    void consecutiveReplansWithoutProgressGiveUp() {
        config = with(c -> c.maxReplansWithoutProgress = 2);
        rebuild();
        Failure.MissingInput missing = new Failure.MissingInput(diamond, 1, "");
        for (int i = 0; i < 4; i++) {
            host.tasks.add(Fakes.FakeTask.failing(missing));
            host.offerPlan(diamondLeaf("mine:ore")); // the same shape every time: no progress
        }
        exec.submit(haveDiamond);
        ticks(20);
        assertEquals(GoalResult.Status.FAILED, host.published.get(0).status());
        // The wall the bot hit is what is published, not a generic "no progress".
        assertEquals(Failure.Kind.MISSING_INPUT, host.published.get(0).failure().kind());
    }

    @Test
    void aChangedPlanFingerprintCountsAsProgressAndResetsTheStreak() {
        config = with(c -> c.maxReplansWithoutProgress = 2);
        rebuild();
        Failure.MissingInput missing = new Failure.MissingInput(diamond, 1, "");
        for (int i = 0; i < 4; i++) {
            host.tasks.add(Fakes.FakeTask.failing(missing));
            host.offerPlan(diamondLeaf(i % 2 == 0 ? "mine:oreA" : "mine:oreB")); // the source changes
        }
        exec.submit(haveDiamond);
        ticks(30);
        assertTrue(host.published.isEmpty()); // never hit the no-progress limit
        assertEquals(0, exec.view().consecutiveReplansWithoutProgress());
        assertEquals(5, host.planRequests.size()); // the initial plan plus four replans
    }

    @Test
    void aRisenMatchedCountCountsAsProgress() {
        config = with(c -> c.maxReplansWithoutProgress = 2);
        rebuild();
        Goal.HaveItem five = new Goal.HaveItem(diamond, 5);
        NodeSpec leaf = NodeSpec.leaf(five, "mine", Map.of(), "mine:ore");
        Failure.MissingInput missing = new Failure.MissingInput(diamond, 1, "");
        for (int i = 0; i < 3; i++) {
            host.tasks.add(Fakes.FakeTask.failing(missing));
            host.offerPlan(leaf); // same fingerprint throughout: only the matched count moves
        }
        exec.submit(five);
        ticks(3); // request, adopt, start the first task
        tick();   // it fails: replan charged
        host.snapshot = Snapshot.builder().item(diamond, 1).build();
        tick();   // adopted with matched risen: progress
        tick();   // start the second task
        tick();   // fails: replan charged (the streak was reset)
        host.snapshot = Snapshot.builder().item(diamond, 2).build();
        tick();   // adopted with matched risen again: progress
        tick();
        tick();   // the third task fails and a fourth replan is charged
        assertEquals(4, host.planRequests.size());
        assertTrue(host.published.isEmpty());
        assertEquals(0, exec.view().consecutiveReplansWithoutProgress());
    }

    @Test
    void theTotalReplanBudgetGivesUp() {
        config = with(c -> c.maxTotalReplans = 1);
        rebuild();
        Failure.MissingInput missing = new Failure.MissingInput(diamond, 1, "");
        host.tasks.add(Fakes.FakeTask.failing(missing));
        host.tasks.add(Fakes.FakeTask.failing(missing));
        host.offerPlan(diamondLeaf("mine:ore"));
        host.offerPlan(diamondLeaf("mine:ore")); // the one replan the budget allows
        exec.submit(haveDiamond);
        ticks(10);
        assertEquals(GoalResult.Status.FAILED, host.published.get(0).status());
        assertEquals(Failure.Kind.MISSING_INPUT, host.published.get(0).failure().kind());
    }

    @Test
    void aPlansDeclaredHigherTotalIsHonouredUpToTheCeiling() {
        config = with(c -> {
            c.maxTotalReplans = 2;
            c.totalReplanCeiling = 3;
            c.maxReplansWithoutProgress = 10; // only the total may end this goal
        });
        rebuild();
        Failure.MissingInput missing = new Failure.MissingInput(diamond, 1, "");
        for (int i = 0; i < 4; i++) {
            host.tasks.add(Fakes.FakeTask.failing(missing));
            host.tickets.add(new Fakes.FakeTicket(new PlanOutcome.Planned(
                    new Plan(List.of(diamondLeaf("mine:ore")), 1L, 99, List.of())), 1)); // declares 99
        }
        exec.submit(haveDiamond);
        ticks(30);
        assertEquals(GoalResult.Status.FAILED, host.published.get(0).status()); // the ceiling of 3 wins
        assertEquals(4, host.planRequests.size()); // initial + 3 replans, then give up
    }

    @Test
    void repairReplansAreCapped() {
        config = with(c -> c.maxRepairReplans = 1);
        rebuild();
        Goal root = new Goal.HaveItem(ItemKey.of("stick"), 1);
        NodeSpec other = NodeSpec.leaf(new Goal.HaveItem(ItemKey.of("torch"), 1), "craft", Map.of(), "");
        host.tasks.add(Fakes.FakeTask.completing());
        host.tasks.add(Fakes.FakeTask.completing());
        host.snapshot = Snapshot.builder().item(ItemKey.of("torch"), 1).build();
        host.offerPlan(other);
        host.offerPlan(other); // the one repair replan the cap allows
        exec.submit(root);
        ticks(20);
        assertEquals(GoalResult.Status.FAILED, host.published.get(0).status());
        assertEquals(2, host.planRequests.size()); // initial + one repair, then give up
        // No failure ever caused a replan here, so there is no wall to report.
        assertEquals(Failure.Kind.NO_PROGRESS, host.published.get(0).failure().kind());
    }

    // --- Adopting a plan ---------------------------------------------------------------------

    @Test
    void thePlanIsAdoptedWhenTheInputFingerprintMatches() {
        runToActive(diamondLeaf("mine:ore"));
        assertEquals(1, host.planRequests.size());
        assertEquals(1, exec.view().nodes().size());
    }

    @Test
    void aMovedInputFingerprintReRunsThePlan() {
        host.offerPlan(1L, diamondLeaf("mine:ore"));
        host.inputFingerprint = 2L; // the snapshot moved under the planner
        host.offerPlan(2L, diamondLeaf("mine:oreB"));
        exec.submit(haveDiamond);
        ticks(4);
        assertEquals(2, host.planRequests.size());
        assertEquals("mine:oreB", node(0).source());
    }

    @Test
    void afterTheRerunLimitTheNextResultIsAdoptedRegardless() {
        host.inputFingerprint = 2L;
        for (int i = 0; i < 4; i++) host.offerPlan(1L, diamondLeaf("mine:ore"));
        exec.submit(haveDiamond);
        ticks(8);
        assertEquals(3, host.planRequests.size()); // planRerunLimit is 2
        assertEquals("mine:ore", node(0).source());
        assertTrue(exec.active());
    }

    @Test
    void aThrowingPlannerGivesUpWithInternalAndLogs() {
        host.tickets.add(Fakes.FakeTicket.throwing(new IllegalStateException("planner bug")));
        exec.submit(haveDiamond);
        ticks(3);
        assertEquals(GoalResult.Status.FAILED, host.published.get(0).status());
        assertEquals(Failure.Kind.INTERNAL, host.published.get(0).failure().kind());
        assertFalse(host.logs.isEmpty());
    }

    @Test
    void unplannableGivesUpWithItsFailure() {
        Failure.NoSource noSource = new Failure.NoSource(diamond, "", "no source left");
        host.tickets.add(Fakes.FakeTicket.unplannable(noSource));
        exec.submit(haveDiamond);
        ticks(3);
        assertEquals(GoalResult.Status.FAILED, host.published.get(0).status());
        assertEquals(noSource, host.published.get(0).failure());
    }

    // --- Death -------------------------------------------------------------------------------

    @Test
    void deathAbortsTheActiveTaskAndItsNodeReturnsToPending() {
        host.tasks.add(new Fakes.FakeTask());
        runToActive(diamondLeaf("mine:ore"));
        exec.onDeath(new Failure.Died("minecraft:overworld", new Pos(5, 60, 5), List.of(), 1L, ""));
        assertTrue(taskOf(0).calls.stream().anyMatch(c -> c.startsWith("abort")));
        assertEquals(NodeState.PENDING, node(0).state());
        assertEquals(1, exec.view().deaths());
    }

    @Test
    void afterRespawnTheDeathReplanRunsUncountedWithRecoverDropsInFront() {
        host.offerPlan(diamondLeaf("mine:ore"));
        host.offerPlan(diamondLeaf("mine:ore"));
        exec.submit(haveDiamond);
        ticks(3); // adopted, the node is running on the default task
        Failure.Died died = new Failure.Died("minecraft:overworld", new Pos(5, 60, 5),
                List.of(new Pos(6, 60, 6)), 1L, "");
        host.dropsRecoverable = true;
        exec.onDeath(died);
        exec.onRespawn();
        tick(); // the death replan is requested
        tick(); // and adopted
        assertEquals(2, host.planRequests.size());
        assertEquals(0, exec.view().totalReplans()); // not counted against the budgets
        assertEquals(16, host.planRequests.get(1).exclusions().areas().get(0).radius());
        ExecutorView.NodeView front = node(0);
        assertEquals("recover_drops", front.taskKind());
        assertTrue(front.executorInserted());
        assertTrue(front.bestEffort());
        Goal.Custom custom = (Goal.Custom) front.goal();
        assertEquals("minecraft:overworld", custom.params().get("dimension"));
        assertEquals("6,60,6", custom.params().get("drops"));
    }

    @Test
    void theThirdDeathWithinOneGoalGivesUpWithTheDiedFailure() {
        for (int i = 0; i < 3; i++) host.offerPlan(diamondLeaf("mine:ore"));
        exec.submit(haveDiamond);
        ticks(4);
        Failure.Died died = new Failure.Died("minecraft:overworld", new Pos(5, 60, 5), List.of(), 1L, "");
        exec.onDeath(died);
        exec.onRespawn();
        tick();
        exec.onDeath(died);
        exec.onRespawn();
        tick();
        exec.onDeath(died);
        assertEquals(GoalResult.Status.FAILED, host.published.get(0).status());
        assertEquals(Failure.Kind.DIED, host.published.get(0).failure().kind());
        assertFalse(exec.active());
    }

    // --- Preemption --------------------------------------------------------------------------

    @Test
    void aHoldPausesTheRunningTaskAndKeepsIt() {
        host.tasks.add(new Fakes.FakeTask());
        runToActive(diamondLeaf("mine:ore"));
        exec.tick(BODY, new ReflexHold(true, "mob"));
        Fakes.FakeTask task = taskOf(0);
        assertTrue(task.calls.contains("pause:mob"));
        assertEquals(TaskState.PAUSED, task.state());
        assertEquals(NodeState.ACTIVE, node(0).state());
        assertTrue(exec.view().reflexHeld());
        assertEquals(GoalStatus.RUNNING, exec.view().status()); // the goal stays RUNNING, not PAUSED
    }

    @Test
    void whileHeldTheExecutorStartsNothingAndResponsesWait() {
        host.tickets.add(Fakes.FakeTicket.plan(diamondLeaf("mine:ore")));
        Fakes.FakeTicket ticket = (Fakes.FakeTicket) host.tickets.peek();
        exec.submit(haveDiamond);
        tick(); // the plan is in flight
        int polls = ticket.polls;
        exec.tick(BODY, new ReflexHold(true, "mob"));
        exec.tick(BODY, new ReflexHold(true, "mob"));
        assertEquals(polls, ticket.polls); // the finished plan is not even polled while held
        assertTrue(host.taskRequests.isEmpty());
    }

    @Test
    void aQueuedGoalDoesNotStartWhileTheGateIsShut() {
        runToActive(diamondLeaf("mine:ore"));
        exec.submit(new Goal.HaveItem(ItemKey.of("stick"), 1));
        exec.tick(BODY, new ReflexHold(true, "mob")); // the reflex layer takes the body
        exec.cancel(); // publishes CANCELLED, forgets, and the queue would advance...
        tick();
        assertTrue(host.published.stream().anyMatch(r -> r.status() == GoalResult.Status.CANCELLED));
        assertEquals(1, host.planRequests.size()); // ...but not while the gate is shut
        exec.tick(BODY, ReflexHold.NONE); // the gate opens
        tick();
        assertEquals(2, host.planRequests.size());
    }

    @Test
    void onReleaseThePostconditionIsCheckedFirstAndCanSatisfyTheNode() {
        host.tasks.add(new Fakes.FakeTask());
        runToActive(diamondLeaf("mine:ore"));
        exec.tick(BODY, new ReflexHold(true, "mob"));
        host.snapshot = Snapshot.builder().item(diamond, 1).build(); // the node's postcondition now holds
        exec.tick(BODY, ReflexHold.NONE);
        Fakes.FakeTask task = taskOf(0);
        assertTrue(task.calls.contains("pause:mob"));
        assertTrue(task.calls.stream().anyMatch(c -> c.startsWith("abort"))); // aborted, not resumed
        // The node was SATISFIED and the goal with it, so the tree is gone; the result says it.
        assertEquals(GoalResult.Status.SATISFIED, host.published.get(0).status());
    }

    @Test
    void onReleaseATaskWhosePostconditionDoesNotHoldResumes() {
        host.tasks.add(new Fakes.FakeTask());
        runToActive(diamondLeaf("mine:ore"));
        exec.tick(BODY, new ReflexHold(true, "mob"));
        exec.tick(BODY, ReflexHold.NONE);
        Fakes.FakeTask task = taskOf(0);
        assertTrue(task.calls.contains("resume"));
        assertEquals(TaskState.RUNNING, task.state());
        assertEquals(NodeState.ACTIVE, node(0).state());
    }

    @Test
    void pastThePausedTickLimitTheGoalGivesUpPreemptStalledStillHeld() {
        config = with(c -> c.preemptMaxPausedTicks = 3);
        rebuild();
        host.tasks.add(new Fakes.FakeTask());
        runToActive(diamondLeaf("mine:ore"));
        for (int i = 0; i < 5; i++) exec.tick(BODY, new ReflexHold(true, "spawner"));
        assertEquals(GoalResult.Status.FAILED, host.published.get(0).status());
        assertEquals(Failure.Kind.PREEMPT_STALLED, host.published.get(0).failure().kind());
        assertTrue(taskOf(0).calls.stream().anyMatch(c -> c.startsWith("abort")));
        assertFalse(exec.active());
    }

    @Test
    void repeatedPreemptionsWithinTheWindowGiveUp() {
        config = with(c -> c.preemptMaxCount = 1);
        rebuild();
        host.tasks.add(new Fakes.FakeTask());
        runToActive(diamondLeaf("mine:ore"));
        exec.tick(BODY, new ReflexHold(true, "mob"));  // preemption 1
        exec.tick(BODY, ReflexHold.NONE);
        exec.tick(BODY, new ReflexHold(true, "mob"));  // preemption 2 within the window
        exec.tick(BODY, new ReflexHold(true, "mob"));
        assertEquals(GoalResult.Status.FAILED, host.published.get(0).status());
        assertEquals(Failure.Kind.PREEMPT_STALLED, host.published.get(0).failure().kind());
    }

    // --- Budgets and watchdog ----------------------------------------------------------------

    @Test
    void exceedingTheTaskBudgetFailsTimedOutAndGrantsOneRetry() {
        config = with(c -> c.defaultTaskBudgetTicks = 2);
        rebuild();
        host.tasks.add(new Fakes.FakeTask()); // runs forever
        host.tasks.add(new Fakes.FakeTask()); // the retry instance
        runToActive(diamondLeaf("mine:ore"));
        ticks(10);
        assertEquals(2, host.taskRequests.size()); // the original and one retry, then a replan
        assertEquals(2, host.planRequests.size());
    }

    @Test
    void aStuckTaskFailsNoProgressAfterStuckTicks() {
        config = with(c -> c.stuckTicks = 3);
        rebuild();
        host.tasks.add(new Fakes.FakeTask());
        host.tasks.add(new Fakes.FakeTask());
        runToActive(diamondLeaf("mine:ore"));
        ticks(10);
        assertEquals(2, host.taskRequests.size());
        assertEquals(2, host.planRequests.size());
    }

    @Test
    void aWaitingTaskIsNotFailedByTheWatchdog() {
        config = with(c -> c.stuckTicks = 3);
        rebuild();
        Fakes.FakeTask task = new Fakes.FakeTask();
        task.waiting = true;
        host.tasks.add(task);
        runToActive(diamondLeaf("mine:ore"));
        ticks(10);
        assertEquals(NodeState.ACTIVE, node(0).state());
        assertTrue(task.ticksRun > 3);
    }

    @Test
    void ownerPausedTicksCountTowardNeitherBudgetNorWatchdog() {
        config = with(c -> c.stuckTicks = 3);
        rebuild();
        host.tasks.add(new Fakes.FakeTask());
        runToActive(diamondLeaf("mine:ore"));
        exec.pause();
        for (int i = 0; i < 10; i++) tick();
        assertEquals(GoalStatus.PAUSED, exec.view().status());
        assertTrue(exec.active()); // PAUSED still counts as active
        exec.resume();
        assertEquals(GoalStatus.RUNNING, exec.view().status());
        tick();
        assertEquals(NodeState.ACTIVE, node(0).state());
        assertTrue(host.published.isEmpty());
    }

    @Test
    void ownerPausePausesTheActiveTaskAndResumeResumesIt() {
        host.tasks.add(new Fakes.FakeTask());
        runToActive(diamondLeaf("mine:ore"));
        exec.pause();
        assertEquals(TaskState.PAUSED, taskOf(0).state());
        exec.resume();
        assertEquals(TaskState.RUNNING, taskOf(0).state());
    }

    // --- Results -----------------------------------------------------------------------------

    @Test
    void giveUpWithSomeMatchedIsPartial() {
        host.snapshot = Snapshot.builder().item(diamond, 3).build();
        host.tickets.add(Fakes.FakeTicket.unplannable(new Failure.NoSource(diamond, "", "")));
        Goal.HaveItem five = new Goal.HaveItem(diamond, 5);
        exec.submit(five);
        ticks(4);
        GoalResult result = host.published.get(0);
        assertEquals(GoalResult.Status.PARTIAL, result.status());
        assertEquals(3, result.matched());
        assertEquals(5, result.required());
    }

    @Test
    void giveUpWithNothingMatchedIsFailed() {
        host.tickets.add(Fakes.FakeTicket.unplannable(new Failure.NoSource(diamond, "", "")));
        exec.submit(haveDiamond);
        ticks(4);
        assertEquals(GoalResult.Status.FAILED, host.published.get(0).status());
        assertEquals(0, host.published.get(0).matched());
    }

    @Test
    void nodeLevelFailuresTheExecutorIsStillHandlingAreNotPublished() {
        host.tasks.add(Fakes.FakeTask.failing(new Failure.Unreachable("t", "r", "")));
        host.tasks.add(Fakes.FakeTask.failing(new Failure.Unreachable("t", "r", "")));
        host.offerPlan(diamondLeaf("mine:ore"));
        exec.submit(haveDiamond);
        ticks(6);
        assertTrue(host.published.isEmpty()); // a retry, then a replan: still handling it
    }

    // --- Queue and cancel --------------------------------------------------------------------

    @Test
    void theQueueIsFifoAndGoalsStartInOrderWhenTheCurrentOneEnds() {
        Goal.HaveItem sticks = new Goal.HaveItem(ItemKey.of("stick"), 1);
        Goal.HaveItem torches = new Goal.HaveItem(ItemKey.of("torch"), 1);
        exec.submit(haveDiamond);
        exec.submit(sticks);
        exec.submit(torches);
        assertEquals(2, exec.view().queue().size());
        host.tickets.add(Fakes.FakeTicket.unplannable(new Failure.NoSource(diamond, "", "")));
        host.tickets.add(Fakes.FakeTicket.unplannable(new Failure.NoSource(ItemKey.of("stick"), "", "")));
        ticks(8);
        assertEquals(3, host.planRequests.size());
        assertEquals(torches, exec.view().goal());
    }

    @Test
    void cancelAbortsTheTaskPublishesCancelledOnceAndForgetsTheGoalAtOnce() {
        host.tasks.add(new Fakes.FakeTask());
        runToActive(diamondLeaf("mine:ore"));
        exec.cancel();
        assertTrue(taskOf(0).calls.stream().anyMatch(c -> c.startsWith("abort")));
        assertEquals(1, host.published.size());
        assertEquals(GoalResult.Status.CANCELLED, host.published.get(0).status());
        assertNull(exec.view().goal());
        assertFalse(exec.active());
        Map<String, Object> afterCancel = exec.save();
        assertFalse(afterCancel.containsKey("goal")); // gone from saved state at once
        assertTrue(((List<?>) afterCancel.get("queue")).isEmpty());
    }

    @Test
    void cancelAllAlsoClearsTheQueue() {
        exec.submit(haveDiamond);
        exec.submit(new Goal.HaveItem(ItemKey.of("stick"), 1));
        host.tickets.add(Fakes.FakeTicket.unplannable(new Failure.NoSource(diamond, "", "")));
        ticks(2); // the first goal gives up; the second is queued, not yet planned
        exec.cancelAll();
        assertTrue(((List<?>) exec.save().get("queue")).isEmpty());
        assertNull(exec.view().goal());
        ticks(4);
        assertEquals(1, host.planRequests.size()); // nothing more ever starts
    }

    // --- Checkpoints -------------------------------------------------------------------------

    @Test
    void aTasksCheckpointIsStoredUnderItsNodeIdentityAndGivenToTheNextTask() {
        Fakes.FakeTask task = Fakes.FakeTask.failing(new Failure.Unreachable("t", "r", ""));
        task.checkpoint = Map.of("shaft", "10,5,10");
        host.tasks.add(task);
        host.tasks.add(new Fakes.FakeTask()); // the retry instance
        runToActive(diamondLeaf("mine:ore"));
        tick(); // the task fails and its checkpoint is stored; the retry is granted
        @SuppressWarnings("unchecked")
        Map<String, Object> checkpoints = (Map<String, Object>) exec.save().get("checkpoints");
        assertTrue(checkpoints.containsKey("mine|" + diamond.id()));
        tick(); // the fresh task for the same node gets the checkpoint as hints
        assertEquals(Map.of("shaft", "10,5,10"), host.taskHints.get(1));
    }

    @Test
    void aReplanCarriesACheckpointOverToANodeWithTheSameIdentityAndDropsTheRest() {
        Fakes.FakeTask task = Fakes.FakeTask.failing(new Failure.MissingInput(diamond, 1, ""));
        task.checkpoint = Map.of("shaft", "10,5,10");
        host.tasks.add(task);
        host.tasks.add(new Fakes.FakeTask());
        host.offerPlan(diamondLeaf("mine:ore"));
        host.offerPlan(diamondLeaf("mine:ore")); // the replan's result, same node identity
        exec.submit(haveDiamond);
        ticks(4); // adopt, start, fail, replan
        tick(); // adopted: the same node identity, so the checkpoint survives
        @SuppressWarnings("unchecked")
        Map<String, Object> checkpoints = (Map<String, Object>) exec.save().get("checkpoints");
        assertEquals(Map.of("mine|" + diamond.id(), Map.of("shaft", "10,5,10")), checkpoints);
        tick(); // the new node with the same identity starts from the checkpoint
        assertEquals(Map.of("shaft", "10,5,10"), host.taskHints.get(1));
    }

    // --- Save and load -----------------------------------------------------------------------

    @Test
    void aSaveRoundTripRestoresEverythingAndReplansUncounted() {
        Failure.MissingInput missing = new Failure.MissingInput(diamond, 1, "");
        host.tasks.add(Fakes.FakeTask.failing(missing));
        host.offerPlan(diamondLeaf("mine:ore"));
        exec.submit(haveDiamond);
        exec.submit(new Goal.HaveItem(ItemKey.of("stick"), 1));
        ticks(5); // one plan-caused replan happened
        int replans = exec.view().totalReplans();
        assertTrue(replans >= 1);
        GoalExecutor<Object> second = new GoalExecutor<>(host, postconditions, config);
        second.load(exec.save(), BODY);
        assertTrue(second.active());
        assertEquals(haveDiamond, second.view().goal());
        assertEquals(1, second.view().queue().size());
        assertEquals(replans, second.view().totalReplans()); // counters persist
        int requests = host.planRequests.size();
        second.tick(BODY, ReflexHold.NONE); // the restart replan is requested
        assertEquals(requests + 1, host.planRequests.size());
        assertEquals(replans, second.view().totalReplans()); // a restart replan is not counted
    }

    @Test
    void loadDropsTheGoalOnVersionMismatchReportsItAndStaysUsable() {
        exec.load(new java.util.HashMap<>(Map.of("v", 99L)), BODY);
        assertFalse(exec.active());
        assertEquals(1, host.published.size());
        assertEquals(GoalResult.Status.FAILED, host.published.get(0).status());
        assertFalse(host.logs.isEmpty());
        host.offerPlan(diamondLeaf("mine:ore")); // usable: a new goal runs
        exec.submit(haveDiamond);
        ticks(3);
        assertEquals(1, host.planRequests.size());
    }

    @Test
    void loadDropsTheGoalWhenTheRootGoalDoesNotDecode() {
        Map<String, Object> bad = new java.util.HashMap<>();
        bad.put("v", 1L);
        bad.put("goal", Map.of("type", "nonsense"));
        exec.load(bad, BODY);
        assertNull(exec.view().goal());
        assertEquals(GoalResult.Status.FAILED, host.published.get(0).status());
        assertEquals(Failure.Kind.INTERNAL, host.published.get(0).failure().kind());
        assertFalse(host.logs.isEmpty());
    }

    @Test
    void loadDropsOnlyTheQueuedGoalThatDoesNotDecode() {
        Map<String, Object> saved = new java.util.HashMap<>();
        saved.put("v", 1L);
        saved.put("goal", SaveCodec.encodeGoal(haveDiamond));
        saved.put("queue", List.of(SaveCodec.encodeGoal(new Goal.HaveItem(ItemKey.of("stick"), 1)),
                Map.of("type", "nonsense")));
        exec.load(saved, BODY);
        assertEquals(1, exec.view().queue().size());
    }

    @Test
    void loadDropsOnlyTheCheckpointThatIsNotAStringMap() {
        Map<String, Object> saved = new java.util.HashMap<>();
        saved.put("v", 1L);
        saved.put("goal", SaveCodec.encodeGoal(haveDiamond));
        saved.put("tree", List.of());
        Map<String, Object> checkpoints = new java.util.HashMap<>();
        checkpoints.put("mine|" + diamond.id(), Map.of("shaft", "10,5,10"));
        checkpoints.put("other", Map.of("shaft", 5)); // not a string map
        saved.put("checkpoints", checkpoints);
        exec.load(saved, BODY);
        @SuppressWarnings("unchecked")
        Map<String, Object> kept = (Map<String, Object>) exec.save().get("checkpoints");
        assertTrue(kept.containsKey("mine|" + diamond.id()));
        assertFalse(kept.containsKey("other"));
        assertFalse(host.logs.isEmpty());
    }

    @Test
    void loadKeepsExecutorInsertedPendingNodesAtTheFront() {
        host.offerPlan(diamondLeaf("mine:ore"));
        exec.submit(haveDiamond);
        ticks(3);
        Failure.Died died = new Failure.Died("minecraft:overworld", new Pos(5, 60, 5),
                List.of(new Pos(6, 60, 6)), 1L, "");
        host.dropsRecoverable = true;
        exec.onDeath(died);
        exec.onRespawn();
        tick(); // recover-drops inserted, the death replan is in flight
        GoalExecutor<Object> second = new GoalExecutor<>(host, postconditions, config);
        host.offerPlan(diamondLeaf("mine:ore")); // the restart replan's result
        second.load(exec.save(), BODY);
        assertTrue(second.view().nodes().stream().anyMatch(n -> "recover_drops".equals(n.taskKind())
                && n.executorInserted()));
        second.tick(BODY, ReflexHold.NONE); // restart replan
        second.tick(BODY, ReflexHold.NONE); // adopted
        assertEquals("recover_drops", second.view().nodes().get(0).taskKind()); // still in front
    }

    @Test
    void aBadSaveNeverThrowsOutOfLoad() {
        exec.load(new java.util.HashMap<>(Map.of("v", "one")), BODY); // the version is not a number
        exec.load(Map.of(), BODY); // no version at all
        exec.load(new java.util.HashMap<>(Map.of("v", 1L, "tree", "not-a-list")), BODY);
        assertFalse(exec.active());
    }

    @Test
    void theViewShowsTheGoalItsStatusItsNodesAndThePendingState() {
        host.tickets.add(Fakes.FakeTicket.plan(diamondLeaf("mine:ore")));
        exec.submit(haveDiamond);
        tick();
        ExecutorView view = exec.view();
        assertEquals(haveDiamond, view.goal());
        assertEquals(GoalStatus.RUNNING, view.status());
        assertTrue(view.planPending());
        assertFalse(view.reflexHeld());
        tick();
        tick();
        assertEquals(1, exec.view().nodes().size());
        assertEquals("mine", node(0).taskKind());
        assertEquals("mine:ore", node(0).source());
        assertFalse(node(0).executorInserted());
        assertFalse(node(0).bestEffort());
    }


    // --- A. A goal never wedges --------------------------------------------------------------

    @Test
    void aTaskThatFailedBetweenTicksEndsItsNodeAndRoutesTheFailure() {
        Fakes.FakeTask task = new Fakes.FakeTask();
        host.tasks.add(task);
        runToActive(diamondLeaf("mine:ore"));
        task.failFromOutside(new Failure.MissingInput(diamond, 1, "")); // a callback failed it, not a tick
        tick();
        assertEquals(NodeState.FAILED, node(0).state());
        assertEquals(2, host.planRequests.size()); // MISSING_INPUT routes to a replan
    }

    @Test
    void aTaskThatCompletedBetweenTicksGoesToThePostconditionCheck() {
        Fakes.RawTask task = new Fakes.RawTask();
        host.tasks.add(task);
        runToActive(diamondLeaf("mine:ore"));
        task.state = TaskState.COMPLETED;
        host.snapshot = Snapshot.builder().item(diamond, 1).build();
        tick();
        assertEquals(1, host.published.size());
        assertEquals(GoalResult.Status.SATISFIED, host.published.get(0).status());
        assertEquals(0, task.aborts); // it ended itself: nothing to abort
    }

    @Test
    void aTaskLeftInAStateTheExecutorDidNotExpectGivesUpWithInternal() {
        Fakes.RawTask task = new Fakes.RawTask();
        task.stateAfterStart = TaskState.PENDING; // start() did nothing
        host.tasks.add(task);
        runToActive(diamondLeaf("mine:ore"));
        assertEquals(1, host.published.size());
        assertEquals(Failure.Kind.INTERNAL, host.published.get(0).failure().kind());
    }

    @Test
    void aFailedTaskThatNamesNoFailureGivesUpWithInternal() {
        Fakes.RawTask task = new Fakes.RawTask();
        task.stateAfterStart = TaskState.FAILED; // failed, but the failure is missing
        host.tasks.add(task);
        runToActive(diamondLeaf("mine:ore"));
        assertEquals(1, host.published.size());
        assertEquals(Failure.Kind.INTERNAL, host.published.get(0).failure().kind());
    }

    @Test
    void aTaskThatThrowsInOwnerPauseIsRoutedAndLoggedWithItsStackTrace() {
        Fakes.FakeTask task = new Fakes.FakeTask();
        task.throwOnPause = true;
        host.tasks.add(task);
        runToActive(diamondLeaf("mine:ore"));
        exec.pause();
        assertEquals(1, host.published.size());
        assertEquals(Failure.Kind.INTERNAL, host.published.get(0).failure().kind());
        assertTrue(host.loggedErrors.contains(task.thrown));
        assertFalse(exec.active());
    }

    @Test
    void aTaskThatThrowsInOwnerResumeIsRoutedAndLoggedWithItsStackTrace() {
        Fakes.FakeTask task = new Fakes.FakeTask();
        task.throwOnResume = true;
        host.tasks.add(task);
        runToActive(diamondLeaf("mine:ore"));
        exec.pause();
        exec.resume();
        assertEquals(1, host.published.size());
        assertEquals(Failure.Kind.INTERNAL, host.published.get(0).failure().kind());
        assertTrue(host.loggedErrors.contains(task.thrown));
    }

    @Test
    void aTaskThatThrowsWhenTheReflexLayerPausesItGivesUpEvenThoughTheBodyIsHeld() {
        Fakes.FakeTask task = new Fakes.FakeTask();
        task.throwOnPause = true;
        host.tasks.add(task);
        runToActive(diamondLeaf("mine:ore"));
        exec.tick(BODY, new ReflexHold(true, "mob"));
        assertEquals(1, host.published.size());
        assertEquals(Failure.Kind.INTERNAL, host.published.get(0).failure().kind());
        assertTrue(host.loggedErrors.contains(task.thrown));
    }

    @Test
    void aTaskThatThrowsWhenTheReflexHoldIsReleasedGivesUp() {
        Fakes.FakeTask task = new Fakes.FakeTask();
        task.throwOnResume = true;
        host.tasks.add(task);
        runToActive(diamondLeaf("mine:ore"));
        exec.tick(BODY, new ReflexHold(true, "mob"));
        exec.tick(BODY, ReflexHold.NONE);
        assertEquals(1, host.published.size());
        assertEquals(Failure.Kind.INTERNAL, host.published.get(0).failure().kind());
        assertTrue(host.loggedErrors.contains(task.thrown));
    }

    @Test
    void anExceptionAHookThrewAfterFailIsLoggedWithItsStackTraceWhileTheTypedFailureIsRouted() {
        Fakes.FakeTask task = Fakes.FakeTask.failingThenThrowing(new Failure.Unreachable("t", "r", ""));
        host.tasks.add(task);
        host.tasks.add(new Fakes.FakeTask()); // the retry the typed failure is granted
        runToActive(diamondLeaf("mine:ore")); // the task fails itself and throws in its first tick
        assertTrue(host.loggedErrors.contains(task.thrown));
        tick();
        assertEquals(2, host.taskRequests.size()); // UNREACHABLE kept its routing: one retry
    }

    @Test
    void anExceptionFromAbortWhenTheExecutorRetiresATaskIsLogged() {
        Fakes.FakeTask task = new Fakes.FakeTask();
        task.throwOnAbort = true;
        host.tasks.add(task);
        runToActive(diamondLeaf("mine:ore"));
        exec.cancel();
        assertTrue(host.loggedErrors.contains(task.thrown));
        assertEquals(GoalResult.Status.CANCELLED, host.published.get(0).status());
    }

    @Test
    void loadTurnsAnActiveNodeBackIntoPendingSoItsTaskIsStartedAgain() {
        host.offerPlan(diamondLeaf("mine:ore"));
        host.offerPlan(diamondLeaf("mine:ore"));
        exec.submit(haveDiamond);
        ticks(3);
        host.dropsRecoverable = true;
        exec.onDeath(new Failure.Died("minecraft:overworld", new Pos(5, 60, 5), List.of(new Pos(6, 60, 6)), 1L, ""));
        exec.onRespawn();
        ticks(3); // the death replan, its adoption, then recover-drops starts
        assertEquals("recover_drops", node(0).taskKind());
        assertEquals(NodeState.ACTIVE, node(0).state());
        int tasksBefore = host.taskRequests.size();

        GoalExecutor<Object> second = new GoalExecutor<>(host, postconditions, config);
        host.offerPlan(diamondLeaf("mine:ore"));
        second.load(exec.save(), BODY);
        assertNotEquals(NodeState.ACTIVE, second.view().nodes().get(0).state()); // its task instance is gone
        for (int i = 0; i < 4; i++) second.tick(BODY, ReflexHold.NONE);
        assertEquals(tasksBefore + 1, host.taskRequests.size());
        assertEquals("recover_drops", host.taskRequests.get(tasksBefore).taskKind());
    }

    @Test
    void aSnapshotThatThrowsWhilePlanningGivesUpWithInternalInsteadOfEscapingTick() {
        host.snapshotThrows = new IllegalStateException("collector broke");
        exec.submit(haveDiamond);
        assertDoesNotThrow(() -> ticks(3));
        assertEquals(1, host.published.size());
        assertEquals(GoalResult.Status.FAILED, host.published.get(0).status());
        assertEquals(Failure.Kind.INTERNAL, host.published.get(0).failure().kind());
        assertTrue(host.loggedErrors.contains(host.snapshotThrows));
        assertFalse(exec.active());
    }

    @Test
    void aPlannerThatThrowsWhenStartedGivesUpWithInternalInsteadOfEscapingTick() {
        host.planThrows = new IllegalStateException("planner could not start");
        exec.submit(haveDiamond);
        assertDoesNotThrow(() -> ticks(3));
        assertEquals(1, host.published.size());
        assertEquals(Failure.Kind.INTERNAL, host.published.get(0).failure().kind());
        assertTrue(host.loggedErrors.contains(host.planThrows));
        assertFalse(exec.active());
    }

    @Test
    void aSnapshotThatThrowsMidGoalGivesUpWithInternalToo() {
        host.tasks.add(Fakes.FakeTask.completing());
        adopt(haveDiamond, diamondLeaf("mine:ore"));
        host.snapshotThrows = new IllegalStateException("collector broke later");
        assertDoesNotThrow(this::tick); // the task completes and the postcondition check needs a snapshot
        assertEquals(1, host.published.size());
        assertEquals(Failure.Kind.INTERNAL, host.published.get(0).failure().kind());
        assertTrue(host.loggedErrors.contains(host.snapshotThrows));
    }

    @Test
    void aRestoredGoalWithASavedDeathReplansOnTheFirstTickAndOnlyOnce() {
        host.offerPlan(diamondLeaf("mine:ore"));
        exec.submit(haveDiamond);
        ticks(3);
        exec.onDeath(new Failure.Died("minecraft:overworld", new Pos(5, 60, 5), List.of(new Pos(6, 60, 6)), 1L, ""));
        Map<String, Object> saved = exec.save(); // taken while the body is dead
        assertTrue(saved.containsKey("deaths"));

        GoalExecutor<Object> second = new GoalExecutor<>(host, postconditions, config);
        host.dropsRecoverable = true;
        host.offerPlan(diamondLeaf("mine:ore"));
        int before = host.planRequests.size();
        second.load(saved, BODY);
        second.tick(BODY, ReflexHold.NONE); // a loaded executor is ticked with a live body: it respawned
        assertEquals(before + 1, host.planRequests.size());
        var areas = host.planRequests.get(before).exclusions().areas();
        assertEquals(16, areas.get(areas.size() - 1).radius()); // the death area is excluded
        for (int i = 0; i < 3; i++) second.tick(BODY, ReflexHold.NONE);
        assertEquals(before + 1, host.planRequests.size()); // the restart flag was cleared: not planned twice
        assertEquals("recover_drops", second.view().nodes().get(0).taskKind());
    }

    @Test
    void anOwnerPauseWithNoGoalLeavesNoPausedFlagForTheNextGoal() {
        exec.pause();
        exec.submit(haveDiamond);
        assertEquals(GoalStatus.RUNNING, exec.view().status());
        assertEquals(false, exec.save().get("paused"));
    }

    @Test
    void cancelResetsTheRestartFlagSoTheNextGoalIsPlannedOnce() {
        Map<String, Object> saved = new java.util.HashMap<>();
        saved.put("v", 1L);
        saved.put("goal", SaveCodec.encodeGoal(haveDiamond));
        exec.load(saved, BODY); // a restart replan is now pending
        exec.cancel();
        host.offerPlan(diamondLeaf("mine:ore"));
        exec.submit(haveDiamond);
        ticks(5);
        assertEquals(1, host.planRequests.size());
    }

    @Test
    void cancelResetsTheRerunStreakSoTheNextGoalGetsItsFullReruns() {
        host.inputFingerprint = 2L; // every plan below is stale
        host.offerPlan(1L, diamondLeaf("mine:ore"));
        host.offerPlan(1L, diamondLeaf("mine:ore"));
        exec.submit(haveDiamond);
        ticks(2); // requested, found stale, one re-run requested
        assertEquals(2, host.planRequests.size());
        exec.cancel();
        for (int i = 0; i < 4; i++) host.offerPlan(1L, diamondLeaf("mine:ore"));
        exec.submit(haveDiamond);
        ticks(10);
        // The new goal: its plan, planRerunLimit (2) re-runs, then the next result is adopted regardless.
        assertEquals(2 + 3, host.planRequests.size());
    }

    // --- B. Tasks get the real body ----------------------------------------------------------

    @Test
    void ownerPauseHandsTheTaskTheBodyLastTicked() {
        Fakes.FakeTask task = new Fakes.FakeTask();
        host.tasks.add(task);
        runToActive(diamondLeaf("mine:ore"));
        exec.pause();
        assertSame(BODY, task.pausedWith);
    }

    @Test
    void ownerResumeHandsTheTaskTheBodyLastTicked() {
        Fakes.FakeTask task = new Fakes.FakeTask();
        host.tasks.add(task);
        runToActive(diamondLeaf("mine:ore"));
        exec.pause();
        exec.resume();
        assertSame(BODY, task.resumedWith);
    }

    @Test
    void cancelHandsTheTaskTheBodyLastTicked() {
        Fakes.FakeTask task = new Fakes.FakeTask();
        host.tasks.add(task);
        runToActive(diamondLeaf("mine:ore"));
        exec.cancel();
        assertSame(BODY, task.abortedWith);
    }

    @Test
    void cancelAllHandsTheTaskTheLastBody() {
        Fakes.FakeTask task = new Fakes.FakeTask();
        host.tasks.add(task);
        runToActive(diamondLeaf("mine:ore"));
        exec.cancelAll();
        assertSame(BODY, task.abortedWith);
    }

    @Test
    void aDeathHandsTheTaskTheLastBodyToAbort() {
        Fakes.FakeTask task = new Fakes.FakeTask();
        host.tasks.add(task);
        runToActive(diamondLeaf("mine:ore"));
        exec.onDeath(new Failure.Died("minecraft:overworld", new Pos(5, 60, 5), List.of(), 1L, ""));
        assertSame(BODY, task.abortedWith);
    }

    @Test
    void beforeTheFirstTickThereIsNoTaskSoPauseResumeAndCancelCallNothing() {
        exec.submit(haveDiamond);
        exec.pause();
        exec.resume();
        exec.cancel();
        assertEquals(1, host.published.size());
        assertEquals(GoalResult.Status.CANCELLED, host.published.get(0).status());
        assertTrue(host.taskRequests.isEmpty());
    }

    @Test
    void theStuckWatchdogAbortsTheTaskLikeTheTimeoutPath() {
        config = with(c -> c.stuckTicks = 3);
        rebuild();
        Fakes.FakeTask stuck = new Fakes.FakeTask();
        host.tasks.add(stuck);
        host.tasks.add(new Fakes.FakeTask());
        runToActive(diamondLeaf("mine:ore"));
        ticks(10);
        assertTrue(stuck.calls.stream().anyMatch(c -> c.startsWith("abort")));
        assertEquals(TaskState.CANCELLED, stuck.state());
    }

    @Test
    void theTimeoutPathAbortsTheTask() {
        config = with(c -> c.defaultTaskBudgetTicks = 2);
        rebuild();
        Fakes.FakeTask slow = new Fakes.FakeTask();
        host.tasks.add(slow);
        host.tasks.add(new Fakes.FakeTask());
        runToActive(diamondLeaf("mine:ore"));
        ticks(5);
        assertEquals(TaskState.CANCELLED, slow.state());
    }

    @Test
    void aTaskThatEndsItselfByCompletingOrFailingIsNotAbortedAgain() {
        Fakes.RawTask completing = Fakes.RawTask.completing();
        host.tasks.add(completing);
        runToActive(diamondLeaf("mine:ore")); // completes with the postcondition unmet: a retry follows
        assertEquals(0, completing.aborts);

        host = new Fakes.FakeHost();
        rebuild();
        Fakes.RawTask failing = Fakes.RawTask.failing(new Failure.MissingInput(diamond, 1, ""));
        host.tasks.add(failing);
        runToActive(diamondLeaf("mine:ore"));
        assertEquals(0, failing.aborts);
    }

    // --- C. What the design says -------------------------------------------------------------

    @Test
    void saveStoresTheCheckpointOfTheLiveTask() {
        Fakes.FakeTask task = new Fakes.FakeTask();
        task.checkpoint = Map.of("shaft", "10,5,10");
        host.tasks.add(task);
        runToActive(diamondLeaf("mine:ore"));
        assertEquals(Map.of("mine|" + diamond.id(), Map.of("shaft", "10,5,10")), exec.save().get("checkpoints"));
        task.checkpoint = Map.of("shaft", "10,4,10"); // the task moved on: the next save sees it
        assertEquals(Map.of("mine|" + diamond.id(), Map.of("shaft", "10,4,10")), exec.save().get("checkpoints"));
    }

    @Test
    void aGroupWhosePostconditionHoldsOnFirstReachMarksItsDescendantsDoneWithoutRunningThem() {
        Goal.HaveItem sticks = new Goal.HaveItem(ItemKey.of("stick"), 1);
        NodeSpec group = NodeSpec.group(haveDiamond, List.of(diamondLeaf("mine:ore"),
                NodeSpec.leaf(new Goal.HaveItem(ItemKey.of("torch"), 1), "craft", Map.of(), "")));
        host.snapshot = Snapshot.builder().item(diamond, 1).build(); // the bot was handed the prerequisite
        adopt(sticks, group);
        assertTrue(host.taskRequests.isEmpty()); // nothing is gathered again
        assertEquals(3, exec.view().nodes().size());
        assertTrue(exec.view().nodes().stream().allMatch(n -> n.state() == NodeState.SATISFIED));
    }

    @Test
    void aGroupWhosePostconditionIsUnsatisfiedDescendsAndItsChildrenRun() {
        NodeSpec group = NodeSpec.group(haveDiamond, List.of(diamondLeaf("mine:ore")));
        adopt(haveDiamond, group);
        assertEquals(1, host.taskRequests.size());
        assertEquals(NodeState.ACTIVE, node(1).state());
    }

    @Test
    void aGroupWhosePostconditionIsUnknownDescendsAndNeitherGivesUpNorInsertsAReach() {
        Goal.Stockpile stock = new Goal.Stockpile(diamond, 1, "minecraft:overworld", new Pos(100, 64, 100));
        NodeSpec group = NodeSpec.group(stock, List.of(
                NodeSpec.leaf(new Goal.HaveItem(ItemKey.of("stick"), 1), "craft", Map.of(), "")));
        adopt(haveDiamond, group); // the snapshot has no sight of the chest: the group is UNKNOWN
        assertTrue(host.published.isEmpty());
        assertEquals(1, host.taskRequests.size());
        assertEquals("craft", host.taskRequests.get(0).taskKind());
        assertTrue(exec.view().nodes().stream().noneMatch(ExecutorView.NodeView::executorInserted));
    }

    @Test
    void aDropInTheGoalsMatchedCountAfterANodeCompletesReplansCountedAsNormal() {
        Goal.HaveItem five = new Goal.HaveItem(diamond, 5);
        NodeSpec first = NodeSpec.leaf(new Goal.HaveItem(ItemKey.of("stick"), 1), "craft", Map.of(), "");
        NodeSpec second = NodeSpec.leaf(new Goal.HaveItem(ItemKey.of("torch"), 1), "craft", Map.of(), "");
        host.tasks.add(Fakes.FakeTask.completing());
        host.snapshot = Snapshot.builder().item(diamond, 3).build(); // matched 3 when the plan is adopted
        adopt(five, first, second);
        host.snapshot = Snapshot.builder().item(ItemKey.of("stick"), 1).item(diamond, 1).build(); // diamonds lost
        tick(); // the first node completes; the goal now matches only 1
        assertEquals(2, host.planRequests.size());
        assertEquals(1, exec.view().totalReplans()); // counted like any other replan...
        assertEquals(0, exec.view().repairReplans()); // ...but not a repair
        assertEquals(1, host.taskRequests.size()); // and the second node did not start
    }

    @Test
    void anUnknownGoalEvaluationAfterANodeCompletesIsIgnoredUntilTheTreeRunsOut() {
        Pos chest = new Pos(100, 64, 100);
        Goal.Stockpile stock = new Goal.Stockpile(diamond, 5, "minecraft:overworld", chest);
        NodeSpec first = NodeSpec.leaf(new Goal.HaveItem(ItemKey.of("stick"), 1), "craft", Map.of(), "");
        NodeSpec second = NodeSpec.leaf(new Goal.HaveItem(ItemKey.of("torch"), 1), "craft", Map.of(), "");
        host.tasks.add(Fakes.FakeTask.completing());
        host.snapshot = Snapshot.builder()
                .container(new com.legoj15.megablockbot.core.snapshot.ContainerSight(chest, "minecraft:chest", true,
                        false, Map.of(diamond, 3))).build(); // matched 3 when the plan is adopted
        adopt(stock, first, second);
        host.snapshot = Snapshot.builder().item(ItemKey.of("stick"), 1).build(); // the chest is out of sight now
        tick(); // the first node completes; the goal is UNKNOWN, not lower
        tick(); // the second node starts
        assertEquals(1, host.planRequests.size());
        assertEquals(2, host.taskRequests.size());
    }

    @Test
    void aCompletedTaskWhosePostconditionIsUnknownInsertsOneReachNodeInsteadOfNoProgress() {
        Pos chest = new Pos(100, 64, 100);
        Goal.Stockpile stock = new Goal.Stockpile(diamond, 1, "minecraft:overworld", chest);
        host.tasks.add(Fakes.FakeTask.completing());
        host.snapshot = Snapshot.builder() // the chest is in sight and empty: UNSATISFIED, so the task runs
                .container(new com.legoj15.megablockbot.core.snapshot.ContainerSight(chest, "minecraft:chest", true,
                        false, Map.of())).build();
        adopt(haveDiamond, NodeSpec.leaf(stock, "deposit", Map.of(), ""));
        host.snapshot = Snapshot.builder().build(); // then the chest goes out of sight
        tick(); // the task completes and the postcondition is UNKNOWN
        assertEquals("reach", node(0).taskKind());
        assertTrue(node(0).executorInserted());
        assertEquals(chest, ((Goal.Reach) node(0).goal()).pos());
        assertEquals(1, host.planRequests.size()); // no failure, no replan
        assertEquals(NodeState.PENDING, node(1).state());
    }

    @Test
    void anExecutorInsertedNodeWhosePostconditionIsUnknownCountsAsDoneAfterItsTaskCompletes() {
        host.offerPlan(diamondLeaf("mine:ore"));
        host.offerPlan(diamondLeaf("mine:ore"));
        exec.submit(haveDiamond);
        ticks(3);
        host.dropsRecoverable = true;
        exec.onDeath(new Failure.Died("minecraft:overworld", new Pos(5, 60, 5), List.of(new Pos(6, 60, 6)), 1L, ""));
        exec.onRespawn();
        ticks(2); // the death replan, adopted with recover-drops in front
        host.tasks.add(Fakes.FakeTask.completing()); // the recover-drops task
        ticks(2); // it starts (an UNKNOWN pre-check runs the task) and completes
        assertEquals("recover_drops", node(0).taskKind());
        assertEquals(NodeState.SATISFIED, node(0).state()); // not SKIPPED as a failure
    }

    @Test
    void aDeathReportedByABestEffortNodeIsStillCountedExcludedAndReplanned() {
        NodeSpec torch = NodeSpec.leaf(new Goal.HaveItem(ItemKey.of("torch"), 1), "craft", Map.of(), "")
                .asBestEffort();
        host.tasks.add(Fakes.FakeTask.failing(
                new Failure.Died("minecraft:overworld", new Pos(0, 40, 0), List.of(), 100L, "")));
        host.offerPlan(torch);
        host.offerPlan(diamondLeaf("mine:ore"));
        exec.submit(haveDiamond);
        ticks(5);
        assertEquals(1, exec.view().deaths());
        assertEquals(2, host.planRequests.size());
        assertEquals(16, host.planRequests.get(1).exclusions().areas().get(0).radius());
    }

    @Test
    void aGiveUpAfterTheReplanBudgetPublishesTheFailureThatCausedTheLatestReplan() {
        config = with(c -> c.maxTotalReplans = 1);
        rebuild();
        Failure.MissingTool wall = new Failure.MissingTool(ToolClass.PICKAXE, ToolTier.IRON, "need a pickaxe");
        host.tasks.add(Fakes.FakeTask.failing(wall));
        host.tasks.add(Fakes.FakeTask.failing(wall));
        host.offerPlan(diamondLeaf("mine:ore"));
        host.offerPlan(diamondLeaf("mine:ore"));
        exec.submit(haveDiamond);
        ticks(10);
        Failure published = host.published.get(0).failure();
        Failure.MissingTool tool = assertInstanceOf(Failure.MissingTool.class, published);
        assertEquals(ToolClass.PICKAXE, tool.toolClass());
        assertEquals(ToolTier.IRON, tool.tier());
        assertTrue(tool.detail().contains("budget")); // the budget that ran out is named
    }

    @Test
    void aRepairBudgetGiveUpKeepsTheFailureThatCausedAnEarlierReplan() {
        config = with(c -> c.maxRepairReplans = 1);
        rebuild();
        Goal stick = new Goal.HaveItem(ItemKey.of("stick"), 1);
        NodeSpec torch = NodeSpec.leaf(new Goal.HaveItem(ItemKey.of("torch"), 1), "craft", Map.of(), "");
        host.snapshot = Snapshot.builder().item(ItemKey.of("torch"), 1).build();
        host.tasks.add(Fakes.FakeTask.failing(new Failure.MissingInput(diamond, 1, "")));
        host.offerPlan(diamondLeaf("mine:ore")); // the first plan: the node fails, a replan follows
        host.offerPlan(torch); // the replan: its node is already satisfied, the tree runs out
        host.offerPlan(torch); // the one repair replan
        exec.submit(stick);
        ticks(20);
        assertEquals(GoalResult.Status.FAILED, host.published.get(0).status());
        assertEquals(Failure.Kind.MISSING_INPUT, host.published.get(0).failure().kind());
    }

    @Test
    void aRerunKeepsTheCauseThatStartedItSoItCountsTowardTheNoProgressStreak() {
        host.tasks.add(Fakes.FakeTask.failing(new Failure.MissingInput(diamond, 1, "")));
        host.offerPlan(diamondLeaf("mine:ore")); // the first plan, adopted as it is
        host.offerPlan(1L, diamondLeaf("mine:ore")); // the replan's first result: stale by then
        host.offerPlan(2L, diamondLeaf("mine:ore")); // its re-run: same shape as before, so no progress
        exec.submit(haveDiamond);
        ticks(2); // requested, adopted
        host.inputFingerprint = 2L; // the world moved while the node ran
        ticks(2); // the node starts and fails; the replan is requested
        ticks(4); // its result is stale and is re-run; the re-run is adopted
        assertEquals(3, host.planRequests.size()); // initial, the replan, its re-run
        assertEquals(1, exec.view().consecutiveReplansWithoutProgress());
        assertEquals(1, exec.view().totalReplans()); // a re-run is not a second replan
    }

    @Test
    void aPlanDeclaringALowerTotalDoesNotLowerTheDefaultBudget() {
        Failure.MissingInput missing = new Failure.MissingInput(diamond, 1, "");
        for (int i = 0; i < 4; i++) {
            host.tasks.add(Fakes.FakeTask.failing(missing));
            host.tickets.add(new Fakes.FakeTicket(new PlanOutcome.Planned(new Plan(
                    List.of(diamondLeaf(i % 2 == 0 ? "mine:oreA" : "mine:oreB")), 1L, 2, List.of())), 1));
        }
        host.offerPlan(diamondLeaf("mine:oreC"));
        exec.submit(haveDiamond);
        ticks(30);
        assertTrue(host.published.isEmpty()); // declared 2, but the default of 12 still holds
        assertTrue(host.planRequests.size() >= 5);
    }

    @Test
    void aRootGoalThatDoesNotDecodeClearsTheQueueToo() {
        Map<String, Object> saved = new java.util.HashMap<>();
        saved.put("v", 1L);
        saved.put("goal", Map.of("type", "nonsense"));
        saved.put("queue", List.of(SaveCodec.encodeGoal(new Goal.HaveItem(ItemKey.of("stick"), 1))));
        exec.load(saved, BODY);
        assertTrue(exec.view().queue().isEmpty());
        assertFalse(exec.active());
        ticks(3);
        assertTrue(host.planRequests.isEmpty()); // idle: the queued goal did not start
    }

    @Test
    void anIdleSaveWithAQueueLoadsItsQueueWithoutReportingAFailure() {
        Map<String, Object> saved = new java.util.HashMap<>();
        saved.put("v", 1L);
        saved.put("queue", List.of(SaveCodec.encodeGoal(new Goal.HaveItem(ItemKey.of("stick"), 1))));
        exec.load(saved, BODY);
        assertEquals(1, exec.view().queue().size());
        assertTrue(host.published.isEmpty()); // no goal, so nothing was dropped
    }

    // --- Round three: the edge cases the sweeps left -----------------------------------------

    private Failure.Died died(Pos at, Pos drop, long time, String detail) {
        return new Failure.Died("minecraft:overworld", at, List.of(drop), time, detail);
    }

    private Map<String, Object> bareSave(Goal goal) {
        Map<String, Object> saved = new java.util.HashMap<>();
        saved.put("v", 1L);
        saved.put("goal", SaveCodec.encodeGoal(goal));
        return saved;
    }

    // 1. Planning has a deadline

    @Test
    void aPlanThatNeverFinishesGivesUpTimedOutOncePastThePlanDeadlineAndCancelsItsTicket() {
        config = with(c -> c.planTimeoutTicks = 5);
        rebuild();
        Fakes.FakeTicket hung = new Fakes.FakeTicket();
        host.tickets.add(hung);
        exec.submit(haveDiamond);
        ticks(6); // the request, then five ticks of waiting: the deadline itself is still inside it
        assertTrue(host.published.isEmpty());
        tick(); // the sixth tick of waiting is past it
        assertEquals(1, host.published.size());
        GoalResult result = host.published.get(0);
        assertEquals(GoalResult.Status.FAILED, result.status());
        assertEquals(Failure.Kind.TIMED_OUT, result.failure().kind());
        assertTrue(hung.cancelled);
        assertFalse(exec.active());
    }

    @Test
    void ticksWhileTheOwnerHasTheGoalPausedDoNotCountAgainstThePlanDeadline() {
        config = with(c -> c.planTimeoutTicks = 5);
        rebuild();
        host.tickets.add(new Fakes.FakeTicket());
        exec.submit(haveDiamond);
        ticks(3); // the request and two ticks of waiting
        exec.pause();
        ticks(20);
        exec.resume();
        ticks(3); // three more: five waited in all, still inside the deadline
        assertTrue(host.published.isEmpty());
        assertTrue(exec.active());
    }

    // 2. Releasing a task never throws

    @Test
    void aTaskWhoseCheckpointThrowsIsStillReleasedAndItsFailureRoutesAsUsual() {
        Fakes.FakeTask task = Fakes.FakeTask.failing(new Failure.Unreachable("t", "r", ""));
        task.checkpointThrows = new IllegalStateException("checkpoint broke");
        host.tasks.add(task);
        host.tasks.add(new Fakes.FakeTask()); // the retry UNREACHABLE is granted
        runToActive(diamondLeaf("mine:ore")); // the task fails in its first tick and is released
        tick();
        assertTrue(host.published.isEmpty()); // not an INTERNAL give-up
        assertEquals(2, host.taskRequests.size()); // the retry started
        assertTrue(host.loggedErrors.contains(task.checkpointThrows));
    }

    @Test
    void cancelNeverThrowsWhenTheTasksCheckpointThrows() {
        Fakes.FakeTask task = new Fakes.FakeTask();
        task.checkpointThrows = new IllegalStateException("checkpoint broke");
        host.tasks.add(task);
        runToActive(diamondLeaf("mine:ore"));
        assertDoesNotThrow(exec::cancel);
        assertEquals(1, host.published.size());
        assertEquals(GoalResult.Status.CANCELLED, host.published.get(0).status());
        assertEquals(TaskState.CANCELLED, task.state());
        assertTrue(host.loggedErrors.contains(task.checkpointThrows));
    }

    @Test
    void cancelAllNeverThrowsWhenTheTasksCheckpointThrows() {
        Fakes.FakeTask task = new Fakes.FakeTask();
        task.checkpointThrows = new IllegalStateException("checkpoint broke");
        host.tasks.add(task);
        runToActive(diamondLeaf("mine:ore"));
        assertDoesNotThrow(exec::cancelAll);
        assertEquals(GoalResult.Status.CANCELLED, host.published.get(0).status());
        assertFalse(exec.active());
    }

    @Test
    void aBodyDeathIsCountedAndAppliedEvenWhenTheTasksCheckpointThrows() {
        Fakes.FakeTask task = new Fakes.FakeTask();
        task.checkpointThrows = new IllegalStateException("checkpoint broke");
        host.tasks.add(task);
        runToActive(diamondLeaf("mine:ore"));
        assertDoesNotThrow(() -> exec.onDeath(died(new Pos(5, 60, 5), new Pos(6, 60, 6), 1L, "")));
        assertEquals(1, exec.view().deaths());
        assertEquals(NodeState.PENDING, node(0).state());
        assertEquals(TaskState.CANCELLED, task.state());
    }

    @Test
    void cancelAllEndsTheRunningGoalAndEveryQueuedOneSoNothingStartsAfterIt() {
        exec.submit(haveDiamond);
        exec.submit(new Goal.HaveItem(ItemKey.of("stick"), 1));
        exec.submit(new Goal.HaveItem(ItemKey.of("torch"), 1));
        tick(); // the first goal's plan is in flight
        exec.cancelAll();
        assertFalse(exec.active()); // ending the first goal must not start the second
        assertNull(exec.view().goal());
        assertTrue(exec.view().queue().isEmpty());
        ticks(3);
        assertEquals(1, host.planRequests.size());
        assertEquals(1, host.published.size()); // only the goal that was running
    }

    // 3. No task outlives its goal

    @Test
    void aGiveUpReachedThroughTheGuardAbortsTheActiveTaskInsteadOfLeavingItPaused() {
        Fakes.FakeTask task = new Fakes.FakeTask();
        host.tasks.add(task);
        runToActive(diamondLeaf("mine:ore"));
        exec.tick(BODY, new ReflexHold(true, "mob")); // the task is paused and kept
        host.snapshotThrows = new IllegalStateException("collector broke");
        exec.tick(BODY, ReflexHold.NONE); // released: the postcondition check needs a snapshot, which throws
        assertEquals(1, host.published.size());
        assertEquals(Failure.Kind.INTERNAL, host.published.get(0).failure().kind());
        assertEquals(TaskState.CANCELLED, task.state());
        assertTrue(task.calls.stream().anyMatch(c -> c.startsWith("abort")));
    }

    // 5. Every death counts, and is applied

    @Test
    void twoDeathsBeforeTheNextTickBothExcludeTheirAreaAndBothRecoverTheirDrops() {
        host.offerPlan(diamondLeaf("mine:ore"));
        host.offerPlan(diamondLeaf("mine:ore"));
        exec.submit(haveDiamond);
        ticks(3);
        host.dropsRecoverable = true;
        Failure.Died first = died(new Pos(5, 60, 5), new Pos(6, 60, 6), 1L, "");
        Failure.Died second = died(new Pos(50, 60, 50), new Pos(51, 60, 51), 2L, "");
        exec.onDeath(first);
        exec.onRespawn();
        exec.onDeath(second); // the new body died before its first tick
        exec.onRespawn();
        tick(); // one death replan is requested for both
        tick(); // and adopted
        assertEquals(2, exec.view().deaths());
        assertEquals(2, host.planRequests.size());
        var areas = host.planRequests.get(1).exclusions().areas();
        assertEquals(2, areas.size());
        assertEquals(first.deathPos(), areas.get(0).center());
        assertEquals(second.deathPos(), areas.get(1).center());
        // Both drop sites get a node, the earlier death first: its drops despawn first.
        assertEquals("recover_drops", node(0).taskKind());
        assertEquals("recover_drops", node(1).taskKind());
        assertEquals("5,60,5", ((Goal.Custom) node(0).goal()).params().get("deathPos"));
        assertEquals("50,60,50", ((Goal.Custom) node(1).goal()).params().get("deathPos"));
    }

    @Test
    void aDeathBeforeTheFirstTickMakesTheDeathReplanTheOnlyPlan() {
        host.offerPlan(diamondLeaf("mine:ore"));
        host.offerPlan(diamondLeaf("mine:ore"));
        exec.submit(haveDiamond); // the initial plan is owed
        exec.onDeath(died(new Pos(5, 60, 5), new Pos(6, 60, 6), 1L, ""));
        exec.onRespawn();
        ticks(6);
        assertEquals(1, host.planRequests.size());
        assertEquals(1, host.taskRequests.size());
    }

    @Test
    void aDeathRightAfterALoadMakesTheDeathReplanTheOnlyPlan() {
        host.offerPlan(diamondLeaf("mine:ore"));
        host.offerPlan(diamondLeaf("mine:ore"));
        exec.load(bareSave(haveDiamond), BODY); // a restart replan is owed
        exec.onDeath(died(new Pos(5, 60, 5), new Pos(6, 60, 6), 1L, ""));
        exec.onRespawn();
        ticks(6);
        assertEquals(1, host.planRequests.size());
        assertEquals(1, host.taskRequests.size());
    }

    // 6. Load on a live executor

    @Test
    void loadEndsTheLiveGoalFirstAbortingItsTaskAndPublishingCancelledForIt() {
        Fakes.FakeTask task = new Fakes.FakeTask();
        host.tasks.add(task);
        runToActive(diamondLeaf("mine:ore"));
        exec.submit(new Goal.HaveItem(ItemKey.of("stick"), 1)); // queued: the save replaces the queue
        Map<String, Object> idle = new GoalExecutor<>(host, postconditions, config).save();
        exec.load(idle, BODY);
        assertEquals(1, host.published.size());
        assertEquals(GoalResult.Status.CANCELLED, host.published.get(0).status());
        assertEquals(haveDiamond, host.published.get(0).goal());
        assertEquals(TaskState.CANCELLED, task.state());
        assertFalse(exec.active());
        ticks(3);
        assertEquals(1, host.planRequests.size()); // the old queue's goal did not start in between
    }

    @Test
    void loadResetsThePreemptionAccountingOfTheGoalItReplaces() {
        config = with(c -> c.preemptMaxPausedTicks = 3);
        rebuild();
        host.tasks.add(new Fakes.FakeTask());
        runToActive(diamondLeaf("mine:ore"));
        for (int i = 0; i < 3; i++) exec.tick(BODY, new ReflexHold(true, "mob")); // at the limit, not past it
        exec.load(bareSave(haveDiamond), BODY);
        for (int i = 0; i < 3; i++) exec.tick(BODY, new ReflexHold(true, "mob")); // the restored goal's own three
        assertTrue(exec.active());
        assertEquals(1, host.published.size()); // the CANCELLED of the replaced goal, no stall
        assertEquals(GoalResult.Status.CANCELLED, host.published.get(0).status());
    }

    // 7. The result says what the bot has now

    private void adoptFive(NodeSpec leaf) {
        host.offerPlan(leaf);
        exec.submit(new Goal.HaveItem(diamond, 5));
        ticks(3); // adopted with whatever the snapshot holds, the node started
    }

    private final Snapshot threeDiamonds = Snapshot.builder().item(ItemKey.of("diamond"), 3).build();

    @Test
    void aGiveUpReportsWhatTheBotHasAtThatMomentNotAtTheLastEvaluation() {
        host.tasks.add(Fakes.FakeTask.failing(new Failure.Internal(null, "bug")));
        adoptFive(NodeSpec.leaf(new Goal.HaveItem(diamond, 5), "mine", Map.of(), "mine:ore"));
        host.snapshot = threeDiamonds; // picked up since the plan was adopted
        tick(); // the task fails INTERNAL: give up
        GoalResult result = host.published.get(0);
        assertEquals(GoalResult.Status.PARTIAL, result.status());
        assertEquals(3, result.matched());
        assertEquals(5, result.required());
    }

    @Test
    void aCancelReportsWhatTheBotHasAtThatMoment() {
        host.tasks.add(new Fakes.FakeTask());
        adoptFive(NodeSpec.leaf(new Goal.HaveItem(diamond, 5), "mine", Map.of(), "mine:ore"));
        host.snapshot = threeDiamonds;
        exec.cancel();
        GoalResult result = host.published.get(0);
        assertEquals(GoalResult.Status.CANCELLED, result.status());
        assertEquals(3, result.matched());
        assertEquals(5, result.required());
    }

    @Test
    void aCancelAfterRespawnButBeforeTheNewBodysFirstTickDoesNotLookAtTheDeadBody() {
        host.tasks.add(new Fakes.FakeTask());
        adoptFive(NodeSpec.leaf(new Goal.HaveItem(diamond, 5), "mine", Map.of(), "mine:ore"));
        exec.onDeath(new Failure.Died("minecraft:overworld", new Pos(5, 60, 5), List.of(), 1L, ""));
        exec.onRespawn();
        host.snapshot = threeDiamonds; // what asking a body would say now; the only body known is the dead one
        exec.cancel();
        GoalResult result = host.published.get(0);
        assertEquals(GoalResult.Status.CANCELLED, result.status());
        assertEquals(0, result.matched(), "the result came from looking at the body that died");
    }

    @Test
    void aDeathCapEndingReportsWhatTheBotHasAtThatMoment() {
        Failure.Died died = new Failure.Died("minecraft:overworld", new Pos(0, 40, 0), List.of(), 1L, "");
        NodeSpec leaf = NodeSpec.leaf(new Goal.HaveItem(diamond, 5), "mine", Map.of(), "mine:ore");
        for (int i = 0; i < 2; i++) host.tasks.add(Fakes.FakeTask.failing(died));
        Fakes.FakeTask last = new Fakes.FakeTask() {
            @Override
            protected TickResult onTick(Object body) {
                host.snapshot = threeDiamonds; // the bot picked something up before it died the third time
                return super.onTick(body);
            }
        };
        last.mode = Fakes.FakeTask.Mode.FAIL;
        last.failure = died;
        host.tasks.add(last);
        for (int i = 0; i < 3; i++) host.offerPlan(leaf);
        exec.submit(new Goal.HaveItem(diamond, 5));
        ticks(30);
        GoalResult result = host.published.get(0);
        assertEquals(Failure.Kind.DIED, result.failure().kind());
        assertEquals(GoalResult.Status.PARTIAL, result.status());
        assertEquals(3, result.matched());
    }

    @Test
    void aDeathCapEndingThroughOnDeathUsesTheLastEvaluationBecauseThereIsNoBodyToLookAt() {
        host.snapshot = threeDiamonds; // what the bot had when the plan was adopted
        for (int i = 0; i < 3; i++) {
            host.offerPlan(NodeSpec.leaf(new Goal.HaveItem(diamond, 5), "mine", Map.of(), "mine:ore"));
        }
        exec.submit(new Goal.HaveItem(diamond, 5));
        ticks(4);
        Failure.Died died = new Failure.Died("minecraft:overworld", new Pos(5, 60, 5), List.of(), 1L, "");
        exec.onDeath(died);
        exec.onRespawn();
        tick();
        exec.onDeath(died);
        exec.onRespawn();
        tick();
        host.snapshot = Snapshot.builder().build(); // a dead body carries nothing
        exec.onDeath(died);
        GoalResult result = host.published.get(0);
        assertEquals(GoalResult.Status.PARTIAL, result.status());
        assertEquals(3, result.matched());
    }

    @Test
    void aGiveUpWhoseFinalEvaluationThrowsFallsBackToTheLastEvaluation() {
        host.snapshot = threeDiamonds;
        host.tasks.add(Fakes.FakeTask.completing());
        adoptFive(NodeSpec.leaf(new Goal.HaveItem(diamond, 5), "mine", Map.of(), "mine:ore"));
        host.snapshotThrows = new IllegalStateException("collector broke");
        tick(); // the task completes; the postcondition check throws; the final evaluation throws too
        GoalResult result = host.published.get(0);
        assertEquals(Failure.Kind.INTERNAL, result.failure().kind());
        assertEquals(GoalResult.Status.PARTIAL, result.status());
        assertEquals(3, result.matched());
    }

    @Test
    void aFreshUnknownEvaluationDoesNotReplaceAnEarlierKnownOne() {
        Pos chest = new Pos(100, 64, 100);
        Goal.Stockpile five = new Goal.Stockpile(diamond, 5, "minecraft:overworld", chest);
        host.snapshot = Snapshot.builder().container(new com.legoj15.megablockbot.core.snapshot.ContainerSight(
                chest, "minecraft:chest", true, false, Map.of(diamond, 3))).build(); // three in the chest
        host.tasks.add(Fakes.FakeTask.failing(new Failure.Internal(null, "bug")));
        host.offerPlan(NodeSpec.leaf(five, "deposit", Map.of(), ""));
        exec.submit(five);
        ticks(3);
        host.snapshot = Snapshot.builder().build(); // the chest is out of sight now: UNKNOWN, not "zero"
        tick();
        GoalResult result = host.published.get(0);
        assertEquals(GoalResult.Status.PARTIAL, result.status());
        assertEquals(3, result.matched());
    }

    // 8. Checkpoint maps are cleaned at the boundary

    @Test
    void aCheckpointEntryWithANullKeyOrValueIsDroppedAndLoggedAtSaveAndAtRelease() {
        Map<String, String> dirty = new java.util.HashMap<>();
        dirty.put("shaft", "10,5,10");
        dirty.put("lost", null);
        dirty.put(null, "orphan");
        Fakes.FakeTask task = Fakes.FakeTask.failing(new Failure.Unreachable("t", "r", ""));
        task.checkpoint = dirty;
        host.tasks.add(task);
        host.tasks.add(new Fakes.FakeTask());
        adopt(haveDiamond, diamondLeaf("mine:ore")); // the task is live and has not ticked yet
        Map<String, Map<String, String>> clean = Map.of("mine|" + diamond.id(), Map.of("shaft", "10,5,10"));
        assertEquals(clean, exec.save().get("checkpoints")); // at save
        assertFalse(host.logs.isEmpty());
        host.logs.clear();
        tick(); // the task fails and is released: its checkpoint is stored
        assertEquals(clean, exec.save().get("checkpoints")); // at release
        assertFalse(host.logs.isEmpty());
    }

    // 9. A pending death keeps its detail across save and load

    @Test
    void aPendingDeathKeepsItsDetailAcrossSaveAndLoad() {
        host.offerPlan(diamondLeaf("mine:ore"));
        exec.submit(haveDiamond);
        ticks(3);
        exec.onDeath(died(new Pos(5, 60, 5), new Pos(6, 60, 6), 1L, "fell into lava"));
        GoalExecutor<Object> second = new GoalExecutor<>(host, postconditions, config);
        host.offerPlan(diamondLeaf("mine:ore"));
        second.load(exec.save(), BODY);
        second.tick(BODY, ReflexHold.NONE); // the death replan reads the record
        assertEquals("fell into lava", host.recoverChecks.get(0).detail());
    }

    // 10. Load restores only what the restart replan keeps

    @Test
    void loadRestoresOnlyTheExecutorInsertedNodesBecauseTheRestartReplanRebuildsTheRest() {
        NodeSpec planned = NodeSpec.group(haveDiamond, List.of(diamondLeaf("mine:ore")));
        NodeSpec reach = new NodeSpec(new Goal.Reach("minecraft:overworld", new Pos(1, 64, 1), 4), "reach",
                Map.of("pos", "1,64,1", "dimension", "minecraft:overworld"), "", false, List.of());
        Map<String, Object> saved = bareSave(haveDiamond);
        saved.put("tree", List.of(SaveCodec.encodeNode(ExecNode.of(planned, false, null)),
                SaveCodec.encodeNode(ExecNode.of(reach, true, null))));
        exec.load(saved, BODY);
        assertEquals(1, exec.view().nodes().size());
        assertEquals("reach", node(0).taskKind());
        assertTrue(node(0).executorInserted());
        assertEquals(NodeState.PENDING, node(0).state());
    }

    @Test
    void aMalformedPlannerNodeInTheSavedTreeDoesNotDropTheGoalBecauseItIsNeverUsed() {
        NodeSpec planned = NodeSpec.group(haveDiamond, List.of(diamondLeaf("mine:ore")));
        Map<String, Object> broken = new java.util.HashMap<>(SaveCodec.encodeNode(ExecNode.of(planned, false, null)));
        broken.put("state", "NOT_A_STATE");
        Map<String, Object> saved = bareSave(haveDiamond);
        saved.put("tree", List.of(broken));
        exec.load(saved, BODY);
        assertEquals(haveDiamond, exec.view().goal()); // the restart replan will rebuild the tree anyway
        assertTrue(host.published.isEmpty());
    }

    // --- config helper -----------------------------------------------------------------------

    private interface Editor {
        void edit(Mutable m);
    }

    /** Changes one number of {@link ExecutorConfig#defaults()} and nothing else. */
    private static final class Mutable {
        int maxReplansWithoutProgress = 3;
        int maxTotalReplans = 12;
        int totalReplanCeiling = 48;
        int maxRepairReplans = 3;
        int maxDeathsPerGoal = 3;
        int deathExclusionRadius = 16;
        int hazardExclusionRadius = 8;
        int defaultTaskBudgetTicks = 12000;
        int stuckTicks = 600;
        int preemptMaxPausedTicks = 2400;
        int preemptMaxCount = 8;
        int preemptWindowTicks = 1200;
        int planRerunLimit = 2;
        int planTimeoutTicks = 1200;
    }

    private ExecutorConfig with(Editor editor) {
        Mutable m = new Mutable();
        editor.edit(m);
        return new ExecutorConfig(m.maxReplansWithoutProgress, m.maxTotalReplans, m.totalReplanCeiling,
                m.maxRepairReplans, m.maxDeathsPerGoal, m.deathExclusionRadius, m.hazardExclusionRadius,
                m.defaultTaskBudgetTicks, Map.of(), m.stuckTicks, m.preemptMaxPausedTicks,
                m.preemptMaxCount, m.preemptWindowTicks, m.planRerunLimit, m.planTimeoutTicks);
    }
}
