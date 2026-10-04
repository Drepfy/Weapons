package io.github.drepfy.combat.config;

import io.github.drepfy.combat.util.Durations;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Reads config.yml. A wrong value is replaced by its default and reported, never fatal. */
public final class SettingsLoader {

    static final Map<String, String> DEFAULT_MESSAGES;

    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("prefix", "&b&lᴠᴀɴɪʟʟᴀ sᴍᴘ » &r");
        // Above the hotbar while in combat. {seconds} = seconds left.
        m.put("action-bar", "&c⚔ Combat: &f{seconds}s");
        m.put("combat-start", "&cYou are in combat. Logging out will not end it.");
        m.put("combat-end", "&aYou are no longer in combat.");
        m.put("combat-rejoin", "&cYou are still in combat: &f{seconds}s&c left.");
        m.put("combat-logout-kill", "&c{player} logged out during combat and died.");
        m.put("pearl-cooldown", "&cYou can use an Ender Pearl again in &f{seconds}s&c.");
        m.put("status-combat", "&7Combat: &c{seconds}s left");
        m.put("status-no-combat", "&7Combat: &anot in combat");
        m.put("status-pearl", "&7Ender Pearl: &c{seconds}s");
        m.put("status-pearl-ready", "&7Ender Pearl: &aready");
        m.put("no-permission", "&cYou do not have permission to do that.");
        m.put("player-not-found", "&cPlayer not found: &f{player}");
        m.put("reloaded", "&aCombat reloaded. &7({warnings} warning(s), see console)");
        DEFAULT_MESSAGES = java.util.Collections.unmodifiableMap(m);
    }

    private SettingsLoader() {
    }

    public static Settings load(ConfigurationSection root) {
        List<String> warnings = new ArrayList<>();
        long combat = duration(root, "combat.duration", 60_000L, 1_000L, warnings);
        Settings.ArmorRule armor = choice(root, "combat.armor", Settings.ArmorRule.ARMOR_PIECES, warnings);
        Settings.LogoutRule logout = choice(root, "combat.logout", Settings.LogoutRule.KEEP, warnings);
        long pearl = duration(root, "ender-pearl.cooldown", 15_000L, 0L, warnings);
        boolean overlay = bool(root, "ender-pearl.show-on-item", true, warnings);
        Map<String, String> messages = new HashMap<>();
        for (Map.Entry<String, String> entry : DEFAULT_MESSAGES.entrySet()) {
            Object value = root.get("messages." + entry.getKey());
            messages.put(entry.getKey(), value == null ? entry.getValue() : value.toString());
        }
        Object prefix = root.get("prefix");
        if (prefix != null) {
            messages.put("prefix", prefix.toString());
        }
        return new Settings(combat, armor, logout, pearl, overlay, new Settings.Messages(messages), warnings);
    }

    private static long duration(ConfigurationSection root, String path, long def, long min, List<String> warnings) {
        Object value = root.get(path);
        if (value == null) {
            return def;
        }
        Long parsed = Durations.parse(value.toString());
        if (parsed == null || parsed < min) {
            warnings.add(path + " must be a time such as 15s, 60s or 2m (got '" + value + "'); using "
                    + Durations.format(def) + ".");
            return def;
        }
        return parsed;
    }

    private static boolean bool(ConfigurationSection root, String path, boolean def, List<String> warnings) {
        Object value = root.get(path);
        if (value == null) {
            return def;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        warnings.add(path + " must be true or false (got '" + value + "'); using " + def + ".");
        return def;
    }

    private static <E extends Enum<E>> E choice(ConfigurationSection root, String path, E def, List<String> warnings) {
        Object value = root.get(path);
        if (value == null) {
            return def;
        }
        String name = value.toString().trim().toUpperCase(Locale.ROOT).replace('-', '_');
        for (E option : def.getDeclaringClass().getEnumConstants()) {
            if (option.name().equals(name)) {
                return option;
            }
        }
        List<String> names = new ArrayList<>();
        for (E option : def.getDeclaringClass().getEnumConstants()) {
            names.add(option.name().toLowerCase(Locale.ROOT).replace('_', '-'));
        }
        warnings.add(path + " must be one of " + names + " (got '" + value + "'); using "
                + def.name().toLowerCase(Locale.ROOT).replace('_', '-') + ".");
        return def;
    }
}
