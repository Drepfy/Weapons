package io.github.drepfy.vigil.moderation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * A predefined reason such as {@code Cheating}, with the punishment length for each
 * offence: {@code [7d, 30d, perm]} means 7 days the first time, 30 days the second
 * time and permanent from the third time on.
 *
 * @param name      what staff type (and what tab completion suggests)
 * @param durations lengths per offence in ms ({@link Durations#PERMANENT} allowed);
 *                  empty when the preset has no default length
 */
public record ReasonPreset(String name, List<Long> durations) {

    public ReasonPreset {
        durations = List.copyOf(durations);
    }

    /** A preset with a single length (or none when {@code defaultDuration} is null). */
    public ReasonPreset(String name, Long defaultDuration) {
        this(name, defaultDuration == null ? List.of() : List.of(defaultDuration));
    }

    /** The reason as shown to players: underscores become spaces. */
    public String display() {
        return name.replace('_', ' ');
    }

    /** Length for the first offence, or {@code null} without a default. */
    public Long defaultDuration() {
        return durations.isEmpty() ? null : durations.get(0);
    }

    /** Length after {@code previousOffences} earlier punishments for this reason (the last step repeats). */
    public Long durationFor(int previousOffences) {
        if (durations.isEmpty()) {
            return null;
        }
        return durations.get(Math.min(Math.max(0, previousOffences), durations.size() - 1));
    }

    /** Whether the length grows with repeated offences. */
    public boolean escalates() {
        return durations.size() > 1;
    }

    /** "7 days, 30 days, Permanent", or "" without a default. */
    public String ladderText(String permanentText) {
        List<String> parts = new ArrayList<>();
        for (Long duration : durations) {
            parts.add(Durations.format(duration, permanentText));
        }
        return String.join(", ", parts);
    }

    /** Finds a preset by name, ignoring case, spaces, underscores and dashes. */
    public static ReasonPreset find(Collection<ReasonPreset> presets, String input) {
        if (input == null || input.isBlank()) {
            return null;
        }
        String needle = normalize(input);
        for (ReasonPreset preset : presets) {
            if (normalize(preset.name()).equals(needle)) {
                return preset;
            }
        }
        return null;
    }

    /** "1st", "2nd", "3rd", "4th"... */
    public static String ordinal(int number) {
        int lastTwo = number % 100;
        String suffix = lastTwo >= 11 && lastTwo <= 13 ? "th" : switch (number % 10) {
            case 1 -> "st";
            case 2 -> "nd";
            case 3 -> "rd";
            default -> "th";
        };
        return number + suffix;
    }

    private static String normalize(String text) {
        return text.toLowerCase(Locale.ROOT).replace("_", "").replace(" ", "").replace("-", "");
    }
}
