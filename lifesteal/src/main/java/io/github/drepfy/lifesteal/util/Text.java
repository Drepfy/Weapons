package io.github.drepfy.lifesteal.util;

import org.bukkit.ChatColor;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Colour codes ({@code &c}, {@code &#RRGGBB}) and placeholders. */
public final class Text {

    private static final Pattern HEX = Pattern.compile("&#([0-9A-Fa-f]{6})");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z-]+)\\}");

    private Text() {
    }

    public static String color(String text) {
        if (text == null) {
            return "";
        }
        String result = text;
        if (result.contains("&#")) {
            Matcher matcher = HEX.matcher(result);
            StringBuilder out = new StringBuilder();
            while (matcher.find()) {
                StringBuilder hex = new StringBuilder("§x");
                for (char digit : matcher.group(1).toLowerCase(java.util.Locale.ROOT).toCharArray()) {
                    hex.append('§').append(digit);
                }
                matcher.appendReplacement(out, Matcher.quoteReplacement(hex.toString()));
            }
            matcher.appendTail(out);
            result = out.toString();
        }
        return ChatColor.translateAlternateColorCodes('&', result);
    }

    /**
     * Colours the template, then fills in {@code {placeholders}} in one pass, so names
     * typed by players are shown as typed.
     */
    public static String format(String template, Object... pairs) {
        Matcher matcher = PLACEHOLDER.matcher(color(template));
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String value = null;
            for (int i = 0; i + 1 < pairs.length; i += 2) {
                if (matcher.group(1).equals(pairs[i])) {
                    value = String.valueOf(pairs[i + 1]);
                    break;
                }
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(value != null ? value : matcher.group()));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** "" for one, "s" otherwise (for "heart{s}"). */
    public static String plural(long count) {
        return count == 1 ? "" : "s";
    }
}
