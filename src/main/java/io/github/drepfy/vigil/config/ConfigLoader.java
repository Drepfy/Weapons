package io.github.drepfy.vigil.config;

import io.github.drepfy.vigil.api.CheckType;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns a raw YAML tree into validated {@link Settings}.
 *
 * <p>Loading never fails because of a bad value: every invalid or out-of-range
 * value is replaced by its default and reported as a warning. Missing keys silently
 * use their defaults, so configuration files from older versions keep working.
 */
public final class ConfigLoader {

    public static final int SUPPORTED_CONFIG_VERSION = 1;

    static final Map<String, String> DEFAULT_MESSAGES;

    static {
        Map<String, String> messages = new LinkedHashMap<>();
        messages.put("prefix", "&8[&cVigil&8] &r");
        messages.put("no-permission", "&cYou do not have permission to do that.");
        messages.put("player-not-found", "&cPlayer not found: &f{player}");
        messages.put("alerts-enabled", "&aStaff alerts enabled.");
        messages.put("alerts-disabled", "&eStaff alerts disabled.");
        messages.put("reloaded", "&aConfiguration reloaded. &7({warnings} warning(s))");
        messages.put("reload-failed", "&cReload failed, previous configuration kept: &f{error}");
        messages.put("case-opened", "&eReview case &f#{id} &eopened for &f{player}&e: {reason}");
        DEFAULT_MESSAGES = Map.copyOf(messages);
    }

    private static final Set<String> COMMON_CHECK_KEYS = Set.of("enabled", "alert-vl", "review-vl",
            "decay-per-minute", "vl-per-flag", "buffer-threshold", "mitigate", "actions");

    private ConfigLoader() {
    }

    /** Settings built purely from built-in defaults (used when the file is unreadable). */
    public static Settings defaults(List<String> warnings) {
        return load(new org.bukkit.configuration.MemoryConfiguration(), warnings);
    }

    public static Settings load(ConfigurationSection root) {
        return load(root, new ArrayList<>());
    }

    /** Loads settings, prepending {@code initialWarnings} to the reported warnings. */
    public static Settings load(ConfigurationSection root, List<String> initialWarnings) {
        Reader r = new Reader(root, new ArrayList<>(initialWarnings));

        int version = (int) r.number("config-version", SUPPORTED_CONFIG_VERSION, 0, Integer.MAX_VALUE);
        if (version > SUPPORTED_CONFIG_VERSION) {
            r.warn("config-version " + version + " is newer than this plugin supports ("
                    + SUPPORTED_CONFIG_VERSION + "); unknown options are ignored.");
        }

        Settings.General general = new Settings.General(
                r.bool("general.enabled", true),
                r.bool("general.passive-mode", false),
                r.bool("general.exempt-creative-and-spectator", true),
                new HashSet<>(r.stringList("general.disabled-worlds", List.of())),
                r.bool("general.use-client-tick-events", true),
                upper(r.stringList("general.platform-entities", List.of("BOAT", "RAFT", "MINECART", "SHULKER", "HAPPY_GHAST"))),
                r.bool("general.debug", false));

        Settings.Lag lag = new Settings.Lag(
                r.number("lag-protection.min-tps", 17.0, 0.0, 20.0),
                r.millis("lag-protection.lag-spike-threshold-ms", 400, 100, 60_000),
                r.millis("lag-protection.lag-spike-grace-ms", 3000, 0, 120_000),
                r.millis("lag-protection.max-ping-ms", 400, 50, 10_000),
                r.millis("lag-protection.join-grace-ms", 5000, 0, 120_000),
                r.millis("lag-protection.respawn-grace-ms", 3000, 0, 120_000),
                r.millis("lag-protection.teleport-grace-ms", 1500, 0, 120_000),
                r.millis("lag-protection.world-change-grace-ms", 3000, 0, 120_000),
                r.millis("lag-protection.gamemode-change-grace-ms", 2000, 0, 120_000),
                r.millis("lag-protection.velocity-grace-ms", 1500, 0, 120_000),
                r.millis("lag-protection.vehicle-exit-grace-ms", 1500, 0, 120_000),
                r.millis("lag-protection.elytra-grace-ms", 2500, 0, 120_000),
                r.millis("lag-protection.riptide-grace-ms", 3000, 0, 120_000),
                r.number("lag-protection.disturbance-radius", 8.0, 0.0, 64.0),
                r.millis("lag-protection.disturbance-grace-ms", 3000, 0, 120_000));

        Settings.Alerts alerts = new Settings.Alerts(
                r.bool("alerts.enabled", true),
                r.bool("alerts.console", true),
                r.millis("alerts.cooldown-ms", 5000, 0, 3_600_000),
                r.string("alerts.format",
                        "&8[&cVigil&8] &f{player} &7flagged &c{check} &8(&7VL &f{vl}&8) &7{detail}{suppressed}"),
                r.bool("alerts.clickable", true));

        Settings.Violations violations = new Settings.Violations(
                (int) r.number("violations.history-size", 50, 1, 1000),
                r.bool("violations.log-to-file", true),
                (int) r.number("violations.log-retention-days", 0, 0, 36500));

        Settings.Review review = new Settings.Review(
                r.bool("review.enabled", true),
                (int) r.number("review.max-evidence", 25, 1, 500),
                r.bool("review.notify", true),
                (int) r.number("review.max-cases", 1000, 10, 100_000));

        Settings.Punishments punishments = new Settings.Punishments(
                r.bool("punishments.enabled", false),
                r.bool("punishments.dry-run", true),
                (int) r.number("punishments.min-flags-in-window", 5, 1, 10_000),
                r.millis("punishments.window-ms", 600_000, 1000, 86_400_000L),
                r.millis("punishments.cooldown-ms", 1_800_000, 0, 86_400_000L));

        Map<CheckType, CheckSettings> checks = new EnumMap<>(CheckType.class);
        for (CheckType type : CheckType.values()) {
            checks.put(type, loadCheck(r, type));
        }
        ConfigurationSection checksSection = root.getConfigurationSection("checks");
        if (checksSection != null) {
            for (String key : checksSection.getKeys(false)) {
                if (CheckType.fromId(key) == null || !CheckType.fromId(key).id().equals(key)) {
                    r.warn("checks." + key + " is not a known check and is ignored.");
                }
            }
        }

        Map<String, String> messageValues = new HashMap<>();
        for (Map.Entry<String, String> entry : DEFAULT_MESSAGES.entrySet()) {
            messageValues.put(entry.getKey(), r.string("messages." + entry.getKey(), entry.getValue()));
        }

        return new Settings(general, lag, alerts, violations, review, punishments, checks,
                new Settings.Messages(messageValues), r.warnings);
    }

    private static CheckSettings loadCheck(Reader r, CheckType type) {
        CheckSpec spec = CheckSpec.of(type);
        CheckSpec.Defaults d = spec.defaults();
        String base = "checks." + type.id() + ".";

        Map<String, Double> numbers = new HashMap<>();
        for (CheckSpec.NumberOption option : spec.numbers()) {
            numbers.put(option.key(), r.number(base + option.key(), option.defaultValue(), option.min(), option.max()));
        }
        Map<String, List<String>> lists = new HashMap<>();
        for (CheckSpec.ListOption option : spec.lists()) {
            lists.put(option.key(), upper(r.stringList(base + option.key(), option.defaultValue())));
        }

        // Never cancel something the check would not even flag.
        if (numbers.containsKey("cancel-leniency") && numbers.containsKey("leniency")
                && numbers.get("cancel-leniency") < numbers.get("leniency")) {
            r.warn(base + "cancel-leniency is lower than leniency; using leniency instead.");
            numbers.put("cancel-leniency", numbers.get("leniency"));
        }

        ConfigurationSection section = r.root.getConfigurationSection("checks." + type.id());
        if (section != null) {
            Set<String> known = new HashSet<>(COMMON_CHECK_KEYS);
            spec.numbers().forEach(option -> known.add(option.key()));
            spec.lists().forEach(option -> known.add(option.key()));
            for (String key : section.getKeys(false)) {
                if (!known.contains(key)) {
                    r.warn(base + key + " is not a known option and is ignored.");
                }
            }
        }

        return new CheckSettings(type,
                r.bool(base + "enabled", d.enabled()),
                r.number(base + "alert-vl", d.alertVl(), 0.0, 1_000_000.0),
                r.number(base + "review-vl", d.reviewVl(), 0.0, 1_000_000.0),
                r.number(base + "decay-per-minute", d.decayPerMinute(), 0.0, 1_000_000.0),
                r.number(base + "vl-per-flag", d.vlPerFlag(), 0.01, 1000.0),
                r.number(base + "buffer-threshold", d.bufferThreshold(), 1.0, 1000.0),
                r.bool(base + "mitigate", d.mitigate()),
                loadActions(r, base + "actions"),
                numbers,
                lists);
    }

    private static List<ActionRule> loadActions(Reader r, String path) {
        Object raw = r.root.get(path);
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> list)) {
            r.warn(path + " must be a list; ignoring it.");
            return List.of();
        }
        List<ActionRule> rules = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            Object entry = list.get(i);
            Map<?, ?> map = null;
            if (entry instanceof Map<?, ?> m) {
                map = m;
            } else if (entry instanceof ConfigurationSection s) {
                map = s.getValues(false);
            }
            if (map == null) {
                r.warn(path + "[" + i + "] must contain 'vl' and 'commands'; ignoring it.");
                continue;
            }
            Double vl = Reader.toDouble(map.get("vl"));
            Object commandsRaw = map.get("commands");
            if (vl == null || vl <= 0 || !Double.isFinite(vl) || !(commandsRaw instanceof List<?> commands)) {
                r.warn(path + "[" + i + "] needs a positive 'vl' and a 'commands' list; ignoring it.");
                continue;
            }
            List<String> cleaned = new ArrayList<>();
            for (Object command : commands) {
                if (command != null && !command.toString().isBlank()) {
                    String text = command.toString().trim();
                    cleaned.add(text.startsWith("/") ? text.substring(1) : text);
                }
            }
            if (cleaned.isEmpty()) {
                r.warn(path + "[" + i + "] has no commands; ignoring it.");
                continue;
            }
            rules.add(new ActionRule(vl, cleaned));
        }
        rules.sort((a, b) -> Double.compare(a.vl(), b.vl()));
        return rules;
    }

    private static List<String> upper(List<String> values) {
        List<String> result = new ArrayList<>(values.size());
        for (String value : values) {
            result.add(value.trim().toUpperCase(Locale.ROOT));
        }
        return result;
    }

    /** Typed, validating accessor that records a warning for every rejected value. */
    private static final class Reader {
        private final ConfigurationSection root;
        private final List<String> warnings;

        Reader(ConfigurationSection root, List<String> warnings) {
            this.root = root;
            this.warnings = warnings;
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
            warn(path + " must be true or false (got '" + value + "'); using default " + def + ".");
            return def;
        }

        double number(String path, double def, double min, double max) {
            Object value = root.get(path);
            if (value == null) {
                return def;
            }
            Double parsed = toDouble(value);
            if (parsed == null || !Double.isFinite(parsed)) {
                warn(path + " must be a number (got '" + value + "'); using default " + format(def) + ".");
                return def;
            }
            if (parsed < min || parsed > max) {
                warn(path + " must be between " + format(min) + " and " + format(max) + " (got " + format(parsed)
                        + "); using default " + format(def) + ".");
                return def;
            }
            return parsed;
        }

        long millis(String path, long def, long min, long max) {
            return Math.round(number(path, def, min, max));
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

        List<String> stringList(String path, List<String> def) {
            Object value = root.get(path);
            if (value == null) {
                return def;
            }
            if (!(value instanceof List<?> list)) {
                warn(path + " must be a list; using the default.");
                return def;
            }
            List<String> result = new ArrayList<>();
            for (Object element : list) {
                if (element != null && !element.toString().isBlank()) {
                    result.add(element.toString().trim());
                }
            }
            return result;
        }

        static Double toDouble(Object value) {
            if (value instanceof Number number) {
                return number.doubleValue();
            }
            if (value instanceof String text) {
                try {
                    return Double.parseDouble(text.trim());
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
            return null;
        }

        private static String format(double value) {
            return value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value);
        }
    }
}
