package io.github.drepfy.vigil.model;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/**
 * Mining history used to recognise x-ray and ore finders: which blocks the player
 * broke recently, which valuable ores they mined, and how many ordinary blocks they
 * mined since they last found a hidden vein of each ore group.
 */
public final class XrayTracker {

    private static final int MAX_BROKEN = 1024;
    private static final int MAX_ORES = 256;
    /** Ores closer than this (in every axis) belong to the same vein. */
    private static final int VEIN_DISTANCE = 3;
    private static final long VEIN_MEMORY_MS = 10 * 60_000L;

    private record Ore(long position, int group, long timeMs) {
    }

    private final Deque<Long> brokenOrder = new ArrayDeque<>();
    private final Set<Long> broken = new HashSet<>();
    private final Deque<Ore> ores = new ArrayDeque<>();
    /** Veins judged together. */
    public static final int WINDOW = 5;

    private final int[] blocksSinceVein = new int[4];
    private final int[][] veinGaps = new int[4][WINDOW];
    private final int[] veinCount = new int[4];

    public static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFFL);
    }

    /** Remembers that the player broke this block. */
    public void recordBroken(int x, int y, int z) {
        long key = pack(x, y, z);
        if (broken.add(key)) {
            brokenOrder.addLast(key);
            if (brokenOrder.size() > MAX_BROKEN) {
                broken.remove(brokenOrder.removeFirst());
            }
        }
    }

    public boolean brokeRecently(int x, int y, int z) {
        return broken.contains(pack(x, y, z));
    }

    /** An ordinary block (stone, netherrack...) was mined. */
    public void recordFiller() {
        for (int i = 0; i < blocksSinceVein.length; i++) {
            if (blocksSinceVein[i] < Integer.MAX_VALUE) {
                blocksSinceVein[i]++;
            }
        }
    }

    /**
     * Records a mined ore and tells whether it starts a new vein (no ore of the same
     * group was mined close to it recently).
     */
    public boolean recordOre(int group, int x, int y, int z, long nowMs) {
        while (!ores.isEmpty() && nowMs - ores.peekFirst().timeMs() > VEIN_MEMORY_MS) {
            ores.removeFirst();
        }
        boolean newVein = true;
        for (Ore ore : ores) {
            if (ore.group() == group && near(ore.position(), x, y, z)) {
                newVein = false;
                break;
            }
        }
        ores.addLast(new Ore(pack(x, y, z), group, nowMs));
        if (ores.size() > MAX_ORES) {
            ores.removeFirst();
        }
        return newVein;
    }

    /**
     * Records that a new hidden vein of the group was found and returns the average
     * number of ordinary blocks mined per vein over the last {@link #WINDOW} veins, or
     * {@link Double#NaN} while fewer veins were found.
     */
    public double recordVein(int group) {
        int[] gaps = veinGaps[group];
        gaps[veinCount[group] % WINDOW] = blocksSinceVein[group];
        veinCount[group]++;
        blocksSinceVein[group] = 0;
        if (veinCount[group] < WINDOW) {
            return Double.NaN;
        }
        double sum = 0;
        for (int gap : gaps) {
            sum += gap;
        }
        return sum / WINDOW;
    }

    /** Starts over for a group (after a flag). */
    public void clearVeins(int group) {
        veinCount[group] = 0;
    }

    private static boolean near(long packed, int x, int y, int z) {
        int ox = (int) (packed >> 38);
        int oz = (int) (packed << 26 >> 38);
        int oy = (int) (packed << 52 >> 52);
        return Math.abs(ox - x) <= VEIN_DISTANCE && Math.abs(oy - y) <= VEIN_DISTANCE
                && Math.abs(oz - z) <= VEIN_DISTANCE;
    }
}
