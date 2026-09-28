package io.github.drepfy.vigil.config;

import io.github.drepfy.vigil.api.CheckType;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Declares every configurable option of every check, with its default value and
 * valid range. The configuration loader validates against these specs and the
 * test suite verifies that the bundled config.yml matches them exactly.
 */
public record CheckSpec(CheckType type, Defaults defaults, List<NumberOption> numbers, List<ListOption> lists) {

    /** Defaults for the options every check shares. */
    public record Defaults(boolean enabled, double alertVl, double reviewVl, double decayPerMinute,
                           double vlPerFlag, double bufferThreshold, boolean mitigate) {
    }

    public record NumberOption(String key, double defaultValue, double min, double max) {
    }

    public record ListOption(String key, List<String> defaultValue) {
    }

    private static final Map<CheckType, CheckSpec> SPECS = new EnumMap<>(CheckType.class);

    static {
        register(new CheckSpec(CheckType.SPEED,
                new Defaults(true, 3, 10, 1.0, 1.0, 1, false),
                List.of(
                        num("leniency", 1.25, 1.0, 10.0),
                        num("burst-ticks", 20, 5, 200),
                        num("extra-blocks", 2.0, 0.0, 50.0),
                        num("ice-multiplier", 3.2, 1.0, 20.0),
                        num("ceiling-multiplier", 1.8, 1.0, 20.0),
                        num("slime-multiplier", 2.0, 1.0, 20.0),
                        num("soul-speed-multiplier", 1.8, 1.0, 20.0),
                        num("surface-memory-ms", 2000, 0, 30000),
                        num("velocity-credit", 15.0, 0.0, 200.0)),
                List.of()));
        register(new CheckSpec(CheckType.FLIGHT,
                new Defaults(true, 2, 6, 1.0, 1.0, 1, false),
                List.of(
                        num("hover-window-ms", 1000, 500, 10000),
                        num("min-descent", 1.0, 0.1, 10.0),
                        num("ascend-tolerance", 1.0, 0.25, 20.0),
                        num("confirm-ms", 1000, 0, 10000),
                        num("impulse-max-rise-ms", 4000, 1000, 60000)),
                List.of()));
        register(new CheckSpec(CheckType.VERTICAL,
                new Defaults(true, 2, 6, 1.0, 1.0, 2, false),
                List.of(num("tolerance", 0.1, 0.0, 5.0)),
                List.of()));
        register(new CheckSpec(CheckType.GROUND_SPOOF,
                new Defaults(true, 3, 8, 1.0, 1.0, 8, false),
                List.of(
                        num("vertical-tolerance", 0.5, 0.1, 3.0),
                        num("horizontal-tolerance", 0.3, 0.0, 2.0)),
                List.of()));
        register(new CheckSpec(CheckType.TIMER,
                new Defaults(true, 3, 10, 1.0, 1.0, 1, false),
                List.of(
                        num("max-debt-ms", 600, 150, 10000),
                        num("max-credit-ms", 1000, 100, 10000),
                        num("stall-forgiveness-ms", 2500, 500, 30000)),
                List.of()));
        register(new CheckSpec(CheckType.REACH,
                new Defaults(true, 3, 10, 1.0, 1.0, 3, true),
                List.of(
                        num("leniency", 0.5, 0.1, 5.0),
                        num("cancel-leniency", 2.0, 0.5, 10.0),
                        num("lag-window-extra-ms", 250, 0, 2000),
                        num("max-lag-window-ms", 1000, 100, 5000),
                        num("mob-leniency", 0.6, 0.0, 5.0)),
                List.of()));
        register(new CheckSpec(CheckType.HIT_ANGLE,
                new Defaults(true, 3, 8, 1.0, 1.0, 4, false),
                List.of(
                        num("max-angle", 70.0, 20.0, 180.0),
                        num("min-distance", 1.2, 0.0, 6.0)),
                List.of()));
        register(new CheckSpec(CheckType.WALL_HIT,
                new Defaults(true, 3, 8, 1.0, 1.0, 3, false),
                List.of(num("max-traces-per-second", 10, 1, 100)),
                List.of()));
        register(new CheckSpec(CheckType.BLOCK_REACH,
                new Defaults(true, 2, 6, 1.0, 1.0, 2, true),
                List.of(
                        num("leniency", 1.0, 0.1, 5.0),
                        num("cancel-leniency", 1.5, 0.5, 10.0)),
                List.of()));
        register(new CheckSpec(CheckType.WALL_INTERACT,
                new Defaults(true, 3, 8, 1.0, 1.0, 3, false),
                List.of(),
                List.of(new ListOption("blocks", List.of("CHEST", "TRAPPED_CHEST", "BARREL", "ENDER_CHEST",
                        "*SHULKER_BOX", "FURNACE", "BLAST_FURNACE", "SMOKER", "HOPPER", "DROPPER", "DISPENSER",
                        "BREWING_STAND")))));
    }

    private static NumberOption num(String key, double def, double min, double max) {
        return new NumberOption(key, def, min, max);
    }

    private static void register(CheckSpec spec) {
        SPECS.put(spec.type(), spec);
    }

    public static CheckSpec of(CheckType type) {
        return SPECS.get(type);
    }
}
