package com.legoj15.megablockbot.game.gametest;

import com.legoj15.megablockbot.MegaBlockBot;
import com.legoj15.megablockbot.game.body.BodyException;
import com.legoj15.megablockbot.game.body.BodyManager;
import com.legoj15.megablockbot.game.body.BotPlayer;
import com.legoj15.megablockbot.game.safety.Allowance;
import com.legoj15.megablockbot.game.safety.BlockSafety;
import com.legoj15.megablockbot.game.safety.ClaimProvider;
import com.legoj15.megablockbot.game.safety.PlacedBlocks;
import com.legoj15.megablockbot.game.safety.Verdict;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

/**
 * Block safety (design 3.5, issue 9) against a running server. What these protect: that a real player's placement is
 * seen by the loader hook and protected, that a bot's own placements and natural blocks are not, that the protected
 * radius follows the owner's respawn point and nothing overrides it, and that the saved record survives a restart and
 * a damaged file. A rule that stops recording, a verdict that flips, or a position that decodes to the wrong block fails
 * one of them.
 *
 * <p>The arena of a GameTest sits at a random place in the world, millions of blocks from the world spawn, so the spawn
 * zone never covers it; the tests that need a zone make one with an owner's respawn point. {@code mayBreak} takes the
 * block state as an argument, so a test may ask about a position it has not built.
 */
public final class BlockSafetyGameTests {
    private static final int FLOOR_Y = 1;
    private static final String RUN_ID = Integer.toString((int) (System.nanoTime() & 0xFFFFF), 36);

    private BlockSafetyGameTests() {}

    // ---------------------------------------------------------------------------------------------------------
    // Placed blocks: the loader hook and the record
    // ---------------------------------------------------------------------------------------------------------

    /**
     * The whole path of design 3.5's second rule: a player places a block through the normal item-use path, the hook
     * records it at the end of the tick, the bot may not break it; the player breaks it, the record goes, and a block of
     * the same type set there afterwards (terrain) may be broken. Setting the same type again is what proves the record
     * is gone: a stale record would be pruned by a different type.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 100)
    public static void playerPlacementIsRecordedThenForgottenWhenBroken(GameTestHelper helper) {
        Fixture f = new Fixture(helper, 100);
        f.guard(() -> {
            BotPlayer body = f.body("place", null, 2, 2);
            ServerPlayer player = f.mockPlayer();
            BlockPos against = f.at(8, FLOOR_Y, 8);
            BlockPos target = against.above();
            f.forgetAtEnd(target);

            InteractionResult result = f.place(player, Items.COBBLESTONE, against);
            helper.assertTrue(result.consumesAction() && f.level.getBlockState(target).is(Blocks.COBBLESTONE),
                "the mock player's item use placed nothing: " + result + ", block is " + f.level.getBlockState(target));

            // The loader hook delivers at the end of the tick, so the record is there a tick or two later.
            f.then(2, () -> {
                helper.assertTrue(f.placed.lookup(target.asLong(), Blocks.COBBLESTONE, body.getUUID()) == PlacedBlocks.PLAYER,
                    "the placement was not recorded as player-placed: " + f.placed.describe(target.asLong()));
                Verdict refused = f.safety.mayBreak(body, f.level, target, f.level.getBlockState(target));
                helper.assertTrue(!refused.allowed() && refused.rule().equals("player_placed"),
                    "a player's block should be refused as player_placed, got " + refused);

                helper.assertTrue(player.gameMode.destroyBlock(target) && f.level.getBlockState(target).isAir(),
                    "the mock player could not break its own block");
                f.then(2, () -> {
                    helper.assertTrue(f.placed.lookup(target.asLong(), Blocks.COBBLESTONE, body.getUUID()) == PlacedBlocks.NONE,
                        "the break did not drop the record: " + f.placed.describe(target.asLong()));
                    f.set(target, Blocks.COBBLESTONE);
                    Verdict allowed = f.safety.mayBreak(body, f.level, target, f.level.getBlockState(target));
                    helper.assertTrue(allowed.allowed() && allowed.rule().isEmpty(),
                        "terrain of the same type at a broken block's position is still refused: " + allowed);
                    f.pass();
                });
            });
        }).run();
    }

    /** What the bot's own placement is: its own to break, nobody else's. Noted by hand, as a task would. */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 40)
    public static void botMayBreakWhatItNotedAsItsOwnAndAnotherBotMayNot(GameTestHelper helper) {
        Fixture f = new Fixture(helper, 40);
        f.guard(() -> {
            BotPlayer a = f.body("own_a", null, 2, 2);
            BotPlayer b = f.body("own_b", null, 12, 12);
            BlockPos pos = f.at(6, FLOOR_Y + 1, 6);
            f.forgetAtEnd(pos);
            f.set(pos, Blocks.COBBLESTONE);

            helper.assertTrue(allowed(f, b, pos), "control: an unrecorded block is terrain for any body");
            f.safety.noteBotPlaced(a, f.level, pos);
            helper.assertTrue(allowed(f, a, pos), "the body that placed it may not break it: " + verdict(f, a, pos));
            Verdict other = verdict(f, b, pos);
            helper.assertTrue(!other.allowed() && other.rule().equals("player_placed"),
                "another body should be refused as player_placed, got " + other);
        }).run();
        f.pass();
    }

    /** The bot's own placement through the same hook a player's takes: recorded as that bot's, not as a player's. */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 60)
    public static void botPlacementThroughItemUseIsItsOwn(GameTestHelper helper) {
        Fixture f = new Fixture(helper, 60);
        f.guard(() -> {
            BotPlayer a = f.body("hook_a", null, 2, 2);
            BotPlayer b = f.body("hook_b", null, 12, 12);
            BlockPos against = f.at(8, FLOOR_Y, 8);
            BlockPos target = against.above();
            f.forgetAtEnd(target);
            InteractionResult result = f.place(a, Items.COBBLESTONE, against);
            helper.assertTrue(result.consumesAction() && f.level.getBlockState(target).is(Blocks.COBBLESTONE),
                "the body's item use placed nothing: " + result);
            f.then(2, () -> {
                helper.assertTrue(f.placed.lookup(target.asLong(), Blocks.COBBLESTONE, a.getUUID()) == PlacedBlocks.BOT_SELF,
                    "the body's placement was not recorded as its own: " + f.placed.describe(target.asLong()));
                helper.assertTrue(allowed(f, a, target), "the body may not break what it placed: " + verdict(f, a, target));
                helper.assertTrue(!allowed(f, b, target), "another body may break it: " + verdict(f, b, target));
                f.pass();
            });
        }).run();
    }

    /**
     * The hooks' own decisions, driven directly with causes the item-use path cannot produce. An enderman setting a
     * block down (or a cause the loader does not name) built nothing, and a bot digging must still be able to take that
     * block; a break the loader reported but that left the block standing did not happen, and must not cost the record.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 20)
    public static void onlyPlayersPlacementsAreRecordedAndOnlyRealBreaksForgotten(GameTestHelper helper) {
        Fixture f = new Fixture(helper, 20);
        f.guard(() -> {
            BlockPos pos = f.at(6, FLOOR_Y + 1, 6);
            f.forgetAtEnd(pos);
            f.set(pos, Blocks.COBBLESTONE);
            UUID anyBody = UUID.randomUUID();

            Entity pig = EntityType.PIG.create(f.level);
            f.safety.blockPlaced(f.level, pos, pig);
            f.safety.blockPlaced(f.level, pos, null);
            helper.assertTrue(f.placed.lookup(pos.asLong(), Blocks.COBBLESTONE, anyBody) == PlacedBlocks.NONE,
                "a placement by something that is not a player was recorded: " + f.placed.describe(pos.asLong()));
            // Control: the very same call with a player as the cause records it.
            f.safety.blockPlaced(f.level, pos, f.mockPlayer());
            helper.assertTrue(f.placed.lookup(pos.asLong(), Blocks.COBBLESTONE, anyBody) == PlacedBlocks.PLAYER,
                "control: a player's placement was not recorded: " + f.placed.describe(pos.asLong()));

            f.safety.blockBroken(f.level, pos, Blocks.COBBLESTONE.defaultBlockState());
            helper.assertTrue(f.placed.lookup(pos.asLong(), Blocks.COBBLESTONE, anyBody) == PlacedBlocks.PLAYER,
                "a break that left the block standing dropped its record");
            f.set(pos, Blocks.AIR);
            f.safety.blockBroken(f.level, pos, Blocks.COBBLESTONE.defaultBlockState());
            helper.assertTrue(f.placed.lookup(pos.asLong(), Blocks.COBBLESTONE, anyBody) == PlacedBlocks.NONE,
                "a break that removed the block kept its record: " + f.placed.describe(pos.asLong()));
        }).run();
        f.pass();
    }

    /**
     * The loader reports a placement at one position, the one the player aimed at; a door is two blocks, so the half it
     * did not report is protected through its partner.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 60)
    public static void aPlayersDoorIsProtectedAtBothHalves(GameTestHelper helper) {
        Fixture f = new Fixture(helper, 60);
        f.guard(() -> {
            BotPlayer body = f.body("door", null, 2, 2);
            ServerPlayer player = f.mockPlayer();
            BlockPos against = f.at(8, FLOOR_Y, 8);
            BlockPos lower = against.above();
            BlockPos upper = lower.above();
            f.forgetAtEnd(lower, upper);
            InteractionResult result = f.place(player, Items.OAK_DOOR, against);
            helper.assertTrue(result.consumesAction() && f.level.getBlockState(upper).is(Blocks.OAK_DOOR),
                "the mock player's door was not placed: " + result + ", upper half is " + f.level.getBlockState(upper));
            f.then(2, () -> {
                for (BlockPos half : List.of(lower, upper)) {
                    Verdict v = verdict(f, body, half);
                    helper.assertTrue(!v.allowed() && v.rule().equals("player_placed"), "a player's door at " + half + ": " + v);
                }
                f.pass();
            });
        }).run();
    }

    /**
     * A record is a claim about a block, and the block can be gone (an explosion, a piston, a fluid): when the level
     * holds another block or air there, the record is terrain, and asking drops it so the record does not grow with
     * every explosion. What the level holds decides, never what the caller says: a path search's stale snapshot or a
     * caller's mistake must not erase a player's record for good.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 20)
    public static void aRecordIsPrunedOnlyOnWhatTheLevelHolds(GameTestHelper helper) {
        Fixture f = new Fixture(helper, 20);
        f.guard(() -> {
            BotPlayer body = f.body("stale", null, 2, 2);
            BlockPos pos = f.at(6, FLOOR_Y + 1, 6);
            f.forgetAtEnd(pos);
            f.set(pos, Blocks.COBBLESTONE);
            f.placed.recordPlayer(pos.asLong(), Blocks.COBBLESTONE);
            Verdict held = f.safety.mayBreak(body, f.level, pos, f.level.getBlockState(pos));
            helper.assertTrue(!held.allowed() && held.rule().equals("player_placed"), "control: the recorded block itself: " + held);

            // A caller that is wrong about what is there: the record is the player's and stays, and so does the answer.
            for (BlockState wrong : List.of(Blocks.DIRT.defaultBlockState(), Blocks.AIR.defaultBlockState())) {
                Verdict v = f.safety.mayBreak(body, f.level, pos, wrong);
                helper.assertTrue(f.placed.lookup(pos.asLong(), Blocks.COBBLESTONE, body.getUUID()) == PlacedBlocks.PLAYER,
                    "a wrong state " + wrong + " from the caller pruned a record the level still backs: " + f.placed.describe(pos.asLong()));
                helper.assertTrue(!v.allowed() && v.rule().equals("player_placed"),
                    "a wrong state " + wrong + " from the caller let a player's block through: " + v);
            }

            // The level really holds another block (what an explosion leaves): terrain now, and the record goes.
            f.set(pos, Blocks.DIRT);
            Verdict other = f.safety.mayBreak(body, f.level, pos, f.level.getBlockState(pos));
            helper.assertTrue(other.allowed(), "a position that holds another block than recorded is still protected: " + other);
            helper.assertTrue(f.placed.lookup(pos.asLong(), Blocks.COBBLESTONE, body.getUUID()) == PlacedBlocks.NONE,
                "asking did not prune the stale record: " + f.placed.describe(pos.asLong()));

            f.set(pos, Blocks.COBBLESTONE);
            f.placed.recordPlayer(pos.asLong(), Blocks.COBBLESTONE);
            f.set(pos, Blocks.AIR);
            Verdict air = f.safety.mayBreak(body, f.level, pos, f.level.getBlockState(pos));
            helper.assertTrue(air.allowed(), "a position that holds air is still protected: " + air);
            helper.assertTrue(f.placed.lookup(pos.asLong(), Blocks.COBBLESTONE, body.getUUID()) == PlacedBlocks.NONE,
                "asking about air did not prune the record: " + f.placed.describe(pos.asLong()));
        }).run();
        f.pass();
    }

    /**
     * A bed is two blocks and the loader reports the placement at one of them: a bed the bot placed must be breakable
     * from either half (it was refused from the half nobody recorded), a bed it did not place from neither, and a
     * double chest is two block entities that are recorded one by one (see the chest test).
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 60)
    public static void theBotsOwnBedMayBeBrokenFromEitherHalfAndNobodyElses(GameTestHelper helper) {
        Fixture f = new Fixture(helper, 60);
        f.guard(() -> {
            BotPlayer a = f.body("bed_a", null, 2, 2);
            BotPlayer b = f.body("bed_b", null, 12, 12);

            // Placed through the item-use path, as the bot's tasks will: the hook records one of the halves.
            BlockPos against = f.at(8, FLOOR_Y, 4);
            BlockPos clicked = against.above();
            f.forgetAtEnd(clicked, clicked.south(), clicked.north(), clicked.east(), clicked.west());
            InteractionResult result = f.place(a, Items.RED_BED, against);
            helper.assertTrue(result.consumesAction() && f.level.getBlockState(clicked).getBlock() instanceof BedBlock,
                "the body's item use placed no bed: " + result + ", block is " + f.level.getBlockState(clicked));

            // Noted by hand, one half each, the other half never mentioned.
            BlockPos footOnly = f.at(3, FLOOR_Y + 1, 8);
            BlockPos headOnly = f.at(3, FLOOR_Y + 1, 12);
            f.forgetAtEnd(footOnly, footOnly.north(), headOnly, headOnly.north());
            for (BlockPos foot : List.of(footOnly, headOnly)) {
                f.level.setBlock(foot, Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.NORTH).setValue(BedBlock.PART, BedPart.FOOT), 3);
                f.level.setBlock(foot.north(), Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.NORTH).setValue(BedBlock.PART, BedPart.HEAD), 3);
            }
            f.safety.noteBotPlaced(a, f.level, footOnly);
            f.safety.noteBotPlaced(a, f.level, headOnly.north());

            f.then(2, () -> {
                // The loader may report one half or both; whichever it did, both must answer for the bot.
                List<BlockPos> bothHalves = new ArrayList<>(List.of(clicked));
                for (Direction d : Direction.Plane.HORIZONTAL) {
                    BlockPos n = clicked.relative(d);
                    if (f.level.getBlockState(n).getBlock() instanceof BedBlock) bothHalves.add(n);
                }
                helper.assertTrue(bothHalves.size() == 2, "the bed placed through item use has " + bothHalves.size() + " blocks: " + bothHalves);
                for (BlockPos half : bothHalves) {
                    helper.assertTrue(allowed(f, a, half), "the body may not break the " + f.level.getBlockState(half).getValue(BedBlock.PART) + " of the bed it placed: " + verdict(f, a, half));
                    Verdict stranger = verdict(f, b, half);
                    helper.assertTrue(!stranger.allowed() && stranger.rule().equals("bed"), "another body may break the bed: " + stranger);
                }
                for (BlockPos half : List.of(footOnly, footOnly.north(), headOnly, headOnly.north())) {
                    helper.assertTrue(allowed(f, a, half), "the body may not break the " + f.level.getBlockState(half).getValue(BedBlock.PART) + " of a bed it noted one half of: " + verdict(f, a, half));
                    helper.assertTrue(!allowed(f, b, half), "another body may break a bed the first one noted");
                }
                f.pass();
            });
        }).run();
    }

    // ---------------------------------------------------------------------------------------------------------
    // Rules
    // ---------------------------------------------------------------------------------------------------------

    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 20)
    public static void terrainOutsideEveryZoneMayBeBrokenAndTheWorldSpawnIsProtected(GameTestHelper helper) {
        Fixture f = new Fixture(helper, 20);
        f.guard(() -> {
            BotPlayer body = f.body("terrain", null, 8, 8);
            Verdict natural = f.safety.mayBreak(body, f.level, f.at(8, FLOOR_Y, 8), Blocks.STONE.defaultBlockState());
            helper.assertTrue(natural.allowed() && natural.rule().isEmpty(), "natural stone in the arena is refused: " + natural);

            // An ownerless body is bound by the world-spawn rule alone, and the zone is a square of PROTECTED_RADIUS.
            BlockPos spawn = f.level.getSharedSpawnPos();
            for (BlockPos inside : List.of(spawn, spawn.offset(3, 0, -3), spawn.offset(16, 40, 0), spawn.offset(0, -30, -16))) {
                Verdict v = f.safety.mayBreak(body, f.level, inside, Blocks.STONE.defaultBlockState());
                helper.assertTrue(!v.allowed() && v.rule().equals("protected_radius"), "inside the world spawn zone at " + inside + ": " + v);
            }
            for (BlockPos outside : List.of(spawn.offset(17, 0, 0), spawn.offset(0, 0, 17), spawn.offset(-17, 0, -17))) {
                Verdict v = f.safety.mayBreak(body, f.level, outside, Blocks.STONE.defaultBlockState());
                helper.assertTrue(v.allowed(), "outside the world spawn zone at " + outside + ": " + v);
            }
            // The world spawn zone is the overworld's: the same coordinates in the nether are not protected.
            ServerLevel nether = f.server.getLevel(Level.NETHER);
            helper.assertTrue(nether != null, "the test server has no nether to check the dimension rule against");
            Verdict inNether = f.safety.mayBreak(body, nether, spawn, Blocks.STONE.defaultBlockState());
            helper.assertTrue(inNether.allowed(), "the overworld's spawn zone applies in the nether: " + inNether);
        }).run();
        f.pass();
    }

    /** A chest, a bed and a lectern the bot did not place are off-limits to break and to use; ones it placed are not. */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 20)
    public static void containersBedsAndWorkstationsAreOffLimitsUnlessTheBotPlacedThem(GameTestHelper helper) {
        Fixture f = new Fixture(helper, 20);
        f.guard(() -> {
            BotPlayer body = f.body("kinds", null, 12, 12);
            BlockPos chest = f.at(3, FLOOR_Y + 1, 3);
            BlockPos bedFoot = f.at(6, FLOOR_Y + 1, 3);
            BlockPos lectern = f.at(9, FLOOR_Y + 1, 3);
            BlockPos barrel = f.at(3, FLOOR_Y + 1, 6);
            BlockPos smithing = f.at(6, FLOOR_Y + 1, 6);
            BlockPos stone = f.at(9, FLOOR_Y + 1, 6);
            for (BlockPos p : List.of(chest, bedFoot, bedFoot.north(), lectern, barrel, smithing, stone)) f.forgetAtEnd(p);
            f.set(chest, Blocks.CHEST);
            f.level.setBlock(bedFoot, Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.NORTH).setValue(BedBlock.PART, BedPart.FOOT), 3);
            f.level.setBlock(bedFoot.north(), Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.NORTH).setValue(BedBlock.PART, BedPart.HEAD), 3);
            f.set(lectern, Blocks.LECTERN);
            f.set(barrel, Blocks.BARREL);
            f.set(smithing, Blocks.SMITHING_TABLE);
            f.set(stone, Blocks.STONE);

            expectRefusedBoth(f, body, chest, "container");
            expectRefusedBoth(f, body, bedFoot, "bed");
            expectRefusedBoth(f, body, lectern, "workstation");
            // A barrel is a fisherman's job site and a container: it reports as a job site, the rule an allowance
            // never lifts.
            expectRefusedBoth(f, body, barrel, "workstation");
            // The smithing table is a job site and holds nothing, which is the point of deriving jobs from the POI data.
            expectRefusedBoth(f, body, smithing, "workstation");
            Verdict plain = f.safety.mayUseContainer(body, f.level, stone);
            helper.assertTrue(plain.allowed(), "plain stone is refused as a container: " + plain);

            // What the bot placed itself is its own, the librarian reroll's lectern included.
            for (BlockPos mine : List.of(chest, bedFoot, lectern)) f.safety.noteBotPlaced(body, f.level, mine);
            for (BlockPos mine : List.of(chest, bedFoot, lectern)) {
                helper.assertTrue(allowed(f, body, mine), "the body may not break its own " + f.level.getBlockState(mine).getBlock() + ": " + verdict(f, body, mine));
                Verdict use = f.safety.mayUseContainer(body, f.level, mine);
                helper.assertTrue(use.allowed(), "the body may not use its own " + f.level.getBlockState(mine).getBlock() + ": " + use);
            }
        }).run();
        f.pass();
    }

    /**
     * The zone around the owner's bed: refused for any block, the bot's own included; a square of the radius and not a
     * column of it; only in the owner's respawn dimension; gone when the owner has no respawn point.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 20)
    public static void ownersRespawnPointIsProtectedEvenForTheBotsOwnBlocks(GameTestHelper helper) {
        Fixture f = new Fixture(helper, 20);
        f.guard(() -> {
            ServerPlayer owner = f.mockPlayer();
            BlockPos home = f.at(8, FLOOR_Y + 1, 8);
            owner.setRespawnPosition(Level.OVERWORLD, home, 0.0F, true, false);
            BotPlayer body = f.body("home", owner.getGameProfile(), 2, 2);
            ServerLevel level = f.level;

            Verdict near = f.safety.mayBreak(body, level, home.offset(5, 0, 5), Blocks.STONE.defaultBlockState());
            helper.assertTrue(!near.allowed() && near.rule().equals("protected_radius"),
                "a block near the owner's respawn point: " + near);
            // Even a block the bot placed itself.
            BlockPos mine = home.offset(2, 0, 0);
            f.forgetAtEnd(mine);
            f.set(mine, Blocks.COBBLESTONE);
            f.safety.noteBotPlaced(body, level, mine);
            Verdict own = f.safety.mayBreak(body, level, mine, level.getBlockState(mine));
            helper.assertTrue(!own.allowed() && own.rule().equals("protected_radius"), "the bot's own block inside the zone: " + own);

            int r = 16;
            for (BlockPos edge : List.of(home.offset(r, 0, 0), home.offset(-r, 0, 0), home.offset(0, 0, r), home.offset(0, 0, -r), home.offset(r, 0, r), home.offset(0, 200, 0), home.offset(0, -200, 0))) {
                Verdict v = f.safety.mayBreak(body, level, edge, Blocks.STONE.defaultBlockState());
                helper.assertTrue(!v.allowed() && v.rule().equals("protected_radius"), "on the edge of the zone at " + edge + ": " + v);
            }
            for (BlockPos beyond : List.of(home.offset(r + 1, 0, 0), home.offset(-r - 1, 0, 0), home.offset(0, 0, r + 1), home.offset(0, 0, -r - 1), home.offset(r + 1, 0, r + 1))) {
                Verdict v = f.safety.mayBreak(body, level, beyond, Blocks.STONE.defaultBlockState());
                helper.assertTrue(v.allowed(), "beyond the zone at " + beyond + ": " + v);
            }

            // A respawn point in the nether protects nothing in the overworld at the same coordinates.
            owner.setRespawnPosition(Level.NETHER, home, 0.0F, true, false);
            Verdict otherDimension = f.safety.mayBreak(body, level, home.offset(1, 0, 1), Blocks.STONE.defaultBlockState());
            helper.assertTrue(otherDimension.allowed(), "a respawn point in another dimension protects the overworld: " + otherDimension);
            // No respawn point, no zone.
            owner.setRespawnPosition(Level.OVERWORLD, null, 0.0F, false, false);
            Verdict none = f.safety.mayBreak(body, level, home.offset(1, 0, 1), Blocks.STONE.defaultBlockState());
            helper.assertTrue(none.allowed(), "an owner with no respawn point still has a zone: " + none);
        }).run();
        f.pass();
    }

    /**
     * A bot works while its owner is away, which is when the bed needs protecting most: an owner who is not logged in is
     * read from their saved player data.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 20)
    public static void anOfflineOwnersSavedRespawnPointIsProtected(GameTestHelper helper) {
        Fixture f = new Fixture(helper, 20);
        f.guard(() -> {
            UUID ownerId = UUID.randomUUID();
            GameProfile profile = new GameProfile(ownerId, "gt_away_" + RUN_ID);
            BlockPos home = f.at(8, FLOOR_Y + 1, 8);
            ServerPlayer saved = new ServerPlayer(f.server, f.level, profile, ClientInformation.createDefault());
            saved.setRespawnPosition(Level.OVERWORLD, home, 0.0F, true, false);
            Path file = f.server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(ownerId + ".dat");
            try {
                Files.createDirectories(file.getParent());
                NbtIo.writeCompressed(saved.saveWithoutId(new CompoundTag()), file);
            } catch (IOException e) {
                throw new IllegalStateException("could not write the owner's player data", e);
            }
            f.cleanups.add(() -> {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException ignored) {
                    // A leftover file of a random UUID is harmless.
                }
            });
            helper.assertTrue(f.server.getPlayerList().getPlayer(ownerId) == null, "setup: the owner must not be online");

            BotPlayer body = f.body("away", profile, 2, 2);
            Verdict near = f.safety.mayBreak(body, f.level, home.offset(4, 0, 4), Blocks.STONE.defaultBlockState());
            helper.assertTrue(!near.allowed() && near.rule().equals("protected_radius"), "near an offline owner's saved respawn point: " + near);
            Verdict far = f.safety.mayBreak(body, f.level, home.offset(20, 0, 0), Blocks.STONE.defaultBlockState());
            helper.assertTrue(far.allowed(), "far from an offline owner's saved respawn point: " + far);
        }).run();
        f.pass();
    }

    /**
     * An allowance lifts the player-placed and container rules for the positions it covers, for the body that opened it,
     * until it is closed; the protected radius and a claim stay.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 20)
    public static void anAllowanceLiftsPlayerPlacedAndContainerRulesButNeverTheRadiusOrAClaim(GameTestHelper helper) {
        Fixture f = new Fixture(helper, 20);
        List<UUID> claimedFor = new ArrayList<>();
        BlockPos claimedPos = f.at(14, FLOOR_Y + 1, 14);
        ClaimProvider provider = (level, pos, actingFor) -> {
            if (!pos.equals(claimedPos)) return false;
            claimedFor.add(actingFor);
            return true;
        };
        BlockSafety.registerClaimProvider(provider);
        f.cleanups.add(() -> BlockSafety.unregisterClaimProvider(provider));
        f.guard(() -> {
            // The owner's respawn point starts far from the arena, so only the rules under test apply.
            ServerPlayer owner = f.mockPlayer();
            BlockPos away = f.at(-40, FLOOR_Y + 1, -40);
            owner.setRespawnPosition(Level.OVERWORLD, away, 0.0F, true, false);
            BotPlayer body = f.body("allow", owner.getGameProfile(), 12, 12);
            BotPlayer other = f.body("other", null, 13, 13);

            BlockPos covered = f.at(8, FLOOR_Y + 1, 12);
            BlockPos uncovered = f.at(8, FLOOR_Y + 1, 14);
            f.forgetAtEnd(covered, uncovered, claimedPos);
            for (BlockPos p : List.of(covered, uncovered)) {
                f.set(p, Blocks.COBBLESTONE);
                f.placed.recordPlayer(p.asLong(), Blocks.COBBLESTONE);
                helper.assertTrue(!allowed(f, body, p), "setup: a player-placed block should be refused: " + verdict(f, body, p));
            }
            BlockPos chest = f.at(10, FLOOR_Y + 1, 10);
            f.forgetAtEnd(chest);
            f.set(chest, Blocks.CHEST);
            helper.assertTrue(!f.safety.mayUseContainer(body, f.level, chest).allowed(), "setup: a chest the bot did not place should be refused");

            try (Allowance allowance = f.safety.allow(body, "test", p -> p.equals(covered) || p.equals(chest))) {
                helper.assertTrue(allowed(f, body, covered), "an allowance does not lift the player-placed rule: " + verdict(f, body, covered));
                helper.assertTrue(!allowed(f, body, uncovered), "an allowance reaches a position its predicate did not cover");
                helper.assertTrue(f.safety.mayUseContainer(body, f.level, chest).allowed(), "an allowance does not lift the container rule");
                helper.assertTrue(!allowed(f, other, covered), "an allowance reaches a body that did not open it");
            }
            helper.assertTrue(!allowed(f, body, covered), "a closed allowance still lifts the player-placed rule");
            helper.assertTrue(!f.safety.mayUseContainer(body, f.level, chest).allowed(), "a closed allowance still lifts the container rule");

            // The radius is not the allowance's to lift. Put the owner's respawn point next to the blocks.
            owner.setRespawnPosition(Level.OVERWORLD, covered, 0.0F, true, false);
            try (Allowance everything = f.safety.allow(body, "everything", p -> true)) {
                Verdict v = f.safety.mayBreak(body, f.level, covered, f.level.getBlockState(covered));
                helper.assertTrue(!v.allowed() && v.rule().equals("protected_radius"), "an allowance lifted the protected radius: " + v);
            }
            owner.setRespawnPosition(Level.OVERWORLD, away, 0.0F, true, false);

            // Nor a claim, which is asked on behalf of the owner.
            f.set(claimedPos, Blocks.STONE);
            Verdict claimed = f.safety.mayBreak(body, f.level, claimedPos, f.level.getBlockState(claimedPos));
            helper.assertTrue(!claimed.allowed() && claimed.rule().equals("claim"), "a claimed position: " + claimed);
            try (Allowance everything = f.safety.allow(body, "everything", p -> true)) {
                Verdict v = f.safety.mayBreak(body, f.level, claimedPos, f.level.getBlockState(claimedPos));
                helper.assertTrue(!v.allowed() && v.rule().equals("claim"), "an allowance lifted a claim: " + v);
            }
            helper.assertTrue(claimedFor.contains(owner.getUUID()), "the claim provider was not asked on behalf of the owner: " + claimedFor);
        }).run();
        f.pass();
    }

    /**
     * An allowance lifts two rules, player-placed and container, and nothing else: a bed or a villager job site that the
     * bot did not place stays off-limits to break and to use whatever any goal says, because the one thing a goal that
     * wants a chest cannot want is somebody's bed.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 20)
    public static void anAllowanceNeverLiftsABedOrAJobSite(GameTestHelper helper) {
        Fixture f = new Fixture(helper, 20);
        f.guard(() -> {
            BotPlayer body = f.body("lift", null, 12, 12);
            BlockPos bedFoot = f.at(6, FLOOR_Y + 1, 3);
            BlockPos lectern = f.at(9, FLOOR_Y + 1, 3);
            BlockPos smithing = f.at(6, FLOOR_Y + 1, 6);
            BlockPos chest = f.at(3, FLOOR_Y + 1, 3);
            for (BlockPos p : List.of(bedFoot, bedFoot.north(), lectern, smithing, chest)) f.forgetAtEnd(p);
            f.level.setBlock(bedFoot, Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.NORTH).setValue(BedBlock.PART, BedPart.FOOT), 3);
            f.level.setBlock(bedFoot.north(), Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.NORTH).setValue(BedBlock.PART, BedPart.HEAD), 3);
            f.set(lectern, Blocks.LECTERN);
            f.set(smithing, Blocks.SMITHING_TABLE);
            f.set(chest, Blocks.CHEST);

            try (Allowance everything = f.safety.allow(body, "everything", p -> true)) {
                // Control: what the allowance is for does lift.
                helper.assertTrue(allowed(f, body, chest) && f.safety.mayUseContainer(body, f.level, chest).allowed(),
                    "control: an allowance does not lift the container rule");
                expectRefusedBoth(f, body, bedFoot, "bed");
                expectRefusedBoth(f, body, bedFoot.north(), "bed");
                expectRefusedBoth(f, body, lectern, "workstation");
                expectRefusedBoth(f, body, smithing, "workstation");
            }
        }).run();
        f.pass();
    }

    /**
     * An allowance a goal fails to close must not outlive the body it was opened for: it ends when the body is
     * despawned, and {@code closeAll} ends every allowance of a body at once (what the brain calls when a goal ends).
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 20)
    public static void anAllowanceEndsWithItsBodyAndCloseAllEndsTheRest(GameTestHelper helper) {
        Fixture f = new Fixture(helper, 20);
        f.guard(() -> {
            BotPlayer a = f.body("leak_a", null, 2, 2);
            BotPlayer b = f.body("leak_b", null, 12, 12);
            BlockPos pos = f.at(8, FLOOR_Y + 1, 8);
            f.forgetAtEnd(pos);
            f.set(pos, Blocks.COBBLESTONE);
            f.placed.recordPlayer(pos.asLong(), Blocks.COBBLESTONE);

            Allowance forA = f.safety.allow(a, "a", p -> true);
            Allowance firstForB = f.safety.allow(b, "b1", p -> true);
            Allowance secondForB = f.safety.allow(b, "b2", p -> true);
            helper.assertTrue(allowed(f, a, pos) && allowed(f, b, pos), "setup: both allowances should lift the player-placed rule");

            f.safety.closeAll(b);
            helper.assertTrue(!allowed(f, b, pos), "closeAll left an allowance of the body open");
            helper.assertTrue(allowed(f, a, pos), "closeAll closed the allowance of another body");
            // Closing what closeAll already closed is harmless.
            firstForB.close();
            secondForB.close();

            BodyManager.of(f.server).despawn(a);
            helper.assertTrue(!allowed(f, a, pos), "an allowance outlived the despawn of its body");
            forA.close();
        }).run();
        f.pass();
    }

    /**
     * A body that dies is replaced by a new instance with the same identity: the allowance a goal opened for the dead one
     * is over, and must not pass to the replacement, whatever the brain did or did not do about it.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 80)
    public static void anAllowanceDoesNotSurviveItsBodysDeath(GameTestHelper helper) {
        Fixture f = new Fixture(helper, 80);
        f.guard(() -> {
            BotPlayer original = f.body("dies", null, 8, 8);
            BlockPos pos = f.at(4, FLOOR_Y + 1, 4);
            f.forgetAtEnd(pos);
            f.set(pos, Blocks.COBBLESTONE);
            f.placed.recordPlayer(pos.asLong(), Blocks.COBBLESTONE);
            Allowance leaked = f.safety.allow(original, "never closed", p -> true);
            helper.assertTrue(allowed(f, original, pos), "setup: the allowance should lift the player-placed rule");

            // genericKill bypasses the invulnerability a new body has for its first seconds.
            original.hurt(f.level.damageSources().genericKill(), 1000.0F);
            helper.assertTrue(original.isDeadOrDying(), "setup: the body did not die");
            helper.assertTrue(!allowed(f, original, pos), "an allowance outlived the death of its body");

            int[] ticks = {0};
            f.onEachTick(() -> {
                BotPlayer now = BodyManager.of(f.server).get(original.getUUID());
                if (now == original || now == null) {
                    helper.assertTrue(++ticks[0] < 40, "the dead body was not replaced");
                    return;
                }
                helper.assertTrue(!allowed(f, now, pos), "the allowance of a dead body lifts the rule for its replacement");
                leaked.close();
                f.pass();
            });
        }).run();
    }

    /**
     * A double chest is two block entities, each recorded when its own half was placed. Breaking is per half: the bot may
     * break the half it placed, and not the half somebody else did. Using is not: the two halves are one 54-slot
     * inventory, so a bot's chest set beside a player's chest is a window onto the player's items, and using either half
     * needs both to be the bot's.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 20)
    public static void aBotsChestBesideAPlayersChestIsNoWindowOntoTheirItems(GameTestHelper helper) {
        Fixture f = new Fixture(helper, 20);
        f.guard(() -> {
            BotPlayer body = f.body("double", null, 12, 12);
            // Facing north, the left half's partner is to the east.
            BlockPos mine = f.at(4, FLOOR_Y + 1, 4);
            BlockPos theirs = mine.east();
            f.forgetAtEnd(mine, theirs);
            f.level.setBlock(mine, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH).setValue(ChestBlock.TYPE, ChestType.LEFT), 3);
            f.level.setBlock(theirs, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH).setValue(ChestBlock.TYPE, ChestType.RIGHT), 3);
            f.placed.recordPlayer(theirs.asLong(), Blocks.CHEST);
            f.safety.noteBotPlaced(body, f.level, mine);

            helper.assertTrue(allowed(f, body, mine), "the body may not break the half of a double chest that it placed: " + verdict(f, body, mine));
            helper.assertTrue(!allowed(f, body, theirs), "the body may break the half of a double chest that a player placed");
            Verdict use = f.safety.mayUseContainer(body, f.level, mine);
            helper.assertTrue(!use.allowed() && use.rule().equals("container"),
                "the body may use its own half of a double chest although the other half is a player's: " + use);

            f.safety.noteBotPlaced(body, f.level, theirs);
            helper.assertTrue(f.safety.mayUseContainer(body, f.level, mine).allowed() && f.safety.mayUseContainer(body, f.level, theirs).allowed(),
                "the body may not use a double chest that it placed both halves of");
        }).run();
        f.pass();
    }

    /**
     * The owner's respawn point is where they respawn now, online or not. Two ways the old cache was wrong when the owner
     * left: a "no respawn point" taken while they were online hid the one they set (and saved) just before logging out,
     * and a respawn point cached while they were online kept protecting the old bed after they moved it.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 20)
    public static void anOwnersRespawnPointIsFollowedThroughLoggingOut(GameTestHelper helper) {
        Fixture f = new Fixture(helper, 20);
        f.guard(() -> {
            BlockPos home = f.at(8, FLOOR_Y + 1, 8);
            BlockPos oldHome = f.at(-40, FLOOR_Y + 1, -40);

            // 1: asked while they have none, then they set one and leave.
            ServerPlayer first = f.mockPlayer();
            BotPlayer firstBody = f.body("out_a", first.getGameProfile(), 2, 2);
            Verdict before = f.safety.mayBreak(firstBody, f.level, home.offset(4, 0, 4), Blocks.STONE.defaultBlockState());
            helper.assertTrue(before.allowed(), "setup: an owner with no respawn point has a zone: " + before);
            first.setRespawnPosition(Level.OVERWORLD, home, 0.0F, true, false);
            f.server.getPlayerList().remove(first); // saves their data, as logging out does
            helper.assertTrue(f.server.getPlayerList().getPlayer(first.getUUID()) == null, "setup: the owner is still online");
            Verdict after = f.safety.mayBreak(firstBody, f.level, home.offset(4, 0, 4), Blocks.STONE.defaultBlockState());
            helper.assertTrue(!after.allowed() && after.rule().equals("protected_radius"),
                "the respawn point set just before logging out does not protect anything: " + after);

            // 2: asked while the old one is set, then they move it and leave.
            ServerPlayer second = f.mockPlayer();
            second.setRespawnPosition(Level.OVERWORLD, oldHome, 0.0F, true, false);
            BotPlayer secondBody = f.body("out_b", second.getGameProfile(), 12, 12);
            Verdict old = f.safety.mayBreak(secondBody, f.level, oldHome.offset(3, 0, 3), Blocks.STONE.defaultBlockState());
            helper.assertTrue(!old.allowed(), "setup: the owner's respawn point protects nothing: " + old);
            second.setRespawnPosition(Level.OVERWORLD, home, 0.0F, true, false);
            f.server.getPlayerList().remove(second);
            Verdict moved = f.safety.mayBreak(secondBody, f.level, home.offset(4, 0, 4), Blocks.STONE.defaultBlockState());
            helper.assertTrue(!moved.allowed() && moved.rule().equals("protected_radius"),
                "the respawn point they moved to does not protect anything: " + moved);
            Verdict stale = f.safety.mayBreak(secondBody, f.level, oldHome.offset(3, 0, 3), Blocks.STONE.defaultBlockState());
            helper.assertTrue(stale.allowed(), "the respawn point they moved away from still protects: " + stale);
        }).run();
        f.pass();
    }

    // ---------------------------------------------------------------------------------------------------------
    // The saved record
    // ---------------------------------------------------------------------------------------------------------

    /**
     * The record encodes and decodes to the same answers (player and bot entries, negative coordinates, the extremes of
     * the height range), is dirty only when something changed, and a damaged file costs only what is damaged.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 20)
    public static void placedBlockDataRoundTripsAndSkipsWhatItCannotRead(GameTestHelper helper) {
        var registries = helper.getLevel().registryAccess();
        UUID botA = UUID.randomUUID();
        UUID botB = UUID.randomUUID();
        // One position per chunk, so that damaging one chunk's entry costs exactly that position.
        BlockPos[] players = {new BlockPos(5, 70, -3), new BlockPos(-100, -60, 250), new BlockPos(0, -2048, 0), new BlockPos(-1, 2047, -1), new BlockPos(-33_000, 5, 33_000)};
        Block[] playerBlocks = {Blocks.STONE, Blocks.OBSIDIAN, Blocks.COBBLESTONE, Blocks.OAK_PLANKS, Blocks.STONE};
        BlockPos[] botPositions = {new BlockPos(48, 64, 48), new BlockPos(-160, 10, 160), new BlockPos(300, -5, -300)};
        UUID[] botOwners = {botA, botA, botB};
        Block[] botBlocks = {Blocks.CHEST, Blocks.LECTERN, Blocks.COBBLESTONE};

        PlacedBlocks data = new PlacedBlocks();
        helper.assertTrue(!data.isDirty(), "a new record is dirty");
        for (int i = 0; i < players.length; i++) data.recordPlayer(players[i].asLong(), playerBlocks[i]);
        for (int i = 0; i < botPositions.length; i++) data.recordBot(botPositions[i].asLong(), botBlocks[i], botOwners[i]);
        helper.assertTrue(data.isDirty() && data.size() == players.length + botPositions.length, "recording did not mark the record dirty, size " + data.size());

        // Written only when dirty: the same facts again change nothing.
        data.setDirty(false);
        data.recordPlayer(players[0].asLong(), playerBlocks[0]);
        data.recordBot(botPositions[0].asLong(), botBlocks[0], botOwners[0]);
        helper.assertTrue(!data.forget(BlockPos.ZERO.asLong()), "forgetting an unrecorded position reported a record");
        helper.assertTrue(!data.isDirty(), "recording what is already recorded, or forgetting nothing, marked the record dirty");
        data.recordPlayer(players[0].asLong(), Blocks.DIRT);
        helper.assertTrue(data.isDirty(), "changing a record did not mark it dirty");
        data.recordPlayer(players[0].asLong(), playerBlocks[0]);

        CompoundTag saved = data.save(new CompoundTag(), registries);
        expectSameAnswers(helper, "round trip", data, PlacedBlocks.load(saved, registries), players, playerBlocks, botPositions, botBlocks, botOwners, botA, botB);

        // A file of a schema this version does not know is read as empty, not as garbage.
        CompoundTag future = saved.copy();
        future.putInt("schema", PlacedBlocks.SCHEMA + 98);
        helper.assertTrue(PlacedBlocks.load(future, registries).size() == 0, "a file of an unknown schema was read");

        // One chunk entry of an unknown version: that chunk's position is lost, every other survives.
        CompoundTag future1 = saved.copy();
        setChunkField(future1, players[1], "v", 99);
        PlacedBlocks without1 = PlacedBlocks.load(future1, registries);
        helper.assertTrue(without1.size() == data.size() - 1 && without1.lookup(players[1].asLong(), playerBlocks[1], botA) == PlacedBlocks.NONE,
            "an entry of an unknown schema was read, or took others with it: size " + without1.size());
        helper.assertTrue(without1.lookup(players[0].asLong(), playerBlocks[0], botA) == PlacedBlocks.PLAYER, "an unknown entry cost a good one");

        // A block that no longer exists loses its entries only.
        CompoundTag renamed = saved.copy();
        ListTag palette = renamed.getList("palette", Tag.TAG_STRING);
        boolean found = false;
        for (int i = 0; i < palette.size(); i++) {
            if (palette.getString(i).equals("minecraft:obsidian")) {
                palette.set(i, StringTag.valueOf("megablockbot_test:no_such_block"));
                found = true;
            }
        }
        helper.assertTrue(found, "setup: obsidian is not in the palette");
        PlacedBlocks withoutObsidian = PlacedBlocks.load(renamed, registries);
        helper.assertTrue(withoutObsidian.size() == data.size() - 1 && withoutObsidian.lookup(players[1].asLong(), Blocks.OBSIDIAN, botA) == PlacedBlocks.NONE,
            "an entry naming a missing block was read, or took others with it: size " + withoutObsidian.size());

        // A chunk entry that is not even a chunk entry.
        CompoundTag junk = saved.copy();
        junk.getList("chunks", Tag.TAG_COMPOUND).add(new CompoundTag());
        helper.assertTrue(PlacedBlocks.load(junk, registries).size() == data.size(), "an empty chunk entry cost the good ones");
        helper.succeed();
    }

    private static void expectSameAnswers(GameTestHelper helper, String what, PlacedBlocks expected, PlacedBlocks actual, BlockPos[] players,
                                          Block[] playerBlocks, BlockPos[] botPositions, Block[] botBlocks, UUID[] botOwners, UUID botA, UUID botB) {
        helper.assertTrue(actual.size() == expected.size(), what + ": size " + actual.size() + ", expected " + expected.size());
        for (int i = 0; i < players.length; i++) {
            long pos = players[i].asLong();
            helper.assertTrue(actual.lookup(pos, playerBlocks[i], botA) == PlacedBlocks.PLAYER, what + ": player entry " + players[i] + " reads as " + actual.describe(pos));
            helper.assertTrue(actual.lookup(pos, Blocks.BEDROCK, botA) == PlacedBlocks.STALE, what + ": a different block at " + players[i] + " is not stale");
        }
        for (int i = 0; i < botPositions.length; i++) {
            long pos = botPositions[i].asLong();
            UUID owner = botOwners[i];
            UUID stranger = owner.equals(botA) ? botB : botA;
            helper.assertTrue(actual.lookup(pos, botBlocks[i], owner) == PlacedBlocks.BOT_SELF, what + ": bot entry " + botPositions[i] + " reads as " + actual.describe(pos));
            helper.assertTrue(actual.lookup(pos, botBlocks[i], stranger) == PlacedBlocks.BOT_OTHER, what + ": bot entry " + botPositions[i] + " is not another bot's to the stranger");
        }
        helper.assertTrue(actual.lookup(BlockPos.ZERO.asLong(), Blocks.STONE, botA) == PlacedBlocks.NONE, what + ": an unrecorded position reads as recorded");
    }

    private static void setChunkField(CompoundTag saved, BlockPos inChunk, String field, int value) {
        ListTag chunks = saved.getList("chunks", Tag.TAG_COMPOUND);
        for (int i = 0; i < chunks.size(); i++) {
            CompoundTag chunk = chunks.getCompound(i);
            if (chunk.getInt("x") == (inChunk.getX() >> 4) && chunk.getInt("z") == (inChunk.getZ() >> 4)) {
                chunk.putInt(field, value);
                return;
            }
        }
        throw new IllegalStateException("no chunk entry for " + inChunk);
    }

    // ---------------------------------------------------------------------------------------------------------
    // The debug command
    // ---------------------------------------------------------------------------------------------------------

    /**
     * {@code /aiplayer safety check}: needs permission 2, answers for a named body or an ownerless one, and its result
     * says whether the block may be broken. What it prints is not pinned here: a wording change is not a regression.
     */
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 20)
    public static void safetyCheckCommandAnswersForANamedBodyAndAnOwnerlessOne(GameTestHelper helper) {
        Fixture f = new Fixture(helper, 20);
        f.guard(() -> {
            BotPlayer body = f.body("cmd", null, 2, 2);
            BlockPos placedPos = f.at(8, FLOOR_Y + 1, 8);
            f.forgetAtEnd(placedPos);
            f.set(placedPos, Blocks.COBBLESTONE);
            f.placed.recordPlayer(placedPos.asLong(), Blocks.COBBLESTONE);
            BlockPos terrain = f.at(8, FLOOR_Y, 8);

            CommandSourceStack operator = source(f, 2);
            int refused = run(helper, f, operator, "aiplayer safety check " + coords(placedPos) + " " + body.getGameProfile().getName());
            helper.assertTrue(refused == 0, "the command reports a refused block as success: " + refused);
            int allowed = run(helper, f, operator, "aiplayer safety check " + coords(terrain));
            helper.assertTrue(allowed == 1, "the command reports terrain as refused: " + allowed);

            // Permission level 2, like the lint command: an ordinary player does not get to see it.
            boolean refusedToPlayer;
            try {
                f.server.getCommands().getDispatcher().execute("aiplayer safety check " + coords(terrain), source(f, 0));
                refusedToPlayer = false;
            } catch (CommandSyntaxException e) {
                refusedToPlayer = true;
            }
            helper.assertTrue(refusedToPlayer, "a permission level 0 source ran the safety command");
        }).run();
        f.pass();
    }

    private static int run(GameTestHelper helper, Fixture f, CommandSourceStack source, String command) {
        try {
            return f.server.getCommands().getDispatcher().execute(command, source);
        } catch (CommandSyntaxException e) {
            helper.fail("'" + command + "' did not run: " + e.getMessage());
            return -1;
        }
    }

    private static String coords(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    /** A command source at a permission level that discards what it is sent. */
    private static CommandSourceStack source(Fixture f, int permission) {
        CommandSource sink = new CommandSource() {
            @Override public void sendSystemMessage(Component message) {}
            @Override public boolean acceptsSuccess() { return true; }
            @Override public boolean acceptsFailure() { return true; }
            @Override public boolean shouldInformAdmins() { return false; }
        };
        return new CommandSourceStack(sink, Vec3.ZERO, Vec2.ZERO, f.level, permission, "gt", Component.literal("gt"), f.server, null);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Support
    // ---------------------------------------------------------------------------------------------------------

    private static Verdict verdict(Fixture f, BotPlayer body, BlockPos pos) {
        return f.safety.mayBreak(body, f.level, pos, f.level.getBlockState(pos));
    }

    private static boolean allowed(Fixture f, BotPlayer body, BlockPos pos) {
        return verdict(f, body, pos).allowed();
    }

    /** Both questions refuse, each with the same rule. */
    private static void expectRefusedBoth(Fixture f, BotPlayer body, BlockPos pos, String rule) {
        Verdict brk = verdict(f, body, pos);
        f.helper.assertTrue(!brk.allowed() && brk.rule().equals(rule), "breaking " + f.level.getBlockState(pos).getBlock() + ": expected " + rule + ", got " + brk);
        Verdict use = f.safety.mayUseContainer(body, f.level, pos);
        f.helper.assertTrue(!use.allowed() && use.rule().equals(rule), "using " + f.level.getBlockState(pos).getBlock() + ": expected " + rule + ", got " + use);
    }

    private static void forgetPlayerFiles(MinecraftServer server, UUID id) {
        try {
            Files.deleteIfExists(server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(id + ".dat"));
            Files.deleteIfExists(server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(id + ".dat_old"));
            Files.deleteIfExists(server.getWorldPath(LevelResource.PLAYER_STATS_DIR).resolve(id + ".json"));
            Files.deleteIfExists(server.getWorldPath(LevelResource.PLAYER_ADVANCEMENTS_DIR).resolve(id + ".json"));
        } catch (IOException ignored) {
            // Leftover files of a test player are harmless; its id is random.
        }
    }

    /**
     * One test's world: a stone floor, bodies and a mock player it made, and the records it wrote, all undone when the
     * test ends, passed or not (bodies under a name unique to this run, like the body tests).
     */
    private static final class Fixture {
        final GameTestHelper helper;
        final ServerLevel level;
        final MinecraftServer server;
        final BlockSafety safety;
        final PlacedBlocks placed;
        final List<Runnable> cleanups = new ArrayList<>();

        /** @param deadlineTicks the test's {@code timeoutTicks}: if the test is still running then, it is cleaned up. */
        Fixture(GameTestHelper helper, int deadlineTicks) {
            this.helper = helper;
            this.level = helper.getLevel();
            this.server = level.getServer();
            this.safety = BlockSafety.of(server);
            this.placed = safety.placedBlocks(level);
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) helper.setBlock(x, FLOOR_Y, z, Blocks.STONE);
            }
            // A test that never ends is failed by the framework; this makes sure it does not leave its bodies or its
            // placed-block records in the GameTest world (the same deadline cleanup the body tests use).
            helper.runAtTickTime(deadlineTicks, this::close);
        }

        BlockPos at(int x, int y, int z) {
            return helper.absolutePos(new BlockPos(x, y, z));
        }

        void set(BlockPos absolute, Block block) {
            level.setBlock(absolute, block.defaultBlockState(), 3);
        }

        /** Drops these positions' records when the test ends, so none leaks into the saved level. */
        void forgetAtEnd(BlockPos... positions) {
            cleanups.add(() -> {
                for (BlockPos p : positions) placed.forget(p.asLong());
            });
        }

        /** Spawns a body standing on the floor at (x, z) of the arena. */
        BotPlayer body(String tag, @Nullable GameProfile owner, int x, int z) {
            // A body name is at most 16 characters: "gs_" + tag (up to 8) + "_" + the run id (up to 4).
            String name = "gs_" + tag + "_" + RUN_ID;
            BodyManager manager = BodyManager.of(server);
            BotPlayer body;
            try {
                body = manager.spawn(name, owner, level, Vec3.atBottomCenterOf(at(x, FLOOR_Y + 1, z)), 0.0F, null);
            } catch (BodyException e) {
                throw new IllegalStateException("could not spawn " + name + ": " + e.getMessage(), e);
            }
            cleanups.add(() -> {
                BotPlayer current = manager.get(name);
                if (current != null) manager.despawn(current);
                try {
                    manager.deleteSaved(BodyManager.uuidFor(name));
                } catch (BodyException ignored) {
                    // Still logged in only if the despawn did not take; the unique name keeps it from mattering.
                }
            });
            return body;
        }

        /** A player in the player list (so it counts as online), removed again at the end. */
        @SuppressWarnings("removal") // GameTestHelper marks it for removal; the replacement does not place the player in the list
        ServerPlayer mockPlayer() {
            ServerPlayer player = helper.makeMockServerPlayerInLevel();
            cleanups.add(() -> {
                // A test that logged the player out itself must not log it out twice.
                if (server.getPlayerList().getPlayers().contains(player)) server.getPlayerList().remove(player);
                forgetPlayerFiles(server, player.getUUID());
            });
            return player;
        }

        /** Right-clicks the top of {@code against} with one {@code item}, as a client would. */
        InteractionResult place(ServerPlayer player, Item item, BlockPos against) {
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(item, 8));
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(against).add(0.0, 0.5, 0.0), Direction.UP, against, false);
            return player.gameMode.useItemOn(player, level, player.getItemInHand(InteractionHand.MAIN_HAND), InteractionHand.MAIN_HAND, hit);
        }

        /** Runs later, cleaning up first if it throws. */
        void then(int delayTicks, Runnable action) {
            helper.runAfterDelay(delayTicks, guard(action));
        }

        /** Runs every tick until the test ends, cleaning up first if it throws. */
        void onEachTick(Runnable action) {
            helper.onEachTick(guard(action));
        }

        Runnable guard(Runnable action) {
            return () -> {
                try {
                    action.run();
                } catch (RuntimeException | Error e) {
                    close();
                    throw e;
                }
            };
        }

        void pass() {
            close();
            helper.succeed();
        }

        private void close() {
            for (int i = cleanups.size() - 1; i >= 0; i--) {
                try {
                    cleanups.get(i).run();
                } catch (RuntimeException ignored) {
                    // Cleanup must not hide the failure that brought it here.
                }
            }
            cleanups.clear();
        }
    }
}
