package com.legoj15.megablockbot.game.gametest;

import com.legoj15.megablockbot.MegaBlockBot;
import com.legoj15.megablockbot.core.EquipSlot;
import com.legoj15.megablockbot.core.ItemKey;
import com.legoj15.megablockbot.core.exec.GoalResult;
import com.legoj15.megablockbot.core.exec.GoalStatus;
import com.legoj15.megablockbot.core.goal.Goal;
import com.legoj15.megablockbot.core.plan.NodeSpec;
import com.legoj15.megablockbot.core.task.Failure;
import com.legoj15.megablockbot.core.task.Task;
import com.legoj15.megablockbot.core.task.TaskState;
import com.legoj15.megablockbot.core.task.TickResult;
import com.legoj15.megablockbot.game.body.BodyException;
import com.legoj15.megablockbot.game.body.BodyManager;
import com.legoj15.megablockbot.game.body.BotPlayer;
import com.legoj15.megablockbot.game.bot.BotRuntime;
import com.legoj15.megablockbot.game.bot.BotSpeech;
import com.legoj15.megablockbot.game.bot.NbtForm;
import com.legoj15.megablockbot.game.plan.BotMemory;
import com.legoj15.megablockbot.game.task.TaskRegistry;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.LastSeenMessages;
import net.minecraft.network.protocol.game.ServerboundChatPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

/**
 * The bot runtime against a running server (design 3.4, issues 10 to 12). Body names are at most 16 characters, so a
 * test's tag is at most 7. What these protect: that a goal typed to a
 * body turns into real crafting through the menus a player uses (a recipe that needs a table cannot be made any other
 * way), that the table the body puts down comes up again and the one it found stays, that a typed goal and a chat line
 * mean the same thing and only the owner's chat counts, and that a goal in progress survives a save and a respawn of
 * the body. A craft that skips the menu, a table left behind, a result that names no reason, or a goal that vanishes
 * across a save fails one of them.
 *
 * <p>Each test spawns its own body (a name unique to this run) in the arena, whose floor is the stone layer at y=1:
 * feet stand at y=2.0. Bodies are despawned and forgotten when the test ends, passed or not. Placing items in a body's
 * inventory is test setup, not the bot cheating: the bot's own crafting is what is measured.
 */
public final class BotRuntimeGameTests {
    private static final int FLOOR_Y = 1;
    private static final double FLOOR_TOP = FLOOR_Y + 1.0;
    private static final String RUN_ID = Integer.toString((int) (System.nanoTime() & 0xFFFFF), 36);
    /** A pickaxe goal takes about 200 ticks: planks, table, sticks, then place, craft and break the table. */
    private static final int PICKAXE_TICKS = 700;

    private BotRuntimeGameTests() {}

    // ---------------------------------------------------------------------------------------------------------
    // Crafting
    // ---------------------------------------------------------------------------------------------------------

    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 300)
    public static void fourPlanksFromOneLog(GameTestHelper helper) {
        floor(helper);
        Rig rig = Rig.spawn(helper, "planks", null, 300);
        rig.give(Items.OAK_LOG, 1);
        rig.runtime().submit(new Goal.HaveItem(ItemKey.of("oak_planks"), 4));
        rig.onEachTick(() -> {
            GoalResult result = rig.runtime().lastResult();
            if (result == null) return;
            helper.assertTrue(result.status() == GoalResult.Status.SATISFIED, "the goal ended " + BotSpeech.result(result));
            helper.assertTrue(rig.count(Items.OAK_PLANKS) == 4 && rig.count(Items.OAK_LOG) == 0,
                "expected 4 planks and no log, got " + rig.count(Items.OAK_PLANKS) + " planks and " + rig.count(Items.OAK_LOG) + " logs");
            rig.assertHandsEmpty();
            rig.pass();
        });
    }

    /** The text goes through the command, the language layer and the planner: planks first, then sticks from them. */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 300)
    public static void sticksFromTextThroughTheCommandPath(GameTestHelper helper) {
        floor(helper);
        Rig rig = Rig.spawnOwned(helper, "sticks", 300);
        rig.give(Items.OAK_LOG, 1);
        Feedback feedback = new Feedback();
        helper.assertTrue(run(rig.server(), rig.ownerSource(feedback), "aiplayer goal " + rig.name() + " make 4 stick") == 1,
            "the goal command failed: " + feedback.messages);
        helper.assertTrue(feedback.contains("get 4 stick"), "the bot did not say what it took on: " + feedback.messages);
        rig.onEachTick(() -> {
            GoalResult result = rig.runtime().lastResult();
            if (result == null) return;
            helper.assertTrue(result.status() == GoalResult.Status.SATISFIED, "the goal ended " + BotSpeech.result(result));
            // One log is four planks, and four sticks cost two of them.
            helper.assertTrue(rig.count(Items.STICK) == 4 && rig.count(Items.OAK_PLANKS) == 2 && rig.count(Items.OAK_LOG) == 0,
                "expected 4 sticks, 2 planks and no log, got " + rig.count(Items.STICK) + ", " + rig.count(Items.OAK_PLANKS) + ", "
                    + rig.count(Items.OAK_LOG));
            helper.assertTrue(BotSpeech.result(result).startsWith("Done: get 4 stick"), "the result reads " + BotSpeech.result(result));
            rig.pass();
        });
    }

    /**
     * Three logs and nothing else: planks, a table, sticks, then the pickaxe, which only the 3x3 grid can make. The
     * table is placed beside the body, used and broken again, and its item is back in the inventory at the end.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = PICKAXE_TICKS)
    public static void woodenPickaxeFromThreeLogs(GameTestHelper helper) {
        floor(helper);
        Rig rig = Rig.spawn(helper, "pickaxe", null, PICKAXE_TICKS);
        rig.give(Items.OAK_LOG, 3);
        rig.runtime().submit(new Goal.HaveItem(ItemKey.of("wooden_pickaxe"), 1));
        boolean[] sawTable = {false};
        rig.onEachTick(() -> {
            if (!rig.tablesNearBody().isEmpty()) sawTable[0] = true;
            GoalResult result = rig.runtime().lastResult();
            if (result == null) return;
            helper.assertTrue(result.status() == GoalResult.Status.SATISFIED, "the goal ended " + BotSpeech.result(result));
            helper.assertTrue(rig.count(Items.WOODEN_PICKAXE) == 1, "no wooden pickaxe in the inventory");
            helper.assertTrue(rig.firstStack(Items.WOODEN_PICKAXE).getDamageValue() == 0, "the new pickaxe was worn down breaking the table");
            helper.assertTrue(rig.count(Items.CRAFTING_TABLE) == 1, "the crafting table is not back in the inventory: " + rig.count(Items.CRAFTING_TABLE));
            helper.assertTrue(sawTable[0], "no crafting table ever stood beside the body, so the pickaxe was not made at one");
            helper.assertTrue(rig.tablesInArena().isEmpty(), "a crafting table was left standing at " + rig.tablesInArena());
            helper.assertTrue(BotMemory.of(rig.id()).placedCount() == 0, "the body still remembers a block it placed");
            rig.assertHandsEmpty();
            rig.pass();
        });
    }

    /**
     * The table's item hops away from the block it came from and can come to rest out of the body's pickup reach. The test
     * moves it three blocks off the moment it appears, which is where it would sit on a bad hop: the body must walk over
     * and take it, by its input, and the goal must still end with the table back in the inventory.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = PICKAXE_TICKS)
    public static void aTableDropOutOfReachIsWalkedOver(GameTestHelper helper) {
        floor(helper);
        Rig rig = Rig.spawn(helper, "fetch", null, PICKAXE_TICKS);
        rig.give(Items.OAK_LOG, 3);
        rig.runtime().submit(new Goal.HaveItem(ItemKey.of("wooden_pickaxe"), 1));
        boolean[] moved = {false};
        rig.onEachTick(() -> {
            BotPlayer body = rig.body();
            if (!moved[0]) {
                for (ItemEntity drop : helper.getLevel().getEntitiesOfClass(ItemEntity.class, body.getBoundingBox().inflate(3.0),
                    candidate -> candidate.getItem().is(Items.CRAFTING_TABLE))) {
                    drop.setPos(body.getX() + 3.0, body.getY() + 0.3, body.getZ());
                    drop.setDeltaMovement(Vec3.ZERO);
                    moved[0] = true;
                }
            }
            GoalResult result = rig.runtime().lastResult();
            if (result == null) return;
            helper.assertTrue(moved[0], "the table's drop never appeared, so nothing was tested");
            helper.assertTrue(result.status() == GoalResult.Status.SATISFIED, "the goal ended " + BotSpeech.result(result));
            helper.assertTrue(rig.count(Items.WOODEN_PICKAXE) == 1, "no wooden pickaxe in the inventory");
            helper.assertTrue(rig.count(Items.CRAFTING_TABLE) == 1, "the table's item was not fetched: " + rig.count(Items.CRAFTING_TABLE));
            helper.assertTrue(rig.tablesInArena().isEmpty(), "a crafting table was left standing at " + rig.tablesInArena());
            rig.assertHandsEmpty();
            rig.pass();
        });
    }

    /** A table that stands beside the body is used where it is and left standing; the bot placed none of its own. */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = PICKAXE_TICKS)
    public static void usesTheTableThatStandsBeside(GameTestHelper helper) {
        floor(helper);
        BlockPos standing = new BlockPos(9, FLOOR_Y + 1, 8);
        helper.setBlock(standing, Blocks.CRAFTING_TABLE);
        Rig rig = Rig.spawn(helper, "stand", null, PICKAXE_TICKS);
        rig.give(Items.OAK_LOG, 3);
        rig.runtime().submit(new Goal.HaveItem(ItemKey.of("wooden_pickaxe"), 1));
        boolean[] extraTable = {false};
        rig.onEachTick(() -> {
            if (rig.tablesNearBody().size() > 1) extraTable[0] = true;
            GoalResult result = rig.runtime().lastResult();
            if (result == null) return;
            helper.assertTrue(result.status() == GoalResult.Status.SATISFIED, "the goal ended " + BotSpeech.result(result));
            helper.assertTrue(rig.count(Items.WOODEN_PICKAXE) == 1, "no wooden pickaxe in the inventory");
            helper.assertTrue(helper.getBlockState(standing).is(Blocks.CRAFTING_TABLE), "the table that stood there was taken away");
            helper.assertTrue(rig.tablesInArena().size() == 1, "expected only the table that stood there, found " + rig.tablesInArena());
            helper.assertTrue(!extraTable[0], "the body placed a table of its own although one stood beside it");
            rig.assertHandsEmpty();
            rig.pass();
        });
    }

    /** What the planner chose for each slot is what goes in the grid; without a layout, the first alternative held. */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 200)
    public static void craftFollowsTheLayoutItIsGiven(GameTestHelper helper) {
        floor(helper);
        Rig rig = Rig.spawn(helper, "layout", null, 200);
        rig.give(Items.COAL, 2);
        rig.give(Items.CHARCOAL, 2);
        rig.give(Items.STICK, 4);
        ItemKey torch = ItemKey.of("torch");
        // Torches take a coal or a charcoal over a stick. The tag lists coal first, so only a layout puts charcoal in.
        Map<String, String> withLayout = Map.of("recipe", "minecraft:torch", "times", "1", "station", "",
            "layout", "1*minecraft:charcoal,minecraft:stick");
        Map<String, String> withoutLayout = Map.of("recipe", "minecraft:torch", "times", "1", "station", "");
        Task<BotPlayer> first = TaskRegistry.create(NodeSpec.leaf(new Goal.HaveItem(torch, 4), "craft", withLayout, ""), Map.of());
        Task<BotPlayer> second = TaskRegistry.create(NodeSpec.leaf(new Goal.HaveItem(torch, 8), "craft", withoutLayout, ""), Map.of());
        AtomicReference<Task<BotPlayer>> current = new AtomicReference<>(first);
        int[] stage = {0};
        rig.onEachTick(() -> {
            BotPlayer body = rig.body();
            Task<BotPlayer> task = current.get();
            if (task.state() == TaskState.PENDING) task.start(body);
            TickResult result = task.tick(body);
            if (result instanceof TickResult.Failed failed) {
                helper.fail("the craft failed: " + failed.failure());
                return;
            }
            if (!(result instanceof TickResult.Completed)) return;
            if (stage[0] == 0) {
                helper.assertTrue(rig.count(Items.CHARCOAL) == 1 && rig.count(Items.COAL) == 2 && rig.count(Items.TORCH) == 4,
                    "the layout asked for charcoal: coal " + rig.count(Items.COAL) + ", charcoal " + rig.count(Items.CHARCOAL)
                        + ", torches " + rig.count(Items.TORCH));
                stage[0] = 1;
                current.set(second);
                return;
            }
            helper.assertTrue(rig.count(Items.COAL) == 1 && rig.count(Items.CHARCOAL) == 1 && rig.count(Items.TORCH) == 8,
                "with no layout the first alternative held is coal: coal " + rig.count(Items.COAL) + ", charcoal "
                    + rig.count(Items.CHARCOAL) + ", torches " + rig.count(Items.TORCH));
            rig.assertHandsEmpty();
            rig.pass();
        });
    }

    // ---------------------------------------------------------------------------------------------------------
    // Results
    // ---------------------------------------------------------------------------------------------------------

    /** Nothing supplies bedrock: the goal fails at planning, and the result names the item. */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 200)
    public static void anItemNothingSuppliesFailsAndNamesIt(GameTestHelper helper) {
        floor(helper);
        Rig rig = Rig.spawn(helper, "bedrock", null, 200);
        rig.runtime().submit(new Goal.HaveItem(ItemKey.of("bedrock"), 1));
        rig.onEachTick(() -> {
            GoalResult result = rig.runtime().lastResult();
            if (result == null) return;
            helper.assertTrue(result.status() == GoalResult.Status.FAILED, "expected FAILED, the goal ended " + BotSpeech.result(result));
            helper.assertTrue(result.failure() instanceof Failure.NoSource none && none.key().id().equals("minecraft:bedrock"),
                "the failure does not name bedrock: " + result.failure());
            helper.assertTrue(BotSpeech.result(result).contains("bedrock"), "what the bot says does not name the item: " + BotSpeech.result(result));
            rig.pass();
        });
    }

    // ---------------------------------------------------------------------------------------------------------
    // Persistence
    // ---------------------------------------------------------------------------------------------------------

    /**
     * A goal in progress is saved with the body, the body is logged out and made again, and the brain it gets is a new
     * runtime that loads the goal and finishes it. The body keeps its inventory through the same save.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = PICKAXE_TICKS + 100)
    public static void aGoalInProgressSurvivesALogout(GameTestHelper helper) {
        floor(helper);
        Rig rig = Rig.spawn(helper, "persist", null, PICKAXE_TICKS + 100);
        rig.give(Items.OAK_LOG, 3);
        rig.runtime().submit(new Goal.HaveItem(ItemKey.of("wooden_pickaxe"), 1));
        BotRuntime[] before = new BotRuntime[1];
        int[] stage = {0};
        int[] waited = {0};
        rig.onEachTick(() -> {
            switch (stage[0]) {
                case 0 -> {
                    // The plan is adopted: there is a goal with steps to save.
                    if (rig.runtime().view().nodes().isEmpty()) return;
                    before[0] = rig.runtime();
                    helper.assertTrue(rig.runtime().wantsToStayLoaded(), "a body with a goal does not want to stay loaded");
                    rig.manager().despawn(rig.body());
                    stage[0] = 1;
                }
                case 1 -> {
                    if (++waited[0] < 5) return;
                    rig.respawn(helper.absoluteVec(new Vec3(8.5, FLOOR_TOP, 8.5)));
                    BotRuntime after = rig.runtime();
                    helper.assertTrue(after != before[0], "the respawned body kept the old runtime");
                    helper.assertTrue(after.hasGoal(), "the goal did not come back with the body");
                    helper.assertTrue(after.view().goal() instanceof Goal.HaveItem have && have.key().id().equals("minecraft:wooden_pickaxe"),
                        "the goal that came back is " + after.view().goal());
                    stage[0] = 2;
                }
                default -> {
                    GoalResult result = rig.runtime().lastResult();
                    if (result == null) return;
                    helper.assertTrue(result.status() == GoalResult.Status.SATISFIED, "the goal ended " + BotSpeech.result(result));
                    helper.assertTrue(rig.count(Items.WOODEN_PICKAXE) == 1, "no wooden pickaxe after the restart");
                    helper.assertTrue(rig.tablesInArena().isEmpty(), "a crafting table was left standing at " + rig.tablesInArena());
                    rig.pass();
                }
            }
        });
    }

    /**
     * The body is logged out the moment its table stands, which is what a server stop in the middle of a craft does. The
     * planner counts a table in the inventory, not in the world, so a table left standing would cost the goal its planks:
     * the new runtime takes the table up first (its memory says it placed it), then finishes the goal.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = PICKAXE_TICKS + 200)
    public static void aTableLeftStandingByALogoutIsTakenUp(GameTestHelper helper) {
        floor(helper);
        Rig rig = Rig.spawn(helper, "tidy", null, PICKAXE_TICKS + 200);
        rig.give(Items.OAK_LOG, 3);
        rig.runtime().submit(new Goal.HaveItem(ItemKey.of("wooden_pickaxe"), 1));
        BotRuntime[] before = new BotRuntime[1];
        int[] stage = {0};
        int[] waited = {0};
        rig.onEachTick(() -> {
            switch (stage[0]) {
                case 0 -> {
                    if (rig.tablesInArena().isEmpty()) return;
                    helper.assertTrue(BotMemory.of(rig.id()).placedCount() == 1, "the body does not remember placing the table");
                    before[0] = rig.runtime();
                    rig.manager().despawn(rig.body());
                    stage[0] = 1;
                }
                case 1 -> {
                    if (++waited[0] < 5) return;
                    rig.respawn(helper.absoluteVec(new Vec3(8.5, FLOOR_TOP, 8.5)));
                    helper.assertTrue(rig.runtime() != before[0], "the respawned body kept the old runtime");
                    helper.assertTrue(!rig.tablesInArena().isEmpty(), "the table is gone before the new body did anything");
                    stage[0] = 2;
                }
                default -> {
                    GoalResult result = rig.runtime().lastResult();
                    if (result == null) return;
                    helper.assertTrue(result.status() == GoalResult.Status.SATISFIED, "the goal ended " + BotSpeech.result(result));
                    helper.assertTrue(rig.count(Items.WOODEN_PICKAXE) == 1, "no wooden pickaxe after the restart");
                    helper.assertTrue(rig.count(Items.CRAFTING_TABLE) == 1, "the table is not back in the inventory: " + rig.count(Items.CRAFTING_TABLE));
                    helper.assertTrue(rig.tablesInArena().isEmpty(), "a crafting table was left standing at " + rig.tablesInArena());
                    helper.assertTrue(BotMemory.of(rig.id()).placedCount() == 0, "the body still remembers a block it placed");
                    rig.assertHandsEmpty();
                    rig.pass();
                }
            }
        });
    }

    /** The save form's types and nesting survive the trip through NBT unchanged, a list of mixed things included. */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 20)
    public static void theSaveFormRoundTripsThroughNbt(GameTestHelper helper) {
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("flag", true);
        inner.put("off", false);
        inner.put("ratio", -0.5);
        inner.put("big", Long.MAX_VALUE);
        inner.put("small", Long.MIN_VALUE);
        inner.put("text", "mine:minecraft:iron_ore");
        inner.put("empty", "");
        Map<String, Object> form = new LinkedHashMap<>();
        form.put("v", 1L);
        form.put("paused", false);
        form.put("nested", inner);
        form.put("emptyMap", new LinkedHashMap<String, Object>());
        form.put("emptyList", new ArrayList<>());
        form.put("mixed", new ArrayList<Object>(List.of("a", 2L, 3.5, true, new LinkedHashMap<>(Map.of("k", "v")), List.of(List.of(1L)))));
        CompoundTag tag = NbtForm.toTag(form);
        Map<String, Object> back = NbtForm.toMap(tag);
        helper.assertTrue(back.equals(form), "the form changed on the way through NBT:\n" + form + "\n" + back);
        helper.assertTrue(back.get("v") instanceof Long && back.get("paused") instanceof Boolean && back.get("mixed") instanceof List<?>,
            "a value came back as another type: " + back);
        boolean refused = false;
        try {
            NbtForm.toTag(Map.of("bad", new Object()));
        } catch (IllegalArgumentException expected) {
            refused = true;
        }
        helper.assertTrue(refused, "a value outside the save form was written");
        helper.succeed();
    }

    // ---------------------------------------------------------------------------------------------------------
    // Talking to the bot
    // ---------------------------------------------------------------------------------------------------------

    /** Status names the goal and the step; pause and resume flip the goal's state; cancel ends it as CANCELLED. */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 200)
    public static void statusPauseResumeAndCancelThroughCommands(GameTestHelper helper) {
        floor(helper);
        Rig rig = Rig.spawnOwned(helper, "cmds", 200);
        rig.give(Items.OAK_LOG, 3);
        MinecraftServer server = rig.server();
        helper.assertTrue(run(server, rig.ownerSource(new Feedback()), "aiplayer goal " + rig.name() + " make 1 wooden pickaxe") == 1,
            "the goal command failed");
        int[] stage = {0};
        rig.onEachTick(() -> {
            if (stage[0] > 0 || rig.runtime().view().nodes().isEmpty()) return;
            stage[0] = 1;
            Feedback status = new Feedback();
            helper.assertTrue(run(server, rig.ownerSource(status), "aiplayer status " + rig.name()) == 1, "status failed: " + status.messages);
            helper.assertTrue(status.contains("get 1 wooden pickaxe") && status.contains("Step "),
                "status does not say which goal and which step: " + status.messages);

            Feedback pause = new Feedback();
            run(server, rig.ownerSource(pause), "aiplayer pause " + rig.name());
            helper.assertTrue(rig.runtime().view().status() == GoalStatus.PAUSED, "the goal is " + rig.runtime().view().status() + " after pause");
            run(server, rig.ownerSource(new Feedback()), "aiplayer resume " + rig.name());
            helper.assertTrue(rig.runtime().view().status() == GoalStatus.RUNNING, "the goal is " + rig.runtime().view().status() + " after resume");

            Feedback cancel = new Feedback();
            helper.assertTrue(run(server, rig.ownerSource(cancel), "aiplayer cancel " + rig.name()) == 1, "cancel failed: " + cancel.messages);
            GoalResult result = rig.runtime().lastResult();
            helper.assertTrue(result != null && result.status() == GoalResult.Status.CANCELLED, "the goal did not end CANCELLED: " + result);
            helper.assertTrue(cancel.contains("Cancelled"), "the cancel was not told to its source: " + cancel.messages);
            helper.assertTrue(!rig.runtime().hasGoal(), "a cancelled goal is still active");
            rig.assertHandsEmpty();

            Feedback idle = new Feedback();
            run(server, rig.ownerSource(idle), "aiplayer status " + rig.name());
            helper.assertTrue(idle.contains("Idle"), "an idle bot says " + idle.messages);

            Feedback stranger = new Feedback();
            helper.assertTrue(run(server, rig.strangerSource(stranger), "aiplayer goal " + rig.name() + " make 4 planks") == 0,
                "a stranger could give the bot a goal");
            helper.assertTrue(!rig.runtime().hasGoal() && !stranger.messages.isEmpty(), "the refusal said nothing or the goal was set");
            rig.pass();
        });
    }

    /**
     * The owner's chat line that starts with the bot's name is a goal, by the real chat path (the packet handler and the
     * loader's chat event); the same line from another player is not. Both speakers are bodies, which are players.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 200)
    public static void onlyTheOwnersChatSetsAGoal(GameTestHelper helper) {
        floor(helper);
        Rig owner = Rig.spawn(helper, "cown", null, 200);
        Rig stranger = Rig.spawn(helper, "coth", null, 200);
        Rig bot = Rig.spawn(helper, "cbot", owner.profile(), 200);
        owner.alongside(stranger);
        owner.alongside(bot);
        bot.give(Items.OAK_LOG, 1);
        int[] tick = {0};
        owner.onEachTick(() -> {
            int t = ++tick[0];
            if (t == 3) say(stranger.body(), bot.name() + " make 4 oak planks");
            if (t == 8) {
                helper.assertTrue(!bot.runtime().hasGoal() && bot.runtime().lastResult() == null, "a stranger's chat line set a goal");
                say(owner.body(), bot.name() + ", make 4 oak planks");
            }
            if (t > 8 && bot.runtime().lastResult() != null) {
                GoalResult result = bot.runtime().lastResult();
                helper.assertTrue(result.status() == GoalResult.Status.SATISFIED, "the goal ended " + BotSpeech.result(result));
                helper.assertTrue(bot.count(Items.OAK_PLANKS) == 4, "expected 4 planks, got " + bot.count(Items.OAK_PLANKS));
                owner.pass();
            }
        });
    }

    // ---------------------------------------------------------------------------------------------------------
    // Equipping
    // ---------------------------------------------------------------------------------------------------------

    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 200)
    public static void equipPutsAHelmetOnTheHead(GameTestHelper helper) {
        floor(helper);
        Rig rig = Rig.spawn(helper, "equip", null, 200);
        rig.give(Items.IRON_HELMET, 1);
        rig.runtime().submit(new Goal.Equip(Map.of(EquipSlot.HEAD, ItemKey.of("iron_helmet"))));
        rig.onEachTick(() -> {
            GoalResult result = rig.runtime().lastResult();
            if (result == null) return;
            helper.assertTrue(result.status() == GoalResult.Status.SATISFIED, "the goal ended " + BotSpeech.result(result));
            helper.assertTrue(rig.body().getItemBySlot(EquipmentSlot.HEAD).is(Items.IRON_HELMET), "the helmet is not on the head");
            helper.assertTrue(rig.carried(Items.IRON_HELMET) == 0, "the helmet is on the head and still in the inventory");
            rig.assertHandsEmpty();
            rig.pass();
        });
    }

    // ---------------------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------------------

    private static void floor(GameTestHelper helper) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) helper.setBlock(x, FLOOR_Y, z, Blocks.STONE);
        }
    }

    /** A chat line as the client sends it: through the packet handler, which fires the loader's chat event. */
    private static void say(BotPlayer speaker, String line) {
        speaker.connection.handleChat(new ServerboundChatPacket(line, Instant.now(), 0L, null, new LastSeenMessages.Update(0, new BitSet())));
    }

    private static CommandSourceStack source(MinecraftServer server, ServerLevel level, int permission, net.minecraft.world.entity.Entity entity,
                                             CommandSource sink) {
        return new CommandSourceStack(sink, Vec3.ZERO, Vec2.ZERO, level, permission, "gt", Component.literal("gt"), server, entity);
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

        boolean contains(String text) {
            return messages.stream().anyMatch(message -> message.contains(text));
        }

        @Override public void sendSystemMessage(Component message) { messages.add(message.getString()); }
        @Override public boolean acceptsSuccess() { return true; }
        @Override public boolean acceptsFailure() { return true; }
        @Override public boolean shouldInformAdmins() { return false; }
    }

    /**
     * One test body, spawned in the arena under a name unique to this run and found again by that name (a respawn
     * replaces the instance); despawned and forgotten when the test ends, passed or not.
     */
    private static final class Rig {
        private final GameTestHelper helper;
        private final BodyManager manager;
        private final String name;
        private final UUID id;
        private final GameProfile owner;
        private final ServerPlayer ownerPlayer;
        private final List<Rig> companions = new ArrayList<>();

        private Rig(GameTestHelper helper, String tag, GameProfile owner, ServerPlayer ownerPlayer) {
            this.helper = helper;
            this.manager = BodyManager.of(helper.getLevel().getServer());
            this.name = "gt_" + tag + "_" + RUN_ID;
            this.id = BodyManager.uuidFor(name);
            this.owner = owner;
            this.ownerPlayer = ownerPlayer;
        }

        /** A body with no owner: the bot's answers go to the log only. */
        static Rig spawn(GameTestHelper helper, String tag, GameProfile owner, int deadlineTicks) {
            Rig rig = new Rig(helper, tag, owner, null);
            rig.respawn(helper.absoluteVec(new Vec3(8.5, FLOOR_TOP, 8.5)));
            helper.runAtTickTime(deadlineTicks, rig::cleanup);
            return rig;
        }

        /** A body owned by a player who is not in the world, so a command can come from its owner. */
        static Rig spawnOwned(GameTestHelper helper, String tag, int deadlineTicks) {
            ServerLevel level = helper.getLevel();
            GameProfile profile = new GameProfile(UUID.randomUUID(), "owner_" + tag);
            ServerPlayer player = new ServerPlayer(level.getServer(), level, profile, ClientInformation.createDefault());
            Vec3 at = helper.absoluteVec(new Vec3(8.5, FLOOR_TOP, 8.5));
            player.setPos(at.x, at.y, at.z);
            Rig rig = new Rig(helper, tag, profile, player);
            rig.respawn(at);
            helper.runAtTickTime(deadlineTicks, rig::cleanup);
            return rig;
        }

        String name() {
            return name;
        }

        UUID id() {
            return id;
        }

        GameProfile profile() {
            return new GameProfile(id, name);
        }

        MinecraftServer server() {
            return helper.getLevel().getServer();
        }

        BodyManager manager() {
            return manager;
        }

        BotPlayer body() {
            BotPlayer current = manager.get(id);
            if (current == null) throw new IllegalStateException("no body " + name + " is logged in");
            return current;
        }

        BotRuntime runtime() {
            BotRuntime runtime = BotRuntime.of(body());
            if (runtime == null) throw new IllegalStateException(name + " has the brain " + body().brain() + ", not a runtime");
            return runtime;
        }

        BotPlayer respawn(Vec3 absolute) {
            try {
                return manager.spawn(name, owner, helper.getLevel(), absolute);
            } catch (BodyException e) {
                throw new IllegalStateException("could not spawn " + name + ": " + e.getMessage(), e);
            }
        }

        CommandSourceStack ownerSource(CommandSource sink) {
            return source(server(), helper.getLevel(), 0, ownerPlayer, sink);
        }

        CommandSourceStack strangerSource(CommandSource sink) {
            ServerLevel level = helper.getLevel();
            ServerPlayer stranger = new ServerPlayer(level.getServer(), level, new GameProfile(UUID.randomUUID(), "stranger"),
                ClientInformation.createDefault());
            return source(server(), level, 0, stranger, sink);
        }

        void give(Item item, int count) {
            body().getInventory().add(new ItemStack(item, count));
        }

        /** What the inventory's carried storage holds of the item (not what is worn). */
        int carried(Item item) {
            int total = 0;
            for (int slot = 0; slot < 36; slot++) {
                ItemStack stack = body().getInventory().getItem(slot);
                if (stack.is(item)) total += stack.getCount();
            }
            return total;
        }

        int count(Item item) {
            return body().getInventory().countItem(item);
        }

        /** The first stack of the item in the inventory, or an empty stack. */
        ItemStack firstStack(Item item) {
            for (int slot = 0; slot < body().getInventory().getContainerSize(); slot++) {
                ItemStack stack = body().getInventory().getItem(slot);
                if (stack.is(item)) return stack;
            }
            return ItemStack.EMPTY;
        }

        /** The cursor and every crafting grid slot of the menu the body has open are empty. */
        void assertHandsEmpty() {
            BotPlayer body = body();
            helper.assertTrue(body.containerMenu == body.inventoryMenu, "a menu is still open: " + body.containerMenu);
            helper.assertTrue(body.containerMenu.getCarried().isEmpty(), "the cursor holds " + body.containerMenu.getCarried());
            for (int slot = 1; slot <= 4; slot++) {
                helper.assertTrue(body.inventoryMenu.getSlot(slot).getItem().isEmpty(),
                    "the 2x2 grid slot " + slot + " holds " + body.inventoryMenu.getSlot(slot).getItem());
            }
        }

        /** Every crafting table within three blocks of the body (and one up or down), as absolute positions. */
        List<BlockPos> tablesNearBody() {
            BlockPos at = body().blockPosition();
            ServerLevel level = helper.getLevel();
            List<BlockPos> found = new ArrayList<>();
            for (BlockPos pos : BlockPos.betweenClosed(at.offset(-3, -1, -3), at.offset(3, 1, 3))) {
                if (level.getBlockState(pos).is(Blocks.CRAFTING_TABLE)) found.add(pos.immutable());
            }
            return found;
        }

        /** Every crafting table block in the arena, as relative positions. */
        List<BlockPos> tablesInArena() {
            List<BlockPos> found = new ArrayList<>();
            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        if (helper.getBlockState(new BlockPos(x, y, z)).is(Blocks.CRAFTING_TABLE)) found.add(new BlockPos(x, y, z));
                    }
                }
            }
            return found;
        }

        void onEachTick(Runnable action) {
            helper.onEachTick(() -> {
                try {
                    action.run();
                } catch (RuntimeException | Error e) {
                    cleanup();
                    throw e;
                }
            });
        }

        void alongside(Rig other) {
            companions.add(other);
        }

        void pass() {
            cleanup();
            helper.succeed();
        }

        void cleanup() {
            for (Rig companion : companions) companion.cleanup();
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
