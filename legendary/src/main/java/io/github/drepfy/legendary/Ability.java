package io.github.drepfy.legendary;

import java.util.List;

/**
 * Every ability, with the settings it reads from {@code config.yml} and their defaults. Times
 * are in seconds.
 */
public enum Ability {

    // ---- Kurogane ----
    CRIMSON_FLASH("crimson-flash", "Crimson Flash", true,
            cooldown(16),
            num("range", 8, 2, 20),
            num("width", 1.6, 0.5, 5),
            num("damage", 6, 0.5, 100),
            time("delay", 0.4, 0, 3),
            num("bleed-damage", 1, 0, 20),
            time("bleed-duration", 3, 0, 20),
            whole("charges", 2, 1, 5),
            time("recast-window", 3, 0.5, 10)),
    IAIDO("iaido", "Iaido", true,
            cooldown(22),
            time("stance", 1.5, 0.25, 5),
            num("reach", 12, 2, 32),
            num("damage", 12, 0.5, 100),
            num("heal", 4, 0, 40),
            num("slash-damage", 8, 0, 100),
            num("slash-range", 6, 1, 16)),
    CRIMSON_EDGE("crimson-edge", "Crimson Edge", false,
            whole("hits", 3, 2, 10),
            time("window", 3, 0.5, 10),
            time("min-hit-gap", 0.5, 0, 3),
            num("bonus-damage", 4, 0, 50),
            num("bleed-damage", 1, 0, 20),
            time("bleed-duration", 3, 0, 20)),

    // ---- Sugarcrash ----
    CANDY_HOOK("candy-hook", "Candy Hook", true,
            cooldown(14),
            num("range", 22, 4, 48),
            num("speed", 1.6, 0.4, 4),
            num("damage", 6, 0, 50),
            num("yank", 1.0, 0, 3),
            time("stun", 0.75, 0, 5),
            num("grapple", 1.0, 0, 3)),
    CANDY_BARRAGE("candy-barrage", "Candy Barrage", true,
            cooldown(22),
            whole("canes", 5, 1, 12),
            time("duration", 8, 1, 30),
            num("damage", 3, 0.5, 50),
            num("speed", 2.4, 0.5, 5),
            num("range", 32, 4, 64),
            num("knockback", 0.3, 0, 2),
            time("shot-gap", 0.25, 0, 2)),
    SUGAR_HIGH("sugar-high", "Sugar High", false,
            whole("stacks", 5, 2, 20),
            time("stack-duration", 4, 1, 30),
            time("min-hit-gap", 0.5, 0, 3),
            num("crash-damage", 4, 0, 50),
            num("splash-damage", 6, 0, 50),
            num("splash-radius", 3, 0.5, 10),
            num("knockback", 0.8, 0, 3)),

    // ---- Riftblade ----
    VOID_REND("void-rend", "Void Rend", true,
            cooldown(20),
            num("distance", 5, 1, 16),
            num("pull-radius", 5.5, 1, 16),
            num("pull", 0.28, 0, 2),
            time("pull-time", 1.25, 0.25, 5),
            num("radius", 3.2, 0.5, 12),
            num("damage", 10, 0.5, 100),
            time("darkness", 3, 0, 15),
            time("lift", 1, 0, 5)),
    RIFT_SWAP("rift-swap", "Rift Swap", true,
            cooldown(28),
            num("range", 20, 4, 48),
            num("damage", 6, 0.5, 50),
            time("nausea", 3, 0, 15),
            num("blink", 10, 2, 32),
            time("echo", 4, 0, 15)),
    PHASE_SHIFT("phase-shift", "Phase Shift", false,
            num("chance", 0.2, 0, 1),
            cooldown(10),
            num("distance", 3, 1, 8)),

    // ---- Gravebreaker ----
    EXECUTIONERS_LEAP("executioners-leap", "Executioner's Leap", true,
            cooldown(18),
            num("leap-up", 1.1, 0.2, 2.5),
            num("leap-forward", 0.9, 0, 2.5),
            num("radius", 5, 1, 12),
            num("damage", 12, 0.5, 100),
            num("edge-damage", 6, 0, 100),
            num("launch", 0.7, 0, 3),
            num("push", 0.6, 0, 3),
            whole("slow-level", 2, 0, 6),
            time("slow-duration", 2, 0, 30),
            num("dive-range", 18, 4, 48),
            num("dive-speed", 1.8, 0.5, 4),
            num("dive-bonus", 2, 0, 50)),
    GRAVE_RISE("grave-rise", "Grave Rise", true,
            cooldown(28),
            num("range", 12, 3, 32),
            whole("stones", 6, 1, 16),
            time("interval", 0.15, 0.05, 2),
            num("width", 1.5, 0.5, 5),
            num("damage", 8, 0.5, 100),
            num("launch", 0.9, 0, 3),
            whole("slow-level", 3, 0, 7),
            time("slow-duration", 2, 0, 30),
            whole("fatigue-level", 2, 0, 5),
            time("fatigue-duration", 3, 0, 30),
            num("tomb-radius", 3, 0, 10),
            num("tomb-damage", 6, 0, 100)),
    LAST_RITES("last-rites", "Last Rites", false,
            num("threshold", 0.4, 0.05, 1),
            num("bonus", 0.25, 0, 2)),

    // ---- Starforged ----
    STARFALL("starfall", "Starfall", true,
            cooldown(24),
            num("range", 28, 4, 64),
            time("warning", 1, 0.5, 5),
            num("radius", 4.5, 1, 12),
            whole("stars", 7, 1, 20),
            time("interval", 0.2, 0.05, 2),
            num("star-radius", 2, 0.5, 6),
            num("damage", 6, 0.5, 100),
            whole("max-hits", 2, 1, 20),
            num("launch", 0.6, 0, 3),
            num("follow-speed", 0.6, 0, 3)),
    SINGULARITY("singularity", "Singularity", true,
            cooldown(35),
            num("range", 22, 4, 64),
            num("radius", 8, 2, 16),
            time("duration", 3, 1, 15),
            num("pull", 0.2, 0, 1),
            num("grip-damage", 1, 0.5, 20),
            num("nova-damage", 10, 0.5, 100),
            num("nova-knockback", 1.4, 0, 4),
            num("nova-lift", 0.55, 0, 2),
            whole("slow-level", 2, 0, 6),
            time("slow-duration", 3, 0, 30),
            time("lockout", 1.5, 0, 30)),
    STARSTRUCK("starstruck", "Starstruck", false,
            whole("hits", 4, 2, 10),
            time("window", 4, 0.5, 10),
            time("min-hit-gap", 0.5, 0, 3),
            time("delay", 0.5, 0, 3),
            num("damage", 6, 0, 50),
            num("radius", 1.8, 0.5, 6),
            num("launch", 0.5, 0, 3));

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
