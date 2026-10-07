package io.github.drepfy.legendary;

import java.util.List;

/**
 * Every ability, with the settings it reads from {@code config.yml} and their defaults. Times
 * are in seconds; damage and health are in hearts (1 = one heart = 2 health points).
 */
public enum Ability {

    // ---- Katana ----
    BLEED("bleed", "Bleed", false,
            time("duration", 4, 0.5, 30),
            num("damage", 0.5, 0, 10),
            time("interval", 1, 0.25, 5)),
    DRAW("draw", "Draw", true,
            cooldown(25),
            time("window", 4, 0.5, 30),
            num("percent", 0.3, 0, 1),
            num("min", 1, 0, 20),
            num("max", 3.5, 0, 20)),

    // ---- Candy Cane ----
    STICKY_SWEET("sticky-sweet", "Sticky Sweet", false,
            num("chance", 0.25, 0, 1),
            time("duration", 1.5, 0.1, 10),
            time("immunity", 3, 0, 60)),
    SUGAR_TRAP("sugar-trap", "Sugar Trap", true,
            cooldown(25),
            whole("traps", 5, 1, 12),
            num("radius", 2.5, 1, 8),
            time("lifetime", 8, 1, 60),
            whole("poison-level", 1, 0, 5),
            time("poison-duration", 4, 0, 30),
            time("nausea-duration", 4, 0, 30),
            whole("slow-level", 1, 0, 6),
            time("slow-duration", 1, 0, 30)),

    // ---- Crush ----
    HEAVY("heavy", "Heavy", false,
            num("knockback", 1.3, 1, 3),
            num("airborne", 0.5, 0.1, 5),
            num("slam", 0.8, 0, 3),
            num("damage", 0.75, 0, 10)),
    CRUSH("crush", "Crush", true,
            cooldown(28),
            time("window", 10, 1, 60),
            num("slam", 1.6, 0, 4),
            num("damage", 0.5, 0, 20),
            num("damage-per-block", 0.75, 0, 10),
            num("max-damage", 4, 0, 40)),

    // ---- Reaper ----
    EXECUTION("execution", "Execution", false,
            num("below", 6, 0.5, 100),
            num("damage", 0.75, 0, 10)),
    REAP("reap", "Reap", true,
            cooldown(30),
            time("window", 8, 1, 60),
            num("below", 8, 0.5, 100),
            num("damage", 3.5, 0, 40));

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

    /** The key in {@code config.yml}, e.g. {@code sugar-trap}. */
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
