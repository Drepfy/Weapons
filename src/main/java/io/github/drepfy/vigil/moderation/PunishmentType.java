package io.github.drepfy.vigil.moderation;

import java.util.Locale;

/**
 * Kinds of manual punishment. Unban and unmute only have preset reasons, they are
 * not stored as separate punishments (they close a ban or mute).
 */
public enum PunishmentType {
    BAN,
    MUTE,
    WARN,
    KICK,
    UNBAN,
    UNMUTE;

    /** Configuration key, e.g. {@code ban}. */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }
}
