package dev.drepfy.moderation.model;

import java.util.Objects;
import java.util.UUID;

/**
 * Whoever issued or lifted a punishment.
 *
 * @param uniqueId the staff member's UUID, or {@code null} for the console and automatic actions
 * @param name     display name recorded in the history
 */
public record Actor(UUID uniqueId, String name) {

    public Actor {
        Objects.requireNonNull(name, "name");
    }

    public static Actor system(String name) {
        return new Actor(null, name);
    }

    public boolean isPlayer() {
        return uniqueId != null;
    }
}
