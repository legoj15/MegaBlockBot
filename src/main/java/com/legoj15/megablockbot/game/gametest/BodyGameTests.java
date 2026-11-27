package com.legoj15.megablockbot.game.gametest;

import com.legoj15.megablockbot.MegaBlockBot;
import com.legoj15.megablockbot.game.body.BodyConstants;
import com.legoj15.megablockbot.game.body.BodyException;
import com.legoj15.megablockbot.game.body.BodyManager;
import com.legoj15.megablockbot.game.body.BotBrain;
import com.legoj15.megablockbot.game.body.BotPlayer;
import com.legoj15.megablockbot.game.body.DeathRecord;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.netty.channel.embedded.EmbeddedChannel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.GameProfileCache;
import net.minecraft.server.players.PlayerList;
import net.minecraft.server.players.SleepStatus;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

/**
 * The acceptance check of the body (design 3.1, issue 6): the claim "a client-less player moves under plain inputs"
 * can only be measured in a running server, so each test spawns a real {@link BotPlayer} in the test arena, drives it
 * through its {@code input()} and reads where it ends up. Nothing here teleports a body or sets its velocity.
 *
 * <p>Every body has a name unique to this run (the GameTest world's player data outlives a run, and a saved body
 * would otherwise come back where an earlier run left it) and is despawned and forgotten when its test ends.
 * Relative coordinates: y=1 is a stone floor across the whole arena, so feet standing on it are at y=2.0.
 */
public final class BodyGameTests {
    private static final int FLOOR_Y = 1;
    private static final double FLOOR_TOP = FLOOR_Y + 1.0;
    private static final String RUN_ID = Integer.toString((int) (System.nanoTime() & 0xFFFFF), 36);

    private BodyGameTests() {}

    // ---------------------------------------------------------------------------------------------------------
    // Movement
    // ---------------------------------------------------------------------------------------------------------

    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 80)
    public static void fallsUnderGravity(GameTestHelper helper) {
        floor(helper);
        Rig rig = Rig.spawn(helper, "fall", new Vec3(8.5, FLOOR_TOP + 5.0, 8.5), 0.0F, 80);
        double floorTop = helper.absoluteVec(new Vec3(0, FLOOR_TOP, 0)).y;
        List<Double> ys = new ArrayList<>();
        rig.onEachTick(() -> {
            BotPlayer body = rig.body();
            ys.add(body.getY());
            if (!body.onGround()) {
                helper.assertTrue(ys.size() <= 40, "still in the air after 40 ticks, y=" + body.getY());
                return;
            }
            double biggestStep = 0;
            int fallingTicks = 0;
            for (int i = 1; i < ys.size(); i++) {
                double step = ys.get(i - 1) - ys.get(i);
                helper.assertTrue(step >= -1.0E-9, "went up while falling: " + ys);
                biggestStep = Math.max(biggestStep, step);
                if (step > 0.05) fallingTicks++;
            }
            // A teleport to the floor is one step of 5 blocks. Gravity over a 5 block fall is never above about 1 per tick.
            helper.assertTrue(biggestStep < 1.5, "fell " + biggestStep + " blocks in a single tick: " + ys);
            helper.assertTrue(fallingTicks >= 6, "reached the floor in " + fallingTicks + " falling ticks, not a fall: " + ys);
            helper.assertTrue(Math.abs(body.getY() - floorTop) < 1.0E-3, "ended at y=" + body.getY() + ", the floor is at " + floorTop);
            rig.pass();
        });
    }

    /** Fall damage is the other half of gravity: ServerPlayer ignores it unless something feeds it the fall. */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 200)
    public static void fallDamageHurts(GameTestHelper helper) {
        floor(helper);
        // Stands on one block, 8 above the floor. A new body is invulnerable for 60 ticks (vanilla), so the block is
        // taken away after that; the body then falls 8 blocks: 8 - 3 safe = 5 damage.
        BlockPos pillar = new BlockPos(8, FLOOR_Y + 8, 8);
        helper.setBlock(pillar, Blocks.STONE);
        Rig rig = Rig.spawn(helper, "hurt", new Vec3(8.5, pillar.getY() + 1.0, 8.5), 0.0F, 200);
        boolean[] dropped = {false};
        boolean[] leftGround = {false};
        int[] tick = {0};
        rig.onEachTick(() -> {
            BotPlayer body = rig.body();
            tick[0]++;
            if (tick[0] == 65) {
                helper.assertTrue(body.onGround(), "should be standing on the pillar");
                helper.assertTrue(body.getHealth() == body.getMaxHealth(), "hurt before the fall");
                helper.setBlock(pillar, Blocks.AIR);
                dropped[0] = true;
            }
            if (!dropped[0]) return;
            if (!body.onGround()) {
                leftGround[0] = true;
                return;
            }
            if (!leftGround[0]) return;
            float lost = body.getMaxHealth() - body.getHealth();
            helper.assertTrue(Math.abs(lost - 5.0F) <= 1.0F, "landed after an 8 block fall having lost " + lost + " health, expected 5");
            rig.pass();
        });
    }

    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 120)
    public static void walksForwardOnInput(GameTestHelper helper) {
        floor(helper);
        Rig rig = Rig.spawn(helper, "walk", new Vec3(8.5, FLOOR_TOP, 2.5), 0.0F, 120);
        int[] tick = {0};
        Vec3[] start = new Vec3[1];
        Vec3[] beforeLast = new Vec3[1];
        rig.onEachTick(() -> {
            BotPlayer body = rig.body();
            int t = ++tick[0];
            if (t == 5) {
                start[0] = body.position();
                body.input().forward = 1.0F;
            }
            if (t == 45) {
                Vec3 look = body.getLookAngle();
                Vec3 flat = new Vec3(look.x, 0, look.z).normalize();
                double along = body.position().subtract(start[0]).dot(flat);
                helper.assertTrue(along >= 3.0, "40 ticks of forward moved it " + along + " blocks along its look direction, expected at least 3");
                body.input().reset();
            }
            if (t == 54) beforeLast[0] = body.position();
            if (t == 55) {
                double lastTick = body.position().distanceTo(beforeLast[0]);
                helper.assertTrue(lastTick < 0.01, "10 ticks after the input ended it still moves " + lastTick + " blocks per tick");
                rig.pass();
            }
        });
    }

    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 80)
    public static void jumpsOnInput(GameTestHelper helper) {
        floor(helper);
        Rig rig = Rig.spawn(helper, "jump", new Vec3(8.5, FLOOR_TOP, 8.5), 0.0F, 80);
        double floorTop = helper.absoluteVec(new Vec3(0, FLOOR_TOP, 0)).y;
        int[] tick = {0};
        double[] peak = {0};
        boolean[] airborne = {false};
        rig.onEachTick(() -> {
            BotPlayer body = rig.body();
            int t = ++tick[0];
            if (t <= 5) {
                helper.assertTrue(body.getY() < floorTop + 0.01, "left the floor with no jump input, y=" + body.getY());
            }
            if (t == 5) body.input().jump = true;
            if (t == 6) body.input().jump = false; // one jump, not a bunny hop
            if (t < 6) return; // a body's onGround is false until its first tick: only watch from the jump input on
            peak[0] = Math.max(peak[0], body.getY() - floorTop);
            if (!body.onGround()) airborne[0] = true;
            if (airborne[0] && body.onGround()) {
                helper.assertTrue(peak[0] >= 1.0, "the jump rose only " + peak[0] + " blocks");
                helper.assertTrue(Math.abs(body.getY() - floorTop) < 1.0E-3, "landed at y=" + body.getY() + ", the floor is at " + floorTop);
                rig.pass();
            }
        });
    }

    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 150)
    public static void stepsUpOneBlockWithJump(GameTestHelper helper) {
        floor(helper);
        // A one-block ledge across the arena from z=6 on.
        for (int x = 0; x < 16; x++) {
            for (int z = 6; z < 16; z++) helper.setBlock(x, FLOOR_Y + 1, z, Blocks.STONE);
        }
        double ledgeTop = helper.absoluteVec(new Vec3(0, FLOOR_TOP + 1.0, 0)).y;
        double ledgeEdgeZ = helper.absoluteVec(new Vec3(0, 0, 6.0)).z;
        double floorTop = helper.absoluteVec(new Vec3(0, FLOOR_TOP, 0)).y;
        Rig climber = Rig.spawn(helper, "step", new Vec3(6.5, FLOOR_TOP, 2.5), 0.0F, 150);
        // Control: the same walk without the jump. If it also ended up on the ledge the test would prove nothing.
        Rig walker = Rig.spawn(helper, "wall", new Vec3(10.5, FLOOR_TOP, 2.5), 0.0F, 150);
        climber.alongside(walker);
        int[] tick = {0};
        climber.onEachTick(() -> {
            int t = ++tick[0];
            if (t == 3) {
                climber.body().input().forward = 1.0F;
                climber.body().input().jump = true;
                walker.body().input().forward = 1.0F;
            }
            BotPlayer body = climber.body();
            boolean onLedge = body.onGround() && Math.abs(body.getY() - ledgeTop) < 1.0E-3 && body.getZ() > ledgeEdgeZ + 0.3;
            if (!onLedge) return;
            BotPlayer control = walker.body();
            helper.assertTrue(Math.abs(control.getY() - floorTop) < 1.0E-3 && control.getZ() < ledgeEdgeZ,
                "the body without a jump input is not held back by the ledge: y=" + control.getY() + " z=" + control.getZ());
            climber.pass();
        });
    }

    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 100)
    public static void swimsUpWithJump(GameTestHelper helper) {
        floor(helper);
        // A 1x1 pool, 3 deep, walled in so the water stays put: the body starts at the bottom with its head under.
        for (int y = FLOOR_Y + 1; y <= FLOOR_Y + 3; y++) {
            helper.setBlock(7, y, 8, Blocks.STONE);
            helper.setBlock(9, y, 8, Blocks.STONE);
            helper.setBlock(8, y, 7, Blocks.STONE);
            helper.setBlock(8, y, 9, Blocks.STONE);
        }
        for (int y = FLOOR_Y + 1; y <= FLOOR_Y + 3; y++) helper.setBlock(8, y, 8, Blocks.WATER);
        Rig rig = Rig.spawn(helper, "swim", new Vec3(8.5, FLOOR_TOP, 8.5), 0.0F, 100);
        double bottom = helper.absoluteVec(new Vec3(0, FLOOR_TOP, 0)).y;
        int[] tick = {0};
        rig.onEachTick(() -> {
            BotPlayer body = rig.body();
            int t = ++tick[0];
            if (t == 2) {
                helper.assertTrue(body.isEyeInFluid(FluidTags.WATER), "should start with its head under water");
                body.input().jump = true;
            }
            if (t > 2 && !body.isEyeInFluid(FluidTags.WATER)) {
                helper.assertTrue(body.getY() > bottom + 1.0, "head out of the water at y=" + body.getY() + " without having risen");
                rig.pass();
            }
        });
    }

    // ---------------------------------------------------------------------------------------------------------
    // Death, respawn, persistence
    // ---------------------------------------------------------------------------------------------------------

    /** Records what the body layer tells a brain. */
    private static final class RecordingBrain implements BotBrain {
        final List<DeathRecord> deaths = new ArrayList<>();
        final List<BotPlayer> respawnedAs = new ArrayList<>();

        @Override public void tick(BotPlayer body) {}
        @Override public void onDeath(BotPlayer body, DeathRecord death) { deaths.add(death); }
        @Override public void onRespawn(BotPlayer newBody) { respawnedAs.add(newBody); }
        @Override public boolean wantsToStayLoaded() { return false; }
        @Override public void save(CompoundTag tag, HolderLookup.Provider registries) {}
        @Override public void load(CompoundTag tag, HolderLookup.Provider registries) {}
    }

    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 100)
    public static void takesDamageAndDies(GameTestHelper helper) {
        floor(helper);
        Rig rig = Rig.spawn(helper, "dies", new Vec3(8.5, FLOOR_TOP, 8.5), 0.0F, 100);
        BotPlayer original = rig.body();
        RecordingBrain brain = new RecordingBrain();
        original.setBrain(brain);
        original.getInventory().add(new ItemStack(Items.COBBLESTONE, 5));
        int[] tick = {0};
        BlockPos[] diedAt = new BlockPos[1];
        long[] diedAtTime = new long[1];
        rig.onEachTick(() -> {
            int t = ++tick[0];
            if (t == 3) {
                diedAt[0] = original.blockPosition();
                diedAtTime[0] = helper.getLevel().getGameTime();
                // genericKill bypasses the invulnerability a new body has for its first 3 seconds.
                original.hurt(helper.getLevel().damageSources().genericKill(), 1000.0F);
                helper.assertTrue(original.isDeadOrDying(), "a lethal damage source did not kill it");
                helper.assertTrue(brain.deaths.size() == 1, "the brain heard of " + brain.deaths.size() + " deaths, expected 1");
                DeathRecord death = brain.deaths.get(0);
                helper.assertTrue(death.dimension().equals("minecraft:overworld"), "death dimension " + death.dimension());
                helper.assertTrue(death.pos().equals(diedAt[0]), "death position " + death.pos() + " but it died at " + diedAt[0]);
                helper.assertTrue(Math.abs(death.gameTime() - diedAtTime[0]) <= 1, "death time " + death.gameTime() + ", expected " + diedAtTime[0]);
                helper.assertTrue(!death.dropPositions().isEmpty(), "the 5 cobblestone dropped but the record has no drop position");
                for (BlockPos drop : death.dropPositions()) {
                    helper.assertTrue(drop.distManhattan(diedAt[0]) <= 3, "item drop at " + drop + " is nowhere near the death at " + diedAt[0]);
                }
            }
            if (t <= 3) return;
            BotPlayer now = rig.body();
            if (now == original) {
                helper.assertTrue(t < 20, "still the dead body 17 ticks after dying: it was not respawned");
                return;
            }
            // The replacement: a BotPlayer by type, the one the manager returns for the name, alive, with the same brain.
            helper.assertTrue(now.isAlive() && now.getHealth() > 0, "the replacement is not alive");
            helper.assertTrue(now.brain() == brain, "the brain did not move to the respawned body");
            helper.assertTrue(brain.respawnedAs.size() == 1 && brain.respawnedAs.get(0) == now,
                "the brain was handed " + brain.respawnedAs + " on respawn, expected exactly the new body");
            helper.assertTrue(original.brain() == BotBrain.NONE, "the dead body still holds the brain");
            rig.pass();
        });
    }

    /**
     * A body saved dead (the server stopped, or crashed, between its death and its respawn) comes back dead from its
     * player data. Nothing would ever respawn it: its client does not exist to press the button.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 60)
    public static void bodySpawnedDeadComesBackAlive(GameTestHelper helper) {
        floor(helper);
        Rig rig = Rig.spawn(helper, "dead", new Vec3(8.5, FLOOR_TOP, 8.5), 0.0F, 60);
        int[] tick = {0};
        BotPlayer[] spawnedDead = new BotPlayer[1];
        rig.onEachTick(() -> {
            int t = ++tick[0];
            if (t == 3) {
                BotPlayer body = rig.body();
                Vec3 where = body.position();
                body.hurt(helper.getLevel().damageSources().genericKill(), 1000.0F);
                helper.assertTrue(body.isDeadOrDying(), "setup: a lethal damage source did not kill it");
                // Logged out in the same tick, before the end-of-tick respawn: what vanilla saves is a dead body.
                rig.manager().despawn(body);
                BotPlayer back = rig.respawn(where);
                helper.assertTrue(back != body && back.isDeadOrDying(), "setup: the body saved dead did not come back dead (health " + back.getHealth() + ")");
                spawnedDead[0] = back;
                return;
            }
            if (t < 3) return;
            BotPlayer now = rig.body();
            if (now == spawnedDead[0]) {
                helper.assertTrue(t < 15, "the body that was spawned dead was still dead " + (t - 3) + " ticks later: nothing respawned it");
                return;
            }
            helper.assertTrue(now.isAlive() && now.getHealth() > 0, "the replacement is not alive");
            rig.pass();
        });
    }

    /**
     * A body that gave up on its respawn is out of the world, but the server did that, not its owner: with a goal it
     * must come back at the next server start like any body with a goal (the record's stay-loaded flag is what the start
     * reads). The control has no goal and is not asked back. The respawn is made to fail without a throw, one body at a
     * time, because vanilla's does not fail on demand.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 60)
    public static void bodyThatGaveUpRespawningStillComesBackWithTheServer(GameTestHelper helper) {
        floor(helper);
        Supplier<BotBrain> factory = CounterBrain::new;
        Rig goal = Rig.spawn(helper, "gu_goal", new Vec3(5.5, FLOOR_TOP, 8.5), 0.0F, 60, factory);
        Rig idle = Rig.spawn(helper, "gu_idle", new Vec3(10.5, FLOOR_TOP, 8.5), 0.0F, 60, factory);
        goal.alongside(idle);
        int[] tick = {0};
        goal.onEachTick(() -> {
            int t = ++tick[0];
            if (t == 3) {
                ((CounterBrain) goal.body().brain()).stay = true; // a goal set since the record was last brought up to date
                for (Rig rig : List.of(goal, idle)) {
                    rig.manager().failRespawnsOf(rig.id(), true);
                    rig.body().hurt(helper.getLevel().damageSources().genericKill(), 1000.0F);
                    helper.assertTrue(rig.body().isDeadOrDying(), "setup: a lethal damage source did not kill " + rig.name());
                }
                return;
            }
            if (t < 3) return;
            if (goal.isLoggedIn() || idle.isLoggedIn()) {
                helper.assertTrue(t < 20, "still logged in " + (t - 3) + " ticks after the death: the respawn was not given up on");
                return;
            }
            BodyManager.RecordSummary kept = goal.manager().recordSummary(goal.name());
            BodyManager.RecordSummary dropped = idle.manager().recordSummary(idle.name());
            helper.assertTrue(kept != null && kept.stayLoaded(), "the body with a goal was not recorded as wanting to stay loaded after it gave up: " + kept);
            helper.assertTrue(dropped != null && !dropped.stayLoaded(), "the body with no goal was recorded as wanting to stay loaded: " + dropped);
            goal.pass();
        });
    }

    /**
     * A respawn that fails halfway (the replacement is built, {@code restoreFrom} has copied the brain onto it, and
     * then vanilla or a mod's event handler throws before the replacement is listed) must not cost the body its
     * brain: the manager retries on a later tick, and the body that then stands under the name must carry the same
     * brain, while the one that died has let go of it. The first restore of this body is made to throw after the copy
     * ({@code failRestoresOf}), which nothing vanilla does on demand.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 100)
    public static void brainIsNotLostToARespawnThatThrowsHalfway(GameTestHelper helper) {
        floor(helper);
        Supplier<BotBrain> factory = CounterBrain::new;
        Rig rig = Rig.spawn(helper, "halfway", new Vec3(8.5, FLOOR_TOP, 8.5), 0.0F, 100, factory);
        BotPlayer[] died = new BotPlayer[1];
        CounterBrain[] brain = new CounterBrain[1];
        int[] tick = {0};
        rig.onEachTick(() -> {
            int t = ++tick[0];
            if (t == 3) {
                BotPlayer body = rig.body();
                died[0] = body;
                brain[0] = (CounterBrain) body.brain();
                brain[0].value = 42; // a goal in progress
                rig.manager().failRestoresOf(rig.id(), 1);
                body.hurt(helper.getLevel().damageSources().genericKill(), 1000.0F);
                helper.assertTrue(body.isDeadOrDying(), "setup: a lethal damage source did not kill it");
                return;
            }
            if (t <= 3) return;
            BotPlayer now = rig.body();
            if (now == died[0]) {
                helper.assertTrue(t < 20, "still the dead body " + (t - 3) + " ticks after dying: the respawn was never retried");
                return;
            }
            helper.assertTrue(now.brain() == brain[0], "the body that stands after the retry has brain " + now.brain()
                + ", not the one whose goal was in progress");
            helper.assertTrue(((CounterBrain) now.brain()).value == 42, "the brain lost its state across the failed respawn");
            helper.assertTrue(died[0].brain() == BotBrain.NONE, "the body that died still holds the brain");
            rig.pass();
        });
    }

    /**
     * The same throw on every attempt, until the manager gives up after {@code MAX_RESPAWN_FAILURES} and despawns the
     * body: the give-up logout must save the brain the body had, so a goal in progress comes back with it. Where
     * {@code bodyThatGaveUpRespawningStillComesBackWithTheServer} models the give-up with failures that do not throw,
     * this one uses the real halfway kind.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 100)
    public static void brainIsNotLostToRespawnsThatThrowUntilTheGiveUp(GameTestHelper helper) {
        floor(helper);
        Supplier<BotBrain> factory = CounterBrain::new;
        Rig rig = Rig.spawn(helper, "thrown", new Vec3(8.5, FLOOR_TOP, 8.5), 0.0F, 100, factory);
        Vec3[] where = new Vec3[1];
        int[] tick = {0};
        rig.onEachTick(() -> {
            int t = ++tick[0];
            if (t == 3) {
                BotPlayer body = rig.body();
                ((CounterBrain) body.brain()).value = 42; // a goal in progress
                where[0] = body.position();
                rig.manager().failRestoresOf(rig.id(), BodyConstants.MAX_RESPAWN_FAILURES);
                body.hurt(helper.getLevel().damageSources().genericKill(), 1000.0F);
                helper.assertTrue(body.isDeadOrDying(), "setup: a lethal damage source did not kill it");
                return;
            }
            if (t <= 3) return;
            if (rig.isLoggedIn()) {
                helper.assertTrue(t < 20, "still logged in " + (t - 3) + " ticks after dying: the respawns were not given up on");
                return;
            }
            BotPlayer back = rig.respawn(where[0]);
            helper.assertTrue(back.brain() instanceof CounterBrain, "the body came back with brain " + back.brain());
            int value = ((CounterBrain) back.brain()).value;
            helper.assertTrue(value == 42, "the goal in progress was lost to the failed respawns: the brain came back with " + value);
            rig.pass();
        });
    }

    /**
     * A scripted despawn (a command, a task, the idle policy) in the tick a body dies in a hardcore world comes before
     * the end-of-tick respawn that would have retired it, so it has to mark the record itself: a body that died there is
     * never spawned again. The world is not hardcore in a GameTest, so the manager is told it is, for these few calls.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 40)
    public static void despawnInTheTickOfAHardcoreDeathMarksTheRecord(GameTestHelper helper) {
        floor(helper);
        Rig rig = Rig.spawn(helper, "hard", new Vec3(8.5, FLOOR_TOP, 8.5), 0.0F, 40);
        helper.runAfterDelay(3, rig.guard(() -> {
            BotPlayer body = rig.body();
            rig.manager().useHardcore(() -> true);
            try {
                body.hurt(helper.getLevel().damageSources().genericKill(), 1000.0F);
                helper.assertTrue(body.isDeadOrDying(), "setup: a lethal damage source did not kill it");
                rig.manager().despawn(body);
            } finally {
                rig.manager().useHardcore(null);
            }
            helper.assertTrue(!rig.isLoggedIn(), "the body is still logged in after its despawn");
            BodyManager.RecordSummary record = rig.manager().recordSummary(rig.name());
            helper.assertTrue(record != null && record.dead(), "the record of a body that died in a hardcore world and was despawned in the same tick does not say it died: " + record);
            rig.pass();
        }));
    }

    /**
     * The stop that crashed skipped the stopping hook, so {@code PlayerList.removeAll} (which disconnects each player
     * by index) meets the bodies, and a body's disconnect used to take it out of the list at once, which made the walk
     * jump over the entry after it: a real player listed after a body never got its disconnect. This walks the player
     * list the way {@code removeAll} does, by index, calling the same {@code connection.disconnect} on the body and on a
     * real player listed right after it (and only on those two: the other players of the other tests are not to be
     * disconnected). The manager is told the server is in its final stop, which a GameTest cannot be. The real player
     * is one whose connection keeps its channel open and its place in the list, as a real one's does until the channel
     * closes. The body is left logged in and the record is brought up to date, because nothing after this brings it up.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 40)
    public static void shutdownWalkReachesARealPlayerListedAfterABody(GameTestHelper helper) {
        floor(helper);
        Supplier<BotBrain> factory = CounterBrain::new;
        Rig rig = Rig.spawn(helper, "stop", new Vec3(8.5, FLOOR_TOP, 8.5), 0.0F, 40, factory);
        helper.runAfterDelay(2, rig.guard(() -> {
            ServerLevel level = helper.getLevel();
            MinecraftServer server = level.getServer();
            PlayerList players = server.getPlayerList();
            // Out and in again in this one step, so that the real player placed next is right behind the body in the list.
            Vec3 where = rig.body().position();
            rig.manager().despawn(rig.body());
            BotPlayer body = rig.respawn(where);
            ((CounterBrain) body.brain()).stay = true; // a goal set since the record was last brought up to date
            ProbeConnection connection = ProbeConnection.create();
            ServerPlayer real = standalonePlayer(server, level, UUID.randomUUID(), "gt_stopreal");
            join(server, real, connection);
            try {
                List<ServerPlayer> list = players.getPlayers();
                helper.assertTrue(list.indexOf(real) == list.indexOf(body) + 1, "setup: the real player is not listed right after the body");
                rig.manager().useServerStopped(() -> true);
                try {
                    Component reason = Component.translatable("multiplayer.disconnect.server_shutdown");
                    for (int i = 0; i < list.size(); i++) {
                        ServerPlayer player = list.get(i);
                        if (player == body || player == real) player.connection.disconnect(reason);
                    }
                } finally {
                    rig.manager().useServerStopped(null);
                }
                helper.assertTrue(connection.disconnectSent, "the real player listed after the body never got its disconnect");
                BodyManager.RecordSummary record = rig.manager().recordSummary(rig.name());
                helper.assertTrue(record != null && record.stayLoaded(), "the record was not brought up to date for the body's goal: " + record);
                rig.pass();
            } finally {
                leave(server, real);
            }
        }));
    }

    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 40)
    public static void inventorySurvivesDespawnAndSpawn(GameTestHelper helper) {
        floor(helper);
        Rig rig = Rig.spawn(helper, "inv", new Vec3(8.5, FLOOR_TOP, 8.5), 0.0F, 40);
        helper.runAfterDelay(3, rig.guard(() -> {
            BotPlayer body = rig.body();
            body.getInventory().add(new ItemStack(Items.DIAMOND, 3));
            body.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
            body.setHealth(7.0F);
            body.getFoodData().setFoodLevel(12);
            Vec3 where = body.position();

            rig.manager().despawn(body);
            helper.assertTrue(rig.manager().get(rig.name()) == null, "still logged in after despawn");
            Vec3 elsewhere = where.add(3.0, 0.0, 3.0);
            BotPlayer back = rig.respawn(elsewhere);

            helper.assertTrue(count(back, Items.DIAMOND) == 3, "diamonds after respawn: " + count(back, Items.DIAMOND));
            helper.assertTrue(back.getItemBySlot(EquipmentSlot.HEAD).is(Items.IRON_HELMET), "helmet lost");
            helper.assertTrue(back.getHealth() == 7.0F, "health after respawn: " + back.getHealth());
            helper.assertTrue(back.getFoodData().getFoodLevel() == 12, "hunger after respawn: " + back.getFoodData().getFoodLevel());
            helper.assertTrue(back.position().distanceTo(where) < 0.5, "came back at " + back.position() + ", not where it was (" + where + ")");
            helper.assertTrue(back.position().distanceTo(elsewhere) > 1.0, "took the requested position " + elsewhere + " over its saved one");
            rig.pass();
        }));
    }

    /** A brain with one saved number. */
    private static final class CounterBrain implements BotBrain {
        int value;
        boolean stay;

        @Override public void tick(BotPlayer body) {}
        @Override public void onDeath(BotPlayer body, DeathRecord death) {}
        @Override public void onRespawn(BotPlayer newBody) {}
        @Override public boolean wantsToStayLoaded() { return stay; }
        @Override public void save(CompoundTag tag, HolderLookup.Provider registries) { tag.putInt("value", value); }
        @Override public void load(CompoundTag tag, HolderLookup.Provider registries) { value = tag.getInt("value"); }
    }

    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 40)
    public static void brainStateSurvivesDespawnAndSpawn(GameTestHelper helper) {
        floor(helper);
        Supplier<BotBrain> factory = CounterBrain::new;
        Rig rig = Rig.spawn(helper, "brain", new Vec3(8.5, FLOOR_TOP, 8.5), 0.0F, 40, factory);
        helper.runAfterDelay(3, rig.guard(() -> {
            BotPlayer body = rig.body();
            ((CounterBrain) body.brain()).value = 42;
            Vec3 where = body.position();
            rig.manager().despawn(body);
            BotPlayer back = rig.respawn(where);
            helper.assertTrue(back.brain() instanceof CounterBrain, "the respawned body has brain " + back.brain());
            int value = ((CounterBrain) back.brain()).value;
            helper.assertTrue(value == 42, "the brain's saved value came back as " + value + ", expected 42");
            rig.pass();
        }));
    }

    /** A brain whose save and load throw, to show a bad save does not take the body down with it. */
    private static final class ExplodingBrain implements BotBrain {
        final boolean failSave;
        final boolean failLoad;
        int loadsAttempted;

        ExplodingBrain(boolean failSave, boolean failLoad) {
            this.failSave = failSave;
            this.failLoad = failLoad;
        }

        @Override public void tick(BotPlayer body) {}
        @Override public void onDeath(BotPlayer body, DeathRecord death) {}
        @Override public void onRespawn(BotPlayer newBody) {}
        @Override public boolean wantsToStayLoaded() { return false; }

        @Override
        public void save(CompoundTag tag, HolderLookup.Provider registries) {
            if (failSave) throw new IllegalStateException("test brain: save fails");
            tag.putInt("marker", 1);
        }

        @Override
        public void load(CompoundTag tag, HolderLookup.Provider registries) {
            loadsAttempted++;
            if (failLoad) throw new IllegalStateException("test brain: load fails");
        }
    }

    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 40)
    public static void badBrainDoesNotBlockSaveOrSpawn(GameTestHelper helper) {
        floor(helper);
        boolean[] failSave = {true};
        Supplier<BotBrain> factory = () -> new ExplodingBrain(failSave[0], true);
        Rig rig = Rig.spawn(helper, "bad", new Vec3(8.5, FLOOR_TOP, 8.5), 0.0F, 40, factory);
        helper.runAfterDelay(3, rig.guard(() -> {
            rig.body().getInventory().add(new ItemStack(Items.DIAMOND, 2));
            Vec3 where = rig.body().position();

            // 1. The brain cannot save: the body is still saved, without it.
            rig.manager().despawn(rig.body());
            BotPlayer second = rig.respawn(where);
            helper.assertTrue(count(second, Items.DIAMOND) == 2, "a brain that failed to save cost the body its inventory");

            // 2. The brain saves, then cannot load: the body still comes back, with a fresh brain.
            failSave[0] = false;
            rig.manager().despawn(second);
            BotPlayer third = rig.respawn(where);          // brain made with failSave=false; nothing saved for it yet
            rig.manager().despawn(third);                   // saves {marker}
            BotPlayer fourth = rig.respawn(where);          // loads {marker} into a brain that throws
            helper.assertTrue(rig.manager().get(rig.name()) == fourth, "the body did not come back after a brain failed to load");
            helper.assertTrue(count(fourth, Items.DIAMOND) == 2, "a brain that failed to load cost the body its inventory");
            ExplodingBrain current = (ExplodingBrain) fourth.brain();
            helper.assertTrue(current.loadsAttempted == 0, "the body kept the brain whose load threw (" + current.loadsAttempted + " load attempts)");
            rig.pass();
        }));
    }

    // ---------------------------------------------------------------------------------------------------------
    // Decisions of the issue: sleep, owner, advancements
    // ---------------------------------------------------------------------------------------------------------

    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 40)
    public static void doesNotBlockSleep(GameTestHelper helper) {
        floor(helper);
        Rig rig = Rig.spawn(helper, "sleep", new Vec3(8.5, FLOOR_TOP, 8.5), 0.0F, 40);
        helper.runAfterDelay(2, rig.guard(() -> {
            ServerLevel level = helper.getLevel();
            MinecraftServer server = level.getServer();
            ServerPlayer sleeper = standalonePlayer(server, level, UUID.randomUUID(), "gt_sleeper");
            sleeper.setSleepingPos(helper.absolutePos(new BlockPos(2, 2, 2)));
            helper.assertTrue(sleeper.isSleeping() && !rig.body().isSleeping(), "setup: one sleeper and one awake body");

            // Control: an awake ordinary player does count, so the requirement is NOT met at 100 percent.
            ServerPlayer awakePlayer = standalonePlayer(server, level, UUID.randomUUID(), "gt_awake");
            SleepStatus withPlayer = new SleepStatus();
            withPlayer.update(List.of(sleeper, awakePlayer));
            helper.assertTrue(!withPlayer.areEnoughSleeping(100), "control failed: an awake player should hold the night");

            // The body does not: the one sleeper is all the active players there are.
            SleepStatus withBody = new SleepStatus();
            withBody.update(List.of(sleeper, rig.body()));
            helper.assertTrue(withBody.areEnoughSleeping(100), "an awake body holds the night: " + withBody.amountSleeping() + " sleeping, "
                + withBody.sleepersNeeded(100) + " needed");
            rig.pass();
        }));
    }

    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 40)
    public static void mayCommandRules(GameTestHelper helper) {
        floor(helper);
        UUID ownerId = UUID.randomUUID();
        Rig rig = Rig.spawn(helper, "owner", new Vec3(8.5, FLOOR_TOP, 8.5), 0.0F, 40, null, new GameProfile(ownerId, "TheOwner"));
        helper.runAfterDelay(2, rig.guard(() -> {
            ServerLevel level = helper.getLevel();
            MinecraftServer server = level.getServer();
            BotPlayer body = rig.body();
            helper.assertTrue(ownerId.equals(body.ownerId()) && "TheOwner".equals(body.ownerName()), "owner not recorded on the body");

            ServerPlayer owner = standalonePlayer(server, level, ownerId, "TheOwner");
            Pig stranger = EntityType.PIG.create(level);
            // Not a player, but carries the owner's UUID: the owner rule is for the owner's own player only.
            Pig impostor = EntityType.PIG.create(level);
            impostor.setUUID(ownerId);
            helper.assertTrue(body.mayCommand(source(server, level, 0, owner)), "the owner may not command its body");
            helper.assertTrue(!body.mayCommand(source(server, level, 0, impostor)), "a non-player entity carrying the owner's UUID may command the body");
            helper.assertTrue(!body.mayCommand(source(server, level, 0, stranger)), "a stranger may command a body");
            helper.assertTrue(!body.mayCommand(source(server, level, 0, null)), "an anonymous level-0 source may command a body");
            helper.assertTrue(body.mayCommand(source(server, level, 2, stranger)), "a level-2 source may not command a body");
            helper.assertTrue(body.mayCommand(server.createCommandSourceStack()), "the console may not command a body");
            helper.assertTrue(!BodyManager.mayCommand(null, source(server, level, 0, stranger)), "a stranger may command an ownerless body");
            helper.assertTrue(BodyManager.mayCommand(null, server.createCommandSourceStack()), "the console may not command an ownerless body");
            rig.pass();
        }));
    }

    /**
     * The cap is on bodies owned, spawned or not, and per owner. The control is a level-2 source (not limited) and
     * another player (not limited by the first one's bodies). Spawned through the command, which is where the rule is.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 40)
    public static void unprivilegedOwnerIsCappedAtTwoBodies(GameTestHelper helper) {
        floor(helper);
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        ServerPlayer player = playerInArena(helper, "gt_capper");
        ServerPlayer other = playerInArena(helper, "gt_other");
        Rig first = Rig.named(helper, "cap1", 40);
        Rig second = Rig.named(helper, "cap2", 40);
        Rig third = Rig.named(helper, "cap3", 40);
        Rig fourth = Rig.named(helper, "cap4", 40);
        first.alongside(second);
        first.alongside(third);
        first.alongside(fourth);
        helper.runAfterDelay(2, first.guard(() -> {
            Feedback feedback = new Feedback();
            CommandSourceStack asPlayer = source(server, level, 0, player, feedback);
            helper.assertTrue(run(server, asPlayer, "aiplayer spawn " + first.name()) == 1, "the first body was refused: " + feedback.messages);
            helper.assertTrue(run(server, asPlayer, "aiplayer spawn " + second.name()) == 1, "the second body was refused: " + feedback.messages);
            helper.assertTrue(first.body().ownerId().equals(player.getUUID()), "the spawning player is not the owner");

            Feedback refusal = new Feedback();
            int result = run(server, source(server, level, 0, player, refusal), "aiplayer spawn " + third.name());
            helper.assertTrue(result == 0 && !third.isLoggedIn(), "a third body was spawned for a player below permission level 2 (result " + result + ")");
            helper.assertTrue(!refusal.messages.isEmpty(), "the refusal did not say anything");

            // Despawned is still owned: the limit is not a limit on how many are logged in at once.
            first.manager().despawn(first.body());
            helper.assertTrue(run(server, source(server, level, 0, player, new Feedback()), "aiplayer spawn " + third.name()) == 0 && !third.isLoggedIn(),
                "despawning a body freed its slot");

            helper.assertTrue(run(server, source(server, level, 0, other, new Feedback()), "aiplayer spawn " + fourth.name()) == 1,
                "another player was refused because of the first player's bodies");
            helper.assertTrue(run(server, source(server, level, 2, player, new Feedback()), "aiplayer spawn " + third.name()) == 1 && third.isLoggedIn(),
                "a level-2 source was held to the limit");
            helper.assertTrue(third.body().ownerId().equals(player.getUUID()), "the level-2 source's body has another owner");
            first.pass();
        }));
    }

    /**
     * A body must not take the name of a player who is not online but is known to the server (its profile cache).
     * The GameTest server has no profile cache of its own, so the test gives the manager one. Controls: an unknown
     * name, a level-2 source, and the body's own cache entry (spawning puts every body in the profile cache, under
     * its own UUID, which must not count as a real player's).
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 40)
    public static void knownPlayerNameIsRefusedBelowLevelTwo(GameTestHelper helper) {
        floor(helper);
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        ServerPlayer player = playerInArena(helper, "gt_namer");
        Rig real = Rig.named(helper, "real", 40);
        Rig fresh = Rig.named(helper, "fresh", 40);
        real.alongside(fresh);
        helper.runAfterDelay(2, real.guard(() -> {
            Path cacheFile = null;
            try {
                cacheFile = Files.createTempFile("megablockbot-gametest-usercache", ".json");
                GameProfileCache cache = new GameProfileCache((names, callback) -> {}, cacheFile.toFile());
                cache.add(new GameProfile(UUID.randomUUID(), real.name())); // a player who is offline, as the server knows them
                real.manager().useProfileCache(() -> cache);

                helper.assertTrue(run(server, source(server, level, 0, player, new Feedback()), "aiplayer spawn " + fresh.name()) == 1,
                    "a name the profile cache does not know was refused");
                Feedback refusal = new Feedback();
                int result = run(server, source(server, level, 0, player, refusal), "aiplayer spawn " + real.name());
                helper.assertTrue(result == 0 && !real.isLoggedIn(), "a body took the name of a player the profile cache knows (result " + result + ")");
                helper.assertTrue(!refusal.messages.isEmpty(), "the refusal did not say anything");

                helper.assertTrue(run(server, source(server, level, 2, player, new Feedback()), "aiplayer spawn " + real.name()) == 1 && real.isLoggedIn(),
                    "a level-2 source was refused a known player's name");

                // What placeNewPlayer does on a server that has a profile cache (this one has none): the body's own entry
                // replaces the name's. Forgotten, the body is then spawned from scratch and must not be refused for it.
                cache.add(real.body().getGameProfile());
                real.manager().despawn(real.body());
                real.manager().deleteSaved(BodyManager.uuidFor(real.name()));
                helper.assertTrue(run(server, source(server, level, 0, player, new Feedback()), "aiplayer spawn " + real.name()) == 1,
                    "the body's own profile cache entry was taken for a real player's");
                real.pass();
            } catch (IOException | BodyException e) {
                throw new IllegalStateException(e);
            } finally {
                real.manager().useProfileCache(null);
                if (cacheFile != null) {
                    try {
                        Files.deleteIfExists(cacheFile);
                    } catch (IOException ignored) {
                        // A leftover file in the temp directory is harmless.
                    }
                }
            }
        }));
    }

    /**
     * The refusal of a known player's name is not only for new bodies: a body that exists (its record is kept after it is
     * despawned) is refused to whoever is below level 2 once a player of its name is known to the server, which is
     * what keeps the body that gave way to a joining player from coming back beside them. Controls: level 2 is not
     * limited, and with the name unknown the owner may spawn the body again.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 40)
    public static void knownPlayerNameIsRefusedForAnExistingBodyToo(GameTestHelper helper) {
        floor(helper);
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        ServerPlayer owner = playerInArena(helper, "gt_keeper");
        Rig rig = Rig.spawn(helper, "kept", new Vec3(8.5, FLOOR_TOP, 8.5), 0.0F, 40, null, owner.getGameProfile());
        helper.runAfterDelay(2, rig.guard(() -> {
            Path cacheFile = null;
            try {
                cacheFile = Files.createTempFile("megablockbot-gametest-usercache", ".json");
                GameProfileCache cache = new GameProfileCache((names, callback) -> {}, cacheFile.toFile());
                cache.add(new GameProfile(UUID.randomUUID(), rig.name())); // a player of this name, as the server knows them
                rig.manager().useProfileCache(() -> cache);
                rig.manager().despawn(rig.body());
                helper.assertTrue(rig.manager().recordSummary(rig.name()) != null, "setup: the despawned body has no record");

                Feedback refusal = new Feedback();
                int result = run(server, source(server, level, 0, owner, refusal), "aiplayer spawn " + rig.name());
                helper.assertTrue(result == 0 && !rig.isLoggedIn(), "the owner brought back a body under the name of a player the server knows (result " + result + ")");
                helper.assertTrue(!refusal.messages.isEmpty(), "the refusal did not say anything");

                helper.assertTrue(run(server, source(server, level, 2, owner, new Feedback()), "aiplayer spawn " + rig.name()) == 1 && rig.isLoggedIn(),
                    "a level-2 source was refused the body of a known player's name");
                rig.manager().despawn(rig.body());

                rig.manager().useProfileCache(null); // the GameTest server has no profile cache: nobody is known
                helper.assertTrue(run(server, source(server, level, 0, owner, new Feedback()), "aiplayer spawn " + rig.name()) == 1 && rig.isLoggedIn(),
                    "the owner was refused their own body under a name nobody knows");
                rig.pass();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            } finally {
                rig.manager().useProfileCache(null);
                if (cacheFile != null) {
                    try {
                        Files.deleteIfExists(cacheFile);
                    } catch (IOException ignored) {
                        // A leftover file in the temp directory is harmless.
                    }
                }
            }
        }));
    }

    /**
     * A body spawned under a real player's name (an operator may) must not take the name's place in the profile
     * cache: {@code placeNewPlayer} writes the body's own profile under the name, and from then on the name means the
     * body, which defeats the refusal above (the player reads as nobody while they are offline) and every other
     * by-name lookup of that player finds the body instead. The manager puts the player's profile back once the body
     * is placed. The GameTest server has no cache of its own, so the test supplies one and, with it, plays the
     * server's part: what {@code placeNewPlayer} writes into it once the body is placed.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 40)
    public static void aBodyNeverTakesARealPlayersPlaceInTheProfileCache(GameTestHelper helper) {
        floor(helper);
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        ServerPlayer owner = playerInArena(helper, "gt_placer");
        Rig rig = Rig.named(helper, "placed", 40);
        helper.runAfterDelay(2, rig.guard(() -> {
            Path cacheFile = null;
            try {
                cacheFile = Files.createTempFile("megablockbot-gametest-usercache", ".json");
                GameProfileCache cache = new GameProfileCache((names, callback) -> {}, cacheFile.toFile());
                UUID playerId = UUID.randomUUID();
                cache.add(new GameProfile(playerId, rig.name())); // the player, as the server knows them
                // The server's part, played whenever the cache is asked for: once the body stands under the name,
                // the body's own profile is what the cache holds under it, as placeNewPlayer's write leaves a cache
                // the server has. Written once; the restore under test, if it runs, has already put the player's
                // back by the next asking.
                boolean[] bodyPlaced = {false};
                boolean[] written = {false};
                Supplier<GameProfileCache> supplied = () -> {
                    if (rig.isLoggedIn()) bodyPlaced[0] = true;
                    if (bodyPlaced[0] && !written[0]) {
                        written[0] = true;
                        cache.add(new GameProfile(BodyManager.uuidFor(rig.name()), rig.name()));
                    }
                    return cache;
                };
                rig.manager().useProfileCache(supplied);
                try {
                    helper.assertTrue(run(server, source(server, level, 2, owner, new Feedback()), "aiplayer spawn " + rig.name()) == 1 && rig.isLoggedIn(),
                        "an operator-level source was refused the name of a player the server knows");
                    supplied.get(); // what the cache holds now that the body is placed
                    GameProfileCache.GameProfileInfo underName = cache.profilesByName.get(rig.name().toLowerCase(Locale.ROOT));
                    helper.assertTrue(underName != null && playerId.equals(underName.getProfile().getId()),
                        "with the body placed, the profile cache maps the name to "
                            + (underName == null ? "nobody" : underName.getProfile().getId()) + ", not the player's " + playerId);
                    rig.manager().despawn(rig.body());
                    underName = cache.profilesByName.get(rig.name().toLowerCase(Locale.ROOT));
                    helper.assertTrue(underName != null && playerId.equals(underName.getProfile().getId()),
                        "after the despawn, the profile cache maps the name to "
                            + (underName == null ? "nobody" : underName.getProfile().getId()) + ", not the player's " + playerId);
                    boolean refused = false;
                    try {
                        rig.manager().checkMaySpawn(source(server, level, 0, owner), rig.name());
                    } catch (BodyException e) {
                        refused = true;
                    }
                    helper.assertTrue(refused, "an owner without operator permission was allowed the name of a player the server knows");
                    rig.pass();
                } finally {
                    rig.manager().useProfileCache(null);
                }
            } catch (IOException e) {
                throw new IllegalStateException(e);
            } finally {
                rig.manager().useProfileCache(null);
                if (cacheFile != null) {
                    try {
                        Files.deleteIfExists(cacheFile);
                    } catch (IOException ignored) {
                        // A leftover file in the temp directory is harmless.
                    }
                }
            }
        }));
    }

    /**
     * Vanilla lets the newest login win a duplicate (it disconnects the older session of the same profile), and a real
     * player must never be shut out of their own name; a body has its own UUID, so vanilla's rule never meets it. The body
     * gives way instead: it is logged out with its record kept, its owner is told, and nothing but the joining player
     * can be under that name until they leave. The fake player joins through {@code placeNewPlayer}, as a client does;
     * the owner is another one whose connection keeps what it is sent. The owner's notice is told from the join and leave
     * lines vanilla broadcasts by being a literal text; the control is that those lines did arrive.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 40)
    public static void realPlayerJoiningTakesTheNameOfABody(GameTestHelper helper) {
        floor(helper);
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        PlayerList players = server.getPlayerList();
        UUID ownerId = UUID.randomUUID();
        Rig rig = Rig.spawn(helper, "yield", new Vec3(8.5, FLOOR_TOP, 8.5), 0.0F, 40, null, new GameProfile(ownerId, "gt_yieldowner"));
        helper.runAfterDelay(2, rig.guard(() -> {
            ServerPlayer owner = standalonePlayer(server, level, ownerId, "gt_yieldowner");
            ServerPlayer joiner = standalonePlayer(server, level, UUID.randomUUID(), rig.name());
            EmbeddedChannel ownerChannel = join(server, owner);
            try {
                helper.assertTrue(players.getPlayerByName(rig.name()) == rig.body(), "setup: the body is not the player of its name");
                ownerChannel.outboundMessages().clear();

                join(server, joiner);

                helper.assertTrue(!rig.isLoggedIn(), "the body kept its name after a player of that name joined");
                helper.assertTrue(players.getPlayerByName(rig.name()) == joiner && !joiner.hasDisconnected(), "the joining player was not left with their name");
                helper.assertTrue(rig.manager().recordSummary(rig.name()) != null, "the record of the body that gave way was not kept");
                helper.assertTrue(systemMessages(ownerChannel, true) >= 1, "control failed: the owner was not sent the join line of the player");
                helper.assertTrue(systemMessages(ownerChannel, false) >= 1, "the owner was not told why the body went");

                // Never alongside them: even a source that no cache check limits cannot put the body back while they are on.
                helper.assertTrue(run(server, source(server, level, 2, owner, new Feedback()), "aiplayer spawn " + rig.name()) == 0 && !rig.isLoggedIn(),
                    "the body was spawned beside the player who has its name");
                rig.pass();
            } finally {
                leave(server, joiner);
                leave(server, owner);
            }
        }));
    }

    /**
     * No owner means nobody to be near it, so a body spawned from the console is given a longer grace than an owned
     * one before the idle policy despawns it. The grace is crossed with the manager's per-body entry point instead of
     * by waiting for it (the periodic check may add a few ticks on top, which the margins allow for).
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 40)
    public static void ownerlessBodyIsIdleLongerThanOwnedOne(GameTestHelper helper) {
        floor(helper);
        // Neither has a goal (the default brain wants nothing), and the owner of the first is not online.
        Rig owned = Rig.spawn(helper, "idle_o", new Vec3(5.5, FLOOR_TOP, 8.5), 0.0F, 40, null, new GameProfile(UUID.randomUUID(), "gt_away"));
        Rig ownerless = Rig.spawn(helper, "idle_n", new Vec3(10.5, FLOOR_TOP, 8.5), 0.0F, 40);
        owned.alongside(ownerless);
        helper.runAfterDelay(2, owned.guard(() -> {
            BodyManager manager = owned.manager();
            int margin = 5 * BodyConstants.IDLE_CHECK_INTERVAL_TICKS;
            manager.applyIdlePolicy(owned.body(), BodyConstants.IDLE_GRACE_TICKS - margin);
            manager.applyIdlePolicy(ownerless.body(), BodyConstants.IDLE_GRACE_TICKS - margin);
            helper.assertTrue(owned.isLoggedIn() && ownerless.isLoggedIn(), "a body was despawned before the ordinary grace was over");

            manager.applyIdlePolicy(owned.body(), 2 * margin);
            manager.applyIdlePolicy(ownerless.body(), 2 * margin);
            helper.assertTrue(!owned.isLoggedIn(), "the owned body was still there after the ordinary grace");
            helper.assertTrue(ownerless.isLoggedIn(), "the ownerless body was despawned after the ordinary grace, not the longer one");

            manager.applyIdlePolicy(ownerless.body(), BodyConstants.OWNERLESS_IDLE_GRACE_TICKS - 10 * margin);
            helper.assertTrue(ownerless.isLoggedIn(), "the ownerless body was despawned before its own grace was over");
            manager.applyIdlePolicy(ownerless.body(), 20 * margin);
            helper.assertTrue(!ownerless.isLoggedIn(), "the ownerless body was still there after its own grace");
            owned.pass();
        }));
    }

    /**
     * Advancements are kept but not announced. The control is a real player on a real connection (its outbound
     * packets are kept): it hears its own advancement announced, and does not hear the body's.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 40)
    public static void advancementsAreNotAnnounced(GameTestHelper helper) {
        floor(helper);
        Rig rig = Rig.spawn(helper, "adv", new Vec3(8.5, FLOOR_TOP, 8.5), 0.0F, 40);
        helper.runAfterDelay(2, rig.guard(() -> {
            ServerLevel level = helper.getLevel();
            MinecraftServer server = level.getServer();
            AdvancementHolder stone = server.getAdvancements().get(ResourceLocation.withDefaultNamespace("story/mine_stone"));
            helper.assertTrue(stone != null, "setup: no advancement story/mine_stone");

            Connection connection = new Connection(PacketFlow.SERVERBOUND);
            EmbeddedChannel channel = new EmbeddedChannel(connection);
            ServerPlayer listener = standalonePlayer(server, level, UUID.randomUUID(), "gt_listener");
            server.getPlayerList().placeNewPlayer(connection, listener, new CommonListenerCookie(listener.getGameProfile(), 0, ClientInformation.createDefault(), false));
            try {
                channel.outboundMessages().clear();
                award(listener, stone);
                helper.assertTrue(announcements(channel) >= 1, "control failed: a player earning an advancement was not announced to another player");

                channel.outboundMessages().clear();
                award(rig.body(), stone);
                helper.assertTrue(rig.body().getAdvancements().getOrStartProgress(stone).isDone(), "the body did not earn the advancement");
                helper.assertTrue(announcements(channel) == 0, "a body's advancement was announced in chat " + announcements(channel) + " time(s)");
            } finally {
                server.getPlayerList().remove(listener);
                forgetPlayerFiles(server, listener.getUUID());
            }
            rig.pass();
        }));
    }

    /** A body is a player to mobs: an ordinary zombie picks it as a target. (It must be attackable.) */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 120)
    public static void mobsTargetBody(GameTestHelper helper) {
        floor(helper);
        Rig rig = Rig.spawn(helper, "mob", new Vec3(8.5, FLOOR_TOP, 8.5), 0.0F, 120);
        Zombie zombie = helper.spawn(EntityType.ZOMBIE, new BlockPos(12, FLOOR_Y + 1, 8));
        rig.onEachTick(() -> {
            if (zombie.getTarget() != rig.body()) return;
            zombie.discard();
            rig.pass();
        });
    }

    /** Decision: listed in the tab list, like a player. */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 20)
    public static void listedInTabList(GameTestHelper helper) {
        floor(helper);
        Rig rig = Rig.spawn(helper, "tab", new Vec3(8.5, FLOOR_TOP, 8.5), 0.0F, 20);
        helper.runAfterDelay(1, rig.guard(() -> {
            BotPlayer body = rig.body();
            ClientboundPlayerInfoUpdatePacket join = ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(body));
            helper.assertTrue(join.actions().contains(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED), "the join packet does not carry the listed flag");
            helper.assertTrue(join.entries().size() == 1 && join.entries().get(0).listed(), "the body's tab list entry is not listed");
            helper.assertTrue(body.allowsListing(), "the body does not allow listing (server status player sample)");
            rig.pass();
        }));
    }

    // ---------------------------------------------------------------------------------------------------------
    // Support
    // ---------------------------------------------------------------------------------------------------------

    private static void floor(GameTestHelper helper) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) helper.setBlock(x, FLOOR_Y, z, Blocks.STONE);
        }
    }

    private static int count(BotPlayer body, net.minecraft.world.item.Item item) {
        return body.getInventory().countItem(item);
    }

    /** A ServerPlayer object that is not placed in any level: enough to ask it things like isSleeping. */
    private static ServerPlayer standalonePlayer(MinecraftServer server, ServerLevel level, UUID id, String name) {
        return new ServerPlayer(server, level, new GameProfile(id, name), ClientInformation.createDefault());
    }

    private static CommandSourceStack source(MinecraftServer server, ServerLevel level, int permission, net.minecraft.world.entity.Entity entity) {
        return source(server, level, permission, entity, CommandSource.NULL);
    }

    private static CommandSourceStack source(MinecraftServer server, ServerLevel level, int permission, net.minecraft.world.entity.Entity entity,
                                             CommandSource sink) {
        return new CommandSourceStack(sink, Vec3.ZERO, Vec2.ZERO, level, permission, "gt", Component.literal("gt"), server, entity);
    }

    /** A player who is not in any level, standing (as far as a command that reads its position can tell) in the arena. */
    private static ServerPlayer playerInArena(GameTestHelper helper, String name) {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = standalonePlayer(level.getServer(), level, UUID.randomUUID(), name);
        Vec3 at = helper.absoluteVec(new Vec3(8.5, FLOOR_TOP, 8.5));
        // Not moveTo(x, y, z): ServerPlayer's version tells its connection, which this player does not have.
        player.setPos(at.x, at.y, at.z);
        return player;
    }

    /** Keeps what a command source is told, so a test can see that a refusal said something. */
    private static final class Feedback implements CommandSource {
        final List<String> messages = new ArrayList<>();

        @Override public void sendSystemMessage(Component message) { messages.add(message.getString()); }
        @Override public boolean acceptsSuccess() { return true; }
        @Override public boolean acceptsFailure() { return true; }
        @Override public boolean shouldInformAdmins() { return false; }
    }

    /** Runs a command line as {@code source} and returns what the command returned: 0 when it failed. */
    private static int run(MinecraftServer server, CommandSourceStack source, String line) {
        try {
            return server.getCommands().getDispatcher().execute(line, source);
        } catch (CommandSyntaxException e) {
            throw new IllegalStateException("'" + line + "' did not parse: " + e.getMessage(), e);
        }
    }

    private static void award(ServerPlayer player, AdvancementHolder advancement) {
        for (String criterion : advancement.value().criteria().keySet()) {
            player.getAdvancements().award(advancement, criterion);
        }
    }

    private static long announcements(EmbeddedChannel channel) {
        return channel.outboundMessages().stream()
            .filter(message -> message instanceof ClientboundSystemChatPacket chat
                && chat.content().getContents() instanceof TranslatableContents text && text.getKey().startsWith("chat.type.advancement."))
            .count();
    }

    /** Places a player the way a client's login does, on a connection whose channel keeps what is sent to it. */
    private static EmbeddedChannel join(MinecraftServer server, ServerPlayer player) {
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        EmbeddedChannel channel = new EmbeddedChannel(connection);
        join(server, player, connection);
        return channel;
    }

    private static void join(MinecraftServer server, ServerPlayer player, Connection connection) {
        server.getPlayerList().placeNewPlayer(connection, player, new CommonListenerCookie(player.getGameProfile(), 0, ClientInformation.createDefault(), false));
    }

    /** Takes a player placed by {@link #join} out again, and forgets what was saved for them. */
    private static void leave(MinecraftServer server, ServerPlayer player) {
        if (server.getPlayerList().getPlayer(player.getUUID()) == player) server.getPlayerList().remove(player);
        forgetPlayerFiles(server, player.getUUID());
    }

    /** The chat lines a channel was sent: those vanilla makes (translatable, such as the join and leave lines) or ours (literal). */
    private static long systemMessages(EmbeddedChannel channel, boolean vanilla) {
        return channel.outboundMessages().stream()
            .filter(message -> message instanceof ClientboundSystemChatPacket chat
                && (chat.content().getContents() instanceof TranslatableContents) == vanilla)
            .count();
    }

    /**
     * A real player's connection as far as a shutdown can tell: it keeps the disconnect it is sent and stays open, so
     * the player stays in the list (a real channel closes on its own thread, later than the call that asks it to).
     */
    private static final class ProbeConnection extends Connection {
        boolean disconnectSent;

        private ProbeConnection() {
            super(PacketFlow.SERVERBOUND);
        }

        static ProbeConnection create() {
            ProbeConnection connection = new ProbeConnection();
            new EmbeddedChannel(connection);
            return connection;
        }

        @Override public void send(Packet<?> packet) { note(packet); }
        @Override public void send(Packet<?> packet, @Nullable PacketSendListener listener) { note(packet); }
        @Override public void send(Packet<?> packet, @Nullable PacketSendListener listener, boolean flush) { note(packet); }

        private void note(Packet<?> packet) {
            if (packet instanceof ClientboundDisconnectPacket) disconnectSent = true;
        }
    }

    private static void forgetPlayerFiles(MinecraftServer server, UUID id) {
        try {
            Files.deleteIfExists(server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(id + ".dat"));
            Files.deleteIfExists(server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(id + ".dat_old"));
            Files.deleteIfExists(server.getWorldPath(LevelResource.PLAYER_STATS_DIR).resolve(id + ".json"));
            Files.deleteIfExists(server.getWorldPath(LevelResource.PLAYER_ADVANCEMENTS_DIR).resolve(id + ".json"));
        } catch (IOException ignored) {
            // Leftover files of a test player are harmless; the name is unique per run.
        }
    }

    /**
     * One test body: spawned in the arena under a name unique to this run, found again by that name (a respawn
     * replaces the instance), and despawned and forgotten when the test ends, passed or not.
     */
    private static final class Rig {
        private final GameTestHelper helper;
        private final BodyManager manager;
        private final String name;
        private final UUID id;
        private final Supplier<BotBrain> brainFactory;
        private final GameProfile owner;
        private final List<Rig> companions = new ArrayList<>();

        private Rig(GameTestHelper helper, String name, Supplier<BotBrain> brainFactory, GameProfile owner) {
            this.helper = helper;
            this.manager = BodyManager.of(helper.getLevel().getServer());
            this.name = name;
            this.id = BodyManager.uuidFor(name);
            this.brainFactory = brainFactory;
            this.owner = owner;
        }

        static Rig spawn(GameTestHelper helper, String tag, Vec3 relative, float yaw, int deadlineTicks) {
            return spawn(helper, tag, relative, yaw, deadlineTicks, null, null);
        }

        static Rig spawn(GameTestHelper helper, String tag, Vec3 relative, float yaw, int deadlineTicks, Supplier<BotBrain> brainFactory) {
            return spawn(helper, tag, relative, yaw, deadlineTicks, brainFactory, null);
        }

        static Rig spawn(GameTestHelper helper, String tag, Vec3 relative, float yaw, int deadlineTicks,
                         Supplier<BotBrain> brainFactory, GameProfile owner) {
            Rig rig = new Rig(helper, "gt_" + tag + "_" + RUN_ID, brainFactory, owner);
            rig.respawn(helper.absoluteVec(relative), yaw);
            // A test that never ends is failed by the framework; this makes sure it does not leave its body behind.
            helper.runAtTickTime(deadlineTicks, rig::cleanup);
            return rig;
        }

        /** A rig for a body that something else (a command) spawns: it only cleans up after it. */
        static Rig named(GameTestHelper helper, String tag, int deadlineTicks) {
            Rig rig = new Rig(helper, "gt_" + tag + "_" + RUN_ID, null, null);
            helper.runAtTickTime(deadlineTicks, rig::cleanup);
            return rig;
        }

        String name() {
            return name;
        }

        UUID id() {
            return id;
        }

        boolean isLoggedIn() {
            return manager.get(id) != null;
        }

        BodyManager manager() {
            return manager;
        }

        /** The body now logged in under this name; after a death that is the replacement, not the first instance. */
        BotPlayer body() {
            BotPlayer current = manager.get(id);
            if (current == null) throw new IllegalStateException("no body " + name + " is logged in");
            return current;
        }

        /** Spawns (again) at an absolute position; a body with saved data ignores the position and returns where it was. */
        BotPlayer respawn(Vec3 absolute) {
            return respawn(absolute, 0.0F);
        }

        private BotPlayer respawn(Vec3 absolute, float yaw) {
            try {
                return manager.spawn(name, owner, helper.getLevel(), absolute, yaw, brainFactory);
            } catch (BodyException e) {
                throw new IllegalStateException("could not spawn " + name + ": " + e.getMessage(), e);
            }
        }

        /** Runs every tick; whatever it throws, the body is cleaned up first. */
        void onEachTick(Runnable action) {
            helper.onEachTick(guard(action));
        }

        Runnable guard(Runnable action) {
            return () -> {
                try {
                    action.run();
                } catch (RuntimeException | Error e) {
                    cleanup();
                    throw e;
                }
            };
        }

        void pass() {
            cleanup();
            helper.succeed();
        }

        /** Bodies that live and die with this one: cleaned up whenever this rig is. */
        void alongside(Rig other) {
            companions.add(other);
        }

        void cleanup() {
            for (Rig companion : companions) companion.cleanup();
            manager.failRespawnsOf(id, false);
            manager.failRestoresOf(id, 0);
            BotPlayer current = manager.get(id);
            if (current != null) manager.despawn(current);
            try {
                manager.deleteSaved(id);
            } catch (BodyException ignored) {
                // Still logged in only if despawn did not take; the unique name keeps it from mattering.
            }
        }
    }
}
