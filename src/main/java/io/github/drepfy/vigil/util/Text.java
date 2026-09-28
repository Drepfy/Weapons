package io.github.drepfy.vigil.util;

import org.bukkit.ChatColor;

import java.util.Locale;

/**
 * Formatting helpers. Uses legacy colour codes so the plugin works on Spigot and Paper.
 */
public final class Text {

    private Text() {
    }

    public static String color(String text) {
        return text == null ? "" : ChatColor.translateAlternateColorCodes('&', text);
    }

    /** Replaces {key} placeholders; arguments are key/value pairs. */
    public static String replace(String template, Object... pairs) {
        if (template == null) {
            return "";
        }
        String result = template;
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            result = result.replace("{" + pairs[i] + "}", String.valueOf(pairs[i + 1]));
        }
        return result;
    }

    /** Compact decimal: at most two decimals, no trailing zeros. */
    public static String num(double value) {
        if (!Double.isFinite(value)) {
            return String.valueOf(value);
        }
        String text = String.format(Locale.ROOT, "%.2f", value);
        if (text.contains(".")) {
            text = text.replaceAll("0+$", "");
            if (text.endsWith(".")) {
                text = text.substring(0, text.length() - 1);
            }
        }
        return text.equals("-0") ? "0" : text;
    }

    /** Human readable duration such as {@code 3m 12s}. */
    public static String duration(long millis) {
        long seconds = Math.max(0L, millis / 1000L);
        long days = seconds / 86_400;
        long hours = (seconds % 86_400) / 3600;
        long minutes = (seconds % 3600) / 60;
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
