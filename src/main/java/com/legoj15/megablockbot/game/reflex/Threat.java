package com.legoj15.megablockbot.game.reflex;

import net.minecraft.world.entity.Mob;

/**
 * A hostile mob the body has to deal with now.
 *
 * @param targetsBody the mob's current target is the body
 * @param aboutToBlow a creeper that is swelling, lit, or close enough to start, or coming for the body; always false for anything else
 * @param fusing      a creeper whose fuse is burning now (swelling or lit): it goes off within 30 ticks unless the body gets away
 */
record Threat(Mob mob, double distance, boolean targetsBody, boolean creeper, boolean powered, boolean aboutToBlow, boolean fusing) {}
