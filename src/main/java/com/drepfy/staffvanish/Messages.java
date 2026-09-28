package com.drepfy.staffvanish;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.ConfigurationSection;
import org.jspecify.annotations.Nullable;

/**
 * Renders the text in the {@code messages} section of the config.
 * <p>
 * Text may use MiniMessage tags and {@code &}-colour codes. A message may be a single string or a list of lines. An
 * empty string disables it. Every message can use {@code <prefix>}.
 */
public final class Messages {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final Pattern COLOR_CODE = Pattern.compile("&(?:#([0-9a-fA-F]{6})|([0-9a-fA-Fk-oK-OrR]))");
    private static final String[] COLORS = {
            "black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple", "gold", "gray",
            "dark_gray", "blue", "green", "aqua", "red", "light_purple", "yellow", "white"
    };
    private static final Map<Character, String> DECORATIONS = Map.of(
            'k', "obfuscated", 'l', "bold", 'm', "strikethrough", 'n', "underlined", 'o', "italic");

    private final ConfigurationSection section;
    private final TagResolver prefix;

    public Messages(ConfigurationSection section) {
        this.section = section;
        this.prefix = Placeholder.component("prefix", MINI_MESSAGE.deserialize(translateColorCodes(string("prefix"))));
    }

    /**
     * @return the rendered message, or {@code null} if it is disabled
     */
    public @Nullable Component get(String key, TagResolver... resolvers) {
        List<Component> lines = lines(key, resolvers);
        return lines.isEmpty() ? null : Component.join(JoinConfiguration.newlines(), lines);
    }

    public List<Component> lines(String key, TagResolver... resolvers) {
        List<String> raw = section.isList(key) ? section.getStringList(key) : List.of(string(key));
        List<Component> lines = new ArrayList<>(raw.size());
        for (String line : raw) {
            if (!line.isEmpty() || raw.size() > 1) {
                lines.add(parse(line, resolvers));
            }
        }
        return lines;
    }

    public void send(Audience audience, String key, TagResolver... resolvers) {
        for (Component line : lines(key, resolvers)) {
            audience.sendMessage(line);
        }
    }

    /** Parses text from the config, with {@code <prefix>} available. */
    public Component parse(String text, TagResolver... resolvers) {
        return MINI_MESSAGE.deserialize(translateColorCodes(text),
                TagResolver.resolver(prefix, TagResolver.resolver(resolvers)));
    }

    /** Parses item text, which Minecraft would otherwise show in italics. */
    public Component parseItemText(String text, TagResolver... resolvers) {
        return parse(text, resolvers).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    /**
     * Turns {@code &}-colour codes ({@code &b}, {@code &l}, {@code &#55ffff}, ...) into MiniMessage tags. As in chat,
     * a colour code ends any formatting (bold, italic, ...) that came before it.
     */
    static String translateColorCodes(String text) {
        if (text.indexOf('&') < 0) {
            return text;
        }
        Matcher matcher = COLOR_CODE.matcher(text);
        List<String> openDecorations = new ArrayList<>();
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            StringBuilder tags = new StringBuilder();
            char code = matcher.group(2) == null ? '#' : Character.toLowerCase(matcher.group(2).charAt(0));
            String decoration = DECORATIONS.get(code);
            if (decoration != null) {
                openDecorations.add(decoration);
                tags.append('<').append(decoration).append('>');
            } else {
                for (int i = openDecorations.size() - 1; i >= 0; i--) {
                    tags.append("</").append(openDecorations.get(i)).append('>');
                }
                openDecorations.clear();
                if (code == 'r') {
                    tags.append("<reset>");
                } else if (code == '#') {
                    tags.append("<#").append(matcher.group(1)).append('>');
                } else {
                    tags.append('<').append(COLORS[Character.digit(code, 16)]).append('>');
                }
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(tags.toString()));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /**
     * Reads a message, falling back to the bundled default. (Passing a default to {@code getString} would skip the
     * bundled one, so messages added in an update would come out empty for existing configs.)
     */
    private String string(String key) {
        return Objects.requireNonNullElse(section.getString(key), "");
    }
}
