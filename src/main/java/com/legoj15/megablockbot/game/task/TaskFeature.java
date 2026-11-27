package com.legoj15.megablockbot.game.task;

/**
 * The tasks that move and mine (design 3.4, Task; 4.3, Acquisition resolver): what a plan leaf of each kind becomes in
 * the world. Each kind is registered with {@link TaskRegistry}, which the bot's runtime asks when the executor wants a
 * task for a leaf.
 *
 * <ul>
 *   <li>{@value #NAVIGATE} and {@value #REACH}: walk to a place ({@link ReachTask}).</li>
 *   <li>{@value #MINE}: break blocks until the body holds a count of an item ({@link MineTask}).</li>
 *   <li>{@value #RECOVER_DROPS}: pick up what a death dropped ({@link RecoverDropsTask}).</li>
 *   <li>{@value #DEPOSIT}: put items into a container ({@link DepositTask}).</li>
 *   <li>{@value #WITHDRAW}: take items out of a container its owner named (or the body placed), as a player does
 *       ({@link WithdrawTask}).</li>
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
    /** The kind of the node the executor inserts after a death; the name is the executor's ({@code DeathHandling}). */
    public static final String RECOVER_DROPS = "recover_drops";
    public static final String DEPOSIT = "deposit";
    /** The kind the planner's supply leaves bind to ({@code SolverPlanner}, "withdraw"). */
    public static final String WITHDRAW = "withdraw";

    private TaskFeature() {}

    public static void init() {
        TaskRegistry.register(NAVIGATE, (leaf, hints) -> new ReachTask(leaf));
        TaskRegistry.register(REACH, (leaf, hints) -> new ReachTask(leaf));
        TaskRegistry.register(MINE, MineTask::new);
        TaskRegistry.register(RECOVER_DROPS, (leaf, hints) -> new RecoverDropsTask(leaf));
        TaskRegistry.register(DEPOSIT, (leaf, hints) -> new DepositTask(leaf));
        TaskRegistry.register(WITHDRAW, (leaf, hints) -> new WithdrawTask(leaf));
    }
}
