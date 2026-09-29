package io.github.drepfy.vigil.api;

import java.util.Locale;
import java.util.Map;

/**
 * Every check Vigil runs. The {@link #id()} is the identifier used in the
 * configuration and in commands; {@link #reason()} is what players and staff see
 * ("Steve has been flagged for Kill Aura").
 */
public enum CheckType {
    SPEED("speed", "Speed", "Speed Hacks", CheckCategory.MOVEMENT),
    FLIGHT("flight", "Flight", "Flying", CheckCategory.MOVEMENT),
    STEP("step", "Step", "Step / High Jump", CheckCategory.MOVEMENT),
    NOFALL("nofall", "NoFall", "NoFall", CheckCategory.MOVEMENT),
    TIMER("timer", "Timer", "Timer", CheckCategory.MOVEMENT),
    NOSLOW("noslow", "NoSlow", "NoSlow", CheckCategory.MOVEMENT),
    VELOCITY("velocity", "Velocity", "Anti-Knockback", CheckCategory.MOVEMENT),
    REACH("reach", "Reach", "Reach", CheckCategory.COMBAT),
    KILLAURA("killaura", "KillAura", "Kill Aura", CheckCategory.COMBAT),
    NOSWING("noswing", "NoSwing", "Kill Aura (No Swing)", CheckCategory.COMBAT),
    WALLHIT("wallhit", "WallHit", "Hitting Through Walls", CheckCategory.COMBAT),
    AUTOCLICKER("autoclicker", "AutoClicker", "Auto Clicker", CheckCategory.COMBAT),
    MACE("mace", "Mace", "Mace Exploit", CheckCategory.COMBAT),
    BLOCKREACH("blockreach", "BlockReach", "Block Reach", CheckCategory.INTERACTION),
    INTERACT("interact", "Interact", "Scaffold / Impossible Interaction", CheckCategory.INTERACTION),
    FASTPLACE("fastplace", "FastPlace", "Fast Place", CheckCategory.INTERACTION),
    NUKER("nuker", "Nuker", "Nuker", CheckCategory.INTERACTION),
    CHESTAURA("chestaura", "ChestAura", "Chest Aura", CheckCategory.INTERACTION),
    XRAY("xray", "XRay", "X-Ray / Ore Finder", CheckCategory.INTERACTION);

    /** Identifiers used by version 1.0/1.1, still accepted everywhere. */
    private static final Map<String, CheckType> LEGACY_IDS = Map.of(
            "vertical", STEP,
            "ground-spoof", NOFALL,
            "hit-angle", KILLAURA,
            "wall-hit", WALLHIT,
            "block-reach", BLOCKREACH,
            "wall-interact", CHESTAURA);

    private final String id;
    private final String displayName;
    private final String reason;
    private final CheckCategory category;

    CheckType(String id, String displayName, String reason, CheckCategory category) {
        this.id = id;
        this.displayName = displayName;
        this.reason = reason;
        this.category = category;
    }

    /** Configuration/command identifier, e.g. {@code killaura}. */
    public String id() {
        return id;
    }

    /** Short technical name, e.g. {@code KillAura}. */
    public String displayName() {
        return displayName;
    }

    /** Player-friendly name of what was detected, e.g. {@code Kill Aura}; used in alerts and bans. */
    public String reason() {
        return reason;
    }

    public CheckCategory category() {
        return category;
    }

    /** Permission that exempts a player from this check (only if bypass permissions are enabled). */
    public String bypassPermission() {
        return "vigil.bypass." + id;
    }

    /**
     * Resolves a check from its id, legacy id, display name or enum name (case-insensitive).
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
        return LEGACY_IDS.get(needle);
    }
}
