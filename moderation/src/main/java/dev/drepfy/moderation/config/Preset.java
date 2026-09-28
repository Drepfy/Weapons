package dev.drepfy.moderation.config;

import dev.drepfy.moderation.model.Length;

/**
 * A named offence with a predefined length and reason, e.g. {@code /mute Steve spam}.
 */
public record Preset(String name, Length length, String reason) {
}
