package dev.drepfy.moderation.util;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe per-player rate limiting, used so blocked chat/voice attempts don't spam the player
 * or the log.
 */
public final class Cooldowns {

    private final Map<UUID, Map<String, Long>> last = new ConcurrentHashMap<>();

    /** Returns {@code true} (and starts a new cooldown) if the previous one has elapsed. */
    public boolean tryAcquire(UUID player, String key, long now, long cooldownMillis) {
        Map<String, Long> keys = last.computeIfAbsent(player, id -> new ConcurrentHashMap<>());
        boolean[] acquired = {false};
        keys.compute(key, (k, previous) -> {
            if (previous == null || now - previous >= cooldownMillis) {
                acquired[0] = true;
                return now;
            }
            return previous;
        });
        return acquired[0];
    }

    public void clear(UUID player) {
        last.remove(player);
    }
}
