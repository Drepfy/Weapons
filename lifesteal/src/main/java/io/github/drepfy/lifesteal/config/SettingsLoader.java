package io.github.drepfy.lifesteal.config;

import io.github.drepfy.lifesteal.util.Durations;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reads config.yml. A wrong value never stops the plugin: it is replaced by its default
 * and reported, so the console tells the owner exactly what to fix.
 */
public final class SettingsLoader {

    public static final String DEFAULT_PREFIX = "&b&lᴠᴀɴɪʟʟᴀ sᴍᴘ » &r";
    public static final List<String> DEFAULT_SHAPE = List.of("DND", "DSD", "DND");

    static final Map<String, String> DEFAULT_MESSAGES;

    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("prefix", DEFAULT_PREFIX);
        // Kills. Placeholders: {killer} {victim} {hearts} {min} {max} {time}
        m.put("steal-killer", "&7You stole a heart from &c{victim}&7. You now have &c{hearts} &7hearts.");
        m.put("steal-victim", "&c{killer} &7stole one of your hearts. You now have &c{hearts} &7hearts.");
        m.put("steal-killer-at-max", "&7You already have the maximum of &c{max} &7hearts, so the heart from &c{victim} &7dropped as an item.");
        m.put("victim-at-min-killer", "&c{victim} &7only has &c{min} &7hearts left, so there was no heart to steal.");
        m.put("victim-at-min-victim", "&7You are at the minimum of &c{min} &7hearts and did not lose one.");
        m.put("cooldown-killer", "&7You took a heart from &c{victim} &7recently. You can take another in &c{time}&7.");
        m.put("cooldown-victim", "&7You did not lose a heart: &c{killer} &7took one from you recently.");
        m.put("alt-killer", "&7No heart was stolen: this kill was not counted by the alt account protection.");
        m.put("alt-victim", "&7You did not lose a heart: this kill was not counted by the alt account protection.");
        m.put("alt-staff", "&8[&cLifesteal&8] &7{killer} killed {victim}, not counted: &f{reason}");
        m.put("natural-death", "&7You lost a heart. You now have &c{hearts} &7hearts.");
        // Heart items. Placeholders: {count} {s} {hearts} {max}
        m.put("consume", "&7You used &c{count} &7Heart{s}. You now have &c{hearts} &7hearts.");
        m.put("consume-at-max", "&cYou already have the maximum of {max} hearts.");
        // /withdraw. Placeholders: {count} {s} {hearts} {min} {max} {available} {input}
        m.put("withdraw-success", "&7You withdrew &c{count} &7heart{s}. You now have &c{hearts} &7hearts.");
        m.put("withdraw-usage", "&cUsage: /withdraw <amount>");
        m.put("withdraw-invalid", "&c'{input}' is not a valid amount. Use a whole number from 1 to {max}.");
        m.put("withdraw-too-many", "&cYou can withdraw at most {max} hearts at once.");
        m.put("withdraw-too-low", "&cYou must keep at least {min} hearts. You can withdraw up to {available} right now.");
        m.put("withdraw-none", "&cYou only have {min} hearts, so you cannot withdraw any.");
        m.put("withdraw-disabled", "&cWithdrawing hearts is turned off.");
        m.put("inventory-full", "&7Your inventory is full, so {count} Heart{s} dropped at your feet.");
        // /hearts. Placeholders: {player} {hearts} {rank}
        m.put("hearts-self", "&7You have &c{hearts} &7hearts.");
        m.put("hearts-other", "&f{player} &7has &c{hearts} &7hearts.");
        m.put("top-header", "&7Most hearts:");
        m.put("top-entry", "&8{rank}. &f{player} &8- &c{hearts} &7hearts");
        // General
        m.put("no-permission", "&cYou do not have permission to do that.");
        m.put("player-not-found", "&cPlayer not found: &f{player}");
        m.put("players-only", "&cOnly players can do that.");
        m.put("reloaded", "&aLifesteal reloaded. &7({warnings} warning(s), see console)");
        m.put("reload-failed", "&cReload failed, the previous settings are kept: &f{error}");
        m.put("hearts-set", "&7{player} now has &c{hearts} &7hearts.");
        m.put("hearts-changed-notify", "&7Your hearts were changed by staff. You now have &c{hearts} &7hearts.");
        m.put("given", "&7Gave &c{count} &7Heart{s} to &f{player}&7.");
        m.put("invalid-number", "&c'{input}' is not a valid number.");
        DEFAULT_MESSAGES = java.util.Collections.unmodifiableMap(m);
    }

    private SettingsLoader() {
    }

    public static Settings load(ConfigurationSection root) {
        Reader r = new Reader(root);

        int max = r.integer("hearts.max", 20, 1, 1024);
        int min = r.integer("hearts.min", 3, 1, 1024);
        if (min > max) {
            r.warn("hearts.min (" + min + ") is above hearts.max (" + max + "); using 3 and 20.");
            min = 3;
            max = 20;
        }
        int start = r.integer("hearts.start", 10, 1, 1024);
        if (start < min || start > max) {
            r.warn("hearts.start must be between hearts.min and hearts.max; using " + Math.max(min, Math.min(max, 10)) + ".");
            start = Math.max(min, Math.min(max, 10));
        }
        int perKill = r.integer("hearts.per-kill", 1, 1, 100);
        Set<String> disabledWorlds = new HashSet<>(r.stringList("hearts.disabled-worlds"));

        long cooldown = r.duration("cooldown.time", 30L * 60 * 1000);

        Settings.Withdraw withdraw = new Settings.Withdraw(r.bool("withdraw.enabled", true),
                r.integer("withdraw.max-per-command", 17, 1, 1024));

        Material material = r.material("heart-item.material", Material.RED_DYE, false);
        List<String> lore = r.root.contains("heart-item.lore") ? r.stringList("heart-item.lore")
                : List.of("&7A stolen heart.", "", "&eRight-click to consume", "&8Sneak + right-click to use the whole stack");
        Settings.HeartItem item = new Settings.HeartItem(material, r.string("heart-item.name", "&c&l❤ Heart"), lore,
                r.integer("heart-item.custom-model-data", 1001, 0, Integer.MAX_VALUE),
                r.bool("heart-item.glow", true));

        Settings.Recipe recipe = loadRecipe(r);

        Settings.ResourcePack pack = new Settings.ResourcePack(r.string("resource-pack.url", "").trim(),
                r.string("resource-pack.sha1", "").trim().toLowerCase(Locale.ROOT),
                r.bool("resource-pack.required", false),
                r.string("resource-pack.prompt", "&7This server uses a small pack for the Heart item."));
        if (!pack.sha1().isEmpty() && !pack.sha1().matches("[0-9a-f]{40}")) {
            r.warn("resource-pack.sha1 must be 40 hexadecimal characters; the bundled pack's is used instead.");
            pack = new Settings.ResourcePack(pack.url(), "", pack.required(), pack.prompt());
        }
        if (!pack.url().isEmpty() && !pack.url().startsWith("http://") && !pack.url().startsWith("https://")) {
            r.warn("resource-pack.url must be a direct http(s) download link; the pack is not sent.");
            pack = new Settings.ResourcePack("", pack.sha1(), pack.required(), pack.prompt());
        }

        Settings.AltProtection alts = new Settings.AltProtection(
                r.bool("alt-protection.enabled", true),
                r.bool("alt-protection.shared-ip.enabled", true),
                r.duration("alt-protection.shared-ip.remember", 30L * 24 * 3600 * 1000),
                r.integer("alt-protection.shared-ip.max-accounts-per-ip", 5, 2, 1000),
                r.duration("alt-protection.min-playtime", 30L * 60 * 1000),
                r.bool("alt-protection.notify-staff", true));

        Map<String, String> messages = new HashMap<>();
        for (Map.Entry<String, String> entry : DEFAULT_MESSAGES.entrySet()) {
            messages.put(entry.getKey(), r.text("messages." + entry.getKey(), entry.getValue()));
        }
        messages.put("prefix", r.text("prefix", messages.get("prefix")));

        return new Settings(start, min, max, perKill, r.bool("hearts.lose-on-natural-death", false),
                r.bool("hearts.heal-gained-hearts", true), disabledWorlds, cooldown,
                r.bool("cooldown.both-directions", false), withdraw, item, recipe, pack, alts,
                r.bool("log-to-file", true), new Settings.Messages(messages), r.warnings);
    }

    private static Settings.Recipe loadRecipe(Reader r) {
        boolean enabled = r.bool("recipe.enabled", true);
        List<String> shape = r.root.contains("recipe.shape") ? r.stringList("recipe.shape") : DEFAULT_SHAPE;
        Map<Character, Material> ingredients = new HashMap<>();
        ConfigurationSection section = r.root.getConfigurationSection("recipe.ingredients");
        if (section == null) {
            ingredients.put('D', Material.DIAMOND_BLOCK);
            ingredients.put('N', Material.NETHERITE_INGOT);
            ingredients.put('S', Material.NETHER_STAR);
        } else {
            for (String key : section.getKeys(false)) {
                if (key.length() != 1 || key.charAt(0) == ' ') {
                    r.warn("recipe.ingredients." + key + ": ingredient keys must be a single letter.");
                    continue;
                }
                Material material = r.material("recipe.ingredients." + key, null, false);
                if (material != null) {
                    ingredients.put(key.charAt(0), material);
                }
            }
        }
        boolean valid = !shape.isEmpty() && shape.size() <= 3;
        int width = shape.isEmpty() ? 0 : shape.get(0).length();
        boolean any = false;
        for (String row : shape) {
            if (row.length() != width || row.isEmpty() || row.length() > 3) {
                valid = false;
            }
            for (char c : row.toCharArray()) {
                if (c != ' ') {
                    any = true;
                    if (!ingredients.containsKey(c)) {
                        valid = false;
                    }
                }
            }
        }
        if (!valid || !any) {
            r.warn("recipe.shape must be 1-3 rows of the same length (1-3 letters), and every letter needs an "
                    + "ingredient; using the default recipe.");
            ingredients.clear();
            ingredients.put('D', Material.DIAMOND_BLOCK);
            ingredients.put('N', Material.NETHERITE_INGOT);
            ingredients.put('S', Material.NETHER_STAR);
            shape = DEFAULT_SHAPE;
        }
        return new Settings.Recipe(enabled, shape, ingredients);
    }

    /** Typed access that records a warning for every rejected value. */
    private static final class Reader {
        final ConfigurationSection root;
        final List<String> warnings = new ArrayList<>();

        Reader(ConfigurationSection root) {
            this.root = root;
        }

        void warn(String message) {
            warnings.add(message);
        }

        boolean bool(String path, boolean def) {
            Object value = root.get(path);
            if (value == null) {
                return def;
            }
            if (value instanceof Boolean b) {
                return b;
            }
            String text = value.toString().trim().toLowerCase(Locale.ROOT);
            if (text.equals("true") || text.equals("yes") || text.equals("on")) {
                return true;
            }
            if (text.equals("false") || text.equals("no") || text.equals("off")) {
                return false;
            }
            warn(path + " must be true or false (got '" + value + "'); using " + def + ".");
            return def;
        }

        int integer(String path, int def, int min, int max) {
            Object value = root.get(path);
            if (value == null) {
                return def;
            }
            try {
                long parsed = value instanceof Number number && !(value instanceof Double || value instanceof Float)
                        ? number.longValue() : Long.parseLong(value.toString().trim());
                if (parsed < min || parsed > max) {
                    warn(path + " must be between " + min + " and " + max + " (got " + parsed + "); using " + def + ".");
                    return def;
                }
                return (int) parsed;
            } catch (NumberFormatException e) {
                warn(path + " must be a whole number (got '" + value + "'); using " + def + ".");
                return def;
            }
        }

        String string(String path, String def) {
            Object value = root.get(path);
            if (value == null) {
                return def;
            }
            if (value instanceof ConfigurationSection || value instanceof List<?>) {
                warn(path + " must be text; using the default.");
                return def;
            }
            return value.toString();
        }

        /** Text, or a list of lines. */
        String text(String path, String def) {
            Object value = root.get(path);
            if (value instanceof List<?> lines) {
                List<String> parts = new ArrayList<>();
                for (Object line : lines) {
                    parts.add(line == null ? "" : line.toString());
                }
                return String.join("\n", parts);
            }
            return string(path, def);
        }

        List<String> stringList(String path) {
            Object value = root.get(path);
            if (value == null) {
                return List.of();
            }
            if (!(value instanceof List<?> list)) {
                warn(path + " must be a list; it is ignored.");
                return List.of();
            }
            List<String> result = new ArrayList<>();
            for (Object element : list) {
                result.add(element == null ? "" : element.toString());
            }
            return result;
        }

        long duration(String path, long def) {
            Object value = root.get(path);
            if (value == null) {
                return def;
            }
            Long parsed = Durations.parse(value.toString());
            if (parsed == null) {
                warn(path + " must be a time such as 30m, 12h or 7d (got '" + value + "'); using the default.");
                return def;
            }
            return parsed;
        }

        Material material(String path, Material def, boolean allowAir) {
            Object value = root.get(path);
            if (value == null) {
                return def;
            }
            Material material = Material.matchMaterial(value.toString().trim());
            if (material == null || (!allowAir && material.isAir()) || !material.isItem()) {
                warn(path + ": '" + value + "' is not an item" + (def != null ? "; using " + def.name() : "; it is ignored")
                        + ".");
                return def;
            }
            return material;
        }
    }
}
