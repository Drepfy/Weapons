package dev.drepfy.moderation.util;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses and formats punishment durations such as {@code 30m}, {@code 1d12h} or {@code permanent}.
 */
public final class Durations {

    /** Longest accepted duration. Anything longer should be issued as permanent. */
    public static final Duration MAX = Duration.ofDays(365L * 100);

    private static final Set<String> PERMANENT_KEYWORDS = Set.of("perm", "permanent", "forever", "infinite");
    private static final Pattern WHOLE = Pattern.compile("(?:\\d{1,9}[a-z]+)+");
    private static final Pattern PART = Pattern.compile("(\\d{1,9})([a-z]+)");
    private static final Pattern ATTEMPT = Pattern.compile("^-?\\d.*");

    private static final long SECOND = 1;
    private static final long MINUTE = 60 * SECOND;
    private static final long HOUR = 60 * MINUTE;
    private static final long DAY = 24 * HOUR;
    private static final long WEEK = 7 * DAY;
    private static final long MONTH = 30 * DAY;
    private static final long YEAR = 365 * DAY;

    private static final Map<String, Long> UNITS = Map.ofEntries(
            Map.entry("s", SECOND), Map.entry("sec", SECOND), Map.entry("secs", SECOND),
            Map.entry("second", SECOND), Map.entry("seconds", SECOND),
            Map.entry("m", MINUTE), Map.entry("min", MINUTE), Map.entry("mins", MINUTE),
            Map.entry("minute", MINUTE), Map.entry("minutes", MINUTE),
            Map.entry("h", HOUR), Map.entry("hr", HOUR), Map.entry("hrs", HOUR),
            Map.entry("hour", HOUR), Map.entry("hours", HOUR),
            Map.entry("d", DAY), Map.entry("day", DAY), Map.entry("days", DAY),
            Map.entry("w", WEEK), Map.entry("wk", WEEK), Map.entry("wks", WEEK),
            Map.entry("week", WEEK), Map.entry("weeks", WEEK),
            Map.entry("mo", MONTH), Map.entry("mon", MONTH), Map.entry("month", MONTH), Map.entry("months", MONTH),
            Map.entry("y", YEAR), Map.entry("yr", YEAR), Map.entry("yrs", YEAR),
            Map.entry("year", YEAR), Map.entry("years", YEAR));

    private Durations() {
    }

    public static boolean isPermanentKeyword(String input) {
        return PERMANENT_KEYWORDS.contains(input.toLowerCase(Locale.ROOT));
    }

    /**
     * Whether a token was probably meant as a duration. Command parsing treats such tokens as a
     * duration even when they fail to parse, so a typo like {@code 10} or {@code 1x} is reported
     * instead of silently becoming part of the reason (and leaving the punishment at the default,
     * possibly permanent, length).
     */
    public static boolean looksLikeDuration(String input) {
        return ATTEMPT.matcher(input).matches();
    }

    /**
     * Parses a positive, finite duration. Returns empty for anything invalid, including zero,
     * overflowing values and values longer than {@link #MAX}.
     */
    public static Optional<Duration> parse(String input) {
        String text = input.toLowerCase(Locale.ROOT);
        if (!WHOLE.matcher(text).matches()) {
            return Optional.empty();
        }
        long totalSeconds = 0;
        Matcher matcher = PART.matcher(text);
        try {
            while (matcher.find()) {
                Long unit = UNITS.get(matcher.group(2));
                if (unit == null) {
                    return Optional.empty();
                }
                long amount = Long.parseLong(matcher.group(1));
                totalSeconds = Math.addExact(totalSeconds, Math.multiplyExact(amount, unit));
            }
        } catch (ArithmeticException overflow) {
            return Optional.empty();
        }
        if (totalSeconds <= 0 || totalSeconds > MAX.toSeconds()) {
            return Optional.empty();
        }
        return Optional.of(Duration.ofSeconds(totalSeconds));
    }

    /**
     * Unit labels used when formatting, e.g. {@code "d"} or {@code " days"}.
     */
    public record Units(String year, String month, String week, String day, String hour, String minute, String second) {
        public static final Units DEFAULT = new Units("y", "mo", "w", "d", "h", "m", "s");
    }

    /**
     * Formats a length of time using at most three of the largest non-zero units, e.g.
     * {@code 1d 2h 30m}. Partial seconds round up, so a mute with 300ms left shows as {@code 1s}.
     */
    public static String format(Duration duration, Units units) {
        long seconds = Math.max(1, (duration.toMillis() + 999) / 1000);
        long[] sizes = {YEAR, MONTH, WEEK, DAY, HOUR, MINUTE, SECOND};
        String[] labels = {units.year(), units.month(), units.week(), units.day(), units.hour(), units.minute(), units.second()};
        List<String> parts = new ArrayList<>(3);
        for (int i = 0; i < sizes.length && parts.size() < 3; i++) {
            long amount = seconds / sizes[i];
            if (amount > 0) {
                parts.add(amount + labels[i]);
                seconds -= amount * sizes[i];
            }
        }
        return String.join(" ", parts);
    }

    public static String format(Duration duration) {
        return format(duration, Units.DEFAULT);
    }
}
