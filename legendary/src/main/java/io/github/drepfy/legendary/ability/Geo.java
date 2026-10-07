package io.github.drepfy.legendary.ability;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.util.Vector;

/** Directions, line of sight and finding the ground, using only block types (works on every server). */
final class Geo {

    private Geo() {
    }

    /** The way the player faces, flat on the ground. */
    static Vector flat(Location location) {
        double yaw = Math.toRadians(location.getYaw());
        return new Vector(-Math.sin(yaw), 0, Math.cos(yaw));
    }

    static boolean solid(World world, double x, double y, double z) {
        Block block = world.getBlockAt((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
        return block.getType().isSolid();
    }

    static boolean solid(Location at) {
        return at.getWorld() != null && solid(at.getWorld(), at.getX(), at.getY(), at.getZ());
    }

    /** No solid block between the two points (glass counts as a wall). */
    static boolean clear(Location from, Location to) {
        World world = from.getWorld();
        if (world == null || !world.equals(to.getWorld())) {
            return false;
        }
        Vector step = to.toVector().subtract(from.toVector());
        double length = step.length();
        if (length < 0.6) {
            return true;
        }
        step.multiply(0.25 / length);
        Vector point = from.toVector();
        for (double travelled = 0.25; travelled < length - 0.35; travelled += 0.25) {
            point.add(step);
            if (solid(world, point.getX(), point.getY(), point.getZ())) {
                return false;
            }
        }
        return true;
    }

    /** The middle of an entity's body. */
    static Location middle(Entity entity) {
        return entity.getLocation().add(0, entity.getHeight() / 2.0, 0);
    }

    /**
     * The ground under a column for a wave rolling along it: one block up a step, or up to
     * three blocks down. {@link Integer#MIN_VALUE} when there is a wall or a drop.
     */
    static int groundY(World world, double x, double z, int currentGround) {
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        for (int y = currentGround + 1; y >= currentGround - 3 && y >= world.getMinHeight(); y--) {
            if (world.getBlockAt(bx, y, bz).getType().isSolid()
                    && !world.getBlockAt(bx, y + 1, bz).getType().isSolid()
                    && !world.getBlockAt(bx, y + 2, bz).getType().isSolid()) {
                return y;
            }
        }
        return Integer.MIN_VALUE;
    }

    static double flatDistance(Location a, Location b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }
}
