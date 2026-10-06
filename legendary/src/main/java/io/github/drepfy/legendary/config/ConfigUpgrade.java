package io.github.drepfy.legendary.config;

import io.github.drepfy.legendary.Ability;
import io.github.drepfy.legendary.WeaponType;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Brings an older config.yml up to date: settings of abilities that were replaced, the action
 * bar settings and messages that no longer exist are removed, and enchantments still at the
 * old default (Sharpness 6) become the new ones. Texts and settings still at an old default are
 * handled by {@link TextUpdate}.
 */
public final class ConfigUpgrade {

    public static final int VERSION = 3;

    private ConfigUpgrade() {
    }

    /** @return a description of each change (empty when there was nothing to do) */
    public static List<String> apply(ConfigurationSection config, ConfigurationSection defaults) {
        List<String> changes = new ArrayList<>();
        if (config.getInt("config-version", 1) >= VERSION || config.getKeys(false).isEmpty()) {
            return changes;
        }
        for (String old : List.of("display.action-bar", "display.ready-sound")) {
            if (config.isSet(old)) {
                config.set(old, null);
                changes.add("removed " + old + " (cooldowns are boss bars now)");
            }
        }
        ConfigurationSection messages = config.getConfigurationSection("messages");
        ConfigurationSection defaultMessages = defaults.getConfigurationSection("messages");
        if (messages != null && defaultMessages != null) {
            for (String key : new ArrayList<>(messages.getKeys(false))) {
                if (!defaultMessages.contains(key)) {
                    messages.set(key, null);
                    changes.add("removed messages." + key);
                }
            }
        }
        for (WeaponType type : WeaponType.values()) {
            String base = "weapons." + type.key();
            ConfigurationSection weapon = config.getConfigurationSection(base);
            if (weapon == null) {
                continue;
            }
            ConfigurationSection abilities = weapon.getConfigurationSection("abilities");
            if (abilities != null) {
                for (String key : new ArrayList<>(abilities.getKeys(false))) {
                    Ability current = null;
                    for (Ability ability : type.abilities()) {
                        if (ability.key().equals(key)) {
                            current = ability;
                        }
                    }
                    if (current == null) {
                        abilities.set(key, null);
                        changes.add("removed " + base + ".abilities." + key + " (an ability this version no longer has)");
                    }
                }
            }
            ConfigurationSection enchantments = weapon.getConfigurationSection("enchantments");
            ConfigurationSection fresh = defaults.getConfigurationSection(base + ".enchantments");
            if (enchantments != null && fresh != null) {
                Map<String, Object> values = enchantments.getValues(false);
                if (values.size() == 1 && "6".equals(String.valueOf(values.get("sharpness")))) {
                    weapon.set("enchantments", null);
                    for (String key : fresh.getKeys(false)) {
                        weapon.set("enchantments." + key, fresh.get(key));
                    }
                    changes.add(base + ".enchantments: Sharpness 6 → " + String.join(", ", fresh.getKeys(false)));
                }
            }
        }
        config.set("config-version", VERSION);
        return changes;
    }
}
