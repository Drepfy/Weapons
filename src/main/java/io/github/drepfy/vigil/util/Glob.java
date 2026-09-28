package io.github.drepfy.vigil.util;

import java.util.Collection;
import java.util.Locale;

/**
 * Minimal case-insensitive wildcard matching for material/entity names:
 * {@code *X*} contains, {@code *X} ends with, {@code X*} starts with, otherwise equals.
 */
public final class Glob {

    private Glob() {
    }

    public static boolean matches(String pattern, String name) {
        if (pattern == null || name == null) {
            return false;
        }
        String p = pattern.toUpperCase(Locale.ROOT);
        String n = name.toUpperCase(Locale.ROOT);
        boolean leading = p.startsWith("*");
        boolean trailing = p.endsWith("*") && p.length() > 1;
        String core = p.substring(leading ? 1 : 0, p.length() - (trailing ? 1 : 0));
        if (core.isEmpty()) {
            return leading || trailing;
        }
        if (leading && trailing) {
            return n.contains(core);
        }
        if (leading) {
            return n.endsWith(core);
        }
        if (trailing) {
            return n.startsWith(core);
        }
        return n.equals(core);
    }

    public static boolean matchesAny(Collection<String> patterns, String name) {
        for (String pattern : patterns) {
            if (matches(pattern, name)) {
                return true;
            }
        }
        return false;
    }

    /** Plain "contains" matching used for entity type names such as BOAT. */
    public static boolean containsAny(Collection<String> needles, String name) {
        String upper = name.toUpperCase(Locale.ROOT);
        for (String needle : needles) {
            if (!needle.isEmpty() && upper.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
