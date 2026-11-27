package com.legoj15.megablockbot.game.reflex;

import com.legoj15.megablockbot.game.body.BotPlayer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;

/**
 * Who is a threat to a body (design 3.2, item 2): a hostile mob whose target is the body, or any hostile within melee
 * range, and in both cases one the body could see. The sight rule is what keeps a body that is walled into its shelter
 * from fighting the zombies outside it: a player could not hit them either. A creeper that targets the body counts only
 * within the reach of its blast or while it is coming closer ({@link ApproachWatch}); one that hovers farther off is watched.
 */
final class Threats {
    private Threats() {}

    /** The threats around {@code body}, nearest first, leaving out mobs it has given up on ({@code ignored}: mob to the tick until which). */
    static List<Threat> scan(BotPlayer body, ServerLevel level, Map<UUID, Long> ignored, ApproachWatch approach, long now) {
        AABB box = body.getBoundingBox().inflate(ReflexConstants.SCAN_RADIUS, ReflexConstants.SCAN_HEIGHT, ReflexConstants.SCAN_RADIUS);
        List<Threat> found = new ArrayList<>();
        for (Mob mob : level.getEntitiesOfClass(Mob.class, box, m -> m.isAlive() && isHostile(m, body))) {
            Long until = ignored.get(mob.getUUID());
            if (until != null && until > now) continue;
            if (!body.hasLineOfSight(mob)) continue;
            boolean targets = mob.getTarget() == body;
            double distance = body.distanceTo(mob);
            // Only a creeper that is after the body and far off needs its approach watched: nearer ones are threats at once.
            boolean closing = mob instanceof Creeper && targets && approach.closing(mob.getUUID(), now, mob.position(), body.position());
            Threat threat = mob instanceof Creeper creeper ? ofCreeper(creeper, distance, targets, closing) : ordinary(mob, distance, targets);
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

    /**
     * Whether this mob hurts things from a distance (issue 29): a {@code RangedAttackMob} holding a projectile weapon,
     * or one of the kinds that shoot with nothing in their hands. Such a mob is run from the moment it is a threat
     * when the body cannot answer it, not only once it has already been hurt.
     */
    static boolean attacksFromRange(Mob mob) {
        if (ReflexConstants.RANGED_WITHOUT_A_WEAPON.contains(mob.getType())) return true;
        if (!(mob instanceof RangedAttackMob)) return false;
        ItemStack held = mob.getMainHandItem();
        return held.is(Items.BOW) || held.is(Items.CROSSBOW) || held.is(Items.TRIDENT);
    }

    /**
     * How far this mob follows what it is after: its {@code FOLLOW_RANGE} attribute, or the usual one for a hostile
     * mob when it has none. What a retreat from it must clear, and what a danger zone around it is wide as.
     */
    static double followRange(Mob mob) {
        AttributeInstance range = mob.getAttribute(Attributes.FOLLOW_RANGE);
        return range == null ? ReflexConstants.DEFAULT_FOLLOW_RANGE : range.getValue();
    }

    /**
     * How far a retreat must go to lose these mobs, and how wide a danger zone around where they stand is: the
     * largest follow range among them plus the margin.
     */
    static double retreatDistance(List<Threat> threats) {
        double widest = ReflexConstants.DEFAULT_FOLLOW_RANGE;
        for (Threat threat : threats) widest = Math.max(widest, followRange(threat.mob()));
        return widest + ReflexConstants.FOLLOW_RANGE_MARGIN;
    }

    private static Threat ordinary(Mob mob, double distance, boolean targets) {
        if (!targets && distance > ReflexConstants.MELEE_TRIGGER) return null;
        return new Threat(mob, distance, targets, false, false, false, false);
    }

    private static Threat ofCreeper(Creeper creeper, double distance, boolean targets, boolean closing) {
        boolean powered = creeper.isPowered();
        double safe = powered ? ReflexConstants.POWERED_CREEPER_SAFE_DISTANCE : ReflexConstants.CREEPER_SAFE_DISTANCE;
        boolean fusing = creeper.getSwellDir() > 0 || creeper.isIgnited();
        // A creeper that is not coming for the body and is not close is no business of the reflex.
        if (!targets && !fusing && distance > ReflexConstants.MELEE_TRIGGER) return null;
        // Beyond the blast and with no fuse lit, a creeper that is not after the body is only a mob that has not got here yet.
        if (!targets && distance >= safe && !fusing) return null;
        // One that is after the body but beyond the blast and not coming (it hovers, stuck behind a river or among trees) is
        // watched, not run from: a retreat from each sight of one took the body out of sight, the hold ended, the executor walked
        // it back, and the creeper was in sight again, nine holds in thirty seconds. Once it is within the blast's reach, or
        // closing, it is a threat from the first tick: the gap is kept or widened, never closed.
        if (targets && !fusing && distance >= safe && !closing) return null;
        boolean about = fusing || targets || distance <= ReflexConstants.CREEPER_SWELL_RANGE;
        return new Threat(creeper, distance, targets, true, powered, about, fusing);
    }
}
