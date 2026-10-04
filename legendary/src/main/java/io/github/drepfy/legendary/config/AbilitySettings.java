package io.github.drepfy.legendary.config;

import io.github.drepfy.legendary.Ability;
import io.github.drepfy.legendary.util.Text;

import java.util.HashMap;
import java.util.Map;

/** The values of one ability's settings (already checked). Times are in seconds. */
public final class AbilitySettings {

    private final Ability ability;
    private final String name;
    private final Map<String, Double> values;

    public AbilitySettings(Ability ability, String name, Map<String, Double> values) {
        this.ability = ability;
        this.name = name;
        this.values = Map.copyOf(values);
    }

    /** Every setting at its default. */
    public static AbilitySettings defaults(Ability ability) {
        Map<String, Double> values = new HashMap<>();
        for (Ability.Option option : ability.options()) {
            values.put(option.key(), option.def());
        }
        return new AbilitySettings(ability, ability.defaultName(), values);
    }

    public Ability ability() {
        return ability;
    }

    /** The name players see ("Crescent Draw"). */
    public String name() {
        return name;
    }

    public double num(String key) {
        Double value = values.get(key);
        if (value == null) {
            throw new IllegalArgumentException(ability.key() + " has no setting " + key);
        }
        return value;
    }

    public int whole(String key) {
        return (int) Math.round(num(key));
    }

    /** A time setting in server ticks (20 a second). */
    public int ticks(String key) {
        return (int) Math.round(num(key) * 20.0);
    }

    /** How a setting reads in lore: {@code 8s}, {@code 2.5}, {@code 4}. */
    public String display(String key) {
        Ability.Option option = ability.option(key);
        double value = num(key);
        return option.kind() == Ability.Kind.TIME ? Text.seconds(value) : Text.number(value);
    }

    /** {@code crescent-draw.cooldown} → {@code 8s} and so on, plus {@code crescent-draw.name}. */
    public void placeholders(Map<String, String> into) {
        into.put(ability.key() + ".name", name);
        for (Ability.Option option : ability.options()) {
            into.put(ability.key() + "." + option.key(), display(option.key()));
        }
    }
}
