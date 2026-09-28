package dev.drepfy.moderation.command;

import dev.drepfy.moderation.config.Preset;
import dev.drepfy.moderation.model.Length;
import dev.drepfy.moderation.util.Durations;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;

/**
 * The part of a punishment command after the target: {@code [duration] [reason...] [-s]}.
 *
 * @param length          the duration typed by staff, if any
 * @param invalidDuration a token that looked like a duration but didn't parse, or {@code null}
 * @param reason          the reason text, possibly empty
 * @param silent          whether {@code -s} was given
 */
public record PunishmentArguments(Optional<Length> length, String invalidDuration, String reason, boolean silent) {

    /**
     * @param args           arguments after the target name
     * @param acceptDuration whether the first argument may be a duration (false for kicks)
     */
    public static PunishmentArguments parse(List<String> args, boolean acceptDuration) {
        boolean silent = false;
        List<String> rest = new ArrayList<>(args.size());
        for (String arg : args) {
            String lower = arg.toLowerCase(Locale.ROOT);
            if (lower.equals("-s") || lower.equals("--silent")) {
                silent = true;
            } else if (!arg.isEmpty()) {
                rest.add(arg);
            }
        }

        Optional<Length> length = Optional.empty();
        String invalid = null;
        if (acceptDuration && !rest.isEmpty()) {
            String first = rest.getFirst();
            if (Durations.isPermanentKeyword(first) || Durations.looksLikeDuration(first)) {
                rest.removeFirst();
                length = Length.parse(first);
                if (length.isEmpty()) {
                    invalid = first;
                }
            }
        }
        return new PunishmentArguments(length, invalid, String.join(" ", rest).strip(), silent);
    }

    /**
     * Applies a preset named by the first word of the reason: {@code /mute Steve spam in chat}
     * becomes the preset's reason plus "(in chat)", with the preset's length unless one was typed.
     */
    public PunishmentArguments withPreset(Function<String, Optional<Preset>> presets) {
        if (reason.isEmpty()) {
            return this;
        }
        int space = reason.indexOf(' ');
        Optional<Preset> preset = presets.apply(space < 0 ? reason : reason.substring(0, space));
        if (preset.isEmpty()) {
            return this;
        }
        String extra = space < 0 ? "" : reason.substring(space + 1).strip();
        String presetReason = extra.isEmpty() ? preset.get().reason() : preset.get().reason() + " (" + extra + ")";
        Optional<Length> presetLength = length.isPresent() ? length : Optional.of(preset.get().length());
        return new PunishmentArguments(presetLength, invalidDuration, presetReason, silent);
    }
}
