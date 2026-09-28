package dev.drepfy.moderation.model;

import dev.drepfy.moderation.util.Durations;

import java.time.Duration;
import java.util.Optional;

/**
 * How long a punishment lasts: either permanent or a fixed duration.
 *
 * @param duration the duration, or {@code null} for permanent
 */
public record Length(Duration duration) {

    public static final Length PERMANENT = new Length(null);

    public Length {
        if (duration != null && (duration.isNegative() || duration.isZero())) {
            throw new IllegalArgumentException("Punishment length must be positive: " + duration);
        }
    }

    public static Length of(Duration duration) {
        return new Length(duration);
    }

    /** Parses {@code permanent} (and synonyms) or a duration such as {@code 7d}. */
    public static Optional<Length> parse(String input) {
        if (Durations.isPermanentKeyword(input)) {
            return Optional.of(PERMANENT);
        }
        return Durations.parse(input).map(Length::of);
    }

    public boolean permanent() {
        return duration == null;
    }

    /** Expiry timestamp for a punishment issued at {@code now}, or {@code null} if permanent. */
    public Long expiresAt(long now) {
        return duration == null ? null : now + duration.toMillis();
    }

    /** Whether this length is no longer than {@code limit}. Permanent exceeds every finite limit. */
    public boolean fitsWithin(Duration limit) {
        return duration != null && duration.compareTo(limit) <= 0;
    }
}
