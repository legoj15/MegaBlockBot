package com.legoj15.megablockbot.game.reflex;

import com.legoj15.megablockbot.game.body.BotPlayer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.AABB;

/**
 * Who is a threat to a body (design 3.2, item 2): a hostile mob whose target is the body, or any hostile within melee
 * range, and in both cases one the body could see. The sight rule is what keeps a body that is walled into its shelter
 * from fighting the zombies outside it: a player could not hit them either.
 */
final class Threats {
    private Threats() {}

    /** The threats around {@code body}, nearest first, leaving out mobs it has given up on ({@code ignored}: mob to the tick until which). */
    static List<Threat> scan(BotPlayer body, ServerLevel level, Map<UUID, Long> ignored, long now) {
        AABB box = body.getBoundingBox().inflate(ReflexConstants.SCAN_RADIUS, ReflexConstants.SCAN_HEIGHT, ReflexConstants.SCAN_RADIUS);
        List<Threat> found = new ArrayList<>();
        for (Mob mob : level.getEntitiesOfClass(Mob.class, box, m -> m.isAlive() && isHostile(m, body))) {
            Long until = ignored.get(mob.getUUID());
            if (until != null && until > now) continue;
            if (!body.hasLineOfSight(mob)) continue;
            boolean targets = mob.getTarget() == body;
            double distance = body.distanceTo(mob);
            Threat threat = mob instanceof Creeper creeper ? ofCreeper(creeper, distance, targets) : ordinary(mob, distance, targets);
            if (threat != null) found.add(threat);
        }
        found.sort(Comparator.comparingDouble(Threat::distance));
        return found;
    }

    /** Hostile, and not neutral unless it has been angered at this body (an enderman is hostile only once it targets one). */
    static boolean isHostile(Mob mob, BotPlayer body) {
        if (!(mob instanceof Enemy)) return false;
        if (mob instanceof NeutralMob && mob.getTarget() != body) return false;
        return !mob.isAlliedTo(body);
    }

    private static Threat ordinary(Mob mob, double distance, boolean targets) {
        if (!targets && distance > ReflexConstants.MELEE_TRIGGER) return null;
        return new Threat(mob, distance, targets, false, false, false, false);
    }

    private static Threat ofCreeper(Creeper creeper, double distance, boolean targets) {
        boolean powered = creeper.isPowered();
        double safe = powered ? ReflexConstants.POWERED_CREEPER_SAFE_DISTANCE : ReflexConstants.CREEPER_SAFE_DISTANCE;
        boolean fusing = creeper.getSwellDir() > 0 || creeper.isIgnited();
        // A creeper that is not coming for the body and is not close is no business of the reflex.
        if (!targets && !fusing && distance > ReflexConstants.MELEE_TRIGGER) return null;
        // Beyond the blast and with no fuse lit it is only a mob that has not got here yet.
        if (distance >= safe && !fusing) return null;
        boolean about = fusing || distance <= ReflexConstants.CREEPER_SWELL_RANGE || (targets && distance < safe);
        return new Threat(creeper, distance, targets, true, powered, about, fusing);
    }
}
