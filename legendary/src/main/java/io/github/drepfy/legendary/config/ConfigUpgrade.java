package io.github.drepfy.legendary.config;

import io.github.drepfy.legendary.Ability;
import io.github.drepfy.legendary.WeaponType;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Brings an older config.yml up to date: the abilities from before 2.0 (all of them were replaced),
 * the Riftblade (replaced by the Wyrmfang in 2.0), the action bar settings and the messages and
 * sounds that no longer exist are removed; the new weapon, abilities, sounds and settings are
 * written in with their explanations; lore that names an ability the weapon no longer has goes
 * back to the default; and enchantments still at the old default (Sharpness 6) become the new
 * ones. Texts and settings still at an old default are handled by {@link TextUpdate}.
 */
public final class ConfigUpgrade {

    public static final int VERSION = 6;

    /** A setting placeholder in the lore, such as {@code {phantom-step.cooldown}}. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z0-9-]+)\\.[a-z0-9-]+}");

    /** Paths whose explanation changed (it named old abilities): the new one is written over it. */
    private static final List<String> NEW_COMMENTS = List.of("config-version", "weapons", "sounds");

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
        if (config.isSet("weapons.riftblade")) {
            config.set("weapons.riftblade", null);
            changes.add("removed weapons.riftblade (the Wyrmfang took its place: Riftblades turn into Wyrmfangs)");
        }
        if (!config.isSet("melee-damage") && defaults.isSet("melee-damage")) {
            copy(config, defaults, "melee-damage");
            changes.add("added melee-damage: " + defaults.get("melee-damage"));
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
        for (WeaponType type : WeaponType.values()) {
            String base = "weapons." + type.key();
            ConfigurationSection weapon = own(config, base);
            if (weapon == null) {
                if (!config.isSet(base) && defaults.isConfigurationSection(base)) {
                    copy(config, defaults, base);
                    changes.add("added " + base + " (" + type.key() + " is new)");
                }
                continue;
            }
            // Every ability is new in 2.0, even where a name came back (1.1's Sugar Rush, 1.0's
            // Earthsplitter): the old settings meant something else, so they all make way for the
            // new abilities and their explained defaults.
            ConfigurationSection abilities = own(config, base + ".abilities");
            if (abilities != null) {
                for (String key : abilities.getKeys(false)) {
                    changes.add("removed " + base + ".abilities." + key + " (an ability from before 2.0)");
                }
            }
            if (defaults.isConfigurationSection(base + ".abilities")) {
                copy(config, defaults, base + ".abilities");
                changes.add(base + ".abilities: the 2.0 abilities ("
                        + String.join(", ", defaults.getConfigurationSection(base + ".abilities").getKeys(false)) + ")");
            }
            if (namesOldAbility(weapon.getStringList("lore"), type) && defaults.isSet(base + ".lore")) {
                copy(config, defaults, base + ".lore");
                changes.add(base + ".lore: back to the default (it described abilities this version no longer has)");
            }
            ConfigurationSection enchantments = own(config, base + ".enchantments");
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

    /**
     * A section the server's config.yml really has. (Asked for a section it only has in the
     * defaults, Bukkit makes an empty one, which would then be saved as "{}".)
     */
    private static ConfigurationSection own(ConfigurationSection config, String path) {
        return config.isSet(path) ? config.getConfigurationSection(path) : null;
    }

    private static boolean namesOldAbility(List<String> lore, WeaponType type) {
        for (String line : lore) {
            Matcher matcher = PLACEHOLDER.matcher(line);
            while (matcher.find()) {
                boolean known = false;
                for (Ability ability : type.abilities()) {
                    known |= ability.key().equals(matcher.group(1));
                }
                if (!known) {
                    return true;
                }
            }
        }
        return false;
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
