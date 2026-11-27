package com.legoj15.megablockbot.game.reflex;

import com.legoj15.megablockbot.game.body.BotPlayer;
import com.legoj15.megablockbot.game.safety.BlockSafety;
import com.legoj15.megablockbot.game.safety.Verdict;
import com.legoj15.megablockbot.nav.engine.api.entity.IInteractionManager;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Breaks and places blocks for a reflex the way a client does, through the body's interaction manager and so through
 * its own game mode: a block takes as long to break as it takes a player, a click only lands on a face the eyes can see
 * and reach, and the break and place events fire. Every break asks {@link BlockSafety} first: this is the single place
 * the reflex layer asks "may I break that" (design 3.5); a refused block is reported, never broken.
 */
final class BlockWork {
    enum Outcome {
        /** Not finished: call again next tick. */
        WORKING,
        /** The block is gone. */
        DONE,
        /** The break rules forbid it. */
        REFUSED,
        /** No face of it can be seen and reached from here. */
        UNREACHABLE
    }

    private int sequence;

    /** One more tick of breaking the block at {@code pos}: starts it, keeps hitting it, finishes it. */
    Outcome breakBlock(ReflexContext ctx, BlockPos pos) {
        BotPlayer body = ctx.body;
        IInteractionManager manager = body.getInteractionManager();
        BlockState state = ctx.level.getBlockState(pos);
        if (state.isAir()) {
            abort(body);
            return Outcome.DONE;
        }
        Verdict verdict = BlockSafety.of(ctx.level.getServer()).mayBreak(body, ctx.level, pos, state);
        if (!verdict.allowed()) {
            abort(body);
            return Outcome.REFUSED;
        }
        int maxY = ctx.level.getMaxBuildHeight();
        if (!manager.isMining() || !manager.getMiningPos().equals(pos)) {
            abort(body);
            BlockHitResult hit = visibleFace(ctx, pos);
            if (hit == null) return Outcome.UNREACHABLE;
            int tool = Hands.bestTool(body, state);
            if (tool >= 0) Hands.toMainHand(body, tool);
            Steer.face(body, hit.getLocation());
            manager.processBlockBreakingAction(pos, Action.START_DESTROY_BLOCK, hit.getDirection(), maxY, sequence++);
            if (ctx.level.getBlockState(pos).isAir()) return Outcome.DONE;
            return manager.isMining() ? Outcome.WORKING : Outcome.UNREACHABLE;
        }
        manager.continueBreaking(pos, Direction.UP);
        if (!manager.isMining()) return ctx.level.getBlockState(pos).isAir() ? Outcome.DONE : Outcome.WORKING;
        if (manager.getBlockBreakingProgress() >= 10) {
            manager.processBlockBreakingAction(pos, Action.STOP_DESTROY_BLOCK, Direction.UP, maxY, sequence++);
            if (ctx.level.getBlockState(pos).isAir()) return Outcome.DONE;
        }
        return Outcome.WORKING;
    }

    /** Stops a break in progress, as a player letting go of the button does. */
    void abort(BotPlayer body) {
        IInteractionManager manager = body.getInteractionManager();
        if (manager.isMining()) {
            manager.processBlockBreakingAction(manager.getMiningPos(), Action.ABORT_DESTROY_BLOCK, Direction.UP, body.level().getMaxBuildHeight(), sequence++);
        }
    }

    /**
     * Places the block in the main hand at {@code cell} by clicking a face of a neighbour that the eyes can see (a
     * ray from them reaches that face first) and reach. Returns true when a block was placed. The body should be
     * sneaking, so that a click on a door or a crafting table places instead of using it.
     */
    boolean place(ReflexContext ctx, BlockPos cell) {
        BotPlayer body = ctx.body;
        ItemStack stack = body.getMainHandItem();
        if (stack.isEmpty()) return false;
        Vec3 eye = body.getEyePosition();
        for (Direction toAnchor : Direction.values()) {
            BlockPos anchor = cell.relative(toAnchor);
            BlockState anchorState = ctx.level.getBlockState(anchor);
            Direction face = toAnchor.getOpposite();
            if (anchorState.isAir() || !anchorState.isFaceSturdy(ctx.level, anchor, face)) continue;
            // Aim a hair inside the anchor, at the middle of the face that looks at the cell.
            Vec3 aim = Vec3.atCenterOf(anchor).relative(face, 0.5).relative(toAnchor, 0.02);
            BlockHitResult hit = ctx.level.clip(new ClipContext(eye, aim, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, body));
            if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(anchor) || hit.getDirection() != face) continue;
            if (eye.distanceTo(hit.getLocation()) > body.blockInteractionRange()) continue;
            Steer.face(body, hit.getLocation());
            InteractionResult result = body.getInteractionManager().interactBlock(body, ctx.level, stack, InteractionHand.MAIN_HAND, hit);
            if (result.consumesAction()) {
                // The loader's placement hook would record it at the end of the tick; this is the same fact, now.
                if (!ctx.level.getBlockState(cell).isAir()) BlockSafety.of(ctx.level.getServer()).noteBotPlaced(body, ctx.level, cell);
                return true;
            }
        }
        return false;
    }

    /** A point on the block that the eyes can see and reach: the hit a client would send. Null when there is none. */
    @Nullable
    BlockHitResult visibleFace(ReflexContext ctx, BlockPos pos) {
        BotPlayer body = ctx.body;
        Vec3 eye = body.getEyePosition();
        Vec3 center = Vec3.atCenterOf(pos);
        BlockHitResult hit = look(ctx, eye, center, pos);
        if (hit != null) return hit;
        for (Direction d : Direction.values()) {
            hit = look(ctx, eye, center.relative(d, 0.45), pos);
            if (hit != null) return hit;
        }
        return null;
    }

    @Nullable
    private BlockHitResult look(ReflexContext ctx, Vec3 eye, Vec3 point, BlockPos pos) {
        BlockHitResult hit = ctx.level.clip(new ClipContext(eye, point, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, ctx.body));
        if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(pos)) return null;
        return eye.distanceTo(hit.getLocation()) <= ctx.body.blockInteractionRange() ? hit : null;
    }
}
