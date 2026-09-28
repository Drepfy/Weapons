package io.github.drepfy.vigil.api;

import java.util.Locale;

/**
 * Every check Vigil runs. The {@link #id()} is the stable identifier used in the
 * configuration, in commands and in bypass permissions ({@code vigil.bypass.<id>}).
 * Identifiers are part of the public API and will not be renamed.
 */
public enum CheckType {
    SPEED("speed", "Speed", CheckCategory.MOVEMENT),
    FLIGHT("flight", "Flight", CheckCategory.MOVEMENT),
    VERTICAL("vertical", "Vertical", CheckCategory.MOVEMENT),
    GROUND_SPOOF("ground-spoof", "GroundSpoof", CheckCategory.MOVEMENT),
    TIMER("timer", "Timer", CheckCategory.MOVEMENT),
    REACH("reach", "Reach", CheckCategory.COMBAT),
    HIT_ANGLE("hit-angle", "HitAngle", CheckCategory.COMBAT),
    WALL_HIT("wall-hit", "WallHit", CheckCategory.COMBAT),
    BLOCK_REACH("block-reach", "BlockReach", CheckCategory.INTERACTION),
    WALL_INTERACT("wall-interact", "WallInteract", CheckCategory.INTERACTION);

    private final String id;
    private final String displayName;
    private final CheckCategory category;

    CheckType(String id, String displayName, CheckCategory category) {
        this.id = id;
        this.displayName = displayName;
        this.category = category;
    }

    /** Stable configuration/command identifier, e.g. {@code ground-spoof}. */
    public String id() {
        return id;
    }

    /** Human readable name used in alerts, e.g. {@code GroundSpoof}. */
    public String displayName() {
        return displayName;
    }

    public CheckCategory category() {
        return category;
    }

    /** Permission that exempts a player from this check only. */
    public String bypassPermission() {
        return "vigil.bypass." + id;
    }

    /**
     * Resolves a check from its id, display name or enum name (case-insensitive).
     *
     * @return the check, or {@code null} if nothing matches
     */
    public static CheckType fromId(String input) {
        if (input == null) {
            return null;
        }
        String needle = input.trim().toLowerCase(Locale.ROOT);
        for (CheckType type : values()) {
            if (type.id.equals(needle)
                    || type.displayName.toLowerCase(Locale.ROOT).equals(needle)
                    || type.name().toLowerCase(Locale.ROOT).equals(needle)) {
                return type;
            }
        }
        return null;
    }
}
