package com.drepfy.staffvanish;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
 * Renders the MiniMessage strings in the {@code messages} section of the config.
 * <p>
 * A message may be a single string or a list of lines. An empty string disables it. Every message can use
 * {@code <prefix>}.
 */
public final class Messages {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private final ConfigurationSection section;
    private final TagResolver prefix;

    public Messages(ConfigurationSection section) {
        this.section = section;
        this.prefix = Placeholder.component("prefix", MINI_MESSAGE.deserialize(string("prefix")));
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

    /** Parses an arbitrary MiniMessage string, with {@code <prefix>} available. */
    public Component parse(String miniMessage, TagResolver... resolvers) {
        return MINI_MESSAGE.deserialize(miniMessage, TagResolver.resolver(prefix, TagResolver.resolver(resolvers)));
    }

    /**
     * Reads a message, falling back to the bundled default. (Passing a default to {@code getString} would skip the
     * bundled one, so messages added in an update would come out empty for existing configs.)
     */
    private String string(String key) {
        return Objects.requireNonNullElse(section.getString(key), "");
    }

    /** Parses item text, which Minecraft would otherwise show in italics. */
    public Component parseItemText(String miniMessage, TagResolver... resolvers) {
        return parse(miniMessage, resolvers).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }
}
