package io.github.drepfy.legendary.config;

import org.bukkit.configuration.ConfigurationSection;

import java.util.List;

/**
 * Brings texts that are still exactly as an older version shipped them (lore, messages) up to
 * the current defaults, so a server that never edited them gets the new wording. Texts that were
 * changed by hand are left alone.
 */
public final class TextUpdate {

    private TextUpdate() {
    }

    /**
     * @param config   the server's config.yml (changed in place)
     * @param previous the wording older versions shipped (previous-text.yml)
     * @param defaults the config.yml bundled in the jar
     * @return how many texts were updated
     */
    public static int apply(ConfigurationSection config, ConfigurationSection previous, ConfigurationSection defaults) {
        int updated = 0;
        for (String path : previous.getKeys(true)) {
            if (previous.isConfigurationSection(path) || !config.isSet(path)) {
                continue;
            }
            Object current = config.get(path);
            Object fresh = defaults.get(path);
            if (fresh == null || !same(current, previous.get(path)) || same(current, fresh)) {
                continue;
            }
            config.set(path, fresh instanceof List<?> list ? List.copyOf(list) : fresh);
            updated++;
        }
        return updated;
    }

    private static boolean same(Object a, Object b) {
        if (a instanceof List<?> x && b instanceof List<?> y) {
            return x.stream().map(String::valueOf).toList().equals(y.stream().map(String::valueOf).toList());
        }
        return a != null && b != null && !(a instanceof List<?>) && !(b instanceof List<?>)
                && String.valueOf(a).equals(String.valueOf(b));
    }
}
