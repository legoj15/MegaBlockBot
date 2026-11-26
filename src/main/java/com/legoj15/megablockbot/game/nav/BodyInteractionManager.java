package com.legoj15.megablockbot.game.nav;

import com.legoj15.megablockbot.nav.engine.api.entity.IInteractionManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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
                    if (player.level().getBlockState(pos).isAir()) broken++;
                    mining = false;
                    progress = 0.0F;
                    cooldownUntil = now() + DESTROY_DELAY;
                }
            }
            case ABORT_DESTROY_BLOCK -> {
                pendingPos = null;
                if (mining) player.gameMode.handleBlockBreakAction(miningPos, action, direction, worldHeight, sequence);
                mining = false;
                progress = 0.0F;
            }
            default -> { }
        }
    }

    private void start(BlockPos pos, Direction face, int worldHeight, int sequence) {
        pendingPos = null;
        brokeBlock = true;
        boolean wasAir = player.level().getBlockState(pos).isAir();
        player.gameMode.handleBlockBreakAction(pos, Action.START_DESTROY_BLOCK, face, worldHeight, sequence);
        if (!wasAir && player.level().getBlockState(pos).isAir()) broken++;
        // An instant break is already done, and a refused START (out of reach, protected, cancelled) set nothing:
        // either way there is nothing to count. Only a START the game mode is now working on is mined.
        mining = player.gameMode.isDestroyingBlock && !player.level().getBlockState(pos).isAir();
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
