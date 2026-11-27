package com.legoj15.megablockbot.game.reflex;

import com.legoj15.megablockbot.game.body.BodyInput;
import com.legoj15.megablockbot.game.body.BotPlayer;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.world.phys.Vec3;

/**
 * The few plain moves a reflex makes by itself, with the keys a player would press: walk at something, stand still,
 * turn to look at a point. Anything that needs a route goes through the navigator instead.
 */
final class Steer {
    private Steer() {}

    /** Turns the body to look at {@code point} now (so an attack or a click this tick uses it) and keeps it there until told otherwise. */
    static void face(BotPlayer body, Vec3 point) {
        body.input().lookAt(point);
        body.lookAt(EntityAnchorArgument.Anchor.EYES, point);
    }

    /** No movement keys; the look target stays. */
    static void stand(BotPlayer body) {
        BodyInput input = body.input();
        input.forward = 0.0F;
        input.strafe = 0.0F;
        input.jump = false;
        input.sprint = false;
    }

    /**
     * Walks straight at {@code target} while looking at it, jumping when something is in the way or the body is in a
     * fluid (holding jump is how a player swims up and out of lava).
     */
    static void walkToward(BotPlayer body, Vec3 target, boolean sprint) {
        BodyInput input = body.input();
        face(body, target);
        input.forward = 1.0F;
        input.strafe = 0.0F;
        input.sprint = sprint;
        input.jump = body.isInWater() || body.isInLava() || (body.horizontalCollision && body.onGround());
    }
}
