package io.github.drepfy.vigil.model;

/**
 * Vanilla movement constants and derived limits. Values are taken from the
 * vanilla client physics (blocks per tick unless stated otherwise).
 */
public final class Physics {

    /** Gravity applied to a player's vertical velocity each tick. */
    public static final double GRAVITY = 0.08;
    /** Vertical drag multiplier applied each tick in air. */
    public static final double VERTICAL_DRAG = 0.98;
    /** Default jump_strength attribute value. */
    public static final double DEFAULT_JUMP_STRENGTH = 0.42;
    /** Extra jump velocity per Jump Boost level. */
    public static final double JUMP_BOOST_PER_LEVEL = 0.1;
    /** Default step_height attribute value. */
    public static final double DEFAULT_STEP_HEIGHT = 0.6;
    /** Default movement_speed attribute value for players (walking). */
    public static final double DEFAULT_MOVEMENT_SPEED = 0.1;
    /** Default Bukkit walk speed, maps to DEFAULT_MOVEMENT_SPEED. */
    public static final double DEFAULT_WALK_SPEED = 0.2;
    /** Speed potion bonus per level (multiplier on movement speed). */
    public static final double SPEED_POTION_PER_LEVEL = 0.2;
    /**
     * Average horizontal speed of sprint-jumping on open ground (about 7.1 m/s).
     * Head-hitters, ice and potions are handled with multipliers.
     */
    public static final double SPRINT_JUMP_SPEED = 0.36;
    /**
     * PlayerMoveEvent is only fired after the player moved more than this
     * distance (1/16 block), so one event can contain several tiny ticks.
     */
    public static final double MOVE_EVENT_THRESHOLD = 0.0625;
    /** Default survival entity interaction range. */
    public static final double DEFAULT_ENTITY_RANGE = 3.0;
    /** Default survival block interaction range. */
    public static final double DEFAULT_BLOCK_RANGE = 4.5;
    public static final double STANDING_EYE_HEIGHT = 1.62;
    public static final double SNEAKING_EYE_HEIGHT = 1.27;
    public static final double LOW_POSE_EYE_HEIGHT = 0.4;

    private static final int FREE_FALL_TABLE_TICKS = 400;
    private static final double[] FREE_FALL = new double[FREE_FALL_TABLE_TICKS + 1];

    static {
        double velocity = 0.0;
        double distance = 0.0;
        FREE_FALL[0] = 0.0;
        for (int tick = 1; tick <= FREE_FALL_TABLE_TICKS; tick++) {
            velocity = (velocity - GRAVITY) * VERTICAL_DRAG;
            distance -= velocity;
            FREE_FALL[tick] = distance;
        }
    }

    private Physics() {
    }

    /** Distance (blocks) a player falls from rest in the given number of ticks. */
    public static double freeFallDistance(int ticks) {
        if (ticks <= 0) {
            return 0.0;
        }
        return FREE_FALL[Math.min(ticks, FREE_FALL_TABLE_TICKS)];
    }

    /** Initial vertical velocity of a jump. {@code jumpBoostAmplifier} is -1 when absent. */
    public static double jumpVelocity(double jumpStrength, int jumpBoostAmplifier) {
        double velocity = jumpStrength;
        if (jumpBoostAmplifier >= 0) {
            velocity += JUMP_BOOST_PER_LEVEL * (jumpBoostAmplifier + 1);
        }
        return Math.max(0.0, velocity);
    }

    /** Maximum height gained by a jump with the given initial vertical velocity. */
    public static double jumpApex(double initialVelocity) {
        double velocity = initialVelocity;
        double height = 0.0;
        for (int tick = 0; tick < 1000 && velocity > 0.0; tick++) {
            height += velocity;
            velocity = (velocity - GRAVITY) * VERTICAL_DRAG;
        }
        return height;
    }

    /** Largest legitimate upward movement within one tick. */
    public static double maxSingleTickAscent(double jumpVelocity, double stepHeight) {
        return Math.max(jumpVelocity, stepHeight);
    }
}
