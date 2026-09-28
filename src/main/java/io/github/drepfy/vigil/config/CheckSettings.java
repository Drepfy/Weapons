package io.github.drepfy.vigil.config;

import io.github.drepfy.vigil.api.CheckType;

import java.util.List;
import java.util.Map;

/**
 * Validated, immutable settings of one check.
 */
public record CheckSettings(CheckType type,
                            boolean enabled,
                            double alertVl,
                            double reviewVl,
                            double decayPerMinute,
                            double vlPerFlag,
                            double bufferThreshold,
                            boolean mitigate,
                            List<ActionRule> actions,
                            Map<String, Double> numbers,
                            Map<String, List<String>> lists) {

    public CheckSettings {
        actions = List.copyOf(actions);
        numbers = Map.copyOf(numbers);
        lists = Map.copyOf(lists);
    }

    /**
     * A numeric check option. Unknown keys return the spec default so a programming
     * mistake can never crash a check at runtime.
     */
    public double num(String key) {
        Double value = numbers.get(key);
        if (value != null) {
            return value;
        }
        for (CheckSpec.NumberOption option : CheckSpec.of(type).numbers()) {
            if (option.key().equals(key)) {
                return option.defaultValue();
            }
        }
        throw new IllegalArgumentException("Unknown option " + type.id() + "." + key);
    }

    public long millis(String key) {
        return Math.round(num(key));
    }

    public List<String> list(String key) {
        List<String> value = lists.get(key);
        return value != null ? value : List.of();
    }

    /** Settings built from the spec defaults only. */
    public static CheckSettings defaults(CheckType type) {
        CheckSpec spec = CheckSpec.of(type);
        CheckSpec.Defaults d = spec.defaults();
        Map<String, Double> numbers = new java.util.HashMap<>();
        for (CheckSpec.NumberOption option : spec.numbers()) {
            numbers.put(option.key(), option.defaultValue());
        }
        Map<String, List<String>> lists = new java.util.HashMap<>();
        for (CheckSpec.ListOption option : spec.lists()) {
            lists.put(option.key(), option.defaultValue());
        }
        return new CheckSettings(type, d.enabled(), d.alertVl(), d.reviewVl(), d.decayPerMinute(), d.vlPerFlag(),
                d.bufferThreshold(), d.mitigate(), List.of(), numbers, lists);
    }
}
