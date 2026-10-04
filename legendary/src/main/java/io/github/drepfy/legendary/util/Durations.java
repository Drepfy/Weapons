package io.github.drepfy.legendary.util;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Durations such as {@code 8s}, {@code 2.5s}, {@code 1m30s}, {@code 30d}. A plain number is seconds. */
public final class Durations {

    private static final Pattern PART = Pattern.compile("(\\d+(?:\\.\\d+)?)([smhd]?)");
    private static final double CAP = 100.0 * 365 * 24 * 3600;

    private Durations() {
    }

    /** @return seconds, or {@code null} if the text is not a duration */
    public static Double seconds(String text) {
        if (text == null) {
            return null;
        }
        String input = text.trim().toLowerCase(Locale.ROOT).replace(" ", "");
        if (input.isEmpty()) {
            return null;
        }
        Matcher matcher = PART.matcher(input);
        double total = 0;
        int end = 0;
        int parts = 0;
        while (matcher.find()) {
            if (matcher.start() != end || matcher.end() == matcher.start()) {
                return null;
            }
            end = matcher.end();
            parts++;
            double amount = Double.parseDouble(matcher.group(1));
            double unit = switch (matcher.group(2)) {
                case "m" -> 60;
                case "h" -> 3600;
                case "d" -> 86_400;
                default -> 1;
            };
            if (matcher.group(2).isEmpty() && end != input.length()) {
                return null; // "5 10s"
            }
            total = Math.min(CAP, total + amount * unit);
        }
        return end == input.length() && parts > 0 ? total : null;
    }

    /** {@code 3d 2h}, {@code 1h 5m}, {@code 12m 30s}, {@code 8s}. */
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
