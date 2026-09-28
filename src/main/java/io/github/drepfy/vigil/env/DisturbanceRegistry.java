package io.github.drepfy.vigil.env;

import org.bukkit.World;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Remembers where things that can shove players around happened recently
 * (pistons, explosions, wind charges). Stored per chunk so recording is O(1) and a
 * lookup only touches the few chunks within the configured radius. Main thread only.
 */
public final class DisturbanceRegistry {

    private final Map<UUID, Map<Long, Long>> byWorld = new HashMap<>();

    public void record(World world, double x, double z, long nowMs) {
        int cx = WorldProbe.floor(x) >> 4;
        int cz = WorldProbe.floor(z) >> 4;
        byWorld.computeIfAbsent(world.getUID(), key -> new HashMap<>()).put(key(cx, cz), nowMs);
    }

    public boolean isNear(World world, double x, double z, double radius, long nowMs, long graceMs) {
        Map<Long, Long> chunks = byWorld.get(world.getUID());
        if (chunks == null || chunks.isEmpty()) {
            return false;
        }
        int minCx = WorldProbe.floor(x - radius) >> 4;
        int maxCx = WorldProbe.floor(x + radius) >> 4;
        int minCz = WorldProbe.floor(z - radius) >> 4;
        int maxCz = WorldProbe.floor(z + radius) >> 4;
        long threshold = nowMs - graceMs;
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                Long time = chunks.get(key(cx, cz));
                if (time != null && time >= threshold) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Drops entries older than the grace period. */
    public void prune(long nowMs, long graceMs) {
        long threshold = nowMs - graceMs;
        Iterator<Map<Long, Long>> worlds = byWorld.values().iterator();
        while (worlds.hasNext()) {
            Map<Long, Long> chunks = worlds.next();
            chunks.values().removeIf(time -> time < threshold);
            if (chunks.isEmpty()) {
                worlds.remove();
            }
        }
    }

    public void clear() {
        byWorld.clear();
    }

    private static long key(int cx, int cz) {
        return (long) cx & 0xffffffffL | ((long) cz & 0xffffffffL) << 32;
    }
}
