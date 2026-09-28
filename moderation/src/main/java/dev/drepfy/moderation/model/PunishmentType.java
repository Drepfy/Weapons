package dev.drepfy.moderation.model;

import java.util.Locale;
import java.util.Optional;

public enum PunishmentType {
    BAN("ban", true, true),
    MUTE("mute", true, true),
    VOICE_MUTE("voicemute", true, true),
    WARN("warn", true, false),
    KICK("kick", false, false);

    private final String key;
    private final boolean timed;
    private final boolean exclusive;

    PunishmentType(String key, boolean timed, boolean exclusive) {
        this.key = key;
        this.timed = timed;
        this.exclusive = exclusive;
    }

    /** Identifier used in config/message keys and stored in the database. */
    public String key() {
        return key;
    }

    /** Whether this punishment lasts for a period of time (everything except kicks). */
    public boolean timed() {
        return timed;
    }

    /**
     * Whether only one punishment of this type may be active at once. Re-issuing an exclusive
     * punishment is refused rather than silently replacing (and possibly shortening) the old one.
     */
    public boolean exclusive() {
        return exclusive;
    }

    public static Optional<PunishmentType> fromKey(String key) {
        String normalized = key.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
        for (PunishmentType type : values()) {
            if (type.key.equals(normalized)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
