package io.github.drepfy.legendary.ability;

import org.bukkit.Location;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;

/**
 * Shapes drawn with particles: lines, arcs, rings and spirals. Every ability effect is made of
 * vanilla particles placed along these, so it looks like part of the game and never pops in.
 */
final class Shapes {

    private Shapes() {
    }

    /** Points from a to b, about every {@code step} blocks, both ends included. */
    static List<Location> line(Location a, Location b, double step) {
        List<Location> points = new ArrayList<>();
        Vector way = b.toVector().subtract(a.toVector());
        double length = way.length();
        int n = Math.max(1, (int) Math.ceil(length / Math.max(0.05, step)));
        for (int i = 0; i <= n; i++) {
            points.add(a.clone().add(way.clone().multiply((double) i / n)));
        }
        return points;
    }

    /** A ring lying flat round {@code center}, turned by {@code turn} radians. */
    static List<Location> ring(Location center, double radius, int points, double turn) {
        List<Location> out = new ArrayList<>();
        for (int i = 0; i < points; i++) {
            double a = turn + Math.PI * 2 * i / points;
            out.add(center.clone().add(Math.cos(a) * radius, 0, Math.sin(a) * radius));
        }
        return out;
    }

    /** A ring standing upright across {@code facing} (a portal seen from the front). */
    static List<Location> standingRing(Location center, Vector facing, double radius, int points, double turn) {
        Vector right = Geo.right(flat(facing));
        List<Location> out = new ArrayList<>();
        for (int i = 0; i < points; i++) {
            double a = turn + Math.PI * 2 * i / points;
            out.add(center.clone().add(right.clone().multiply(Math.cos(a) * radius)).add(0, Math.sin(a) * radius, 0));
        }
        return out;
    }

    /**
     * A sword stroke: an arc round {@code center}, from {@code from} to {@code to} degrees (0 is
     * straight along {@code facing}, positive to the right), tilted by {@code tilt} degrees about
     * the facing direction (0 = level, 90 = upright).
     */
    static List<Location> arc(Location center, Vector facing, double radius, double from, double to, double tilt,
                              int points) {
        Vector forward = flat(facing);
        Vector right = Geo.right(forward);
        double t = Math.toRadians(tilt);
        Vector side = right.clone().multiply(Math.cos(t)).add(new Vector(0, Math.sin(t), 0));
        List<Location> out = new ArrayList<>();
        for (int i = 0; i < points; i++) {
            double a = Math.toRadians(from + (to - from) * i / Math.max(1, points - 1));
            Vector offset = forward.clone().multiply(Math.cos(a) * radius).add(side.clone().multiply(Math.sin(a) * radius));
            out.add(center.clone().add(offset));
        }
        return out;
    }

    /** A spiral round a vertical axis, from {@code bottom} up {@code height} blocks. */
    static List<Location> spiral(Location bottom, double radius, double height, double turns, int points, double turn) {
        List<Location> out = new ArrayList<>();
        for (int i = 0; i < points; i++) {
            double f = (double) i / Math.max(1, points - 1);
            double a = turn + Math.PI * 2 * turns * f;
            out.add(bottom.clone().add(Math.cos(a) * radius, height * f, Math.sin(a) * radius));
        }
        return out;
    }

    /** Evenly spread points on a sphere (for bursts). */
    static List<Location> sphere(Location center, double radius, int points) {
        List<Location> out = new ArrayList<>();
        double golden = Math.PI * (3 - Math.sqrt(5));
        for (int i = 0; i < points; i++) {
            double y = 1 - 2.0 * (i + 0.5) / points;
            double r = Math.sqrt(1 - y * y);
            double a = golden * i;
            out.add(center.clone().add(Math.cos(a) * r * radius, y * radius, Math.sin(a) * r * radius));
        }
        return out;
    }

    static Vector flat(Vector v) {
        Vector f = v.clone().setY(0);
        return f.lengthSquared() < 1.0E-6 ? new Vector(0, 0, 1) : f.normalize();
    }
}
