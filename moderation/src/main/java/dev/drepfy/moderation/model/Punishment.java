package dev.drepfy.moderation.model;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * An entry in the punishment history. Rows are never deleted; lifting a punishment only records
 * who removed it, when and why.
 *
 * @param expiresAt epoch millis, or {@code null} for permanent punishments
 * @param active    {@code false} once lifted (and always for kicks)
 * @param removal   who lifted it, or {@code null} if it wasn't lifted
 */
public record Punishment(
        long id,
        PunishmentType type,
        UUID targetId,
        String targetName,
        Actor actor,
        String reason,
        String server,
        long createdAt,
        Long expiresAt,
        boolean active,
        boolean silent,
        Removal removal) {

    public Punishment {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(targetId, "targetId");
        Objects.requireNonNull(targetName, "targetName");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(server, "server");
    }

    public boolean permanent() {
        return expiresAt == null;
    }

    public boolean expired(long now) {
        return expiresAt != null && expiresAt <= now;
    }

    /** Whether this punishment is currently in force. Kicks are never in force after the fact. */
    public boolean inForce(long now) {
        return active && type.timed() && !expired(now);
    }

    /** Length the punishment was issued for in millis, or {@code null} if permanent. */
    public Long lengthMillis() {
        return expiresAt == null ? null : expiresAt - createdAt;
    }

    /** Whether this punishment lasts longer than {@code other}; permanent outlasts any expiry. */
    public boolean outlasts(Punishment other) {
        if (other.permanent()) {
            return false;
        }
        return permanent() || expiresAt > other.expiresAt;
    }

    /**
     * The punishment of a type that is in force and lasts longest, so overlapping punishments are
     * always enforced by the strictest one.
     */
    public static Optional<Punishment> strongest(Iterable<Punishment> punishments, PunishmentType type, long now) {
        Punishment best = null;
        for (Punishment punishment : punishments) {
            if (punishment.type() == type && punishment.inForce(now) && (best == null || punishment.outlasts(best))) {
                best = punishment;
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * Details of a lifted punishment.
     */
    public record Removal(Actor actor, String reason, long removedAt) {
        public Removal {
            Objects.requireNonNull(actor, "actor");
            Objects.requireNonNull(reason, "reason");
        }
    }
}
