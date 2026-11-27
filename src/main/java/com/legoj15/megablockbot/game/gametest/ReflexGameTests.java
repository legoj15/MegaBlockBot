package com.legoj15.megablockbot.game.gametest;

import com.legoj15.megablockbot.MegaBlockBot;
import com.legoj15.megablockbot.core.exec.ReflexHold;
import com.legoj15.megablockbot.core.goal.Goal;
import com.legoj15.megablockbot.core.plan.NodeSpec;
import com.legoj15.megablockbot.core.task.Task;
import com.legoj15.megablockbot.core.task.TaskState;
import com.legoj15.megablockbot.game.body.BodyException;
import com.legoj15.megablockbot.game.body.BodyManager;
import com.legoj15.megablockbot.game.body.BotBrain;
import com.legoj15.megablockbot.game.body.BotPlayer;
import com.legoj15.megablockbot.game.body.DeathRecord;
import com.legoj15.megablockbot.game.bot.ReflexSources;
import com.legoj15.megablockbot.game.nav.BotNavigator;
import com.legoj15.megablockbot.game.plan.BotMemory;
import com.legoj15.megablockbot.game.reflex.ReflexConstants;
import com.legoj15.megablockbot.game.reflex.ReflexLayer;
import com.legoj15.megablockbot.game.safety.BlockSafety;
import com.legoj15.megablockbot.game.task.RecoverDropsTask;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Pillager;
import net.minecraft.world.entity.monster.Skeleton;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.Vec3;

/**
 * The acceptance check of the reflex layer (design 3.2, issue 8): each test spawns a real {@link BotPlayer} in the test
 * arena with a test brain that ticks {@link ReflexLayer} the way the runtime will (and, where a test says so, a
 * stand-in "task" that walks the body, so that preemption has something to interrupt), puts the thing the reflex is for
 * next to it, and measures that the body gets through it acting only as a player can: it attacks through the
 * player's own attack (the sword wears), eats by using the item (it takes the time eating takes), places and breaks
 * blocks by clicking (the dirt it started with is back in the inventory in the morning), and never moves more than a
 * block in a tick.
 *
 * <p>The arena is closed with barrier blocks; the fight tests roof it too, so that nothing burns in the sun and no
 * body is under the open sky (the night reflex asks). The mobs are made with {@code EntityType.create} and not
 * through the spawn code, which would hand a zombie random armour, weapons and a baby's speed, and are removed at
 * the end. The tests that set the time of day put it back; each has a batch of its own, because tests of one batch run
 * side by side in one world, and so do the tests that break blocks through the navigator (the break rules are global
 * state that the task tests set) and the open-ground ones.
 *
 * <p>What a test asks about the layer is measured in the world (where the body is, what stands, what it holds, whether
 * it lives) or read from the layer's typed answers ({@link ReflexLayer#holderName}, {@link ReflexLayer#lastNightResult}),
 * never from the text of a cause or a status line. The one-tick move check lets the game's own moves through, the step
 * into a bed and the one out of it, and nothing else. The tests of the layer through the real runtime are in
 * {@link ReflexRuntimeGameTests}.
 */
public final class ReflexGameTests {
    private static final String RUN_ID = Integer.toString((int) (System.nanoTime() & 0xFFFFF), 36);
    private static final int FLOOR_Y = 1;
    /** The first height of the roof of a roofed room: above anything a body does in it. */
    private static final int ROOF_Y = FLOOR_Y + 7;
    /** The game's own moves of a body in a bed (into it, out of it): up to this far in a tick; any other move is capped at a block. */
    private static final double BED_STEP = 3.0;
    /** The daylight ticks a block that cannot be taken down is watched for, and the most holds it may cost in them. */
    private static final int BACKOFF_TICKS = 2400;
    private static final int BACKOFF_MAX_HOLDS = 5;

    private ReflexGameTests() {}

    // ---------------------------------------------------------------------------------------------------------
    // Mobs
    // ---------------------------------------------------------------------------------------------------------

    /** A zombie next to a body holding an iron sword: the zombie dies by the body's hand, the body lives, the hold was taken and let go. */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 520)
    public static void defendsAgainstAZombie(GameTestHelper helper) {
        room(helper, true);
        Run run = Run.start(helper, "zomb", new Vec3(8.5, FLOOR_Y + 1, 8.5), 520);
        BotPlayer body = run.body();
        // Out of the hotbar, so that "select the best weapon" is a real inventory move, and a shield for the off hand.
        body.getInventory().setItem(20, new ItemStack(Items.IRON_SWORD));
        body.getInventory().setItem(21, new ItemStack(Items.SHIELD));
        Zombie zombie = run.zombie(new Vec3(12.5, FLOOR_Y + 1, 8.5));
        run.each(500, () -> {
            if (zombie.isAlive() || run.layer().isHolding()) return;
            Watch watch = run.watch;
            helper.assertTrue(watch.holdsStarted >= 1 && watch.holdsEnded >= 1, "no hold was taken and released: " + watch.summary());
            helper.assertTrue(run.body().getHealth() > 0.0F, "the body died");
            ItemStack sword = find(run.body(), Items.IRON_SWORD);
            helper.assertTrue(sword != null && sword.getDamageValue() > 0, "the sword took no damage, so the kill did not go through the player's attack: " + sword);
            helper.assertTrue(zombie.getLastHurtByMob() == run.body(), "the zombie was not killed by the body: " + zombie.getLastHurtByMob());
            helper.assertTrue(watch.blockingTicks > 0, "a shield in the inventory was never raised and held: " + watch.summary());
            helper.assertTrue(watch.reflexes.contains("mobs"), "the mob reflex never held the body: " + watch.reflexes);
            MegaBlockBot.LOGGER.info("Reflex GameTest zombie with a sword: killed after {} ticks, health {}, sword damage {}, {}",
                run.ticks, run.body().getHealth(), sword.getDamageValue(), watch.summary());
            run.pass();
        });
    }

    /** The same with no weapon: it fights with its fists or retreats, and is alive at the end of the budget. */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 620)
    public static void fightsWithFistsOrRetreatsWithoutAWeapon(GameTestHelper helper) {
        room(helper, true);
        Run run = Run.start(helper, "fist", new Vec3(8.5, FLOOR_Y + 1, 8.5), 620);
        Zombie zombie = run.zombie(new Vec3(12.5, FLOOR_Y + 1, 8.5));
        float[] lowest = {Float.MAX_VALUE};
        run.each(600, () -> {
            lowest[0] = Math.min(lowest[0], run.body().getHealth());
            helper.assertTrue(run.body().getHealth() > 0.0F, "the body died");
            Watch watch = run.watch;
            boolean over = !zombie.isAlive() && !run.layer().isHolding();
            if (!over && run.ticks < 560) return;
            helper.assertTrue(watch.holdsStarted >= 1, "the reflex never took the body: " + watch.summary());
            MegaBlockBot.LOGGER.info("Reflex GameTest zombie, no weapon: zombie {}, after {} ticks health {} (lowest {}), {}",
                zombie.isAlive() ? "still alive" : "dead", run.ticks, run.body().getHealth(), lowest[0], watch.summary());
            run.pass();
        });
    }

    /**
     * A creeper comes for the body on open ground (nothing built: the arena's surroundings are the world's flat ground),
     * sword in the pack and no shield: the body runs, and the gap to the creeper widens by at least a block and a half from
     * its closest while the creeper is still harmless. This is the smallest check of "outside the blast when it goes
     * off" that a GameTest can make repeatable: only about a chunk around the body is loaded, so there is no room to run
     * for long, and a run that goes on ends with the body caught against the edge of what is loaded, as a run in a
     * closed room would (the next test covers being caught). The navigator is warmed up with a short walk first: its very
     * first path in a JVM takes about a second and a half, which is a cost of the test and not of the layer. A batch of
     * its own, so that no other arena stands next to it.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 360, batch = "reflex_open")
    public static void runsFromACreeperThatIsComingForIt(GameTestHelper helper) {
        Run run = Run.start(helper, "creep", new Vec3(8.5, 0.0, 8.5), 360);
        run.body().getInventory().setItem(20, new ItemStack(Items.IRON_SWORD));
        run.watch.walkTo(helper.absolutePos(new BlockPos(6, 0, 8)));
        Creeper[] creeper = {null};
        double[] closest = {Double.MAX_VALUE};
        int[] startTick = {0};
        run.each(340, () -> {
            BotPlayer body = run.body();
            helper.assertTrue(body.getHealth() >= body.getMaxHealth(), "the body was hurt; " + run.watch.summary());
            if (creeper[0] == null) {
                if (!run.watch.arrived) return;
                run.watch.walkTo(null);
                creeper[0] = run.creeper(helper.relativeVec(body.position()).add(6.0, 0.0, 0.0));
                return;
            }
            // The creeper has to pick the body as its target first; the layer takes the body when it does.
            if (run.watch.holdsStarted == 0) return;
            helper.assertTrue(!creeper[0].isRemoved() && creeper[0].isAlive(), "the creeper went off or died; " + run.watch.summary());
            double now = body.distanceTo(creeper[0]);
            if (startTick[0] == 0) startTick[0] = run.ticks;
            closest[0] = Math.min(closest[0], now);
            int held = run.ticks - startTick[0];
            if (now >= closest[0] + 1.5) {
                helper.assertTrue(run.watch.reflexes.contains("mobs"), "the gap widened without the mob reflex: " + run.watch.summary());
                MegaBlockBot.LOGGER.info("Reflex GameTest creeper: ran, the gap went from {} to {} blocks, {} ticks into the hold, {}", closest[0], now, held, run.watch.summary());
                run.pass();
                return;
            }
            helper.assertTrue(held <= 100, "the gap never widened by a block and a half in 100 ticks of the hold (closest " + closest[0] + ", now " + now + "); " + run.watch.summary());
        });
    }

    /**
     * A creeper in a closed room with nowhere to run, and a shield: whichever way it goes (the body braces and the blast is
     * blocked like any hit from the front, or the body kills the creeper before it goes off), the body is not hurt more
     * than a scratch.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 520)
    public static void isNotHurtByACreeperItCannotRunFrom(GameTestHelper helper) {
        room(helper, true);
        Run run = Run.start(helper, "brace", new Vec3(3.5, FLOOR_Y + 1, 8.5), 520);
        run.body().getInventory().setItem(20, new ItemStack(Items.IRON_SWORD));
        run.body().getInventory().setItem(21, new ItemStack(Items.SHIELD));
        Creeper creeper = run.creeper(new Vec3(9.5, FLOOR_Y + 1, 8.5));
        double[] lastDistance = {Double.MAX_VALUE};
        double[] closest = {Double.MAX_VALUE};
        boolean[] gone = {false};
        run.each(500, () -> {
            BotPlayer body = run.body();
            // A blast at two blocks does fifteen or more to a body that does nothing; a point that gets past the shield is a scratch.
            helper.assertTrue(body.getHealth() >= body.getMaxHealth() - 3.0F, "the body was hurt (health " + body.getHealth() + ") at distance "
                + lastDistance[0] + " from the creeper; " + run.watch.summary());
            if (!creeper.isRemoved() && creeper.isAlive()) {
                lastDistance[0] = body.distanceTo(creeper);
                closest[0] = Math.min(closest[0], lastDistance[0]);
                return;
            }
            if (!gone[0]) {
                gone[0] = true;
                MegaBlockBot.LOGGER.info("Reflex GameTest creeper: {} after {} ticks, the body {} blocks from it (closest {}), shield held {} ticks, {}",
                    creeper.isDeadOrDying() ? "killed" : "exploded", run.ticks, lastDistance[0], closest[0], run.watch.blockingTicks, run.watch.summary());
            }
            if (run.layer().isHolding()) return;
            helper.assertTrue(run.watch.holdsStarted >= 1, "the reflex never took the body");
            run.pass();
        });
    }

    // ---------------------------------------------------------------------------------------------------------
    // Hazards
    // ---------------------------------------------------------------------------------------------------------

    /** On fire, beside a pool: the body goes to the water, the fire goes out, and the body is alive. */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 320)
    public static void putsAFireOutInWater(GameTestHelper helper) {
        room(helper, false);
        for (int x = 11; x <= 13; x++) {
            for (int z = 7; z <= 9; z++) {
                helper.setBlock(x, FLOOR_Y - 1, z, Blocks.STONE);
                helper.setBlock(x, FLOOR_Y, z, Blocks.WATER);
            }
        }
        Run run = Run.start(helper, "fire", new Vec3(3.5, FLOOR_Y + 1, 8.5), 320);
        run.body().setRemainingFireTicks(400);
        boolean[] inWater = {false};
        run.each(300, () -> {
            BotPlayer body = run.body();
            helper.assertTrue(body.getHealth() > 0.0F, "the body died");
            if (body.isInWater()) inWater[0] = true;
            if (!inWater[0] || body.isOnFire() || run.layer().isHolding()) return;
            helper.assertTrue(run.watch.reflexes.contains("hazard"), "the hazard reflex never held the body: " + run.watch.summary());
            MegaBlockBot.LOGGER.info("Reflex GameTest fire: in the water and out after {} ticks, health {}, {}", run.ticks, body.getHealth(), run.watch.summary());
            run.pass();
        });
    }

    /** Submerged under two blocks of water with the air low: the body surfaces, breathes again, and lets go. */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 320)
    public static void surfacesWhenTheAirRunsLow(GameTestHelper helper) {
        room(helper, false);
        // A pool two deep with a rim around it, so that the water stays where it was put.
        for (int x = 5; x <= 9; x++) {
            for (int z = 5; z <= 9; z++) {
                boolean rim = x == 5 || x == 9 || z == 5 || z == 9;
                for (int y = FLOOR_Y + 1; y <= FLOOR_Y + 2; y++) helper.setBlock(x, y, z, rim ? Blocks.STONE : Blocks.WATER);
            }
        }
        Run run = Run.start(helper, "air", new Vec3(7.5, FLOOR_Y + 1, 7.5), 320);
        run.body().setAirSupply(60);
        int[] lowest = {60};
        run.each(300, () -> {
            BotPlayer body = run.body();
            // The fluid state of a new body is read on its first ticks.
            if (run.ticks < 3) return;
            if (run.ticks == 3) helper.assertTrue(body.isEyeInFluid(FluidTags.WATER), "setup: the body is not under water");
            helper.assertTrue(body.getHealth() >= body.getMaxHealth() - 2.0F, "the body was drowning: health " + body.getHealth() + ", air " + body.getAirSupply());
            lowest[0] = Math.min(lowest[0], body.getAirSupply());
            if (body.isEyeInFluid(FluidTags.WATER) || run.layer().isHolding()) return;
            helper.assertTrue(body.getAirSupply() >= ReflexConstants.AIR_RECOVERED, "let go with the air at " + body.getAirSupply());
            helper.assertTrue(run.watch.reflexes.contains("hazard"), "the hazard reflex never held the body: " + run.watch.summary());
            MegaBlockBot.LOGGER.info("Reflex GameTest drowning: surfaced after {} ticks, air low point {}, air now {}, {}", run.ticks, lowest[0],
                body.getAirSupply(), run.watch.summary());
            run.pass();
        });
    }

    // ---------------------------------------------------------------------------------------------------------
    // Food
    // ---------------------------------------------------------------------------------------------------------

    /** Hunger at the threshold with bread in the inventory: the bread is eaten, the food goes up, and eating takes the time it takes. */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 320)
    public static void eatsBreadAtTheHungerThreshold(GameTestHelper helper) {
        room(helper, true);
        Run run = Run.start(helper, "eat", new Vec3(8.5, FLOOR_Y + 1, 8.5), 320);
        BotPlayer start = run.body();
        start.getFoodData().setFoodLevel(ReflexConstants.EAT_THRESHOLD);
        start.getInventory().setItem(22, new ItemStack(Items.BREAD, 5));
        int[] chewing = {0};
        int[] longestBite = {0};
        int[] bite = {0};
        run.each(300, () -> {
            BotPlayer body = run.body();
            if (body.isUsingItem() && body.getUseItem().has(DataComponents.FOOD)) {
                chewing[0]++;
                bite[0]++;
                longestBite[0] = Math.max(longestBite[0], bite[0]);
                helper.assertTrue(body.getFoodData().getFoodLevel() <= ReflexConstants.EAT_THRESHOLD, "the food went up before the bite was done");
            } else {
                bite[0] = 0;
            }
            if (run.layer().isHolding() || body.getFoodData().getFoodLevel() <= ReflexConstants.EAT_THRESHOLD) return;
            int bread = body.getInventory().countItem(Items.BREAD);
            helper.assertTrue(bread < 5, "the bread count did not go down: " + bread);
            helper.assertTrue(body.getFoodData().getFoodLevel() >= ReflexConstants.EAT_UNTIL, "food is " + body.getFoodData().getFoodLevel());
            // Bread takes 32 ticks to eat: one tick would be a stack shrunk by hand.
            helper.assertTrue(longestBite[0] >= 30, "the longest bite was " + longestBite[0] + " ticks; " + run.watch.summary());
            helper.assertTrue(run.watch.reflexes.contains("vitals"), "the vitals reflex never held the body: " + run.watch.summary());
            MegaBlockBot.LOGGER.info("Reflex GameTest eating: {} bread left of 5, food {}, bites took up to {} ticks, {} ticks in all, {}",
                bread, body.getFoodData().getFoodLevel(), longestBite[0], run.ticks, run.watch.summary());
            run.pass();
        });
    }

    // ---------------------------------------------------------------------------------------------------------
    // Preemption
    // ---------------------------------------------------------------------------------------------------------

    /**
     * A stand-in task walks the body across the room; a zombie appears. The walking stops while the reflex holds the
     * body (the navigator runs no command during the fight), the hold is let go of afterwards, and the stand-in, which
     * the test brain ticks only when no hold is on, picks the walk up again and arrives.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 820)
    public static void aFightPausesAWalkingTaskAndGivesItBack(GameTestHelper helper) {
        room(helper, true);
        Run run = Run.start(helper, "walk", new Vec3(1.5, FLOOR_Y + 1, 8.5), 820);
        run.body().getInventory().setItem(20, new ItemStack(Items.IRON_SWORD));
        BlockPos goal = helper.absolutePos(new BlockPos(14, FLOOR_Y + 1, 8));
        run.watch.walkTo(goal);
        Zombie[] zombie = {null};
        run.each(800, () -> {
            Watch watch = run.watch;
            if (zombie[0] == null) {
                // Mid-route, a few blocks ahead of the body and off to the side.
                if (run.ticks == 25) zombie[0] = run.zombie(helper.relativeVec(run.body().position()).add(4.0, 0.0, 3.0));
                return;
            }
            if (watch.heldTicks > 0) {
                helper.assertTrue(!watch.navigatorRanWhileHeld, "the stand-in's navigator command went on during the hold");
            }
            if (zombie[0].isAlive() || run.layer().isHolding() || !watch.arrived) return;
            helper.assertTrue(watch.holdsStarted >= 1 && watch.holdsEnded >= 1, "no hold was taken and released: " + watch.summary());
            helper.assertTrue(watch.walkStarts >= 2, "the stand-in was never started again after the hold: " + watch.summary());
            helper.assertTrue(run.body().blockPosition().equals(goal) || run.body().blockPosition().distSqr(goal) <= 2, "the body is at "
                + run.body().blockPosition() + ", not at the goal " + goal);
            MegaBlockBot.LOGGER.info("Reflex GameTest preemption: held {} ticks, walk started {} times, arrived at tick {}", watch.heldTicks, watch.walkStarts, run.ticks);
            run.pass();
        });
    }

    // ---------------------------------------------------------------------------------------------------------
    // Night
    // ---------------------------------------------------------------------------------------------------------

    /**
     * Dusk, dirt in the inventory, the SHELTER policy: the body closes itself in (no air path from the room to its
     * cells, and a zombie that comes for it cannot touch it), and in the morning the shelter blocks are gone and the
     * dirt is back in the inventory. The time of day is set by the test and put back at the end.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 1700, batch = "reflex_dusk")
    public static void closesItselfInAtDuskAndOpensAtMorning(GameTestHelper helper) {
        room(helper, false);
        Run run = Run.start(helper, "shel", new Vec3(7.5, FLOOR_Y + 1, 7.5), 1700);
        run.setDayTime(13000L);
        BotPlayer body = run.body();
        helper.assertTrue(run.layer().nightPolicy() == ReflexConstants.NightPolicy.SHELTER, "setup: the default policy is " + run.layer().nightPolicy());
        body.getInventory().setItem(20, new ItemStack(Items.DIRT, 20));
        int[] phase = {0};
        int[] phaseStart = {0};
        Zombie[] zombie = {null};
        run.each(1650, () -> {
            BotPlayer now = run.body();
            ServerLevel level = helper.getLevel();
            BlockPos cell = BlockPos.containing(helper.relativeVec(now.position()));
            switch (phase[0]) {
                case 0 -> {
                    // Waiting for the walls to close.
                    if (!enclosed(helper, cell, new BlockPos(13, FLOOR_Y + 1, 13))) return;
                    MegaBlockBot.LOGGER.info("Reflex GameTest shelter: closed after {} ticks with {} dirt placed, {}", run.ticks,
                        20 - now.getInventory().countItem(Items.DIRT), run.watch.summary());
                    helper.assertTrue(countBlock(helper, Blocks.DIRT) >= 8, "closed with only " + countBlock(helper, Blocks.DIRT) + " dirt blocks");
                    zombie[0] = run.zombie(new Vec3(13.5, FLOOR_Y + 1, 13.5));
                    phase[0] = 1;
                    phaseStart[0] = run.ticks;
                }
                case 1 -> {
                    // A zombie outside: it may stand at the wall, and may not reach the body.
                    helper.assertTrue(now.getHealth() >= now.getMaxHealth(), "the body was hurt in its shelter: " + now.getHealth());
                    helper.assertTrue(enclosed(helper, cell, new BlockPos(13, FLOOR_Y + 1, 13)), "the shelter has a way in");
                    helper.assertTrue(zombie[0].distanceTo(now) >= 1.0, "the zombie is inside the shelter");
                    if (run.ticks - phaseStart[0] < 150) return;
                    zombie[0].discard();
                    level.setDayTime(24000L + 1000L);
                    phase[0] = 2;
                }
                default -> {
                    // Morning: the shelter comes down and the dirt comes back.
                    if (run.layer().isHolding()) return;
                    helper.assertTrue(countBlock(helper, Blocks.DIRT) == 0, countBlock(helper, Blocks.DIRT) + " dirt blocks are still standing");
                    int dirt = now.getInventory().countItem(Items.DIRT);
                    helper.assertTrue(dirt == 20, "the body carries " + dirt + " of its 20 dirt");
                    MegaBlockBot.LOGGER.info("Reflex GameTest shelter: down and the 20 dirt back after {} ticks, {}", run.ticks, run.watch.summary());
                    run.pass();
                }
            }
        });
    }

    /**
     * Dusk, dirt, and a hole in the floor under the cell east of the body, which is where one of its walls would have to
     * stand: this spot will not do. The body walks to one that will and closes itself in there, a block or more away.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 900, batch = "reflex_dusk_spot")
    public static void walksToABetterSpotWhenItsOwnWillNotDo(GameTestHelper helper) {
        room(helper, false);
        helper.setBlock(8, FLOOR_Y, 7, Blocks.AIR);
        Run run = Run.start(helper, "spot", new Vec3(7.5, FLOOR_Y + 1, 7.5), 900);
        run.setDayTime(13000L);
        run.body().getInventory().setItem(20, new ItemStack(Items.DIRT, 20));
        BlockPos start = new BlockPos(7, FLOOR_Y + 1, 7);
        run.each(850, () -> {
            BotPlayer now = run.body();
            BlockPos cell = BlockPos.containing(helper.relativeVec(now.position()));
            if (!enclosed(helper, cell, new BlockPos(13, FLOOR_Y + 1, 13))) return;
            helper.assertTrue(!cell.equals(start), "it built at the spot that would not do: " + run.watch.summary());
            helper.assertTrue(countBlock(helper, Blocks.DIRT) >= 8, "closed with only " + countBlock(helper, Blocks.DIRT) + " dirt blocks");
            MegaBlockBot.LOGGER.info("Reflex GameTest spot: closed in at {} (it stood at {}) after {} ticks, {}", cell, start, run.ticks, run.watch.summary());
            run.pass();
        });
    }

    /** The same dusk with nothing to build with: the layer takes no hold for it and the body is left to keep working. */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 260, batch = "reflex_dusk_no_blocks")
    public static void keepsWorkingAtDuskWithNothingToBuildWith(GameTestHelper helper) {
        room(helper, false);
        Run run = Run.start(helper, "nob", new Vec3(7.5, FLOOR_Y + 1, 7.5), 260);
        run.setDayTime(13000L);
        run.each(200, () -> {
            helper.assertTrue(!run.layer().isHolding() && run.watch.holdsStarted == 0, "the layer took the body at night with nothing to build with: " + run.watch.summary());
            if (run.ticks < 150) return;
            run.pass();
        });
    }

    /**
     * The BED policy: at night, with a bed this body placed within reach, the body walks to it and sleeps in it instead
     * of building the shelter it has the dirt for.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 760, batch = "reflex_night_bed")
    public static void sleepsInItsOwnBedWithTheBedPolicy(GameTestHelper helper) {
        room(helper, false);
        Run run = Run.start(helper, "bed", new Vec3(3.5, FLOOR_Y + 1, 7.5), 760);
        run.setDayTime(18000L);
        BotPlayer body = run.body();
        run.layer().setNightPolicy(ReflexConstants.NightPolicy.BED);
        body.getInventory().setItem(20, new ItemStack(Items.DIRT, 20));
        placeOwnedBed(helper, body, new BlockPos(10, FLOOR_Y + 1, 7));
        run.each(740, () -> {
            BotPlayer now = run.body();
            if (!now.isSleeping()) return;
            helper.assertTrue(now.getInventory().countItem(Items.DIRT) == 20, "it built a shelter though it had a bed");
            helper.assertTrue(run.watch.reflexes.contains("night"), "the night reflex never held the body: " + run.watch.summary());
            MegaBlockBot.LOGGER.info("Reflex GameTest bed: asleep after {} ticks, {}", run.ticks, run.watch.summary());
            run.pass();
        });
    }

    /**
     * Inside a block (what a fall of gravel does to a body in a two-high tunnel): the body breaks the block its eyes are
     * in, with the pickaxe it carries, and breathes again. A block over a standing body's head only makes it crawl, as it
     * does a player, so the test fills both of its cells.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 320)
    public static void breaksOutOfABlockItIsStuckIn(GameTestHelper helper) {
        room(helper, true);
        Run run = Run.start(helper, "wall", new Vec3(7.5, FLOOR_Y + 1, 7.5), 320);
        run.body().getInventory().setItem(20, new ItemStack(Items.IRON_PICKAXE));
        BlockPos feet = new BlockPos(7, FLOOR_Y + 1, 7);
        BlockPos head = feet.above();
        boolean[] stuck = {false};
        run.each(300, () -> {
            BotPlayer body = run.body();
            if (run.ticks == 3) {
                helper.setBlock(feet, Blocks.STONE);
                helper.setBlock(head, Blocks.STONE);
                return;
            }
            if (run.ticks < 5) return;
            helper.assertTrue(body.getHealth() > 0.0F, "the body died in the wall");
            if (body.isInWall()) stuck[0] = true;
            if (!stuck[0] || body.isInWall() || run.layer().isHolding()) return;
            helper.assertTrue(helper.getBlockState(feet).isAir() || helper.getBlockState(head).isAir(), "out of the wall but nothing was broken");
            helper.assertTrue(run.watch.reflexes.contains("hazard"), "the hazard reflex never held the body: " + run.watch.summary());
            MegaBlockBot.LOGGER.info("Reflex GameTest suffocation: out after {} ticks, health {}, {}", run.ticks, body.getHealth(), run.watch.summary());
            run.pass();
        });
    }

    // ---------------------------------------------------------------------------------------------------------
    // The fix round (issue 8, round two): what each of these protects is on its test
    // ---------------------------------------------------------------------------------------------------------

    /**
     * A body that dies while a reflex holds it is respawned (a new instance, the same navigator): its navigator must not
     * be left refusing every break because the hold was dropped instead of finished. Protects: a body that has met a
     * fight, or a meal, and died in it can still mine for the rest of the session. The test kills the body while it
     * eats, puts the respawned one back in the arena (the respawn is at the world's spawn: setup), and sends it to mine a
     * block. Its own batch, because the break rules are global state that the task tests also set.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 700, batch = "reflex_filter")
    public static void aBodyThatDiesInAHoldCanBreakBlocksAfterItsRespawn(GameTestHelper helper) {
        room(helper, true);
        BlockPos hay = new BlockPos(11, FLOOR_Y + 1, 8);
        helper.setBlock(hay, Blocks.HAY_BLOCK);
        BlockPos hayAbsolute = helper.absolutePos(hay);
        Run run = Run.start(helper, "filt", new Vec3(8.5, FLOOR_Y + 1, 8.5), 700);
        run.expectReplacement();
        BotPlayer first = run.body();
        first.getFoodData().setFoodLevel(ReflexConstants.EAT_THRESHOLD);
        first.getInventory().setItem(20, new ItemStack(Items.BREAD, 5));
        int[] stage = {0};
        int[] waited = {0};
        run.each(650, () -> {
            BotPlayer body = run.body();
            ServerLevel level = helper.getLevel();
            switch (stage[0]) {
                case 0 -> {
                    if (!run.layer().holderName().equals("vitals")) return;
                    helper.assertTrue(!BotNavigator.of(body).breakFilter().mayBreak(level, hayAbsolute, level.getBlockState(hayAbsolute)),
                        "setup: while a reflex holds the body its navigator does not refuse breaks");
                    body.hurt(level.damageSources().genericKill(), 1000.0F);
                    stage[0] = 1;
                }
                case 1 -> {
                    if (body == first) return;
                    Vec3 at = helper.absoluteVec(new Vec3(8.5, FLOOR_Y + 1, 8.5));
                    body.teleportTo(level, at.x, at.y, at.z, 0.0F, 0.0F);
                    run.relocated();
                    stage[0] = 2;
                }
                case 2 -> {
                    // A few ticks of the new body's brain, which ticks the layer first.
                    if (++waited[0] < 5) return;
                    // What a path search sees: the filter asked on another thread as well as on this one.
                    BotNavigator nav = BotNavigator.of(body);
                    // The state is read here: a search thread must not touch the level (it would wait for this thread, which waits for it).
                    BlockState hayState = level.getBlockState(hayAbsolute);
                    boolean here = nav.breakFilter().mayBreak(level, hayAbsolute, hayState);
                    boolean there = CompletableFuture.supplyAsync(() -> nav.breakFilter().mayBreak(level, hayAbsolute, hayState)).join();
                    helper.assertTrue(here && there, "the navigator's break filter refuses a hay block (server thread " + here + ", search thread " + there
                        + "); block safety says " + BlockSafety.of(level.getServer()).mayBreak(body, level, hayAbsolute, hayState));
                    run.watch.mine(Blocks.HAY_BLOCK, Items.HAY_BLOCK);
                    stage[0] = 3;
                }
                default -> {
                    helper.assertTrue(run.watch.mineFailure.isEmpty(), "the mine command failed, the navigator refuses every break: " + run.watch.mineFailure);
                    if (!run.watch.mined) return;
                    helper.assertTrue(body.getInventory().countItem(Items.HAY_BLOCK) >= 1, "the command ended with no hay block in the inventory");
                    run.pass();
                }
            }
        });
    }

    /**
     * The hazard reflex is walking the body to water (a navigator command), the fire goes out and a zombie comes into
     * sight: the hazard lets go and the mob reflex takes the body over inside the same hold. Protects: a command left
     * running by the old holder walking the body off to the water in the middle of the fight. After the handover the
     * navigator is not working (the mob reflex starts no command against one zombie, with a sword and full health), and
     * the zombie is killed by the body.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 560)
    public static void aHandoverStopsTheNavigatorAndTheNewHolderFights(GameTestHelper helper) {
        room(helper, true);
        for (int x = 9; x <= 11; x++) {
            for (int z = 7; z <= 9; z++) {
                helper.setBlock(x, FLOOR_Y - 1, z, Blocks.STONE);
                helper.setBlock(x, FLOOR_Y, z, Blocks.WATER);
            }
        }
        Run run = Run.start(helper, "hand", new Vec3(1.5, FLOOR_Y + 1, 8.5), 560);
        run.body().getInventory().setItem(20, new ItemStack(Items.IRON_SWORD));
        run.body().setRemainingFireTicks(400);
        Zombie[] zombie = {null};
        int[] mobTicks = {0};
        run.each(540, () -> {
            BotPlayer body = run.body();
            BotNavigator nav = BotNavigator.of(body);
            if (zombie[0] == null) {
                if (!run.layer().holderName().equals("hazard") || nav.state() != BotNavigator.State.WORKING) return;
                // On its way to the water: the fire goes out and a zombie is there.
                body.setRemainingFireTicks(0);
                zombie[0] = run.zombie(helper.relativeVec(body.position()).add(2.5, 0.0, 0.0));
                return;
            }
            if (run.layer().holderName().equals("mobs")) {
                mobTicks[0]++;
                helper.assertTrue(nav.state() != BotNavigator.State.WORKING,
                    "the navigator still works for the old holder while the mob reflex holds the body: " + run.watch.summary());
            }
            if (zombie[0].isAlive() || run.layer().isHolding()) return;
            helper.assertTrue(run.watch.handedFromHazardToMobs, "the body was never handed from the hazard reflex to the mob reflex: " + run.watch.summary());
            helper.assertTrue(zombie[0].getLastHurtByMob() == body, "the zombie was not killed by the body: " + zombie[0].getLastHurtByMob());
            helper.assertTrue(mobTicks[0] > 0, "the mob reflex never held the body");
            run.pass();
        });
    }

    /**
     * A shooter the body cannot get to (a fence between them that it cannot cross and the shooter can be seen over) keeps
     * hurting it: the body must not stand in front of it with the shield down. Protects: the give-up on a chase that got
     * nowhere putting the mob on the ignore list while it was still hitting the body. The "skeleton" stands where it is
     * put and the test delivers its arrows (one point of damage every ten ticks while it can see the body, the way an
     * arrow that has a line of fire would), so the hits do not depend on an archer's aim. Cover stands on the body's side.
     * Passes when the body has been out of the skeleton's sight for forty ticks, alive.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 560)
    public static void aShooterItCannotReachIsRunFromOutOfItsSight(GameTestHelper helper) {
        room(helper, true);
        // A fence the whole width of the room: a body cannot cross it (it is a block and a half high), an eye sees over it.
        for (int z = 1; z <= 14; z++) helper.setBlock(7, FLOOR_Y + 1, z, Blocks.OAK_FENCE);
        // Cover on the body's side: a wall three high that the skeleton's line to the corner behind it runs through.
        for (int z = 9; z <= 12; z++) {
            for (int y = FLOOR_Y + 1; y <= FLOOR_Y + 3; y++) helper.setBlock(2, y, z, Blocks.STONE);
        }
        Run run = Run.start(helper, "snip", new Vec3(3.5, FLOOR_Y + 1, 7.5), 560);
        run.body().getInventory().setItem(20, new ItemStack(Items.IRON_SWORD));
        Skeleton skeleton = run.sniper(new Vec3(11.5, FLOOR_Y + 1, 7.5));
        int[] hidden = {0};
        int[] shots = {0};
        run.each(540, () -> {
            BotPlayer body = run.body();
            boolean seen = skeleton.hasLineOfSight(body);
            // A new body is invulnerable for its first three seconds: the shooting starts after that.
            if (run.ticks >= 70 && run.ticks % 10 == 0 && seen) {
                body.hurt(helper.getLevel().damageSources().mobAttack(skeleton), 1.0F);
                shots[0]++;
            }
            helper.assertTrue(body.getHealth() > 0.0F, "the body died in front of a shooter it could not reach: " + run.watch.summary());
            hidden[0] = seen ? 0 : hidden[0] + 1;
            if (hidden[0] < 40) return;
            helper.assertTrue(shots[0] >= 1, "setup: the skeleton never shot at the body, so nothing was tested");
            MegaBlockBot.LOGGER.info("Reflex GameTest shooter: out of sight at tick {} after {} shots, health {}, {}", run.ticks, shots[0], body.getHealth(), run.watch.summary());
            run.pass();
        });
    }

    /**
     * The night a bed skips is the night that was spent: a body that slept through night 0 (and woke in the morning of day
     * 1) is still bound by the BED policy when night 1 falls. Protects: the next night being switched off because the hold
     * ended after midnight and named the night the clock showed by then.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 1300, batch = "reflex_night_skip")
    public static void aNightThatABedSkipsDoesNotSwitchOffTheNextOne(GameTestHelper helper) {
        room(helper, false);
        Run run = Run.start(helper, "skip", new Vec3(8.5, FLOOR_Y + 1, 7.5), 1300);
        run.setDayTime(18000L);
        run.layer().setNightPolicy(ReflexConstants.NightPolicy.BED);
        run.body().getInventory().setItem(20, new ItemStack(Items.DIRT, 20));
        placeOwnedBed(helper, run.body(), new BlockPos(10, FLOOR_Y + 1, 7));
        int[] stage = {0};
        int[] holdsBefore = {0};
        run.each(1250, () -> {
            BotPlayer now = run.body();
            ReflexLayer layer = run.layer();
            ServerLevel level = helper.getLevel();
            switch (stage[0]) {
                case 0 -> {
                    if (!now.isSleeping()) return;
                    holdsBefore[0] = layer.holdsStarted();
                    // The morning of the next day: the bed has skipped the night, and the clock names day 1.
                    level.setDayTime(24000L + 1000L);
                    stage[0] = 1;
                }
                case 1 -> {
                    if (layer.isHolding()) return;
                    helper.assertTrue(layer.lastNightResult() == ReflexLayer.NightResult.SLEPT, "the night did not end as slept: " + layer.lastNightResult());
                    level.setDayTime(24000L + 18000L);
                    stage[0] = 2;
                }
                default -> {
                    if (layer.holdsStarted() <= holdsBefore[0]) return;
                    helper.assertTrue(layer.holderName().equals("night"), "the second night's hold is held by '" + layer.holderName() + "'");
                    run.pass();
                }
            }
        });
    }

    /**
     * The policy is changed from BED to SHELTER while the body walks to its bed: nothing was slept and the night is not
     * done, so the new policy gets its turn and the body builds the shelter. Protects: the change marking the night done
     * (and saying the body slept), which left it in the open at night. The outcome is the typed result, not its text.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 1200, batch = "reflex_night_policy")
    public static void aPolicyChangedOnTheWayToBedBuildsAShelterInstead(GameTestHelper helper) {
        room(helper, false);
        Run run = Run.start(helper, "pol", new Vec3(1.5, FLOOR_Y + 1, 7.5), 1200);
        run.setDayTime(13000L);
        run.layer().setNightPolicy(ReflexConstants.NightPolicy.BED);
        run.body().getInventory().setItem(20, new ItemStack(Items.DIRT, 20));
        placeOwnedBed(helper, run.body(), new BlockPos(12, FLOOR_Y + 1, 7));
        boolean[] switched = {false};
        run.each(1150, () -> {
            BotPlayer now = run.body();
            ReflexLayer layer = run.layer();
            if (!switched[0]) {
                if (!layer.holderName().equals("night") || BotNavigator.of(now).state() != BotNavigator.State.WORKING) return;
                layer.setNightPolicy(ReflexConstants.NightPolicy.SHELTER);
                switched[0] = true;
                return;
            }
            BlockPos cell = BlockPos.containing(helper.relativeVec(now.position()));
            if (!enclosed(helper, cell, new BlockPos(13, FLOOR_Y + 1, 13))) return;
            helper.assertTrue(!now.isSleeping(), "the body is asleep");
            helper.assertTrue(layer.lastNightResult() == ReflexLayer.NightResult.DID_NOT_SLEEP,
                "the walk to the bed ended as " + layer.lastNightResult() + ", the body never slept");
            run.pass();
        });
    }

    /**
     * Every block a wall of the shelter would stand on is a crafting table: the first thing a click finds to place
     * against is a block that is used, not built against. Protects: a click that is consumed without placing (the sneak
     * key not down yet) being counted as a placed block and leaving a menu open on the body. The body must never have a
     * menu open, the shelter closes, and the dirt that left the inventory is the dirt that stands. (A chest, which the
     * brief names, has no full face and is never an anchor; a crafting table is the usable block that is one.)
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 760, batch = "reflex_dusk_anchor")
    public static void buildsAgainstABlockThatCanBeUsed(GameTestHelper helper) {
        room(helper, false);
        for (int x = 6; x <= 8; x++) {
            for (int z = 6; z <= 8; z++) helper.setBlock(x, FLOOR_Y, z, Blocks.CRAFTING_TABLE);
        }
        Run run = Run.start(helper, "anch", new Vec3(7.5, FLOOR_Y + 1, 7.5), 760);
        run.setDayTime(13000L);
        run.body().getInventory().setItem(20, new ItemStack(Items.DIRT, 20));
        run.each(720, () -> {
            BotPlayer now = run.body();
            helper.assertTrue(now.containerMenu == now.inventoryMenu, "a click on a crafting table under a wall opened a menu on the body: " + now.containerMenu);
            BlockPos cell = BlockPos.containing(helper.relativeVec(now.position()));
            if (!enclosed(helper, cell, new BlockPos(13, FLOOR_Y + 1, 13))) return;
            int standing = countBlock(helper, Blocks.DIRT);
            helper.assertTrue(standing >= 8, "closed with only " + standing + " dirt blocks");
            int gone = 20 - now.getInventory().countItem(Items.DIRT);
            helper.assertTrue(gone == standing, gone + " dirt left the inventory and " + standing + " dirt blocks stand");
            run.pass();
        });
    }

    /**
     * A creeper that is after the body, seen from twelve blocks (past the blast), is not waited for once it comes: the body
     * keeps or opens the gap as soon as the creeper is seen closing. Protects: a body that carries on, or stands, while the
     * creeper walks up to the edge of its blast. From the moment the creeper starts walking the gap never falls below where it
     * started by more than two blocks, for as long as the body has room to run (twenty-five ticks: the arena is walled in,
     * so a longer chase ends with the body in a corner, which the test says nothing about). The two blocks are the creeper's
     * first few ticks of walking, in which the layer sees that it is coming (a creeper that only stands about at that distance
     * is watched, not run from: {@code ReflexRuntimeGameTests}); a body that did not react would lose five. The creeper is told
     * whom it is after; it is inside the arena, which is ticking: one put out in the open stands still and nothing is tested.
     * Open ground, in a batch of its own, like the other creeper run.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 520, batch = "reflex_open_far")
    public static void keepsTheGapFromACreeperSeenFarOff(GameTestHelper helper) {
        Run run = Run.start(helper, "farcr", new Vec3(3.5, 0.0, 8.5), 520);
        run.body().getInventory().setItem(20, new ItemStack(Items.IRON_SWORD));
        run.watch.walkTo(helper.absolutePos(new BlockPos(2, 0, 8)));
        Creeper[] creeper = {null};
        Vec3[] spawnedAt = {null};
        double[] start = {0.0};
        int[] walking = {-1};
        int[] since = {0};
        run.each(500, () -> {
            BotPlayer body = run.body();
            helper.assertTrue(body.getHealth() >= body.getMaxHealth(), "the body was hurt; " + run.watch.summary());
            if (creeper[0] == null) {
                if (!run.watch.arrived) return;
                // A body with no loaded ground behind it cannot run: that is waited for (a while), so that what is measured is
                // the body and not how much of the world has been loaded round it.
                if (run.ticks < 150 && !helper.getLevel().isLoaded(BlockPos.containing(body.position().add(-14.0, 0.0, 0.0)))) return;
                run.watch.walkTo(null);
                creeper[0] = run.creeper(helper.relativeVec(body.position()).add(12.0, 0.0, 0.0));
                // A creeper picks its target on its own timer and sometimes not at all at this range: this one is after the body.
                creeper[0].setTarget(body);
                spawnedAt[0] = creeper[0].position();
                start[0] = body.distanceTo(creeper[0]);
                return;
            }
            helper.assertTrue(!creeper[0].isRemoved() && creeper[0].isAlive(), "the creeper went off or died; " + run.watch.summary());
            double gap = body.distanceTo(creeper[0]);
            since[0]++;
            // The clock starts when the creeper does (its walk begins some ticks after it is made).
            if (walking[0] < 0 && creeper[0].position().distanceTo(spawnedAt[0]) > 0.3) walking[0] = since[0];
            if (walking[0] < 0) {
                helper.assertTrue(since[0] < 200, "the creeper never started walking toward the body; " + run.watch.summary());
                return;
            }
            helper.assertTrue(gap >= start[0] - 2.0, "the gap fell from " + start[0] + " to " + gap + " blocks, " + (since[0] - walking[0]) + " ticks after the creeper started to walk; "
                + run.watch.summary());
            if (since[0] - walking[0] < 25) return;
            helper.assertTrue(run.watch.reflexes.contains("mobs"), "the body never reacted to the creeper; " + run.watch.summary());
            run.pass();
        });
    }

    /**
     * A takedown that a fight breaks off is finished afterwards, from wherever the fight left the body. Protects: shelter
     * blocks being forgotten (and left standing for ever) when the takedown could not reach them. At morning the body
     * starts taking its shelter down; part-way, a fight carries it eight blocks off and a zombie is on it there; when the
     * fight is over the body walks back, takes down what stands and picks the dirt up: no dirt block stands and all twenty
     * dirt are in the inventory. The carrying off is the test's (the body is set down), the walk back is the layer's.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 1800, batch = "reflex_dusk_takedown")
    public static void aTakedownThatAFightBrokeOffIsFinishedLater(GameTestHelper helper) {
        room(helper, false);
        Run run = Run.start(helper, "tkdn", new Vec3(7.5, FLOOR_Y + 1, 7.5), 1800);
        run.setDayTime(13000L);
        run.body().getInventory().setItem(20, new ItemStack(Items.DIRT, 20));
        run.body().getInventory().setItem(21, new ItemStack(Items.IRON_SWORD));
        int[] stage = {0};
        int[] peak = {0};
        int[] waited = {0};
        Zombie[] zombie = {null};
        run.each(1750, () -> {
            BotPlayer now = run.body();
            ServerLevel level = helper.getLevel();
            switch (stage[0]) {
                case 0 -> {
                    BlockPos cell = BlockPos.containing(helper.relativeVec(now.position()));
                    if (!enclosed(helper, cell, new BlockPos(13, FLOOR_Y + 1, 13))) return;
                    peak[0] = countBlock(helper, Blocks.DIRT);
                    helper.assertTrue(peak[0] >= 8, "closed with only " + peak[0] + " dirt blocks");
                    level.setDayTime(24000L + 1000L);
                    stage[0] = 1;
                }
                case 1 -> {
                    // The takedown is under way: some blocks are down and some still stand.
                    int standing = countBlock(helper, Blocks.DIRT);
                    helper.assertTrue(standing > 0 || !run.layer().holderName().equals("night"), "the takedown ended before the fight could break it off");
                    if (!run.layer().holderName().equals("night") || standing > peak[0] - 3) return;
                    Vec3 away = helper.absoluteVec(new Vec3(13.5, FLOOR_Y + 1, 13.5));
                    now.teleportTo(level, away.x, away.y, away.z, now.getYRot(), 0.0F);
                    run.relocated();
                    zombie[0] = run.helmetedZombie(new Vec3(11.5, FLOOR_Y + 1, 13.5));
                    stage[0] = 2;
                }
                default -> {
                    if (run.layer().isHolding()) return;
                    helper.assertTrue(run.watch.reflexes.contains("mobs"), "the fight never happened: " + run.watch.summary());
                    int standing = countBlock(helper, Blocks.DIRT);
                    // A block the body could not get at in this hold is tried again after TAKEDOWN_RETRY_TICKS, in a hold
                    // of its own: "afterwards" includes that, so a block still standing between two holds is waited for.
                    if (standing > 0 && ++waited[0] < 2 * ReflexConstants.TAKEDOWN_RETRY_TICKS) return;
                    helper.assertTrue(standing == 0, standing + " dirt blocks still stand: the takedown was never finished");
                    int dirt = now.getInventory().countItem(Items.DIRT);
                    helper.assertTrue(dirt == 20, "the body carries " + dirt + " of its 20 dirt");
                    run.pass();
                }
            }
        });
    }

    /**
     * Giving up on a fire the body cannot put out does not switch off surfacing for air. The body is on fire in a cage it
     * cannot leave, with a pool in sight; the hazard reflex works at the fire for its whole give-up window and lets go.
     * Then the cage fills with water and the air is low: the body surfaces. Protects: one give-up clock for every kind of
     * hazard leaving a drowning body alone for the rest of the window. (The test keeps the fire alight and the body's
     * health up until the give-up, which is setup: a fire it cannot leave would otherwise kill it first.)
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 760)
    public static void givingUpOnAFireDoesNotSwitchOffSurfacing(GameTestHelper helper) {
        room(helper, true);
        for (int x = 9; x <= 11; x++) {
            for (int z = 7; z <= 9; z++) {
                helper.setBlock(x, FLOOR_Y - 1, z, Blocks.STONE);
                helper.setBlock(x, FLOOR_Y, z, Blocks.WATER);
            }
        }
        // The cage: the cell the body stands in, walled all round and open above, so that it can later be a pool.
        for (int y = FLOOR_Y + 1; y <= FLOOR_Y + 4; y++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) if (dx != 0 || dz != 0) helper.setBlock(3 + dx, y, 4 + dz, Blocks.BARRIER);
            }
        }
        Run run = Run.start(helper, "give", new Vec3(3.5, FLOOR_Y + 1, 4.5), 760);
        int[] stage = {0};
        run.each(740, () -> {
            BotPlayer body = run.body();
            if (stage[0] == 0) {
                if (run.ticks > 3) {
                    body.setRemainingFireTicks(200);
                    body.setHealth(body.getMaxHealth());
                }
                // Held for the whole window and then let go: the give-up has happened.
                if (run.watch.heldTicks < ReflexConstants.HAZARD_GIVE_UP_TICKS || !run.layer().holderName().isEmpty()) return;
                helper.assertTrue(run.watch.reflexes.contains("hazard"), "the give-up came from " + run.watch.reflexes);
                for (int y = FLOOR_Y + 1; y <= FLOOR_Y + 3; y++) helper.setBlock(3, y, 4, Blocks.WATER);
                body.setRemainingFireTicks(0);
                body.setAirSupply(60);
                stage[0] = 1;
                return;
            }
            helper.assertTrue(body.getHealth() >= body.getMaxHealth() - 4.0F, "the body was drowning: health " + body.getHealth() + ", air " + body.getAirSupply());
            if (body.isEyeInFluid(FluidTags.WATER) || run.layer().isHolding()) return;
            helper.assertTrue(body.getAirSupply() >= ReflexConstants.AIR_RECOVERED, "let go with the air at " + body.getAirSupply());
            run.pass();
        });
    }

    /** A body that is despawned and not replaced takes its layer out of the layer map at the next sweep; a body still logged in keeps its own. */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 260)
    public static void aDespawnedBodysLayerIsDropped(GameTestHelper helper) {
        room(helper, true);
        Run gone = Run.start(helper, "lgone", new Vec3(2.5, FLOOR_Y + 1, 2.5), 250);
        Run stays = Run.start(helper, "lstay", new Vec3(5.5, FLOOR_Y + 1, 5.5), 250);
        BotPlayer body = gone.body();
        ReflexLayer.of(body);
        ReflexLayer.of(stays.body());
        helper.assertTrue(ReflexLayer.tracks(gone.id()) && ReflexLayer.tracks(stays.id()), "a layer was not tracked once made");
        BodyManager.of(helper.getLevel().getServer()).despawn(body);
        helper.assertTrue(ReflexLayer.tracks(gone.id()), "the layer went with the despawn itself, before any sweep");
        helper.succeedWhen(() -> {
            helper.assertTrue(!ReflexLayer.tracks(gone.id()), "the layer of the despawned body is still tracked");
            helper.assertTrue(ReflexLayer.tracks(stays.id()), "the layer of a body that is still logged in was dropped");
            gone.cleanup();
            stays.cleanup();
        });
    }

    // ---------------------------------------------------------------------------------------------------------
    // The last fix round (issue 8, round three): what each of these protects is on its test
    // ---------------------------------------------------------------------------------------------------------

    /**
     * A recorded shelter block that the body cannot take down does not keep taking the body. The block (dirt) is recorded
     * through the layer, as the night reflex records what it places, and is walled in by stone on every side, so that no face
     * of it can be seen: a takedown of it fails however many times it is tried. Protects: the layer taking the body again
     * every two hundred ticks, for the rest of the day, to look at a block it can do nothing about (each of those is a
     * preemption the executor counts: eight in 1200 ticks and it gives the goal up). Each failed attempt doubles the wait
     * before the next, up to a day, and the block stays recorded. In 2400 daylight ticks with the block in view the body is
     * held for at most {@link #BACKOFF_MAX_HOLDS} holds and a fraction of the time that the test prints.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = BACKOFF_TICKS + 300, batch = "reflex_takedown_backoff")
    public static void aShelterBlockItCannotReachDoesNotKeepTakingTheBody(GameTestHelper helper) {
        room(helper, true);
        BlockPos dirt = new BlockPos(12, FLOOR_Y + 2, 8);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) helper.setBlock(dirt.offset(dx, dy, dz), Blocks.STONE);
            }
        }
        helper.setBlock(dirt, Blocks.DIRT);
        backoffRun(helper, "nblk", dirt, new Vec3(2.5, FLOOR_Y + 1, 8.5));
    }

    /**
     * The same where the body cannot even get within reach of the block: it stands in a nook of the room and the dirt is
     * inside a mass of barrier too deep to be near any cell the body can stand in, so each attempt is a walk that goes
     * nowhere and ends when its time does.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = BACKOFF_TICKS + 300, batch = "reflex_takedown_backoff")
    public static void aShelterBlockItCannotGetNearDoesNotKeepTakingTheBody(GameTestHelper helper) {
        room(helper, false);
        BlockPos dirt = new BlockPos(9, FLOOR_Y + 4, 8);
        for (int dx = -4; dx <= 4; dx++) {
            for (int dy = -4; dy <= 4; dy++) {
                for (int dz = -4; dz <= 4; dz++) helper.setBlock(dirt.offset(dx, dy, dz), Blocks.BARRIER);
            }
        }
        helper.setBlock(dirt, Blocks.DIRT);
        backoffRun(helper, "fblk", dirt, new Vec3(1.5, FLOOR_Y + 1, 8.5));
    }

    private static void backoffRun(GameTestHelper helper, String tag, BlockPos dirt, Vec3 bodyAt) {
        Run run = Run.start(helper, tag, bodyAt, BACKOFF_TICKS + 250);
        run.setDayTime(1000L);
        run.layer().rememberShelterBlock(helper.getLevel(), helper.absolutePos(dirt));
        run.each(BACKOFF_TICKS + 200, () -> {
            helper.assertTrue(run.layer().recordedShelterBlocks() == 1, "the shelter block is no longer recorded: " + run.layer().recordedShelterBlocks());
            helper.assertTrue(helper.getBlockState(dirt).is(Blocks.DIRT), "the block was taken down, so nothing was tested");
            if (run.ticks < BACKOFF_TICKS) return;
            Watch watch = run.watch;
            MegaBlockBot.LOGGER.info("Reflex GameTest backoff {}: {} holds, {} ticks held of {} ({}%)", tag, watch.holdsStarted, watch.heldTicks, BACKOFF_TICKS,
                100 * watch.heldTicks / BACKOFF_TICKS);
            helper.assertTrue(watch.holdsStarted <= BACKOFF_MAX_HOLDS, "the body was taken " + watch.holdsStarted + " times in " + BACKOFF_TICKS
                + " ticks for a block it cannot take down: " + watch.summary());
            helper.assertTrue(watch.heldTicks <= BACKOFF_TICKS * 15 / 100, "the body was held for " + watch.heldTicks + " of " + BACKOFF_TICKS
                + " ticks for a block it cannot take down: " + watch.summary());
            run.pass();
        });
    }

    /**
     * A body that has run from a creeper is not handed back to its task the moment the creeper is out of sight: the hold goes on
     * for a good while after the last sight of a threat that is still about. Protects: the flap of the first night run (a
     * retreat takes the body out of the creeper's sight, the hold ends, the task walks the body back into it, nine holds in half a
     * minute). The body stands in a corner and the creeper, which stands where it is put, seven blocks off along the wall and
     * after the body (so it is a threat, and there is room to run east along the other wall); once the hold has begun a wall the
     * width of the room goes up between them, and the creeper, alive and near, is not seen again. The hold must last at least
     * {@link ReflexConstants#RETREAT_QUIET_TICKS} less a few ticks from there, and end.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 420)
    public static void aRetreatHoldOutlastsTheSightOfTheCreeperItRanFrom(GameTestHelper helper) {
        room(helper, true);
        Run run = Run.start(helper, "hide", new Vec3(1.5, FLOOR_Y + 1, 1.5), 420);
        run.body().getInventory().setItem(20, new ItemStack(Items.IRON_SWORD));
        Creeper creeper = run.pinnedCreeper(new Vec3(1.5, FLOOR_Y + 1, 8.5));
        int[] sightLostAt = {-1};
        run.each(400, () -> {
            if (sightLostAt[0] < 0) {
                if (run.watch.holdsStarted == 0) return;
                helper.assertTrue(run.layer().holderName().equals("mobs"), "the first hold is the '" + run.layer().holderName() + "' reflex's");
                for (int x = 1; x <= 14; x++) {
                    for (int y = FLOOR_Y + 1; y < ROOF_Y; y++) helper.setBlock(x, y, 5, Blocks.STONE);
                }
                sightLostAt[0] = run.ticks;
                return;
            }
            int since = run.ticks - sightLostAt[0];
            helper.assertTrue(creeper.isAlive(), "the creeper is gone, so nothing was tested");
            if (run.layer().isHolding()) {
                helper.assertTrue(since < 250, "the hold has not ended " + since + " ticks after the creeper was last seen");
                return;
            }
            helper.assertTrue(since >= ReflexConstants.RETREAT_QUIET_TICKS - 5, "the body was handed back " + since + " ticks after the last sight of a creeper it ran from, "
                + "which is still about: " + run.watch.summary());
            run.pass();
        });
    }

    /**
     * A body that dies while the hazard reflex is walking it to water leaves nothing of that walk behind: the replacement,
     * after the layer's first tick on it, has an idle navigator with no failure carried over from the dead body's command.
     * Protects: a dropped hold leaving the navigator (kept per UUID across respawns) holding the dead body's command, which
     * the replacement then finds as a failure it did not make. The test kills the body on the way and reads the navigator
     * after the replacement's first brain tick.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 520)
    public static void aBodyThatDiesWalkingToWaterLeavesTheNavigatorIdle(GameTestHelper helper) {
        room(helper, true);
        for (int x = 9; x <= 11; x++) {
            for (int z = 7; z <= 9; z++) {
                helper.setBlock(x, FLOOR_Y - 1, z, Blocks.STONE);
                helper.setBlock(x, FLOOR_Y, z, Blocks.WATER);
            }
        }
        Run run = Run.start(helper, "dwalk", new Vec3(1.5, FLOOR_Y + 1, 8.5), 520);
        run.expectReplacement();
        BotPlayer first = run.body();
        first.setRemainingFireTicks(400);
        boolean[] killed = {false};
        run.each(480, () -> {
            BotPlayer body = run.body();
            ServerLevel level = helper.getLevel();
            if (!killed[0]) {
                if (!run.watch.lastHolder.equals("hazard") || BotNavigator.of(body).state() != BotNavigator.State.WORKING) return;
                helper.assertTrue(run.watch.wasHeld, "setup: the hazard reflex is not holding the body");
                body.hurt(level.damageSources().genericKill(), 1000.0F);
                killed[0] = true;
                return;
            }
            // The replacement's own first tick has run: the layer has met the new body.
            if (body == first || run.watch.lastTicked != body) return;
            BotNavigator nav = BotNavigator.of(body);
            helper.assertTrue(nav.state() == BotNavigator.State.IDLE, "the replacement's navigator is " + nav.state() + " (" + nav.failKind() + "), not idle");
            helper.assertTrue(nav.failKind() == null, "the replacement's navigator carries the dead body's failure: " + nav.failKind());
            run.pass();
        });
    }

    /**
     * The policy is changed while the body lies asleep in its bed: it gets up. Protects: the sleep branch returning "still
     * sleeping" before it looked at the policy (and at the morning), so that a body told to do something else stayed in bed
     * until the game woke it. The night is spent (the body slept), and the result says so.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 760, batch = "reflex_night_wake")
    public static void aPolicyChangedWhileAsleepGetsTheBodyUp(GameTestHelper helper) {
        room(helper, false);
        Run run = Run.start(helper, "wake", new Vec3(3.5, FLOOR_Y + 1, 7.5), 760);
        run.setDayTime(18000L);
        run.layer().setNightPolicy(ReflexConstants.NightPolicy.BED);
        run.body().getInventory().setItem(20, new ItemStack(Items.DIRT, 20));
        placeOwnedBed(helper, run.body(), new BlockPos(10, FLOOR_Y + 1, 7));
        int[] changedAt = {-1};
        run.each(740, () -> {
            BotPlayer now = run.body();
            ReflexLayer layer = run.layer();
            if (changedAt[0] < 0) {
                if (!now.isSleeping()) return;
                layer.setNightPolicy(ReflexConstants.NightPolicy.IGNORE);
                changedAt[0] = run.ticks;
                return;
            }
            if (now.isSleeping()) {
                helper.assertTrue(run.ticks - changedAt[0] < 40, "still asleep " + (run.ticks - changedAt[0]) + " ticks after the policy changed");
                return;
            }
            helper.assertTrue(layer.lastNightResult() == ReflexLayer.NightResult.SLEPT, "the night ended as " + layer.lastNightResult() + ", the body had slept");
            run.pass();
        });
    }

    // ---------------------------------------------------------------------------------------------------------
    // Issue 29: a death, a restart
    // ---------------------------------------------------------------------------------------------------------

    /**
     * A hold whose body dies is dropped in the tick the death is reported -- the brain's {@code onDeath} tells the layer
     * through the reflex source, as the real brain does -- and not on the replacement's first tick. Protects: the
     * executor counting a dead body's hold as held ticks against the goal (issue 29). The test kills the body mid-fight
     * and asks the layer on that same tick, then checks the replacement starts unheld.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 320)
    public static void aHoldEndsInTheTickTheBodyDies(GameTestHelper helper) {
        room(helper, true);
        Run run = Run.start(helper, "dhold", new Vec3(8.5, FLOOR_Y + 1, 8.5), 320);
        run.expectReplacement();
        BotPlayer first = run.body();
        Zombie zombie = run.zombie(new Vec3(10.5, FLOOR_Y + 1, 8.5));
        boolean[] killed = {false};
        run.each(300, () -> {
            BotPlayer body = run.body();
            if (!killed[0]) {
                if (!run.layer().isHolding()) return;
                zombie.discard();
                body.hurt(helper.getLevel().damageSources().genericKill(), 1000.0F);
                killed[0] = true;
                // die() told the brain, and the brain the reflex source, before it returned.
                helper.assertTrue(!run.layer().isHolding(), "the layer still holds a body that died: " + run.layer().status());
                helper.assertTrue(run.layer().holderName().isEmpty(), "the dead body is held by '" + run.layer().holderName() + "'");
                return;
            }
            if (body == first || run.watch.lastTicked != body) return;
            helper.assertTrue(!run.layer().isHolding(), "the replacement is held the moment it appears: " + run.layer().status());
            run.pass();
        });
    }

    /**
     * Shelter blocks survive a save and a load: what the layer remembered as standing is saved with the brain's state,
     * and a fresh layer for the same body (what a restart gets) takes the blocks back as its own -- except one that no
     * longer holds a shelter block where the save had it. Protects: a restart forgetting which blocks to take down
     * (issue 29). The takedown of the block that survived the load is what shows it was counted as the body's own. A
     * batch of its own: the layer map is cleared to stand in for the restart, which is global state.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 460, batch = "reflex_shelter_save")
    public static void shelterBlocksSurviveASaveAndALoad(GameTestHelper helper) {
        room(helper, true);
        // Two shelter blocks standing in the arena, put there by the test before it starts (setup, once).
        BlockPos kept = new BlockPos(5, FLOOR_Y + 2, 8);
        BlockPos gone = new BlockPos(10, FLOOR_Y + 2, 8);
        helper.setBlock(kept, Blocks.DIRT);
        helper.setBlock(gone, Blocks.DIRT);
        Run run = Run.start(helper, "shsv", new Vec3(8.5, FLOOR_Y + 1, 8.5), 460);
        run.setDayTime(1000L);
        ServerLevel level = helper.getLevel();
        int[] stage = {0};
        run.each(420, () -> {
            ReflexLayer layer = ReflexLayer.of(run.body());
            switch (stage[0]) {
                case 0 -> {
                    // Both standing blocks are recorded as the body's shelter and saved as the brain saves them...
                    layer.rememberShelterBlock(level, helper.absolutePos(kept));
                    layer.rememberShelterBlock(level, helper.absolutePos(gone));
                    CompoundTag tag = new CompoundTag();
                    ReflexSources.current().saveShelterBlocks(run.body(), tag);
                    // ...then one of them is gone by the time the brain loads again (taken down, or something broke it).
                    helper.setBlock(gone, Blocks.AIR);
                    // A restart: the layer map went with it, and a fresh layer is made for the same body.
                    ReflexLayer.clearAll();
                    ReflexLayer fresh = ReflexLayer.of(run.body());
                    helper.assertTrue(fresh.recordedShelterBlocks() == 0, "setup: the fresh layer came with a record");
                    ReflexSources.current().loadShelterBlocks(run.body(), tag);
                    helper.assertTrue(fresh.recordedShelterBlocks() == 1, "the fresh layer counts "
                        + fresh.recordedShelterBlocks() + " shelter blocks after the load, not the one that still stands");
                    stage[0] = 1;
                }
                default -> {
                    // The block the load kept is the body's own: a calm daylight tick takes it down. The other was air,
                    // and never comes back.
                    if (helper.getBlockState(kept).is(Blocks.DIRT)) return;
                    helper.assertTrue(layer.recordedShelterBlocks() == 0, layer.recordedShelterBlocks()
                        + " shelter blocks are still recorded after the takedown");
                    MegaBlockBot.LOGGER.info("Reflex GameTest shelter save: the standing block was taken down after the load, {}",
                        run.watch.summary());
                    run.pass();
                }
            }
        });
    }

    // ---------------------------------------------------------------------------------------------------------
    // Issue 29: a shooter at first sight, and the danger zones it leaves behind
    // ---------------------------------------------------------------------------------------------------------

    /**
     * An unarmed body at full health and a pillager that sees it across open ground: on the first held tick the layer
     * is walking the body away from the pillager, not fighting it (issue 29 -- the live runs walked an unarmed body at
     * a crossbow until it was shot down). The pillager is penned by the geometry the test builds (a fence ring it can
     * see and shoot over, a block and a half high, which no mob walks over) and made persistent, and its brain is left
     * on: nothing here switches it off. The navigator is warmed up with a short walk first, as the creeper tests do
     * (its first path in a JVM takes about a second and a half, which is a cost of the test and not of the layer), and
     * the shooter appears only once the ground the retreat needs is loaded.
     * What the arena can show is the retreat beginning and the gap opening. The distance the retreat asks for is the
     * pillager's own follow range plus a margin (about forty blocks), and a GameTest loads about a chunk around the
     * body, so the far spot falls back to the nearest spot there is and the full distance is not shown here: the
     * host's live run is what shows it. Each of these three has a batch of its own, like the open-ground creeper runs.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 600, batch = "reflex_shooter")
    public static void runsFromAShooterAtFirstSightWithNoShield(GameTestHelper helper) {
        pen(helper, 0);
        Run run = Run.start(helper, "shoot", new Vec3(6.5, 0.0, 8.5), 600);
        run.setDayTime(1000L);
        run.watch.walkTo(helper.absolutePos(new BlockPos(4, 0, 8)));
        Pillager[] pillager = {null};
        double[] start = {0.0};
        int[] heldAt = {-1};
        run.each(560, () -> {
            BotPlayer body = run.body();
            helper.assertTrue(body.getHealth() > 0.0F, "the body died; " + run.watch.summary());
            if (pillager[0] == null) {
                // The retreat needs ground to run over: wait until the land west of the body is loaded.
                if (!run.watch.arrived || (run.ticks < 150
                    && !helper.getLevel().isLoaded(BlockPos.containing(body.position().add(-16.0, 0.0, 0.0))))) return;
                run.watch.walkTo(null);
                pillager[0] = run.shooter(new Vec3(12.5, 0.0, 12.5));
                return;
            }
            helper.assertTrue(pillager[0].isAlive(), "the pillager died, so nothing was tested; " + run.watch.summary());
            if (run.watch.holdsStarted == 0) return;
            if (heldAt[0] < 0) {
                heldAt[0] = run.ticks;
                start[0] = body.distanceTo(pillager[0]);
                helper.assertTrue(run.layer().holderName().equals("mobs"), "the first hold is the '"
                    + run.layer().holderName() + "' reflex's, not the mob reflex's");
                helper.assertTrue(run.layer().retreating(), "the first held tick is not a retreat: " + run.layer().status());
            }
            // Ticks a body takes to be clearly on its way: a sprint covers about ten blocks in forty.
            if (run.ticks - heldAt[0] < 40) return;
            double now = body.distanceTo(pillager[0]);
            helper.assertTrue(now > start[0], "after 40 ticks of the hold the body is " + String.format(Locale.ROOT, "%.1f", now)
                + " blocks from the pillager, no further than the " + String.format(Locale.ROOT, "%.1f", start[0])
                + " it started at; " + run.watch.summary());
            MegaBlockBot.LOGGER.info("Reflex GameTest shooter: ran from a pillager, the gap went from {} to {} blocks, {}",
                String.format(Locale.ROOT, "%.1f", start[0]), String.format(Locale.ROOT, "%.1f", now), run.watch.summary());
            run.pass();
        });
    }

    /**
     * The same with a shield in the pack: the layer does not run at first sight, because a shield is an answer to a
     * shooter, and issue 29 leaves that behaviour as it was. The body has no weapon, so what it does instead is walk
     * at the pillager to fight it with its fists, which is why the test measures only that no retreat is under way.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 600, batch = "reflex_shooter_shield")
    public static void doesNotRunFromAShooterItCarriesAShieldAgainst(GameTestHelper helper) {
        pen(helper, 0);
        Run run = Run.start(helper, "shld", new Vec3(6.5, 0.0, 8.5), 600);
        run.setDayTime(1000L);
        run.body().getInventory().setItem(21, new ItemStack(Items.SHIELD));
        run.watch.walkTo(helper.absolutePos(new BlockPos(4, 0, 8)));
        Pillager[] pillager = {null};
        int[] heldAt = {-1};
        run.each(560, () -> {
            BotPlayer body = run.body();
            helper.assertTrue(body.getHealth() > 0.0F, "the body died; " + run.watch.summary());
            if (pillager[0] == null) {
                if (!run.watch.arrived || run.ticks < 60) return;
                run.watch.walkTo(null);
                pillager[0] = run.shooter(new Vec3(12.5, 0.0, 12.5));
                return;
            }
            if (run.watch.holdsStarted == 0) return;
            if (heldAt[0] < 0) heldAt[0] = run.ticks;
            helper.assertTrue(!run.layer().retreating(), "the body ran from a shooter it carries a shield against; "
                + run.layer().status());
            if (run.ticks - heldAt[0] < 60) return;
            MegaBlockBot.LOGGER.info("Reflex GameTest shooter with a shield: no retreat in 60 ticks, the body {} blocks from the pillager, {}",
                String.format(Locale.ROOT, "%.1f", body.distanceTo(pillager[0])), run.watch.summary());
            run.pass();
        });
    }

    /**
     * After a retreat from a shooter, the body's memory holds a danger zone around where the shooter stood, in this
     * dimension and not yet lapsed (issue 29): it is what keeps the goal that resumes from walking the body straight
     * back there.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 600, batch = "reflex_shooter_zone")
    public static void aRetreatFromAShooterNotesADangerZoneWhereItStood(GameTestHelper helper) {
        pen(helper, 0);
        Run run = Run.start(helper, "zone", new Vec3(6.5, 0.0, 8.5), 600);
        run.setDayTime(1000L);
        run.watch.walkTo(helper.absolutePos(new BlockPos(4, 0, 8)));
        Pillager[] pillager = {null};
        run.each(560, () -> {
            if (pillager[0] == null) {
                if (!run.watch.arrived || (run.ticks < 150
                    && !helper.getLevel().isLoaded(BlockPos.containing(run.body().position().add(-16.0, 0.0, 0.0))))) return;
                run.watch.walkTo(null);
                pillager[0] = run.shooter(new Vec3(12.5, 0.0, 12.5));
                return;
            }
            if (!run.layer().retreating()) return; // the retreat has been picked, which is when the zone is written
            BotMemory memory = BotMemory.peek(run.id());
            List<BotMemory.DangerZone> zones = memory == null ? List.of()
                : memory.liveDangerZones(helper.getLevel().getGameTime());
            helper.assertTrue(zones.size() == 1, zones.size() + " danger zones are remembered, not the one");
            BotMemory.DangerZone zone = zones.get(0);
            helper.assertTrue(zone.dimension().equals(helper.getLevel().dimension().location().toString()),
                "the zone is in " + zone.dimension() + ", not this dimension");
            helper.assertTrue(zone.centre().distSqr(pillager[0].blockPosition()) <= 4, "the zone is centred at " + zone.centre()
                + ", not where the pillager stood (" + pillager[0].blockPosition() + ")");
            helper.assertTrue(zone.live(helper.getLevel().getGameTime()), "the zone has already lapsed");
            MegaBlockBot.LOGGER.info("Reflex GameTest danger zone: {} blocks wide around {}, because {}",
                String.format(Locale.ROOT, "%.1f", zone.radius()), zone.centre().toShortString(), zone.reason());
            run.pass();
        });
    }

    /**
     * A mine command with two blocks of the kind known, one inside a danger zone written into the memory at set-up and
     * one outside: the one outside is taken and the one inside stands (issue 29). The one inside is the nearer of the
     * two, so a command that did not keep out of the zone would take it first. The block is not set aside for ever --
     * it comes back when the zone lapses -- but that takes the zone's 12000 ticks, which no GameTest waits for. A batch
     * of its own, like every test that breaks blocks through the navigator: the break rules are global state.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 500, batch = "reflex_zone_mine")
    public static void aMineCommandSkipsTheBlockInsideADangerZone(GameTestHelper helper) {
        room(helper, true);
        BlockPos inside = new BlockPos(6, FLOOR_Y + 1, 8);
        BlockPos outside = new BlockPos(13, FLOOR_Y + 1, 8);
        helper.setBlock(inside, Blocks.HAY_BLOCK);
        helper.setBlock(outside, Blocks.HAY_BLOCK);
        Run run = Run.start(helper, "zmine", new Vec3(2.5, FLOOR_Y + 1, 8.5), 500);
        // Set-up: a zone that covers the one block and nothing the body stands in, alive for well past the test.
        BotMemory.of(run.id()).noteDangerZone(helper.getLevel().dimension().location().toString(), helper.absolutePos(inside),
            2.5, helper.getLevel().getGameTime() + 20_000L, "a test's zone");
        run.watch.mine(Blocks.HAY_BLOCK, Items.HAY_BLOCK);
        run.each(460, () -> {
            if (!run.watch.mined) {
                helper.assertTrue(run.watch.mineFailure.isEmpty(), "the mine command failed: " + run.watch.mineFailure);
                return;
            }
            helper.assertTrue(!helper.getBlockState(outside).is(Blocks.HAY_BLOCK), "the block outside the zone was not taken");
            helper.assertTrue(helper.getBlockState(inside).is(Blocks.HAY_BLOCK), "the block inside the zone was taken");
            helper.assertTrue(run.body().getInventory().countItem(Items.HAY_BLOCK) >= 1, "the command ended with no hay block held");
            MegaBlockBot.LOGGER.info("Reflex GameTest danger zone: mined the block outside it and left the one inside standing");
            run.pass();
        });
    }

    /**
     * A recover node whose one death position lies inside a live danger zone: it completes without the body having
     * walked toward the position -- its distance to the position never falls (issue 29: after the live runs, the
     * respawned body was sent straight back to where the pillager killed it for one dropped stack).
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 200, batch = "reflex_zone_mine")
    public static void aRecoverNodeSkipsADeathPositionInsideADangerZone(GameTestHelper helper) {
        room(helper, true);
        BlockPos death = new BlockPos(12, FLOOR_Y + 1, 8);
        Run run = Run.start(helper, "zrec", new Vec3(2.5, FLOOR_Y + 1, 8.5), 200);
        BotMemory.of(run.id()).noteDangerZone(helper.getLevel().dimension().location().toString(), helper.absolutePos(death),
            4.0, helper.getLevel().getGameTime() + 20_000L, "a test's zone");
        BlockPos absolute = helper.absolutePos(death);
        Map<String, String> params = new LinkedHashMap<>();
        params.put("dimension", helper.getLevel().dimension().location().toString());
        params.put("drops", absolute.getX() + "," + absolute.getY() + "," + absolute.getZ());
        RecoverDropsTask task = new RecoverDropsTask(new NodeSpec(new Goal.Custom("recover_drops", params),
            "recover_drops", params, "", true, List.of()));
        run.watch.work(task);
        Vec3 at = Vec3.atCenterOf(absolute);
        double[] start = {Double.MAX_VALUE};
        double[] closest = {Double.MAX_VALUE};
        run.each(160, () -> {
            BotPlayer body = run.body();
            double now = body.position().distanceTo(at);
            if (start[0] == Double.MAX_VALUE) start[0] = now;
            closest[0] = Math.min(closest[0], now);
            if (task.state() != TaskState.COMPLETED) {
                // PENDING on the first look: the brain starts the task on its own next tick.
                helper.assertTrue(task.state() == TaskState.RUNNING || task.state() == TaskState.PENDING,
                    "the recover task is " + task.state());
                return;
            }
            helper.assertTrue(closest[0] >= start[0] - 0.5, "the body walked toward the death position: it came within "
                + String.format(Locale.ROOT, "%.1f", closest[0]) + " blocks of it, having started at "
                + String.format(Locale.ROOT, "%.1f", start[0]));
            run.pass();
        });
    }

    /**
     * A pen a shooter is held in by geometry alone: a ring of fences an eye sees over and shoots over (a fence is a
     * block and a half high) and no mob walks over. Its brain stays on; only the fences hold it.
     */
    private static void pen(GameTestHelper helper, int y) {
        for (int i = 11; i <= 14; i++) {
            helper.setBlock(i, y, 11, Blocks.OAK_FENCE);
            helper.setBlock(i, y, 14, Blocks.OAK_FENCE);
        }
        for (int z = 12; z <= 13; z++) {
            helper.setBlock(11, y, z, Blocks.OAK_FENCE);
            helper.setBlock(14, y, z, Blocks.OAK_FENCE);
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Terrain and measuring
    // ---------------------------------------------------------------------------------------------------------

    /** A stone floor at {@link #FLOOR_Y}, barrier walls up the arena's edge, and with {@code roof} a barrier roof over all of it. */
    private static void room(GameTestHelper helper, boolean roof) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                helper.setBlock(x, FLOOR_Y, z, Blocks.STONE);
                if (roof) helper.setBlock(x, ROOF_Y, z, Blocks.BARRIER);
            }
        }
        for (int i = 0; i < 16; i++) {
            for (int y = FLOOR_Y; y <= ROOF_Y; y++) {
                helper.setBlock(i, y, 0, Blocks.BARRIER);
                helper.setBlock(i, y, 15, Blocks.BARRIER);
                helper.setBlock(0, y, i, Blocks.BARRIER);
                helper.setBlock(15, y, i, Blocks.BARRIER);
            }
        }
    }

    /** A bed, foot at {@code foot} and head to its east, that the body counts as its own: what the loader's placement hook would have recorded had it placed it. */
    private static void placeOwnedBed(GameTestHelper helper, BotPlayer body, BlockPos foot) {
        BlockPos head = foot.east();
        helper.setBlock(foot, Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.EAST).setValue(BedBlock.PART, BedPart.FOOT));
        helper.setBlock(head, Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.EAST).setValue(BedBlock.PART, BedPart.HEAD));
        BlockSafety safety = BlockSafety.of(helper.getLevel().getServer());
        safety.noteBotPlaced(body, helper.getLevel(), helper.absolutePos(foot));
        safety.noteBotPlaced(body, helper.getLevel(), helper.absolutePos(head));
    }

    private static ItemStack find(BotPlayer body, Item item) {
        for (ItemStack stack : body.getInventory().items) if (stack.is(item)) return stack;
        return null;
    }

    private static int countBlock(GameTestHelper helper, Block block) {
        int n = 0;
        for (int x = 1; x <= 14; x++) {
            for (int z = 1; z <= 14; z++) {
                for (int y = FLOOR_Y + 1; y < ROOF_Y; y++) if (helper.getBlockState(new BlockPos(x, y, z)).is(block)) n++;
            }
        }
        return n;
    }

    /**
     * True when no walk through air leads from {@code outside} to the body's cell or the one above it: a flood fill
     * over every cell a body can be in (nothing solid in it), in the room and the air above it. This is what a mob's
     * path needs, and more than it gets (it does not cross a gap a zombie cannot jump).
     */
    private static boolean enclosed(GameTestHelper helper, BlockPos bodyCell, BlockPos outside) {
        ServerLevel level = helper.getLevel();
        Set<BlockPos> seen = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(outside);
        seen.add(outside);
        while (!queue.isEmpty()) {
            BlockPos at = queue.poll();
            if (at.equals(bodyCell) || at.equals(bodyCell.above())) return false;
            for (Direction d : Direction.values()) {
                BlockPos next = at.relative(d);
                if (next.getX() < 1 || next.getX() > 14 || next.getZ() < 1 || next.getZ() > 14 || next.getY() <= FLOOR_Y || next.getY() > FLOOR_Y + 12) continue;
                if (seen.contains(next)) continue;
                BlockPos abs = helper.absolutePos(next);
                if (!level.getBlockState(abs).getCollisionShape(level, abs).isEmpty()) continue;
                seen.add(next);
                queue.add(next);
            }
        }
        return true;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Harness
    // ---------------------------------------------------------------------------------------------------------

    /**
     * The brain a real one will be: it ticks the reflex layer first and, while no hold is on, the stand-in task (here
     * a walk, or a mine command, by the navigator, started whenever it is not running and not done) and the navigator
     * for it. It keeps what the tests ask about the holds, keyed on the name of the reflex that holds the body
     * ({@link ReflexLayer#holderName}), never on the text of a cause.
     */
    private static final class Watch implements BotBrain {
        int heldTicks;
        int holdsStarted;
        int holdsEnded;
        int blockingTicks;
        boolean wasHeld;
        /** The body this brain was last ticked with: a replacement's first tick shows here. */
        BotPlayer lastTicked;
        boolean navigatorRanWhileHeld;
        final Set<String> reflexes = new LinkedHashSet<>();
        String lastHolder = "";
        /** The last hold's cause, for failure messages only. */
        String lastCause = "";
        /** A tick on which the body was held by the first reflex and the very next by the second, with no gap between. */
        boolean handedFromHazardToMobs;
        private BlockPos walkGoal;
        int walkStarts;
        boolean arrived;
        private Block mineBlock;
        private Item mineItem;
        boolean mined;
        String mineFailure = "";
        /** A leaf's task the test built, ticked by the stand-in as the executor would tick it. */
        @Nullable
        private Task<BotPlayer> job;

        void walkTo(BlockPos goal) {
            walkGoal = goal;
        }

        /** The stand-in task mines one of this block, its drop being this item (a mine command on the navigator). */
        void mine(Block block, Item item) {
            mineBlock = block;
            mineItem = item;
        }

        /** The stand-in brain runs this task of a leaf instead of its own walk or mine (what drives one task a test builds). */
        void work(Task<BotPlayer> task) {
            job = task;
        }

        @Override
        public void tick(BotPlayer body) {
            ReflexLayer layer = ReflexLayer.of(body);
            ReflexHold hold = layer.tick(body);
            lastTicked = body;
            BotNavigator nav = BotNavigator.of(body);
            if (body.isBlocking()) blockingTicks++;
            String holder = layer.holderName();
            if (hold.held()) {
                if (!wasHeld) holdsStarted++;
                heldTicks++;
                lastCause = hold.cause();
                reflexes.add(holder);
                if (lastHolder.equals("hazard") && holder.equals("mobs")) handedFromHazardToMobs = true;
                // The stand-in's command must not run while the layer holds the body. Only a test with no retreat in it asks.
                if (nav.state() == BotNavigator.State.WORKING && holder.equals("mobs")) navigatorRanWhileHeld = true;
            } else {
                if (wasHeld) holdsEnded++;
                standIn(body, nav);
            }
            lastHolder = holder;
            wasHeld = hold.held();
        }

        private void standIn(BotPlayer body, BotNavigator nav) {
            if (job != null) {
                // What the executor does with a leaf's task: start it once, tick it, and let its own navigator commands
                // run (the brain ticks the engine, the task only asks it to move).
                if (job.state() == TaskState.PENDING) job.start(body);
                if (job.state() == TaskState.RUNNING) {
                    job.tick(body);
                    nav.tick(body);
                }
                return;
            }
            if (walkGoal != null && !arrived) {
                if (nav.state() == BotNavigator.State.DONE) {
                    arrived = true;
                } else {
                    if (nav.state() != BotNavigator.State.WORKING) {
                        nav.goTo(walkGoal, 0);
                        walkStarts++;
                    }
                    nav.tick(body);
                    if (nav.state() == BotNavigator.State.DONE) arrived = true;
                }
            }
            if (mineBlock != null && !mined) {
                if (nav.state() == BotNavigator.State.IDLE) nav.mine(List.of(mineBlock), List.of(mineItem), 1);
                nav.tick(body);
                if (nav.state() == BotNavigator.State.DONE) mined = true;
                if (nav.state() == BotNavigator.State.FAILED) mineFailure = nav.failKind() + ": " + nav.failure();
            }
        }

        String summary() {
            return "holds " + holdsStarted + "/" + holdsEnded + ", " + heldTicks + " ticks held by " + reflexes + ", last cause '" + lastCause + "'";
        }

        @Override
        public void onDeath(BotPlayer body, DeathRecord death) {
            // What the real brain does (BotRuntime.onDeath): a hold ends with the body that died, the moment it is
            // reported, and not on the replacement's first tick.
            ReflexSources.current().onDeath(body);
        }

        @Override public void onRespawn(BotPlayer newBody) {}
        @Override public boolean wantsToStayLoaded() { return true; }
        @Override public void save(CompoundTag tag, HolderLookup.Provider registries) {}
        @Override public void load(CompoundTag tag, HolderLookup.Provider registries) {}
    }

    /**
     * One test body: spawned with a {@link Watch}, watched every tick (it must not move more than a block in a tick,
     * must not die unless the test is about that, and the layer must not throw), and despawned and forgotten together
     * with the mobs and the time of day the test set when the test ends.
     */
    private static final class Run {
        private final GameTestHelper helper;
        private final BodyManager manager;
        private final String name;
        private final UUID id;
        final Watch watch = new Watch();
        private final List<Entity> mobs = new ArrayList<>();
        private final int faultsAtStart = ReflexLayer.faults();
        private long savedDayTime = Long.MIN_VALUE;
        int ticks;
        private Vec3 last;
        private BotPlayer lastBody;
        private boolean wasSleeping;
        private boolean replacementExpected;
        private boolean over;

        private Run(GameTestHelper helper, String name) {
            this.helper = helper;
            this.manager = BodyManager.of(helper.getLevel().getServer());
            this.name = name;
            this.id = BodyManager.uuidFor(name);
        }

        static Run start(GameTestHelper helper, String tag, Vec3 relative, int deadlineTicks) {
            Run run = new Run(helper, "gt_rx_" + tag + "_" + RUN_ID);
            try {
                run.manager.spawn(run.name, null, helper.getLevel(), helper.absoluteVec(relative), 0.0F, () -> run.watch);
            } catch (BodyException e) {
                throw new IllegalStateException("could not spawn " + run.name + ": " + e.getMessage(), e);
            }
            helper.runAtTickTime(deadlineTicks, run::cleanup);
            return run;
        }

        BotPlayer body() {
            BotPlayer current = manager.get(id);
            if (current == null) throw new IllegalStateException("no body " + name + " is logged in");
            return current;
        }

        UUID id() {
            return id;
        }

        ReflexLayer layer() {
            return ReflexLayer.of(body());
        }

        /** The test kills the body: a respawn is not a failure, and the step to where it appears is not a move. */
        void expectReplacement() {
            replacementExpected = true;
        }

        /** The test moved the body itself (setup, or "a fight carried it off"): the next step is not a move the body made. */
        void relocated() {
            last = null;
        }

        /** A plain adult zombie with nothing on, made without the spawn code's dice, targeting whoever it likes. */
        Zombie zombie(Vec3 relative) {
            ServerLevel level = helper.getLevel();
            Zombie zombie = EntityType.ZOMBIE.create(level);
            place(zombie, relative);
            return zombie;
        }

        /** A zombie in daylight, with a helmet so that the sun does not burn it before the body can deal with it. */
        Zombie helmetedZombie(Vec3 relative) {
            Zombie zombie = zombie(relative);
            zombie.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
            return zombie;
        }

        Creeper creeper(Vec3 relative) {
            ServerLevel level = helper.getLevel();
            Creeper creeper = EntityType.CREEPER.create(level);
            place(creeper, relative);
            return creeper;
        }

        /** A creeper that stands where it is put (no AI) and is after the body. */
        Creeper pinnedCreeper(Vec3 relative) {
            Creeper creeper = EntityType.CREEPER.create(helper.getLevel());
            creeper.setNoAi(true);
            place(creeper, relative);
            creeper.setTarget(body());
            return creeper;
        }

        /** A skeleton that stands where it is put (no AI), has the body as its target, and shoots only when the test says so. */
        Skeleton sniper(Vec3 relative) {
            Skeleton skeleton = EntityType.SKELETON.create(helper.getLevel());
            skeleton.setNoAi(true);
            place(skeleton, relative);
            skeleton.setTarget(body());
            return skeleton;
        }

        /**
         * A pillager with a loaded crossbow in its hand, its brain on and persistent, put after the body and told to
         * be after it (a pillager picks the body as its target on its own; this starts the hold at once). Where it has
         * to stay put the test pens it with fences, never by switching its brain off.
         */
        Pillager shooter(Vec3 relative) {
            ServerLevel level = helper.getLevel();
            Pillager pillager = EntityType.PILLAGER.create(level);
            ItemStack crossbow = new ItemStack(Items.CROSSBOW);
            crossbow.set(DataComponents.CHARGED_PROJECTILES, ChargedProjectiles.of(new ItemStack(Items.ARROW)));
            pillager.setItemSlot(EquipmentSlot.MAINHAND, crossbow);
            place(pillager, relative);
            pillager.setTarget(body());
            return pillager;
        }

        void place(Mob mob, Vec3 relative) {
            Vec3 at = helper.absoluteVec(relative);
            mob.moveTo(at.x, at.y, at.z, 0.0F, 0.0F);
            mob.setPersistenceRequired();
            helper.getLevel().addFreshEntity(mob);
            mobs.add(mob);
        }

        /** The world's time of day, put back when the test ends. */
        void setDayTime(long time) {
            ServerLevel level = helper.getLevel();
            if (savedDayTime == Long.MIN_VALUE) savedDayTime = level.getDayTime();
            level.setDayTime(time);
        }

        /**
         * Every tick until the test ends: fails on a move of more than a block in one tick, on a reflex that threw, and
         * when {@code budget} ticks pass, then runs {@code check}, which passes or fails the test. Whatever it throws,
         * the body is cleaned up first. A move the game itself makes is not the body's: the step into a bed and the one
         * out of it (two ticks, up to {@link #BED_STEP}); while the body lies in its bed it must not move at all.
         */
        void each(int budget, Runnable check) {
            helper.onEachTick(() -> {
                if (over) return;
                try {
                    ticks++;
                    BotPlayer body = body();
                    Vec3 now = body.position();
                    if (body != lastBody && lastBody != null) {
                        helper.assertTrue(replacementExpected, "the body died and was replaced: " + watch.summary());
                        last = null;
                    }
                    boolean sleeping = body.isSleeping();
                    boolean bedStep = sleeping != wasSleeping;
                    if (last != null) {
                        double step = now.distanceTo(last);
                        helper.assertTrue(step <= (bedStep ? BED_STEP : 1.0), "moved " + step + " blocks in one tick at tick " + ticks);
                    }
                    last = now;
                    lastBody = body;
                    wasSleeping = sleeping;
                    helper.assertTrue(ReflexLayer.faults() == faultsAtStart, "a reflex threw (see the log)");
                    if (ticks > budget) {
                        helper.fail("not done after " + budget + " ticks: " + ReflexLayer.of(body).status() + "; " + watch.summary()
                            + "; at " + helper.relativeVec(body.position()));
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
            helper.succeed();
        }

        void cleanup() {
            if (over) return;
            over = true;
            for (Entity mob : mobs) mob.discard();
            if (savedDayTime != Long.MIN_VALUE) helper.getLevel().setDayTime(savedDayTime);
            BotPlayer current = manager.get(id);
            if (current != null) {
                BotNavigator.of(current).stop();
                manager.despawn(current);
            }
            try {
                manager.deleteSaved(id);
            } catch (BodyException ignored) {
                // Still logged in only if despawn did not take; the unique name keeps it from mattering.
            }
        }
    }
}
