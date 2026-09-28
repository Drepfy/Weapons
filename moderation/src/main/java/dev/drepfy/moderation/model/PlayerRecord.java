package dev.drepfy.moderation.model;

import java.util.UUID;

/**
 * What the plugin remembers about a player who has joined, so they can be punished while offline.
 *
 * @param exempt whether the player had {@code moderation.exempt} the last time they joined; used to
 *               protect exempt players while they are offline
 */
public record PlayerRecord(UUID uniqueId, String name, long firstSeen, long lastSeen, boolean exempt) {
}
