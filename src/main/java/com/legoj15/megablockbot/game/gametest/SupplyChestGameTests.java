package com.legoj15.megablockbot.game.gametest;

import static com.legoj15.megablockbot.game.gametest.BotNavGameTests.FLOOR_Y;
import static com.legoj15.megablockbot.game.gametest.BotNavGameTests.room;

import com.legoj15.megablockbot.MegaBlockBot;
import com.legoj15.megablockbot.core.ItemKey;
import com.legoj15.megablockbot.core.Pos;
import com.legoj15.megablockbot.core.exec.ExecutorView;
import com.legoj15.megablockbot.core.exec.GoalResult;
import com.legoj15.megablockbot.core.goal.Goal;
import com.legoj15.megablockbot.core.snapshot.ContainerSight;
import com.legoj15.megablockbot.core.snapshot.Snapshot;
import com.legoj15.megablockbot.core.task.Failure;
import com.legoj15.megablockbot.game.body.BodyException;
import com.legoj15.megablockbot.game.body.BodyManager;
import com.legoj15.megablockbot.game.body.BotPlayer;
import com.legoj15.megablockbot.game.bot.BotRuntime;
import com.legoj15.megablockbot.game.bot.BotSpeech;
import com.legoj15.megablockbot.game.plan.BotMemory;
import com.legoj15.megablockbot.game.plan.SnapshotCollector;
import com.legoj15.megablockbot.game.plan.SupplyChests;
import com.legoj15.megablockbot.game.safety.BlockSafety;
import com.legoj15.megablockbot.game.safety.ClaimProvider;
import com.legoj15.megablockbot.game.safety.Verdict;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

/**
 * The bot uses what its owner gave it and nothing else (design 3.5, supply containers), through the real runtime: the
 * owner's words reach a body as commands, the body goes and looks inside a chest it was told about (it cannot know what
 * is in a closed chest before that), the planner counts what it noted, and the {@code withdraw} task takes it out with
 * the real menu. What these protect: a chest the owner named supplies a craft once the body has looked in it (and the rest
 * is gathered), and nothing is known of it before; a chest nobody named is never opened; a chest that was emptied
 * meanwhile costs nothing and is not counted again; a chest that will not open, or was replaced by another block, is left
 * out and the goal gathers, whether the owner named it or the body placed it, and block safety agrees; a forgotten chest
 * is left alone; a double chest is one inventory, also when a lone chest became half of one and whichever half a goal
 * names; only the owner may name one; a named chest may be used and never broken; a stockpile goal fills its own chest
 * (the body looks inside it first, and the deposit notes what it put in) and is refused by a claim. A task that opens a
 * chest it was not given, takes more than it needs, counts an inventory twice or leaves a menu open fails one of them.
 *
 * <p>Each test builds a walled room, puts a chest across it from the body, and gives its goals as the owner would:
 * {@code /aiplayer goal <name> use chest at ...}. Filling a chest and emptying it is test setup (the owner's, or another
 * player's, doing); the bot's own moves are what is measured. Where a test changes the world under a plan, the body is
 * first called back across the room (after its look), so that the plan has a walk in it to change the world during.
 * Bodies are named per run and removed when a test ends.
 */
public final class SupplyChestGameTests {
    private static final String RUN_ID = Integer.toString((int) (System.nanoTime() & 0xFFFFF), 36);
    private static final double FLOOR_TOP = FLOOR_Y + 1.0;
    /** Across the room from the body, which stands at 8, 8: out of the eight blocks a snapshot looks around it (6 and 7 away). */
    private static final BlockPos CHEST = new BlockPos(14, FLOOR_Y + 1, 1);
    private static final BlockPos LOG = new BlockPos(4, FLOOR_Y + 1, 11);
    private static final ItemKey PLANKS = ItemKey.of("oak_planks");
    /** Ticks a test watches a bot that must leave a chest alone: a walk to it, and the withdraw, take less. */
    private static final int WATCH_TICKS = 150;

    private SupplyChestGameTests() {}

    // ---------------------------------------------------------------------------------------------------------
    // The chest the owner named
    // ---------------------------------------------------------------------------------------------------------

    /**
     * Eight planks across the room, named by their owner: four sticks cost two of them, and the six left stay in. The body
     * does not know what is in the chest because it was named (a player cannot see into a closed chest): it goes and looks
     * first, as its own goal, and plans the sticks knowing what is there.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 600)
    public static void aChestItsOwnerNamedSuppliesACraftOnceTheBodyHasLookedInside(GameTestHelper helper) {
        room(helper, 3);
        ChestBlockEntity chest = chestWith(helper, CHEST, Items.OAK_PLANKS, 8);
        Rig rig = Rig.spawnOwned(helper, "named", 600);
        rig.say("use chest at " + at(helper, CHEST));
        helper.assertTrue(BotMemory.of(rig.id()).isSupply(dimension(helper), helper.absolutePos(CHEST)), "the chest was not named");
        rig.assertNothingKnownOfTheInside(CHEST);
        rig.say("make 4 stick");
        Watch watch = new Watch();
        rig.onEachTick(() -> {
            watch.look(rig);
            GoalResult result = rig.itemResult();
            if (result == null) return;
            helper.assertTrue(result.status() == GoalResult.Status.SATISFIED, "the goal ended " + BotSpeech.result(result));
            helper.assertTrue(rig.count(Items.STICK) == 4, "expected 4 sticks, got " + rig.count(Items.STICK));
            helper.assertTrue(holds(chest, Items.OAK_PLANKS) == 6, "the chest holds " + holds(chest, Items.OAK_PLANKS) + " planks, not 6");
            helper.assertTrue(rig.count(Items.OAK_PLANKS) == 0, "planks were taken that no craft used: " + rig.count(Items.OAK_PLANKS));
            helper.assertTrue(watch.kinds.contains("look_inside"), "the body never went to look inside the chest: " + watch.kinds);
            helper.assertTrue(watch.kinds.contains("withdraw"), "the plan never took the planks from the chest: " + watch.kinds);
            helper.assertTrue(watch.kinds.indexOf("look_inside") < watch.kinds.indexOf("withdraw"), "it planned from the chest before it looked: " + watch.kinds);
            BotMemory.RememberedContainer noted = BotMemory.of(rig.id()).containerAt(dimension(helper), helper.absolutePos(CHEST));
            helper.assertTrue(noted != null && noted.contents().getOrDefault(PLANKS, 0) == 6, "the body does not remember what it left in the chest: " + noted);
            ContainerSight sight = SnapshotCollector.collect(rig.body()).containerAt(corePos(helper.absolutePos(CHEST)));
            helper.assertTrue(sight != null && sight.remembered() && sight.count(PLANKS) == 6, "what the body noted is not shown as remembered: " + sight);
            rig.assertHandsEmpty();
            rig.pass();
        });
    }

    /**
     * Three planks are in the chest and four are needed (eight sticks): the bot takes what is there and mines the log that
     * stands in the room for the rest.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 1500)
    public static void whatTheChestHoldsIsTakenAndTheRestIsGathered(GameTestHelper helper) {
        room(helper, 3);
        helper.setBlock(LOG, Blocks.OAK_LOG);
        ChestBlockEntity chest = chestWith(helper, CHEST, Items.OAK_PLANKS, 3);
        Rig rig = Rig.spawnOwned(helper, "partial", 1500);
        // The mine tests' arena rules are global: without this the log is outside every arena they know and stays.
        rig.atEnd(MoveAndMineGameTests.allowBreaking(helper));
        rig.say("use chest at " + at(helper, CHEST));
        rig.say("make 8 stick");
        Watch watch = new Watch();
        rig.onEachTick(() -> {
            watch.look(rig);
            GoalResult result = rig.itemResult();
            if (result == null) return;
            helper.assertTrue(result.status() == GoalResult.Status.SATISFIED, "the goal ended " + BotSpeech.result(result));
            helper.assertTrue(rig.count(Items.STICK) >= 8, "expected 8 sticks, got " + rig.count(Items.STICK));
            helper.assertTrue(holds(chest, Items.OAK_PLANKS) == 0, "the chest still holds " + holds(chest, Items.OAK_PLANKS) + " planks");
            helper.assertTrue(helper.getBlockState(LOG).isAir(), "the log was not gathered");
            helper.assertTrue(watch.kinds.contains("withdraw"), "the plan never took the planks from the chest");
            rig.assertHandsEmpty();
            rig.pass();
        });
    }

    /**
     * A chest is a double chest: two block entities, one inventory, named by either half. Three planks are in each half and
     * twelve sticks cost six: with the two halves counted as one the goal is met and both are emptied.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 700)
    public static void aDoubleChestNamedByOneHalfIsOneInventory(GameTestHelper helper) {
        room(helper, 3);
        BlockPos leftHalf = new BlockPos(13, FLOOR_Y + 1, 1);
        BlockPos rightHalf = new BlockPos(14, FLOOR_Y + 1, 1);
        helper.setBlock(leftHalf, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH).setValue(ChestBlock.TYPE, ChestType.LEFT));
        helper.setBlock(rightHalf, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH).setValue(ChestBlock.TYPE, ChestType.RIGHT));
        ChestBlockEntity left = chest(helper, leftHalf);
        ChestBlockEntity right = chest(helper, rightHalf);
        left.setItem(0, new ItemStack(Items.OAK_PLANKS, 3));
        right.setItem(0, new ItemStack(Items.OAK_PLANKS, 3));
        Rig rig = Rig.spawnOwned(helper, "double", 700);
        rig.say("use chest at " + at(helper, rightHalf));
        BotMemory memory = BotMemory.of(rig.id());
        String dimension = dimension(helper);
        helper.assertTrue(memory.supply().size() == 1 && memory.isSupply(dimension, helper.absolutePos(leftHalf))
            && memory.isSupply(dimension, helper.absolutePos(rightHalf)), "naming one half did not name both: " + memory.supply());
        Snapshot snapshot = SnapshotCollector.collect(rig.body());
        helper.assertTrue(snapshot.containers().size() == 1, "a double chest is one container in a snapshot: " + snapshot.containers());
        ContainerSight sight = snapshot.containers().get(0);
        helper.assertTrue(sight.mayTakeFrom() && !sight.remembered() && sight.contents().isEmpty(), "naming the double chest told the body what is in it: " + sight);
        rig.say("make 12 stick");
        Watch watch = new Watch();
        rig.onEachTick(() -> {
            watch.look(rig);
            GoalResult result = rig.itemResult();
            if (result == null) return;
            helper.assertTrue(result.status() == GoalResult.Status.SATISFIED, "the goal ended " + BotSpeech.result(result));
            helper.assertTrue(rig.count(Items.STICK) == 12, "expected 12 sticks, got " + rig.count(Items.STICK));
            helper.assertTrue(holds(left, Items.OAK_PLANKS) == 0 && holds(right, Items.OAK_PLANKS) == 0,
                "the halves still hold " + holds(left, Items.OAK_PLANKS) + " and " + holds(right, Items.OAK_PLANKS) + " planks");
            helper.assertTrue(SnapshotCollector.collect(rig.body()).containers().size() == 1, "the double chest is more than one container now");
            rig.assertHandsEmpty();
            rig.pass();
        });
    }

    /**
     * Another player empties the chest after the plan is made and before the body is there. The body opens it, finds
     * nothing, takes nothing, and says so to the planner by what it remembers: the next plan does not count the chest.
     * (The body looks inside first, at the chest, and is then called back across the room, so that the plan that follows
     * has a walk in it for the chest to change under.)
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 700)
    public static void aChestEmptiedAfterThePlanIsNotCountedAgain(GameTestHelper helper) {
        room(helper, 3);
        ChestBlockEntity chest = chestWith(helper, CHEST, Items.OAK_PLANKS, 8);
        Rig rig = Rig.spawnOwned(helper, "emptied", 700);
        rig.say("use chest at " + at(helper, CHEST));
        Watch watch = new Watch();
        int[] step = {0};
        rig.onEachTick(() -> {
            watch.look(rig);
            if (step[0] == 0) {
                if (!rig.hasLookedInside(CHEST)) return;
                rig.sendBack();
                rig.say("make 4 stick");
                step[0] = 1;
                return;
            }
            if (step[0] == 1) {
                if (!watch.kinds.contains("withdraw")) return;
                chest.clearContent();
                step[0] = 2;
                return;
            }
            helper.assertTrue(rig.count(Items.OAK_PLANKS) == 0 && rig.count(Items.STICK) == 0,
                "the body holds planks or sticks from an empty chest: " + rig.count(Items.OAK_PLANKS) + ", " + rig.count(Items.STICK));
            BotMemory.RememberedContainer noted = BotMemory.of(rig.id()).containerAt(dimension(helper), helper.absolutePos(CHEST));
            if (noted == null || noted.contents().getOrDefault(PLANKS, 0) != 0) return; // not opened yet
            // It opened the chest, found it empty and failed that leaf: the replan must be without the chest.
            GoalResult result = rig.itemResult();
            ExecutorView view = rig.runtime().view();
            boolean stillWithdrawing = view.nodes().stream().anyMatch(node -> "withdraw".equals(node.taskKind()));
            if (result == null && (view.planPending() || stillWithdrawing || view.totalReplans() == 0)) return;
            helper.assertTrue(result == null || result.status() != GoalResult.Status.SATISFIED, "the goal was met from an empty chest");
            Snapshot next = SnapshotCollector.collect(rig.body());
            ContainerSight sight = next.containerAt(new Pos(helper.absolutePos(CHEST).getX(), helper.absolutePos(CHEST).getY(), helper.absolutePos(CHEST).getZ()));
            helper.assertTrue(sight != null && sight.count(PLANKS) == 0, "the next snapshot still counts planks in the chest: " + sight);
            helper.assertTrue(holds(chest, Items.OAK_PLANKS) == 0, "the chest was refilled by the body?");
            rig.assertHandsEmpty();
            rig.pass();
        });
    }

    /**
     * A chest the owner named, and the body looked inside, that cannot be opened any more (something comes to rest on it)
     * must not sink the goal: the take that found it shut rules that chest out of the next plan, which gathers instead.
     * The log that stands in the room is the way; the chest, with planks and logs in it, is left exactly as it was.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 1500)
    public static void aNamedChestThatWillNotOpenIsLeftOutOfThePlanAndTheGoalGathersInstead(GameTestHelper helper) {
        room(helper, 3);
        helper.setBlock(LOG, Blocks.OAK_LOG);
        ChestBlockEntity chest = chestWith(helper, CHEST, Items.OAK_PLANKS, 8);
        chest.setItem(1, new ItemStack(Items.OAK_LOG, 4));
        Rig rig = Rig.spawnOwned(helper, "blocked", 1500);
        rig.atEnd(MoveAndMineGameTests.allowBreaking(helper));
        rig.say("use chest at " + at(helper, CHEST));
        Watch watch = new Watch();
        boolean[] asked = {false};
        rig.onEachTick(() -> {
            watch.look(rig);
            if (!asked[0]) {
                // The body looks inside first; only when it knows what is in the chest does something come to sit on it.
                if (!rig.hasLookedInside(CHEST)) return;
                helper.setBlock(CHEST.above(), Blocks.STONE);
                watch.sawMenu = false;
                rig.say("make 4 stick");
                asked[0] = true;
                return;
            }
            GoalResult result = rig.itemResult();
            if (result == null) return;
            helper.assertTrue(result.status() == GoalResult.Status.SATISFIED, "the goal ended " + BotSpeech.result(result));
            helper.assertTrue(rig.count(Items.STICK) == 4, "expected 4 sticks, got " + rig.count(Items.STICK));
            helper.assertTrue(watch.kinds.contains("withdraw"), "setup: the plan never counted on the chest: " + watch.kinds);
            helper.assertTrue(!watch.sawMenu, "a menu opened on a chest with a block on it");
            helper.assertTrue(helper.getBlockState(LOG).isAir(), "the log was not gathered");
            helper.assertTrue(holds(chest, Items.OAK_PLANKS) == 8 && holds(chest, Items.OAK_LOG) == 4,
                "the shut chest changed: " + holds(chest, Items.OAK_PLANKS) + " planks and " + holds(chest, Items.OAK_LOG) + " logs");
            rig.assertHandsEmpty();
            rig.pass();
        });
    }

    /**
     * A chest that is shut when the owner names it costs the body its look and nothing more: the look cannot open it and
     * is given up on, the chest is never counted (nothing was ever noted of it), and the next goal gathers.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 1500)
    public static void aChestThatIsShutWhenNamedCostsTheBodyOnlyItsLook(GameTestHelper helper) {
        room(helper, 3);
        helper.setBlock(LOG, Blocks.OAK_LOG);
        ChestBlockEntity chest = chestWith(helper, CHEST, Items.OAK_PLANKS, 8);
        helper.setBlock(CHEST.above(), Blocks.STONE);
        Rig rig = Rig.spawnOwned(helper, "shut", 1500);
        rig.atEnd(MoveAndMineGameTests.allowBreaking(helper));
        rig.say("use chest at " + at(helper, CHEST));
        rig.say("make 4 stick");
        Watch watch = new Watch();
        rig.onEachTick(() -> {
            watch.look(rig);
            GoalResult result = rig.itemResult();
            if (result == null) return;
            helper.assertTrue(result.status() == GoalResult.Status.SATISFIED, "the goal ended " + BotSpeech.result(result));
            helper.assertTrue(rig.count(Items.STICK) == 4, "expected 4 sticks, got " + rig.count(Items.STICK));
            helper.assertTrue(watch.kinds.contains("look_inside"), "the body never tried to look inside: " + watch.kinds);
            helper.assertTrue(!watch.kinds.contains("withdraw"), "the plan counted on a chest nothing was known of: " + watch.kinds);
            helper.assertTrue(!watch.sawMenu, "a menu opened on a chest with a block on it");
            helper.assertTrue(BotMemory.of(rig.id()).containerAt(dimension(helper), helper.absolutePos(CHEST)) == null, "the body noted the inside of a chest it could not open");
            helper.assertTrue(helper.getBlockState(LOG).isAir(), "the log was not gathered");
            helper.assertTrue(holds(chest, Items.OAK_PLANKS) == 8, "the chest changed: " + holds(chest, Items.OAK_PLANKS) + " planks");
            rig.assertHandsEmpty();
            rig.pass();
        });
    }

    /**
     * The chest is replaced by a barrel with the same planks in it between the plan and the body's arrival. The owner's
     * word was for the chest: the barrel is not opened, the word is gone, and so is what the body noted of the chest.
     * (The body looks inside first, at the chest, and is then called back across the room, so that the plan that follows
     * has a walk in it for the chest to be replaced under.)
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 700)
    public static void aNamedChestReplacedByAnotherBlockBeforeTheBodyArrivesIsNotOpened(GameTestHelper helper) {
        room(helper, 3);
        ChestBlockEntity chest = chestWith(helper, CHEST, Items.OAK_PLANKS, 8);
        Rig rig = Rig.spawnOwned(helper, "swapped", 700);
        rig.say("use chest at " + at(helper, CHEST));
        Watch watch = new Watch();
        BarrelBlockEntity[] barrel = {null};
        int[] swappedAt = {0};
        boolean[] asked = {false};
        rig.onEachTick(() -> {
            watch.look(rig);
            if (!asked[0]) {
                if (!rig.hasLookedInside(CHEST)) return;
                rig.sendBack();
                rig.say("make 4 stick");
                asked[0] = true;
                return;
            }
            if (barrel[0] == null) {
                if (!watch.kinds.contains("withdraw")) return;
                // The plan counts the chest. Before the body is there, somebody puts a barrel in its place, with planks in it.
                // (The chest is emptied first: a chest that is broken drops what it holds, and the body would pick those up.)
                chest.clearContent();
                helper.setBlock(CHEST, Blocks.BARREL);
                barrel[0] = (BarrelBlockEntity) helper.getBlockEntity(CHEST);
                barrel[0].setItem(0, new ItemStack(Items.OAK_PLANKS, 8));
                watch.sawMenu = false;
                swappedAt[0] = watch.ticks;
                return;
            }
            GoalResult result = rig.itemResult();
            if (result == null && watch.ticks - swappedAt[0] < WATCH_TICKS) return;
            helper.assertTrue(!watch.sawMenu, "the barrel that replaced the chest was opened");
            helper.assertTrue(holds(barrel[0], Items.OAK_PLANKS) == 8, "the barrel holds " + holds(barrel[0], Items.OAK_PLANKS) + " planks, it held 8");
            helper.assertTrue(rig.count(Items.OAK_PLANKS) == 0 && rig.count(Items.STICK) == 0,
                "the body took " + rig.count(Items.OAK_PLANKS) + " planks and made " + rig.count(Items.STICK) + " sticks from a barrel");
            helper.assertTrue(result == null || result.status() != GoalResult.Status.SATISFIED, "the goal was met from the barrel");
            BotMemory memory = BotMemory.of(rig.id());
            helper.assertTrue(memory.supply().isEmpty(), "the owner's word for the chest outlived it: " + memory.supply());
            helper.assertTrue(memory.containerAt(dimension(helper), helper.absolutePos(CHEST)) == null, "the body still remembers the chest: " + memory.containers());
            rig.assertHandsEmpty();
            rig.pass();
        });
    }

    /**
     * One inventory is counted once: a chest named when it was single, joined to a second half and named again is one
     * container in the snapshot, with what the pair holds, and a plan takes from it by that count. The half that was named
     * first is the one with the higher position hash, so the pair is stood for by the half that was added.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 900)
    public static void aChestThatBecameHalfOfADoubleChestIsOneInventoryWhenNamedAgain(GameTestHelper helper) {
        room(helper, 3);
        BlockPos first = CHEST;
        boolean partnerWest = helper.absolutePos(first.west()).asLong() < helper.absolutePos(first).asLong();
        BlockPos partner = partnerWest ? first.west() : first.east();
        ChestBlockEntity single = chestWith(helper, first, Items.OAK_PLANKS, 3);
        Rig rig = Rig.spawnOwned(helper, "joined", 900);
        rig.say("use chest at " + at(helper, first));
        int[] step = {0};
        ChestBlockEntity[] added = {null};
        rig.onEachTick(() -> {
            BotMemory memory = BotMemory.of(rig.id());
            BlockPos firstAbs = helper.absolutePos(first);
            if (step[0] == 0) {
                // The body has looked at the lone chest; then a second chest is set beside it and the owner names it again.
                if (!rig.hasLookedInside(first)) return;
                helper.assertTrue(memory.containerAt(dimension(helper), firstAbs).contents().getOrDefault(PLANKS, 0) == 3, "setup: the lone chest was not seen with 3 planks");
                helper.setBlock(first, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH)
                    .setValue(ChestBlock.TYPE, partnerWest ? ChestType.RIGHT : ChestType.LEFT));
                helper.setBlock(partner, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH)
                    .setValue(ChestBlock.TYPE, partnerWest ? ChestType.LEFT : ChestType.RIGHT));
                added[0] = chest(helper, partner);
                added[0].setItem(0, new ItemStack(Items.OAK_PLANKS, 3));
                rig.say("use chest at " + at(helper, first));
                step[0] = 1;
                return;
            }
            if (step[0] == 1) {
                if (!rig.hasLookedInside(partner)) return;
                Snapshot snapshot = SnapshotCollector.collect(rig.body());
                helper.assertTrue(snapshot.containers().size() == 1, "a pair named twice is one container in a snapshot: " + snapshot.containers());
                ContainerSight sight = snapshot.containers().get(0);
                helper.assertTrue(sight.mayTakeFrom() && sight.remembered() && sight.count(PLANKS) == 6, "the pair is seen as " + sight);
                helper.assertTrue(memory.containers().size() == 1, "the body remembers the pair once: " + memory.containers());
                rig.say("make 12 stick");
                step[0] = 2;
                return;
            }
            GoalResult result = rig.itemResult();
            if (result == null) return;
            helper.assertTrue(result.status() == GoalResult.Status.SATISFIED, "the goal ended " + BotSpeech.result(result));
            helper.assertTrue(rig.count(Items.STICK) == 12, "expected 12 sticks, got " + rig.count(Items.STICK));
            helper.assertTrue(holds(single, Items.OAK_PLANKS) == 0 && holds(added[0], Items.OAK_PLANKS) == 0,
                "the halves still hold " + holds(single, Items.OAK_PLANKS) + " and " + holds(added[0], Items.OAK_PLANKS) + " planks");
            rig.assertHandsEmpty();
            rig.pass();
        });
    }

    // ---------------------------------------------------------------------------------------------------------
    // Putting into the goal's own chest asks block safety too
    // ---------------------------------------------------------------------------------------------------------

    /**
     * A stockpile goal names its chest, and that is the owner's word for it: the body puts into a chest it did not place
     * and nobody named as a supply, one it has never been inside (so it knows nothing of what is there beforehand). The
     * deposit notes what the chest holds when it is done, so that the goal can be judged and what was put in is
     * remembered.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 600)
    public static void aStockpileGoalsOwnChestIsFilledWithoutBeingNamed(GameTestHelper helper) {
        room(helper, 3);
        helper.setBlock(CHEST, Blocks.CHEST);
        ChestBlockEntity chest = chest(helper, CHEST);
        Rig rig = Rig.spawnOwned(helper, "deposit", 600);
        rig.body().getInventory().add(new ItemStack(Items.OAK_PLANKS, 5));
        rig.say("stockpile 5 oak_planks at " + at(helper, CHEST));
        rig.onEachTick(() -> {
            GoalResult result = rig.itemResult();
            if (result == null) return;
            helper.assertTrue(result.status() == GoalResult.Status.SATISFIED, "the goal ended " + BotSpeech.result(result));
            helper.assertTrue(holds(chest, Items.OAK_PLANKS) == 5, "the chest holds " + holds(chest, Items.OAK_PLANKS) + " planks, not 5");
            helper.assertTrue(rig.count(Items.OAK_PLANKS) == 0, "the body still holds " + rig.count(Items.OAK_PLANKS) + " planks");
            BotMemory memory = BotMemory.of(rig.id());
            helper.assertTrue(memory.supply().isEmpty(), "the goal's chest was made a supply container");
            BotMemory.RememberedContainer noted = memory.containerAt(dimension(helper), helper.absolutePos(CHEST));
            helper.assertTrue(noted != null && noted.contents().getOrDefault(PLANKS, 0) == 5, "the body does not remember what it put in the chest: " + noted);
            rig.assertHandsEmpty();
            rig.pass();
        });
    }

    /**
     * The owner types the other half of a double chest than the one the body has a note of: it is the same inventory, and
     * the goal is met through the pair's one note. (The sight is kept at one half, the one with the lower position hash;
     * a goal that names the other must not be "no sight", which is UNKNOWN for ever and never deposits.)
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 900)
    public static void aStockpileGoalThatNamesTheOtherHalfOfADoubleChestFillsTheOneInventory(GameTestHelper helper) {
        room(helper, 3);
        BlockPos leftHalf = new BlockPos(13, FLOOR_Y + 1, 1);
        BlockPos rightHalf = new BlockPos(14, FLOOR_Y + 1, 1);
        helper.setBlock(leftHalf, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH).setValue(ChestBlock.TYPE, ChestType.LEFT));
        helper.setBlock(rightHalf, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH).setValue(ChestBlock.TYPE, ChestType.RIGHT));
        ChestBlockEntity left = chest(helper, leftHalf);
        ChestBlockEntity right = chest(helper, rightHalf);
        boolean leftIsLow = helper.absolutePos(leftHalf).asLong() < helper.absolutePos(rightHalf).asLong();
        BlockPos low = leftIsLow ? leftHalf : rightHalf;
        BlockPos high = leftIsLow ? rightHalf : leftHalf;
        Rig rig = Rig.spawnOwned(helper, "farhalf", 900);
        rig.body().getInventory().add(new ItemStack(Items.OAK_PLANKS, 5));
        rig.say("use chest at " + at(helper, low));
        boolean[] asked = {false};
        rig.onEachTick(() -> {
            if (!asked[0]) {
                if (!rig.hasLookedInside(low)) return;
                rig.say("stockpile 5 oak_planks at " + at(helper, high));
                asked[0] = true;
                return;
            }
            GoalResult result = rig.itemResult();
            if (result == null) return;
            helper.assertTrue(result.status() == GoalResult.Status.SATISFIED, "the goal ended " + BotSpeech.result(result));
            helper.assertTrue(holds(left, Items.OAK_PLANKS) + holds(right, Items.OAK_PLANKS) == 5,
                "the halves hold " + holds(left, Items.OAK_PLANKS) + " and " + holds(right, Items.OAK_PLANKS) + " planks, not 5 in all");
            helper.assertTrue(rig.count(Items.OAK_PLANKS) == 0, "the body still holds " + rig.count(Items.OAK_PLANKS) + " planks");
            BotMemory memory = BotMemory.of(rig.id());
            helper.assertTrue(memory.containers().size() == 1, "the body remembers the pair once: " + memory.containers());
            helper.assertTrue(memory.containers().get(0).contents().getOrDefault(PLANKS, 0) == 5, "the note is " + memory.containers());
            rig.assertHandsEmpty();
            rig.pass();
        });
    }

    /** The same goal for a chest inside a claim: block safety is asked before the lid is touched, so nothing goes in. */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 900)
    public static void aStockpileIntoAClaimedChestIsRefusedBySafety(GameTestHelper helper) {
        room(helper, 3);
        helper.setBlock(CHEST, Blocks.CHEST);
        ChestBlockEntity chest = chest(helper, CHEST);
        BlockPos chestAt = helper.absolutePos(CHEST);
        ClaimProvider claim = (level, pos, actingFor) -> pos.equals(chestAt);
        BlockSafety.registerClaimProvider(claim);
        Rig rig = Rig.spawnOwned(helper, "claimed", 900);
        rig.atEnd(() -> BlockSafety.unregisterClaimProvider(claim));
        rig.body().getInventory().add(new ItemStack(Items.OAK_PLANKS, 5));
        rig.say("stockpile 5 oak_planks at " + at(helper, CHEST));
        Watch watch = new Watch();
        rig.onEachTick(() -> {
            watch.look(rig);
            GoalResult result = rig.itemResult();
            if (result == null && holds(chest, Items.OAK_PLANKS) == 0) return;
            helper.assertTrue(holds(chest, Items.OAK_PLANKS) == 0, "the chest in a claim was filled: " + holds(chest, Items.OAK_PLANKS));
            helper.assertTrue(result != null && result.failure() instanceof Failure.Protected,
                "the goal ended " + (result == null ? "with no result" : BotSpeech.result(result)));
            helper.assertTrue(rig.count(Items.OAK_PLANKS) == 5, "the body holds " + rig.count(Items.OAK_PLANKS) + " planks, it held 5");
            helper.assertTrue(!watch.sawMenu, "a menu was opened on a claimed chest");
            helper.assertTrue(BotMemory.of(rig.id()).containers().isEmpty(), "the body noted the inside of a claimed chest");
            rig.assertHandsEmpty();
            rig.pass();
        });
    }

    // ---------------------------------------------------------------------------------------------------------
    // The chest nobody named, and the one that was taken back
    // ---------------------------------------------------------------------------------------------------------

    /**
     * A chest with planks in it, and no word from the owner. The body has been inside it before (it remembers eight planks),
     * which is not the same as being told to use it. The chest stands within the eight blocks a snapshot looks around the
     * body, so the body sees it and what it noted of it: what keeps it out is the owner's word, not the distance. In a
     * sealed room the goal ends however its plan ends; what is measured is the chest: not opened, not planned from,
     * unchanged, and no planks on the body.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 400)
    public static void aChestNobodyNamedIsLeftAlone(GameTestHelper helper) {
        room(helper, 3);
        BlockPos near = new BlockPos(11, FLOOR_Y + 1, 5);
        ChestBlockEntity chest = chestWith(helper, near, Items.OAK_PLANKS, 8);
        Rig rig = Rig.spawnOwned(helper, "unnamed", 400);
        BotMemory.of(rig.id()).noteContainer(dimension(helper), helper.absolutePos(near), "minecraft:chest", Map.of(PLANKS, 8));
        ContainerSight sight = SnapshotCollector.collect(rig.body()).containerAt(corePos(helper.absolutePos(near)));
        helper.assertTrue(sight != null && sight.contentsKnown() && sight.count(PLANKS) == 8 && !sight.mayTakeFrom(),
            "setup: the chest is not in the body's sight as one it may not take from: " + sight);
        rig.say("make 4 stick");
        rig.leftAlone(chest, new Watch(), 1);
    }

    /** Named and then taken back: the next goal does not open it. */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 400)
    public static void aChestTheOwnerForgotIsLeftAlone(GameTestHelper helper) {
        room(helper, 3);
        ChestBlockEntity chest = chestWith(helper, CHEST, Items.OAK_PLANKS, 8);
        Rig rig = Rig.spawnOwned(helper, "forgot", 400);
        rig.say("use chest at " + at(helper, CHEST));
        helper.assertTrue(BotMemory.of(rig.id()).isSupply(dimension(helper), helper.absolutePos(CHEST)), "the chest was not named");
        // Naming it again is not a second chest; taking it back by either position is one removal.
        rig.say("use chest at " + at(helper, CHEST));
        helper.assertTrue(BotMemory.of(rig.id()).supply().size() == 1, "one chest named twice is " + BotMemory.of(rig.id()).supply());
        rig.say("forget chest at " + at(helper, CHEST));
        helper.assertTrue(BotMemory.of(rig.id()).supply().isEmpty(), "the chest is still named: " + BotMemory.of(rig.id()).supply());
        helper.assertTrue(BotMemory.of(rig.id()).containers().isEmpty(), "the body still remembers what was in it");
        rig.say("make 4 stick");
        rig.leftAlone(chest, new Watch(), 0);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Who may name a chest, and what naming one allows
    // ---------------------------------------------------------------------------------------------------------

    /** The owner and the console may name a chest; anyone else is refused, as for a goal, and the list stays empty. */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 100)
    public static void onlyTheOwnerOrTheConsoleCanNameAChest(GameTestHelper helper) {
        room(helper, 3);
        chestWith(helper, CHEST, Items.OAK_PLANKS, 8);
        Rig rig = Rig.spawnOwned(helper, "who", 100);
        BotMemory memory = BotMemory.of(rig.id());
        String line = "aiplayer goal " + rig.name() + " use chest at " + at(helper, CHEST);
        Feedback stranger = new Feedback();
        helper.assertTrue(run(rig.server(), rig.strangerSource(stranger), line) == 0, "a stranger could name a chest: " + stranger.messages);
        helper.assertTrue(memory.supply().isEmpty(), "a stranger's word was kept: " + memory.supply());
        Feedback console = new Feedback();
        helper.assertTrue(run(rig.server(), source(rig.server(), helper.getLevel(), 2, null, console), line) == 1, "the console was refused: " + console.messages);
        helper.assertTrue(memory.isSupply(dimension(helper), helper.absolutePos(CHEST)), "the console's word was not kept");
        Feedback owner = new Feedback();
        run(rig.server(), rig.ownerSource(owner), "aiplayer goal " + rig.name() + " forget chest at " + at(helper, CHEST));
        helper.assertTrue(memory.supply().isEmpty(), "the owner could not take the chest back");
        rig.pass();
    }

    /**
     * Using a container the owner named is allowed, inside the protected radius too (using is not breaking, and the radius
     * only ever stopped breaking); breaking one is not, named or not. A chest nobody named is refused for use.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 100)
    @SuppressWarnings("removal") // GameTestHelper marks the mock player for removal; the replacement does not put it in the list
    public static void aNamedChestIsUsableEvenInTheProtectedRadiusAndNeverBreakable(GameTestHelper helper) {
        room(helper, 3);
        ServerLevel level = helper.getLevel();
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        BlockPos home = helper.absolutePos(new BlockPos(8, FLOOR_Y + 1, 8));
        owner.setRespawnPosition(Level.OVERWORLD, home, 0.0F, true, false);
        Rig rig = Rig.spawnWithOwner(helper, "radius", owner.getGameProfile(), 100);
        rig.atEnd(() -> {
            if (level.getServer().getPlayerList().getPlayers().contains(owner)) level.getServer().getPlayerList().remove(owner);
        });
        BlockPos chestPos = helper.absolutePos(CHEST);
        helper.setBlock(CHEST, Blocks.CHEST);
        BlockSafety safety = BlockSafety.of(level.getServer());
        BotPlayer body = rig.body();

        Verdict unnamed = safety.mayUseContainer(body, level, chestPos);
        helper.assertTrue(!unnamed.allowed() && unnamed.rule().equals("container"), "a chest nobody named may be used: " + unnamed);
        rig.say("use chest at " + at(helper, CHEST));
        // Within a few blocks of the owner's respawn point: the protected radius.
        helper.assertTrue(Math.abs(chestPos.getX() - home.getX()) <= 16 && Math.abs(chestPos.getZ() - home.getZ()) <= 16, "setup: the chest is outside the radius");
        Verdict inside = safety.mayUseContainer(body, level, chestPos);
        helper.assertTrue(inside.allowed(), "a named chest inside the protected radius may not be used: " + inside);
        Verdict broken = safety.mayBreak(body, level, chestPos, level.getBlockState(chestPos));
        helper.assertTrue(!broken.allowed() && broken.rule().equals("protected_radius"), "a named chest inside the radius may be broken: " + broken);

        // The respawn point moves away: no radius, and the container rule alone keeps the chest standing.
        owner.setRespawnPosition(Level.OVERWORLD, home.offset(-40, 0, 0), 0.0F, true, false);
        Verdict outside = safety.mayUseContainer(body, level, chestPos);
        helper.assertTrue(outside.allowed(), "a named chest outside the radius may not be used: " + outside);
        Verdict stillBroken = safety.mayBreak(body, level, chestPos, level.getBlockState(chestPos));
        helper.assertTrue(!stillBroken.allowed() && stillBroken.rule().equals("container"), "a named chest outside the radius may be broken: " + stillBroken);

        // Taken back, it is refused again.
        rig.say("forget chest at " + at(helper, CHEST));
        Verdict forgotten = safety.mayUseContainer(body, level, chestPos);
        helper.assertTrue(!forgotten.allowed(), "a chest the owner took back may be used: " + forgotten);
        rig.pass();
    }

    /**
     * The owner's word is for the block it named. A barrel, a furnace or another chest set where a named chest stood is
     * nobody's word, and block safety refuses to let the body use it, as the tasks do when they compare the record with
     * what stands there (a barrel is also a job site, which the word would otherwise lift). A double chest that lost a half
     * is the same: the word was for the pair.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 100)
    public static void theOwnersWordForAChestIsNoWordForAnotherBlockThatReplacedIt(GameTestHelper helper) {
        room(helper, 3);
        ServerLevel level = helper.getLevel();
        Rig rig = Rig.spawnOwned(helper, "stale", 100);
        BotPlayer body = rig.body();
        BlockSafety safety = BlockSafety.of(level.getServer());
        BotMemory memory = BotMemory.of(rig.id());
        String dimension = dimension(helper);

        BlockPos at = helper.absolutePos(CHEST);
        helper.setBlock(CHEST, Blocks.CHEST);
        memory.addSupply(new BotMemory.SupplyContainer(dimension, at, null, "minecraft:chest"));
        helper.assertTrue(safety.mayUseContainer(body, level, at).allowed(), "setup: a chest the owner named may not be used");
        for (net.minecraft.world.level.block.Block replacement : List.of(Blocks.BARREL, Blocks.TRAPPED_CHEST, Blocks.FURNACE)) {
            helper.setBlock(CHEST, replacement);
            Verdict verdict = safety.mayUseContainer(body, level, at);
            helper.assertTrue(!verdict.allowed(), "a " + replacement + " where the owner named a chest may be used: " + verdict);
        }
        helper.setBlock(CHEST, Blocks.CHEST);
        helper.assertTrue(safety.mayUseContainer(body, level, at).allowed(), "the chest, put back, is the owner's word again");

        // A pair named as a pair: with one half gone, the half that stands is not what was named.
        BlockPos leftHalf = new BlockPos(7, FLOOR_Y + 1, 3);
        BlockPos rightHalf = new BlockPos(8, FLOOR_Y + 1, 3);
        helper.setBlock(leftHalf, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH).setValue(ChestBlock.TYPE, ChestType.LEFT));
        helper.setBlock(rightHalf, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH).setValue(ChestBlock.TYPE, ChestType.RIGHT));
        SupplyChests.Spot spot = SupplyChests.find(level, helper.absolutePos(leftHalf));
        helper.assertTrue(spot != null && spot.other() != null, "setup: the two chests are not one inventory: " + spot);
        memory.addSupply(new BotMemory.SupplyContainer(dimension, spot.pos(), spot.other(), spot.block()));
        helper.assertTrue(safety.mayUseContainer(body, level, helper.absolutePos(leftHalf)).allowed()
            && safety.mayUseContainer(body, level, helper.absolutePos(rightHalf)).allowed(), "setup: the named pair may not be used");
        helper.setBlock(rightHalf, Blocks.DIRT);
        Verdict lone = safety.mayUseContainer(body, level, helper.absolutePos(leftHalf));
        helper.assertTrue(!lone.allowed(), "the half that stands, of a pair that lost the other, may be used: " + lone);
        rig.pass();
    }

    /**
     * A container the body placed itself, and has been inside, is its own only while it stands as it was: another container
     * put in its place (with the same planks in it) is not opened, the body's note of the chest and its claim on the spot go,
     * and the goal does not take from it. (The body has the chest in its plan, and the barrel is put there before it arrives.)
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 700)
    public static void aChestTheBodyPlacedThatIsAnotherContainerNowIsNotOpened(GameTestHelper helper) {
        room(helper, 3);
        ChestBlockEntity chest = chestWith(helper, CHEST, Items.OAK_PLANKS, 8);
        Rig rig = Rig.spawnOwned(helper, "placed", 700);
        BlockPos at = helper.absolutePos(CHEST);
        // As if the body had set the chest down and filled it: its placement is on record with block safety and in its memory.
        BlockSafety.of(rig.server()).noteBotPlaced(rig.body(), helper.getLevel(), at);
        BotMemory memory = BotMemory.of(rig.id());
        memory.notePlaced(dimension(helper), at);
        memory.noteContainer(dimension(helper), at, "minecraft:chest", Map.of(PLANKS, 8));
        rig.say("make 4 stick");
        Watch watch = new Watch();
        BarrelBlockEntity[] barrel = {null};
        int[] swappedAt = {0};
        rig.onEachTick(() -> {
            watch.look(rig);
            if (barrel[0] == null) {
                if (!watch.kinds.contains("withdraw")) return;
                // The chest is emptied first: a chest that is broken drops what it holds, and the body would pick those up.
                chest.clearContent();
                helper.setBlock(CHEST, Blocks.BARREL);
                barrel[0] = (BarrelBlockEntity) helper.getBlockEntity(CHEST);
                barrel[0].setItem(0, new ItemStack(Items.OAK_PLANKS, 8));
                watch.sawMenu = false;
                swappedAt[0] = watch.ticks;
                return;
            }
            GoalResult result = rig.itemResult();
            if (result == null && watch.ticks - swappedAt[0] < WATCH_TICKS) return;
            helper.assertTrue(!watch.sawMenu, "the barrel that replaced the chest was opened");
            helper.assertTrue(holds(barrel[0], Items.OAK_PLANKS) == 8, "the barrel holds " + holds(barrel[0], Items.OAK_PLANKS) + " planks, it held 8");
            helper.assertTrue(rig.count(Items.OAK_PLANKS) == 0 && rig.count(Items.STICK) == 0,
                "the body took " + rig.count(Items.OAK_PLANKS) + " planks and made " + rig.count(Items.STICK) + " sticks from a barrel");
            helper.assertTrue(result == null || result.status() != GoalResult.Status.SATISFIED, "the goal was met from the barrel");
            helper.assertTrue(memory.containerAt(dimension(helper), at) == null, "the body still remembers the chest: " + memory.containers());
            helper.assertTrue(!memory.isPlaced(dimension(helper), at), "the body still counts the spot as a block it placed");
            rig.assertHandsEmpty();
            rig.pass();
        });
    }

    /**
     * What a task may conclude about a named container from the world: it stands as named, it is another block now (the
     * owner's word was for something else: gone), or the position is not loaded, which is "cannot tell" and never gone.
     * (The task's answer to the last is a failure the executor meets by going there, and it drops nothing. A body that is
     * at a container while its chunk is not loaded cannot be set up, so the decision itself is what is measured.)
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 20)
    public static void aNamedContainerIsGoneOnlyWhenTheWorldSaysAnotherBlockStandsThere(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        String dimension = dimension(helper);
        BlockPos at = helper.absolutePos(CHEST);
        helper.setBlock(CHEST, Blocks.CHEST);
        BotMemory.SupplyContainer single = new BotMemory.SupplyContainer(dimension, at, null, "minecraft:chest");
        helper.assertTrue(SupplyChests.stand(level, at, single) == SupplyChests.Stand.STANDS, "a chest named as a chest does not stand");
        helper.assertTrue(SupplyChests.stand(level, at, null) == SupplyChests.Stand.STANDS, "a container nobody named, loaded, does not stand");

        helper.setBlock(CHEST, Blocks.BARREL);
        helper.assertTrue(SupplyChests.stand(level, at, single) == SupplyChests.Stand.CHANGED, "a barrel where a chest was named is the chest");

        // Both halves of a double chest are the word's: the second one gone is another block there.
        BlockPos next = at.east();
        helper.setBlock(CHEST, Blocks.CHEST);
        helper.setBlock(CHEST.east(), Blocks.CHEST);
        BotMemory.SupplyContainer pair = new BotMemory.SupplyContainer(dimension, at, next, "minecraft:chest");
        helper.assertTrue(SupplyChests.stand(level, at, pair) == SupplyChests.Stand.STANDS, "a pair named as a pair does not stand");
        helper.setBlock(CHEST.east(), Blocks.DIRT);
        helper.assertTrue(SupplyChests.stand(level, at, pair) == SupplyChests.Stand.CHANGED, "a pair that lost a half still stands");

        BlockPos far = at.offset(1_000_000, 0, 0);
        helper.assertTrue(!level.isLoaded(far), "setup: the far position is loaded");
        helper.assertTrue(SupplyChests.stand(level, far, new BotMemory.SupplyContainer(dimension, far, null, "minecraft:chest")) == SupplyChests.Stand.NOT_LOADED,
            "a position that is not loaded was called something other than not loaded");
        helper.assertTrue(SupplyChests.stand(level, far, null) == SupplyChests.Stand.NOT_LOADED, "a position nobody named, not loaded, was called loaded");
        helper.assertTrue(SupplyChests.stand(level, at, new BotMemory.SupplyContainer(dimension, at, far, "minecraft:chest")) == SupplyChests.Stand.NOT_LOADED,
            "a pair with a half that is not loaded was called gone");

        // The same rule for what the body holds of a container nobody named: its own note is of a block too, and the owner's
        // word and the note are each held to theirs.
        UUID id = UUID.randomUUID();
        BotMemory memory = BotMemory.of(id);
        try {
            helper.setBlock(CHEST, Blocks.CHEST);
            helper.setBlock(CHEST.east(), Blocks.AIR);
            helper.assertTrue(SupplyChests.stand(level, memory, at) == SupplyChests.Stand.STANDS, "a position nothing is recorded for does not stand");
            memory.notePlaced(dimension, at);
            memory.noteContainer(dimension, at, "minecraft:chest", Map.of());
            helper.assertTrue(SupplyChests.stand(level, memory, at) == SupplyChests.Stand.STANDS, "a noted chest, still there, does not stand");
            helper.setBlock(CHEST, Blocks.BARREL);
            helper.assertTrue(SupplyChests.stand(level, memory, at) == SupplyChests.Stand.CHANGED, "a barrel where a chest was noted stands");
            helper.assertTrue(!SupplyChests.forget(memory, dimension, at), "there was no owner's word to drop");
            helper.assertTrue(memory.containerAt(dimension, at) == null && !memory.isPlaced(dimension, at),
                "the note or the claim on the spot outlived the chest: " + memory.containers());

            helper.setBlock(CHEST, Blocks.CHEST);
            memory.addSupply(single);
            memory.noteContainer(dimension, at, "minecraft:chest", Map.of());
            helper.assertTrue(SupplyChests.stand(level, memory, at) == SupplyChests.Stand.STANDS, "a named and noted chest does not stand");
            helper.assertTrue(SupplyChests.forget(memory, dimension, at) && memory.supply().isEmpty() && memory.containers().isEmpty(),
                "forgetting did not drop the word and the note: " + memory);
            helper.assertTrue(SupplyChests.stand(level, memory, far) == SupplyChests.Stand.NOT_LOADED, "a position that is not loaded was called something else");
        } finally {
            BotMemory.forget(id);
        }
        helper.succeed();
    }

    // ---------------------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------------------

    private static String dimension(GameTestHelper helper) {
        return helper.getLevel().dimension().location().toString();
    }

    /** The position as the owner would type it: absolute coordinates, space separated. */
    private static String at(GameTestHelper helper, BlockPos relative) {
        BlockPos pos = helper.absolutePos(relative);
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    private static ChestBlockEntity chest(GameTestHelper helper, BlockPos relative) {
        BlockEntity entity = helper.getBlockEntity(relative);
        if (!(entity instanceof ChestBlockEntity chest)) throw new IllegalStateException("no chest at " + relative + ": " + entity);
        return chest;
    }

    private static ChestBlockEntity chestWith(GameTestHelper helper, BlockPos relative, Item item, int count) {
        helper.setBlock(relative, Blocks.CHEST);
        ChestBlockEntity chest = chest(helper, relative);
        chest.setItem(0, new ItemStack(item, count));
        return chest;
    }

    private static Pos corePos(BlockPos pos) {
        return new Pos(pos.getX(), pos.getY(), pos.getZ());
    }

    private static int holds(Container chest, Item item) {
        int total = 0;
        for (int slot = 0; slot < chest.getContainerSize(); slot++) {
            ItemStack stack = chest.getItem(slot);
            if (stack.is(item)) total += stack.getCount();
        }
        return total;
    }

    private static CommandSourceStack source(MinecraftServer server, ServerLevel level, int permission, Entity entity, CommandSource sink) {
        return new CommandSourceStack(sink, Vec3.ZERO, Vec2.ZERO, level, permission, "gt", Component.literal("gt"), server, entity);
    }

    private static int run(MinecraftServer server, CommandSourceStack source, String line) {
        try {
            return server.getCommands().getDispatcher().execute(line, source);
        } catch (CommandSyntaxException e) {
            throw new IllegalStateException("'" + line + "' did not parse: " + e.getMessage(), e);
        }
    }

    /** What a command source is told. */
    private static final class Feedback implements CommandSource {
        final List<String> messages = new ArrayList<>();

        @Override public void sendSystemMessage(Component message) { messages.add(message.getString()); }
        @Override public boolean acceptsSuccess() { return true; }
        @Override public boolean acceptsFailure() { return true; }
        @Override public boolean shouldInformAdmins() { return false; }
    }

    /** What a test saw of the body over time: a menu open at any tick, and the task kinds its plans have had, in order. */
    private static final class Watch {
        boolean sawMenu;
        /** Every leaf task kind the executor has shown, each once, in the order it first appeared. */
        final List<String> kinds = new ArrayList<>();
        int ticks;

        void look(Rig rig) {
            ticks++;
            BotPlayer body = rig.body();
            if (body.containerMenu != body.inventoryMenu) sawMenu = true;
            for (ExecutorView.NodeView node : rig.runtime().view().nodes()) {
                if (node.taskKind() != null && !kinds.contains(node.taskKind())) kinds.add(node.taskKind());
            }
        }
    }

    /**
     * One test body with an owner who gives its orders as commands, spawned in the room under a name unique to this run,
     * despawned and forgotten (its memory too) when the test ends, passed or not.
     */
    private static final class Rig {
        private final GameTestHelper helper;
        private final BodyManager manager;
        private final String name;
        private final UUID id;
        private final GameProfile owner;
        private final ServerPlayer ownerPlayer;
        private final List<Runnable> atEnd = new ArrayList<>();
        private boolean over;

        private Rig(GameTestHelper helper, String tag, GameProfile owner, ServerPlayer ownerPlayer) {
            this.helper = helper;
            this.manager = BodyManager.of(helper.getLevel().getServer());
            this.name = "gs_" + tag + "_" + RUN_ID;
            this.id = BodyManager.uuidFor(name);
            this.owner = owner;
            this.ownerPlayer = ownerPlayer;
        }

        /** A body owned by a player who is not in the world, so a command can come from its owner. */
        static Rig spawnOwned(GameTestHelper helper, String tag, int deadlineTicks) {
            ServerLevel level = helper.getLevel();
            GameProfile profile = new GameProfile(UUID.randomUUID(), "owner_" + tag);
            ServerPlayer player = new ServerPlayer(level.getServer(), level, profile, ClientInformation.createDefault());
            return start(new Rig(helper, tag, profile, player), deadlineTicks);
        }

        /** A body owned by a player the test made (a mock player in the world), whose commands come from that player. */
        static Rig spawnWithOwner(GameTestHelper helper, String tag, GameProfile owner, int deadlineTicks) {
            ServerPlayer online = helper.getLevel().getServer().getPlayerList().getPlayer(owner.getId());
            return start(new Rig(helper, tag, owner, online), deadlineTicks);
        }

        private static Rig start(Rig rig, int deadlineTicks) {
            Vec3 at = rig.helper.absoluteVec(new Vec3(8.5, FLOOR_TOP, 8.5));
            if (rig.ownerPlayer != null) rig.ownerPlayer.setPos(at.x, at.y, at.z);
            try {
                rig.manager.spawn(rig.name, rig.owner, rig.helper.getLevel(), at);
            } catch (BodyException e) {
                throw new IllegalStateException("could not spawn " + rig.name + ": " + e.getMessage(), e);
            }
            rig.helper.runAtTickTime(deadlineTicks, rig::cleanup);
            return rig;
        }

        String name() {
            return name;
        }

        UUID id() {
            return id;
        }

        MinecraftServer server() {
            return helper.getLevel().getServer();
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

        CommandSourceStack ownerSource(CommandSource sink) {
            return source(server(), helper.getLevel(), 0, ownerPlayer, sink);
        }

        CommandSourceStack strangerSource(CommandSource sink) {
            ServerLevel level = helper.getLevel();
            ServerPlayer stranger = new ServerPlayer(level.getServer(), level, new GameProfile(UUID.randomUUID(), "stranger"),
                ClientInformation.createDefault());
            return source(server(), level, 0, stranger, sink);
        }

        /** The owner says it to the body, by the command path a player's chat line takes into the same code. */
        void say(String words) {
            Feedback feedback = new Feedback();
            helper.assertTrue(run(server(), ownerSource(feedback), "aiplayer goal " + name + " " + words) == 1,
                "'" + words + "' was refused: " + feedback.messages);
        }

        int count(Item item) {
            return body().getInventory().countItem(item);
        }

        /** No menu is open, and the cursor and the crafting grid are empty. */
        void assertHandsEmpty() {
            BotPlayer body = body();
            helper.assertTrue(body.containerMenu == body.inventoryMenu, "a menu is still open: " + body.containerMenu);
            helper.assertTrue(body.containerMenu.getCarried().isEmpty(), "the cursor holds " + body.containerMenu.getCarried());
            for (int slot = 1; slot <= 4; slot++) {
                helper.assertTrue(body.inventoryMenu.getSlot(slot).getItem().isEmpty(),
                    "the 2x2 grid slot " + slot + " holds " + body.inventoryMenu.getSlot(slot).getItem());
            }
        }

        /**
         * Watches a body that has a goal it must not meet from this chest: for {@link #WATCH_TICKS} ticks, or until the
         * goal ends. The chest is never opened, never planned from and unchanged, and nothing from it is on the body. The
         * body remembers {@code noted} containers when it starts and the same ones, as they were, at the end: it opened
         * none.
         */
        void leftAlone(ChestBlockEntity chest, Watch watch, int noted) {
            List<BotMemory.RememberedContainer> remembered = BotMemory.of(id).containers();
            helper.assertTrue(remembered.size() == noted, "setup: the body remembers " + remembered);
            int planksBefore = holds(chest, Items.OAK_PLANKS);
            onEachTick(() -> {
                watch.look(this);
                GoalResult result = itemResult();
                if (result == null && watch.ticks < WATCH_TICKS) return;
                helper.assertTrue(result == null || result.status() != GoalResult.Status.SATISFIED, "the goal was met without the chest");
                helper.assertTrue(!watch.kinds.contains("withdraw"), "the plan counted on a chest nobody named: " + watch.kinds);
                helper.assertTrue(!watch.sawMenu, "a menu was opened: the chest was used");
                helper.assertTrue(holds(chest, Items.OAK_PLANKS) == planksBefore, "the chest holds " + holds(chest, Items.OAK_PLANKS) + " planks, it held " + planksBefore);
                helper.assertTrue(count(Items.OAK_PLANKS) == 0 && count(Items.STICK) == 0, "the body holds planks or sticks it had no way to make");
                BotMemory memory = BotMemory.of(id);
                helper.assertTrue(memory.supply().isEmpty(), "the body was given a chest: " + memory.supply());
                helper.assertTrue(memory.containers().equals(remembered), "what the body remembers of chests changed: " + memory.containers());
                assertHandsEmpty();
                pass();
            });
        }

        /**
         * The last goal's result when that goal asked for an item or a stockpile: what the owner's naming a chest queues
         * first (the body's look inside) ends before it, and is not what a test waits for.
         */
        GoalResult itemResult() {
            GoalResult last = runtime().lastResult();
            return last != null && (last.goal() instanceof Goal.HaveItem || last.goal() instanceof Goal.Stockpile) ? last : null;
        }

        /** The body has looked inside the container at {@code relative} (it noted it), and has nothing else to do. */
        boolean hasLookedInside(BlockPos relative) {
            return !runtime().hasGoal() && BotMemory.of(id).containerAt(dimension(helper), helper.absolutePos(relative)) != null;
        }

        /** The body is back where it started, across the room from the chest: the owner called it away. */
        void sendBack() {
            Vec3 at = helper.absoluteVec(new Vec3(8.5, FLOOR_TOP, 8.5));
            body().teleportTo(helper.getLevel(), at.x, at.y, at.z, 0.0F, 0.0F);
        }

        /** Right after the owner named the chest at {@code relative}: the body has not been inside it, so it knows nothing of it. */
        void assertNothingKnownOfTheInside(BlockPos relative) {
            BlockPos at = helper.absolutePos(relative);
            ContainerSight sight = SnapshotCollector.collect(body()).containerAt(corePos(at));
            helper.assertTrue(sight != null && sight.mayTakeFrom() && !sight.remembered() && !sight.contentsKnown() && sight.contents().isEmpty(),
                "naming a chest told the body what is in it: " + sight);
            helper.assertTrue(BotMemory.of(id).containerAt(dimension(helper), at) == null, "the body noted the inside of a chest it has not opened");
        }

        void atEnd(Runnable undo) {
            atEnd.add(undo);
        }

        void onEachTick(Runnable action) {
            helper.onEachTick(() -> {
                if (over) return;
                try {
                    action.run();
                } catch (RuntimeException | Error e) {
                    cleanup();
                    throw e;
                }
            });
        }

        void pass() {
            cleanup();
            helper.succeed();
        }

        void cleanup() {
            if (over) return;
            over = true;
            atEnd.forEach(Runnable::run);
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
