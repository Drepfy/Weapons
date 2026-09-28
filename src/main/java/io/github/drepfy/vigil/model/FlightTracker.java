package io.github.drepfy.vigil.model;

/**
 * Physics-based airborne state machine used by the flight check.
 *
 * <p>Time only advances while the client is demonstrably ticking ({@code activeMs}),
 * so a frozen or lagging client that stops sending packets can never accumulate
 * "hover" time. Two rules are evaluated while the player is airborne without any
 * support or exemption:
 * <ol>
 *   <li><b>Ascend</b>: without an external impulse, a player can never rise more than
 *   the apex of their best possible jump above the point where they left support.</li>
 *   <li><b>Hover</b>: once the player stops rising, gravity must pull them down. Over
 *   every window of client time they must descend at least {@code minDescent}
 *   (vanilla free fall covers ~14 blocks in the first second).</li>
 * </ol>
 * External impulses (knockback, bounces, leaving water/elytra/vehicles...) suspend
 * the ascend rule until the player starts to descend or a maximum rise time passes.
 */
public final class FlightTracker {

    public enum Verdict {
        NONE,
        HOVER,
        ASCEND
    }

    /** A vertical jump larger than this between samples is treated as a teleport/correction. */
    private static final double DISCONTINUITY = 4.0;
    /** A rise smaller than this does not restart the hover window. */
    private static final double RISE_EPSILON = 0.01;
    /** Descent below the peak that marks the end of an impulse. */
    private static final double IMPULSE_END_DESCENT = 0.1;

    /** Tunable parameters, taken from the configuration. */
    public record Params(double hoverWindowMs, double minDescent, double ascendTolerance, double impulseMaxRiseMs) {
    }

    private boolean initialised;
    private boolean airborne;
    private double lastY;
    private double takeoffY;
    private double peakY;
    private double windowStartY;
    private double windowActiveMs;
    private double airActiveMs;
    private boolean impulse;
    private double impulseRiseMs;
    private double lastMeasured;
    private double lastAllowed;

    /** Resets to a neutral state at the given height. */
    public void reset(double y, boolean withImpulse) {
        initialised = true;
        airborne = false;
        lastY = y;
        takeoffY = y;
        peakY = y;
        windowStartY = y;
        windowActiveMs = 0.0;
        airActiveMs = 0.0;
        impulse = withImpulse;
        impulseRiseMs = 0.0;
    }

    /** Records an external impulse (knockback, velocity, bounce...). */
    public void impulse(double y) {
        if (!initialised) {
            reset(y, true);
            return;
        }
        impulse = true;
        impulseRiseMs = 0.0;
        takeoffY = y;
        peakY = y;
        windowStartY = y;
        windowActiveMs = 0.0;
        lastY = y;
    }

    /**
     * Feeds one sample.
     *
     * @param y            current feet height
     * @param supported    whether any support (block, liquid, climbable...) is near
     * @param bouncy       whether the support is bouncy (slime, bed)
     * @param activeMs     client time that passed since the previous sample (0 if none)
     * @param maxJumpApex  apex of the best jump currently possible for this player
     */
    public Verdict sample(double y, boolean supported, boolean bouncy, double activeMs,
                          double maxJumpApex, Params params) {
        if (!initialised || Math.abs(y - lastY) > DISCONTINUITY) {
            reset(y, true);
            return Verdict.NONE;
        }
        lastY = y;

        if (supported) {
            airborne = false;
            takeoffY = y;
            peakY = y;
            windowStartY = y;
            windowActiveMs = 0.0;
            airActiveMs = 0.0;
            impulseRiseMs = 0.0;
            impulse = bouncy;
            return Verdict.NONE;
        }
        if (activeMs <= 0.0) {
            return Verdict.NONE;
        }

        airborne = true;
        airActiveMs += activeMs;

        if (y > peakY) {
            if (impulse) {
                impulseRiseMs += activeMs;
            }
            peakY = y;
        }
        if (y > windowStartY + RISE_EPSILON) {
            windowStartY = y;
            windowActiveMs = 0.0;
        } else {
            windowActiveMs += activeMs;
        }

        if (impulse && y < peakY - IMPULSE_END_DESCENT) {
            impulse = false;
            takeoffY = peakY;
        }
        if (impulse && impulseRiseMs > params.impulseMaxRiseMs()) {
            impulse = false;
            takeoffY = y;
        }

        if (!impulse) {
            double rise = y - takeoffY;
            double allowed = maxJumpApex + params.ascendTolerance();
            if (rise > allowed) {
                lastMeasured = rise;
                lastAllowed = allowed;
                takeoffY = y;
                return Verdict.ASCEND;
            }
        }

        if (windowActiveMs >= params.hoverWindowMs()) {
            double descent = windowStartY - y;
            windowStartY = y;
            windowActiveMs = 0.0;
            if (descent < params.minDescent()) {
                lastMeasured = descent;
                lastAllowed = params.minDescent();
                return Verdict.HOVER;
            }
        }
        return Verdict.NONE;
    }

    public boolean isAirborne() {
        return airborne;
    }

    public boolean hasImpulse() {
        return impulse;
    }

    public double airActiveMs() {
        return airActiveMs;
    }

    /** Measured value of the last verdict (rise or descent, in blocks). */
    public double lastMeasured() {
        return lastMeasured;
    }

    /** Allowed value of the last verdict (in blocks). */
    public double lastAllowed() {
        return lastAllowed;
    }
}
