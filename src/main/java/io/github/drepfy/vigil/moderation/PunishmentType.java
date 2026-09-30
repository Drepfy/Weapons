package io.github.drepfy.vigil.moderation;

import java.util.Locale;

/**
 * Kinds of manual punishment. Unban, unmute and unwarn only have preset reasons, they
 * are not stored as separate punishments (they close a ban, mute or warning).
 */
public enum PunishmentType {
    BAN,
    MUTE,
    WARN,
    KICK,
    UNBAN,
    UNMUTE,
    UNWARN;

    /** Configuration key, e.g. {@code ban}. */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }
}
