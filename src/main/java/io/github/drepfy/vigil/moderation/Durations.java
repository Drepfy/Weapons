package io.github.drepfy.vigil.moderation;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses and formats punishment durations such as {@code 30d}, {@code 1h30m} or
 * {@code perm}.
 */
public final class Durations {

    /** Marker for a punishment that never expires. */
    public static final long PERMANENT = -1L;

    private static final long SECOND = 1000L;
    private static final long MINUTE = 60 * SECOND;
    private static final long HOUR = 60 * MINUTE;
    private static final long DAY = 24 * HOUR;
    private static final long WEEK = 7 * DAY;
    private static final long MONTH = 30 * DAY;
    private static final long YEAR = 365 * DAY;
    private static final long MAX = 100 * YEAR;

    private static final Set<String> PERMANENT_WORDS = Set.of("perm", "permanent", "forever", "never", "p");
    private static final Pattern FULL = Pattern.compile("(\\d{1,9}(mo|y|w|d|h|m|s))+");
    private static final Pattern PART = Pattern.compile("(\\d{1,9})(mo|y|w|d|h|m|s)");

    private Durations() {
    }

    /**
     * @return the duration in milliseconds, {@link #PERMANENT}, or {@code null} if the
     *         text is not a duration
     */
    public static Long parse(String text) {
        if (text == null) {
            return null;
        }
        String value = text.trim().toLowerCase(Locale.ROOT);
        if (PERMANENT_WORDS.contains(value)) {
            return PERMANENT;
        }
        if (!FULL.matcher(value).matches()) {
            return null;
        }
        long total = 0L;
        Matcher matcher = PART.matcher(value);
        while (matcher.find()) {
            long amount = Long.parseLong(matcher.group(1));
            long unit = switch (matcher.group(2)) {
                case "y" -> YEAR;
                case "mo" -> MONTH;
                case "w" -> WEEK;
                case "d" -> DAY;
                case "h" -> HOUR;
                case "m" -> MINUTE;
                default -> SECOND;
            };
            total += amount * unit;
            if (total > MAX) {
                return MAX;
            }
        }
        return total > 0 ? total : null;
    }

    /** Human readable duration, e.g. {@code 30 days} or {@code 1 hour 30 minutes}. */
    public static String format(long millis, String permanentText) {
        if (millis == PERMANENT) {
            return permanentText;
        }
        long remaining = Math.max(0L, millis);
        long[] units = {YEAR, DAY, HOUR, MINUTE, SECOND};
        String[] names = {"year", "day", "hour", "minute", "second"};
        StringBuilder text = new StringBuilder();
        int shown = 0;
        for (int i = 0; i < units.length && shown < 2; i++) {
            long amount = remaining / units[i];
            if (amount > 0) {
                if (text.length() > 0) {
                    text.append(' ');
                }
                text.append(amount).append(' ').append(names[i]).append(amount == 1 ? "" : "s");
                remaining -= amount * units[i];
                shown++;
            }
        }
        return text.length() == 0 ? "0 seconds" : text.toString();
    }
}
