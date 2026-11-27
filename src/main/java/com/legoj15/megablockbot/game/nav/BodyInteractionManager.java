package com.legoj15.megablockbot.game.nav;

import com.legoj15.megablockbot.nav.engine.api.entity.IInteractionManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Breaks, places and uses for a body exactly as the server handles a client's packets: every action goes through
 * the player's own {@code ServerPlayerGameMode}, so the break and place events fire, reach and spawn protection are
 * enforced, the tool wears, drops follow the harvest rules and a break takes as long as it takes a player.
 *
 * <p>This class plays the client's side of breaking (vanilla's {@code MultiPlayerGameMode}): it sends START, adds
 * the block's destroy progress for every tick the engine keeps hitting it (the start tick does not count, as on a
 * client), sends STOP once that reaches 1, and then waits the client's five ticks before it starts another block.
 * The game mode keeps its own count from its own tick and destroys the block on STOP.
 */
public final class BodyInteractionManager implements IInteractionManager {
    /** A client's {@code destroyDelay}: ticks between finishing a block it had to work at and starting the next. */
    private static final int DESTROY_DELAY = 5;

    private final ServerPlayer player;
    private boolean mining;
    private BlockPos miningPos = BlockPos.ZERO;
    private float progress;
    private long startedAt = Long.MIN_VALUE;
    private long cooldownUntil = Long.MIN_VALUE;
    // A START asked for during the cooldown: sent once the cooldown is over, if the engine still wants it.
    private BlockPos pendingPos;
    private Direction pendingFace = Direction.UP;
    private int pendingHeight;
    private boolean brokeBlock;
    // Counts for the dev command's report: blocks this body broke and placed through this manager.
    private int broken;
    private int placed;
    // Where the last block this body broke stood (a task keeps it as a hint of where the ore is).
    private BlockPos lastBroken;

    public BodyInteractionManager(ServerPlayer player) {
        this.player = player;
    }

    private long now() {
        return player.level().getGameTime();
    }

    @Override
    public void processBlockBreakingAction(BlockPos pos, Action action, Direction direction, int worldHeight, int sequence) {
        switch (action) {
            case START_DESTROY_BLOCK -> {
                if (now() < cooldownUntil) {
                    pendingPos = pos.immutable();
                    pendingFace = direction;
                    pendingHeight = worldHeight;
                } else {
                    start(pos, direction, worldHeight, sequence);
                }
            }
            case STOP_DESTROY_BLOCK -> {
                if (mining && pos.equals(miningPos)) {
                    player.gameMode.handleBlockBreakAction(pos, action, direction, worldHeight, sequence);
                    if (player.level().getBlockState(pos).isAir()) {
                        broken++;
                        lastBroken = pos.immutable();
                    }
                    mining = false;
                    progress = 0.0F;
                    cooldownUntil = now() + DESTROY_DELAY;
                }
            }
            case ABORT_DESTROY_BLOCK -> {
                pendingPos = null;
                releaseGameMode(direction, worldHeight, sequence);
                mining = false;
                progress = 0.0F;
            }
            default -> { }
        }
    }

    /**
     * Lets go of whatever break this body has begun, as a player does by releasing the mouse button: nothing is mined,
     * nothing waits to start, and the game mode is no longer working on a block. A no-op when nothing was begun.
     */
    public void abortBreak() {
        pendingPos = null;
        releaseGameMode(Direction.UP, player.level().getMaxBuildHeight(), 0);
        mining = false;
        progress = 0.0F;
    }

    /**
     * Sends the game mode an abort for the block it is working on, if it is working on one: the one this manager began,
     * or one an earlier break left behind in the game mode's own state. The flag can outlive a break (a STOP the
     * game mode refused, a cancelled event), and a flag left set would make a refused START look accepted.
     */
    private void releaseGameMode(Direction face, int worldHeight, int sequence) {
        ServerPlayerGameMode gameMode = player.gameMode;
        if (!mining && !gameMode.isDestroyingBlock) return;
        BlockPos target = gameMode.isDestroyingBlock ? gameMode.destroyPos : miningPos;
        gameMode.handleBlockBreakAction(target, Action.ABORT_DESTROY_BLOCK, face, worldHeight, sequence);
        // The game mode ignores an abort for a block now out of reach, and a mod can cancel one: whatever is left of
        // the break (the flag, the crack shown to other players) is cleared here, since this body is done with it.
        gameMode.isDestroyingBlock = false;
        player.level().destroyBlockProgress(player.getId(), target, -1);
    }

    private void start(BlockPos pos, Direction face, int worldHeight, int sequence) {
        pendingPos = null;
        brokeBlock = true;
        // A flag an earlier break left set is cleared first, as a client aborts the block it was on before it starts
        // another: then the flag after the START says whether the game mode took this one.
        releaseGameMode(face, worldHeight, sequence);
        boolean wasAir = player.level().getBlockState(pos).isAir();
        player.gameMode.handleBlockBreakAction(pos, Action.START_DESTROY_BLOCK, face, worldHeight, sequence);
        boolean air = player.level().getBlockState(pos).isAir();
        if (!wasAir && air) {
            broken++;
            lastBroken = pos.immutable();
        }
        // An instant break is already done, and a refused START (out of reach, protected, cancelled) set nothing:
        // either way there is nothing to count. Only a START the game mode is now working on is mined.
        mining = player.gameMode.isDestroyingBlock && player.gameMode.destroyPos.equals(pos) && !air;
        miningPos = pos.immutable();
        progress = 0.0F;
        startedAt = now();
    }

    @Override
    public void continueBreaking(BlockPos pos, Direction side) {
        if (pendingPos != null) {
            if (now() >= cooldownUntil) start(pendingPos, pendingFace, pendingHeight, 0);
            return;
        }
        if (!mining) return;
        BlockState state = player.level().getBlockState(miningPos);
        if (state.isAir() || !player.gameMode.isDestroyingBlock) {
            // Gone by other means, or the game mode dropped it: a client stops too.
            mining = false;
            progress = 0.0F;
            return;
        }
        if (now() == startedAt) return;
        progress += state.getDestroyProgress(player, player.level(), miningPos);
    }

    @Override
    public boolean isMining() {
        return mining || pendingPos != null;
    }

    @Override
    public BlockPos getMiningPos() {
        return pendingPos != null ? pendingPos : miningPos;
    }

    @Override
    public int getBlockBreakingProgress() {
        // Tenths, the engine's scale: it sends STOP at 10. Nothing to stop while a START still waits.
        return mining ? (int) (progress * 10.0F) : -1;
    }

    @Override
    public boolean hasBrokenBlock() {
        return brokeBlock;
    }

    /** As the packet handler does for a client's use-on-block: reach and spawn protection first, then the game mode. */
    @Override
    public InteractionResult interactBlock(LivingEntity entity, Level world, ItemStack stack, InteractionHand hand, BlockHitResult hitResult) {
        if (!(world instanceof ServerLevel level)) return InteractionResult.FAIL;
        BlockPos pos = hitResult.getBlockPos();
        if (pos.getY() >= level.getMaxBuildHeight() || !player.canInteractWithBlock(pos, 1.0) || !level.mayInteract(player, pos)) {
            return InteractionResult.FAIL;
        }
        player.resetLastActionTime();
        boolean block = stack.getItem() instanceof net.minecraft.world.item.BlockItem;
        InteractionResult result = player.gameMode.useItemOn(player, level, stack, hand, hitResult);
        if (block && result.consumesAction()) placed++;
        return result;
    }

    public int blocksBroken() {
        return broken;
    }

    /** Where the last block this body broke stood, or null when it has broken none. */
    public BlockPos lastBrokenPos() {
        return lastBroken;
    }

    public int blocksPlaced() {
        return placed;
    }

    @Override
    public InteractionResult interactItem(LivingEntity entity, Level world, ItemStack stack, InteractionHand hand) {
        if (stack.isEmpty()) return InteractionResult.PASS;
        player.resetLastActionTime();
        return player.gameMode.useItem(player, world, stack, hand);
    }
}
