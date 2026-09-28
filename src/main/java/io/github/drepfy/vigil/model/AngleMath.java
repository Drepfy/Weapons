package io.github.drepfy.vigil.model;

import java.util.List;

/**
 * Rotation helpers using Minecraft conventions (yaw 0 = +Z, yaw 90 = -X,
 * positive pitch looks down).
 */
public final class AngleMath {

    /** Points sampled per box axis when searching the closest point to a ray. */
    private static final int BOX_SAMPLES = 3;
    /** Interpolated rotations between the previous and next rotation. */
    private static final int ARC_STEPS = 8;

    private AngleMath() {
    }

    public static double wrapDegrees(double degrees) {
        double wrapped = degrees % 360.0;
        if (wrapped >= 180.0) {
            wrapped -= 360.0;
        }
        if (wrapped < -180.0) {
            wrapped += 360.0;
        }
        return wrapped;
    }

    /** Unit look vector for a yaw/pitch pair: {x, y, z}. */
    public static double[] direction(double yaw, double pitch) {
        double yawRad = Math.toRadians(yaw);
        double pitchRad = Math.toRadians(pitch);
        double xz = Math.cos(pitchRad);
        return new double[] {-xz * Math.sin(yawRad), -Math.sin(pitchRad), xz * Math.cos(yawRad)};
    }

    /** Angle in degrees between a unit direction and the vector (vx, vy, vz). */
    public static double angleTo(double[] unitDirection, double vx, double vy, double vz) {
        double length = Math.sqrt(vx * vx + vy * vy + vz * vz);
        if (length < 1.0E-9) {
            return 0.0;
        }
        double dot = (unitDirection[0] * vx + unitDirection[1] * vy + unitDirection[2] * vz) / length;
        return Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, dot))));
    }

    /** Smallest angle between a look direction and any point of a box (0 if the ray hits it). */
    public static double angleToBox(double eyeX, double eyeY, double eyeZ, double[] unitDirection, Box box) {
        if (box.intersectsRay(eyeX, eyeY, eyeZ, unitDirection[0], unitDirection[1], unitDirection[2])) {
            return 0.0;
        }
        double best = 180.0;
        for (int ix = 0; ix < BOX_SAMPLES; ix++) {
            double px = box.minX() + (box.maxX() - box.minX()) * ix / (BOX_SAMPLES - 1);
            for (int iy = 0; iy < BOX_SAMPLES; iy++) {
                double py = box.minY() + (box.maxY() - box.minY()) * iy / (BOX_SAMPLES - 1);
                for (int iz = 0; iz < BOX_SAMPLES; iz++) {
                    double pz = box.minZ() + (box.maxZ() - box.minZ()) * iz / (BOX_SAMPLES - 1);
                    best = Math.min(best, angleTo(unitDirection, px - eyeX, py - eyeY, pz - eyeZ));
                }
            }
        }
        return best;
    }

    /**
     * Smallest angle between any rotation on the arc from (yaw1, pitch1) to
     * (yaw2, pitch2) and any of the candidate boxes. The arc follows the shortest
     * yaw path, which covers a legitimate flick through the target between the
     * two rotations the server received.
     */
    public static double minAngleOverArc(double eyeX, double eyeY, double eyeZ,
                                         double yaw1, double pitch1, double yaw2, double pitch2,
                                         List<Box> boxes) {
        double yawDelta = wrapDegrees(yaw2 - yaw1);
        double pitchDelta = pitch2 - pitch1;
        double best = 180.0;
        for (int step = 0; step <= ARC_STEPS; step++) {
            double t = step / (double) ARC_STEPS;
            double[] dir = direction(yaw1 + yawDelta * t, pitch1 + pitchDelta * t);
            for (Box box : boxes) {
                best = Math.min(best, angleToBox(eyeX, eyeY, eyeZ, dir, box));
                if (best <= 0.0) {
                    return 0.0;
                }
            }
        }
        return best;
    }
}
