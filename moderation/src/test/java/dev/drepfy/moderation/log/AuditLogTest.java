package dev.drepfy.moderation.log;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditLogTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-10T12:34:56Z"), ZoneOffset.UTC);
    private static final Logger QUIET = Logger.getAnonymousLogger();

    static {
        QUIET.setUseParentHandlers(false);
    }

    @TempDir
    Path folder;

    @Test
    void writesOneLinePerEntryIntoDailyFile() throws Exception {
        AuditLog log = new AuditLog(folder, CLOCK, QUIET, new AuditLog.Settings(true, false, 0));
        log.record("BAN", "#1 Steve by Mod reason=\"griefing\"");
        log.warn("BYPASS-BLOCKED", "something odd");
        log.close();

        List<String> lines = Files.readAllLines(folder.resolve("moderation-2026-03-10.log"));
        assertEquals(List.of(
                "[2026-03-10 12:34:56] [BAN] #1 Steve by Mod reason=\"griefing\"",
                "[2026-03-10 12:34:56] WARNING [BYPASS-BLOCKED] something odd"), lines);
    }

    @Test
    void craftedReasonsCannotForgeLogLines() throws Exception {
        AuditLog log = new AuditLog(folder, CLOCK, QUIET, new AuditLog.Settings(true, false, 0));
        log.record("WARN", "reason=\"x\n[2026-03-10 00:00:00] [UNBAN] fake entry\"");
        log.close();
        List<String> lines = Files.readAllLines(folder.resolve("moderation-2026-03-10.log"));
        assertEquals(1, lines.size());
        assertTrue(lines.getFirst().contains("x [2026-03-10 00:00:00] [UNBAN] fake entry"));
    }

    @Test
    void fileLoggingCanBeDisabled() {
        AuditLog log = new AuditLog(folder, CLOCK, QUIET, new AuditLog.Settings(false, false, 0));
        log.record("BAN", "not written");
        log.close();
        assertFalse(Files.exists(folder.resolve("moderation-2026-03-10.log")));
    }

    @Test
    void purgesFilesOlderThanRetention() throws Exception {
        Files.writeString(folder.resolve("moderation-2026-01-01.log"), "old");
        Files.writeString(folder.resolve("moderation-2026-03-05.log"), "recent");
        Files.writeString(folder.resolve("notes.txt"), "unrelated");
        AuditLog log = new AuditLog(folder, CLOCK, QUIET, new AuditLog.Settings(true, false, 30));
        log.purgeOldFiles();
        log.close();
        assertFalse(Files.exists(folder.resolve("moderation-2026-01-01.log")));
        assertTrue(Files.exists(folder.resolve("moderation-2026-03-05.log")));
        assertTrue(Files.exists(folder.resolve("notes.txt")));
    }

    @Test
    void sanitizeTruncatesVeryLongMessages() {
        String sanitized = AuditLog.sanitize("a".repeat(10_000));
        assertEquals(4003, sanitized.length());
        assertTrue(sanitized.endsWith("..."));
    }
}
