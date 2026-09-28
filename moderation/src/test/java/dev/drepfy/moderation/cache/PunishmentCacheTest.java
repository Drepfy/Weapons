package dev.drepfy.moderation.cache;

import dev.drepfy.moderation.model.Actor;
import dev.drepfy.moderation.model.Punishment;
import dev.drepfy.moderation.model.PunishmentType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PunishmentCacheTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final long NOW = 1_000_000L;

    private final PunishmentCache cache = new PunishmentCache();

    private static Punishment punishment(long id, PunishmentType type, Long expiresAt) {
        return new Punishment(id, type, PLAYER, "Steve", Actor.system("Console"), "reason", "test",
                NOW - 1000, expiresAt, type.timed(), false, null);
    }

    @Test
    void findsPunishmentsInForceOnly() {
        cache.add(punishment(1, PunishmentType.MUTE, NOW + 60_000), NOW);
        cache.add(punishment(2, PunishmentType.BAN, NOW - 1), NOW);
        assertTrue(cache.find(PLAYER, PunishmentType.MUTE, NOW).isPresent());
        assertFalse(cache.find(PLAYER, PunishmentType.BAN, NOW).isPresent(), "expired ban must not apply");
        assertFalse(cache.find(PLAYER, PunishmentType.MUTE, NOW + 60_000).isPresent(), "mute expires on time");
        assertFalse(cache.find(UUID.randomUUID(), PunishmentType.MUTE, NOW).isPresent());
    }

    @Test
    void prefersPermanentThenLongestPunishment() {
        cache.add(punishment(1, PunishmentType.MUTE, NOW + 60_000), NOW);
        cache.add(punishment(2, PunishmentType.MUTE, NOW + 120_000), NOW);
        assertEquals(2, cache.find(PLAYER, PunishmentType.MUTE, NOW).orElseThrow().id());
        cache.add(punishment(3, PunishmentType.MUTE, null), NOW);
        assertEquals(3, cache.find(PLAYER, PunishmentType.MUTE, NOW).orElseThrow().id());
    }

    @Test
    void snapshotReplacesStaleEntries() {
        cache.add(punishment(1, PunishmentType.MUTE, null), NOW);
        long token = cache.beginSnapshot();
        // The database says the mute is gone (e.g. lifted on another server).
        cache.applySnapshot(PLAYER, List.of(), token, NOW);
        assertFalse(cache.find(PLAYER, PunishmentType.MUTE, NOW).isPresent());
        assertTrue(cache.isLoaded(PLAYER));
    }

    @Test
    void punishmentIssuedDuringSlowSnapshotIsNotLost() {
        long token = cache.beginSnapshot();              // login starts reading the database
        cache.add(punishment(7, PunishmentType.MUTE, null), NOW); // staff mutes the player meanwhile
        cache.applySnapshot(PLAYER, List.of(), token, NOW);        // read finishes without the new mute
        assertEquals(Optional.of(7L), cache.find(PLAYER, PunishmentType.MUTE, NOW).map(Punishment::id));
    }

    @Test
    void liftedPunishmentIsNotResurrectedByOlderSnapshot() {
        Punishment mute = punishment(9, PunishmentType.MUTE, null);
        long token = cache.beginSnapshot();              // read starts while the mute is still active
        cache.add(mute, NOW);
        cache.remove(PLAYER, 9, NOW);                    // staff unmutes
        cache.applySnapshot(PLAYER, List.of(mute), token, NOW); // stale read still contains it
        assertFalse(cache.find(PLAYER, PunishmentType.MUTE, NOW).isPresent());
        cache.add(mute, NOW);                            // and neither can a late add
        assertFalse(cache.find(PLAYER, PunishmentType.MUTE, NOW).isPresent());
    }

    @Test
    void evictsOnlyIdleOfflinePlayers() {
        UUID other = UUID.randomUUID();
        cache.applySnapshot(PLAYER, List.of(punishment(1, PunishmentType.MUTE, null)), cache.beginSnapshot(), NOW);
        cache.applySnapshot(other, List.of(), cache.beginSnapshot(), NOW);

        // Recently loaded players are kept even when offline (they may be mid-login).
        cache.evictIdle(id -> false, NOW + 1_000, 60_000);
        assertEquals(2, cache.size());

        // Online players are never evicted.
        cache.evictIdle(Set.of(PLAYER)::contains, NOW + 120_000, 60_000);
        assertEquals(1, cache.size());
        assertTrue(cache.isLoaded(PLAYER));
        assertFalse(cache.isLoaded(other));
    }

    @Test
    void kicksAreNeverInForce() {
        cache.add(new Punishment(4, PunishmentType.KICK, PLAYER, "Steve", Actor.system("Console"), "r", "test",
                NOW, null, false, false, null), NOW);
        assertFalse(cache.find(PLAYER, PunishmentType.KICK, NOW).isPresent());
    }
}
