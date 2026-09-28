package dev.drepfy.moderation;

import dev.drepfy.moderation.model.PunishmentType;

/**
 * Permission nodes. Keep in sync with plugin.yml.
 */
public final class Permissions {

    public static final String NOTIFY = "moderation.notify";
    public static final String SILENT = "moderation.silent";
    public static final String HISTORY = "moderation.history";
    public static final String HISTORY_SELF = "moderation.history.self";
    public static final String CHECK = "moderation.check";
    public static final String ADMIN = "moderation.admin";
    public static final String LIMITS_UNLIMITED = "moderation.limits.unlimited";
    public static final String EXEMPT = "moderation.exempt";
    public static final String EXEMPT_OVERRIDE = "moderation.exempt.override";

    private Permissions() {
    }

    /** e.g. {@code moderation.ban} */
    public static String issue(PunishmentType type) {
        return "moderation." + type.key();
    }

    /** e.g. {@code moderation.unban} */
    public static String lift(PunishmentType type) {
        return "moderation.un" + type.key();
    }
}
