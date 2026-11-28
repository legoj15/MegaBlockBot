package com.legoj15.megablockbot.game.task;

import com.legoj15.megablockbot.MegaBlockBot;
import com.legoj15.megablockbot.core.ItemKey;
import com.legoj15.megablockbot.core.goal.Goal;
import com.legoj15.megablockbot.core.plan.NodeSpec;
import com.legoj15.megablockbot.core.task.Failure;
import com.legoj15.megablockbot.core.task.TickResult;
import com.legoj15.megablockbot.game.body.BotPlayer;
import com.legoj15.megablockbot.game.nav.BotNavigator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * {@code kill}: fight living entities of the leaf's type until the body holds the goal's count of the item (design 4.3,
 * Kill mob: leather from a cow, string from a spider). The count is the item's, not the mob's: a cow drops more than
 * leather, and what this task is after is the leaf's item.
 *
 * <p>Parameters from the planner: {@code item}, {@code count} (how many more, used only when the goal is not a
 * {@link Goal.HaveItem} of the item: the goal's count is absolute and wins), {@code entity} (a registry id) and
 * {@code playerKillOnly}. A drop that needs a player kill is satisfied by this body's own kill: the body is a player,
 * so nothing has to be done about that flag. The leaf's source id rides along in {@code NoSource} so a replan excludes
 * exactly that source.
 *
 * <p>The task:
 * <ol>
 *   <li>is done at once when the body already holds the count, before it touches anything;</li>
 *   <li>fails {@code NoRoom} when carried storage cannot take one more of the item, before it starts and again after a
 *       kill's drops are gathered: no mob is fought for a drop that would be lost;</li>
 *   <li>never targets a mob with a custom name, a tamed or leashed animal, or a baby: those belong to someone or drop
 *       nothing. Nearest first, within {@link #SEARCH_RADIUS} blocks and loaded (a mob behind a hedge is a target like
 *       any other; the navigator says how to get there);</li>
 *   <li>follows the target through the navigator ({@link BotNavigator#follow}) until it is within reach, and follows it
 *       again when it moves off, then fights as a player does: the best weapon the body carries brought to the main
 *       hand (the reflex layer's own choice, {@link Weapons}; fists when none), facing the mob, a swing through
 *       {@code Player.attack} only once the attack cooldown has recovered. Defence stays the reflex layer's: this task
 *       raises no shield and retreats from nothing, and a hold pauses it like any task;</li>
 *   <li>after a kill, picks up what dropped: it waits and walks to the drops of the leaf's item that lie within a few
 *       blocks of where the mob died, as {@code recover_drops} walks to drops;</li>
 *   <li>sets a target it cannot get at aside (like an unreachable block) and takes another -- a follow command never
 *       fails for want of a path, so the task watches the chase itself, and a quarry neither closer nor within reach
 *       for {@link #CHASE_STALL_TICKS} ticks is one it cannot get at. When no target is known it explores for a bounded
 *       number of rounds, then fails {@code NoSource} with the leaf's item and source id, so the executor excludes the
 *       source and the next plan gathers the item another way. A round is a look, a stretch of exploring, and another
 *       look: an exploring command that fails at once (a sealed pen has nowhere unexplored to walk to) has looked at
 *       nothing, so it ends the round and never the search -- one tick is not a search, and a mob may walk into range
 *       a moment later.</li>
 * </ol>
 */
public final class KillTask extends BodyTask {
    /**
     * How far from the body a target is looked for. Design 3.2's threat range is 16; a kill task goes looking for its
     * quarry rather than waiting for it to come, and a mob within twice that is one the body can still see coming.
     */
    public static final double SEARCH_RADIUS = 24.0;
    /** The navigator keeps the body within this many blocks of the target it follows: inside the reach of an attack. */
    private static final int FOLLOW_DISTANCE = 2;
    /** How far from where the mob died the drops of the leaf's item are walked to. */
    private static final double LOOT_RADIUS = 8.0;
    /**
     * A swing is made once the attack cooldown has recovered this far. The reflex layer's own number
     * ({@code ReflexConstants.ATTACK_READY}); kept apart, so the task layer does not reach into the reflex layer's.
     */
    private static final float ATTACK_READY = 0.95F;
    /**
     * Ticks after a quarry neither gets closer nor is hit before it is set aside. A follow command never fails for
     * want of a path (it keeps trying), so the task watches the chase itself, with the navigator's own stall bound
     * for one block ({@code BotNavigator.TARGET_STALL_TICKS}): about 85 blocks of walking.
     */
    private static final int CHASE_STALL_TICKS = BotNavigator.TARGET_STALL_TICKS;
    /**
     * Within this many blocks of the quarry the body closes on it by its own steps. A follow command holds the body at
     * its follow radius, which is where the engine is content to stand -- not the same thing as the reach an attack
     * needs -- so the last stretch is walked as the reflex layer walks it ({@code Steer.walkToward}), and the fight
     * begins on a quarry the body can hit.
     */
    private static final double CLOSE_ON = 4.0;
    /**
     * A search step takes at least this many ticks even when the exploring command failed at once (a sealed pen has
     * nowhere unexplored to walk to). One tick is not a search: a look says what stands there now, and a mob may walk
     * into range -- or come into the world -- a moment later.
     */
    private static final int MIN_STEP_TICKS = 20;
    /**
     * Ticks between looks for a quarry while an exploring step walks. A second: the body covers about four blocks,
     * a sixth of the search radius, so a herd the walk passes is seen while it is still near. A look only at the end
     * of a step would be one in over a hundred blocks of walking.
     */
    private static final int LOOK_EVERY_TICKS = 20;
    /** Getting this much closer to a quarry counts as a gain; less is the body swaying about. */
    private static final double CLOSER_BY = 0.5;
    /** The most set-aside quarries a checkpoint keeps. */
    private static final int MAX_SAVED_ASIDE = 16;

    /**
     * How hard the task looks for a target when none is known: one stretch of exploring followed by another look, and
     * the whole search gives up after {@code maxAttempts} of them. As the mine task's search bounds, without a height
     * to dig to first.
     *
     * @param stepTicks   ticks an exploring step lasts
     * @param maxAttempts steps before the search gives up
     */
    public record Search(int stepTicks, int maxAttempts) {
        /** 30 seconds per step, twelve steps: as far as the mine task's default search walks. */
        public static final Search DEFAULT = new Search(600, 12);
    }

    private enum Phase { HUNTING, FIGHTING, LOOTING, EXPLORING }

    private final Search search;
    private final String sourceId;
    private final ItemKey itemKey;
    private final String entityLabel;
    private final int goalCount;
    private final int moreCount;

    // From an earlier task for the same node: hints for where to look, checked against the world before use.
    private final String hintDimension;
    private final int hintAttempts;
    private final Set<UUID> hintAside;

    private Item item;
    private EntityType<?> type;
    private String dimension = "";
    private int target;
    private boolean alreadyDone;
    private Phase phase = Phase.HUNTING;
    /** Quarries the task will not go after again (it could not get at them), kept for its life and its checkpoint. */
    private final Set<UUID> setAside = new HashSet<>();
    private int attempts;
    /** How many quarries the last look left alone for standing inside a live danger zone (issue 29). */
    private int zonedQuarries;
    @Nullable
    private Mob quarry;
    /** The serial of the command this task gave last, or -1: how it tells its own command's fate from anybody else's. */
    private long serial = -1;
    private int stepTicks;
    /** Where the mob it killed fell: drops are looked for around it. */
    @Nullable
    private BlockPos fellAt;
    /** The walk to what a kill dropped, shared with the harvest task. */
    private final DropPickup pickup = new DropPickup();
    private boolean fighting;
    /** The chase's own watch: how close the body has come to the quarry it is after, and for how long none of that. */
    @Nullable
    private UUID chasing;
    private double bestDistance = Double.MAX_VALUE;
    private int noProgress;
    /** The held count as of the last tick: what the status line says while the task runs. */
    private int lastHeld;

    public KillTask(NodeSpec leaf, Map<String, String> hints) {
        this(leaf, hints, Search.DEFAULT);
    }

    public KillTask(NodeSpec leaf, Map<String, String> hints, Search search) {
        this.search = search;
        this.sourceId = leaf.source();
        this.itemKey = ItemKey.of(required(leaf, "item"));
        this.entityLabel = required(leaf, "entity");
        this.goalCount = leaf.goal() instanceof Goal.HaveItem g && g.key().equals(itemKey) ? g.count() : -1;
        this.moreCount = intParam(leaf.params(), "count", 1);
        // "playerKillOnly" says a drop needs the killer to be a player; this body is one, so its kills satisfy it.

        this.hintDimension = hints.getOrDefault("dim", "");
        this.hintAttempts = parseInt(hints.get("attempts"));
        this.hintAside = new HashSet<>();
        for (String text : hints.getOrDefault("aside", "").split(";")) {
            try {
                hintAside.add(UUID.fromString(text.trim()));
            } catch (IllegalArgumentException e) {
                // A checkpoint that does not validate is dropped, not trusted.
            }
        }
    }

    private static int parseInt(@Nullable String text) {
        try {
            return text == null ? 0 : Math.max(0, Integer.parseInt(text.trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Start
    // ---------------------------------------------------------------------------------------------------------

    @Override
    protected void begin(BotPlayer body) {
        dimension = dimensionOf(body);
        ResourceLocation itemId = idOf(itemKey.id());
        item = itemId == null ? null : BuiltInRegistries.ITEM.getOptional(itemId).orElse(null);
        if (item == null) {
            fail(new Failure.NoSource(itemKey, sourceId, "'" + itemKey.id() + "' is not a registered item"));
            return;
        }
        ResourceLocation entityId = idOf(entityLabel);
        type = entityId == null ? null : BuiltInRegistries.ENTITY_TYPE.getOptional(entityId).orElse(null);
        if (type == null) {
            fail(new Failure.NoSource(itemKey, sourceId, "'" + entityLabel + "' is not a registered entity type"));
            return;
        }

        int held = held(body);
        target = goalCount > 0 ? goalCount : held + Math.max(1, moreCount);
        if (held >= target) {
            // Nothing to do, and nothing is touched: the first tick says so.
            alreadyDone = true;
            return;
        }
        if (MenuHand.roomFor(body, new ItemStack(item)) == 0) {
            // No mob is fought for a drop that would be lost: carried storage has no free slot and no stack of the
            // item with room, and a replan would plan the same step into the same full inventory.
            fail(new Failure.NoRoom(itemKey, "the inventory cannot take one more " + itemKey.id()
                + ": no free slot and no stack of it with room"));
            return;
        }
        if (hintDimension.equals(dimension)) {
            attempts = Math.min(hintAttempts, search.maxAttempts());
            // What an earlier task for this node could not get at is not tried again. The world is looked at all the
            // same, so a quarry that is gone costs nothing.
            setAside.addAll(hintAside);
        }
    }

    @Override
    protected void resumeWork(BotPlayer body) {
        // Everything is looked at again from the world: the quarry may be gone, the drops may be picked up, and the
        // count the body holds is counted anew.
        quarry = null;
        chasing = null;
        serial = -1;
        stepTicks = 0;
        phase = Phase.HUNTING;
    }

    private int held(BotPlayer body) {
        return body.getInventory().countItem(item);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Tick
    // ---------------------------------------------------------------------------------------------------------

    @Override
    protected TickResult onTick(BotPlayer body) {
        if (alreadyDone) return TickResult.COMPLETED;
        // The goal first, whatever got the items into the inventory (a mob this task killed, a drop picked up on the way).
        lastHeld = held(body);
        if (lastHeld >= target) {
            release(body);
            return TickResult.COMPLETED;
        }
        fighting = false; // only the tick that fights says it is waiting on one
        return switch (phase) {
            case HUNTING -> hunt(body);
            case FIGHTING -> fight(body);
            case LOOTING -> loot(body);
            case EXPLORING -> explore(body);
        };
    }

    private TickResult hunt(BotPlayer body) {
        BotNavigator nav = nav(body);
        // The kill first: a quarry that has just died is no longer one to fight, and asking that question first would
        // send the body after the next one with this one's drops left lying.
        if (quarry != null && quarry.isDeadOrDying()) return startLooting(body);
        if (quarry == null || !eligible(quarry)) {
            quarry = findQuarry(body);
            serial = -1;
        }
        if (quarry == null) return nextStep(body);
        if (nav.state() == BotNavigator.State.FAILED && nav.serial() == serial) {
            // The body could not get to that one: it is set aside, as an unreachable block is, and another is taken.
            setAside.add(quarry.getUUID());
            MegaBlockBot.LOGGER.info("{}: could not get at the {} at {}; set it aside", body.getGameProfile().getName(),
                describe(quarry), quarry.blockPosition().toShortString());
            quarry = null;
            chasing = null;
            return nextStep(body);
        }
        TickResult stalled = watchTheChase(body);
        if (stalled != null) return stalled;
        if (inReach(body, quarry)) {
            stopOurCommand(nav);
            phase = Phase.FIGHTING;
            return fight(body);
        }
        if (quarry.distanceTo(body) < CLOSE_ON) {
            // The follow command has brought the body near enough that a straight walk at the quarry is a route a
            // player would take: the body's own steps from here (the engine is stopped for them, or it would write
            // its own input over them in its tick), as the reflex layer closes on a mob it means to hit.
            stopOurCommand(nav);
            walkAt(body, quarry.getBoundingBox().getCenter());
            return TickResult.RUNNING;
        }
        if (nav.state() != BotNavigator.State.WORKING) {
            nav.follow(quarry, FOLLOW_DISTANCE);
            serial = nav.serial();
        }
        return TickResult.RUNNING;
    }

    /**
     * Whether the chase is going anywhere: it is while the body keeps getting closer to the quarry, or while it is
     * close enough to swing at it. When neither has happened for {@link #CHASE_STALL_TICKS} ticks the quarry is set
     * aside and another is taken -- a follow command never fails for want of a path (it keeps trying), so this watch
     * is what keeps a quarry behind a wall from holding the body for ever. Null while the chase goes on.
     */
    @Nullable
    private TickResult watchTheChase(BotPlayer body) {
        if (!quarry.getUUID().equals(chasing)) {
            chasing = quarry.getUUID();
            bestDistance = quarry.distanceTo(body);
            noProgress = 0;
            return null;
        }
        // A gain is measured against where the last gain left the body, not against the tick before: a walk covers
        // a fifth of a block a tick, which is never a gain on its own and is one every three ticks. Measured tick
        // to tick the watch was a plain timeout, and a body closing steadily on a far quarry gave it up.
        double distance = quarry.distanceTo(body);
        if (distance < bestDistance - CLOSER_BY) {
            bestDistance = distance;
            noProgress = 0;
            return null;
        }
        if (inReach(body, quarry)) {
            noProgress = 0;
            return null;
        }
        if (++noProgress <= CHASE_STALL_TICKS) return null;
        setAside.add(quarry.getUUID());
        MegaBlockBot.LOGGER.info("{}: no closer to the {} at {} in {} ticks; set it aside", body.getGameProfile().getName(),
            describe(quarry), quarry.blockPosition().toShortString(), CHASE_STALL_TICKS);
        quarry = null;
        chasing = null;
        return nextStep(body);
    }

    private TickResult fight(BotPlayer body) {
        if (quarry != null && quarry.isDeadOrDying()) return startLooting(body); // before the eligibility test, as in hunt
        if (quarry == null || !eligible(quarry)) {
            phase = Phase.HUNTING;
            return TickResult.RUNNING;
        }
        if (!inReach(body, quarry)) {
            // It moved away (a cow flees when hurt): after it again.
            phase = Phase.HUNTING;
            return TickResult.RUNNING;
        }
        noProgress = 0; // a quarry in reach and being fought is a chase that is going somewhere
        fighting = true;
        Vec3 aim = quarry.getBoundingBox().getCenter();
        body.input().lookAt(aim);
        body.lookAt(EntityAnchorArgument.Anchor.EYES, aim);
        stand(body);
        int weapon = Weapons.best(body);
        if (weapon >= 0 && Weapons.score(body.getMainHandItem()) < Weapons.score(body.getInventory().items.get(weapon))) {
            Weapons.toMainHand(body, weapon);
        }
        if (body.getAttackStrengthScale(0.5F) >= ATTACK_READY) {
            body.resetLastActionTime();
            // The player's own attack: damage, enchantments, knockback, sweeping and the tool's wear all happen in it.
            // The swing comes after it, as a client sends it: ServerPlayer.swing resets the attack cooldown, and a
            // swing first would make every attack a weak one.
            body.attack(quarry);
            body.swing(InteractionHand.MAIN_HAND);
        }
        return TickResult.RUNNING;
    }

    /** Picks up what the kill dropped: the drops of the leaf's item near where the mob fell ({@link DropPickup}). */
    private TickResult startLooting(BotPlayer body) {
        stopOurCommand(nav(body));
        fellAt = quarry != null ? quarry.blockPosition() : body.blockPosition();
        pickup.start();
        quarry = null;
        chasing = null;
        phase = Phase.LOOTING;
        return loot(body);
    }

    private TickResult loot(BotPlayer body) {
        if (fellAt != null && pickup.tick(this, body, drop -> drop.getItem().is(item), new AABB(fellAt).inflate(LOOT_RADIUS))) return TickResult.RUNNING;
        return lootDone(body);
    }

    /** The drops around the fall are taken or lost: on to the next quarry, unless there is no room for the item at all. */
    private TickResult lootDone(BotPlayer body) {
        pickup.rest();
        if (held(body) < target && MenuHand.roomFor(body, new ItemStack(item)) == 0) {
            release(body);
            return TickResult.failed(new Failure.NoRoom(itemKey, "the inventory cannot take one more " + itemKey.id()
                + ": no free slot and no stack of it with room"));
        }
        quarry = null;
        phase = Phase.HUNTING;
        return TickResult.RUNNING;
    }

    /** No quarry is known: look further, or say there is none to be found. */
    private TickResult nextStep(BotPlayer body) {
        if (++attempts > search.maxAttempts()) return giveUp(body);
        // The one line that settles why a look took no quarry: how many of the kind stood within the radius at all.
        // Zero where the herd plainly stands says the lookup or the timing; a count with none taken says the
        // eligibility test.
        MegaBlockBot.LOGGER.info("{}: no {} to get {} from ({} of them within {} blocks, {} set aside); exploring, look {} of {}",
            body.getGameProfile().getName(), entityLabel, itemKey.id(), stoodThere(body), (int) SEARCH_RADIUS, setAside.size(),
            attempts, search.maxAttempts());
        phase = Phase.EXPLORING;
        stepTicks = 0;
        nav(body).explore(body.blockPosition());
        return TickResult.RUNNING;
    }

    private TickResult explore(BotPlayer body) {
        stepTicks++;
        BotNavigator nav = nav(body);
        if (nav.state() == BotNavigator.State.FAILED) {
            // Nowhere further to look from here. That ends this step, not the whole search: an exploring command
            // that fails at once has looked at nothing, and a mob may walk into range or come into the world a
            // moment later, so the step stands its minimum and another look follows, until the search's own bound.
            // A failure that is not about the search at all ends the step with what it is (BodyTask.exploreFailure).
            TickResult notTheSource = exploreFailure(body, nav, entityLabel);
            if (notTheSource != null) return notTheSource;
            if (stepTicks < MIN_STEP_TICKS) return TickResult.RUNNING;
        } else if (stepTicks < search.stepTicks() && nav.state() == BotNavigator.State.WORKING) {
            // The walk goes on until the step is over or a look on the way finds a quarry to stop for.
            if (stepTicks % LOOK_EVERY_TICKS != 0 || findQuarry(body) == null) return TickResult.RUNNING;
        }
        // The step's own command ends with the step, whatever its state. The hunt that follows gives its follow
        // only when nothing of this task's is still working, and an explore left running would walk the body on
        // under it while the quarry the next look finds is set aside for never being approached.
        stopOurCommand(nav);
        phase = Phase.HUNTING;
        return TickResult.RUNNING;
    }

    /** How many mobs of the leaf's type stand within the search radius, eligible or not: what a look had to see. */
    private int stoodThere(BotPlayer body) {
        Vec3 at = body.position();
        return body.serverLevel().getEntitiesOfClass(Mob.class, new AABB(at, at).inflate(SEARCH_RADIUS),
            candidate -> candidate.getType() == type).size();
    }

    /**
     * The search is spent. Quarries were known and none could be got at: {@code NoSource} carrying the leaf's item and
     * source, saying how many were found; none was ever known: {@code NoSource} too, saying how far it looked. The
     * executor puts the source in the goal's exclusions, so the next plan gathers the item another way.
     */
    private TickResult giveUp(BotPlayer body) {
        release(body);
        String detail = setAside.isEmpty()
            ? String.format(Locale.ROOT, "found no %s to get %s from within %d blocks in %d attempts",
                entityLabel, itemKey.id(), (int) SEARCH_RADIUS, attempts)
            : String.format(Locale.ROOT, "found %d %s(s) and could not get at any of them in %d attempts",
                setAside.size(), entityLabel, attempts);
        if (zonedQuarries > 0) {
            detail += "; " + zonedQuarries + " of them stood inside a danger zone this body keeps out of";
        }
        MegaBlockBot.LOGGER.info("{}: gave up hunting {}: {}", body.getGameProfile().getName(), entityLabel, detail);
        return TickResult.failed(new Failure.NoSource(itemKey, sourceId, detail));
    }

    // ---------------------------------------------------------------------------------------------------------
    // The quarry
    // ---------------------------------------------------------------------------------------------------------

    /**
     * The nearest mob of the leaf's type the body may fight, or null. What is never fought: a mob with a custom name
     * (a name tag is somebody's mark, and it keeps the mob from despawning), a tamed or leashed animal (somebody's
     * pet), or a baby (it drops nothing). Anything set aside is skipped, and so is a mob that stands inside a live
     * danger zone (issue 29): where the body was driven off or killed is not walked back into for a kill.
     */
    @Nullable
    private Mob findQuarry(BotPlayer body) {
        ServerLevel level = body.serverLevel();
        Vec3 at = body.position();
        AABB box = new AABB(at, at).inflate(SEARCH_RADIUS);
        Mob nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        int zoned = 0;
        for (Mob mob : level.getEntitiesOfClass(Mob.class, box, candidate -> candidate.getType() == type)) {
            if (!eligible(mob)) continue;
            if (inDangerZone(body, mob.blockPosition())) {
                zoned++;
                continue;
            }
            double distance = mob.distanceToSqr(body);
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = mob;
            }
        }
        zonedQuarries = zoned;
        return nearest;
    }

    private boolean eligible(Mob mob) {
        return takeable(mob) && !setAside.contains(mob.getUUID());
    }

    /**
     * Whether a mob is one a player may take from — the kill task's rule, shared with the use-on task (a sheep
     * sheared, a cow milked, a fish caught): alive, and neither named (a name tag is somebody's mark, and it keeps the
     * mob from despawning), leashed nor tamed (somebody's pet), nor a baby (it drops nothing, and it is not ready to
     * be used on). What each task adds on top (the set-aside, a sheep's wool) is its own.
     */
    static boolean takeable(Mob mob) {
        if (mob.isRemoved() || !mob.isAlive() || mob.isDeadOrDying()) return false;
        if (mob.hasCustomName() || mob.isLeashed()) return false;
        if (mob instanceof TamableAnimal tame && tame.isTame()) return false;
        if (mob instanceof AgeableMob aged && aged.isBaby()) return false;
        return true;
    }

    private static boolean inReach(BotPlayer body, Mob mob) {
        return body.canInteractWithEntity(mob, 0.0);
    }

    /** Stops the command this task gave, and nobody else's: the reflex layer's is not this task's to stop. */
    private void stopOurCommand(BotNavigator nav) {
        if (!nav.runsCommandOfAnother(this)) nav.stop();
        serial = -1;
    }

    private static void stand(BotPlayer body) {
        body.input().forward = 0.0F;
        body.input().strafe = 0.0F;
        body.input().jump = false;
        body.input().sprint = false;
    }

    /**
     * Walks straight at a point while looking at it: the body's own steps, not the navigator's, and running, because
     * a quarry that has been hurt once flees about as fast as a player walks.
     */
    private static void walkAt(BotPlayer body, Vec3 at) {
        body.input().lookAt(at);
        body.lookAt(EntityAnchorArgument.Anchor.EYES, at);
        body.input().forward = 1.0F;
        body.input().strafe = 0.0F;
        body.input().sprint = true;
        body.input().jump = body.horizontalCollision && body.onGround();
    }

    private static String describe(@Nullable Mob mob) {
        if (mob != null) return mob.getType().getDescription().getString().toLowerCase(Locale.ROOT);
        return "mob";
    }

    private static String describe(EntityType<?> type) {
        return type.getDescription().getString().toLowerCase(Locale.ROOT);
    }

    // ---------------------------------------------------------------------------------------------------------

    /** The quarries this task will not go after again, by UUID. For tests and the status. */
    public Set<UUID> setAside() {
        return Set.copyOf(setAside);
    }

    @Override
    public boolean waiting() {
        // A fight is the game's timing (the cooldown, the mob's death) and a pickup is the game's too: position and
        // inventory show nothing while either goes on, and the stuck watchdog would cut a kill short.
        return fighting || pickup.waiting();
    }

    @Override
    protected void letGo(BotPlayer body) {
        fighting = false;
        pickup.rest();
        body.input().reset();
    }

    /**
     * The dimension, how many of its attempts the search has spent, and the quarries it could not get at, so a restart
     * does not look from scratch. Hints only; a restarted task checks them against the world.
     */
    @Override
    public Map<String, String> checkpoint() {
        Map<String, String> out = new LinkedHashMap<>();
        if (dimension.isEmpty()) return out;
        out.put("dim", dimension);
        out.put("attempts", Integer.toString(attempts));
        if (!setAside.isEmpty()) {
            StringBuilder aside = new StringBuilder();
            setAside.stream().limit(MAX_SAVED_ASIDE).forEach(id -> {
                if (aside.length() > 0) aside.append(';');
                aside.append(id);
            });
            out.put("aside", aside.toString());
        }
        return out;
    }

    /** What the task is doing now, for the status line: what it is after, and how many of the item it holds. */
    @Override
    public String progress() {
        if (type == null) return ""; // nothing is known before the task starts
        String what = switch (phase) {
            case HUNTING -> quarry != null
                ? "going to a " + describe(quarry) + " at " + quarry.blockPosition().getX() + " "
                    + quarry.blockPosition().getY() + " " + quarry.blockPosition().getZ()
                : "looking for a " + itemKey.id().replace("minecraft:", "") + " from a " + describe(type);
            case FIGHTING -> "fighting a " + describe(quarry);
            case LOOTING -> "picking up the " + itemKey.id().replace("minecraft:", "") + " that fell";
            case EXPLORING -> "exploring for a " + describe(type);
        };
        return what + "; holds " + lastHeld + " of " + target + " " + itemKey.id().replace("minecraft:", "");
    }
}
