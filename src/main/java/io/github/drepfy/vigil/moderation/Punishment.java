package io.github.drepfy.vigil.moderation;

import org.bukkit.configuration.ConfigurationSection;

import java.util.UUID;

/**
 * One ban, mute, warning or kick. Times are epoch milliseconds so punishments
 * survive restarts. Instances are immutable; revoking creates a new instance.
 */
public record Punishment(int id,
                         PunishmentType type,
                         UUID uuid,
                         String name,
                         String reason,
                         String staff,
                         long createdEpochMs,
                         long durationMs,
                         boolean revoked,
                         String revokedBy,
                         String revokeReason,
                         long revokedEpochMs) {

    public boolean isPermanent() {
        return durationMs == Durations.PERMANENT;
    }

    public long expiresEpochMs() {
        return isPermanent() ? Long.MAX_VALUE : createdEpochMs + durationMs;
    }

    /** Whether a ban or mute is currently enforced. */
    public boolean isInEffect(long nowEpochMs) {
        return (type == PunishmentType.BAN || type == PunishmentType.MUTE) && !revoked
                && nowEpochMs < expiresEpochMs();
    }

    public Punishment revoke(String by, String reason, long nowEpochMs) {
        return new Punishment(id, type, uuid, name, this.reason, staff, createdEpochMs, durationMs, true, by, reason,
                nowEpochMs);
    }

    void save(ConfigurationSection section) {
        section.set("type", type.name());
        section.set("uuid", uuid.toString());
        section.set("name", name);
        section.set("reason", reason);
        section.set("staff", staff);
        section.set("created", createdEpochMs);
        section.set("duration", durationMs);
        if (revoked) {
            section.set("revoked-by", revokedBy);
            section.set("revoke-reason", revokeReason);
            section.set("revoked", revokedEpochMs);
        }
    }

    static Punishment load(int id, ConfigurationSection section) {
        String rawType = section.getString("type");
        String rawUuid = section.getString("uuid");
        if (rawType == null || rawUuid == null) {
            throw new IllegalArgumentException("punishment " + id + " is missing its type or uuid");
        }
        boolean revoked = section.isSet("revoked");
        return new Punishment(id, PunishmentType.valueOf(rawType), UUID.fromString(rawUuid),
                section.getString("name", "unknown"), section.getString("reason", ""), section.getString("staff", "?"),
                section.getLong("created"), section.getLong("duration", 0L), revoked,
                section.getString("revoked-by"), section.getString("revoke-reason"), section.getLong("revoked", 0L));
    }
}
