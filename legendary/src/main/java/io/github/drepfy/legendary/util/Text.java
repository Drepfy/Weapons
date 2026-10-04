package io.github.drepfy.legendary.util;

import org.bukkit.ChatColor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Colour codes ({@code &c}, {@code &#RRGGBB}), gradients
 * ({@code <gradient:#RRGGBB:#RRGGBB>text</gradient>}) and {@code {placeholders}}.
 */
public final class Text {

    private static final Pattern GRADIENT = Pattern.compile(
            "<gradient((?::#[0-9A-Fa-f]{6}){2,})>(.*?)</gradient>", Pattern.DOTALL);
    private static final Pattern HEX = Pattern.compile("&#([0-9A-Fa-f]{6})");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z0-9.-]+)\\}");
    private static final String FORMATS = "klmnoKLMNO";

    private Text() {
    }

    public static String color(String text) {
        if (text == null) {
            return "";
        }
        String result = text;
        if (result.contains("<gradient")) {
            Matcher matcher = GRADIENT.matcher(result);
            StringBuilder out = new StringBuilder();
            while (matcher.find()) {
                List<int[]> stops = new ArrayList<>();
                for (String hex : matcher.group(1).substring(1).split(":")) {
                    stops.add(rgb(hex.substring(1)));
                }
                matcher.appendReplacement(out, Matcher.quoteReplacement(gradient(matcher.group(2), stops)));
            }
            matcher.appendTail(out);
            result = out.toString();
        }
        if (result.contains("&#")) {
            Matcher matcher = HEX.matcher(result);
            StringBuilder out = new StringBuilder();
            while (matcher.find()) {
                matcher.appendReplacement(out, Matcher.quoteReplacement(hex(rgb(matcher.group(1)))));
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

    public static String strip(String text) {
        String stripped = ChatColor.stripColor(color(text));
        return stripped == null ? "" : stripped;
    }

    /** Each visible character gets its own colour; {@code &l} and the like inside are kept. */
    static String gradient(String inner, List<int[]> stops) {
        List<Character> chars = new ArrayList<>();
        List<String> formats = new ArrayList<>();
        StringBuilder active = new StringBuilder();
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if ((c == '&' || c == '§') && i + 1 < inner.length()) {
                char code = inner.charAt(i + 1);
                if (FORMATS.indexOf(code) >= 0) {
                    active.append('§').append(Character.toLowerCase(code));
                    i++;
                    continue;
                }
                if (code == 'r' || code == 'R') {
                    active.setLength(0);
                    i++;
                    continue;
                }
            }
            chars.add(c);
            formats.add(active.toString());
        }
        StringBuilder out = new StringBuilder();
        int count = chars.size();
        for (int i = 0; i < count; i++) {
            double t = count == 1 ? 0.0 : (double) i / (count - 1);
            out.append(hex(blend(stops, t))).append(formats.get(i)).append(chars.get(i));
        }
        return out.toString();
    }

    private static int[] blend(List<int[]> stops, double t) {
        double scaled = t * (stops.size() - 1);
        int index = Math.min((int) scaled, stops.size() - 2);
        double local = scaled - index;
        int[] a = stops.get(index);
        int[] b = stops.get(index + 1);
        return new int[] {
                (int) Math.round(a[0] + (b[0] - a[0]) * local),
                (int) Math.round(a[1] + (b[1] - a[1]) * local),
                (int) Math.round(a[2] + (b[2] - a[2]) * local)};
    }

    private static int[] rgb(String hex) {
        int value = Integer.parseInt(hex, 16);
        return new int[] {(value >> 16) & 0xFF, (value >> 8) & 0xFF, value & 0xFF};
    }

    private static String hex(int[] rgb) {
        String digits = String.format(Locale.ROOT, "%02x%02x%02x", rgb[0], rgb[1], rgb[2]);
        StringBuilder out = new StringBuilder("§x");
        for (char digit : digits.toCharArray()) {
            out.append('§').append(digit);
        }
        return out.toString();
    }

    /** "8s", "2.5s", "1m 30s". */
    public static String seconds(double seconds) {
        if (seconds >= 60) {
            long whole = Math.round(seconds);
            return whole % 60 == 0 ? whole / 60 + "m" : whole / 60 + "m " + whole % 60 + "s";
        }
        return number(seconds) + "s";
    }

    /** A countdown: one decimal under 10 seconds ("4.2s"), whole seconds above ("12s"). */
    public static String countdown(long ticks) {
        double seconds = Math.max(0, ticks) / 20.0;
        if (seconds < 10) {
            return String.format(Locale.ROOT, "%.1fs", Math.max(0.1, Math.ceil(seconds * 10) / 10));
        }
        return (long) Math.ceil(seconds) + "s";
    }

    /** 6.0 → "6", 2.5 → "2.5". */
    public static String number(double value) {
        if (value == Math.rint(value)) {
            return Long.toString((long) value);
        }
        String text = String.format(Locale.ROOT, "%.2f", value);
        while (text.endsWith("0")) {
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }
}
