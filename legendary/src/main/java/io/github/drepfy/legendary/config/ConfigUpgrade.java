package io.github.drepfy.legendary.config;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Brings an older config.yml up to date. 3.0 has four new weapons (the Katana, Candy Cane, Crush
 * and Reaper), so the old {@code weapons} section is replaced by the new one with its
 * explanations. The settings, messages and sounds that no longer exist are removed and the new
 * ones are written in. 3.1 gives every weapon its own sounds: sounds still at their 3.0 default
 * become the new ones (sounds you changed are kept). Texts still at an old default are handled by
 * {@link TextUpdate}.
 */
public final class ConfigUpgrade {

    public static final int VERSION = 8;

    /** Paths whose explanation changed: the new one is written over it. */
    private static final List<String> NEW_COMMENTS = List.of("config-version", "controls", "true-damage", "weapons",
            "sounds");

    /** The 3.0 default of each sound 3.1 changed. */
    private static final Map<String, List<String>> OLD_SOUNDS = Map.of(
            "draw", List.of("item.armor.equip_chain 1 0.6"),
            "draw-strike", List.of("item.trident.hit 1 0.7"),
            "sugar-trap", List.of("block.honey_block.place 1 1.3"),
            "sugar-trap-trigger", List.of("block.honey_block.break 1 1.1"),
            "crush", List.of("item.armor.equip_netherite 1 0.6"),
            "crush-impact", List.of("item.mace.smash_ground_heavy 1 0.8"),
            "reap", List.of("particle.soul_escape 1 0.7"),
            "reap-strike", List.of("entity.player.attack.sweep 1 0.5"));

    private ConfigUpgrade() {
    }

    /** @return a description of each change (empty when there was nothing to do) */
    public static List<String> apply(ConfigurationSection config, ConfigurationSection defaults) {
        List<String> changes = new ArrayList<>();
        int version = config.getInt("config-version", 1);
        if (version >= VERSION || config.getKeys(false).isEmpty()) {
            return changes;
        }
        if (version < 7) {
            toThree(config, defaults, changes);
        }
        for (Map.Entry<String, List<String>> old : OLD_SOUNDS.entrySet()) {
            String path = "sounds." + old.getKey();
            if (config.isSet(path) && old.getValue().equals(config.getStringList(path)) && defaults.isSet(path)) {
                copy(config, defaults, path);
                changes.add("updated " + path + " to the 3.1 sound");
            }
        }
        ConfigurationSection freshSounds = defaults.getConfigurationSection("sounds");
        if (freshSounds != null) {
            for (String key : freshSounds.getKeys(false)) {
                if (!config.isSet("sounds." + key)) {
                    copy(config, defaults, "sounds." + key);
                    changes.add("added sounds." + key);
                }
            }
        }
        for (String path : NEW_COMMENTS) {
            List<String> comments = defaults.getComments(path);
            if (config.isSet(path) && !comments.isEmpty()) {
                config.setComments(path, comments);
            }
        }
        config.set("config-version", VERSION);
        return changes;
    }

    /** 3.0: the four new weapons replace the old ones. */
    private static void toThree(ConfigurationSection config, ConfigurationSection defaults, List<String> changes) {
        for (String old : List.of("display.action-bar", "display.ready-sound")) {
            if (config.isSet(old)) {
                config.set(old, null);
                changes.add("removed " + old + " (cooldowns are boss bars now)");
            }
        }
        if (config.isSet("hit-mobs")) {
            config.set("hit-mobs", null);
            changes.add("removed hit-mobs (the weapons only affect players now)");
        }
        for (String part : List.of("messages", "sounds")) {
            ConfigurationSection section = own(config, part);
            ConfigurationSection fresh = defaults.getConfigurationSection(part);
            if (section != null && fresh != null) {
                for (String key : new ArrayList<>(section.getKeys(false))) {
                    if (!fresh.contains(key)) {
                        section.set(key, null);
                        changes.add("removed " + part + "." + key);
                    }
                }
            }
        }
        for (String setting : List.of("melee-damage", "full-strength-hits")) {
            if (!config.isSet(setting) && defaults.isSet(setting)) {
                copy(config, defaults, setting);
                changes.add("added " + setting + ": " + defaults.get(setting));
            }
        }
        ConfigurationSection weapons = own(config, "weapons");
        if (defaults.isConfigurationSection("weapons")) {
            if (weapons != null) {
                changes.add("removed the old weapons (" + String.join(", ", weapons.getKeys(false)) + ")");
            }
            copy(config, defaults, "weapons");
            changes.add("added the 3.0 weapons ("
                    + String.join(", ", defaults.getConfigurationSection("weapons").getKeys(false)) + ")");
        }
    }

    /**
     * A section the server's config.yml really has. (Asked for a section it only has in the
     * defaults, Bukkit makes an empty one, which would then be saved as "{}".)
     */
    private static ConfigurationSection own(ConfigurationSection config, String path) {
        return config.isSet(path) ? config.getConfigurationSection(path) : null;
    }

    /** Copies a setting or a whole section from the defaults, with its explanation comments. */
    private static void copy(ConfigurationSection config, ConfigurationSection defaults, String path) {
        if (defaults.isConfigurationSection(path)) {
            config.set(path, null);
            config.createSection(path);
            for (String key : defaults.getConfigurationSection(path).getKeys(false)) {
                copy(config, defaults, path + "." + key);
            }
        } else {
            Object value = defaults.get(path);
            config.set(path, value instanceof List<?> list ? new ArrayList<>(list) : value);
        }
        List<String> comments = defaults.getComments(path);
        if (!comments.isEmpty()) {
            config.setComments(path, comments);
        }
    }
}
