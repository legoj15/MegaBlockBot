package com.legoj15.megablockbot.game.bot;

import com.legoj15.megablockbot.MegaBlockBot;
import com.legoj15.megablockbot.core.Pos;
import com.legoj15.megablockbot.core.exec.ExecutorConfig;
import com.legoj15.megablockbot.core.exec.ExecutorHost;
import com.legoj15.megablockbot.core.exec.ExecutorView;
import com.legoj15.megablockbot.core.exec.GoalExecutor;
import com.legoj15.megablockbot.core.exec.GoalResult;
import com.legoj15.megablockbot.core.exec.PlanTicket;
import com.legoj15.megablockbot.core.exec.ReflexHold;
import com.legoj15.megablockbot.core.goal.Goal;
import com.legoj15.megablockbot.core.goal.Postconditions;
import com.legoj15.megablockbot.core.plan.Exclusions;
import com.legoj15.megablockbot.core.plan.NodeIdentity;
import com.legoj15.megablockbot.core.plan.NodeSpec;
import com.legoj15.megablockbot.core.plan.PlanRequest;
import com.legoj15.megablockbot.core.plan.Profile;
import com.legoj15.megablockbot.core.planner.LookInside;
import com.legoj15.megablockbot.core.planner.RecipeDef;
import com.legoj15.megablockbot.core.snapshot.Snapshot;
import com.legoj15.megablockbot.core.task.Failure;
import com.legoj15.megablockbot.core.task.Task;
import com.legoj15.megablockbot.core.task.TaskState;
import com.legoj15.megablockbot.core.task.TickResult;
import com.legoj15.megablockbot.game.body.BotBrain;
import com.legoj15.megablockbot.game.body.BotPlayer;
import com.legoj15.megablockbot.game.body.DeathRecord;
import com.legoj15.megablockbot.game.nav.BotNavigator;
import com.legoj15.megablockbot.game.plan.BotMemory;
import com.legoj15.megablockbot.game.reflex.ReflexConstants;
import com.legoj15.megablockbot.game.plan.SnapshotCollector;
import com.legoj15.megablockbot.game.task.CraftTask;
import com.legoj15.megablockbot.game.task.FishTask;
import com.legoj15.megablockbot.game.task.TaskRegistry;
import com.legoj15.megablockbot.game.task.UseOnTask;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The brain of one body (design 3): it owns a {@link GoalExecutor} and is the executor's host, so a goal the owner
 * states becomes a plan on the planner thread, tasks on the server thread, and a result in the owner's chat. Nothing
 * in it knows how to craft or walk: a leaf's task comes from the {@link TaskRegistry}, which the features fill.
 *
 * <p>Server thread only, except {@link BotPlanner}'s thread, which only ever sees immutable plan requests.
 *
 * <p><b>Saving.</b> The brain's compound holds a schema number, the executor's save form as NBT ({@link NbtForm}) and
 * the body's {@link BotMemory}. The memory belongs to the body's UUID, which the brain does not know until it is first
 * ticked (the body is still being built when it loads), so a loaded memory is parked and installed on the first tick.
 */
public final class BotRuntime implements BotBrain, ExecutorHost<BotPlayer> {
    /** Version of this brain's save form; a save with another one does not load. */
    static final int SAVE_SCHEMA = 1;

    /** How long a death's drops are worth walking back to: five minutes of game time, about when they despawn. */
    static final long DROPS_RECOVERABLE_TICKS = 5L * 60L * 20L;
    /**
     * The tick budget of a {@code fish} task: fishing is random, so the budget is what ends a run of bad luck. A bite
     * comes after 5 to 30 seconds (up to twice that with a covered sky) and the wanted item is one of many in the
     * table — a salmon is a quarter of the fish, so some eighty casts cover almost every run. At 20 to 40 seconds a
     * cast, eighty of them is about half an hour: 36000 ticks, three times the default a task gets.
     */
    static final int FISH_TASK_BUDGET_TICKS = 36000;

    /**
     * The smelt kind's own task budget. The default one is 12000 ticks, but one stack of an item already takes 12800
     * in a furnace (64 at 200 ticks each), so a plain smelt would time out mid-stack. 72000 ticks — one hour of game
     * time, five and a half stacks — covers a furnace the plan fills more than once before taking it up, and still
     * bounds a smelt that has stopped for good.
     */
    static final int SMELT_BUDGET_TICKS = 72_000;

    /**
     * The {@code use_on} kind's own task budget. One use is one walk and one click, but the target may be nowhere
     * near: the search explores as far as a mine's (twelve rounds of thirty seconds, 7200 ticks), and a goal of
     * several units repeats the walk per unit. 24000 ticks — twenty minutes — covers that twice over and still bounds
     * a task that has stopped for good.
     */
    static final int USE_ON_BUDGET_TICKS = 24_000;

    /**
     * The {@code build} kind's own tick budget. The default 12000 is a task that walks and mines; a build places or
     * breaks one block at a time, each with its own walk, reach and click, and a blueprint may hold up to
     * {@link com.legoj15.megablockbot.game.build.Blueprints#MAX_BLOCKS} blocks. 200 ticks a block of the largest
     * blueprint allowed (4096 x 200 = 819200) is far too coarse a way to say that, so the number is said outright:
     * 36000 ticks, half an hour of play, enough for a floor of planks and the hut on it, and still short of the age of
     * a server.
     */
    static final int BUILD_BUDGET_TICKS = 36000;

    /**
     * The {@code trade} kind's own task budget. A reroll takes about 15 seconds — the villager scans for the job site,
     * takes the profession, the window is opened, and the site comes up again — and a book of mending takes about 40
     * rolls on average: 600 ticks a roll makes 24000, and 72000 (one hour of game time, five times the average hunt)
     * covers the unlucky runs a specific enchanted book out of the whole tradeable tag needs.
     */
    static final int TRADE_BUDGET_TICKS = 72_000;

    private final GoalExecutor<BotPlayer> executor;

    /** The instance of the body this brain last served: replaced on a respawn, which hands the brain to a new one. */
    @Nullable
    private BotPlayer body;
    @Nullable
    private UUID bodyId;
    /** A loaded memory not yet installed: see the class comment. */
    @Nullable
    private CompoundTag pendingMemory;
    /**
     * Loaded shelter blocks of the reflex layer, not yet given back to it: like the memory, they are installed on the
     * first tick, when a live body (and its level, to check the blocks against) is there.
     */
    @Nullable
    private CompoundTag pendingShelterBlocks;

    /**
     * A station this body put down (a crafting table, a furnace) and was stopped before it took it up again (a
     * restart, a cancel, a failure) is taken up before the executor goes on: the planner counts the station in the
     * inventory, so one left standing would cost a goal its materials. Looked for after a load and whenever a goal
     * ends; {@link #tidy} is the task doing it.
     */
    private boolean tidyArmed;
    @Nullable
    private Task<BotPlayer> tidy;
    /** The block that stood where the tidy task is taking a station up: what its failure log names. */
    @Nullable
    private String tidyStation;

    /** The task the executor last asked for: the one whose end resets the body's input. */
    @Nullable
    private Task<BotPlayer> currentTask;
    private boolean taskWasActive;
    /** Whether the reflex layer held the body in the tick being run: it runs the navigator itself then. */
    private boolean heldThisTick;
    /**
     * The step the body is on, by the node's identity, and the game time it began. A retry or a replan that asks for a
     * task for the same node goes on counting, so the status says how long the body has been on the step, not on the
     * latest attempt at it.
     */
    @Nullable
    private String stepIdentity;
    private long stepStartedTick = -1;

    /** The last goal result, as a line; what a status says about an idle bot. */
    @Nullable
    private GoalResult lastResult;
    /**
     * The body's world-knowledge profile (design 4.5), set by {@code /aiplayer profile} and saved with the brain. The
     * default is what it always was: strict survival, a body that knows only what it has seen or explored.
     */
    private Profile profile = Profile.STRICT_SURVIVAL;
    /** The command being carried out, so the answer to it also goes to whoever typed it. */
    @Nullable
    private CommandSourceStack feedback;

    public BotRuntime() {
        Postconditions postconditions = new Postconditions();
        // The goal the body gives itself when its owner names a container: its postcondition is true once it has looked.
        postconditions.register(LookInside.KIND, LookInside::postcondition);
        this.executor = new GoalExecutor<>(this, postconditions, config());
    }

    /** The defaults, with the budgets the kinds of task that need their own have: see the constants above. */
    private static ExecutorConfig config() {
        ExecutorConfig defaults = ExecutorConfig.defaults();
        return new ExecutorConfig(defaults.maxReplansWithoutProgress(), defaults.maxTotalReplans(),
            defaults.totalReplanCeiling(), defaults.maxRepairReplans(), defaults.maxDeathsPerGoal(),
            defaults.deathExclusionRadius(), defaults.hazardExclusionRadius(), defaults.defaultTaskBudgetTicks(),
            Map.of(RecipeDef.SMELT, SMELT_BUDGET_TICKS, "build", BUILD_BUDGET_TICKS, FishTask.KIND, FISH_TASK_BUDGET_TICKS,
                "trade", TRADE_BUDGET_TICKS, UseOnTask.KIND, USE_ON_BUDGET_TICKS), defaults.stuckTicks(),
            defaults.preemptMaxPausedTicks(),
            defaults.preemptMaxCount(), defaults.preemptWindowTicks(), defaults.planRerunLimit(),
            defaults.planTimeoutTicks());
    }

    /** The runtime driving this body, or null when it has another brain (a test's, the navigation dev command's). */
    @Nullable
    public static BotRuntime of(BotPlayer body) {
        return body.brain() instanceof BotRuntime runtime ? runtime : null;
    }

    // ---------------------------------------------------------------------------------------------------------
    // What commands and chat do (BotTalk)
    // ---------------------------------------------------------------------------------------------------------

    /** Starts the goal now, or queues it behind the one running (design 3.4, the FIFO queue). */
    public void submit(Goal goal) {
        executor.submit(goal);
    }

    public void cancel(boolean all) {
        if (all) executor.cancelAll();
        else executor.cancel();
    }

    public void pause() {
        executor.pause();
    }

    public void resume() {
        executor.resume();
    }

    /** What the executor is doing, as an immutable picture. */
    public ExecutorView view() {
        return executor.view();
    }

    /** The goal that ended last, or null when none has since the body was made. Not saved. */
    @Nullable
    public GoalResult lastResult() {
        return lastResult;
    }

    /** The status paragraph: which goal, which step and how long the body has been on it, what it is doing, what waits. */
    public String statusText() {
        ExecutorView view = executor.view();
        long onStep = -1;
        String progress = "";
        if (view.goal() != null && stepStartedTick >= 0 && body != null && currentTask != null
                && (currentTask.state() == TaskState.RUNNING || currentTask.state() == TaskState.PAUSED)) {
            onStep = body.level().getGameTime() - stepStartedTick;
            try {
                progress = currentTask.progress();
            } catch (RuntimeException e) {
                // A status is for looking at: a task that cannot describe itself says nothing, and the log says why.
                MegaBlockBot.LOGGER.warn("A task could not say what it is doing", e);
            }
        }
        String text = BotSpeech.status(view, lastResult == null ? null : BotSpeech.result(lastResult), onStep, progress);
        // The live danger zones beside the reflex line: they are why a plan goes round a place (issue 29).
        if (body != null) {
            String zones = BotMemory.of(body.getUUID()).liveZonePhrase(body.level().getGameTime());
            if (!zones.isEmpty()) text = text + " Danger zones: " + zones + '.';
        }
        return text;
    }

    /** True while a goal is running or paused. */
    public boolean hasGoal() {
        return executor.active();
    }

    /** Makes this the body whose answers go out, and installs a parked memory and the layer's shelter blocks. Every entry point calls it first. */
    void attach(BotPlayer current) {
        if (body != current) {
            body = current;
            bodyId = current.getUUID();
        }
        if (pendingMemory != null) {
            BotMemory.restore(current.getUUID(), pendingMemory);
            pendingMemory = null;
        }
        if (pendingShelterBlocks != null) {
            ReflexSources.current().loadShelterBlocks(current, pendingShelterBlocks);
            pendingShelterBlocks = null;
        }
    }

    /**
     * Runs {@code action} with {@code source} told everything the bot says meanwhile, so a command's own feedback
     * includes the result it caused (a cancel publishes its CANCELLED result before the call returns).
     */
    void withFeedback(@Nullable CommandSourceStack source, Runnable action) {
        CommandSourceStack before = feedback;
        feedback = source;
        try {
            action.run();
        } finally {
            feedback = before;
        }
    }

    /**
     * The bot says something to its owner as a system message prefixed with its name. The log always gets the line. With
     * the owner online they get it in chat; the source of the command being carried out gets it too unless it is that
     * same player (they would read it twice). With no owner online it goes to the log and the command's feedback only.
     */
    void say(String text) {
        String name = body == null ? "bot" : body.getGameProfile().getName();
        String line = "<" + name + "> " + text;
        MegaBlockBot.LOGGER.info("{}", line);
        ServerPlayer owner = ownerOnline();
        if (owner != null) owner.sendSystemMessage(Component.literal(line));
        if (feedback != null && !(owner != null && feedback.getEntity() == owner)) {
            feedback.sendSuccess(() -> Component.literal(line), false);
        }
    }

    @Nullable
    ServerPlayer ownerOnline() {
        if (body == null || body.ownerId() == null) return null;
        MinecraftServer server = body.getServer();
        return server == null ? null : server.getPlayerList().getPlayer(body.ownerId());
    }

    @Nullable
    BotPlayer body() {
        return body;
    }

    // ---------------------------------------------------------------------------------------------------------
    // BotBrain
    // ---------------------------------------------------------------------------------------------------------

    @Override
    public void tick(BotPlayer current) {
        think(current);
        // Tasks only hand the navigator commands; its engine runs here, once per body tick, after the tasks have
        // decided and before the body applies its input. Without this the body never moves for a goal. A body nothing
        // has navigated yet has no navigator, and one with no command running writes no input. While a reflex holds
        // the body the navigator is the reflex layer's, and that layer runs it itself: a second tick here would move
        // the body twice as fast as a player.
        if (!heldThisTick && BotNavigator.tracks(current.getUUID())) BotNavigator.of(current).tick(current);
    }

    private void think(BotPlayer current) {
        attach(current);
        // The reflex layer is asked first, once per tick: while it holds the body nothing else here may drive it.
        ReflexHold hold = reflexHold(current);
        heldThisTick = hold.held();
        if (hold.held()) {
            // The leftover-station step is a task like any other: paused for the hold, taken up again after it.
            if (tidy != null && tidy.state() == TaskState.RUNNING) tidy.pause(current, hold.cause());
        } else {
            if (tidy != null && tidy.state() == TaskState.PAUSED) tidy.resume(current);
            // The executor waits for the station to be back in the inventory: its replan would plan without it.
            if (tidyTick(current)) return;
        }
        executor.tick(current, hold);
        if (planMayHaveChanged) {
            // A task was created this tick: the plan it belongs to is logged once, when it is not the one logged last.
            planMayHaveChanged = false;
            String plan = BotSpeech.plan(executor.view());
            if (!plan.isEmpty() && !plan.equals(loggedPlan)) {
                loggedPlan = plan;
                MegaBlockBot.LOGGER.info("{} plan: {}", current.getGameProfile().getName(), plan);
            }
        }
        // A task that has ended may have left the body walking: its movement input is reset once, the tick it ends. A
        // hold a command put on a body with no goal is never touched, because no task was ever active for it.
        boolean active = currentTask != null && (currentTask.state() == TaskState.RUNNING || currentTask.state() == TaskState.PAUSED);
        if (taskWasActive && !active) current.input().reset();
        taskWasActive = active;
    }

    /** Set when a task is created; the plan is then compared with the one logged last (see {@link #think}). */
    private boolean planMayHaveChanged;
    private String loggedPlan = "";

    /** True while the leftover-station task has the body this tick. */
    private boolean tidyTick(BotPlayer current) {
        if (tidy == null && tidyArmed && !(currentTask != null && currentTask.state() == TaskState.RUNNING)) {
            tidyArmed = false;
            BlockPos leftover = CraftTask.leftoverStation(current);
            if (leftover != null) {
                BlockState state = current.serverLevel().getBlockState(leftover);
                tidyStation = state.getBlock().getName().getString();
                MegaBlockBot.LOGGER.info("{} takes up the {} it left standing at {}",
                        current.getGameProfile().getName(), tidyStation, leftover.toShortString());
                tidy = CraftTask.takeUpStation(current.serverLevel(), leftover);
                tidy.start(current);
            }
        }
        if (tidy == null) return false;
        TickResult result = tidy.tick(current);
        if (result instanceof TickResult.Running) return true;
        if (result instanceof TickResult.Failed failed) {
            // Not a goal's failure: the station stays, remembered, and the next task that needs one takes it up.
            MegaBlockBot.LOGGER.warn("{} could not take up the {} it left standing: {}",
                    current.getGameProfile().getName(), tidyStation, BotSpeech.reason(failed.failure()));
        }
        tidy = null;
        tidyStation = null;
        return false;
    }

    private ReflexHold reflexHold(BotPlayer current) {
        try {
            ReflexHold hold = ReflexSources.current().holdFor(current);
            return hold == null ? ReflexHold.NONE : hold;
        } catch (RuntimeException e) {
            // Logged with its stack trace, and the body is the executor's this tick: a reflex bug must not stop the server.
            MegaBlockBot.LOGGER.error("The reflex source threw for {}; treating the body as free this tick",
                    current.getGameProfile().getName(), e);
            return ReflexHold.NONE;
        }
    }

    @Override
    public void onDeath(BotPlayer dead, DeathRecord death) {
        attach(dead);
        // The reflex layer is told before the executor is, so the hold ends with the body that died and not on the
        // replacement's first tick (issue 29: a dead body's brain is not ticked, so the layer would hold on paper).
        try {
            ReflexSources.current().onDeath(dead);
        } catch (RuntimeException e) {
            // The layer must not throw; if it does, the drop of the hold falls to the replacement's first tick.
            MegaBlockBot.LOGGER.error("The reflex source threw while {} died", dead.getGameProfile().getName(), e);
        }
        if (tidy != null) {
            tidy.abort(dead, "died");
            tidy = null;
            tidyStation = null;
        }
        List<Pos> drops = new ArrayList<>();
        for (BlockPos drop : death.dropPositions()) drops.add(pos(drop));
        executor.onDeath(new Failure.Died(death.dimension(), pos(death.pos()), drops, death.gameTime(),
                "the body died at " + death.pos().toShortString()));
    }

    @Override
    public void onRespawn(BotPlayer newBody) {
        attach(newBody);
        executor.onRespawn();
    }

    /** A goal running or paused, and also one queued that has not started (a reflex held the body when it was typed). */
    @Override
    public boolean wantsToStayLoaded() {
        return executor.active() || executor.hasQueuedGoal();
    }

    @Override
    public void save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("schema", SAVE_SCHEMA);
        // Absent when it is the default, so a body never given a profile saves what it always did.
        if (profile != Profile.STRICT_SURVIVAL) tag.putString("profile", profile.name());
        tag.put("executor", NbtForm.toTag(executor.save()));
        if (bodyId != null) tag.put("memory", BotMemory.of(bodyId).save());
        else if (pendingMemory != null) tag.put("memory", pendingMemory.copy());
        // The shelter blocks the layer remembers are the body's own state: a restart that loses them loses which
        // blocks to take down (issue 29). Nothing standing, nothing written: a brain with no shelter saves as before.
        if (body != null) {
            CompoundTag shelter = new CompoundTag();
            ReflexSources.current().saveShelterBlocks(body, shelter);
            if (!shelter.isEmpty()) tag.put("shelterBlocks", shelter);
        }
    }

    @Override
    public void load(CompoundTag tag, HolderLookup.Provider registries) {
        // A body saved with no brain of ours (or a brain that had nothing to say) has an empty compound.
        if (tag.isEmpty()) return;
        int schema = tag.getInt("schema");
        if (schema != SAVE_SCHEMA) {
            throw new IllegalArgumentException("the saved brain has schema " + schema + ", this build reads " + SAVE_SCHEMA);
        }
        if (tag.contains("memory", Tag.TAG_COMPOUND)) pendingMemory = tag.getCompound("memory").copy();
        // A profile the owner never set is not written at all; a name that is no longer a profile keeps the default.
        if (tag.contains("profile", Tag.TAG_STRING)) {
            try {
                profile = Profile.valueOf(tag.getString("profile"));
            } catch (IllegalArgumentException e) {
                profile = Profile.STRICT_SURVIVAL;
            }
        }
        // The layer's shelter blocks are given back to it on the first tick, when the body's level can be asked what
        // stands at each position now.
        if (tag.contains("shelterBlocks", Tag.TAG_COMPOUND)) pendingShelterBlocks = tag.getCompound("shelterBlocks").copy();
        if (tag.contains("executor", Tag.TAG_COMPOUND)) {
            // No body yet: the executor takes the live one with its first tick, and replans then (design 3.4).
            executor.load(NbtForm.toMap(tag.getCompound("executor")), null);
        }
        // Whatever the last session was in the middle of, a table it had put down is still standing.
        tidyArmed = true;
    }

    // ---------------------------------------------------------------------------------------------------------
    // ExecutorHost
    // ---------------------------------------------------------------------------------------------------------

    @Override
    public Snapshot snapshot(BotPlayer current, Goal goal) {
        // The goal the snapshot is for, and the first build goal queued behind it (issue 17): its structure report is
        // read from the same snapshot, so the plan made when it starts is never made blind. Only the first: each
        // report stats the blueprint's file and walks its footprint, and an owner who queues many build goals at
        // nearby sites must not multiply that over the whole queue on every snapshot.
        List<Goal> wants = new ArrayList<>();
        for (Goal queued : executor.view().queue()) {
            if (queued instanceof Goal.Build) {
                wants.add(queued);
                break;
            }
        }
        if (goal != null) wants.add(goal);
        return SnapshotCollector.collect(current, BotMemory.of(current.getUUID()), wants);
    }

    /** Where the body stands and what it carries: anything the stuck watchdog should count as progress changes it. */
    @Override
    public long progressSignature(BotPlayer current) {
        long hash = current.blockPosition().asLong();
        Inventory inventory = current.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) continue;
            hash = hash * 31 + BuiltInRegistries.ITEM.getId(stack.getItem());
            hash = hash * 31 + stack.getCount();
            hash = hash * 31 + slot;
        }
        return hash;
    }

    @Override
    public Task<BotPlayer> createTask(NodeSpec leaf, Map<String, String> hints) {
        // The registry answers a kind nobody registered with a task that fails at once, naming the kind.
        Task<BotPlayer> task = TaskRegistry.create(leaf, hints);
        currentTask = task;
        planMayHaveChanged = true;
        String identity = NodeIdentity.of(leaf);
        if (!identity.equals(stepIdentity) && body != null) {
            stepIdentity = identity;
            stepStartedTick = body.level().getGameTime();
        }
        return task;
    }

    @Override
    public PlanTicket plan(PlanRequest request) {
        return BotPlanner.plan(request);
    }

    @Override
    public long inputFingerprint(Goal goal, Snapshot snapshot, Exclusions exclusions) {
        return BotPlanner.inputFingerprint(goal, snapshot, exclusions);
    }

    /** The death is in a dimension that is loaded and is not yet five minutes old: its drops may still be there. */
    @Override
    public boolean dropsRecoverable(Failure.Died death, Snapshot now) {
        MinecraftServer server = body == null ? null : body.getServer();
        if (server == null) return false;
        ResourceLocation id = ResourceLocation.tryParse(death.dimension());
        if (id == null || server.getLevel(ResourceKey.create(Registries.DIMENSION, id)) == null) return false;
        return now.gameTime() - death.deathTime() < DROPS_RECOVERABLE_TICKS;
    }

    @Override
    public void publish(GoalResult result) {
        lastResult = result;
        stepIdentity = null;
        stepStartedTick = -1;
        // A goal that ended early (a cancel, a failure) may have left a station standing: the next tick looks.
        tidyArmed = true;
        String name = body == null ? "?" : body.getGameProfile().getName();
        MegaBlockBot.LOGGER.info("Goal result of {}: goal=[{}] status={} matched={}/{} failure=[{}]", name,
                BotSpeech.goal(result.goal()), result.status(), result.matched(), result.required(),
                result.failure() == null ? "none" : result.failure().kind() + ": " + result.failure().detail());
        say(BotSpeech.result(result));
        explain(result);
    }

    /**
     * For a goal that failed or ended partly done, the language layer's own words follow the built-in line when it has
     * any (design 3.3: a model is consulted at decision points, and the end of a goal is one). The built-in line is
     * said first and at once, so the owner is never left waiting on a model; with no model nothing more is said.
     */
    private void explain(GoalResult result) {
        BotPlayer current = body;
        if (current == null || current.getServer() == null) return;
        if (result.status() != GoalResult.Status.FAILED && result.status() != GoalResult.Status.PARTIAL) return;
        MinecraftServer server = current.getServer();
        try {
            BotTalk.explain(current, this, result).whenComplete((sentence, error) -> {
                // The layer answers on its own thread: the words are said on the server's.
                if (error == null && sentence != null && sentence.isPresent()) server.execute(() -> say(sentence.get()));
            });
        } catch (RuntimeException e) {
            // The contract says a layer never throws; if it does, the owner already has the built-in line.
            MegaBlockBot.LOGGER.error("The language layer threw while explaining a result", e);
        }
    }

    @Override
    public Profile profile() {
        return profile;
    }

    /**
     * The executor was held past its preemption limit (issue 29): where the body stands becomes a danger zone in its
     * memory, as wide as a hostile mob's reach, so the replan the executor makes of it takes another target instead of
     * walking the body back into the same hold.
     */
    @Override
    public void noteDangerZone(BotPlayer current, String reason) {
        if (current == null) return;
        double radius = ReflexConstants.DEFAULT_FOLLOW_RANGE + ReflexConstants.FOLLOW_RANGE_MARGIN;
        long now = current.level().getGameTime();
        BotMemory.of(current.getUUID()).noteDangerZone(current.level().dimension().location().toString(),
            current.blockPosition(), radius, now + ReflexConstants.DANGER_ZONE_TICKS, reason);
        MegaBlockBot.LOGGER.info("{}: {} (a danger zone {} blocks wide is noted around {} for {} ticks)",
            current.getGameProfile().getName(), reason, (int) radius, current.blockPosition().toShortString(),
            ReflexConstants.DANGER_ZONE_TICKS);
    }

    /** Sets the body's profile (the owner's word, saved with the brain); the default stays what it was. */
    public void setProfile(Profile profile) {
        this.profile = profile == null ? Profile.STRICT_SURVIVAL : profile;
    }

    @Override
    public void log(String message, @Nullable Throwable error) {
        String name = body == null ? "?" : body.getGameProfile().getName();
        if (error == null) MegaBlockBot.LOGGER.warn("Bot {}: {}", name, message);
        else MegaBlockBot.LOGGER.error("Bot {}: {}", name, message, error);
    }

    private static Pos pos(BlockPos pos) {
        return new Pos(pos.getX(), pos.getY(), pos.getZ());
    }
}
