package io.github.drepfy.vigil.moderation;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.drepfy.vigil.config.ConfigLoader;
import io.github.drepfy.vigil.config.Settings;
import io.github.drepfy.vigil.storage.IoExecutor;
import org.bukkit.ChatColor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
        // Huge numbers are capped at 100 years instead of overflowing.
        long hundredYears = 100L * 365 * DAY;
        assertEquals(hundredYears, Durations.parse("999999999y"));
        assertEquals(hundredYears, Durations.parse("999999999mo"));
        assertEquals(hundredYears, Durations.parse("200y1d"));
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
        List<ReasonPreset> presets = List.of(new ReasonPreset("Hacked_Client", 30 * DAY), new ReasonPreset("X-Ray", (Long) null));
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
    void presetTimesEscalatePerOffence() {
        ReasonPreset cheating = new ReasonPreset("Cheating", List.of(7 * DAY, 30 * DAY, Durations.PERMANENT));
        assertEquals(7 * DAY, cheating.durationFor(0));
        assertEquals(30 * DAY, cheating.durationFor(1));
        assertEquals(Durations.PERMANENT, cheating.durationFor(2));
        assertEquals(Durations.PERMANENT, cheating.durationFor(10), "the last step repeats");
        assertEquals(7 * DAY, cheating.defaultDuration());
        assertTrue(cheating.escalates());
        assertEquals("7 days, 30 days, Permanent", cheating.ladderText("Permanent"));

        ReasonPreset doxxing = new ReasonPreset("Doxxing", Durations.PERMANENT);
        assertFalse(doxxing.escalates());
        assertEquals(Durations.PERMANENT, doxxing.durationFor(3));
        ReasonPreset free = new ReasonPreset("Other", (Long) null);
        assertNull(free.durationFor(0));
        assertEquals("", free.ladderText("Permanent"));

        assertEquals("1st", ReasonPreset.ordinal(1));
        assertEquals("2nd", ReasonPreset.ordinal(2));
        assertEquals("3rd", ReasonPreset.ordinal(3));
        assertEquals("4th", ReasonPreset.ordinal(4));
        assertEquals("11th", ReasonPreset.ordinal(11));
        assertEquals("12th", ReasonPreset.ordinal(12));
        assertEquals("13th", ReasonPreset.ordinal(13));
        assertEquals("21st", ReasonPreset.ordinal(21));
        assertEquals("102nd", ReasonPreset.ordinal(102));
    }

    @Test
    void offencesAreCountedFromHistory(@TempDir Path dir) {
        IoExecutor io = new IoExecutor(LOGGER);
        ModerationService service = new ModerationService(LOGGER, io, dir.resolve("punishments.yml"));
        UUID steve = UUID.randomUUID();
        UUID alex = UUID.randomUUID();
        assertEquals(0, service.previousOffences(steve, PunishmentType.BAN, "Cheating", Integer.MAX_VALUE));

        Punishment first = service.ban(steve, "Steve", "Cheating", "Mod", 7 * DAY);
        service.unban(steve, "Mod", "Served");
        service.ban(steve, "Steve", "cheating (fly)", "Mod", 30 * DAY); // same reason with details
        service.unban(steve, "Admin", "False ban"); // lifted as a mistake: does not count
        service.ban(steve, "Steve", "Griefing", "Mod", 3 * DAY); // another reason
        service.mute(steve, "Steve", "Cheating", "Mod", DAY); // another type
        service.ban(alex, "Alex", "Cheating", "Mod", 7 * DAY); // another player
        assertEquals(1, service.previousOffences(steve, PunishmentType.BAN, "Cheating", Integer.MAX_VALUE));
        assertEquals(0, service.previousOffences(steve, PunishmentType.BAN, "Cheating", first.id()),
                "only older punishments count");
        assertEquals(1, service.previousOffences(steve, PunishmentType.MUTE, "cheating", Integer.MAX_VALUE));
        assertEquals(0, service.previousOffences(steve, PunishmentType.BAN, "Cheat", Integer.MAX_VALUE),
                "a reason must match whole words");

        String auto = ModerationListener.ANTI_CHEAT_STAFF;
        service.ban(alex, "Alex", "Speed", auto, 30 * DAY);
        Punishment second = service.ban(alex, "Alex", "Flight", auto, Durations.PERMANENT);
        assertEquals(2, service.previousAutoBans(alex, auto));
        assertEquals(1, service.previousAutoBans(alex, auto, second.id()));
        service.unban(alex, "Admin", "Appeal accepted");
        assertEquals(1, service.previousAutoBans(alex, auto));

        service.warn(steve, "Steve", "Spam", "Mod", DAY);
        Punishment newest = service.warn(steve, "Steve", "Caps", "Mod", 7 * DAY);
        assertEquals(2, service.warningCount(steve));
        assertEquals(2, service.activeWarnings(steve, 30 * DAY).size());
        assertTrue(newest.isInEffect(System.currentTimeMillis() + 2 * DAY), "a 7 day warning still counts after 2 days");
        assertFalse(newest.isInEffect(System.currentTimeMillis() + 8 * DAY), "and stops counting after 7 days");

        Punishment removed = service.unwarn(steve, "Admin", "False Warning", 30 * DAY);
        assertEquals(newest.id(), removed.id(), "/unwarn removes the newest warning");
        assertEquals(1, service.activeWarnings(steve, 30 * DAY).size());
        assertNotNull(service.unwarn(steve, "Admin", "Staff Decision", 30 * DAY));
        assertNull(service.unwarn(steve, "Admin", "again", 30 * DAY), "nothing left to remove");
        assertEquals(2, service.warningCount(steve), "removed warnings stay in the history");
        io.shutdown(1000);
    }

    @Test
    void presetsRecogniseTheReasonsTheyGave() {
        ReasonPreset cheating = new ReasonPreset("Cheating", List.of(7 * DAY));
        ReasonPreset xray = new ReasonPreset("X-Ray", List.of(7 * DAY));
        ReasonPreset hacked = new ReasonPreset("Hacked_Client", List.of(7 * DAY));
        assertTrue(cheating.matches("Cheating"));
        assertTrue(cheating.matches("cheating fly hacks"));
        assertTrue(cheating.matches("Cheating (Flying)"), "automatic bans count for the Cheating preset");
        assertFalse(cheating.matches("Cheater"));
        assertFalse(cheating.matches("Anti Cheating"));
        assertTrue(xray.matches("X-Ray"));
        assertTrue(xray.matches("xray"));
        assertTrue(xray.matches("xray found diamonds"));
        assertTrue(hacked.matches("Hacked Client"));
        assertTrue(hacked.matches("hacked_client wurst"));
        assertFalse(hacked.matches("Hacked"));
        assertEquals(xray, ReasonPreset.matching(List.of(cheating, xray), "xray at spawn"));
        assertNull(ReasonPreset.matching(List.of(cheating, xray), "Griefing"));
    }

    @Test
    void offenceNumberMatchesThePresetUsedForTheLength(@TempDir Path dir) {
        IoExecutor io = new IoExecutor(LOGGER);
        ModerationService service = new ModerationService(LOGGER, io, dir.resolve("punishments.yml"));
        Settings settings = ConfigLoader.defaults(new ArrayList<>());
        ModerationListener listener = new ModerationListener(() -> settings, service);
        UUID steve = UUID.randomUUID();
        service.ban(steve, "Steve", "Cheating", "Mod", 7 * DAY);
        service.unban(steve, "Mod", "Served Time");
        // "/ban Steve Cheating fly" uses the Cheating times (2nd offence), and says so.
        Punishment second = service.ban(steve, "Steve", "Cheating fly", "Mod", 30 * DAY);
        String screen = ChatColor.stripColor(listener.banScreen(second));
        assertTrue(screen.contains("Reason: Cheating fly (2nd offence)"), screen);
        io.shutdown(1000);
    }

    @Test
    void banScreensFitTheBan(@TempDir Path dir) {
        IoExecutor io = new IoExecutor(LOGGER);
        ModerationService service = new ModerationService(LOGGER, io, dir.resolve("punishments.yml"));
        Settings settings = ConfigLoader.defaults(new ArrayList<>());
        ModerationListener listener = new ModerationListener(() -> settings, service);
        UUID steve = UUID.randomUUID();

        Punishment first = service.ban(steve, "Steve", "Cheating", "Mod", 7 * DAY);
        String temporary = ChatColor.stripColor(listener.banScreen(first));
        assertTrue(temporary.contains("You are banned from this server."), temporary);
        assertTrue(temporary.contains("Time remaining: 7 days"), temporary);
        assertTrue(temporary.contains("Reason: Cheating (1st offence)"), temporary);
        assertTrue(temporary.contains("Banned by: Mod"), temporary);
        assertTrue(temporary.contains("Ban ID: #" + first.id()), temporary);
        assertTrue(temporary.contains("Appeal: Ask a staff member on our Discord"), temporary);
        assertFalse(temporary.contains("{"), "every placeholder is filled: " + temporary);

        Punishment second = service.ban(steve, "Steve", "Cheating", "Mod", Durations.PERMANENT);
        String permanent = ChatColor.stripColor(listener.banScreen(second));
        assertTrue(permanent.contains("You are permanently banned from this server."), permanent);
        assertTrue(permanent.contains("Reason: Cheating (2nd offence)"), permanent);
        assertFalse(permanent.contains("{"), permanent);

        UUID alex = UUID.randomUUID();
        service.ban(alex, "Alex", "Speed", ModerationListener.ANTI_CHEAT_STAFF, 30 * DAY);
        Punishment auto = service.ban(alex, "Alex", "Flight", ModerationListener.ANTI_CHEAT_STAFF, Durations.PERMANENT);
        String antiCheat = ChatColor.stripColor(listener.banScreen(auto));
        assertTrue(antiCheat.contains("You have been banned by V\u026a\u0262\u026a\u029f."), antiCheat);
        assertTrue(listener.banScreen(auto).contains("\u00a7x"), "the name has its gradient colours");
        assertTrue(antiCheat.contains("Detected: Flight"), antiCheat);
        assertTrue(antiCheat.contains("Length: Permanent (2nd offence)"), "any earlier auto-ban counts: " + antiCheat);
        assertTrue(antiCheat.contains("Expires: Never"), antiCheat);
        assertFalse(antiCheat.contains("{"), antiCheat);
        for (String screen : List.of(temporary, permanent, antiCheat)) {
            assertFalse(screen.matches("(?s).*\\b[A-Z]{4,}\\b.*"), "no words in capitals: " + screen);
            assertFalse(screen.contains("\u26a0"), "no emojis: " + screen);
        }
        io.shutdown(1000);
    }

    @Test
    void discordMessagesAreValidJsonWithoutPings() {
        assertEquals("\"Say \\\"hi\\\"\\nnow\"", DiscordNotifier.quote("&cSay \"hi\"\nnow"));
        assertEquals("\"\\u0001\"", DiscordNotifier.quote("\u0001"));
        assertTrue(DiscordNotifier.quote("x".repeat(5000)).length() < 1100, "long text is cut");

        Punishment ban = new Punishment(12, PunishmentType.BAN, UUID.randomUUID(), "Steve", "Cheating \"fly\" @everyone",
                "Mod", System.currentTimeMillis(), 30 * DAY, false, null, null, 0L);
        JsonObject json = JsonParser.parseString(DiscordNotifier.punishmentJson(ban, "Permanent")).getAsJsonObject();
        assertEquals(0, json.getAsJsonObject("allowed_mentions").getAsJsonArray("parse").size(), "no pings");
        JsonObject embed = json.getAsJsonArray("embeds").get(0).getAsJsonObject();
        assertEquals("Banned: Steve", embed.get("title").getAsString());
        String fields = embed.getAsJsonArray("fields").toString();
        assertTrue(fields.contains("Cheating \\\"fly\\\" @everyone"), fields);
        assertTrue(fields.contains("30 days"), fields);
        assertTrue(fields.contains("#12"), fields);

        Punishment lifted = ban.revoke("Admin", "Appeal", System.currentTimeMillis());
        embed = JsonParser.parseString(DiscordNotifier.punishmentJson(lifted, "Permanent")).getAsJsonObject()
                .getAsJsonArray("embeds").get(0).getAsJsonObject();
        assertEquals("Unbanned: Steve", embed.get("title").getAsString());
        assertTrue(embed.getAsJsonArray("fields").toString().contains("Admin: Appeal"));
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
        service.warn(alex, "Alex", "Spam", "Mod", DAY);
        service.warn(alex, "Alex", "Caps", "Mod", DAY);
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

        // Timed warnings are "in effect" but must never be enforced as a mute after a restart.
        Path other = dir.resolve("warnings.yml");
        UUID sam = UUID.randomUUID();
        IoExecutor io3 = new IoExecutor(LOGGER);
        ModerationService warned = new ModerationService(LOGGER, io3, other);
        warned.warn(sam, "Sam", "Spam", "Mod", 7 * DAY);
        io3.shutdown(5000);
        IoExecutor io4 = new IoExecutor(LOGGER);
        ModerationService afterRestart = new ModerationService(LOGGER, io4, other);
        assertNull(afterRestart.activeMute(sam), "a warned player is not muted after a restart");
        assertNull(afterRestart.activeBan(sam));
        assertEquals(List.of(), afterRestart.mutedNames());
        assertEquals(1, afterRestart.activeWarnings(sam, 30 * DAY).size(), "the warning still counts");
        io4.shutdown(5000);
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
