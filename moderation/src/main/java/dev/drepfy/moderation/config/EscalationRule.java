package dev.drepfy.moderation.config;

import dev.drepfy.moderation.model.Length;
import dev.drepfy.moderation.model.PunishmentType;

import java.util.Optional;

/**
 * Automatic punishment issued when a player reaches a number of active warnings.
 *
 * @param length explicit length, or empty to use the type's default
 */
public record EscalationRule(int warnings, PunishmentType type, Optional<Length> length) {

    /**
     * Parses a rule such as {@code "mute 1h"}, {@code "ban permanent"} or {@code "kick"}.
     *
     * @throws IllegalArgumentException with a readable message if the rule is invalid
     */
    public static EscalationRule parse(int warnings, String spec) {
        if (warnings <= 0) {
            throw new IllegalArgumentException("warning count must be positive");
        }
        String[] parts = spec.strip().split("\\s+");
        if (parts.length == 0 || parts[0].isEmpty() || parts.length > 2) {
            throw new IllegalArgumentException("expected \"<type> [duration]\" but got \"" + spec + "\"");
        }
        PunishmentType type = PunishmentType.fromKey(parts[0])
                .filter(t -> t != PunishmentType.WARN)
                .orElseThrow(() -> new IllegalArgumentException("unknown punishment type \"" + parts[0] + "\""));
        if (parts.length == 1) {
            return new EscalationRule(warnings, type, Optional.empty());
        }
        if (!type.timed()) {
            throw new IllegalArgumentException(type.key() + " doesn't take a duration");
        }
        Length length = Length.parse(parts[1])
                .orElseThrow(() -> new IllegalArgumentException("invalid duration \"" + parts[1] + "\""));
        return new EscalationRule(warnings, type, Optional.of(length));
    }
}
