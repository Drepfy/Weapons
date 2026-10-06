package io.github.drepfy.legendary;

import java.util.List;

/**
 * Every ability, with the settings it reads from {@code config.yml} and their defaults. Times
 * are in seconds.
 */
public enum Ability {

    // ---- Kurogane ----
    CRIMSON_FLASH("crimson-flash", "Crimson Flash", true,
            cooldown(20),
            num("range", 8, 2, 20),
            num("width", 1.6, 0.5, 5),
            num("damage", 7, 0.5, 100),
            time("delay", 0.5, 0, 3),
            num("bleed-damage", 1, 0, 20),
            time("bleed-duration", 3, 0, 20)),
    BLOOD_MOON("blood-moon", "Blood Moon", true,
            cooldown(35),
            time("duration", 6, 1, 30),
            num("bonus-damage", 3, 0, 50),
            num("heal", 1, 0, 20),
            time("min-hit-gap", 0.5, 0, 3)),

    // ---- Sugarcrash ----
    SUGAR_RUSH("sugar-rush", "Sugar Rush", true,
            cooldown(18),
            num("dash-speed", 1.6, 0.3, 4),
            time("dash-time", 0.4, 0.1, 2),
            num("damage", 4, 0.5, 100),
            num("knockback", 0.9, 0, 4),
            num("lift", 0.35, 0, 2),
            time("buff-duration", 5, 0, 60),
            whole("speed-level", 2, 0, 5),
            whole("haste-level", 2, 0, 5)),
    CANDY_CYCLONE("candy-cyclone", "Candy Cyclone", true,
            cooldown(30),
            time("duration", 3, 1, 15),
            num("radius", 4, 1, 12),
            num("damage", 1.5, 0.5, 50),
            time("interval", 0.5, 0.25, 5),
            num("pull", 0.22, 0, 2),
            num("burst-damage", 3, 0, 100),
            num("burst-knockback", 1.1, 0, 4),
            num("burst-lift", 0.45, 0, 2)),

    // ---- Riftblade ----
    VOID_REND("void-rend", "Void Rend", true,
            cooldown(22),
            num("distance", 5, 1, 16),
            num("pull-radius", 5, 1, 16),
            num("pull", 0.25, 0, 2),
            time("pull-time", 1, 0.25, 5),
            num("radius", 3, 0.5, 12),
            num("damage", 7, 0.5, 100),
            time("darkness", 2, 0, 15)),
    RIFT_SWAP("rift-swap", "Rift Swap", true,
            cooldown(30),
            num("range", 18, 4, 48),
            num("damage", 2, 0.5, 50),
            time("nausea", 3, 0, 15),
            num("blink", 10, 2, 32)),

    // ---- Gravebreaker ----
    EXECUTIONERS_LEAP("executioners-leap", "Executioner's Leap", true,
            cooldown(20),
            num("leap-up", 1.1, 0.2, 2.5),
            num("leap-forward", 0.9, 0, 2.5),
            num("radius", 5, 1, 12),
            num("damage", 8, 0.5, 100),
            num("edge-damage", 4, 0, 100),
            num("launch", 0.7, 0, 3),
            num("push", 0.6, 0, 3),
            whole("slow-level", 2, 0, 6),
            time("slow-duration", 2, 0, 30)),
    GRAVE_RISE("grave-rise", "Grave Rise", true,
            cooldown(30),
            num("range", 12, 3, 32),
            whole("stones", 6, 1, 16),
            time("interval", 0.2, 0.05, 2),
            num("width", 1.4, 0.5, 5),
            num("damage", 6, 0.5, 100),
            num("launch", 0.9, 0, 3),
            whole("fatigue-level", 2, 0, 5),
            time("fatigue-duration", 3, 0, 30)),

    // ---- Starforged ----
    STARFALL("starfall", "Starfall", true,
            cooldown(25),
            num("range", 24, 4, 64),
            time("warning", 1.25, 0.5, 5),
            num("radius", 4.5, 1, 12),
            whole("stars", 6, 1, 20),
            time("interval", 0.2, 0.05, 2),
            num("star-radius", 2, 0.5, 6),
            num("damage", 4, 0.5, 100),
            whole("max-hits", 3, 1, 20),
            num("launch", 0.6, 0, 3)),
    SINGULARITY("singularity", "Singularity", true,
            cooldown(35),
            num("range", 20, 4, 64),
            num("radius", 7, 2, 16),
            time("duration", 3, 1, 15),
            num("pull", 0.16, 0, 1),
            num("grip-damage", 1, 0.5, 20),
            num("nova-damage", 6, 0.5, 100),
            num("nova-knockback", 1.3, 0, 4),
            num("nova-lift", 0.5, 0, 2),
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

    /** The key in {@code config.yml}, e.g. {@code crimson-flash}. */
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
