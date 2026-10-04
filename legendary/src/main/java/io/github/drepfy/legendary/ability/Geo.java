package io.github.drepfy.legendary.ability;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
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

    /** To the right of a flat direction. */
    static Vector right(Vector flat) {
        return new Vector(-flat.getZ(), 0, flat.getX());
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
     * Where the player looks, on the ground: the first solid block within {@code range}
     * blocks, then down to the floor under that point. Null when there is nothing in range.
     */
    static Location target(Player player, double range) {
        Location eye = player.getEyeLocation();
        World world = eye.getWorld();
        Vector direction = eye.getDirection().normalize();
        Vector point = eye.toVector();
        Vector step = direction.clone().multiply(0.25);
        for (double travelled = 0; travelled <= range; travelled += 0.25) {
            Vector next = point.clone().add(step);
            if (solid(world, next.getX(), next.getY(), next.getZ())) {
                Location floor = floorBelow(world, point.getX(), point.getY(), point.getZ(), 6);
                return floor != null ? floor : new Location(world, point.getX(), point.getY(), point.getZ());
            }
            point = next;
        }
        return null;
    }

    /** Standing position on the first solid block at or below the point (up to {@code depth} blocks). */
    static Location floorBelow(World world, double x, double y, double z, int depth) {
        int top = (int) Math.floor(y);
        for (int by = top; by >= top - depth && by >= world.getMinHeight(); by--) {
            if (world.getBlockAt((int) Math.floor(x), by, (int) Math.floor(z)).getType().isSolid()) {
                return new Location(world, x, by + 1.0, z);
            }
        }
        return null;
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

    /** Away from a point, flat; the fallback direction when standing right on it. */
    static Vector away(Location from, Location to, Vector fallback) {
        Vector away = to.toVector().subtract(from.toVector()).setY(0);
        if (away.lengthSquared() < 1.0E-4) {
            return fallback.clone();
        }
        return away.normalize();
    }

    static double flatDistance(Location a, Location b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }
}
