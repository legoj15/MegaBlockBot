package com.legoj15.megablockbot.game.gametest;

import static com.legoj15.megablockbot.game.gametest.BotNavGameTests.FLOOR_Y;
import static com.legoj15.megablockbot.game.gametest.BotNavGameTests.room;

import com.legoj15.megablockbot.MegaBlockBot;
import com.legoj15.megablockbot.core.Pos;
import com.legoj15.megablockbot.core.exec.GoalResult;
import com.legoj15.megablockbot.core.goal.Goal;
import com.legoj15.megablockbot.core.plan.NodeSpec;
import com.legoj15.megablockbot.core.task.Task;
import com.legoj15.megablockbot.core.task.TaskState;
import com.legoj15.megablockbot.core.task.TickResult;
import com.legoj15.megablockbot.game.body.BotBrain;
import com.legoj15.megablockbot.game.body.BotPlayer;
import com.legoj15.megablockbot.game.body.BodyException;
import com.legoj15.megablockbot.game.body.BodyManager;
import com.legoj15.megablockbot.game.body.DeathRecord;
import com.legoj15.megablockbot.game.bot.BotRuntime;
import com.legoj15.megablockbot.game.build.Blueprint;
import com.legoj15.megablockbot.game.build.Blueprints;
import com.legoj15.megablockbot.game.gametest.BotNavGameTests.NavRun;
import com.legoj15.megablockbot.game.plan.BotMemory;
import com.legoj15.megablockbot.game.task.TaskFeature;
import com.legoj15.megablockbot.game.task.TaskRegistry;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

/**
 * Building a blueprint (issue 17): the {@code build} leaf and the build goal against a running server. A test
 * registers its blueprint through {@link Blueprints#register} (a name and a list of positions and states, removed when
 * the test ends), so no file is needed; what these protect is that the structure stands at the end and was put there
 * by the body -- placed from the body's inventory through its own hands, nothing outside the blueprint's box changed,
 * a block the rules protect is left standing and said so, and one plank short is said as a missing input with the
 * other blocks standing. Bodies are named per run and removed when a test ends, passed or not.
 *
 * <p>Placing items in a body's inventory, the blocks a test stands in the arena and the record of a player-placed
 * block are test setup, each done once before the task starts and named where it happens.
 */
public final class BuildGameTests {
    private static final String RUN_ID = Integer.toString((int) (System.nanoTime() & 0xFFFFF), 36);
    /** Building 17 blocks takes a walk, a reach and a click each; a goal also crafts the planks first. */
    private static final int BUILD_TICKS = 2400;
    private static final int GOAL_TICKS = 3600;

    private BuildGameTests() {}

    // The blueprint every task-level test builds: a 3 x 3 oak plank floor with a cobblestone ring on it (17 blocks).
    private static final List<Blueprint.Entry> HUT = hutEntries();

    private static List<Blueprint.Entry> hutEntries() {
        List<Blueprint.Entry> entries = new ArrayList<>();
        for (int x = 0; x <= 2; x++) {
            for (int z = 0; z <= 2; z++) {
                entries.add(new Blueprint.Entry(new BlockPos(x, 0, z), Blocks.OAK_PLANKS.defaultBlockState()));
            }
        }
        for (int x = 0; x <= 2; x++) {
            for (int z = 0; z <= 2; z++) {
                if (x == 1 && z == 1) continue; // the middle of the ring is the hut's inside
                entries.add(new Blueprint.Entry(new BlockPos(x, 1, z), Blocks.COBBLESTONE.defaultBlockState()));
            }
        }
        return List.copyOf(entries);
    }

    // ---------------------------------------------------------------------------------------------------------
    // The build leaf
    // ---------------------------------------------------------------------------------------------------------

    /**
     * The body holds exactly the blueprint's blocks (set-up, once before the task starts): the build leaf completes,
     * every position holds its block, the body's inventory holds none of them any more, and nothing outside the
     * blueprint's box changed.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = BUILD_TICKS)
    public static void buildsTheBlueprintFromWhatItHolds(GameTestHelper helper) {
        room(helper, 3);
        String name = "gtbf" + RUN_ID;
        Blueprint blueprint = Blueprints.register(name, HUT);
        BlockPos anchor = helper.absolutePos(new BlockPos(4, FLOOR_Y + 1, 10));
        Map<BlockPos, BlockState> around = snapshotAround(helper, anchor, 1);
        Job job = Job.start(helper, "bfloor", new Vec3(7.5, FLOOR_Y + 1, 7.5), BUILD_TICKS, leaf(helper, name, anchor));
        job.give(Items.OAK_PLANKS, 9);
        job.give(Items.COBBLESTONE, 8);
        job.run.atEnd(() -> Blueprints.forget(name));
        job.run.each(BUILD_TICKS - 200, () -> {
            failIfFailed(helper, job);
            if (job.task.state() != TaskState.COMPLETED) return;
            assertStands(helper, blueprint, anchor);
            helper.assertTrue(job.count(Items.OAK_PLANKS) == 0 && job.count(Items.COBBLESTONE) == 0,
                "the body still holds " + job.count(Items.OAK_PLANKS) + " planks and "
                    + job.count(Items.COBBLESTONE) + " cobblestone");
            assertUnchangedAround(helper, around);
            job.run.pass();
        });
    }

    /**
     * The same with one plank too few (set-up): the leaf ends {@code MissingInput} naming planks, and every other
     * block stands.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = BUILD_TICKS)
    public static void aPlankTooFewEndsMissingInputAndTheRestStands(GameTestHelper helper) {
        room(helper, 3);
        String name = "gtbs" + RUN_ID;
        Blueprint blueprint = Blueprints.register(name, HUT);
        BlockPos anchor = helper.absolutePos(new BlockPos(4, FLOOR_Y + 1, 10));
        Job job = Job.start(helper, "bshort", new Vec3(7.5, FLOOR_Y + 1, 7.5), BUILD_TICKS, leaf(helper, name, anchor));
        job.give(Items.OAK_PLANKS, 8);
        job.give(Items.COBBLESTONE, 8);
        job.run.atEnd(() -> Blueprints.forget(name));
        job.run.each(BUILD_TICKS - 200, () -> {
            helper.assertTrue(job.task.state() != TaskState.COMPLETED, "built a floor of nine planks from eight");
            if (job.task.state() != TaskState.FAILED) return;
            if (!(job.task.failure() instanceof com.legoj15.megablockbot.core.task.Failure.MissingInput missing)) {
                helper.fail("failed as " + job.task.failure());
                return;
            }
            helper.assertTrue(missing.key().equals(com.legoj15.megablockbot.core.ItemKey.of("oak_planks")),
                "MissingInput names " + missing.key());
            int stood = 0;
            ServerLevel level = helper.getLevel();
            for (Blueprint.Entry entry : blueprint.blocks()) {
                if (level.getBlockState(anchor.offset(entry.pos())).getBlock() == entry.state().getBlock()) stood++;
            }
            // The missing plank's own position is empty; the cobble over it may or may not find a face to go against.
            helper.assertTrue(stood >= 15, "only " + stood + " of 17 blocks stand");
            job.run.pass();
        });
    }

    /**
     * A dirt block already stands where a plank goes (set-up): it is broken and the plank placed, and the build
     * completes over it.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = BUILD_TICKS)
    public static void aDirtInTheWayIsBrokenAndReplaced(GameTestHelper helper) {
        room(helper, 3);
        String name = "gtbd" + RUN_ID;
        Blueprint blueprint = Blueprints.register(name, HUT);
        BlockPos anchor = helper.absolutePos(new BlockPos(4, FLOOR_Y + 1, 10));
        helper.setBlock(5, FLOOR_Y + 1, 11, Blocks.DIRT);
        Job job = Job.start(helper, "bdirt", new Vec3(7.5, FLOOR_Y + 1, 7.5), BUILD_TICKS, leaf(helper, name, anchor));
        job.give(Items.OAK_PLANKS, 9);
        job.give(Items.COBBLESTONE, 8);
        job.run.atEnd(() -> Blueprints.forget(name));
        job.run.each(BUILD_TICKS - 200, () -> {
            failIfFailed(helper, job);
            if (job.task.state() != TaskState.COMPLETED) return;
            assertStands(helper, blueprint, anchor);
            helper.assertTrue(helper.getBlockState(new BlockPos(5, FLOOR_Y + 1, 11)).is(Blocks.OAK_PLANKS),
                "the dirt was not replaced by the plank");
            job.run.pass();
        });
    }

    /**
     * The same dirt, but recorded as placed by a player (set-up, once before the task starts, as the block-safety
     * tests record one): the leaf ends {@code Protected} naming the position, and the dirt still stands.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = BUILD_TICKS)
    public static void aPlayerPlacedDirtInTheWayIsProtected(GameTestHelper helper) {
        room(helper, 3);
        String name = "gtbp" + RUN_ID;
        Blueprint blueprint = Blueprints.register(name, HUT);
        BlockPos anchor = helper.absolutePos(new BlockPos(4, FLOOR_Y + 1, 10));
        BlockPos dirt = helper.absolutePos(new BlockPos(5, FLOOR_Y + 1, 11));
        helper.setBlock(5, FLOOR_Y + 1, 11, Blocks.DIRT);
        ServerLevel level = helper.getLevel();
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        com.legoj15.megablockbot.game.safety.BlockSafety.of(level.getServer()).blockPlaced(level, dirt, player);
        Job job = Job.start(helper, "bprot", new Vec3(7.5, FLOOR_Y + 1, 7.5), BUILD_TICKS, leaf(helper, name, anchor));
        job.give(Items.OAK_PLANKS, 9);
        job.give(Items.COBBLESTONE, 8);
        job.run.atEnd(() -> {
            Blueprints.forget(name);
            // The mock player and what the server wrote for it are not worth keeping either way the test ends.
            MinecraftServer server = level.getServer();
            if (server.getPlayerList().getPlayers().contains(player)) server.getPlayerList().remove(player);
            forgetPlayerFiles(server, player.getUUID());
        });
        job.run.each(BUILD_TICKS - 200, () -> {
            helper.assertTrue(job.task.state() != TaskState.COMPLETED, "broke a block a player placed");
            if (job.task.state() != TaskState.FAILED) return;
            if (!(job.task.failure() instanceof com.legoj15.megablockbot.core.task.Failure.Protected protectedBlock)) {
                helper.fail("failed as " + job.task.failure());
                return;
            }
            BlockPos named = new BlockPos(protectedBlock.pos().x(), protectedBlock.pos().y(), protectedBlock.pos().z());
            helper.assertTrue(named.equals(dirt), "Protected names " + named + ", not the dirt at " + dirt);
            helper.assertTrue(level.getBlockState(dirt).is(Blocks.DIRT), "the dirt was broken");
            job.run.pass();
        });
    }

    // ---------------------------------------------------------------------------------------------------------
    // Through the runtime
    // ---------------------------------------------------------------------------------------------------------

    /**
     * The body holds three oak logs and eight cobblestone (set-up) and is asked to build the blueprint: the goal ends
     * satisfied, the planks crafted first and then built, and nothing of the blueprint's blocks is left in the
     * inventory.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = GOAL_TICKS)
    public static void theGoalCraftsThePlanksAndBuildsTheHut(GameTestHelper helper) {
        room(helper, 3);
        String name = "gtbg" + RUN_ID;
        Blueprints.register(name, HUT);
        BlockPos anchor = helper.absolutePos(new BlockPos(4, FLOOR_Y + 1, 10));
        Rig rig = Rig.spawn(helper, "bgoal", GOAL_TICKS);
        rig.atEnd(() -> Blueprints.forget(name));
        rig.give(Items.OAK_LOG, 3);
        rig.give(Items.COBBLESTONE, 8);
        rig.runtime().submit(new Goal.Build(name, dimension(helper), pos(anchor)));
        rig.each(GOAL_TICKS - 400, () -> {
            GoalResult result = rig.runtime().lastResult();
            if (result == null) return;
            helper.assertTrue(result.status() == GoalResult.Status.SATISFIED,
                "the goal ended " + com.legoj15.megablockbot.game.bot.BotSpeech.result(result));
            for (Blueprint.Entry entry : HUT) {
                helper.assertTrue(helper.getLevel().getBlockState(anchor.offset(entry.pos())).is(entry.state().getBlock()),
                    "the block at " + entry.pos() + " (" + entry.state().getBlock() + ") does not stand");
            }
            helper.assertTrue(rig.count(Items.OAK_PLANKS) == 3 && rig.count(Items.COBBLESTONE) == 0,
                "the body holds " + rig.count(Items.OAK_PLANKS) + " planks and " + rig.count(Items.COBBLESTONE)
                    + " cobblestone; three planks and no cobble were to be left");
            rig.pass();
        });
    }

    /**
     * The command: {@code /aiplayer blueprint save} of a small box of the arena (its blocks are test set-up), then a
     * build of the saved name at another place in the arena ends with the same blocks there. The file is deleted when
     * the test ends, however it ends.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = GOAL_TICKS)
    public static void theCommandSavesABoxThatBuildsElsewhere(GameTestHelper helper) {
        room(helper, 3);
        // Set-up: six planks standing in a 3 x 2 box, the shape the blueprint is saved from.
        for (int x = 4; x <= 6; x++) {
            for (int z = 4; z <= 5; z++) helper.setBlock(x, FLOOR_Y + 1, z, Blocks.OAK_PLANKS);
        }
        String name = "gtbc" + RUN_ID;
        MinecraftServer server = helper.getLevel().getServer();
        Feedback feedback = new Feedback();
        CommandSourceStack source = source(server, helper.getLevel(), 2, null, feedback);
        BlockPos from = helper.absolutePos(new BlockPos(4, FLOOR_Y + 1, 4));
        BlockPos to = helper.absolutePos(new BlockPos(6, FLOOR_Y + 1, 5));
        int saved = run(server, source, "aiplayer blueprint save " + name + " " + from.getX() + " " + from.getY() + " "
            + from.getZ() + " " + to.getX() + " " + to.getY() + " " + to.getZ());
        helper.assertTrue(saved == 1, "the save command failed: " + feedback.messages);
        helper.assertTrue(Blueprints.load(server, name) != null, "the saved blueprint did not load back");

        BlockPos anchor = helper.absolutePos(new BlockPos(4, FLOOR_Y + 1, 10));
        Rig rig = Rig.spawn(helper, "bcmd", GOAL_TICKS);
        rig.atEnd(() -> {
            Blueprints.forget(name);
            try {
                Files.deleteIfExists(Blueprints.folder().resolve(name + ".nbt"));
            } catch (IOException ignored) {
                // A leftover file of a test blueprint is harmless; its name is unique to this run.
            }
        });
        rig.give(Items.OAK_PLANKS, 6);
        rig.runtime().submit(new Goal.Build(name, dimension(helper), pos(anchor)));
        rig.each(GOAL_TICKS - 400, () -> {
            GoalResult result = rig.runtime().lastResult();
            if (result == null) return;
            helper.assertTrue(result.status() == GoalResult.Status.SATISFIED,
                "the goal ended " + com.legoj15.megablockbot.game.bot.BotSpeech.result(result));
            for (int x = 4; x <= 6; x++) {
                for (int z = 10; z <= 11; z++) {
                    helper.assertTrue(helper.getBlockState(new BlockPos(x, FLOOR_Y + 1, z)).is(Blocks.OAK_PLANKS),
                        "the saved shape does not stand at " + x + " " + z);
                }
            }
            rig.pass();
        });
    }

    // ---------------------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------------------

    /** A build leaf as the planner writes it: the goal's own name, dimension and anchor. */
    private static NodeSpec leaf(GameTestHelper helper, String name, BlockPos anchor) {
        Goal.Build goal = new Goal.Build(name, dimension(helper), pos(anchor));
        return NodeSpec.leaf(goal, TaskFeature.BUILD,
            Map.of("blueprint", name, "dimension", goal.dimension(), "anchor", goal.anchor().encode()), "");
    }

    private static String dimension(GameTestHelper helper) {
        return helper.getLevel().dimension().location().toString();
    }

    private static Pos pos(BlockPos at) {
        return new Pos(at.getX(), at.getY(), at.getZ());
    }

    private static void failIfFailed(GameTestHelper helper, Job job) {
        if (job.task.state() == TaskState.FAILED) helper.fail("the task failed: " + job.task.failure());
    }

    private static void assertStands(GameTestHelper helper, Blueprint blueprint, BlockPos anchor) {
        ServerLevel level = helper.getLevel();
        for (Blueprint.Entry entry : blueprint.blocks()) {
            helper.assertTrue(level.getBlockState(anchor.offset(entry.pos())).is(entry.state().getBlock()),
                "the block at " + entry.pos() + " (" + entry.state().getBlock() + ") does not stand");
        }
    }

    /** The blocks in a box {@code inflate} around the anchor, minus the blueprint's own box, as they stand now. */
    private static Map<BlockPos, BlockState> snapshotAround(GameTestHelper helper, BlockPos anchor, int inflate) {
        Map<BlockPos, BlockState> seen = new HashMap<>();
        BlockPos min = anchor.offset(-inflate, -1, -inflate);
        BlockPos max = anchor.offset(2 + inflate, 2 + inflate, 2 + inflate);
        for (BlockPos at : BlockPos.betweenClosed(min, max)) {
            if (insideBlueprint(at, anchor)) continue;
            seen.put(at.immutable(), helper.getLevel().getBlockState(at));
        }
        return seen;
    }

    private static boolean insideBlueprint(BlockPos at, BlockPos anchor) {
        int x = at.getX() - anchor.getX();
        int y = at.getY() - anchor.getY();
        int z = at.getZ() - anchor.getZ();
        return x >= 0 && x <= 2 && y >= 0 && y <= 1 && z >= 0 && z <= 2;
    }

    private static void assertUnchangedAround(GameTestHelper helper, Map<BlockPos, BlockState> before) {
        ServerLevel level = helper.getLevel();
        for (Map.Entry<BlockPos, BlockState> stood : before.entrySet()) {
            helper.assertTrue(level.getBlockState(stood.getKey()).is(stood.getValue().getBlock()),
                "the block at " + stood.getKey() + " outside the blueprint's box changed from "
                    + stood.getValue().getBlock() + " to " + level.getBlockState(stood.getKey()).getBlock());
        }
    }

    private static void forgetPlayerFiles(MinecraftServer server, UUID id) {
        try {
            Files.deleteIfExists(server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(id + ".dat"));
            Files.deleteIfExists(server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(id + ".dat_old"));
            Files.deleteIfExists(server.getWorldPath(LevelResource.PLAYER_STATS_DIR).resolve(id + ".json"));
            Files.deleteIfExists(server.getWorldPath(LevelResource.PLAYER_ADVANCEMENTS_DIR).resolve(id + ".json"));
        } catch (IOException ignored) {
            // Leftover files of a test player are harmless; its id is random.
        }
    }

    private static CommandSourceStack source(MinecraftServer server, ServerLevel level, int permission,
                                             net.minecraft.world.entity.Entity entity, CommandSource sink) {
        return new CommandSourceStack(sink, Vec3.ZERO, Vec2.ZERO, level, permission, "gt", Component.literal("gt"),
            server, entity);
    }

    private static int run(MinecraftServer server, CommandSourceStack source, String line) {
        try {
            return server.getCommands().getDispatcher().execute(line, source);
        } catch (CommandSyntaxException e) {
            throw new IllegalStateException("'" + line + "' did not parse: " + e.getMessage(), e);
        }
    }

    /** Keeps what a command source is told. */
    private static final class Feedback implements CommandSource {
        final List<String> messages = new ArrayList<>();

        @Override public void sendSystemMessage(Component message) { messages.add(message.getString()); }
        @Override public boolean acceptsSuccess() { return true; }
        @Override public boolean acceptsFailure() { return true; }
        @Override public boolean shouldInformAdmins() { return false; }
    }

    /** Ticks one task, then the navigator, every body tick: the order that lets a command start driving the same tick. */
    private static final class TaskBrain implements BotBrain {
        private final Task<BotPlayer> task;

        TaskBrain(Task<BotPlayer> task) {
            this.task = task;
        }

        @Override
        public void tick(BotPlayer body) {
            if (task.state() == TaskState.PENDING) task.start(body);
            if (task.state() == TaskState.RUNNING) {
                TickResult result = task.tick(body);
                if (result == null) throw new IllegalStateException("the task returned no result");
            }
            com.legoj15.megablockbot.game.nav.BotNavigator.of(body).tick(body);
        }

        @Override public void onDeath(BotPlayer body, DeathRecord death) {}
        @Override public void onRespawn(BotPlayer newBody) {}
        @Override public boolean wantsToStayLoaded() { return true; }
        @Override public void save(CompoundTag tag, HolderLookup.Provider registries) {}
        @Override public void load(CompoundTag tag, HolderLookup.Provider registries) {}
    }

    /** One test: a body running one task, watched by the shared harness. */
    private static final class Job {
        final NavRun run;
        final Task<BotPlayer> task;

        private Job(NavRun run, Task<BotPlayer> task) {
            this.run = run;
            this.task = task;
        }

        static Job start(GameTestHelper helper, String tag, Vec3 relative, int deadlineTicks, NodeSpec leaf) {
            Task<BotPlayer> task = TaskRegistry.create(leaf, Map.of());
            TaskBrain brain = new TaskBrain(task);
            NavRun run = NavRun.start(helper, tag, relative, deadlineTicks, () -> brain);
            // Breaking is allowed in this test's arena and nowhere outside the registered ones (the rules are global,
            // so a class that set its own would take these tests' away).
            run.atEnd(MoveAndMineGameTests.allowBreaking(helper));
            return new Job(run, task);
        }

        BotPlayer body() {
            return run.body();
        }

        void give(Item item, int count) {
            body().getInventory().add(new ItemStack(item, count));
        }

        int count(Item item) {
            return body().getInventory().countItem(item);
        }
    }

    /**
     * A body with a real runtime, for the goal-level tests: spawned with the default brain (a {@code BotRuntime}, which
     * the bot feature installs) and watched every tick, despawned and forgotten when the test ends, passed or not.
     */
    private static final class Rig {
        private final GameTestHelper helper;
        private final BodyManager manager;
        private final String name;
        private final UUID id;
        private final List<Runnable> endings = new ArrayList<>();
        int ticks;

        private Rig(GameTestHelper helper, String tag) {
            this.helper = helper;
            this.manager = BodyManager.of(helper.getLevel().getServer());
            this.name = "gt_" + tag + "_" + RUN_ID;
            this.id = BodyManager.uuidFor(name);
        }

        static Rig spawn(GameTestHelper helper, String tag, int deadlineTicks) {
            Rig rig = new Rig(helper, tag);
            Vec3 at = helper.absoluteVec(new Vec3(7.5, FLOOR_Y + 1, 7.5));
            try {
                rig.manager.spawn(rig.name, null, helper.getLevel(), at);
            } catch (BodyException e) {
                throw new IllegalStateException("could not spawn " + rig.name + ": " + e.getMessage(), e);
            }
            Runnable undo = MoveAndMineGameTests.allowBreaking(helper);
            rig.atEnd(undo);
            helper.runAtTickTime(deadlineTicks, rig::cleanup);
            return rig;
        }

        BotPlayer body() {
            BotPlayer current = manager.get(id);
            if (current == null) throw new IllegalStateException("no body " + name + " is logged in");
            return current;
        }

        BotRuntime runtime() {
            BotRuntime runtime = BotRuntime.of(body());
            if (runtime == null) throw new IllegalStateException(name + " has the brain " + body().brain());
            return runtime;
        }

        void give(Item item, int count) {
            body().getInventory().add(new ItemStack(item, count));
        }

        int count(Item item) {
            return body().getInventory().countItem(item);
        }

        void atEnd(Runnable undo) {
            endings.add(undo);
        }

        /** Every tick until the test ends: a budget, then the check, which passes or fails the test. */
        void each(int budget, Runnable check) {
            helper.onEachTick(() -> {
                try {
                    if (++ticks > budget) {
                        helper.fail("not done after " + budget + " ticks");
                        return;
                    }
                    check.run();
                } catch (RuntimeException | Error e) {
                    cleanup();
                    throw e;
                }
            });
        }

        void pass() {
            cleanup();
            MegaBlockBot.LOGGER.info("Build GameTest {}: passed after {} ticks", name, ticks);
            helper.succeed();
        }

        void cleanup() {
            endings.forEach(Runnable::run);
            endings.clear();
            BotPlayer current = manager.get(id);
            if (current != null) manager.despawn(current);
            try {
                manager.deleteSaved(id);
            } catch (BodyException ignored) {
                // Still logged in only if the despawn did not take; the unique name keeps that from mattering.
            }
            BotMemory.forget(id);
        }
    }
}
