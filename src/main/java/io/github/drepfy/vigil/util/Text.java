package io.github.drepfy.vigil.util;

import org.bukkit.ChatColor;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Formatting helpers. Uses legacy colour codes so the plugin works on Spigot and Paper.
 *
 * <p>Besides the classic {@code &c}/{@code &l} codes, messages may use hex colours
 * ({@code &#FF8800}) and gradients ({@code <gradient:#FF3C3C:#FFA53C>text</gradient>},
 * two or more colours). Both become the {@code §x§R§R§G§G§B§B} form that chat, titles,
 * action bars and kick screens understand (Minecraft 1.16+).
 */
public final class Text {

    private static final Pattern GRADIENT = Pattern.compile(
            "<gradient:(#[0-9A-Fa-f]{6}(?::#[0-9A-Fa-f]{6})+)>(.*?)</gradient>", Pattern.DOTALL);
    private static final Pattern HEX = Pattern.compile("&#([0-9A-Fa-f]{6})");

    private Text() {
    }

    public static String color(String text) {
        if (text == null) {
            return "";
        }
        String result = text;
        if (result.contains("<gradient:")) {
            result = gradients(result);
        }
        if (result.contains("&#")) {
            result = hexColors(result);
        }
        return ChatColor.translateAlternateColorCodes('&', result);
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

    /** Removes colour codes, hex colours and gradient tags (for logs and Discord). */
    public static String strip(String text) {
        if (text == null) {
            return "";
        }
        String result = text.replaceAll("</?gradient[^>]*>", "").replaceAll("&#[0-9A-Fa-f]{6}", "");
        return result.replaceAll("[&§][0-9a-fk-orxA-FK-ORX]", "");
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

    // ---- hex colours and gradients ------------------------------------------------------------

    private static String hexColors(String text) {
        Matcher matcher = HEX.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(out, Matcher.quoteReplacement(hex(Integer.parseInt(matcher.group(1), 16))));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String gradients(String text) {
        Matcher matcher = GRADIENT.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String[] stops = matcher.group(1).split(":");
            int[] colors = new int[stops.length];
            for (int i = 0; i < stops.length; i++) {
                colors[i] = Integer.parseInt(stops[i].substring(1), 16);
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(gradient(matcher.group(2), colors)));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /**
     * Colours every visible character of {@code content} along the gradient. Formatting codes
     * inside ({@code &l}, {@code &o}...) are kept and re-applied after each colour, because a
     * colour code resets formatting in Minecraft; colour codes inside are ignored.
     */
    static String gradient(String content, int[] colors) {
        int visible = 0;
        for (int i = 0; i < content.length(); ) {
            int skip = codeLength(content, i);
            if (skip > 0) {
                i += skip;
            } else {
                visible++;
                i += Character.charCount(content.codePointAt(i));
            }
        }
        StringBuilder out = new StringBuilder();
        StringBuilder formats = new StringBuilder();
        int index = 0;
        for (int i = 0; i < content.length(); ) {
            int skip = codeLength(content, i);
            if (skip > 0) {
                char code = Character.toLowerCase(content.charAt(i + 1));
                if (skip == 2 && code >= 'k' && code <= 'o') {
                    formats.append('§').append(code);
                } else if (skip == 2 && code == 'r') {
                    formats.setLength(0);
                }
                i += skip;
                continue;
            }
            int codePoint = content.codePointAt(i);
            double t = visible <= 1 ? 0.0 : (double) index / (visible - 1);
            out.append(hex(interpolate(colors, t))).append(formats).appendCodePoint(codePoint);
            index++;
            i += Character.charCount(codePoint);
        }
        return out.toString();
    }

    /** Length of a colour/format code starting at {@code i} ({@code &c}, {@code §l}, {@code &#RRGGBB}), or 0. */
    private static int codeLength(String text, int i) {
        char c = text.charAt(i);
        if ((c != '&' && c != '§') || i + 1 >= text.length()) {
            return 0;
        }
        char next = text.charAt(i + 1);
        if (next == '#' && i + 8 <= text.length() && text.substring(i + 2, i + 8).matches("[0-9A-Fa-f]{6}")) {
            return 8;
        }
        return "0123456789abcdefklmnorABCDEFKLMNOR".indexOf(next) >= 0 ? 2 : 0;
    }

    private static int interpolate(int[] colors, double t) {
        double position = Math.max(0.0, Math.min(1.0, t)) * (colors.length - 1);
        int segment = Math.min((int) position, colors.length - 2);
        double local = position - segment;
        int from = colors[segment];
        int to = colors[segment + 1];
        int r = (int) Math.round(((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * local);
        int g = (int) Math.round(((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * local);
        int b = (int) Math.round((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * local);
        return (r << 16) | (g << 8) | b;
    }

    /** {@code §x§r§r§g§g§b§b}, the legacy form of a hex colour. */
    private static String hex(int rgb) {
        String digits = String.format(Locale.ROOT, "%06x", rgb & 0xFFFFFF);
        StringBuilder out = new StringBuilder("§x");
        for (char digit : digits.toCharArray()) {
            out.append('§').append(digit);
        }
        return out.toString();
    }
}
