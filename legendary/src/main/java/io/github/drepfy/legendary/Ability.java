package io.github.drepfy.legendary;

import java.util.List;

/**
 * Every ability, with the settings it reads from {@code config.yml} and their defaults. Times
 * are in seconds.
 */
public enum Ability {

    // ---- Kurogane ----
    PHANTOM_STEP("phantom-step", "Phantom Step", true,
            cooldown(21),
            num("range", 10, 2, 24),
            num("width", 1.8, 0.5, 5),
            num("damage", 7.5, 0.5, 100),
            num("bleed-damage", 1.25, 0, 20),
            time("bleed-duration", 3, 0, 20),
            whole("speed-level", 3, 0, 5),
            time("speed-duration", 3, 0, 30)),
    CRIMSON_TEMPEST("crimson-tempest", "Crimson Tempest", true,
            cooldown(27),
            time("duration", 5, 1, 20),
            num("range", 7, 2, 20),
            num("width", 2.4, 0.5, 6),
            num("damage", 5, 0.5, 100),
            time("gap", 0.5, 0.05, 5),
            whole("speed-level", 2, 0, 5)),
    CRIMSON_HUNGER("crimson-hunger", "Crimson Hunger", false,
            num("chance", 0.25, 0, 1),
            num("bleed-damage", 1.25, 0, 20),
            time("bleed-duration", 3, 0, 20),
            num("heal", 1, 0, 20)),

    // ---- Sugarcrash ----
    CANDY_REAPER("candy-reaper", "Candy Reaper", true,
            cooldown(19),
            num("range", 18, 4, 48),
            num("speed", 1.3, 0.3, 4),
            num("return-speed", 1.6, 0.3, 4),
            num("damage", 6.25, 0.5, 100),
            num("pull", 0.9, 0, 3)),
    SUGAR_RUSH("sugar-rush", "Sugar Rush", true,
            cooldown(33),
            time("duration", 3, 0.5, 10),
            num("speed", 0.9, 0.2, 2.5),
            num("damage", 6.25, 0.5, 100),
            num("knockback", 0.9, 0, 3),
            num("burst-radius", 4, 0, 12),
            num("burst-damage", 5, 0, 100)),
    SUGAR_HIGH("sugar-high", "Sugar High", false,
            whole("speed-level", 1, 0, 5),
            whole("slow-level", 1, 0, 6),
            time("slow-duration", 1.5, 0, 20)),

    // ---- Wyrmfang ----
    WYRM_LUNGE("wyrm-lunge", "Wyrm Lunge", true,
            cooldown(25),
            num("forward", 1.5, 0.2, 3),
            num("up", 0.45, 0, 2),
            time("reach-time", 1.25, 0.25, 5),
            num("damage", 10, 0.5, 100),
            num("launch", 0.8, 0, 3),
            whole("slow-level", 3, 0, 6),
            time("slow-duration", 2, 0, 30)),
    DRAGONS_BREATH("dragons-breath", "Dragon's Breath", true,
            cooldown(33),
            time("duration", 2, 0.5, 10),
            num("range", 8, 2, 20),
            num("angle", 35, 5, 90),
            time("interval", 0.25, 0.05, 2),
            num("damage", 1.25, 0.25, 50),
            whole("poison-level", 2, 0, 5),
            time("poison-duration", 3, 0, 30)),
    VENOM_FANG("venom-fang", "Venom Fang", false,
            whole("poison-level", 2, 0, 5),
            time("poison-duration", 2, 0, 30)),

    // ---- Gravebreaker ----
    EARTHSPLITTER("earthsplitter", "Earthsplitter", true,
            cooldown(23),
            num("range", 14, 3, 32),
            num("width", 1.6, 0.5, 5),
            num("damage", 10, 0.5, 100),
            num("launch", 1.0, 0, 3),
            whole("slow-level", 2, 0, 6),
            time("slow-duration", 2, 0, 30)),
    IRON_BASTION("iron-bastion", "Iron Bastion", true,
            cooldown(33),
            time("duration", 4, 1, 15),
            whole("resistance-level", 3, 0, 5),
            num("radius", 6, 1, 16),
            num("damage", 6.25, 0, 100),
            num("stored-cap", 6.25, 0, 100),
            num("knockback", 1.0, 0, 4),
            num("lift", 0.5, 0, 2)),
    HEADSMAN("headsman", "Headsman", false,
            num("threshold", 0.4, 0.05, 1),
            num("bonus", 0.3, 0, 2),
            whole("regen-level", 2, 0, 5),
            time("regen-duration", 4, 0, 30)),

    // ---- Starforged ----
    STAR_LANCE("star-lance", "Star Lance", true,
            cooldown(29),
            num("range", 30, 4, 64),
            num("speed", 2, 0.5, 5),
            num("damage", 11.25, 0.5, 100),
            whole("slow-level", 4, 0, 6),
            time("slow-duration", 2, 0, 30),
            time("glow", 5, 0, 60)),
    CELESTIAL_PRISON("celestial-prison", "Celestial Prison", true,
            cooldown(40),
            num("radius", 6, 2, 16),
            time("duration", 3, 0.5, 10),
            num("damage", 12.5, 0.5, 100),
            num("launch", 0.7, 0, 3)),
    STARLIGHT("starlight", "Starlight", false,
            num("bonus", 0.3, 0, 2));

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

    /** The key in {@code config.yml}, e.g. {@code phantom-step}. */
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
