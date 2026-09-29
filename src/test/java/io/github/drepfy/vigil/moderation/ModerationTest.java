package io.github.drepfy.vigil.moderation;

import io.github.drepfy.vigil.storage.IoExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModerationTest {

    private static final Logger LOGGER = Logger.getLogger("test");
    private static final long MINUTE = 60_000L;
    private static final long DAY = 24 * 60 * MINUTE;

    @Test
    void parsesDurations() {
        assertEquals(30 * DAY, Durations.parse("30d"));
        assertEquals(90 * MINUTE, Durations.parse("1h30m"));
        assertEquals(30 * DAY, Durations.parse("1MO"));
        assertEquals(14 * DAY, Durations.parse("2w"));
        assertEquals(15_000L, Durations.parse("15s"));
        assertEquals(Durations.PERMANENT, Durations.parse("perm"));
        assertEquals(Durations.PERMANENT, Durations.parse("Permanent"));
        assertNull(Durations.parse("Cheating"));
        assertNull(Durations.parse("7"));
        assertNull(Durations.parse("0d"));
        assertNull(Durations.parse("d7"));
        assertNull(Durations.parse(""));
    }

    @Test
    void formatsDurations() {
        assertEquals("30 days", Durations.format(30 * DAY, "Permanent"));
        assertEquals("1 hour 30 minutes", Durations.format(90 * MINUTE, "Permanent"));
        assertEquals("1 day", Durations.format(DAY, "Permanent"));
        assertEquals("Permanent", Durations.format(Durations.PERMANENT, "Permanent"));
        assertEquals("1 year 5 days", Durations.format(370 * DAY, "Permanent"));
    }

    @Test
    void findsPresetsLeniently() {
        List<ReasonPreset> presets = List.of(new ReasonPreset("Hacked_Client", 30 * DAY), new ReasonPreset("X-Ray", null));
        assertEquals("Hacked_Client", ReasonPreset.find(presets, "hacked client").name());
        assertEquals("Hacked_Client", ReasonPreset.find(presets, "HACKED_CLIENT").name());
        assertEquals("X-Ray", ReasonPreset.find(presets, "xray").name());
        assertNull(ReasonPreset.find(presets, "Griefing"));
        assertNull(ReasonPreset.find(presets, ""));
        assertEquals("Hacked Client", presets.get(0).display());
    }

    @Test
    void punishmentsExpire() {
        long now = System.currentTimeMillis();
        Punishment temporary = new Punishment(1, PunishmentType.BAN, UUID.randomUUID(), "Steve", "Cheating", "Mod",
                now - 2 * DAY, DAY, false, null, null, 0L);
        assertFalse(temporary.isInEffect(now));
        Punishment permanent = new Punishment(2, PunishmentType.MUTE, UUID.randomUUID(), "Alex", "Spam", "Mod",
                now - 365 * DAY, Durations.PERMANENT, false, null, null, 0L);
        assertTrue(permanent.isInEffect(now));
        assertFalse(permanent.revoke("Mod", "Appeal", now).isInEffect(now));
        Punishment warning = new Punishment(3, PunishmentType.WARN, UUID.randomUUID(), "Sam", "Spam", "Mod",
                now, 0L, false, null, null, 0L);
        assertFalse(warning.isInEffect(now));
    }

    @Test
    void detectsBlockedCommandLabels() {
        assertEquals("msg", ModerationListener.commandLabel("/msg Bob hello"));
        assertEquals("msg", ModerationListener.commandLabel("/minecraft:msg Bob hello"));
        assertEquals("r", ModerationListener.commandLabel("/R hi"));
        assertEquals("spawn", ModerationListener.commandLabel("/spawn"));
    }

    @Test
    void punishmentsSurviveRestarts(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("punishments.yml");
        UUID steve = UUID.randomUUID();
        UUID alex = UUID.randomUUID();

        IoExecutor io = new IoExecutor(LOGGER);
        ModerationService service = new ModerationService(LOGGER, io, file);
        service.ban(steve, "Steve", "Cheating", "Mod", 30 * DAY);
        service.mute(alex, "Alex", "Spam", "Mod", Durations.PERMANENT);
        service.warn(alex, "Alex", "Spam", "Mod");
        service.warn(alex, "Alex", "Caps", "Mod");
        // Re-banning replaces the previous ban.
        Punishment replacement = service.ban(steve, "Steve", "Duping", "Admin", Durations.PERMANENT);
        assertEquals(replacement, service.activeBan(steve));
        assertEquals(List.of("Steve"), service.bannedNames());
        io.shutdown(5000);
        assertTrue(Files.exists(file));

        IoExecutor io2 = new IoExecutor(LOGGER);
        ModerationService reloaded = new ModerationService(LOGGER, io2, file);
        Punishment ban = reloaded.activeBan(steve);
        assertNotNull(ban);
        assertEquals("Duping", ban.reason());
        assertTrue(ban.isPermanent());
        assertNotNull(reloaded.activeMute(alex));
        assertEquals(2, reloaded.warningCount(alex));
        assertEquals(2, reloaded.history(steve).size());

        Punishment lifted = reloaded.unban(steve, "Admin", "Appeal Accepted");
        assertNotNull(lifted);
        assertTrue(lifted.revoked());
        assertNull(reloaded.activeBan(steve));
        assertNull(reloaded.unban(steve, "Admin", "again"));
        assertNotNull(reloaded.activeMuteByName("alex"));
        io2.shutdown(5000);
    }

    @Test
    void corruptFileIsMovedAsideNotDeleted(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("punishments.yml");
        Files.writeString(file, "punishments: [unclosed");
        IoExecutor io = new IoExecutor(LOGGER);
        ModerationService service = new ModerationService(LOGGER, io, file);
        assertFalse(service.isReadOnly());
        try (var files = Files.list(dir)) {
            assertTrue(files.anyMatch(path -> path.getFileName().toString().startsWith("punishments.yml.corrupt-")));
        }
        io.shutdown(1000);
    }
}
