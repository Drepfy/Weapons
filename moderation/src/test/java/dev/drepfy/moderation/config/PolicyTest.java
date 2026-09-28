package dev.drepfy.moderation.config;

import dev.drepfy.moderation.model.Length;
import dev.drepfy.moderation.model.PunishmentType;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PolicyTest {

    private final DurationLimits limits = new DurationLimits(List.of(
            new DurationLimits.Tier("helper", "limits.helper",
                    Map.of(PunishmentType.BAN, Duration.ofDays(1), PunishmentType.MUTE, Duration.ofHours(6))),
            new DurationLimits.Tier("moderator", "limits.moderator",
                    Map.of(PunishmentType.BAN, Duration.ofDays(30)))));

    @Test
    void staffWithoutTierAreNotCapped() {
        assertEquals(Optional.empty(), limits.maxFor(PunishmentType.BAN, permission -> false));
    }

    @Test
    void tierCapsApply() {
        assertEquals(Optional.of(Duration.ofDays(1)), limits.maxFor(PunishmentType.BAN, Set.of("limits.helper")::contains));
        assertEquals(Optional.of(Duration.ofHours(6)), limits.maxFor(PunishmentType.MUTE, Set.of("limits.helper")::contains));
    }

    @Test
    void mostGenerousTierWins() {
        Set<String> both = Set.of("limits.helper", "limits.moderator");
        assertEquals(Optional.of(Duration.ofDays(30)), limits.maxFor(PunishmentType.BAN, both::contains));
        // The moderator tier doesn't cap mutes at all, so a helper+moderator isn't capped either.
        assertEquals(Optional.empty(), limits.maxFor(PunishmentType.MUTE, both::contains));
    }

    @Test
    void parsesEscalationRules() {
        assertEquals(new EscalationRule(3, PunishmentType.MUTE, Optional.of(Length.of(Duration.ofHours(1)))),
                EscalationRule.parse(3, "mute 1h"));
        assertEquals(new EscalationRule(5, PunishmentType.BAN, Optional.of(Length.PERMANENT)),
                EscalationRule.parse(5, "ban permanent"));
        assertEquals(new EscalationRule(2, PunishmentType.VOICE_MUTE, Optional.empty()),
                EscalationRule.parse(2, "voice-mute"));
        assertEquals(new EscalationRule(4, PunishmentType.KICK, Optional.empty()), EscalationRule.parse(4, "kick"));
    }

    @Test
    void rejectsInvalidEscalationRules() {
        assertThrows(IllegalArgumentException.class, () -> EscalationRule.parse(3, "warn 1d"));
        assertThrows(IllegalArgumentException.class, () -> EscalationRule.parse(3, "jail 1d"));
        assertThrows(IllegalArgumentException.class, () -> EscalationRule.parse(3, "mute 1x"));
        assertThrows(IllegalArgumentException.class, () -> EscalationRule.parse(3, "kick 1h"));
        assertThrows(IllegalArgumentException.class, () -> EscalationRule.parse(3, "mute 1h extra"));
        assertThrows(IllegalArgumentException.class, () -> EscalationRule.parse(0, "mute 1h"));
        assertThrows(IllegalArgumentException.class, () -> EscalationRule.parse(3, "  "));
    }
}
