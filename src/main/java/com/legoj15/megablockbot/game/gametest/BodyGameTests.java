package com.legoj15.megablockbot.game.gametest;

import com.legoj15.megablockbot.MegaBlockBot;
import com.legoj15.megablockbot.game.body.BodyException;
import com.legoj15.megablockbot.game.body.BodyManager;
import com.legoj15.megablockbot.game.body.BotBrain;
import com.legoj15.megablockbot.game.body.BotPlayer;
import com.legoj15.megablockbot.game.body.DeathRecord;
import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.PacketFlow;
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
import net.minecraft.server.players.SleepStatus;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
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
        List<ExplodingBrain> made = new ArrayList<>();
        Supplier<BotBrain> factory = () -> {
            ExplodingBrain brain = new ExplodingBrain(failSave[0], true);
            made.add(brain);
            return brain;
        };
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
            int before = made.size();
            BotPlayer fourth = rig.respawn(where);          // loads {marker} into a brain that throws
            helper.assertTrue(rig.manager().get(rig.name()) == fourth, "the body did not come back after a brain failed to load");
            helper.assertTrue(count(fourth, Items.DIAMOND) == 2, "a brain that failed to load cost the body its inventory");
            ExplodingBrain current = (ExplodingBrain) fourth.brain();
            helper.assertTrue(made.size() == before + 2, "expected the failed brain to be replaced by one more from the factory, made " + (made.size() - before) + " new");
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

            Pig owner = EntityType.PIG.create(level);
            owner.setUUID(ownerId);
            Pig stranger = EntityType.PIG.create(level);
            helper.assertTrue(body.mayCommand(source(server, level, 0, owner)), "the owner may not command its body");
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
        return new CommandSourceStack(CommandSource.NULL, Vec3.ZERO, Vec2.ZERO, level, permission, "gt", Component.literal("gt"), server, entity);
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

        String name() {
            return name;
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
