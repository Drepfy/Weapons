package io.github.drepfy.lifesteal.util;

import org.bukkit.attribute.Attribute;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Parts of the API that differ between 1.20 and 1.21.x. Looked up at runtime so one jar
 * works on every version.
 */
public final class Compat {

    /** Max health: {@code MAX_HEALTH} since 1.21.3, {@code GENERIC_MAX_HEALTH} before. */
    public static final Attribute MAX_HEALTH = attribute("MAX_HEALTH", "GENERIC_MAX_HEALTH");

    private Compat() {
    }

    private static Attribute attribute(String... names) {
        for (String name : names) {
            try {
                Object value = Attribute.class.getField(name).get(null);
                if (value instanceof Attribute attribute) {
                    return attribute;
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Try the next name.
            }
        }
        throw new IllegalStateException("This server has no max health attribute");
    }

    /**
     * Makes an item shine like an enchanted one.
     *
     * @return false when the server is older than 1.20.5 (no glint override)
     */
    public static boolean glint(ItemMeta meta) {
        try {
            meta.setEnchantmentGlintOverride(true);
            return true;
        } catch (LinkageError | UnsupportedOperationException e) {
            return false;
        }
    }

    public static boolean classExists(String name) {
        try {
            Class.forName(name, false, Compat.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }
}
