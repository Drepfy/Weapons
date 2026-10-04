package io.github.drepfy.combat;

import org.bukkit.Location;
import org.bukkit.World;

/**
 * An area (from bedrock to the sky) that players in combat cannot enter, such as spawn.
 * Corners are block coordinates and both are inside.
 */
public record SafeZone(String name, String world, int minX, int minZ, int maxX, int maxZ) {

    public static SafeZone of(String name, String world, int x1, int z1, int x2, int z2) {
        return new SafeZone(name, world, Math.min(x1, x2), Math.min(z1, z2), Math.max(x1, x2), Math.max(z1, z2));
    }

    public boolean contains(Location location) {
        World in = location.getWorld();
        return in != null && in.getName().equals(world) && contains(location.getX(), location.getZ());
    }

    boolean contains(double x, double z) {
        return x >= minX && x < maxX + 1 && z >= minZ && z < maxZ + 1;
    }

    /** Horizontal distance from outside to the zone's edge (0 inside). */
    double distance(double x, double z) {
        double dx = Math.max(Math.max(minX - x, 0.0), x - (maxX + 1));
        double dz = Math.max(Math.max(minZ - z, 0.0), z - (maxZ + 1));
        return Math.sqrt(dx * dx + dz * dz);
    }

    double centerX() {
        return (minX + maxX + 1) / 2.0;
    }

    double centerZ() {
        return (minZ + maxZ + 1) / 2.0;
    }

    /** "spawn (world, -50 -50 to 50 50)". */
    String describe() {
        return name + " (" + world + ", " + minX + " " + minZ + " to " + maxX + " " + maxZ + ")";
    }
}
