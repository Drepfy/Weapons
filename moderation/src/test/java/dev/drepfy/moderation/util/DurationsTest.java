package dev.drepfy.moderation.util;

import dev.drepfy.moderation.model.Length;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DurationsTest {

    @Test
    void parsesSingleUnits() {
        assertEquals(Optional.of(Duration.ofSeconds(30)), Durations.parse("30s"));
        assertEquals(Optional.of(Duration.ofMinutes(15)), Durations.parse("15m"));
        assertEquals(Optional.of(Duration.ofHours(12)), Durations.parse("12h"));
        assertEquals(Optional.of(Duration.ofDays(7)), Durations.parse("7d"));
        assertEquals(Optional.of(Duration.ofDays(14)), Durations.parse("2w"));
        assertEquals(Optional.of(Duration.ofDays(90)), Durations.parse("3mo"));
        assertEquals(Optional.of(Duration.ofDays(365)), Durations.parse("1y"));
    }

    @Test
    void parsesCombinedLongFormAndMixedCase() {
        assertEquals(Optional.of(Duration.ofHours(36)), Durations.parse("1d12h"));
        assertEquals(Optional.of(Duration.ofMinutes(90)), Durations.parse("1hour30minutes"));
        assertEquals(Optional.of(Duration.ofDays(2)), Durations.parse("2DAYS"));
        // "M" is minutes regardless of case; months are always "mo".
        assertEquals(Optional.of(Duration.ofMinutes(5)), Durations.parse("5M"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "10", "1x", "d", "0s", "0d0h", "-5m", "5 m", "1.5h", "abc", "1d-2h",
            "999999999y", "101y", "99999999999999999999s"})
    void rejectsInvalidZeroOverflowingOrTooLong(String input) {
        assertEquals(Optional.empty(), Durations.parse(input));
    }

    @Test
    void acceptsUpToOneHundredYears() {
        assertEquals(Optional.of(Durations.MAX), Durations.parse("100y"));
    }

    @Test
    void recognisesPermanentKeywords() {
        for (String keyword : new String[]{"perm", "permanent", "Forever", "INFINITE"}) {
            assertTrue(Durations.isPermanentKeyword(keyword), keyword);
            assertEquals(Optional.of(Length.PERMANENT), Length.parse(keyword));
        }
        assertFalse(Durations.isPermanentKeyword("never"));
    }

    @Test
    void numericTokensLookLikeDurationAttempts() {
        assertTrue(Durations.looksLikeDuration("10"));
        assertTrue(Durations.looksLikeDuration("1x"));
        assertTrue(Durations.looksLikeDuration("-1"));
        assertTrue(Durations.looksLikeDuration("7d"));
        assertFalse(Durations.looksLikeDuration("spam"));
        assertFalse(Durations.looksLikeDuration("x10"));
    }

    @Test
    void formatsUsingThreeLargestUnits() {
        assertEquals("1d 2h 30m", Durations.format(Duration.ofMinutes(24 * 60 + 150)));
        assertEquals("1y 1mo 1w", Durations.format(Duration.ofDays(365 + 30 + 7 + 1).plusHours(3)));
        assertEquals("45s", Durations.format(Duration.ofSeconds(45)));
        assertEquals("2h", Durations.format(Duration.ofHours(2)));
    }

    @Test
    void formatRoundsPartialSecondsUp() {
        assertEquals("1s", Durations.format(Duration.ofMillis(300)));
        assertEquals("1s", Durations.format(Duration.ZERO));
        assertEquals("1m 1s", Durations.format(Duration.ofMillis(60_001)));
    }

    @Test
    void formatUsesCustomUnits() {
        Durations.Units units = new Durations.Units(" years", " months", " weeks", " days", " hours", " minutes", " seconds");
        assertEquals("3 days 4 hours", Durations.format(Duration.ofHours(76), units));
    }

    @Test
    void lengthExpiryAndLimits() {
        Length week = Length.of(Duration.ofDays(7));
        assertEquals(1_000 + Duration.ofDays(7).toMillis(), week.expiresAt(1_000));
        assertEquals(null, Length.PERMANENT.expiresAt(1_000));
        assertTrue(week.fitsWithin(Duration.ofDays(7)));
        assertFalse(week.fitsWithin(Duration.ofDays(6)));
        assertFalse(Length.PERMANENT.fitsWithin(Duration.ofDays(36500)));
    }
}
