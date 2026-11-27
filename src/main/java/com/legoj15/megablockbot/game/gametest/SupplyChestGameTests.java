package com.legoj15.megablockbot.game.gametest;

import static com.legoj15.megablockbot.game.gametest.BotNavGameTests.FLOOR_Y;
import static com.legoj15.megablockbot.game.gametest.BotNavGameTests.room;

import com.legoj15.megablockbot.MegaBlockBot;
import com.legoj15.megablockbot.core.ItemKey;
import com.legoj15.megablockbot.core.Pos;
import com.legoj15.megablockbot.core.exec.ExecutorView;
import com.legoj15.megablockbot.core.exec.GoalResult;
import com.legoj15.megablockbot.core.snapshot.ContainerSight;
import com.legoj15.megablockbot.core.snapshot.Snapshot;
import com.legoj15.megablockbot.game.body.BodyException;
import com.legoj15.megablockbot.game.body.BodyManager;
import com.legoj15.megablockbot.game.body.BotPlayer;
import com.legoj15.megablockbot.game.bot.BotRuntime;
import com.legoj15.megablockbot.game.bot.BotSpeech;
import com.legoj15.megablockbot.game.plan.BotMemory;
import com.legoj15.megablockbot.game.plan.SnapshotCollector;
import com.legoj15.megablockbot.game.safety.BlockSafety;
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
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

/**
 * The bot uses what its owner gave it and nothing else (design 3.5, supply containers), through the real runtime: the
 * owner's words reach a body as commands, the planner counts what a named chest holds, and the {@code withdraw} task
 * takes it out with the real menu. What these protect: a chest the owner named supplies a craft (and the rest is
 * gathered); a chest nobody named is never opened; a chest that was emptied meanwhile costs nothing and is not counted
 * again; a forgotten chest is left alone; a double chest is one inventory; only the owner may name one; a named chest may
 * be used and never broken. A task that opens a chest it was not given, takes more than it needs, or leaves a menu open
 * fails one of them.
 *
 * <p>Each test builds a walled room, puts a chest across it from the body, and gives its goals as the owner would:
 * {@code /aiplayer goal <name> use chest at ...}. Filling a chest and emptying it is test setup (the owner's, or another
 * player's, doing); the bot's own moves are what is measured. Bodies are named per run and removed when a test ends.
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

    /** Eight planks across the room, named by their owner: four sticks cost two of them, and the six left stay in. */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 600)
    public static void aChestItsOwnerNamedSuppliesACraft(GameTestHelper helper) {
        room(helper, 3);
        ChestBlockEntity chest = chestWith(helper, CHEST, Items.OAK_PLANKS, 8);
        Rig rig = Rig.spawnOwned(helper, "named", 600);
        rig.say("use chest at " + at(helper, CHEST));
        helper.assertTrue(BotMemory.of(rig.id()).isSupply(dimension(helper), helper.absolutePos(CHEST)), "the chest was not named");
        rig.say("make 4 stick");
        Watch watch = new Watch();
        rig.onEachTick(() -> {
            watch.look(rig);
            GoalResult result = rig.runtime().lastResult();
            if (result == null) return;
            helper.assertTrue(result.status() == GoalResult.Status.SATISFIED, "the goal ended " + BotSpeech.result(result));
            helper.assertTrue(rig.count(Items.STICK) == 4, "expected 4 sticks, got " + rig.count(Items.STICK));
            helper.assertTrue(holds(chest, Items.OAK_PLANKS) == 6, "the chest holds " + holds(chest, Items.OAK_PLANKS) + " planks, not 6");
            helper.assertTrue(rig.count(Items.OAK_PLANKS) == 0, "planks were taken that no craft used: " + rig.count(Items.OAK_PLANKS));
            helper.assertTrue(watch.sawWithdraw, "the plan never took the planks from the chest");
            BotMemory.RememberedContainer noted = BotMemory.of(rig.id()).containerAt(dimension(helper), helper.absolutePos(CHEST));
            helper.assertTrue(noted != null && noted.contents().getOrDefault(PLANKS, 0) == 6, "the body does not remember what it left in the chest: " + noted);
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
            GoalResult result = rig.runtime().lastResult();
            if (result == null) return;
            helper.assertTrue(result.status() == GoalResult.Status.SATISFIED, "the goal ended " + BotSpeech.result(result));
            helper.assertTrue(rig.count(Items.STICK) >= 8, "expected 8 sticks, got " + rig.count(Items.STICK));
            helper.assertTrue(holds(chest, Items.OAK_PLANKS) == 0, "the chest still holds " + holds(chest, Items.OAK_PLANKS) + " planks");
            helper.assertTrue(helper.getBlockState(LOG).isAir(), "the log was not gathered");
            helper.assertTrue(watch.sawWithdraw, "the plan never took the planks from the chest");
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
        helper.assertTrue(sight.mayTakeFrom() && sight.remembered() && sight.count(PLANKS) == 6, "the double chest is seen as " + sight);
        rig.say("make 12 stick");
        Watch watch = new Watch();
        rig.onEachTick(() -> {
            watch.look(rig);
            GoalResult result = rig.runtime().lastResult();
            if (result == null) return;
            helper.assertTrue(result.status() == GoalResult.Status.SATISFIED, "the goal ended " + BotSpeech.result(result));
            helper.assertTrue(rig.count(Items.STICK) == 12, "expected 12 sticks, got " + rig.count(Items.STICK));
            helper.assertTrue(holds(left, Items.OAK_PLANKS) == 0 && holds(right, Items.OAK_PLANKS) == 0,
                "the halves still hold " + holds(left, Items.OAK_PLANKS) + " and " + holds(right, Items.OAK_PLANKS) + " planks");
            rig.assertHandsEmpty();
            rig.pass();
        });
    }

    /**
     * Another player empties the chest after the plan is made and before the body is there. The body opens it, finds
     * nothing, takes nothing, and says so to the planner by what it remembers: the next plan does not count the chest.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 700)
    public static void aChestEmptiedAfterThePlanIsNotCountedAgain(GameTestHelper helper) {
        room(helper, 3);
        ChestBlockEntity chest = chestWith(helper, CHEST, Items.OAK_PLANKS, 8);
        Rig rig = Rig.spawnOwned(helper, "emptied", 700);
        rig.say("use chest at " + at(helper, CHEST));
        rig.say("make 4 stick");
        Watch watch = new Watch();
        boolean[] emptied = {false};
        rig.onEachTick(() -> {
            watch.look(rig);
            if (!emptied[0]) {
                if (!watch.sawWithdraw) return;
                chest.clearContent();
                emptied[0] = true;
                return;
            }
            helper.assertTrue(rig.count(Items.OAK_PLANKS) == 0 && rig.count(Items.STICK) == 0,
                "the body holds planks or sticks from an empty chest: " + rig.count(Items.OAK_PLANKS) + ", " + rig.count(Items.STICK));
            BotMemory.RememberedContainer noted = BotMemory.of(rig.id()).containerAt(dimension(helper), helper.absolutePos(CHEST));
            if (noted == null || noted.contents().getOrDefault(PLANKS, 0) != 0) return; // not opened yet
            // It opened the chest, found it empty and failed that leaf: the replan must be without the chest.
            GoalResult result = rig.runtime().lastResult();
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

    // ---------------------------------------------------------------------------------------------------------
    // The chest nobody named, and the one that was taken back
    // ---------------------------------------------------------------------------------------------------------

    /**
     * The same chest with the same planks, and no word from the owner. The body has been inside it before (it remembers
     * eight planks), which is not the same as being told to use it. In a sealed room the goal ends however its plan ends;
     * what is measured is the chest: not opened, not planned from, unchanged, and no planks on the body.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 400)
    public static void aChestNobodyNamedIsLeftAlone(GameTestHelper helper) {
        room(helper, 3);
        ChestBlockEntity chest = chestWith(helper, CHEST, Items.OAK_PLANKS, 8);
        Rig rig = Rig.spawnOwned(helper, "unnamed", 400);
        BotMemory.of(rig.id()).noteContainer(dimension(helper), helper.absolutePos(CHEST), "minecraft:chest", Map.of(PLANKS, 8));
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
     * The list is saved with the body: a single chest, a double chest (both halves) and one in another dimension come back
     * as they were; naming the other half of a double chest is the same chest and taking back either half takes it back;
     * a save from before the list existed loads as none.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 20)
    public static void theNamedChestsSurviveASave(GameTestHelper helper) {
        UUID body = UUID.randomUUID();
        UUID restoredBody = UUID.randomUUID();
        UUID oldBody = UUID.randomUUID();
        try {
            BotMemory memory = BotMemory.of(body);
            String overworld = dimension(helper);
            BlockPos single = helper.absolutePos(CHEST);
            BlockPos left = helper.absolutePos(new BlockPos(3, FLOOR_Y + 1, 5));
            BlockPos right = left.east();
            helper.assertTrue(memory.addSupply(new BotMemory.SupplyContainer(overworld, single, null)), "a chest named is not new");
            helper.assertTrue(memory.addSupply(new BotMemory.SupplyContainer(overworld, left, right)), "a double chest named is not new");
            helper.assertTrue(!memory.addSupply(new BotMemory.SupplyContainer(overworld, left, right)), "the same chest named twice is new");
            helper.assertTrue(memory.addSupply(new BotMemory.SupplyContainer("minecraft:the_nether", single, null)), "the nether chest is the overworld's");
            helper.assertTrue(memory.supply().size() == 3, "three chests were named: " + memory.supply());
            helper.assertTrue(memory.isSupply(overworld, right) && !memory.isSupply("minecraft:the_nether", right), "the right half, or the wrong dimension: " + memory.supply());

            CompoundTag saved = memory.save();
            BotMemory restored = BotMemory.restore(restoredBody, saved);
            helper.assertTrue(restored.supply().equals(memory.supply()), "the list changed in a save: " + restored.supply() + " against " + memory.supply());

            helper.assertTrue(restored.removeSupply(overworld, right) && restored.supplyAt(overworld, left) == null,
                "taking back one half did not take back the chest");
            helper.assertTrue(restored.supply().size() == 2, "taking back one chest took more: " + restored.supply());

            saved.remove("supply");
            helper.assertTrue(BotMemory.restore(oldBody, saved).supply().isEmpty(), "a save with no list loaded one");
        } finally {
            BotMemory.forget(body);
            BotMemory.forget(restoredBody);
            BotMemory.forget(oldBody);
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

    private static int holds(ChestBlockEntity chest, Item item) {
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

    /** What a test saw of the body over time: a menu open at any tick, a plan that asked a chest for something. */
    private static final class Watch {
        boolean sawMenu;
        boolean sawWithdraw;
        int ticks;

        void look(Rig rig) {
            ticks++;
            BotPlayer body = rig.body();
            if (body.containerMenu != body.inventoryMenu) sawMenu = true;
            for (ExecutorView.NodeView node : rig.runtime().view().nodes()) {
                if ("withdraw".equals(node.taskKind())) sawWithdraw = true;
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
         * Watches a body that has a goal it must not meet from this chest: for {@link #WATCH_TICKS} ticks, or until the goal
         * ends. The chest is never opened, never planned from and unchanged, and nothing from it is on the body. The body
         * remembers {@code noted} containers when it starts and the same ones, as they were, at the end: it opened none.
         */
        void leftAlone(ChestBlockEntity chest, Watch watch, int noted) {
            List<BotMemory.RememberedContainer> remembered = BotMemory.of(id).containers();
            helper.assertTrue(remembered.size() == noted, "setup: the body remembers " + remembered);
            int planksBefore = holds(chest, Items.OAK_PLANKS);
            onEachTick(() -> {
                watch.look(this);
                GoalResult result = runtime().lastResult();
                if (result == null && watch.ticks < WATCH_TICKS) return;
                helper.assertTrue(result == null || result.status() != GoalResult.Status.SATISFIED, "the goal was met without the chest");
                helper.assertTrue(!watch.sawWithdraw, "the plan counted on a chest nobody named");
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
