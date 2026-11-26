package com.legoj15.megablockbot.game.safety;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * What a claims-mod adapter implements and registers with {@link BlockSafety#registerClaimProvider}. None ships here.
 *
 * <p>Called from whatever thread asks {@link BlockSafety#mayBreak}, which includes the navigation engine's search
 * threads while it costs a path. An implementation must be thread-safe and must not touch the level off the server
 * thread: answer from a snapshot it keeps. It must not throw; if it does, the position counts as claimed.
 */
public interface ClaimProvider {
    /**
     * True when {@code pos} is inside a claim that {@code actingFor} may not break blocks in. {@code actingFor} is the
     * body's owner, or the body's own id when it has none.
     */
    boolean isClaimed(ServerLevel level, BlockPos pos, UUID actingFor);
}
