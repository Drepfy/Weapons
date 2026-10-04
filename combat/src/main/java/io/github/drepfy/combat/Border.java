package io.github.drepfy.combat;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;

/** Draws the part of a safe zone's edge that is near a player, as red dust only they can see. */
final class Border {

    /** {@code DUST} since 1.20.5, {@code REDSTONE} before. */
    private static final Particle DUST = particle("DUST", "REDSTONE");
    private static final Particle.DustOptions RED = new Particle.DustOptions(Color.fromRGB(255, 40, 40), 1.4f);
    private static final int HALF_WIDTH = 4;

    private Border() {
    }

    static void draw(Player player, SafeZone zone, double reach) {
        if (DUST == null) {
            return;
        }
        Location at = player.getLocation();
        double x = at.getX();
        double z = at.getZ();
        double west = zone.minX();
        double east = zone.maxX() + 1;
        double north = zone.minZ();
        double south = zone.maxZ() + 1;
        // Each side: a line at a fixed x (or z), drawn around the point closest to the player.
        wall(player, at, true, west, clamp(z, north, south), north, south, reach);
        wall(player, at, true, east, clamp(z, north, south), north, south, reach);
        wall(player, at, false, north, clamp(x, west, east), west, east, reach);
        wall(player, at, false, south, clamp(x, west, east), west, east, reach);
    }

    private static void wall(Player player, Location at, boolean fixedX, double line, double around, double from,
                             double to, double reach) {
        double nearX = fixedX ? line : around;
        double nearZ = fixedX ? around : line;
        double dx = nearX - at.getX();
        double dz = nearZ - at.getZ();
        if (dx * dx + dz * dz > reach * reach) {
            return;
        }
        for (double offset = -HALF_WIDTH; offset <= HALF_WIDTH; offset += 1.0) {
            double along = around + offset;
            if (along < from || along > to) {
                continue;
            }
            for (double y = at.getY() - 1; y <= at.getY() + 3; y += 1.0) {
                Location point = new Location(at.getWorld(), fixedX ? line : along, y, fixedX ? along : line);
                try {
                    player.spawnParticle(DUST, point, 1, 0, 0, 0, 0, RED);
                } catch (RuntimeException | LinkageError e) {
                    return; // Particles are only for show.
                }
            }
        }
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static Particle particle(String... names) {
        for (String name : names) {
            try {
                Object value = Particle.class.getField(name).get(null);
                if (value instanceof Particle particle) {
                    return particle;
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Try the next name.
            }
        }
        return null;
    }
}
