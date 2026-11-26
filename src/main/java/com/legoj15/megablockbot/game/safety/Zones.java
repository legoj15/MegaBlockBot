package com.legoj15.megablockbot.game.safety;

import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * The protected-radius zones that apply to one owner's bodies (design 3.5): the overworld's world spawn, and the owner's
 * respawn point in its own dimension. Immutable, so the server thread can publish one and any thread can read it: it
 * is what lets a path search ask the radius question without touching the player list or the level.
 *
 * @param respawnDimension the dimension of the owner's respawn point; null when the owner has none
 */
record Zones(int spawnX, int spawnZ, Verdict spawnVerdict,
             @Nullable ResourceKey<Level> respawnDimension, int respawnX, int respawnY, int respawnZ, Verdict respawnVerdict) {
    static Zones of(BlockPos worldSpawn, @Nullable OwnerSpawns.Spawn respawn) {
        Verdict spawnVerdict = Verdict.deny(SafetyConstants.RULE_PROTECTED_RADIUS,
            "within " + SafetyConstants.PROTECTED_RADIUS + " blocks of the world spawn (" + worldSpawn.getX() + ", " + worldSpawn.getZ() + ")");
        if (respawn == null) return new Zones(worldSpawn.getX(), worldSpawn.getZ(), spawnVerdict, null, 0, 0, 0, Verdict.ALLOWED);
        BlockPos at = respawn.pos();
        Verdict respawnVerdict = Verdict.deny(SafetyConstants.RULE_PROTECTED_RADIUS,
            "within " + SafetyConstants.PROTECTED_RADIUS + " blocks of the owner's respawn point (" + at.getX() + ", " + at.getY() + ", " + at.getZ() + ")");
        return new Zones(worldSpawn.getX(), worldSpawn.getZ(), spawnVerdict,
            respawn.dimension(), at.getX(), at.getY(), at.getZ(), respawnVerdict);
    }

    /** Whether this snapshot already says what {@link #of} would, so a refresh that finds no change builds nothing. */
    boolean matches(BlockPos worldSpawn, @Nullable OwnerSpawns.Spawn respawn) {
        if (spawnX != worldSpawn.getX() || spawnZ != worldSpawn.getZ()) return false;
        if (respawn == null) return respawnDimension == null;
        BlockPos at = respawn.pos();
        return respawn.dimension().equals(respawnDimension) && respawnX == at.getX() && respawnY == at.getY() && respawnZ == at.getZ();
    }

    /** The verdict of the zone that covers this column of this dimension, or null when none does. Allocation-free. */
    @Nullable
    Verdict covering(ResourceKey<Level> dimension, int x, int z) {
        int radius = SafetyConstants.PROTECTED_RADIUS;
        if (dimension == Level.OVERWORLD && Math.abs(x - spawnX) <= radius && Math.abs(z - spawnZ) <= radius) return spawnVerdict;
        if (respawnDimension == dimension && Math.abs(x - respawnX) <= radius && Math.abs(z - respawnZ) <= radius) return respawnVerdict;
        return null;
    }
}
