package io.github.drepfy.vigil.data;

import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Holds state for online players. State of players who left is kept for a while so
 * violation levels survive a quick relog (and relogging cannot be used to reset them).
 * Main thread only.
 */
public final class PlayerDataManager {

    /** How long state of a player who left is kept for a rejoin. */
    private static final long RETAIN_AFTER_QUIT_MS = 30 * 60_000L;

    private final Map<UUID, PlayerData> online = new HashMap<>();
    private final Map<UUID, PlayerData> recentlyQuit = new HashMap<>();
    private final Map<UUID, Long> quitTimes = new HashMap<>();

    /** Returns the player's data, creating it if it is missing (e.g. after /reload). */
    public PlayerData get(Player player) {
        PlayerData data = online.get(player.getUniqueId());
        if (data == null) {
            data = join(player, io.github.drepfy.vigil.util.Clock.now());
        }
        return data;
    }

    /** Data if present, without creating it. */
    public PlayerData peek(UUID uuid) {
        return online.get(uuid);
    }

    public PlayerData join(Player player, long nowMs) {
        UUID uuid = player.getUniqueId();
        PlayerData data = recentlyQuit.remove(uuid);
        quitTimes.remove(uuid);
        if (data == null) {
            data = new PlayerData(uuid, player.getName());
        }
        data.setName(player.getName());
        data.joinMs = nowMs;
        data.resetMovementState();
        data.positions.clear();
        data.lastSafeLocation = null;
        data.invalidateBypass();
        online.put(uuid, data);
        return data;
    }

    /** Removes an online player and keeps their state for a possible rejoin. */
    public PlayerData quit(UUID uuid, long nowMs) {
        PlayerData data = online.remove(uuid);
        if (data != null) {
            data.lastSafeLocation = null;
            recentlyQuit.put(uuid, data);
            quitTimes.put(uuid, nowMs);
        }
        return data;
    }

    public void pruneQuit(long nowMs) {
        Iterator<Map.Entry<UUID, Long>> iterator = quitTimes.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Long> entry = iterator.next();
            if (nowMs - entry.getValue() > RETAIN_AFTER_QUIT_MS) {
                recentlyQuit.remove(entry.getKey());
                iterator.remove();
            }
        }
    }

    public Collection<PlayerData> online() {
        return Collections.unmodifiableCollection(online.values());
    }

    public void clear() {
        online.clear();
        recentlyQuit.clear();
        quitTimes.clear();
    }
}
