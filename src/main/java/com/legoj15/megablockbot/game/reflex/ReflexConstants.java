package com.legoj15.megablockbot.game.reflex;

import java.util.List;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * Every threshold of the reflex layer (design 3.2), in one place so a later config pass can find them. Nothing here is
 * read from a config file yet. The numbers start from AIBot's (docs/handoff/aibot-behaviours.md, sections 5 and 7);
 * where one differs the comment says how and docs/reflexes.md says why. Health is in HP points (20 is ten hearts),
 * food in food points (20 is full), distances in blocks, times in ticks.
 */
public final class ReflexConstants {
    private ReflexConstants() {}

    /** What the night reflex does at dusk (design 3.2, item 4). */
    public enum NightPolicy {
        /** Keep working and fight what comes. */
        IGNORE,
        /** Build a closed one-by-two shelter from carried blocks and wait in it until morning. */
        SHELTER,
        /** Sleep in a bed this body owns nearby; with none, as {@link #SHELTER}. */
        BED
    }

    /** The default for every body; a body's own layer can be told another one ({@code ReflexLayer.setNightPolicy}). */
    public static final NightPolicy NIGHT_POLICY = NightPolicy.SHELTER;

    // ---------------------------------------------------------------------------------------------------------
    // Mobs (priority 2)
    // ---------------------------------------------------------------------------------------------------------

    /**
     * Where hostile mobs are looked for. AIBot used 10 (20 for ranged mobs, 16 for creepers) and did not ask whom the
     * mob was after; design 3.2 asks for mobs that target the body, so the whole of this range counts, but only
     * for a mob that has a line of sight to the body.
     */
    public static final double SCAN_RADIUS = 16.0;
    public static final double SCAN_HEIGHT = 8.0;

    /** A hostile this close, in sight, is a threat whoever it is after (a zombie that has not picked a target yet). */
    public static final double MELEE_TRIGGER = 3.5;

    /** A swing is made once the attack cooldown has recovered this far (AIBot: 0.95, same). */
    public static final float ATTACK_READY = 0.95F;

    /** A sword or axe with this much durability or less left is not a weapon any more (AIBot: 2, same). */
    public static final int MIN_WEAPON_DURABILITY = 2;

    /** Walk, do not sprint, when this close to the target: a sprint hit knocks the mob out of reach. */
    public static final double SPRINT_APPROACH_DISTANCE = 5.0;

    /** Raise a shield, between swings, when a threat that targets the body is this close (AIBot: 4.0, same). */
    public static final double SHIELD_RANGE = 4.0;

    /** The threat set must be empty this long, and the body unhurt by a mob this long, before the hold ends (design 3.4: "N ticks"). */
    public static final int THREAT_QUIET_TICKS = 20;
    public static final int HURT_QUIET_TICKS = 40;

    /** A target that has not got closer for this long is given up on, and ignored for {@link #IGNORE_MOB_TICKS}. */
    public static final int CHASE_GIVE_UP_TICKS = 100;
    public static final int IGNORE_MOB_TICKS = 600;

    // ---------------------------------------------------------------------------------------------------------
    // Retreat
    // ---------------------------------------------------------------------------------------------------------

    /** Retreat at this health or below (AIBot: 10, same). */
    public static final float RETREAT_HEALTH = 10.0F;

    /** Retreat from more attackers than this (AIBot: 2, same). */
    public static final int MAX_ATTACKERS = 2;

    /**
     * With no sword or axe the body fights with its fists only against this many attackers and from this health.
     * AIBot never fought without a weapon; the brief asks for fists or retreat.
     */
    public static final int FIST_MAX_ATTACKERS = 1;
    public static final float FIST_MIN_HEALTH = 14.0F;

    /** A hostile with this much health or more (a warden, a wither, a ravager) is never fought: the body retreats from it. */
    public static final float OUTCLASSED_MAX_HEALTH = 60.0F;

    /** How far away a retreat spot is looked for, how close counts as arrived, and how long a spot is kept. */
    public static final int RETREAT_DISTANCE = 14;
    public static final int RETREAT_ARRIVE_RADIUS = 2;
    public static final int RETREAT_REPICK_TICKS = 60;
    /** A retreat that has arrived this many times and is still followed, or finds no way, stands and fights instead (AIBot: "no valid retreat route"). */
    public static final int RETREAT_MAX_ARRIVALS = 8;
    /** Spots the navigator failed to reach, tried one after another, before the body gives up on running. */
    public static final int RETREAT_MAX_FAILED_SPOTS = 8;
    public static final int CORNERED_TICKS = 80;

    /**
     * A creeper that targets the body is never approached and counts as about to explode from this close. It swells
     * from 3 blocks and keeps swelling to 7; the fuse is 30 ticks. AIBot never meleed one either (escape distance 12).
     */
    public static final double CREEPER_FLEE_RANGE = 9.0;
    /**
     * A creeper whose fuse is burning, this close, with a shield to hand: the body stops running, faces it and raises the
     * shield instead. An explosion is blocked like any hit from the front, and at this range running no longer gets the
     * body out of the blast in time.
     */
    public static final double CREEPER_BRACE_RANGE = 4.5;
    /** A creeper this close starts swelling next tick whatever it targets. */
    public static final double CREEPER_SWELL_RANGE = 3.5;
    /** Past this distance a creeper's explosion cannot reach and its fuse is cut (a charged one blows twice as wide). */
    public static final double CREEPER_SAFE_DISTANCE = 9.0;
    public static final double POWERED_CREEPER_SAFE_DISTANCE = 14.0;

    // ---------------------------------------------------------------------------------------------------------
    // Health and hunger (priority 3)
    // ---------------------------------------------------------------------------------------------------------

    /** Eat at this food level or below (AIBot: 14, same). */
    public static final int EAT_THRESHOLD = 14;
    /** Below this, anything edible goes, a harmful food included (AIBot: 6, same). */
    public static final int CRITICAL_FOOD = 6;
    /** Keep eating until food is this high (natural regeneration needs 18), so the hold is one stretch and not a stop per bite. AIBot ate once per trigger. */
    public static final int EAT_UNTIL = 18;
    /** Eat to heal at this health or below while the food bar has room (AIBot: 10, same). */
    public static final float LOW_HEALTH = 10.0F;
    /** Stop healing by eating at this health. */
    public static final float HEAL_UNTIL = 14.0F;
    /** A food that could not be started is not tried again for this long. */
    public static final int EAT_RETRY_TICKS = 40;

    // ---------------------------------------------------------------------------------------------------------
    // Immediate hazards (priority 1)
    // ---------------------------------------------------------------------------------------------------------

    /**
     * Surface when the air is below this (AIBot: 100 for the guard, 50 for the top threat). The air supply is 300; it
     * drops one a tick, and the damage starts at zero.
     */
    public static final int AIR_LOW = 100;
    /** Hold until the head is out of the water and the air is back to this. */
    public static final int AIR_RECOVERED = 200;
    /** Water this close, horizontally (and 3 blocks up or down), is a place to put a fire out. */
    public static final int WATER_SEARCH_RADIUS = 8;
    /** The hazard must be gone this long before the hold ends. */
    public static final int HAZARD_CLEAR_TICKS = 10;
    /** How far a body that stands in lava, fire or a wall looks for the nearest open, safe cell. */
    public static final int SAFE_SPOT_RADIUS = 5;
    /** A hazard that has held the body this long without ending is given up on (a body in a cage of fire cannot be helped by waiting). */
    public static final int HAZARD_GIVE_UP_TICKS = 400;

    // ---------------------------------------------------------------------------------------------------------
    // Night and exposure (priority 4)
    // ---------------------------------------------------------------------------------------------------------

    /**
     * The part of the 24000-tick day the policy covers: from dusk, a few hundred ticks before the first monsters spawn
     * (the sun sets at 12000, sleeping is possible from 12542), to just before sunrise (zombies stop burning at
     * about 23460).
     */
    public static final int NIGHT_BEGIN = 12000;
    public static final int NIGHT_END = 23000;
    /** Only a body under the open sky is exposed: monsters spawn underground at any hour, so a shelter there would not help. */
    public static final int SKY_EXPOSURE_MIN_LIGHT = 8;
    /** A night with no shelter built is tried again after this long, from wherever the body then stands. */
    public static final int SHELTER_RETRY_TICKS = 200;
    /**
     * Where the body looks for a spot that will do when its own will not (a slope, a flower bed, a drop beside it), and how
     * long it may take to walk there.
     */
    public static final int SPOT_SEARCH_RADIUS = 6;
    public static final int SPOT_WALK_TICKS = 300;
    /** A shelter that is not closed after this long is given up on. */
    public static final int BUILD_TIMEOUT_TICKS = 400;
    /** Taking the shelter down, and then picking its blocks up, are given this long each. */
    public static final int TAKEDOWN_TIMEOUT_TICKS = 600;
    public static final int COLLECT_TICKS = 100;
    /** Ticks between two placements (a client's right-click repeat is 4; this is quicker because each click is its own). */
    public static final int PLACE_DELAY_TICKS = 2;
    /** How far from the middle of its cell the body may stand when it starts building: the walls must not touch its box. */
    public static final double CENTER_TOLERANCE = 0.12;
    /** Where a bed is looked for, and how long a go at sleeping may take. */
    public static final int BED_SEARCH_RADIUS = 12;
    public static final int BED_ATTEMPT_TICKS = 300;

    /**
     * What a shelter is built from, best first: blocks that drop themselves when broken by hand or with the tool at
     * hand, and that do not fall. A block outside this list is never used, so a body never walls itself in with its
     * ore or its tools' blocks.
     */
    public static final List<Item> SHELTER_BLOCKS = List.of(
        Items.DIRT, Items.COARSE_DIRT, Items.COBBLESTONE, Items.COBBLED_DEEPSLATE, Items.NETHERRACK,
        Items.ANDESITE, Items.DIORITE, Items.GRANITE, Items.TUFF, Items.BLACKSTONE, Items.BASALT);
}
