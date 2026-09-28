package dev.drepfy.moderation.config;

import dev.drepfy.moderation.model.PunishmentType;
import dev.drepfy.moderation.util.Durations;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * messages.yml, flattened into an immutable map at load time so messages can be rendered safely
 * from chat and voice threads. Missing keys fall back to the defaults bundled in the jar.
 */
public final class Messages {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final Map<String, String> templates;
    private final TagResolver globals;
    private final Durations.Units units;
    private final Logger logger;
    private final Set<String> reportedMissing = ConcurrentHashMap.newKeySet();

    private Messages(Map<String, String> templates, Logger logger) {
        this.templates = Map.copyOf(templates);
        this.logger = logger;
        this.globals = TagResolver.resolver(
                Placeholder.parsed("prefix", templates.getOrDefault("prefix", "")),
                Placeholder.parsed("staff_prefix", templates.getOrDefault("staff-prefix", "")));
        this.units = new Durations.Units(
                templates.getOrDefault("time.units.year", "y"),
                templates.getOrDefault("time.units.month", "mo"),
                templates.getOrDefault("time.units.week", "w"),
                templates.getOrDefault("time.units.day", "d"),
                templates.getOrDefault("time.units.hour", "h"),
                templates.getOrDefault("time.units.minute", "m"),
                templates.getOrDefault("time.units.second", "s"));
    }

    /**
     * @param file     the server's messages.yml
     * @param defaults the messages.yml bundled in the jar
     */
    public static Messages load(File file, InputStream defaults, Logger logger) {
        Map<String, String> templates = new HashMap<>();
        if (defaults != null) {
            flatten(YamlConfiguration.loadConfiguration(new InputStreamReader(defaults, StandardCharsets.UTF_8)), templates);
        }
        // The server's file wins; anything it lacks keeps the bundled default.
        flatten(YamlConfiguration.loadConfiguration(file), templates);
        return new Messages(templates, logger);
    }

    private static void flatten(ConfigurationSection config, Map<String, String> into) {
        for (String key : config.getKeys(true)) {
            if (config.isConfigurationSection(key)) {
                continue;
            }
            if (config.isList(key)) {
                List<String> lines = config.getStringList(key);
                into.put(key, String.join("<newline>", lines));
            } else {
                into.put(key, config.getString(key, ""));
            }
        }
    }

    /** The raw template for a key, or an empty string if it's missing or disabled. */
    public String raw(String key) {
        String template = templates.get(key);
        if (template == null) {
            if (reportedMissing.add(key)) {
                logger.warning("messages.yml is missing \"" + key + "\"");
            }
            return "";
        }
        return template;
    }

    /** Plain-text value (e.g. names and default reasons stored in the database). */
    public String plain(String key) {
        return raw(key).strip();
    }

    /** Whether the message is enabled (not blank). */
    public boolean enabled(String key) {
        return !raw(key).isBlank();
    }

    public Component render(String key, TagResolver... placeholders) {
        String template = raw(key);
        if (template.isBlank()) {
            return Component.empty();
        }
        return MINI.deserialize(template, TagResolver.resolver(globals, TagResolver.resolver(placeholders)));
    }

    /** Sends a message unless it is disabled. */
    public void send(Audience audience, String key, TagResolver... placeholders) {
        if (enabled(key)) {
            audience.sendMessage(render(key, placeholders));
        }
    }

    public Durations.Units units() {
        return units;
    }

    public String typeName(PunishmentType type) {
        String name = plain("types." + type.key());
        return name.isEmpty() ? type.key() : name;
    }
}
