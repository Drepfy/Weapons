package dev.drepfy.moderation.command;

import dev.drepfy.moderation.config.Preset;
import dev.drepfy.moderation.model.Length;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PunishmentArgumentsTest {

    private static PunishmentArguments parse(String line) {
        return PunishmentArguments.parse(line.isEmpty() ? List.of() : List.of(line.split(" ")), true);
    }

    @Test
    void durationThenReason() {
        PunishmentArguments args = parse("7d griefing the spawn");
        assertEquals(Optional.of(Length.of(Duration.ofDays(7))), args.length());
        assertEquals("griefing the spawn", args.reason());
        assertNull(args.invalidDuration());
        assertFalse(args.silent());
    }

    @Test
    void permanentKeyword() {
        PunishmentArguments args = parse("permanent cheating");
        assertEquals(Optional.of(Length.PERMANENT), args.length());
        assertEquals("cheating", args.reason());
    }

    @Test
    void reasonOnlyLeavesLengthUnset() {
        PunishmentArguments args = parse("being rude");
        assertEquals(Optional.empty(), args.length());
        assertEquals("being rude", args.reason());
    }

    @Test
    void typoInDurationIsReportedInsteadOfBecomingTheReason() {
        // Without this, "/ban Steve 10 spam" would silently become a permanent ban for "10 spam".
        assertEquals("10", parse("10 spam").invalidDuration());
        assertEquals("1x", parse("1x spam").invalidDuration());
        assertEquals("-1", parse("-1").invalidDuration());
    }

    @Test
    void silentFlagAnywhereIsRemovedFromReason() {
        PunishmentArguments args = parse("-s 1h spam");
        assertTrue(args.silent());
        assertEquals(Optional.of(Length.of(Duration.ofHours(1))), args.length());
        assertEquals("spam", args.reason());

        PunishmentArguments trailing = parse("1h spam --SILENT");
        assertTrue(trailing.silent());
        assertEquals("spam", trailing.reason());
    }

    @Test
    void kicksNeverTreatTheFirstWordAsDuration() {
        PunishmentArguments args = PunishmentArguments.parse(List.of("1d", "later"), false);
        assertEquals(Optional.empty(), args.length());
        assertNull(args.invalidDuration());
        assertEquals("1d later", args.reason());
    }

    @Test
    void emptyArguments() {
        PunishmentArguments args = parse("");
        assertEquals("", args.reason());
        assertEquals(Optional.empty(), args.length());
    }

    private static final Function<String, Optional<Preset>> PRESETS = name -> name.equalsIgnoreCase("spam")
            ? Optional.of(new Preset("spam", Length.of(Duration.ofMinutes(30)), "Spamming"))
            : Optional.empty();

    @Test
    void presetSuppliesReasonAndLength() {
        PunishmentArguments args = parse("spam").withPreset(PRESETS);
        assertEquals("Spamming", args.reason());
        assertEquals(Optional.of(Length.of(Duration.ofMinutes(30))), args.length());
    }

    @Test
    void presetKeepsExplicitLengthAndAppendsExtraText() {
        PunishmentArguments args = parse("2h SPAM in global chat").withPreset(PRESETS);
        assertEquals("Spamming (in global chat)", args.reason());
        assertEquals(Optional.of(Length.of(Duration.ofHours(2))), args.length());
    }

    @Test
    void presetOnlyMatchesFirstWord() {
        PunishmentArguments args = parse("no spam please").withPreset(PRESETS);
        assertEquals("no spam please", args.reason());
        assertEquals(Optional.empty(), args.length());
    }

    @Test
    void presetDoesNotHideInvalidDuration() {
        PunishmentArguments args = parse("10 spam").withPreset(PRESETS);
        assertEquals("10", args.invalidDuration());
    }
}
