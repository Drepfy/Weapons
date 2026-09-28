package com.drepfy.staffvanish.selector;

import java.util.Locale;
import java.util.logging.Logger;

/** What a click with the staff selector does. */
public enum SelectorAction {
    NONE,
    OPEN_MENU,
    NEXT_PLAYER,
    PREVIOUS_PLAYER,
    RANDOM_PLAYER,
    INSPECT;

    public static SelectorAction parse(String raw, SelectorAction fallback, Logger logger, String path) {
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException e) {
            logger.warning("Unknown selector action '" + raw + "' at " + path + ", using " + fallback);
            return fallback;
        }
    }
}
