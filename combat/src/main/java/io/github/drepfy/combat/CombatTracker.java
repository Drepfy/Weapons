package io.github.drepfy.combat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Who is in combat with whom, and until when. Pure bookkeeping (no Bukkit), server thread only.
 *
 * <p>Each player keeps one end time per opponent; they are in combat until the latest one.
 * That way killing one opponent can end the combat with that opponent while a fight with
 * someone else goes on. While a player is offline their timer is paused, so logging out
 * never runs it down.
 */
public final class CombatTracker {

    /** The "opponent" of a combat started by staff ({@code /combat tag}). */
    public static final UUID STAFF = new UUID(0L, 0L);

    private static final class Combat {
        final Map<UUID, Long> ends = new HashMap<>();
        /** When the player went offline, or -1 while online. */
        long pausedAt = -1;
        /** The player has been told they are in combat. */
        boolean announced;

        long latestEnd() {
            long latest = 0;
            for (long end : ends.values()) {
                latest = Math.max(latest, end);
            }
            return latest;
        }

        long remaining(long now) {
            return Math.max(0L, latestEnd() - (pausedAt >= 0 ? pausedAt : now));
        }
    }

    private final Map<UUID, Combat> combats = new HashMap<>();

    /** Puts {@code player} in combat with {@code opponent} for {@code durationMs} (never shortens it). */
    public void tag(UUID player, UUID opponent, long now, long durationMs) {
        Combat combat = combats.computeIfAbsent(player, key -> new Combat());
        long base = combat.pausedAt >= 0 ? combat.pausedAt : now;
        combat.ends.merge(opponent, base + durationMs, Math::max);
    }

    /** Milliseconds of combat left (0 = not in combat). Paused while offline. */
    public long remaining(UUID player, long now) {
        Combat combat = combats.get(player);
        return combat == null ? 0L : combat.remaining(now);
    }

    public boolean inCombat(UUID player, long now) {
        return remaining(player, now) > 0;
    }

    /** Ends the combat with one opponent (the other fights go on). */
    public void removeOpponent(UUID player, UUID opponent) {
        Combat combat = combats.get(player);
        if (combat != null) {
            combat.ends.remove(opponent);
        }
    }

    /** Ends all combat of the player. */
    public void clear(UUID player) {
        combats.remove(player);
    }

    /** The player went offline: the timer stops. */
    public void pause(UUID player, long now) {
        Combat combat = combats.get(player);
        if (combat != null && combat.pausedAt < 0) {
            combat.pausedAt = now;
        }
    }

    /** The player came back: the timer goes on from where it stopped. */
    public void resume(UUID player, long now) {
        Combat combat = combats.get(player);
        if (combat != null && combat.pausedAt >= 0) {
            long shift = now - combat.pausedAt;
            combat.ends.replaceAll((opponent, end) -> end + shift);
            combat.pausedAt = -1;
        }
    }

    /**
     * Drops finished fights.
     *
     * @return players (not paused) whose combat has just ended
     */
    public List<UUID> expire(long now) {
        List<UUID> ended = new ArrayList<>();
        for (Iterator<Map.Entry<UUID, Combat>> it = combats.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Combat> entry = it.next();
            Combat combat = entry.getValue();
            if (combat.pausedAt >= 0) {
                continue;
            }
            combat.ends.values().removeIf(end -> end <= now);
            if (combat.ends.isEmpty()) {
                it.remove();
                if (combat.announced) {
                    ended.add(entry.getKey());
                }
            }
        }
        return ended;
    }

    /** Marks the player as told; returns true the first time. */
    public boolean announce(UUID player) {
        Combat combat = combats.get(player);
        if (combat == null || combat.announced) {
            return false;
        }
        combat.announced = true;
        return true;
    }

    /** Was told about the combat (so needs to be told it ended). */
    public boolean announced(UUID player) {
        Combat combat = combats.get(player);
        return combat != null && combat.announced;
    }

    public Set<UUID> players() {
        return combats.keySet();
    }

    /** Opponents and the time left against each (for saving and for staff). */
    public Map<UUID, Long> opponents(UUID player, long now) {
        Combat combat = combats.get(player);
        Map<UUID, Long> result = new HashMap<>();
        if (combat != null) {
            long base = combat.pausedAt >= 0 ? combat.pausedAt : now;
            for (Map.Entry<UUID, Long> entry : combat.ends.entrySet()) {
                if (entry.getValue() > base) {
                    result.put(entry.getKey(), entry.getValue() - base);
                }
            }
        }
        return result;
    }

    /** Restores a saved combat as paused (the player is offline until they join). */
    public void restore(UUID player, Map<UUID, Long> remaining, long now) {
        if (remaining.isEmpty()) {
            return;
        }
        Combat combat = new Combat();
        combat.pausedAt = now;
        combat.announced = true;
        for (Map.Entry<UUID, Long> entry : remaining.entrySet()) {
            combat.ends.put(entry.getKey(), now + entry.getValue());
        }
        combats.put(player, combat);
    }
}
