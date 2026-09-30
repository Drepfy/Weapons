package io.github.drepfy.vigil.config;

import io.github.drepfy.vigil.moderation.PunishmentType;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Brings a config.yml from an older 2.x version up to date without touching anything the
 * owner changed:
 * <ul>
 *   <li>values still equal to an old default (ban screen, preset reasons with a single
 *   time, auto-ban length...) are replaced by the new default;</li>
 *   <li>options that did not exist yet are added, with their comments.</li>
 * </ul>
 * Pure in-memory logic; the caller saves the file and keeps a backup.
 */
public final class ConfigUpgrader {

    /** Options added after 2.0, copied from the bundled config when missing. */
    private static final List<String> NEW_PATHS = List.of(
            "anticheat.auto-ban.animation",
            "anticheat.setup-paper-anti-xray",
            "anticheat.hide-storage-from-esp",
            "anticheat.hide-ores-from-xray",
            "anticheat.client-check",
            "moderation.appeal",
            "moderation.date-format",
            "moderation.warn-escalation",
            "moderation.warnings-expire-after",
            "discord");

    /** Ban presets of 2.0-2.2 (one time each). */
    private static final Map<String, String> OLD_BAN_PRESETS = pairs(
            "Cheating", "30d", "Hacked_Client", "30d", "Kill_Aura", "30d", "Fly_Hacks", "14d",
            "Speed_Hacks", "14d", "X-Ray", "14d", "Reach", "14d", "Auto_Clicker", "7d", "Duping", "30d",
            "Exploiting", "14d", "Griefing", "7d", "Stealing", "3d", "Scamming", "7d", "Harassment", "7d",
            "Hate_Speech", "30d", "Threats", "30d", "Doxxing", "perm", "Advertising", "7d", "Spam", "1d",
            "Inappropriate_Build", "3d", "Inappropriate_Skin", "1d", "Inappropriate_Name", "perm",
            "Lag_Machine", "7d", "Staff_Disrespect", "3d", "Ban_Evasion", "perm", "Alt_Account", "perm",
            "Chargeback", "perm");

    /** Mute presets of 2.0-2.2 (one time each). */
    private static final Map<String, String> OLD_MUTE_PRESETS = pairs(
            "Spam", "30m", "Chat_Flood", "30m", "Excessive_Caps", "15m", "Swearing", "30m", "Toxicity", "1h",
            "Harassment", "6h", "Hate_Speech", "7d", "Threats", "7d", "Advertising", "1d",
            "Inappropriate_Language", "1h", "Arguing_With_Staff", "30m", "Spoilers", "15m", "Begging", "30m",
            "Impersonation", "1d", "Politics_Or_Religion", "1h");

    private ConfigUpgrader() {
    }

    /**
     * Upgrades {@code current} in place using the bundled {@code defaults}.
     *
     * @return a short description of every change (empty when nothing changed)
     */
    public static List<String> upgrade(YamlConfiguration current, YamlConfiguration defaults) {
        List<String> changes = new ArrayList<>();
        if (!current.isConfigurationSection("anticheat")) {
            return changes; // Not a 2.x file (1.x files are migrated separately).
        }

        // Messages still at an old default get the new text.
        for (Map.Entry<String, String> entry : ConfigLoader.OLD_DEFAULT_MESSAGES.entrySet()) {
            String path = "messages." + entry.getKey();
            if (entry.getValue().equals(text(current.get(path))) && defaults.contains(path)) {
                current.set(path, defaults.get(path));
                changes.add(path);
            }
        }
        // Preset reasons still at the old single times become escalating.
        upgradePresets(current, defaults, PunishmentType.BAN, OLD_BAN_PRESETS, changes);
        upgradePresets(current, defaults, PunishmentType.MUTE, OLD_MUTE_PRESETS, changes);
        if ("30d".equals(String.valueOf(current.get("anticheat.auto-ban.duration")).trim())) {
            current.set("anticheat.auto-ban.duration", defaults.get("anticheat.auto-ban.duration"));
            changes.add("anticheat.auto-ban.duration");
        }

        // New options and messages.
        for (String path : NEW_PATHS) {
            copyIfMissing(current, defaults, path, changes);
        }
        copyMissingChildren(current, defaults, "anticheat.checks", changes);
        copyMissingChildren(current, defaults, "messages", changes);
        return changes;
    }

    private static void upgradePresets(YamlConfiguration current, YamlConfiguration defaults, PunishmentType type,
                                       Map<String, String> old, List<String> changes) {
        String path = "moderation.reasons." + type.key();
        ConfigurationSection section = current.getConfigurationSection(path);
        if (section == null || !defaults.isConfigurationSection(path)) {
            return;
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            values.put(key, String.valueOf(section.get(key)).trim());
        }
        if (values.equals(old)) {
            current.set(path, null);
            current.createSection(path, defaults.getConfigurationSection(path).getValues(false));
            current.setComments(path, defaults.getComments(path));
            changes.add(path);
        }
    }

    private static void copyIfMissing(YamlConfiguration current, YamlConfiguration defaults, String path,
                                      List<String> changes) {
        if (current.contains(path) || !defaults.contains(path)) {
            return;
        }
        Object value = defaults.get(path);
        if (value instanceof ConfigurationSection section) {
            current.createSection(path, section.getValues(true));
        } else {
            current.set(path, value);
        }
        current.setComments(path, defaults.getComments(path));
        changes.add(path);
    }

    private static void copyMissingChildren(YamlConfiguration current, YamlConfiguration defaults, String parent,
                                            List<String> changes) {
        ConfigurationSection section = defaults.getConfigurationSection(parent);
        if (section == null || !current.isConfigurationSection(parent)) {
            return;
        }
        for (String key : section.getKeys(false)) {
            copyIfMissing(current, defaults, parent + "." + key, changes);
        }
    }

    /** A message as one string, whether it is written as text or as a list of lines. */
    private static String text(Object value) {
        if (value instanceof List<?> lines) {
            List<String> parts = new ArrayList<>();
            for (Object line : lines) {
                parts.add(line == null ? "" : line.toString());
            }
            return String.join("\n", parts);
        }
        return value == null ? null : value.toString().replace("\\n", "\n");
    }

    private static Map<String, String> pairs(String... values) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < values.length; i += 2) {
            map.put(values[i], values[i + 1]);
        }
        return map;
    }
}
