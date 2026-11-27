package com.legoj15.megablockbot.game.task;

import com.legoj15.megablockbot.MegaBlockBot;
import com.legoj15.megablockbot.core.ItemKey;
import com.legoj15.megablockbot.core.Pos;
import com.legoj15.megablockbot.core.goal.ComponentCheck;
import com.legoj15.megablockbot.core.goal.Goal;
import com.legoj15.megablockbot.core.plan.NodeSpec;
import com.legoj15.megablockbot.core.plan.Profile;
import com.legoj15.megablockbot.core.snapshot.NotedOffer;
import com.legoj15.megablockbot.core.task.Failure;
import com.legoj15.megablockbot.core.task.TickResult;
import com.legoj15.megablockbot.game.body.BotPlayer;
import com.legoj15.megablockbot.game.bot.BotRuntime;
import com.legoj15.megablockbot.game.nav.BodyInteractionManager;
import com.legoj15.megablockbot.game.nav.BotNavigator;
import com.legoj15.megablockbot.game.plan.BotMemory;
import com.legoj15.megablockbot.game.plan.ItemKeys;
import com.legoj15.megablockbot.game.plan.SnapshotCollector;
import com.legoj15.megablockbot.game.safety.BlockSafety;
import com.legoj15.megablockbot.game.safety.Verdict;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerData;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;

/**
 * {@code trade}: buy the leaf's item from a villager the way a player does (issue #15): find a villager, open its trade
 * window, pay, take the result. A villager's offers are learned only by opening its window, so the task opens each
 * candidate once, notes what it saw in the body's memory (which the next plan prices the payment by) and closes; when
 * an offer's result satisfies the leaf's goal — the goal's component check applied to the result stack through the
 * same facts the snapshot reads — it pays what the offer charges and takes the result out of the result slot.
 *
 * <p>Parameters from the planner: {@code item}, {@code count} (the trades: one item each), {@code profession},
 * {@code level}, {@code cost}, {@code chance} and {@code jobSite} (the item of the block that gives a villager the
 * profession, or empty). The leaf's goal is the absolute count of the item, and with a component check only stacks
 * that pass it count.
 *
 * <p><b>Rerolling.</b> When no villager within the search radius offers the item and the body carries the job-site
 * item, the task makes one: it puts the job site down within 3 blocks of an adult villager with no profession (not a
 * nitwit), at a place found the way a crafting table's place is found, and notes it as the body's so block safety
 * lets the body take it up again. It waits for the villager to take the profession (visible on it), opens the window
 * and notes the offers; if the wanted offer is not there, it closes, breaks the job site, picks its item up, waits for
 * the villager to lose the profession again and puts it back: a new roll, since a villager that has never traded gets
 * new offers each time it takes the job. The loop never goes back through the search for its own villager — the
 * villager it is rolling keeps its place in it until it trades, is lost, or the roll's own bound passes — and a
 * job-site block this body placed that still stands (a roll that lost track of itself) is taken up before a new one is
 * asked for. Bounded by {@link #MAX_ROLLS} and the task's own budget; out of rolls is {@code NoProgress} with the
 * count. Every villager within the radius has been looked at, none offers the item and no job-site item is carried
 * (and none of the body's stands): {@code MissingInput} naming the job-site item, without exploring first — the
 * missing thing is an item, not a villager further away.
 *
 * <p>Other villagers' job sites are never touched (only one the body put down itself is taken up), and a villager is
 * never moved, hurt, cured or bred. A job site the task put down for a villager that then showed the offer the goal
 * asks for stays where it is and stays remembered, whether the body could pay at once or not: the offer is what the
 * rolls were for, and a villager that has not traded loses it with its job site. One put down for a villager that
 * never showed the offer is taken up again on every exit. A job site of this body that a villager who has traded
 * works at is never taken up by a later task. Whichever way the task ends, the window is closed and the cursor empty.
 *
 * <p><b>A noted villager.</b> A leaf whose price the plan took from a noted offer names that villager and where it
 * stood ({@code villager}, {@code at}): the task goes there first and looks at that one first, so the price the plan
 * gathered is the price it is asked. A villager whose charge the body cannot pay does not end the task while another
 * is still to be looked at; when none is left the cheapest charge seen is the answer ({@code MissingInput}), and no
 * roll is begun for an item somebody already offers.
 */
public final class TradeTask extends BodyTask {
    public static final String KIND = "trade";
    /** The menu slot the trade result sits in ({@code MerchantMenu}'s own {@code RESULT_SLOT} is protected). */
    private static final int RESULT_SLOT = 2;
    /**
     * How far from the body an adult villager is looked for. A villager scans for a job site within 48 of itself
     * (AcquirePoi's range), so one within this of the body is also one the body can make take a job site it puts down.
     */
    public static final double SEARCH_RADIUS = 48.0;
    /** Within this many blocks of the villager the job site is put down: near enough that its own scan finds it fast. */
    private static final double JOB_SITE_NEAR = 3.0;
    /**
     * Ticks the task waits for a villager to take, or to lose, the profession of a job site the body put down or took
     * up. The villager scans every 20-39 ticks at first and backs off to 400 a try; 600 is past the second scan even
     * at its slowest, and far short of one task budget spent on a single villager.
     */
    static final int PROFESSION_WAIT_TICKS = 600;
    /**
     * Rerolls before the task gives up. A novice librarian offers a wanted level-1 offer in about two rolls of three,
     * and a book of mending — one specific enchanted book out of the whole tradeable tag — takes about 40 rolls on
     * average; 200 is five times that, which a run of bad luck does not reach.
     */
    static final int MAX_ROLLS = 200;
    /** How often a window that would not open is tried before that villager is skipped for a while. */
    private static final int MAX_OPENS = 3;
    /**
     * How far the game is asked for the nearest village, in chunks, in the ASSISTED profile (design 4.5): the bound
     * the game's own locate command searches to. The call holds the server thread for a second or two, so it is made
     * once for a goal: the answer rides in the task's checkpoint.
     */
    private static final int VILLAGE_RADIUS = 100;
    /** Within this many blocks, level, of where it was sent (a village, a noted villager's place) the walk there is over. */
    private static final double ARRIVED_WITHIN = 24.0;
    /**
     * Ticks a villager that has just taken the job is tried for its window before the roll gives that villager up:
     * five seconds, longer than it is busy walking to its new job site, shorter than a night's sleep.
     */
    private static final int WINDOW_WAIT_TICKS = 100;

    /**
     * How hard the task looks for a villager when none is known: rounds of exploring, each one followed by another
     * look, as the mine, kill and harvest tasks take their search bounds — so a test can ask for a short one.
     *
     * @param exploreRounds    rounds before the task says there is no villager to trade with
     * @param exploreStepTicks ticks an exploring round lasts; short, because a villager is found by sight within the
     *                         radius, not by distance
     */
    public record Search(int exploreRounds, int exploreStepTicks) {
        /**
         * Six rounds of 100 ticks each: short steps, because a villager within the radius is seen rather than walked
         * to, and six looks are enough to say none is coming into one.
         */
        public static final Search DEFAULT = new Search(6, 100);
    }

    private final Search search;

    private enum Phase { LOOKING, WALKING, INSPECTING, TRADING, PLACING, WAITING_JOB, TAKING_SITE, WAITING_RESET,
        VILLAGE, EXPLORING, TIDYING }

    private final String sourceId;
    private final ItemKey itemKey;
    private final String profession;
    @Nullable private final ComponentCheck check;
    private final int goalCount;
    private final int moreCount;
    @Nullable private final ItemKey jobSiteKey;
    /** The villager whose noted offer set the plan's price, and where it stood; null when the plan had no note. */
    @Nullable private final UUID notedVillager;
    @Nullable private final Pos notedAt;
    /** The village an earlier task for this node was told of, and the dimension it was in (a checkpoint's hint). */
    @Nullable private final Pos hintVillage;
    private final String hintDimension;

    private Item item;
    @Nullable private Item jobSiteItem;
    @Nullable private Block jobSiteBlock;
    private String dimension = "";
    private int target;
    private boolean alreadyDone;
    private Phase phase = Phase.LOOKING;
    /** Villagers whose window would not open (asleep, trading with someone else), skipped for a while. */
    private final Map<UUID, Integer> skipped = new HashMap<>();
    /** Villagers whose window this task already looked at: offers change only on a restock or a reroll. */
    private final java.util.Set<UUID> inspected = new java.util.HashSet<>();
    /** The villager the task is at, and the window it opened at that villager. */
    @Nullable private Villager villager;
    /** The villager this task is rerolling, by id: the one its job site was put down for, whichever phase the roll is in. */
    @Nullable private UUID rolled;
    @Nullable private MerchantMenu menu;
    private int opens;
    /**
     * The job site this task put down, and whether the villager it was put down for showed the offer the goal asks
     * for: such a site stays down, paid for now or by a later plan.
     */
    @Nullable private BlockPos site;
    private boolean siteEarned;
    /** Job sites of this body that this task could not take up: not adopted again, or the task would circle them. */
    private final Set<BlockPos> leftStanding = new HashSet<>();
    /** Whether the walk to the noted villager's place was begun, and whether the note was checked on arriving. */
    private boolean wentToNoted;
    private boolean notedChecked;
    /** Whether the game was asked for a village, what it answered, and whether the body has been sent there. */
    private boolean villageAsked;
    @Nullable private BlockPos villageAt;
    private boolean villageVisited;
    /** Where a walk in the VILLAGE phase is headed, and what to call it in a failure. */
    @Nullable private BlockPos walkTo;
    private String walkToName = "the village";
    /** The cheapest charge a villager asked that the body could not pay, and its total: the answer when nobody is left. */
    @Nullable private Failure.MissingInput shortfall;
    private int shortfallTotal;
    private int windowTicks;
    /**
     * Villagers that took the job and then would not open their window: not rolled again by this task. Not the
     * same as {@link #skipped}: an unemployed villager's window never opens, and that one is exactly who is rolled.
     */
    private final Set<UUID> unrollable = new HashSet<>();
    private int rolls;
    private int phaseTicks;
    private int exploreRounds;
    private int takeTicks;
    /** The offer the task is buying, by index in the villager's own list. */
    private int offerIndex = -1;
    /** The answer the tidy step hands back once the job site it must take up is taken up. */
    @Nullable private TickResult pending;
    /** What the body held of the leaf's item when the last tick looked: the status line. */
    private int lastHeld;

    public TradeTask(NodeSpec leaf, Map<String, String> hints) {
        this(leaf, hints, Search.DEFAULT);
    }

    public TradeTask(NodeSpec leaf, Map<String, String> hints, Search search) {
        this.search = search;
        this.sourceId = leaf.source();
        this.itemKey = ItemKey.of(required(leaf, "item"));
        this.profession = required(leaf, "profession");
        this.goalCount = leaf.goal() instanceof Goal.HaveItem g && g.key().equals(itemKey) ? g.count() : -1;
        this.check = leaf.goal() instanceof Goal.HaveItem g ? g.check() : null;
        this.moreCount = intParam(leaf.params(), "count", 1);
        String siteText = leaf.params().get("jobSite");
        this.jobSiteKey = siteText == null || siteText.isBlank() ? null : ItemKey.of(siteText);
        this.notedVillager = uuidOf(leaf.params().get("villager"));
        this.notedAt = posOf(leaf.params().get("at"));
        this.hintVillage = posOf(hints.get("village"));
        this.hintDimension = hints.getOrDefault("dim", "");
    }

    @Nullable
    private static UUID uuidOf(@Nullable String text) {
        if (text == null || text.isBlank()) return null;
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Nullable
    private static Pos posOf(@Nullable String text) {
        if (text == null || text.isBlank()) return null;
        try {
            return Pos.parse(text);
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Start
    // ---------------------------------------------------------------------------------------------------------

    @Override
    protected void begin(BotPlayer body) {
        dimension = dimensionOf(body);
        item = itemOf(itemKey);
        if (item == null) {
            fail(new Failure.NoSource(itemKey, sourceId, "'" + itemKey.id() + "' is not a registered item"));
            return;
        }
        if (jobSiteKey != null) {
            jobSiteItem = itemOf(jobSiteKey);
            if (jobSiteItem instanceof BlockItem blockItem) jobSiteBlock = blockItem.getBlock();
        }
        if (hintVillage != null && hintDimension.equals(dimension)) {
            // The game was asked once for this goal already: its answer is taken, not asked for again.
            villageAsked = true;
            villageAt = blockPos(hintVillage);
        }
        int held = held(body);
        target = goalCount > 0 ? goalCount : held + Math.max(1, moreCount);
        if (held >= target) alreadyDone = true; // nothing to do, and nothing is touched: the first tick says so
    }

    @Override
    protected void resumeWork(BotPlayer body) {
        // A resumed task looks again from the top with what it has noted: the villager may be gone, its window was
        // closed by the pause, and the count the body holds is counted anew. A villager mid-roll is kept — the roll
        // goes on with it (checked against the world) rather than starting over at another.
        // A payment that was under way goes on with its villager, and so does a villager whose job site stands: to
        // drop either would leave the site this task put down standing with nobody to answer for it.
        boolean paying = phase == Phase.TRADING && villager != null && villager.isAlive() && offerIndex >= 0;
        if (rolled == null && site == null && !paying) villager = null;
        menu = null;
        opens = 0;
        windowTicks = 0;
        phaseTicks = 0;
        phase = paying ? Phase.TRADING : site != null ? Phase.WAITING_JOB : Phase.LOOKING;
    }

    private int held(BotPlayer body) {
        int total = 0;
        for (int slot = 0; slot < MenuHand.CARRIED_SLOTS; slot++) {
            ItemStack stack = body.getInventory().getItem(slot);
            if (!stack.is(item)) continue;
            if (check != null && !check.test(SnapshotCollector.factsOf(stack))) continue;
            total += stack.getCount();
        }
        return total;
    }

    @Nullable
    private static Item itemOf(ItemKey key) {
        ResourceLocation id = idOf(key.id());
        return id == null ? null : BuiltInRegistries.ITEM.getOptional(id).orElse(null);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Tick
    // ---------------------------------------------------------------------------------------------------------

    @Override
    protected TickResult onTick(BotPlayer body) {
        if (alreadyDone) return TickResult.COMPLETED;
        lastHeld = held(body);
        // The goal first, whatever got the items into the inventory (a trade this task made, an earlier one).
        if (lastHeld >= target) return tidy(body, TickResult.COMPLETED);
        phaseTicks++;
        return switch (phase) {
            case LOOKING -> look(body);
            case WALKING -> walking(body);
            case INSPECTING -> inspect(body);
            case TRADING -> trade(body);
            case PLACING -> place(body);
            case WAITING_JOB -> waitForJob(body);
            case TAKING_SITE -> takeSite(body);
            case WAITING_RESET -> waitForReset(body);
            case VILLAGE -> village(body);
            case EXPLORING -> explore(body);
            case TIDYING -> tidyTick(body);
        };
    }

    /** Finds a villager to talk to: one of the leaf's profession first, then one to make take the job. */
    private TickResult look(BotPlayer body) {
        if (notedVillager != null && notedAt != null) {
            Entity known = body.serverLevel().getEntity(notedVillager);
            boolean here = known != null && known.isAlive()
                && known.distanceToSqr(body) <= SEARCH_RADIUS * SEARCH_RADIUS;
            if (!wentToNoted) {
                wentToNoted = true;
                if (!here) {
                    // The plan gathered this villager's price: the body goes back to it before it looks at anyone.
                    BlockPos at = known != null && known.isAlive() ? known.blockPosition() : blockPos(notedAt);
                    MegaBlockBot.LOGGER.info("{} walks back to the {} it noted at {}", body.getGameProfile().getName(),
                        profession, at.toShortString());
                    return walkToward(body, at, "the villager whose offer was noted");
                }
            }
            if (!notedChecked) {
                notedChecked = true;
                if (known == null && body.serverLevel().isLoaded(blockPos(notedAt))) {
                    // The place is loaded and the villager is not in the world: the note is of nobody.
                    MegaBlockBot.LOGGER.info("{}: the {} noted at {} is gone; its offers are forgotten",
                        body.getGameProfile().getName(), profession, blockPos(notedAt).toShortString());
                    BotMemory.of(body.getUUID()).forgetOffers(notedVillager);
                }
            }
        }
        List<Villager> candidates = villagers(body);
        for (Villager candidate : candidates) {
            if (skipped.containsKey(candidate.getUUID()) || inspected.contains(candidate.getUUID())) continue;
            villager = candidate;
            MegaBlockBot.LOGGER.info("{}: chose the villager at {} ({})", body.getGameProfile().getName(),
                candidate.blockPosition().toShortString(), professionOf(candidate));
            opens = 0;
            phase = Phase.WALKING;
            phaseTicks = 0;
            return TickResult.RUNNING;
        }
        // Nobody's window is left to look at. Somebody offered the item and asked more than the body carries: that
        // is the answer, and the next plan brings the price. No roll is begun for an item a villager already offers.
        if (shortfall != null) return tidy(body, TickResult.failed(shortfall));
        // A reroll needs the job-site item in hand and a villager to take the job.
        if (jobSiteBlock != null && jobSiteItem != null && MenuHand.carriedCount(body, jobSiteItem) >= 1) {
            Villager unemployed = rerollee(body, candidates);
            if (unemployed != null) {
                villager = unemployed;
                return roll(body);
            }
        } else if (jobSiteKey != null) {
            // The job-site item is not carried. One this body placed earlier and left standing is taken up first: it is
            // the lectern of a roll that lost track of itself, and buying a second one would leave it standing too.
            BlockPos standing = standingSite(body);
            if (standing != null) {
                site = standing;
                BotMemory.of(body.getUUID()).notePlaced(dimension, site);
                phase = Phase.TAKING_SITE;
                phaseTicks = 0;
                return TickResult.RUNNING;
            }
            // Every villager within the radius has been looked at and none offers the item: what is missing is the
            // job-site item that would make one of the profession offer it, and no walk makes one appear.
            if (!candidates.isEmpty()) {
                return tidy(body, TickResult.failed(new Failure.MissingInput(jobSiteKey, 1,
                    "no villager within " + (int) SEARCH_RADIUS + " blocks offers " + itemKey.id()
                        + " and the body carries no " + jobSiteKey.id() + " to give one the profession " + profession)));
            }
        }
        return noneKnown(body);
    }

    /**
     * The nearest job-site block this body placed in this dimension that still stands, or null: a site a roll put down
     * and never took up, recovered rather than replaced.
     */
    @Nullable
    private BlockPos standingSite(BotPlayer body) {
        if (jobSiteBlock == null) return null;
        BlockPos nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (BlockPos at : BotMemory.of(body.getUUID()).placedIn(dimension)) {
            if (!body.serverLevel().isLoaded(at)) continue; // a read never loads a chunk
            if (!body.serverLevel().getBlockState(at).is(jobSiteBlock)) continue;
            if (leftStanding.contains(at) || keptByATrader(body.serverLevel(), at)) continue;
            double distance = at.distSqr(body.blockPosition());
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = at.immutable();
            }
        }
        return nearest;
    }

    /**
     * Whether a villager that has traded works at this job site. Such a site stays: the villager restocks at it, and
     * the trades it has made are why it was put down. The villager's own brain says which block is its job site.
     */
    private static boolean keptByATrader(ServerLevel level, BlockPos at) {
        AABB box = new AABB(at).inflate(SEARCH_RADIUS);
        for (Villager worker : level.getEntitiesOfClass(Villager.class, box, Villager::isAlive)) {
            if (worker.getVillagerXp() <= 0) continue;
            if (!worker.getBrain().hasMemoryValue(MemoryModuleType.JOB_SITE)) continue;
            Optional<GlobalPos> job = worker.getBrain().getMemory(MemoryModuleType.JOB_SITE);
            if (job.isPresent() && job.get().pos().equals(at)) return true;
        }
        return false;
    }

    /**
     * The adult villagers within the radius: the one the plan's price was noted at first, then those of the leaf's
     * profession, then the rest, nearest first.
     */
    private List<Villager> villagers(BotPlayer body) {
        List<Villager> ofProfession = new ArrayList<>();
        List<Villager> all = new ArrayList<>();
        AABB box = new AABB(body.position(), body.position()).inflate(SEARCH_RADIUS);
        for (Villager candidate : body.serverLevel().getEntitiesOfClass(Villager.class, box, Villager::isAlive)) {
            if (candidate.isBaby()) continue; // a child trades nothing and takes no job
            all.add(candidate);
            if (professionOf(candidate).equals(profession)) ofProfession.add(candidate);
        }
        Comparator<Villager> near = Comparator.comparingDouble(v -> v.distanceToSqr(body));
        ofProfession.sort(near);
        all.sort(near);
        List<Villager> ordered = new ArrayList<>();
        for (Villager candidate : all) {
            if (candidate.getUUID().equals(notedVillager)) ordered.add(candidate);
        }
        ordered.addAll(ofProfession);
        ordered.addAll(all);
        return ordered;
    }

    /** An adult villager with no profession that is not a nitwit: the one a job site can be put in front of. */
    @Nullable
    private Villager rerollee(BotPlayer body, List<Villager> candidates) {
        for (Villager candidate : candidates) {
            // One that has traded keeps its offers whatever job it takes, and one whose window would not open is
            // not asked again by this task: neither is a villager to roll.
            if (candidate.getVillagerXp() > 0 || unrollable.contains(candidate.getUUID())) continue;
            if (professionOf(candidate).equals("minecraft:none")) return candidate; // takes the first job it finds
        }
        // One of the leaf's profession that has never traded can be rerolled too: the villager this task is already
        // rolling with, whatever became of the site it was given — the site is down between rolls, and a roll can lose
        // it without losing the villager. One that has traded keeps its site and is never touched.
        if (villager != null && villager.isAlive() && villager.getUUID().equals(rolled)
            && professionOf(villager).equals(profession) && villager.getVillagerXp() == 0) {
            return villager;
        }
        return null;
    }

    private static String professionOf(Villager villager) {
        VillagerData data = villager.getVillagerData();
        return BuiltInRegistries.VILLAGER_PROFESSION.getKey(data.getProfession()).toString();
    }

    /** No villager is known: look further, by the profile's means, or say there is none. */
    private TickResult noneKnown(BotPlayer body) {
        BotRuntime runtime = BotRuntime.of(body);
        if (runtime != null && runtime.profile() == Profile.ASSISTED && !villageAsked) {
            // The game's own locate, as a player's map or a command gives it (design 4.5): the nearest village.
            // Asked once: the call holds the server thread, and a village with nobody to trade with is still the
            // nearest the next time.
            villageAsked = true;
            villageAt = body.serverLevel().findNearestMapStructure(StructureTags.VILLAGE, body.blockPosition(),
                VILLAGE_RADIUS, false);
        }
        if (villageAt != null && !villageVisited) {
            villageVisited = true;
            if (!arrivedAt(body, villageAt)) {
                MegaBlockBot.LOGGER.info("{} walks to the village at {} {} to look for a {}",
                    body.getGameProfile().getName(), villageAt.getX(), villageAt.getZ(), profession);
                return walkToward(body, villageAt, "the village");
            }
        }
        if (++exploreRounds > search.exploreRounds()) {
            return tidy(body, TickResult.failed(new Failure.NoSource(itemKey, sourceId,
                "found no villager to get " + itemKey.id() + " from within " + (int) SEARCH_RADIUS + " blocks in "
                    + search.exploreRounds() + " looks")));
        }
        nav(body).explore(body.blockPosition());
        phase = Phase.EXPLORING;
        phaseTicks = 0;
        return TickResult.RUNNING;
    }

    /**
     * Sends the body to a place known only by its column: the game's locate answers with no height, and a villager
     * noted long ago has walked. The walk is over once the body is near (see {@link #village}).
     */
    private TickResult walkToward(BotPlayer body, BlockPos at, String name) {
        walkTo = at;
        walkToName = name;
        nav(body).goToXZ(at.getX(), at.getZ());
        phase = Phase.VILLAGE;
        phaseTicks = 0;
        return TickResult.RUNNING;
    }

    private static boolean arrivedAt(BotPlayer body, BlockPos at) {
        double dx = body.getX() - (at.getX() + 0.5);
        double dz = body.getZ() - (at.getZ() + 0.5);
        return dx * dx + dz * dz <= ARRIVED_WITHIN * ARRIVED_WITHIN;
    }

    private TickResult village(BotPlayer body) {
        BotNavigator nav = nav(body);
        // Near enough to look: the exact column may be a roof or a well, and nothing is to be done at it.
        if (walkTo == null || arrivedAt(body, walkTo)) {
            stopOurCommand(nav);
            phase = Phase.LOOKING;
            return TickResult.RUNNING;
        }
        switch (nav.state()) {
            case DONE, IDLE -> {
                phase = Phase.LOOKING;
                return TickResult.RUNNING;
            }
            case FAILED -> {
                String reason = nav.failure();
                return tidy(body, TickResult.failed(unreachable(walkToName, reason,
                    "could not walk to " + walkToName + " to look for a " + profession + ": " + reason)));
            }
            default -> { return TickResult.RUNNING; }
        }
    }

    private TickResult explore(BotPlayer body) {
        BotNavigator nav = nav(body);
        if (nav.state() == BotNavigator.State.FAILED) {
            TickResult notTheSource = exploreFailure(body, nav, "a villager");
            if (notTheSource != null) return tidy(body, notTheSource);
            // Nowhere unexplored to walk: this round is spent; another look follows, to the search's own bound.
        } else if (nav.state() == BotNavigator.State.WORKING && phaseTicks < search.exploreStepTicks()) {
            return TickResult.RUNNING;
        }
        // The round ends with its command, whatever its state: a look that finds a villager to walk to must not run
        // under an explore that is still walking the body on.
        stopOurCommand(nav);
        phase = Phase.LOOKING;
        return TickResult.RUNNING;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Walking to a villager and opening its window
    // ---------------------------------------------------------------------------------------------------------

    private TickResult walking(BotPlayer body) {
        Villager who = villager;
        if (who == null || !who.isAlive()) {
            phase = Phase.LOOKING;
            return TickResult.RUNNING;
        }
        if (body.canInteractWithEntity(who, 1.0)) {
            stopOurCommand(nav(body));
            body.input().lookAt(who.getBoundingBox().getCenter());
            phase = Phase.INSPECTING;
            opens = 0; // the tries are counted for this look at this window, not carried over from the last one
            return TickResult.RUNNING;
        }
        BotNavigator nav = nav(body);
        if (nav.state() != BotNavigator.State.WORKING) nav.follow(who, 2);
        return TickResult.RUNNING;
    }

    /** Opens the villager's window, notes its offers and closes it; an offer that satisfies the goal goes to trade. */
    private TickResult inspect(BotPlayer body) {
        Villager who = villager;
        if (who == null || !who.isAlive()) {
            phase = Phase.LOOKING;
            return TickResult.RUNNING;
        }
        if (menu == null && !openWindow(body, who)) return TickResult.RUNNING; // it skipped itself, or retries
        if (menu != null && body.containerMenu != menu) {
            menu = null; // closed under the task
            if (++opens > MAX_OPENS) return skipWindow(body, who);
        }
        if (menu == null) return TickResult.RUNNING;
        note(body, who); // the body's own memory: the next plan prices the payment by it
        inspected.add(who.getUUID());
        int offer = offerFor(who);
        close(body);
        if (offer >= 0) return pay(body, who, offer);
        if (site != null) return roll(body); // this villager was made by a roll: roll it again
        phase = Phase.LOOKING;
        return TickResult.RUNNING;
    }

    /**
     * A use-click on the villager, as a player makes: the villager's own rules decide whether a window opens. A
     * villager whose window will not open (asleep, trading with someone else) is skipped for a while, never failed.
     */
    private boolean openWindow(BotPlayer body, Villager who) {
        if (!body.canInteractWithEntity(who, 1.0)) {
            phase = Phase.WALKING;
            return false;
        }
        if (++opens > MAX_OPENS) {
            skipWindow(body, who);
            return false;
        }
        body.interactOn(who, InteractionHand.MAIN_HAND);
        if (!(body.containerMenu instanceof MerchantMenu open)) {
            if (opens >= MAX_OPENS) skipWindow(body, who);
            return false;
        }
        menu = open;
        opens = 0;
        return true;
    }

    /** A villager whose window would not open (asleep, trading with someone else) is skipped for a while, never failed. */
    private TickResult skipWindow(BotPlayer body, Villager who) {
        skipped.put(who.getUUID(), 1);
        MegaBlockBot.LOGGER.info("{}: skipped the villager at {}: its window did not open ({} of {} tries)",
            body.getGameProfile().getName(), who.blockPosition().toShortString(), opens, MAX_OPENS);
        opens = 0;
        villager = null;
        phase = Phase.LOOKING;
        return TickResult.RUNNING;
    }

    /** Writes the window's offers into the body's memory, replaced by each fresh look. */
    private void note(BotPlayer body, Villager who) {
        MerchantMenu open = menu;
        if (open == null) return;
        List<NotedOffer> offers = new ArrayList<>();
        StringBuilder what = new StringBuilder();
        for (MerchantOffer offer : open.getOffers()) {
            ItemStack result = offer.getResult();
            if (result.isEmpty()) continue;
            Map<ItemKey, Integer> charge = chargesOf(offer);
            offers.add(new NotedOffer(who.getUUID(), corePos(who.blockPosition()), ItemKeys.of(result.getItem()),
                SnapshotCollector.factsOf(result), charge, body.level().getGameTime()));
            if (what.length() > 0) what.append(", ");
            what.append(result.getCount()).append(' ').append(result.getItem());
        }
        MegaBlockBot.LOGGER.info("{}{}: the {} at {} showed {} offer(s): {}", body.getGameProfile().getName(),
            site != null ? " (roll " + rolls + " of " + MAX_ROLLS + ")" : "", profession,
            who.blockPosition().toShortString(), offers.size(), what);
        BotMemory.of(body.getUUID()).noteOffers(dimension, who.getUUID(), who.blockPosition(), offers);
    }

    private static void addCost(Map<ItemKey, Integer> charge, ItemStack stack) {
        if (stack.isEmpty()) return;
        charge.merge(ItemKeys.of(stack.getItem()), stack.getCount(), Integer::sum);
    }

    /** The index of the first offer whose result satisfies the leaf's goal, or -1 when none does. */
    private int offerFor(Villager who) {
        MerchantOffers offers = who.getOffers();
        for (int i = 0; i < offers.size(); i++) {
            if (satisfies(offers.get(i))) return i;
        }
        return -1;
    }

    private boolean satisfies(MerchantOffer offer) {
        ItemStack result = offer.getResult();
        if (!result.is(item)) return false;
        return check == null || check.test(SnapshotCollector.factsOf(result));
    }

    private void close(BotPlayer body) {
        if (menu != null && body.containerMenu == menu) {
            new MenuHand(body, menu).putBack();
            body.closeContainer();
        }
        menu = null;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Trading
    // ---------------------------------------------------------------------------------------------------------

    /** At a villager whose window offered the item: end now when the body cannot pay; otherwise pay and take. */
    private TickResult pay(BotPlayer body, Villager who, int index) {
        MerchantOffer offer = who.getOffers().get(index);
        // The villager this task gave the job shows the offer the rolls were for: its site stays down from here on,
        // whether the body can pay now or a later plan brings the price.
        boolean ours = site != null;
        if (ours) siteEarned = true;
        int total = 0;
        Failure.MissingInput missing = null;
        for (Map.Entry<ItemKey, Integer> charge : chargesOf(offer).entrySet()) {
            Item charged = itemOf(charge.getKey());
            int carries = charged == null ? 0 : MenuHand.carriedCount(body, charged);
            total += charge.getValue();
            if (carries < charge.getValue() && missing == null) {
                missing = new Failure.MissingInput(charge.getKey(), charge.getValue(),
                    "the " + profession + " at " + who.blockPosition().toShortString() + " charges "
                        + charge.getValue() + " " + charge.getKey().id() + " for " + itemKey.id()
                        + " and the body carries " + carries);
            }
        }
        if (missing != null) {
            // The body cannot pay. The price is noted, so the next plan gathers it and names this villager.
            MegaBlockBot.LOGGER.info("{}: cannot pay: {}", body.getGameProfile().getName(), missing.detail());
            if (shortfall == null || total < shortfallTotal) {
                shortfall = missing;
                shortfallTotal = total;
            }
            if (ours) return tidy(body, TickResult.failed(shortfall));
            // Another villager may ask less: this one is passed by, and its charge is the answer if nobody does.
            villager = null;
            phase = Phase.LOOKING;
            return TickResult.RUNNING;
        }
        villager = who;
        rolled = null; // a trade begun is not a roll any more: its site stays down and the villager keeps its job
        MegaBlockBot.LOGGER.info("{}: takes the {}'s offer of {} x{} for {}", body.getGameProfile().getName(),
            profession, itemKey.id(), offer.getResult().getCount(), chargesOf(offer));
        offerIndex = index;
        phase = Phase.TRADING;
        phaseTicks = 0;
        takeTicks = 0;
        // The window is opened again to pay: with the tries of the look before still counted, the first try was
        // already one too many, and a villager with the very offer wanted was skipped and rolled again.
        opens = 0;
        return trade(body);
    }

    /**
     * One trade at a time, the way the client's select-trade packet and a click do it: the offer selected, its payment
     * moved into the payment slots by the game, the result taken out of the result slot by a click.
     */
    private TickResult trade(BotPlayer body) {
        Villager who = villager;
        if (who == null || !who.isAlive() || offerIndex < 0) {
            phase = Phase.LOOKING;
            return TickResult.RUNNING;
        }
        if (held(body) >= target) return tidy(body, TickResult.COMPLETED);
        if (menu == null || body.containerMenu != menu) {
            menu = null;
            if (!openWindow(body, who)) return TickResult.RUNNING;
        }
        MerchantOffer offer = who.getOffers().get(offerIndex);
        if (offer.isOutOfStock()) {
            return tidy(body, TickResult.failed(new Failure.NoProgress(offer.getUses(),
                "the " + profession + "'s offer of " + itemKey.id() + " is out of stock (used " + offer.getUses()
                    + " of " + offer.getMaxUses() + " times)")));
        }
        if (MenuHand.roomFor(body, offer.getResult()) <= 0) {
            return tidy(body, TickResult.failed(new Failure.NoRoom(itemKey, "the inventory cannot take one more "
                + itemKey.id() + ": no free slot and no stack of it with room")));
        }
        int before = held(body);
        // The server's own select-trade path: mark the offer and let the game move what it charges into the slots.
        menu.setSelectionHint(offerIndex);
        menu.tryMoveItems(offerIndex);
        if (!menu.getSlot(RESULT_SLOT).hasItem()) {
            if (++takeTicks > MAX_OPENS) {
                return tidy(body, TickResult.failed(new Failure.MissingInput(itemKey, 1,
                    "the payment for the " + profession + "'s " + itemKey.id() + " did not reach the trade window")));
            }
            return TickResult.RUNNING;
        }
        MenuHand hand = new MenuHand(body, menu);
        hand.click(RESULT_SLOT, 0, ClickType.PICKUP);
        hand.putBack();
        if (held(body) <= before) {
            // A take that brought nothing in (the game refused it): a few more, then the step is given up.
            if (++takeTicks > MAX_OPENS) {
                return tidy(body, TickResult.failed(new Failure.NoProgress(takeTicks,
                    "taking the " + itemKey.id() + " out of the trade window brought nothing " + takeTicks + " times")));
            }
            return TickResult.RUNNING;
        }
        takeTicks = 0;
        return TickResult.RUNNING;
    }

    /**
     * What one use of the offer charges now, by item: the game's own stacks, merged. The first is the price the
     * window moves into the payment slot (the base price with the villager's demand and any discount applied), which
     * is what the body must carry; the base price alone is not what is asked after a few trades.
     */
    private static Map<ItemKey, Integer> chargesOf(MerchantOffer offer) {
        Map<ItemKey, Integer> charge = new TreeMap<>();
        addCost(charge, offer.getCostA());
        addCost(charge, offer.getCostB());
        return charge;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Rerolling
    // ---------------------------------------------------------------------------------------------------------

    /** A new roll begins: bounds first, then the job site goes down beside the villager this task is making a trader. */
    private TickResult roll(BotPlayer body) {
        if (villager != null && villager.isAlive() && villager.getVillagerXp() > 0) {
            // It traded (with someone else, while the task was not looking): it keeps the profession and the site it
            // has, and a villager that has traded gets no new offers from losing the job anyway.
            rolled = null;
            return look(body);
        }
        if (++rolls > MAX_ROLLS) {
            return tidy(body, TickResult.failed(new Failure.NoProgress(rolls, "rerolled a " + profession + " "
                + MAX_ROLLS + " times and its offers never included " + itemKey.id())));
        }
        if (jobSiteKey == null || jobSiteItem == null || jobSiteBlock == null) {
            return tidy(body, TickResult.failed(new Failure.MissingInput(jobSiteKey == null ? itemKey : jobSiteKey, 1,
                "no villager offers " + itemKey.id() + " and no job-site item is known for the " + profession)));
        }
        if (MenuHand.carriedCount(body, jobSiteItem) < 1) {
            return tidy(body, TickResult.failed(new Failure.MissingInput(jobSiteKey, 1,
                "no villager offers " + itemKey.id() + " and the body carries no " + jobSiteKey.id()
                    + " to make one of the profession " + profession)));
        }
        rolled = villager != null && villager.isAlive() ? villager.getUUID() : null;
        phase = Phase.PLACING;
        phaseTicks = 0;
        return TickResult.RUNNING;
    }

    /**
     * Puts the job site down within {@link #JOB_SITE_NEAR} blocks of the villager, through the player's own item use,
     * sneaking, at a place found the way a crafting table's place is found (TableSites), and notes it as the body's.
     */
    private TickResult place(BotPlayer body) {
        Villager who = villager;
        if (who == null || !who.isAlive()) {
            phase = Phase.LOOKING;
            return TickResult.RUNNING;
        }
        if (site != null) {
            phase = Phase.WAITING_JOB;
            return TickResult.RUNNING;
        }
        if (who.getBoundingBox().getCenter().distanceToSqr(body.position())
                > (JOB_SITE_NEAR + 2) * (JOB_SITE_NEAR + 2)) {
            BotNavigator nav = nav(body);
            if (nav.state() != BotNavigator.State.WORKING) nav.goTo(who.blockPosition(), 1);
            return TickResult.RUNNING;
        }
        if (!body.isShiftKeyDown()) {
            body.input().sneak = true;
            return TickResult.RUNNING;
        }
        if (!StationTask.hold(body, jobSiteItem)) {
            body.input().sneak = false;
            return tidy(body, TickResult.failed(new Failure.MissingInput(jobSiteKey, 1,
                "the " + jobSiteKey.id() + " could not be taken into the hand")));
        }
        ServerLevel level = body.serverLevel();
        BlockPos feet = body.blockPosition();
        BlockState state = jobSiteBlock.defaultBlockState();
        List<TableSites.Site> sites = TableSites.sitesFrom(body, level, feet,
            TableSites.sneakingEye(body, feet, true), true, 6, new TableSites.Refusal(), state);
        for (TableSites.Site candidate : sites) {
            if (candidate.spot().distSqr(who.blockPosition()) > (long) JOB_SITE_NEAR * JOB_SITE_NEAR) continue;
            body.input().lookAt(candidate.click());
            body.getInteractionManager().interactBlock(body, level, body.getMainHandItem(), InteractionHand.MAIN_HAND,
                new BlockHitResult(candidate.click(), candidate.face(), candidate.against(), false));
            if (!level.getBlockState(candidate.spot()).is(jobSiteBlock)) continue;
            site = candidate.spot().immutable();
            MegaBlockBot.LOGGER.info("{}: the {} goes down at {} beside the villager at {} (roll {} of {})",
                body.getGameProfile().getName(), jobSiteKey.id(), site.toShortString(),
                who.blockPosition().toShortString(), rolls, MAX_ROLLS);
            BotMemory.of(body.getUUID()).notePlaced(dimension, site);
            BlockSafety.of(level.getServer()).noteBotPlaced(body, level, site);
            body.input().sneak = false;
            body.input().clearLook();
            phase = Phase.WAITING_JOB;
            phaseTicks = 0;
            return TickResult.RUNNING;
        }
        body.input().sneak = false;
        body.input().clearLook();
        return tidy(body, TickResult.failed(unreachable("a place beside the villager for the " + jobSiteKey.id(),
            "nowhere to put it down", "found nowhere within " + (int) JOB_SITE_NEAR + " blocks of the villager at "
                + who.blockPosition().toShortString() + " to put the " + jobSiteKey.id() + " down")));
    }

    /** Waits for the villager to take the profession of the job site; visible on it. Then its window says the offers. */
    private TickResult waitForJob(BotPlayer body) {
        Villager who = villager;
        if (site == null) {
            phase = Phase.LOOKING;
            return TickResult.RUNNING;
        }
        if (!body.serverLevel().getBlockState(site).is(jobSiteBlock)) {
            // The site is gone: there is nothing of it to remember. A look from the top finds the villager again (it
            // keeps its place in the roll until it trades) and takes the roll on.
            forgetSite(body);
            phase = Phase.LOOKING;
            return TickResult.RUNNING;
        }
        if (who == null || !who.isAlive()) {
            // The villager is gone and the site stands: it comes up, as it does after any roll that found nothing.
            phase = Phase.TAKING_SITE;
            phaseTicks = 0;
            return TickResult.RUNNING;
        }
        if (!professionOf(who).equals(profession)) {
            if (phaseTicks > PROFESSION_WAIT_TICKS) {
                // It never took the job: the site comes up, and the task looks elsewhere for its trader.
                MegaBlockBot.LOGGER.info("{}: the villager at {} did not take the job of the {} at {} in {} ticks",
                    body.getGameProfile().getName(), who.blockPosition().toShortString(), jobSiteKey.id(),
                    site.toShortString(), PROFESSION_WAIT_TICKS);
                phase = Phase.TAKING_SITE;
                phaseTicks = 0;
                return TickResult.RUNNING;
            }
            return TickResult.RUNNING;
        }
        // A new profession, so offers nobody has seen: open the window (a look, as a player must) and note them.
        if (menu == null) {
            if (!body.canInteractWithEntity(who, 1.0)) {
                BotNavigator nav = nav(body);
                if (nav.state() != BotNavigator.State.WORKING) nav.goTo(who.blockPosition(), 1);
                return TickResult.RUNNING;
            }
            body.interactOn(who, InteractionHand.MAIN_HAND);
            if (!(body.containerMenu instanceof MerchantMenu open)) {
                if (++windowTicks > WINDOW_WAIT_TICKS) {
                    // Its window will not open (asleep, trading with someone else): the roll ends, the site comes
                    // up, and this villager is not rolled again by this task.
                    MegaBlockBot.LOGGER.info("{}: the {} at {} would not open its window in {} ticks; the roll ends",
                        body.getGameProfile().getName(), profession, who.blockPosition().toShortString(),
                        WINDOW_WAIT_TICKS);
                    unrollable.add(who.getUUID());
                    windowTicks = 0;
                    phase = Phase.TAKING_SITE;
                    phaseTicks = 0;
                }
                return TickResult.RUNNING;
            }
            menu = open;
            windowTicks = 0;
        }
        note(body, who);
        int offer = offerFor(who);
        close(body);
        if (offer >= 0) return pay(body, who, offer);
        phase = Phase.TAKING_SITE; // not this roll: the site comes up and the villager loses the profession again
        phaseTicks = 0;
        return TickResult.RUNNING;
    }

    /** Breaks the job site this task put down, as a player takes a block back; the item is picked up after. */
    private TickResult takeSite(BotPlayer body) {
        if (site == null || breakSite(body, PROFESSION_WAIT_TICKS)) {
            phase = Phase.WAITING_RESET;
            phaseTicks = 0;
        } else if (phaseTicks > PROFESSION_WAIT_TICKS) {
            leftStanding.add(site);
            site = null; // the break never finished; it stands on, remembered, and the roll ends here
            phase = Phase.WAITING_RESET;
            phaseTicks = 0;
        }
        return TickResult.RUNNING;
    }

    /**
     * One tick of breaking the job site. True when there is nothing left to break (gone already, given up, or taken
     * down); false while the break goes on or the body is on its way to the site.
     */
    private boolean breakSite(BotPlayer body, int bound) {
        ServerLevel level = body.serverLevel();
        BlockState state = level.getBlockState(site);
        if (!state.is(jobSiteBlock)) {
            MegaBlockBot.LOGGER.info("{}: the {} at {} comes up (roll {} of {})", body.getGameProfile().getName(),
                jobSiteKey.id(), site.toShortString(), rolls, MAX_ROLLS);
            forgetSite(body);
            return true;
        }
        if (phaseTicks > bound) {
            MegaBlockBot.LOGGER.info("{} stops breaking the {} at {} and leaves it standing",
                body.getGameProfile().getName(), jobSiteKey.id(), site.toShortString());
            leftStanding.add(site);
            site = null;
            return true;
        }
        if (!body.canInteractWithBlock(site, 0.0)) {
            BotNavigator nav = nav(body);
            if (nav.state() != BotNavigator.State.WORKING) nav.goTo(site, 1);
            return false;
        }
        Verdict verdict = BlockSafety.of(level.getServer()).mayBreak(body, level, site, state);
        if (!verdict.allowed()) {
            leftStanding.add(site);
            site = null; // not the body's to break after all: leave it, remembered, and end the roll
            return true;
        }
        BodyInteractionManager hand = (BodyInteractionManager) body.getInteractionManager();
        if (!hand.isMining()) {
            if (MenuHand.roomFor(body, new ItemStack(jobSiteItem)) == 0) {
                leftStanding.add(site);
                site = null; // would only be dropped and lost: it stays, remembered, for a task with room for it
                return true;
            }
            StationTask.selectBreakingTool(body, state);
            hand.processBlockBreakingAction(site, Action.START_DESTROY_BLOCK, Direction.UP,
                level.getMaxBuildHeight(), phaseTicks);
        }
        hand.continueBreaking(site, Direction.UP);
        // A client sends the finish once its own progress reaches 1; the engine reports progress in tenths.
        if (hand.isMining() && hand.getBlockBreakingProgress() >= 10) {
            hand.processBlockBreakingAction(hand.getMiningPos(), Action.STOP_DESTROY_BLOCK, Direction.UP,
                level.getMaxBuildHeight(), phaseTicks);
        }
        if (!level.getBlockState(site).is(jobSiteBlock)) {
            MegaBlockBot.LOGGER.info("{}: the {} at {} comes up (roll {} of {})", body.getGameProfile().getName(),
                jobSiteKey.id(), site.toShortString(), rolls, MAX_ROLLS);
            forgetSite(body);
            return true;
        }
        return false;
    }

    /**
     * The site is gone: its item is picked up, and the villager's profession is waited away. Then the same villager
     * takes the next roll — the loop the reroll is (site down, offers, site up, profession away) never goes through the
     * search for its own villager. Past the roll's own bound the roll is given up instead of waited on for ever.
     */
    private TickResult waitForReset(BotPlayer body) {
        Villager who = villager;
        String still = who == null || !who.isAlive() ? "minecraft:none" : professionOf(who);
        boolean reset = still.equals("minecraft:none") || still.equals("minecraft:nitwit");
        boolean pickedUp = jobSiteItem == null || MenuHand.carriedCount(body, jobSiteItem) >= 1 || siteEarned;
        // The item of the broken site may lie out of reach: walk to it, as the body walks to any drop.
        if (!pickedUp && phaseTicks > 20) walkToSiteDrop(body);
        if (reset && pickedUp) {
            if (who != null && unrollable.contains(who.getUUID())) {
                rolled = null;
                villager = null;
                return look(body);
            }
            return roll(body);
        }
        if (phaseTicks <= PROFESSION_WAIT_TICKS) return TickResult.RUNNING;
        // The profession did not go, or the item did not come in: the roll waits no longer than its own bound.
        MegaBlockBot.LOGGER.info("{}: gives up rolling the {} (its profession is still {}, and the site's item {}) after"
                + " {} ticks", body.getGameProfile().getName(), profession, still,
            pickedUp ? "is in hand" : "never came in", PROFESSION_WAIT_TICKS);
        rolled = null;
        return look(body);
    }

    @Nullable
    private ItemEntity nearestSiteDrop(BotPlayer body) {
        ItemEntity nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        AABB box = site == null ? new AABB(body.blockPosition()).inflate(8.0) : new AABB(site).inflate(8.0);
        for (ItemEntity drop : body.serverLevel().getEntitiesOfClass(ItemEntity.class, box,
                candidate -> !candidate.isRemoved() && candidate.getItem().is(jobSiteItem))) {
            double distance = drop.distanceToSqr(body);
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = drop;
            }
        }
        return nearest;
    }

    private void walkToSiteDrop(BotPlayer body) {
        ItemEntity drop = nearestSiteDrop(body);
        if (drop == null) return;
        BotNavigator nav = nav(body);
        if (drop.distanceToSqr(body) > 6.25) {
            if (nav.state() != BotNavigator.State.WORKING) nav.goTo(drop.blockPosition(), 1);
        } else {
            if (nav.state() == BotNavigator.State.WORKING) stopOurCommand(nav);
            body.input().lookAt(drop.position());
            body.input().forward = 0.5F;
        }
    }

    private void forgetSite(BotPlayer body) {
        if (site == null) return;
        BotMemory.of(body.getUUID()).forgetPlaced(dimension, site);
        site = null;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Ending
    // ---------------------------------------------------------------------------------------------------------

    /**
     * Every exit goes through here. A job site the task put down for a villager that showed the offer the goal asks
     * for stays where it is and stays remembered (the villager keeps the offer only while it keeps the job); one put
     * down for a villager that never showed it is taken up again before the task's answer is given.
     */
    private TickResult tidy(BotPlayer body, TickResult answer) {
        close(body);
        if (site != null && !siteEarned) {
            pending = answer; // the answer waits until the site is up
            phase = Phase.TIDYING;
            phaseTicks = 0;
            return TickResult.RUNNING;
        }
        release(body);
        return answer;
    }

    /** The take-up a tidy step asked for is done (or gave up): the pending answer is the task's answer. */
    private TickResult tidyTick(BotPlayer body) {
        if (site != null) breakSite(body, PROFESSION_WAIT_TICKS);
        if (site != null && phaseTicks <= PROFESSION_WAIT_TICKS) return TickResult.RUNNING;
        TickResult answer = pending == null ? TickResult.COMPLETED : pending;
        pending = null;
        release(body);
        return answer;
    }

    /** Stops the navigator when what runs is this task's own command; the reflex layer's is not the task's to stop. */
    private void stopOurCommand(BotNavigator nav) {
        if (!nav.runsCommandOfAnother(this)) nav.stop();
    }

    @Override
    public boolean waiting() {
        // The game's own timing: a trade is the window's click, and a profession change is the villager's own brain.
        return phase == Phase.TRADING || phase == Phase.WAITING_JOB || phase == Phase.WAITING_RESET;
    }

    @Override
    protected void letGo(BotPlayer body) {
        close(body);
        body.input().reset();
    }

    /**
     * The village the game answered with, so a task that takes this node up again does not ask a second time (the
     * call holds the server thread). A hint only: the next task walks there and looks for itself.
     */
    @Override
    public Map<String, String> checkpoint() {
        Map<String, String> out = new LinkedHashMap<>();
        if (villageAt != null) {
            out.put("dim", dimension);
            out.put("village", corePos(villageAt).encode());
        }
        return out;
    }

    /** What the task is doing now, for the status line. */
    @Override
    public String progress() {
        String what = switch (phase) {
            case LOOKING -> "looking for a villager of the " + profession.replace("minecraft:", "");
            case WALKING -> villager != null
                ? "walking to a villager at " + villager.blockPosition().getX() + " " + villager.blockPosition().getY()
                    + " " + villager.blockPosition().getZ()
                : "looking for a villager";
            case INSPECTING -> "looking at a villager's offers";
            case TRADING -> "paying a " + profession.replace("minecraft:", "") + " for "
                + itemKey.id().replace("minecraft:", "");
            case PLACING, WAITING_JOB, TAKING_SITE, WAITING_RESET -> "rerolling a "
                + profession.replace("minecraft:", "") + " (roll " + rolls + " of " + MAX_ROLLS + ")";
            case VILLAGE -> "walking to " + walkToName;
            case EXPLORING -> "exploring for a villager";
            case TIDYING -> "taking up the job site it put down";
        };
        return what + "; holds " + lastHeld + " of " + target + " " + itemKey.id().replace("minecraft:", "");
    }
}
