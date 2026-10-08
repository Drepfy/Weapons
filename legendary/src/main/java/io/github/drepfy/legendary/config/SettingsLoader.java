package io.github.drepfy.legendary.config;

import io.github.drepfy.legendary.Ability;
import io.github.drepfy.legendary.WeaponType;
import io.github.drepfy.legendary.util.Compat;
import io.github.drepfy.legendary.util.Durations;
import io.github.drepfy.legendary.util.Text;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Reads config.yml. Anything missing comes from the bundled defaults; a wrong value is
 * replaced by its default and reported, never fatal.
 */
public final class SettingsLoader {

    private static final Pattern ITEM_MODEL = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    private SettingsLoader() {
    }

    /**
     * @param root     the server's config.yml
     * @param defaults the config.yml bundled in the jar
     */
    public static Settings load(ConfigurationSection root, ConfigurationSection defaults) {
        List<String> warnings = new ArrayList<>();
        Reader in = new Reader(root, defaults, warnings);

        String prefix = in.string("prefix");
        boolean oneOfEach = in.bool("one-of-each");
        boolean markLost = in.bool("mark-lost");
        boolean dropOnDeath = in.bool("drop-on-death");
        Settings.Alts alts = new Settings.Alts(in.bool("alt-protection.enabled"),
                (long) (in.duration("alt-protection.remember-ip", 3600, 3650L * 86_400) * 1000),
                (int) in.number("alt-protection.max-accounts-per-ip", 1, 1000));
        List<String> blocked = new ArrayList<>();
        for (String command : in.list("blocked-commands")) {
            String clean = command.trim().toLowerCase(Locale.ROOT);
            if (clean.startsWith("/")) {
                clean = clean.substring(1);
            }
            if (!clean.isEmpty()) {
                blocked.add(clean);
            }
        }
        Settings.Controls controls = in.choice("controls", Settings.Controls.class);
        boolean fullStrengthHits = in.bool("full-strength-hits");
        boolean trueDamage = in.bool("true-damage");
        double meleeDamage = in.number("melee-damage", 0.1, 10);
        boolean bossBars = in.bool("display.boss-bars");
        boolean barsWhenReady = in.bool("display.boss-bars-when-ready");

        Map<WeaponType, Settings.Look> looks = new EnumMap<>(WeaponType.class);
        Map<Ability, AbilitySettings> abilities = new EnumMap<>(Ability.class);
        for (WeaponType type : WeaponType.values()) {
            String base = "weapons." + type.key() + ".";
            looks.put(type, look(in, base, warnings));
            for (Ability ability : type.abilities()) {
                abilities.put(ability, ability(in, base + "abilities." + ability.key() + ".", ability));
            }
        }
        ConfigurationSection weapons = root.getConfigurationSection("weapons");
        if (weapons != null) {
            for (String key : weapons.getKeys(false)) {
                if (WeaponType.byKey(key) == null) {
                    warnings.add("weapons." + key + ": there is no weapon called " + key + " (ignored)");
                }
            }
        }

        Map<String, List<Settings.SoundSpec>> sounds = new HashMap<>();
        ConfigurationSection soundDefaults = defaults.getConfigurationSection("sounds");
        if (soundDefaults != null) {
            for (String key : soundDefaults.getKeys(false)) {
                List<Settings.SoundSpec> specs = new ArrayList<>();
                for (String line : in.list("sounds." + key)) {
                    Settings.SoundSpec spec = sound(line);
                    if (spec == null) {
                        warnings.add("sounds." + key + ": \"" + line + "\" should be \"<sound> [volume] [pitch]\" (ignored)");
                    } else {
                        specs.add(spec);
                    }
                }
                sounds.put(key, List.copyOf(specs));
            }
        }

        Map<String, String> messages = new LinkedHashMap<>();
        ConfigurationSection messageDefaults = defaults.getConfigurationSection("messages");
        if (messageDefaults != null) {
            for (String key : messageDefaults.getKeys(false)) {
                messages.put(key, in.string("messages." + key));
            }
        }
        return new Settings(prefix, oneOfEach, markLost, dropOnDeath, alts, List.copyOf(blocked), controls, fullStrengthHits,
                trueDamage, meleeDamage, bossBars, barsWhenReady, looks, abilities, sounds, messages, List.copyOf(warnings));
    }

    private static Settings.Look look(Reader in, String base, List<String> warnings) {
        String name = in.string(base + "name");
        List<String> lore = in.list(base + "lore");
        int model = (int) in.number(base + "custom-model-data", 0, Integer.MAX_VALUE);
        String itemModel = in.string(base + "item-model").trim().toLowerCase(Locale.ROOT);
        if (!itemModel.isEmpty() && !ITEM_MODEL.matcher(itemModel).matches()) {
            warnings.add(base + "item-model: \"" + itemModel + "\" should look like namespace:name (ignored)");
            itemModel = "";
        }
        String tooltipStyle = in.string(base + "tooltip-style").trim().toLowerCase(Locale.ROOT);
        if (!tooltipStyle.isEmpty() && !ITEM_MODEL.matcher(tooltipStyle).matches()) {
            warnings.add(base + "tooltip-style: \"" + tooltipStyle + "\" should look like namespace:name (ignored)");
            tooltipStyle = "";
        }
        // The real vanilla enchantment glint, on unless turned off (also for a weapon with no glint setting).
        boolean glint = in.bool(base + "glint", true);
        org.bukkit.boss.BarColor barColor = in.choice(base + "boss-bar.color", org.bukkit.boss.BarColor.class);
        String barText = in.string(base + "boss-bar.text");
        boolean unbreakable = in.bool(base + "unbreakable");
        Map<String, Integer> enchantments = new LinkedHashMap<>();
        ConfigurationSection section = in.section(base + "enchantments");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                String id = key.toLowerCase(Locale.ROOT);
                Object value = section.get(key);
                int level;
                try {
                    level = value instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(value).trim());
                } catch (NumberFormatException e) {
                    warnings.add(base + "enchantments." + key + ": \"" + value + "\" is not a level (ignored)");
                    continue;
                }
                if (level < 0 || level > 255) {
                    warnings.add(base + "enchantments." + key + ": level must be 0 to 255 (ignored)");
                } else if (Compat.enchantment(id) == null) {
                    warnings.add(base + "enchantments." + key + ": unknown enchantment (ignored)");
                } else if (level > 0) {
                    enchantments.put(id, level);
                }
            }
        }
        return new Settings.Look(name, List.copyOf(lore), model, itemModel, tooltipStyle, glint, unbreakable,
                java.util.Collections.unmodifiableMap(new LinkedHashMap<>(enchantments)), barColor, barText);
    }

    private static AbilitySettings ability(Reader in, String base, Ability ability) {
        Map<String, Double> values = new HashMap<>();
        for (Ability.Option option : ability.options()) {
            String path = base + option.key();
            double value = switch (option.kind()) {
                case TIME -> in.durationOr(path, option.def(), option.min(), option.max());
                case WHOLE -> Math.rint(in.numberOr(path, option.def(), option.min(), option.max()));
                case NUMBER -> in.numberOr(path, option.def(), option.min(), option.max());
            };
            values.put(option.key(), value);
        }
        String name = in.root.getString(base + "name", ability.defaultName());
        return new AbilitySettings(ability, name == null || name.isBlank() ? ability.defaultName() : name, values);
    }

    /** "entity.player.attack.sweep 1 1.4" */
    static Settings.SoundSpec sound(String line) {
        String[] parts = line.trim().split("\\s+");
        if (parts.length == 0 || parts[0].isEmpty() || parts.length > 3) {
            return null;
        }
        try {
            float volume = parts.length > 1 ? Float.parseFloat(parts[1]) : 1.0f;
            float pitch = parts.length > 2 ? Float.parseFloat(parts[2]) : 1.0f;
            if (volume < 0 || volume > 10 || pitch < 0.5f || pitch > 2.0f) {
                return null;
            }
            return new Settings.SoundSpec(parts[0].toLowerCase(Locale.ROOT), volume, pitch);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Reads a value from the server's config, or the bundled default when it is missing or wrong. */
    private static final class Reader {
        final ConfigurationSection root;
        final ConfigurationSection defaults;
        final List<String> warnings;

        Reader(ConfigurationSection root, ConfigurationSection defaults, List<String> warnings) {
            this.root = root;
            this.defaults = defaults;
            this.warnings = warnings;
        }

        String string(String path) {
            Object value = root.get(path);
            if (value == null || value instanceof ConfigurationSection) {
                return defaults.getString(path, "");
            }
            return value.toString();
        }

        boolean bool(String path) {
            return bool(path, false);
        }

        /** fallback: the value when neither config.yml nor the defaults have it. */
        boolean bool(String path, boolean fallback) {
            Object value = root.get(path);
            boolean def = defaults.isSet(path) ? defaults.getBoolean(path) : fallback;
            if (value == null) {
                return def;
            }
            if (value instanceof Boolean flag) {
                return flag;
            }
            String text = value.toString().trim().toLowerCase(Locale.ROOT);
            if (text.equals("true") || text.equals("yes") || text.equals("on")) {
                return true;
            }
            if (text.equals("false") || text.equals("no") || text.equals("off")) {
                return false;
            }
            warnings.add(path + ": \"" + value + "\" should be true or false (using " + def + ")");
            return def;
        }

        List<String> list(String path) {
            Object value = root.get(path);
            if (value == null) {
                return defaults.getStringList(path);
            }
            if (value instanceof List<?> items) {
                List<String> result = new ArrayList<>();
                for (Object item : items) {
                    result.add(item == null ? "" : item.toString());
                }
                return result;
            }
            return List.of(value.toString());
        }

        ConfigurationSection section(String path) {
            ConfigurationSection section = root.getConfigurationSection(path);
            return section != null ? section : defaults.getConfigurationSection(path);
        }

        double number(String path, double min, double max) {
            return numberOr(path, defaults.getDouble(path), min, max);
        }

        double numberOr(String path, double def, double min, double max) {
            Object value = root.get(path);
            if (value == null) {
                return def;
            }
            try {
                double parsed = value instanceof Number n ? n.doubleValue() : Double.parseDouble(value.toString().trim());
                if (parsed >= min && parsed <= max) {
                    return parsed;
                }
                warnings.add(path + ": " + value + " must be between " + Text.number(min)
                        + " and " + Text.number(max) + " (using "
                        + Text.number(def) + ")");
            } catch (NumberFormatException e) {
                warnings.add(path + ": \"" + value + "\" is not a number (using "
                        + Text.number(def) + ")");
            }
            return def;
        }

        double duration(String path, double minSeconds, double maxSeconds) {
            Double def = Durations.seconds(defaults.getString(path, "0"));
            return durationOr(path, def == null ? 0 : def, minSeconds, maxSeconds);
        }

        double durationOr(String path, double def, double min, double max) {
            Object value = root.get(path);
            if (value == null) {
                return def;
            }
            Double seconds = Durations.seconds(value.toString());
            String fallback = Text.seconds(def);
            if (seconds == null) {
                warnings.add(path + ": \"" + value + "\" is not a time such as 8s or 2.5s (using " + fallback + ")");
                return def;
            }
            if (seconds < min || seconds > max) {
                warnings.add(path + ": " + value + " must be between " + Text.seconds(min)
                        + " and " + Text.seconds(max) + " (using " + fallback + ")");
                return def;
            }
            return seconds;
        }

        <E extends Enum<E>> E choice(String path, Class<E> type) {
            String def = defaults.getString(path, "");
            Object value = root.get(path);
            String text = (value == null ? def : value.toString()).trim().toUpperCase(Locale.ROOT).replace('-', '_');
            try {
                return Enum.valueOf(type, text);
            } catch (IllegalArgumentException e) {
                StringBuilder options = new StringBuilder();
                for (E option : type.getEnumConstants()) {
                    options.append(options.length() == 0 ? "" : ", ")
                            .append(option.name().toLowerCase(Locale.ROOT).replace('_', '-'));
                }
                warnings.add(path + ": \"" + value + "\" should be one of " + options + " (using " + def + ")");
                return Enum.valueOf(type, def.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
            }
        }
    }
}
