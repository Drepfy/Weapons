package io.github.drepfy.vigil.model;

/**
 * Minimal re-implementation of vanilla player movement along one horizontal axis
 * (LivingEntity#travel), used to generate legitimate movement for the tests.
 */
final class VanillaMovementSim {

    static final double NORMAL = 0.6;
    static final double ICE = 0.98;
    static final double BLUE_ICE = 0.989;
    static final double SLIME = 0.8;

    private final double friction;
    private final double movementSpeed;
    private final boolean sprinting;
    private final boolean jumping;
    private final double ceilingGap;
    private final double jumpVelocity;

    private double velocity;
    private double y;
    private double vy;
    private boolean onGround = true;

    /**
     * @param friction      block slipperiness under the player
     * @param speedAmplifier Speed effect amplifier, -1 for none
     * @param sprinting     sprinting
     * @param jumping       jumps again as soon as it lands (tapping space)
     * @param ceilingGap    space between head and ceiling (Double.POSITIVE_INFINITY for none)
     */
    VanillaMovementSim(double friction, int speedAmplifier, boolean sprinting, boolean jumping, double ceilingGap) {
        this.friction = friction;
        double speed = 0.1 * (speedAmplifier >= 0 ? 1.0 + 0.2 * (speedAmplifier + 1) : 1.0);
        this.movementSpeed = sprinting ? speed * 1.3 : speed;
        this.sprinting = sprinting;
        this.jumping = jumping;
        this.ceilingGap = ceilingGap;
        this.jumpVelocity = 0.42;
    }

    /** Advances one tick and returns the horizontal distance moved during it. */
    double tick() {
        if (onGround && jumping) {
            vy = jumpVelocity;
            if (sprinting) {
                velocity += 0.2;
            }
        }
        double acceleration = onGround
                ? movementSpeed * (0.21600002 / (friction * friction * friction))
                : (sprinting ? 0.026 : 0.02);
        velocity += acceleration;
        double moved = velocity;

        double dy = vy;
        if (dy > 0 && y + dy > ceilingGap) {
            dy = Math.max(0.0, ceilingGap - y);
            vy = 0.0;
        }
        y += dy;
        if (y <= 0.0) {
            y = 0.0;
            vy = 0.0;
            onGround = true;
        } else {
            onGround = false;
        }
        velocity *= onGround ? friction * 0.91 : 0.91;
        vy = (vy - 0.08) * 0.98;
        return moved;
    }

    double y() {
        return y;
    }
}
