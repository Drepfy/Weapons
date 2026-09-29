package io.github.drepfy.vigil.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Fixed-size ring buffer of recent hitbox positions, recorded once per server tick.
 * Used for lag compensation: when a player attacks, the target may have been
 * anywhere along its recent path from the attacker's point of view.
 */
public final class PositionHistory {

    /** Sub-steps interpolated between two consecutive samples. */
    private static final int INTERPOLATION_STEPS = 4;

    private final long[] times;
    private final double[] xs;
    private final double[] ys;
    private final double[] zs;
    private final double[] widths;
    private final double[] heights;
    private int head;
    private int size;

    public PositionHistory(int capacity) {
        if (capacity < 2) {
            throw new IllegalArgumentException("capacity must be >= 2");
        }
        times = new long[capacity];
        xs = new double[capacity];
        ys = new double[capacity];
        zs = new double[capacity];
        widths = new double[capacity];
        heights = new double[capacity];
    }

    public void record(long timeMs, double x, double y, double z, double width, double height) {
        times[head] = timeMs;
        xs[head] = x;
        ys[head] = y;
        zs[head] = z;
        widths[head] = width;
        heights[head] = height;
        head = (head + 1) % times.length;
        if (size < times.length) {
            size++;
        }
    }

    public void clear() {
        size = 0;
        head = 0;
    }

    public int size() {
        return size;
    }

    /**
     * Boxes recorded at or after {@code sinceMs}, newest first, including the newest
     * sample older than {@code sinceMs} (the position the target was leaving at the
     * start of the window) and interpolated positions between samples.
     */
    public List<Box> boxesSince(long sinceMs) {
        List<Box> result = new ArrayList<>();
        Box newer = null;
        for (int i = 0; i < size; i++) {
            int index = Math.floorMod(head - 1 - i, times.length);
            Box box = Box.ofEntity(xs[index], ys[index], zs[index], widths[index], heights[index]);
            if (newer != null) {
                for (int step = 1; step < INTERPOLATION_STEPS; step++) {
                    result.add(Box.lerp(newer, box, step / (double) INTERPOLATION_STEPS));
                }
            }
            result.add(box);
            newer = box;
            if (times[index] < sinceMs) {
                break;
            }
        }
        return result;
    }

    /**
     * Smallest distance from a point to any box the entity occupied since
     * {@code sinceMs}, or {@link Double#NaN} when no history is available.
     */
    public double minDistanceSince(long sinceMs, double px, double py, double pz) {
        List<Box> boxes = boxesSince(sinceMs);
        if (boxes.isEmpty()) {
            return Double.NaN;
        }
        double best = Double.MAX_VALUE;
        for (Box box : boxes) {
            best = Math.min(best, box.distanceTo(px, py, pz));
        }
        return best;
    }

    /**
     * Average horizontal speed in blocks per 50 ms along the recorded path since
     * {@code sinceMs}, 0 if fewer than two samples are available.
     */
    public double averageHorizontalSpeed(long sinceMs) {
        if (size < 2) {
            return 0.0;
        }
        double path = 0.0;
        long newestTime = times[Math.floorMod(head - 1, times.length)];
        long oldestTime = newestTime;
        for (int i = 0; i + 1 < size; i++) {
            int newer = Math.floorMod(head - 1 - i, times.length);
            int older = Math.floorMod(head - 2 - i, times.length);
            if (times[older] < sinceMs) {
                break;
            }
            double dx = xs[newer] - xs[older];
            double dz = zs[newer] - zs[older];
            path += Math.sqrt(dx * dx + dz * dz);
            oldestTime = times[older];
        }
        long elapsed = newestTime - oldestTime;
        return elapsed <= 0 ? 0.0 : path * 50.0 / elapsed;
    }

    /**
     * Average horizontal speed in blocks per 50 ms along the path recorded between
     * {@code fromMs} and {@code toMs}, or {@link Double#NaN} when the history does not
     * cover at least 80% of that window.
     */
    public double averageHorizontalSpeed(long fromMs, long toMs) {
        double path = 0.0;
        long first = Long.MAX_VALUE;
        long last = Long.MIN_VALUE;
        for (int i = 0; i + 1 < size; i++) {
            int newer = Math.floorMod(head - 1 - i, times.length);
            int older = Math.floorMod(head - 2 - i, times.length);
            if (times[older] < fromMs) {
                break;
            }
            if (times[newer] > toMs) {
                continue;
            }
            double dx = xs[newer] - xs[older];
            double dz = zs[newer] - zs[older];
            path += Math.sqrt(dx * dx + dz * dz);
            first = Math.min(first, times[older]);
            last = Math.max(last, times[newer]);
        }
        long covered = last - first;
        if (first == Long.MAX_VALUE || covered < (toMs - fromMs) * 0.8) {
            return Double.NaN;
        }
        return path * 50.0 / covered;
    }

    /** Horizontal speed (blocks/tick) between the two most recent samples, 0 if unknown. */
    public double recentHorizontalSpeed() {
        if (size < 2) {
            return 0.0;
        }
        int newest = Math.floorMod(head - 1, times.length);
        int previous = Math.floorMod(head - 2, times.length);
        double dx = xs[newest] - xs[previous];
        double dz = zs[newest] - zs[previous];
        long dt = Math.max(1L, times[newest] - times[previous]);
        return Math.sqrt(dx * dx + dz * dz) * (50.0 / dt);
    }
}
