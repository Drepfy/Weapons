package io.github.drepfy.combat.util;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Durations such as {@code 30m}, {@code 1h30m}, {@code 7d}. */
public final class Durations {

    private static final Pattern PART = Pattern.compile("(\\d+)(mo|[smhdwy])");
    private static final long CAP = 100L * 365 * 24 * 3600 * 1000;

    private Durations() {
    }

    /** @return milliseconds, or {@code null} if the text is not a duration */
    public static Long parse(String text) {
        if (text == null) {
            return null;
        }
        String input = text.trim().toLowerCase(Locale.ROOT);
        if (input.isEmpty()) {
            return null;
        }
        if (input.equals("0")) {
            return 0L;
        }
        Matcher matcher = PART.matcher(input);
        long total = 0;
        int end = 0;
        while (matcher.find()) {
            if (matcher.start() != end) {
                return null;
            }
            end = matcher.end();
            long amount;
            try {
                amount = Long.parseLong(matcher.group(1));
            } catch (NumberFormatException e) {
                return CAP;
            }
            long unit = switch (matcher.group(2)) {
                case "s" -> 1000L;
                case "m" -> 60_000L;
                case "h" -> 3_600_000L;
                case "d" -> 86_400_000L;
                case "w" -> 604_800_000L;
                case "mo" -> 2_592_000_000L;
                default -> 31_536_000_000L;
            };
            total = amount > CAP / unit ? CAP : Math.min(CAP, total + amount * unit);
        }
        return end == input.length() && end > 0 ? total : null;
    }

    /** {@code 12m 30s}, {@code 1h 5m}, {@code 3d 2h}. */
    public static String format(long millis) {
        long seconds = Math.max(0L, (millis + 999) / 1000);
        long days = seconds / 86_400;
        long hours = seconds % 86_400 / 3600;
        long minutes = seconds % 3600 / 60;
        long secs = seconds % 60;
        if (days > 0) {
            return days + "d " + hours + "h";
        }
        if (hours > 0) {
            return hours + "h " + minutes + "m";
        }
        if (minutes > 0) {
            return minutes + "m " + secs + "s";
        }
        return secs + "s";
    }
}
