package com.legoj15.megablockbot.game.task;

import com.legoj15.megablockbot.core.planner.LookInside;

/**
 * The tasks that move and mine (design 3.4, Task; 4.3, Acquisition resolver): what a plan leaf of each kind becomes in
 * the world. Each kind is registered with {@link TaskRegistry}, which the bot's runtime asks when the executor wants a
 * task for a leaf.
 *
 * <ul>
 *   <li>{@value #NAVIGATE} and {@value #REACH}: walk to a place ({@link ReachTask}).</li>
 *   <li>{@value #MINE}: break blocks until the body holds a count of an item ({@link MineTask}).</li>
 *   <li>{@value #FISH}: cast a rod into water until the body holds a count of an item ({@link FishTask}).</li>
 *   <li>{@value #KILL}: fight mobs until the body holds a count of an item ({@link KillTask}).</li>
 *   <li>{@value #HARVEST}: take growables until the body holds a count of an item ({@link HarvestTask}).</li>
 *   <li>{@value #RECOVER_DROPS}: pick up what a death dropped ({@link RecoverDropsTask}).</li>
 *   <li>{@value #DEPOSIT}: put items into a container ({@link DepositTask}).</li>
 *   <li>{@value #WITHDRAW}: take items out of a container its owner named (or the body placed), as a player does
 *       ({@link WithdrawTask}).</li>
 *   <li>{@value #LOOK_INSIDE}: open a container its owner named and note what is in it ({@link LookTask}).</li>
 *   <li>{@value #BUILD}: place a blueprint's blocks at its anchor ({@link BuildTask}, issue 17).</li>
 * </ul>
 *
 * <p>Called from {@code NavFeature.init()} for now, since the tasks are built on the navigator; the bot runtime
 * ({@code BotFeature}) will take this call over when it exists. {@code GameBootstrap} is not touched.
 */
public final class TaskFeature {
    /** The kind a planner leaf with a {@code Reach} goal binds to. */
    public static final String NAVIGATE = "navigate";
    /** The kind of the node the executor inserts when a postcondition cannot be read from where the body is. */
    public static final String REACH = "reach";
    public static final String MINE = "mine";
    /** The kind a {@code Source.Fishing} gather leaf binds to ({@code SolverPlanner}, "fish"). */
    public static final String FISH = FishTask.KIND;
    /** The kind a planner leaf for a {@code KillMob} source binds to ({@code SolverPlanner}). */
    public static final String KILL = "kill";
    /** The kind a planner leaf for a {@code Harvest} source binds to ({@code SolverPlanner}). */
    public static final String HARVEST = "harvest";
    /** The kind of the node the executor inserts after a death; the name is the executor's ({@code DeathHandling}). */
    public static final String RECOVER_DROPS = "recover_drops";
    public static final String DEPOSIT = "deposit";
    /** The kind the planner's supply leaves bind to ({@code SolverPlanner}, "withdraw"). */
    public static final String WITHDRAW = "withdraw";
    /** The kind of the one leaf of the body's look at a container it was told to take from ({@code LookInside}). */
    public static final String LOOK_INSIDE = LookInside.TASK;
    /** The kind of the one leaf of a build goal that gathers nothing ({@code SolverPlanner}, "build"). */
    public static final String BUILD = "build";
    /** The kind a planner leaf for a {@code Source.Trade} gather binds to ({@code SolverPlanner}, issue 15). */
    public static final String TRADE = TradeTask.KIND;

    private TaskFeature() {}

    public static void init() {
        TaskRegistry.register(NAVIGATE, (leaf, hints) -> new ReachTask(leaf));
        TaskRegistry.register(REACH, (leaf, hints) -> new ReachTask(leaf));
        TaskRegistry.register(MINE, MineTask::new);
        TaskRegistry.register(FISH, FishTask::new);
        TaskRegistry.register(KILL, KillTask::new);
        TaskRegistry.register(HARVEST, HarvestTask::new);
        TaskRegistry.register(RECOVER_DROPS, (leaf, hints) -> new RecoverDropsTask(leaf));
        TaskRegistry.register(DEPOSIT, (leaf, hints) -> new DepositTask(leaf));
        TaskRegistry.register(WITHDRAW, (leaf, hints) -> new WithdrawTask(leaf));
        TaskRegistry.register(LOOK_INSIDE, (leaf, hints) -> new LookTask(leaf));
        TaskRegistry.register(BUILD, (leaf, hints) -> new BuildTask(leaf));
        TaskRegistry.register(TRADE, TradeTask::new);
    }
}
