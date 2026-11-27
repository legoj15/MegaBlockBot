package com.legoj15.megablockbot.game.gametest;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.trading.ItemCost;
import com.legoj15.megablockbot.MegaBlockBot;
import com.legoj15.megablockbot.core.ItemKey;
import com.legoj15.megablockbot.core.goal.ComponentCheck;
import com.legoj15.megablockbot.core.goal.Goal;
import com.legoj15.megablockbot.core.plan.NodeSpec;
import com.legoj15.megablockbot.core.task.Failure;
import com.legoj15.megablockbot.core.task.Task;
import com.legoj15.megablockbot.core.task.TaskState;
import com.legoj15.megablockbot.core.task.TickResult;
import com.legoj15.megablockbot.game.body.BodyException;
import com.legoj15.megablockbot.game.body.BodyManager;
import com.legoj15.megablockbot.game.body.BotPlayer;
import com.legoj15.megablockbot.game.plan.BotMemory;
import com.legoj15.megablockbot.game.plan.ItemKeys;
import com.legoj15.megablockbot.game.task.TradeTask;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.EnchantedBookItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentInstance;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The trade task against a running server (issue #15): a villager trades through its window, and a librarian can be
 * rerolled from nothing. What these protect: that a {@code trade} leaf ends with the item, paid for through the trade
 * window; that a price the body cannot pay is a {@code MissingInput} naming what is missing and what the body noted;
 * that rerolling puts the job site down and takes it up again until the offer is there.
 *
 * <p>Each test spawns its own body (a name unique to this run) in a closed room, so the villager stays where the test
 * put it. The villager's profession, level and offers are set by the test before the task starts (set-up, said at each
 * use, not the bot's doing); the trade itself is what is measured. Bodies, villagers and any lectern the task put down
 * are cleaned up when the test ends, passed or not, so no test's 48-block look sees an earlier one's leftovers.
 */
public final class TradeGameTests {
    private static final int FLOOR_Y = 1;
    private static final double FLOOR_TOP = FLOOR_Y + 1.0;
    private static final String RUN_ID = Integer.toString((int) (System.nanoTime() & 0xFFFFF), 36);
    /** One trade takes a walk and a few clicks; enough for the slowest of these tests. */
    private static final int TRADE_TICKS = 400;
    /** A reroll takes about 600 ticks (the villager's own job-site scan) plus the walks; several rolls fit in this. */
    private static final int REROLL_TICKS = 3200;
    /**
     * The NoSource bound, sized from the search the test asks for: two explore rounds of 20 ticks each is 40, and the
     * rest is room for the looks between the rounds and the task's start ({@code TradeTask.Search(2, 20)}).
     */
    private static final int NONE_TICKS = 200;

    private TradeGameTests() {}

    // ---------------------------------------------------------------------------------------------------------
    // The tests
    // ---------------------------------------------------------------------------------------------------------

    /**
     * A librarian (set-up: profession, level and this one offer, a book enchanted with mending for 10 emeralds and a
     * book) in a closed room; the body holds 10 emeralds and a book. The {@code trade} leaf completes, the body holds
     * a book that carries mending and no emeralds, and the villager's offer has been used once.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = TRADE_TICKS, batch = "trade_buysamendingbookthrought")
    public static void buysAMendingBookThroughTheWindow(GameTestHelper helper) {
        room(helper);
        Rig rig = Rig.spawn(helper, "mending", TRADE_TICKS);
        Holder<Enchantment> mending = helper.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT)
            .getOrThrow(Enchantments.MENDING);
        ItemStack book = EnchantedBookItem.createForEnchantment(new EnchantmentInstance(mending, 1));
        Villager librarian = rig.villager(VillagerProfession.LIBRARIAN, offer(Items.EMERALD, 10, Items.BOOK, 1, book));
        rig.give(Items.EMERALD, 10);
        rig.give(Items.BOOK, 1);
        TaskRun run = new TaskRun(tradeTask(new ComponentCheck(Map.of("minecraft:mending", 1), ""),
                Items.ENCHANTED_BOOK));
        rig.onEachTick(() -> {
            if (!run.tick(rig.body())) return;
            helper.assertTrue(run.result instanceof TickResult.Completed, "the trade ended " + run.result);
            helper.assertTrue(rig.count(Items.ENCHANTED_BOOK) == 1 && carriesMending(rig), "the body holds no mending book");
            helper.assertTrue(rig.count(Items.EMERALD) == 0, "the emeralds were not spent: " + rig.count(Items.EMERALD));
            helper.assertTrue(librarian.getOffers().get(0).getUses() == 1,
                "the offer has been used " + librarian.getOffers().get(0).getUses() + " times");
            rig.assertWindowClosed();
            rig.pass();
        });
    }

    /**
     * The same librarian, but the body holds only 4 emeralds (set-up): the leaf ends {@code MissingInput} naming the
     * emeralds and 10 of them, and the body's memory holds the noted offer.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = TRADE_TICKS, batch = "trade_apricethebodycannotpayis")
    public static void aPriceTheBodyCannotPayIsAMissingInputAndANote(GameTestHelper helper) {
        room(helper);
        Rig rig = Rig.spawn(helper, "dear", TRADE_TICKS);
        Holder<Enchantment> mending = helper.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT)
            .getOrThrow(Enchantments.MENDING);
        ItemStack book = EnchantedBookItem.createForEnchantment(new EnchantmentInstance(mending, 1));
        Villager librarian = rig.villager(VillagerProfession.LIBRARIAN, offer(Items.EMERALD, 10, Items.BOOK, 1, book));
        rig.give(Items.EMERALD, 4);
        rig.give(Items.BOOK, 1);
        TaskRun run = new TaskRun(tradeTask(new ComponentCheck(Map.of("minecraft:mending", 1), ""),
                Items.ENCHANTED_BOOK));
        rig.onEachTick(() -> {
            if (!run.tick(rig.body())) return;
            helper.assertTrue(run.failure() instanceof Failure.MissingInput missing
                    && missing.key().id().equals("minecraft:emerald") && missing.count() == 10,
                "the trade ended " + run.result);
            helper.assertTrue(rig.count(Items.EMERALD) == 4, "something was spent: " + rig.count(Items.EMERALD));
            boolean noted = false;
            for (BotMemory.VillagerOffers offers : BotMemory.of(rig.id()).notedOffers()) {
                if (offers.villager().equals(librarian.getUUID()) && !offers.offers().isEmpty()) noted = true;
            }
            helper.assertTrue(noted, "the body noted no offer of the librarian it opened");
            rig.assertWindowClosed();
            rig.pass();
        });
    }

    /**
     * A librarian whose offers do not include the item (set-up: it sells only paper) and a body with no lectern,
     * asked for a bookshelf: the leaf ends {@code MissingInput} naming the lectern, the job-site item that would make
     * a librarian offer it.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = TRADE_TICKS, batch = "trade_nobodyoffersitandnojobsi")
    public static void nobodyOffersItAndNoJobSiteIsCarried(GameTestHelper helper) {
        room(helper);
        Rig rig = Rig.spawn(helper, "nojob", TRADE_TICKS);
        rig.villager(VillagerProfession.LIBRARIAN, offer(Items.EMERALD, 2, null, 0, new ItemStack(Items.PAPER)));
        rig.give(Items.EMERALD, 9);
        TaskRun run = new TaskRun(tradeTask(null, Items.BOOKSHELF));
        rig.onEachTick(() -> {
            if (!run.tick(rig.body())) return;
            helper.assertTrue(run.failure() instanceof Failure.MissingInput missing
                    && missing.key().id().equals("minecraft:lectern"), "the trade ended " + run.result);
            rig.pass();
        });
    }

    /**
     * An unemployed villager (set-up: no profession, no offers) and a body holding a lectern and enough emeralds,
     * asked for a bookshelf: a novice librarian offers it in two rolls of three, so eight rolls fail once in several
     * thousand runs. The task ends with a bookshelf, having put the lectern down at least once; the lectern stands
     * (the villager has traded) and is remembered as the body's.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = REROLL_TICKS, batch = "trade_rerollsanunemployedvilla")
    public static void rerollsAnUnemployedVillagerUntilItOffersTheItem(GameTestHelper helper) {
        room(helper);
        Rig rig = Rig.spawn(helper, "reroll", REROLL_TICKS);
        rig.villager(null, null);
        rig.give(Items.LECTERN, 1);
        rig.give(Items.EMERALD, 12);
        TaskRun run = new TaskRun(tradeTask(null, Items.BOOKSHELF));
        boolean[] placed = {false};
        rig.onEachTick(() -> {
            placed[0] |= !lecternsIn(helper).isEmpty();
            if (!run.tick(rig.body())) return;
            helper.assertTrue(run.result instanceof TickResult.Completed, "the trade ended " + run.result);
            helper.assertTrue(rig.count(Items.BOOKSHELF) == 1, "the body holds no bookshelf");
            helper.assertTrue(placed[0], "the task never put a lectern down");
            List<BlockPos> lecterns = lecternsIn(helper);
            helper.assertTrue(lecterns.size() == 1, "expected the lectern to stand, found " + lecterns);
            if (!lecterns.isEmpty()) {
                helper.assertTrue(BotMemory.of(rig.id()).isPlaced(helper.getLevel().dimension().location().toString(),
                    helper.absolutePos(lecterns.get(0))), "the standing lectern is not remembered as the body's");
            }
            rig.assertWindowClosed();
            rig.pass();
        });
    }

    /**
     * The same unemployed villager and lectern, but the body holds no emeralds (set-up): the rolls go on until the
     * villager offers the bookshelf, and the leaf then ends {@code MissingInput} naming the emeralds — with the
     * lectern still standing and remembered. The offer is what the rolls were for, and a villager that has not traded
     * loses it with its job site: taking the lectern up here would throw the offer away before the plan could pay.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = REROLL_TICKS, batch = "trade_arolledofferkeepsitssite")
    public static void aRolledVillagerThatOffersTheItemKeepsItsJobSiteWhenTheBodyCannotPay(GameTestHelper helper) {
        room(helper);
        Rig rig = Rig.spawn(helper, "keeps", REROLL_TICKS);
        Villager villager = rig.villager(null, null);
        rig.give(Items.LECTERN, 1);
        TaskRun run = new TaskRun(tradeTask(null, Items.BOOKSHELF));
        rig.onEachTick(() -> {
            if (!run.tick(rig.body())) return;
            helper.assertTrue(run.failure() instanceof Failure.MissingInput missing
                    && missing.key().id().equals("minecraft:emerald"), "the trade ended " + run.result);
            List<BlockPos> lecterns = lecternsIn(helper);
            helper.assertTrue(lecterns.size() == 1, "expected the lectern to stand, found " + lecterns);
            helper.assertTrue(villager.getVillagerData().getProfession() == VillagerProfession.LIBRARIAN,
                "the villager is no librarian any more");
            if (!lecterns.isEmpty()) {
                helper.assertTrue(BotMemory.of(rig.id()).isPlaced(helper.getLevel().dimension().location().toString(),
                    helper.absolutePos(lecterns.get(0))), "the standing lectern is not remembered as the body's");
            }
            rig.assertWindowClosed();
            rig.pass();
        });
    }

    /**
     * No villager anywhere near, strict profile: {@code NoSource} naming the item and the source, within a bound. The
     * search is the short one the task takes from its constructor (as a mine or kill task's search is narrowed), so the
     * bound the test waits for is the task's own, not the default search's six rounds.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = NONE_TICKS, batch = "trade_novillagerisanosourcewit")
    public static void noVillagerIsANoSourceWithinABound(GameTestHelper helper) {
        room(helper);
        Rig rig = Rig.spawn(helper, "nobody", NONE_TICKS);
        rig.give(Items.EMERALD, 12);
        TaskRun run = new TaskRun(tradeTask(null, Items.BOOKSHELF, new TradeTask.Search(2, 20)));
        rig.onEachTick(() -> {
            if (!run.tick(rig.body())) return;
            helper.assertTrue(run.failure() instanceof Failure.NoSource no
                    && no.key().id().equals("minecraft:bookshelf") && "trade:test".equals(no.source()),
                "the trade ended " + run.result);
            rig.pass();
        });
    }

    // ---------------------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------------------

    /** A floor and walls, so a villager put in the room stays in it. */
    private static void room(GameTestHelper helper) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                helper.setBlock(x, FLOOR_Y, z, Blocks.STONE);
                for (int y = FLOOR_Y + 1; y < 6; y++) {
                    boolean wall = x == 0 || x == 15 || z == 0 || z == 15;
                    if (wall) helper.setBlock(x, y, z, Blocks.STONE);
                }
            }
        }
    }

    /** A mending book among what the body holds, read the way the snapshot reads it. */
    private static boolean carriesMending(Rig rig) {
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = rig.body().getInventory().getItem(slot);
            ItemEnchantments stored = stack.get(DataComponents.STORED_ENCHANTMENTS);
            if (stored == null) continue;
            for (var entry : stored.entrySet()) {
                if (entry.getKey().unwrapKey().map(k -> k.location().toString().equals("minecraft:mending")).orElse(false)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Spawns an adult villager in the room (set-up: the profession, level and offers the test wants are set before the
     * task starts — {@code profession} null leaves it unemployed, {@code offers} null leaves it with none), so it
     * stays put. It is removed again when the test ends.
     */
    private static Villager villager(GameTestHelper helper, VillagerProfession profession, MerchantOffer offer) {
        Villager villager = EntityType.VILLAGER.create(helper.getLevel());
        if (profession != null) {
            villager.setVillagerData(villager.getVillagerData().setProfession(profession).setLevel(1));
            // A villager that has never traded and has no job site gives its profession up within a second (the
            // game's own rule, and what a reroll rests on). One point of trading experience is what keeps it.
            villager.setVillagerXp(1);
        }
        Vec3 at = helper.absoluteVec(new Vec3(8.5, FLOOR_TOP, 10.5));
        villager.moveTo(at.x, at.y, at.z, 180.0F, 0.0F);
        helper.getLevel().addFreshEntity(villager);
        villager.setNoAi(false);
        if (offer != null) {
            MerchantOffers offers = villager.getOffers();
            offers.clear();
            offers.add(offer);
        }
        return villager;
    }

    /** One offer: {@code emeralds} of the first item, plus {@code extraCount} of the second when there is one. */
    private static MerchantOffer offer(Item emeralds, int count, Item extra, int extraCount, ItemStack result) {
        ItemCost costA = new ItemCost(emeralds, count);
        Optional<ItemCost> costB = extra == null ? Optional.empty() : Optional.of(new ItemCost(extra, extraCount));
        return new MerchantOffer(costA, costB, result, 12, 2, 0.2F);
    }

    /** The {@code trade} leaf of a librarian's trade for {@code item}, checked with {@code check} when it has one. */
    private static Task<BotPlayer> tradeTask(ComponentCheck check, Item item) {
        return tradeTask(check, item, TradeTask.Search.DEFAULT);
    }

    /** As above, with the search bounds the test asks for, as a mine or kill task's test narrows theirs. */
    private static Task<BotPlayer> tradeTask(ComponentCheck check, Item item, TradeTask.Search search) {
        Goal.HaveItem goal = check == null
                ? new Goal.HaveItem(ItemKeys.of(item), 1)
                : new Goal.HaveItem(ItemKeys.of(item), 1, check);
        Map<String, String> params = Map.of(
                "item", ItemKeys.of(item).id(),
                "count", "1",
                "profession", "minecraft:librarian",
                "level", "1",
                "cost", "minecraft:emerald=9",
                "chance", "1.0",
                "jobSite", "minecraft:lectern");
        return new TradeTask(NodeSpec.leaf(goal, "trade", params, "trade:test"), Map.of(), search);
    }

    /** Every lectern block in the arena, as relative positions. */
    private static List<BlockPos> lecternsIn(GameTestHelper helper) {
        List<BlockPos> found = new ArrayList<>();
        for (int x = 1; x < 15; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 1; z < 15; z++) {
                    BlockState state = helper.getBlockState(new BlockPos(x, y, z));
                    if (state.is(Blocks.LECTERN)) found.add(new BlockPos(x, y, z));
                }
            }
        }
        return found;
    }

    /** One task ticked the way the executor ticks it, keeping how it ended. */
    private static final class TaskRun {
        private final Task<BotPlayer> task;
        TickResult result;

        TaskRun(Task<BotPlayer> task) {
            this.task = task;
        }

        /** One tick of the task, starting it first; true once it has ended. */
        boolean tick(BotPlayer body) {
            if (result != null && !(result instanceof TickResult.Running)) return true;
            if (task.state() == TaskState.PENDING) task.start(body);
            result = task.tick(body);
            return !(result instanceof TickResult.Running);
        }

        Failure failure() {
            return result instanceof TickResult.Failed failed ? failed.failure() : null;
        }
    }

    /**
     * One test body, spawned in the arena under a name unique to this run; despawned and forgotten when the test
     * ends, passed or not.
     */
    private static final class Rig {
        private final GameTestHelper helper;
        private final BodyManager manager;
        private final String name;
        private final UUID id;
        /** The villagers this test spawned: discarded when it ends, passed or not, so no test sees another's. */
        private final List<Villager> villagers = new ArrayList<>();

        private Rig(GameTestHelper helper, String tag) {
            this.helper = helper;
            this.manager = BodyManager.of(helper.getLevel().getServer());
            this.name = "gt_" + tag + "_" + RUN_ID;
            this.id = BodyManager.uuidFor(name);
        }

        /** A body with no owner: the bot's answers go to the log only. */
        static Rig spawn(GameTestHelper helper, String tag, int deadlineTicks) {
            Rig rig = new Rig(helper, tag);
            rig.respawn(helper.absoluteVec(new Vec3(8.5, FLOOR_TOP, 6.5)));
            helper.runAtTickTime(deadlineTicks, rig::cleanup);
            return rig;
        }

        UUID id() {
            return id;
        }

        BotPlayer body() {
            BotPlayer current = manager.get(id);
            if (current == null) throw new IllegalStateException("no body " + name + " is logged in");
            return current;
        }

        BotPlayer respawn(Vec3 absolute) {
            try {
                return manager.spawn(name, null, helper.getLevel(), absolute);
            } catch (BodyException e) {
                throw new IllegalStateException("could not spawn " + name + ": " + e.getMessage(), e);
            }
        }

        void give(Item item, int count) {
            body().getInventory().add(new ItemStack(item, count));
        }

        /** Spawns the test's villager, remembered for the cleanup when the test ends. */
        Villager villager(VillagerProfession profession, MerchantOffer offer) {
            Villager villager = TradeGameTests.villager(helper, profession, offer);
            villagers.add(villager);
            return villager;
        }

        int count(Item item) {
            return body().getInventory().countItem(item);
        }

        /** No menu is open and the cursor is empty: whatever the task did through a window, it is done. */
        void assertWindowClosed() {
            BotPlayer body = body();
            helper.assertTrue(body.containerMenu == body.inventoryMenu, "a menu is still open: " + body.containerMenu);
            helper.assertTrue(body.containerMenu.getCarried().isEmpty(),
                "the cursor holds " + body.containerMenu.getCarried());
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

        void pass() {
            cleanup();
            helper.succeed();
        }

        void cleanup() {
            // What the test brought into the arena leaves with it: the villagers it spawned (only the body is the
            // framework's) and any lectern the task put down, so a later test's 48-block look finds none of it.
            for (Villager villager : villagers) villager.discard();
            villagers.clear();
            for (BlockPos lectern : lecternsIn(helper)) {
                helper.setBlock(lectern.getX(), lectern.getY(), lectern.getZ(), Blocks.AIR);
            }
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
