package com.legoj15.megablockbot.game.body;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;

/**
 * What a real player's keyboard and mouse would say this tick, written by the brain and applied by the body just
 * before its entity tick. This is the only way a body moves: nothing here teleports it or sets its velocity.
 *
 * <p>The values persist until someone changes them; whoever stops driving the body must {@link #reset()}.
 */
public final class BodyInput {
    /** -1 (back) to 1 (forward). */
    public float forward;
    /** -1 (right) to 1 (left), the sign vanilla's {@code xxa} uses. */
    public float strafe;
    public boolean jump;
    public boolean sneak;
    public boolean sprint;

    private enum Look { NONE, ANGLES, POINT }

    private Look look = Look.NONE;
    private float yaw;
    private float pitch;
    private Vec3 point = Vec3.ZERO;

    // /aiplayer input: the held values replace whatever the brain wrote, for this many ticks, then everything clears.
    private int holdLeft;
    private float heldForward;
    private float heldStrafe;
    private boolean heldJump;
    private boolean holding;

    /** Face a fixed direction (degrees, vanilla's yaw and pitch conventions) until changed. */
    public void lookAngles(float yaw, float pitch) {
        this.look = Look.ANGLES;
        this.yaw = yaw;
        this.pitch = pitch;
    }

    /** Face a point in the world, re-evaluated from the body's eyes every tick. */
    public void lookAt(Vec3 target) {
        this.look = Look.POINT;
        this.point = target;
    }

    public void clearLook() {
        this.look = Look.NONE;
    }

    /** Everything off: standing still, no jump, no sneak, no sprint, no look target. Any hold is dropped too. */
    public void reset() {
        forward = 0.0F;
        strafe = 0.0F;
        jump = false;
        sneak = false;
        sprint = false;
        look = Look.NONE;
        holdLeft = 0;
        holding = false;
    }

    /** Holds these movement values for {@code ticks} body ticks, overriding the brain, then resets the input. */
    public void hold(float forward, float strafe, boolean jump, int ticks) {
        this.heldForward = forward;
        this.heldStrafe = strafe;
        this.heldJump = jump;
        this.holdLeft = Math.max(0, ticks);
        this.holding = true;
    }

    /** Advances a {@link #hold}: copies its values in while it lasts and clears the input once it has run out. */
    void tickHold() {
        if (!holding) return;
        if (holdLeft > 0) {
            forward = heldForward;
            strafe = heldStrafe;
            jump = heldJump;
            holdLeft--;
        } else {
            reset();
        }
    }

    /** Writes the input into the body the way vanilla's input handling would, so the entity tick that follows reads it. */
    void applyTo(BotPlayer body) {
        float f = clean(forward);
        float s = clean(strafe);
        // The client scales its impulses by the sneaking-speed attribute while crouched; Swift Sneak raises it.
        float slow = sneak ? (float) body.getAttributeValue(Attributes.SNEAKING_SPEED) : 1.0F;
        body.zza = f * slow;
        body.xxa = s * slow;
        body.setJumping(jump);
        body.setShiftKeyDown(sneak);
        // A client stops sprinting when it can no longer: not moving forward, crouching, or too hungry.
        boolean mayRun = body.getFoodData().getFoodLevel() > 6 || body.getAbilities().mayfly;
        body.setSprinting(sprint && f > 0.0F && !sneak && mayRun);

        switch (look) {
            case ANGLES -> face(body, yaw, pitch);
            case POINT -> {
                Vec3 eyes = body.getEyePosition();
                double dx = point.x - eyes.x;
                double dy = point.y - eyes.y;
                double dz = point.z - eyes.z;
                double flat = Math.sqrt(dx * dx + dz * dz);
                face(body, (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0F, (float) -(Mth.atan2(dy, flat) * Mth.RAD_TO_DEG));
            }
            case NONE -> { }
        }
    }

    private static void face(BotPlayer body, float yaw, float pitch) {
        float y = Mth.wrapDegrees(yaw);
        float p = Mth.clamp(Mth.wrapDegrees(pitch), -90.0F, 90.0F);
        body.setYRot(y);
        body.setXRot(p);
        // Observers read the head, not the body: without this the model keeps looking the old way.
        body.setYHeadRot(y);
    }

    private static float clean(float value) {
        return Float.isNaN(value) ? 0.0F : Mth.clamp(value, -1.0F, 1.0F);
    }
}
