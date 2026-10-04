package io.github.drepfy.legendary.ability;

import io.github.drepfy.legendary.Ability;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Cooldowns belong to the weapon, not the player: passing a weapon to a friend, dropping it
 * and picking it up again, or reconnecting does not reset them. Counted in server ticks.
 */
public final class Cooldowns {

    private final Map<String, Long> ends = new HashMap<>();

    private static String key(UUID weapon, Ability ability) {
        return weapon + ":" + ability.key();
    }

    /** Ticks left (0 = ready). */
    public long remaining(UUID weapon, Ability ability, long now) {
        Long end = ends.get(key(weapon, ability));
        return end == null ? 0 : Math.max(0, end - now);
    }

    public void start(UUID weapon, Ability ability, long now, long ticks) {
        ends.put(key(weapon, ability), now + ticks);
    }

    /** At least this long, never shorter than a cooldown already running. */
    public void atLeast(UUID weapon, Ability ability, long now, long ticks) {
        ends.merge(key(weapon, ability), now + ticks, Math::max);
    }

    public void reset(UUID weapon) {
        String prefix = weapon + ":";
        ends.keySet().removeIf(key -> key.startsWith(prefix));
    }

    public void prune(long now) {
        ends.values().removeIf(end -> end <= now);
    }
}
