package dev.drepfy.moderation.cache;

import dev.drepfy.moderation.model.Punishment;
import dev.drepfy.moderation.model.PunishmentType;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

/**
 * Thread-safe view of the punishments currently in force for players who are online or joining.
 *
 * <p>Chat, command and voice checks run on many threads (voice packets are handled off the main
 * thread) and must never wait on the database, so they read from here. The cache is kept correct
 * against races between database reads and punishments issued or lifted at the same time:
 * <ul>
 *   <li>A snapshot read from the database only drops entries that were cached <em>before</em> the
 *       read started ({@link #beginSnapshot()}), so a punishment issued while a slow login or sync
 *       query was running is never lost.</li>
 *   <li>Lifted punishments leave a tombstone, so a snapshot read before the removal can't bring
 *       them back.</li>
 * </ul>
 */
public final class PunishmentCache {

    /** Tombstones only need to outlive the slowest in-flight snapshot. */
    private static final long TOMBSTONE_TTL_MILLIS = 15 * 60 * 1000L;

    private final Map<UUID, PlayerEntry> players = new ConcurrentHashMap<>();
    private final Map<Long, Long> tombstones = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    private record Cached(Punishment punishment, long sequence) {
    }

    private static final class PlayerEntry {
        final Map<Long, Cached> punishments = new ConcurrentHashMap<>();
        volatile boolean loaded;
        volatile long touchedAt;
    }

    /** Call before reading a player's punishments from the database; pass the token to {@link #applySnapshot}. */
    public long beginSnapshot() {
        return sequence.incrementAndGet();
    }

    /**
     * Replaces what is cached for a player with punishments read from the database.
     *
     * @param active the player's punishments in force at the time of the read
     * @param token  the value {@link #beginSnapshot()} returned before the read started
     */
    public void applySnapshot(UUID player, Collection<Punishment> active, long token, long now) {
        PlayerEntry entry = players.computeIfAbsent(player, id -> new PlayerEntry());
        Set<Long> ids = new HashSet<>();
        for (Punishment punishment : active) {
            ids.add(punishment.id());
        }
        entry.punishments.entrySet().removeIf(e -> e.getValue().sequence() < token && !ids.contains(e.getKey()));
        for (Punishment punishment : active) {
            if (!tombstones.containsKey(punishment.id())) {
                entry.punishments.putIfAbsent(punishment.id(), new Cached(punishment, token));
                dropIfTombstoned(entry, punishment.id());
            }
        }
        entry.loaded = true;
        entry.touchedAt = now;
    }

    /** Records a punishment that was just issued. */
    public void add(Punishment punishment, long now) {
        if (tombstones.containsKey(punishment.id())) {
            return;
        }
        PlayerEntry entry = players.computeIfAbsent(punishment.targetId(), id -> new PlayerEntry());
        entry.punishments.put(punishment.id(), new Cached(punishment, sequence.incrementAndGet()));
        entry.touchedAt = now;
        dropIfTombstoned(entry, punishment.id());
    }

    /** Records that a punishment was lifted. */
    public void remove(UUID player, long punishmentId, long now) {
        tombstones.put(punishmentId, now);
        PlayerEntry entry = players.get(player);
        if (entry != null) {
            entry.punishments.remove(punishmentId);
        }
    }

    /**
     * The punishment of the given type currently in force for a player: a permanent one if there is
     * one, otherwise the one that lasts longest.
     */
    public Optional<Punishment> find(UUID player, PunishmentType type, long now) {
        PlayerEntry entry = players.get(player);
        if (entry == null) {
            return Optional.empty();
        }
        return Punishment.strongest(entry.punishments.values().stream().map(Cached::punishment).toList(), type, now);
    }

    /** Whether the player's punishments have been read from the database at least once. */
    public boolean isLoaded(UUID player) {
        PlayerEntry entry = players.get(player);
        return entry != null && entry.loaded;
    }

    public int size() {
        return players.size();
    }

    /**
     * Frees memory for players who are no longer online. Entries touched recently are kept so a
     * player who is between login and join (or relogging) keeps the data their login loaded.
     */
    public void evictIdle(Predicate<UUID> online, long now, long idleMillis) {
        players.entrySet().removeIf(e -> !online.test(e.getKey()) && now - e.getValue().touchedAt > idleMillis);
        tombstones.values().removeIf(removedAt -> now - removedAt > TOMBSTONE_TTL_MILLIS);
    }

    private void dropIfTombstoned(PlayerEntry entry, long id) {
        // A removal may have raced with the put above; re-checking after the put closes the window.
        if (tombstones.containsKey(id)) {
            entry.punishments.remove(id);
        }
    }

}
