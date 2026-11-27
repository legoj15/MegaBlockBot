package com.legoj15.megablockbot.game.gametest;

import com.legoj15.megablockbot.MegaBlockBot;
import com.legoj15.megablockbot.core.exec.ReflexHold;
import com.legoj15.megablockbot.game.body.BodyException;
import com.legoj15.megablockbot.game.body.BodyManager;
import com.legoj15.megablockbot.game.body.BotBrain;
import com.legoj15.megablockbot.game.body.BotPlayer;
import com.legoj15.megablockbot.game.body.DeathRecord;
import com.legoj15.megablockbot.game.nav.BotNavigator;
import com.legoj15.megablockbot.game.reflex.ReflexConstants;
import com.legoj15.megablockbot.game.reflex.ReflexLayer;
import com.legoj15.megablockbot.game.safety.BlockSafety;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
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
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
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
 * the end. The test that has to be dusk sets the world's time and puts it back; it has a batch of its own, because
 * tests of one batch run side by side in one world.
 */
public final class ReflexGameTests {
    private static final String RUN_ID = Integer.toString((int) (System.nanoTime() & 0xFFFFF), 36);
    private static final int FLOOR_Y = 1;
    /** The first height of the roof of a roofed room: above anything a body does in it. */
    private static final int ROOF_Y = FLOOR_Y + 7;

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
                helper.assertTrue(run.watch.lastCause.contains("retreating from creeper"), "the body is not retreating: " + run.watch.summary());
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
            helper.assertTrue(run.layer().status().contains("nothing to build"), "the status does not say why: " + run.layer().status());
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
        BlockPos foot = new BlockPos(10, FLOOR_Y + 1, 7);
        BlockPos head = foot.east();
        helper.setBlock(foot, Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.EAST).setValue(BedBlock.PART, BedPart.FOOT));
        helper.setBlock(head, Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.EAST).setValue(BedBlock.PART, BedPart.HEAD));
        // What the loader's placement hook would have recorded had the body placed it.
        BlockSafety safety = BlockSafety.of(helper.getLevel().getServer());
        safety.noteBotPlaced(body, helper.getLevel(), helper.absolutePos(foot));
        safety.noteBotPlaced(body, helper.getLevel(), helper.absolutePos(head));
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
     * a walk by the navigator, started whenever it is not running and not done) and the navigator for it. It keeps
     * what the tests ask about the holds.
     */
    private static final class Watch implements BotBrain {
        int heldTicks;
        int holdsStarted;
        int holdsEnded;
        int blockingTicks;
        boolean wasHeld;
        boolean navigatorRanWhileHeld;
        final Set<String> reflexes = new LinkedHashSet<>();
        String lastCause = "";
        private BlockPos walkGoal;
        int walkStarts;
        boolean arrived;

        void walkTo(BlockPos goal) {
            walkGoal = goal;
        }

        @Override
        public void tick(BotPlayer body) {
            ReflexHold hold = ReflexLayer.of(body).tick(body);
            BotNavigator nav = BotNavigator.of(body);
            if (body.isBlocking()) blockingTicks++;
            if (hold.held()) {
                if (!wasHeld) holdsStarted++;
                heldTicks++;
                lastCause = hold.cause();
                int colon = hold.cause().indexOf(':');
                reflexes.add(colon < 0 ? hold.cause() : hold.cause().substring(0, colon));
                // The stand-in's command must not run while the layer holds the body (the layer's own retreat may; a fight must not).
                if (nav.state() == BotNavigator.State.WORKING && hold.cause().startsWith("mobs: fighting")) navigatorRanWhileHeld = true;
            } else {
                if (wasHeld) holdsEnded++;
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
            }
            wasHeld = hold.held();
        }

        String summary() {
            return "holds " + holdsStarted + "/" + holdsEnded + ", " + heldTicks + " ticks held by " + reflexes + ", last cause '" + lastCause + "'";
        }

        @Override public void onDeath(BotPlayer body, DeathRecord death) {}
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

        ReflexLayer layer() {
            return ReflexLayer.of(body());
        }

        /** A plain adult zombie with nothing on, made without the spawn code's dice, targeting whoever it likes. */
        Zombie zombie(Vec3 relative) {
            ServerLevel level = helper.getLevel();
            Zombie zombie = EntityType.ZOMBIE.create(level);
            place(zombie, relative);
            return zombie;
        }

        Creeper creeper(Vec3 relative) {
            ServerLevel level = helper.getLevel();
            Creeper creeper = EntityType.CREEPER.create(level);
            place(creeper, relative);
            return creeper;
        }

        private void place(Mob mob, Vec3 relative) {
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
         * the body is cleaned up first.
         */
        void each(int budget, Runnable check) {
            helper.onEachTick(() -> {
                if (over) return;
                try {
                    ticks++;
                    BotPlayer body = body();
                    Vec3 now = body.position();
                    helper.assertTrue(lastBody == null || lastBody == body, "the body died and was replaced: " + watch.summary());
                    // Going to sleep puts a body in its bed, as it does a player.
                    if (body.isSleeping()) last = null;
                    if (last != null) {
                        double step = now.distanceTo(last);
                        helper.assertTrue(step <= 1.0, "moved " + step + " blocks in one tick at tick " + ticks);
                    }
                    last = now;
                    lastBody = body;
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
