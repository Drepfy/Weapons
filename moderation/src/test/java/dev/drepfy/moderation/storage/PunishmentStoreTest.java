package dev.drepfy.moderation.storage;

import dev.drepfy.moderation.model.Actor;
import dev.drepfy.moderation.model.PlayerRecord;
import dev.drepfy.moderation.model.Punishment;
import dev.drepfy.moderation.model.PunishmentType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PunishmentStoreTest {

    private static final UUID STEVE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final Actor MOD = new Actor(UUID.fromString("00000000-0000-0000-0000-0000000000ff"), "Mod");
    private static final long NOW = 1_700_000_000_000L;
    private static final long HOUR = 3_600_000L;

    @TempDir
    Path folder;

    private Database database;
    private PunishmentStore store;

    @BeforeEach
    void open() throws Exception {
        database = Database.open(StorageSettings.sqlite("data/punishments.db", "sm_"), folder);
        store = new PunishmentStore(database);
    }

    @AfterEach
    void close() {
        database.close();
    }

    private Punishment insert(PunishmentType type, UUID target, Long expiresAt, boolean notified) throws Exception {
        return store.insert(new Punishment(0, type, target, target.equals(STEVE) ? "Steve" : "Alex", MOD, "reason " + type,
                "survival", NOW, expiresAt, type.timed(), false, null), notified);
    }

    @Test
    void usesWriteAheadLogging() throws Exception {
        try (Connection connection = database.connection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA journal_mode")) {
            result.next();
            assertEquals("wal", result.getString(1).toLowerCase());
        }
    }

    @Test
    void reopeningKeepsDataAndSchema() throws Exception {
        Punishment ban = insert(PunishmentType.BAN, STEVE, null, true);
        database.close();
        database = Database.open(StorageSettings.sqlite("data/punishments.db", "sm_"), folder);
        store = new PunishmentStore(database);
        assertEquals(Optional.of(ban), store.findById(ban.id()));
    }

    @Test
    void refusesNewerSchema() throws Exception {
        try (Connection connection = database.connection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE sm_meta SET meta_value = '99' WHERE meta_key = 'schema_version'");
        }
        database.close();
        assertThrows(Exception.class, () -> Database.open(StorageSettings.sqlite("data/punishments.db", "sm_"), folder));
        database = Database.open(StorageSettings.sqlite("other.db", "sm_"), folder);
    }

    @Test
    void rejectsUnsafeTablePrefix() {
        assertThrows(IllegalArgumentException.class, () -> StorageSettings.sqlite("x.db", "sm_; DROP TABLE x; --"));
    }

    @Test
    void remembersPlayersAndNameChanges() throws Exception {
        store.recordLogin(STEVE, "Steve", NOW);
        store.recordLogin(STEVE, "Steve2", NOW + 10);
        PlayerRecord record = store.findPlayer(STEVE).orElseThrow();
        assertEquals("Steve2", record.name());
        assertEquals(NOW, record.firstSeen());
        assertEquals(NOW + 10, record.lastSeen());
        assertFalse(record.exempt());

        assertEquals(Optional.of(STEVE), store.findPlayerByName("sTeVe2").map(PlayerRecord::uniqueId));

        // Alex later takes the name Steve used to have: lookups resolve to the most recent holder.
        store.recordLogin(ALEX, "Steve2", NOW + 20);
        assertEquals(Optional.of(ALEX), store.findPlayerByName("steve2").map(PlayerRecord::uniqueId));

        store.setExempt(STEVE, true);
        assertTrue(store.findPlayer(STEVE).orElseThrow().exempt());
        store.recordLogin(STEVE, "Steve3", NOW + 30);
        assertTrue(store.findPlayer(STEVE).orElseThrow().exempt(), "logging in again keeps the exempt snapshot");
    }

    @Test
    void roundTripsPunishments() throws Exception {
        Punishment mute = insert(PunishmentType.MUTE, STEVE, NOW + HOUR, true);
        Punishment console = store.insert(new Punishment(0, PunishmentType.BAN, STEVE, "Steve", Actor.system("Console"),
                "cheating", "survival", NOW, null, true, true, null), true);
        assertTrue(console.id() > mute.id());

        Punishment loaded = store.findById(console.id()).orElseThrow();
        assertNull(loaded.expiresAt());
        assertNull(loaded.actor().uniqueId());
        assertEquals("Console", loaded.actor().name());
        assertTrue(loaded.silent());
        assertNull(loaded.removal());
        assertEquals(Optional.of(mute), store.findById(mute.id()));
        assertEquals(Optional.empty(), store.findById(12345));
    }

    @Test
    void findsOnlyPunishmentsInForce() throws Exception {
        Punishment mute = insert(PunishmentType.MUTE, STEVE, NOW + HOUR, true);
        insert(PunishmentType.BAN, STEVE, NOW - 1, true);          // expired
        insert(PunishmentType.KICK, STEVE, null, true);            // kicks are never in force
        Punishment alexBan = insert(PunishmentType.BAN, ALEX, null, true);
        Punishment lifted = insert(PunishmentType.VOICE_MUTE, STEVE, null, true);
        store.lift(STEVE, PunishmentType.VOICE_MUTE, MOD, "appeal", NOW);

        assertEquals(List.of(mute), store.findInForce(STEVE, NOW));
        Map<UUID, List<Punishment>> both = store.findInForce(List.of(STEVE, ALEX, UUID.randomUUID()), NOW);
        assertEquals(List.of(mute), both.get(STEVE));
        assertEquals(List.of(alexBan), both.get(ALEX));
        assertEquals(2, both.size());
        assertFalse(store.findInForce(STEVE, NOW + HOUR).contains(mute), "expires exactly at expires_at");
        assertNotNull(store.findById(lifted.id()).orElseThrow().removal());
    }

    @Test
    void liftsAllActivePunishmentsOfOneType() throws Exception {
        Punishment first = insert(PunishmentType.MUTE, STEVE, NOW + HOUR, true);
        Punishment second = insert(PunishmentType.MUTE, STEVE, null, true);
        Punishment ban = insert(PunishmentType.BAN, STEVE, null, true);

        List<Punishment> lifted = store.lift(STEVE, PunishmentType.MUTE, MOD, "appeal accepted", NOW + 5);
        assertEquals(List.of(first.id(), second.id()), lifted.stream().map(Punishment::id).sorted().toList());
        Punishment.Removal removal = store.findById(first.id()).orElseThrow().removal();
        assertEquals(new Punishment.Removal(MOD, "appeal accepted", NOW + 5), removal);
        assertFalse(store.findById(first.id()).orElseThrow().active());

        assertEquals(List.of(), store.lift(STEVE, PunishmentType.MUTE, MOD, "again", NOW + 6));
        assertEquals(List.of(ban), store.findInForce(STEVE, NOW + 6));
    }

    @Test
    void liftsWarningById() throws Exception {
        Punishment warning = insert(PunishmentType.WARN, STEVE, NOW + HOUR, true);
        Punishment mute = insert(PunishmentType.MUTE, STEVE, null, true);

        assertEquals(Optional.empty(), store.liftById(mute.id(), PunishmentType.WARN, MOD, "wrong type", NOW));
        assertEquals(1, store.countInForce(STEVE, PunishmentType.WARN, NOW));
        assertEquals(warning.id(), store.liftById(warning.id(), PunishmentType.WARN, MOD, "mistake", NOW).orElseThrow().id());
        assertEquals(0, store.countInForce(STEVE, PunishmentType.WARN, NOW));
        assertEquals(Optional.empty(), store.liftById(warning.id(), PunishmentType.WARN, MOD, "again", NOW));
    }

    @Test
    void pagesHistoryNewestFirst() throws Exception {
        for (int i = 0; i < 5; i++) {
            store.insert(new Punishment(0, PunishmentType.WARN, STEVE, "Steve", MOD, "warning " + i, "survival",
                    NOW + i, null, true, false, null), true);
        }
        insert(PunishmentType.WARN, ALEX, null, true);
        assertEquals(5, store.countHistory(STEVE));
        assertEquals(List.of("warning 4", "warning 3"), store.history(STEVE, 0, 2).stream().map(Punishment::reason).toList());
        assertEquals(List.of("warning 0"), store.history(STEVE, 4, 2).stream().map(Punishment::reason).toList());
        assertEquals(List.of(), store.history(STEVE, 10, 2));
    }

    @Test
    void tracksUndeliveredWarnings() throws Exception {
        Punishment seen = insert(PunishmentType.WARN, STEVE, null, true);
        Punishment offline1 = insert(PunishmentType.WARN, STEVE, null, false);
        Punishment offline2 = insert(PunishmentType.WARN, STEVE, null, false);
        assertFalse(store.undeliveredWarnings(STEVE).contains(seen));
        assertEquals(List.of(offline1, offline2), store.undeliveredWarnings(STEVE));

        store.markNotified(List.of(offline1.id(), offline2.id()));
        assertEquals(List.of(), store.undeliveredWarnings(STEVE));
        store.markNotified(List.of());
    }

    @Test
    void clipsOverlongText() throws Exception {
        String longReason = "x".repeat(2000);
        Punishment stored = store.insert(new Punishment(0, PunishmentType.WARN, STEVE, "Steve", MOD, longReason,
                "survival", NOW, null, true, false, null), true);
        assertEquals(512, store.findById(stored.id()).orElseThrow().reason().length());
    }
}
