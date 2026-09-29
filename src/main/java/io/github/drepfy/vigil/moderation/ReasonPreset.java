package io.github.drepfy.vigil.moderation;

import java.util.Collection;
import java.util.Locale;

/**
 * A predefined reason such as {@code Cheating}, optionally with a default duration.
 *
 * @param name            what staff type (and what tab completion suggests)
 * @param defaultDuration default duration in ms, {@link Durations#PERMANENT}, or
 *                        {@code null} when the preset has none
 */
public record ReasonPreset(String name, Long defaultDuration) {

    /** The reason as shown to players: underscores become spaces. */
    public String display() {
        return name.replace('_', ' ');
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

    private static String normalize(String text) {
        return text.toLowerCase(Locale.ROOT).replace("_", "").replace(" ", "").replace("-", "");
    }
}
