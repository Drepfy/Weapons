package io.github.drepfy.vigil.model;

/**
 * Immutable axis-aligned bounding box, independent of the Bukkit API so the
 * geometry can be unit tested.
 */
public record Box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {

    public Box {
        if (minX > maxX || minY > maxY || minZ > maxZ) {
            throw new IllegalArgumentException("min > max");
        }
    }

    /** Box of an entity standing at (x, y, z) with the given width and height. */
    public static Box ofEntity(double x, double y, double z, double width, double height) {
        double half = width / 2.0;
        return new Box(x - half, y, z - half, x + half, y + height, z + half);
    }

    public static Box ofBlock(int x, int y, int z) {
        return new Box(x, y, z, x + 1.0, y + 1.0, z + 1.0);
    }

    public Box expand(double amount) {
        return new Box(minX - amount, minY - amount, minZ - amount, maxX + amount, maxY + amount, maxZ + amount);
    }

    public double centerX() {
        return (minX + maxX) / 2.0;
    }

    public double centerY() {
        return (minY + maxY) / 2.0;
    }

    public double centerZ() {
        return (minZ + maxZ) / 2.0;
    }

    /** Euclidean distance from a point to the closest point of this box (0 if inside). */
    public double distanceTo(double x, double y, double z) {
        double dx = Math.max(Math.max(minX - x, 0.0), x - maxX);
        double dy = Math.max(Math.max(minY - y, 0.0), y - maxY);
        double dz = Math.max(Math.max(minZ - z, 0.0), z - maxZ);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * Whether the ray starting at (ox, oy, oz) with (not necessarily normalised)
     * direction (dx, dy, dz) intersects this box at a non-negative distance.
     */
    public boolean intersectsRay(double ox, double oy, double oz, double dx, double dy, double dz) {
        double tMin = 0.0;
        double tMax = Double.POSITIVE_INFINITY;
        double[] origin = {ox, oy, oz};
        double[] dir = {dx, dy, dz};
        double[] min = {minX, minY, minZ};
        double[] max = {maxX, maxY, maxZ};
        for (int axis = 0; axis < 3; axis++) {
            if (Math.abs(dir[axis]) < 1.0E-9) {
                if (origin[axis] < min[axis] || origin[axis] > max[axis]) {
                    return false;
                }
            } else {
                double inv = 1.0 / dir[axis];
                double t1 = (min[axis] - origin[axis]) * inv;
                double t2 = (max[axis] - origin[axis]) * inv;
                if (t1 > t2) {
                    double swap = t1;
                    t1 = t2;
                    t2 = swap;
                }
                tMin = Math.max(tMin, t1);
                tMax = Math.min(tMax, t2);
                if (tMin > tMax) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Linear interpolation between two boxes (used to approximate the positions an
     * entity passed through between two recorded samples).
     */
    public static Box lerp(Box a, Box b, double t) {
        return new Box(
                a.minX + (b.minX - a.minX) * t,
                a.minY + (b.minY - a.minY) * t,
                a.minZ + (b.minZ - a.minZ) * t,
                a.maxX + (b.maxX - a.maxX) * t,
                a.maxY + (b.maxY - a.maxY) * t,
                a.maxZ + (b.maxZ - a.maxZ) * t);
    }
}
