package io.github.drepfy.vigil.config;

import io.github.drepfy.vigil.api.CheckType;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Declares every option of every check with its default value and valid range.
 * Only {@code enabled} and {@code ban-at} appear in the default config.yml; all
 * other options are optional fine-tuning that can be added under the check
 * (e.g. {@code speed: {enabled: true, ban-at: 15, leniency: 1.3}}).
 */
public record CheckSpec(CheckType type, Defaults defaults, List<NumberOption> numbers, List<ListOption> lists) {

    /**
     * Defaults for the options every check shares.
     *
     * @param banVl VL at which the player is automatically banned (0 = never)
     */
    public record Defaults(boolean enabled, double alertVl, double banVl, double decayPerMinute,
                           double vlPerFlag, double bufferThreshold, boolean mitigate) {
    }

    public record NumberOption(String key, double defaultValue, double min, double max) {
    }

    public record ListOption(String key, List<String> defaultValue) {
    }

    private static final Map<CheckType, CheckSpec> SPECS = new EnumMap<>(CheckType.class);

    static {
        register(CheckType.SPEED, new Defaults(true, 1, 15, 1.0, 1.0, 1, true),
                num("leniency", 1.25, 1.0, 10.0),
                num("burst-ticks", 20, 5, 200),
                num("extra-blocks", 2.0, 0.0, 50.0),
                num("ice-multiplier", 3.2, 1.0, 20.0),
                num("ceiling-multiplier", 1.8, 1.0, 20.0),
                num("slime-multiplier", 2.0, 1.0, 20.0),
                num("soul-speed-multiplier", 1.8, 1.0, 20.0),
                num("surface-memory-ms", 2000, 0, 30000),
                num("velocity-credit", 15.0, 0.0, 200.0));
        register(CheckType.FLIGHT, new Defaults(true, 1, 8, 1.0, 1.0, 1, true),
                num("hover-window-ms", 500, 250, 10000),
                num("min-descent", 0.5, 0.05, 10.0),
                num("ascend-tolerance", 0.8, 0.25, 20.0),
                num("confirm-ms", 500, 0, 10000),
                num("impulse-max-rise-ms", 4000, 1000, 60000));
        register(CheckType.STEP, new Defaults(true, 1, 8, 1.0, 1.0, 2, true),
                num("tolerance", 0.1, 0.0, 5.0));
        register(CheckType.NOFALL, new Defaults(true, 1, 10, 1.0, 1.0, 5, false),
                num("vertical-tolerance", 0.5, 0.1, 3.0),
                num("horizontal-tolerance", 0.3, 0.0, 2.0));
        register(CheckType.TIMER, new Defaults(true, 1, 10, 1.0, 1.0, 1, false),
                num("max-debt-ms", 400, 150, 10000),
                num("max-credit-ms", 1000, 100, 10000),
                num("stall-forgiveness-ms", 2500, 500, 30000));
        register(CheckType.NOSLOW, new Defaults(true, 1, 12, 1.0, 1.0, 2, false),
                num("max-speed", 0.13, 0.05, 2.0),
                num("grace-ms", 500, 0, 5000));
        register(CheckType.VELOCITY, new Defaults(true, 1, 10, 1.0, 1.0, 2, false),
                num("min-ratio", 0.25, 0.05, 1.0),
                num("window-extra-ms", 600, 100, 5000));
        register(CheckType.REACH, new Defaults(true, 1, 10, 1.0, 1.0, 2, true),
                num("leniency", 0.3, 0.05, 5.0),
                num("cancel-leniency", 2.0, 0.5, 10.0),
                num("lag-window-extra-ms", 150, 0, 2000),
                num("max-lag-window-ms", 1000, 100, 5000),
                num("mob-leniency", 0.6, 0.0, 5.0));
        register(CheckType.KILLAURA, new Defaults(true, 1, 10, 1.0, 1.0, 3, false),
                num("hitbox-expand", 0.3, 0.05, 3.0),
                num("min-distance", 1.0, 0.0, 6.0));
        register(CheckType.NOSWING, new Defaults(true, 1, 10, 1.0, 1.0, 3, false),
                num("swing-window-ms", 200, 50, 2000));
        register(CheckType.WALLHIT, new Defaults(true, 1, 8, 1.0, 1.0, 3, false),
                num("max-traces-per-second", 10, 1, 100));
        register(CheckType.AUTOCLICKER, new Defaults(true, 1, 0, 1.0, 1.0, 3, false),
                num("max-cps", 25, 8, 100));
        register(CheckType.MACE, new Defaults(true, 1, 3, 1.0, 1.0, 1, true),
                num("min-rise", 2.0, 1.0, 50.0));
        register(CheckType.BLOCKREACH, new Defaults(true, 1, 8, 1.0, 1.0, 2, true),
                num("leniency", 0.4, 0.05, 5.0),
                num("cancel-leniency", 0.8, 0.1, 10.0));
        register(CheckType.INTERACT, new Defaults(true, 1, 8, 1.0, 1.0, 2, true),
                num("margin", 0.05, 0.0, 1.0));
        register(CheckType.FASTPLACE, new Defaults(true, 1, 12, 1.0, 1.0, 2, false),
                num("max-per-second", 20, 5, 100));
        register(CheckType.NUKER, new Defaults(true, 1, 8, 1.0, 1.0, 2, true),
                num("max-per-second", 30, 10, 200));
        register(CheckType.XRAY, new Defaults(true, 1, 0, 0.1, 1.0, 4, false),
                num("max-blocks-per-vein", 30, 5, 500));
        SPECS.put(CheckType.CHESTAURA, new CheckSpec(CheckType.CHESTAURA, new Defaults(true, 1, 8, 1.0, 1.0, 3, false),
                List.of(),
                List.of(new ListOption("blocks", List.of("CHEST", "TRAPPED_CHEST", "BARREL", "ENDER_CHEST",
                        "*SHULKER_BOX", "FURNACE", "BLAST_FURNACE", "SMOKER", "HOPPER", "DROPPER", "DISPENSER",
                        "BREWING_STAND")))));
    }

    private static NumberOption num(String key, double def, double min, double max) {
        return new NumberOption(key, def, min, max);
    }

    private static void register(CheckType type, Defaults defaults, NumberOption... numbers) {
        SPECS.put(type, new CheckSpec(type, defaults, List.of(numbers), List.of()));
    }

    public static CheckSpec of(CheckType type) {
        return SPECS.get(type);
    }
}
