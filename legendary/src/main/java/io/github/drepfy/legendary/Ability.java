package io.github.drepfy.legendary;

import java.util.List;

/**
 * Every ability and passive, with the settings it reads from {@code config.yml} and their
 * defaults. Times are in seconds.
 */
public enum Ability {

    // ---- Kurogane ----
    CRESCENT_DRAW("crescent-draw", "Crescent Draw", true,
            cooldown(8),
            num("damage", 6, 0.5, 100),
            num("damage-per-edge", 1.5, 0, 20),
            num("range", 7, 1, 32),
            num("width", 3.5, 0.5, 10),
            num("speed", 1.2, 0.2, 5),
            num("knockback", 0.8, 0, 4),
            num("lift", 0.2, 0, 2)),
    UNBROKEN_EDGE("unbroken-edge", "Unbroken Edge", false,
            whole("max-stacks", 4, 1, 20),
            num("damage-per-stack", 5, 0, 100),
            time("window", 3, 0.5, 30),
            time("min-hit-gap", 0.55, 0, 3)),

    // ---- Sugarcrash ----
    SUGAR_RUSH("sugar-rush", "Sugar Rush", true,
            time("duration", 6, 1, 60),
            cooldown(18),
            whole("speed-level", 2, 1, 5),
            whole("haste-level", 2, 0, 5)),
    SWEET_SHOCK("sweet-shock", "Sweet Shock", true,
            cooldown(14),
            num("radius", 5, 1, 16),
            num("damage", 2, 0.5, 100),
            num("knockback", 1.1, 0, 4),
            num("lift", 0.35, 0, 2),
            whole("slow-level", 2, 0, 6),
            time("slow-duration", 2.5, 0, 30)),

    // ---- Riftblade ----
    RIFT_SLASH("rift-slash", "Rift Slash", true,
            cooldown(10),
            num("damage", 6, 0.5, 100),
            num("range", 10, 2, 32),
            num("width", 2.5, 0.5, 10),
            num("height", 2.5, 0.5, 10),
            num("speed", 0.9, 0.2, 5),
            num("knockback", 0.9, 0, 4),
            num("lift", 0.25, 0, 2),
            time("distortion", 3, 0, 10)),
    RIFT_RECALL("rift-recall", "Rift Recall", true,
            time("window", 10, 2, 60),
            cooldown(22),
            num("max-distance", 64, 4, 1000)),

    // ---- Gravebreaker ----
    EARTHSPLITTER("earthsplitter", "Earthsplitter", true,
            cooldown(12),
            num("damage", 6, 0.5, 100),
            num("range", 9, 2, 32),
            num("width", 2.2, 0.5, 8),
            num("speed", 1, 0.2, 5),
            num("launch", 0.75, 0, 3),
            num("push", 0.35, 0, 3),
            whole("fatigue-level", 2, 0, 5),
            time("fatigue-duration", 3, 0, 30),
            num("marked-multiplier", 1.6, 1, 5),
            num("max-launch", 1.2, 0.1, 3)),
    EXECUTIONERS_MARK("executioners-mark", "Executioner's Mark", false,
            whole("hits", 3, 2, 10),
            time("hit-window", 6, 1, 60),
            time("min-hit-gap", 0.7, 0, 3),
            time("duration", 8, 1, 60)),

    // ---- Starforged ----
    ASTRAL_IMPACT("astral-impact", "Astral Impact", true,
            cooldown(16),
            num("range", 24, 4, 64),
            time("warning", 1.25, 0.5, 5),
            num("radius", 4, 1, 12),
            num("damage", 7, 0.5, 100),
            num("launch", 0.9, 0, 3),
            num("push", 0.3, 0, 3)),
    GRAVITY_WELL("gravity-well", "Gravity Well", true,
            cooldown(20),
            num("range", 20, 4, 64),
            num("radius", 6, 2, 16),
            time("duration", 4, 1, 15),
            num("pull", 0.12, 0, 1),
            num("grip-damage", 1, 0.5, 20),
            num("pulse-damage", 4, 0.5, 100),
            num("pulse-knockback", 1.2, 0, 4),
            num("pulse-lift", 0.45, 0, 2),
            time("lockout", 1.5, 0, 30));

    private final String key;
    private final String defaultName;
    private final boolean active;
    private final List<Option> options;

    Ability(String key, String defaultName, boolean active, Option... options) {
        this.key = key;
        this.defaultName = defaultName;
        this.active = active;
        this.options = List.of(options);
    }

    /** The key in {@code config.yml}, e.g. {@code crescent-draw}. */
    public String key() {
        return key;
    }

    public String defaultName() {
        return defaultName;
    }

    /** Used with a key (true) or a passive (false). */
    public boolean active() {
        return active;
    }

    public List<Option> options() {
        return options;
    }

    public Option option(String key) {
        for (Option option : options) {
            if (option.key().equals(key)) {
                return option;
            }
        }
        throw new IllegalArgumentException(this.key + " has no setting " + key);
    }

    /** One setting: a number, a whole number or a time (seconds). */
    public record Option(String key, Kind kind, double def, double min, double max) {
    }

    public enum Kind {
        NUMBER, WHOLE, TIME
    }

    private static Option num(String key, double def, double min, double max) {
        return new Option(key, Kind.NUMBER, def, min, max);
    }

    private static Option whole(String key, int def, int min, int max) {
        return new Option(key, Kind.WHOLE, def, min, max);
    }

    private static Option time(String key, double def, double min, double max) {
        return new Option(key, Kind.TIME, def, min, max);
    }

    private static Option cooldown(double seconds) {
        return time("cooldown", seconds, 1, 600);
    }
}
