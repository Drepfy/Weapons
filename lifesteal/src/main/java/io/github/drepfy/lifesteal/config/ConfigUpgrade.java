package io.github.drepfy.lifesteal.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Adds the settings newer versions brought to an existing config.yml, with their comments, and
 * leaves everything else (and the owner's own comments) as it was. Settings that are missing
 * still work with their defaults; this only makes them visible.
 */
public final class ConfigUpgrade {

    private static final Pattern EFFECTS = Pattern.compile("(?m)^effects:");
    private static final Pattern MESSAGES = Pattern.compile("(?m)^(# Placeholders[^\\n]*\\n)?messages:");
    private static final Pattern NATURAL_DEATH = Pattern.compile("(?m)^([ \\t]+)natural-death:[^\\n]*$");

    /** 1.1.0: titles, sounds and floating hearts. */
    public static final String EFFECTS_BLOCK = """
            # What players see and hear when hearts change hands.
            effects:
              # A title in the middle of the screen: "+1 ❤" for the killer, "-1 ❤" for the player who lost it
              # (also when a Heart item is used). The words are title-gain, title-lose and the subtitles below.
              titles: true
              # Hearts float up round a player who gains one (everyone near sees them).
              particles: true
              # "<sound> <volume> <pitch>", any Minecraft sound, or "" for none.
              sound-gain: "entity.player.levelup 0.7 1.4"
              sound-lose: "block.respawn_anchor.deplete 0.8 1.3"

            """;

    private static final String[] TITLE_MESSAGES = {
            "# Titles (effects.titles). \"\" leaves that line empty.",
            "title-gain: \"&c+{count} ❤\"",
            "title-lose: \"&4-{count} ❤\"",
            "subtitle-stolen: \"&7stolen from &f{victim}\"",
            "subtitle-taken: \"&7taken by &f{killer}\"",
            "subtitle-hearts: \"&7You now have &c{hearts} &7hearts\""};

    private ConfigUpgrade() {
    }

    /** @return whether the file was changed */
    public static boolean upgrade(Path file, Logger logger) {
        try {
            if (!Files.exists(file)) {
                return false;
            }
            String text = Files.readString(file, StandardCharsets.UTF_8);
            String upgraded = upgrade(text);
            if (upgraded.equals(text)) {
                return false;
            }
            Files.writeString(file, upgraded, StandardCharsets.UTF_8);
            logger.info("config.yml: added the new settings for titles, sounds and floating hearts (effects).");
            return true;
        } catch (IOException | RuntimeException e) {
            logger.warning("Could not update config.yml (" + e.getMessage() + "); the new settings use their defaults.");
            return false;
        }
    }

    public static String upgrade(String text) {
        String result = text.replace("\r\n", "\n");
        if (!EFFECTS.matcher(result).find()) {
            Matcher messages = MESSAGES.matcher(result);
            if (messages.find()) {
                result = result.substring(0, messages.start()) + EFFECTS_BLOCK + result.substring(messages.start());
            } else {
                result = result + (result.endsWith("\n") ? "\n" : "\n\n") + EFFECTS_BLOCK.stripTrailing() + "\n";
            }
        }
        if (!result.contains("title-gain:")) {
            Matcher line = NATURAL_DEATH.matcher(result);
            if (line.find()) {
                StringBuilder added = new StringBuilder();
                for (String message : TITLE_MESSAGES) {
                    added.append('\n').append(line.group(1)).append(message);
                }
                result = result.substring(0, line.end()) + added + result.substring(line.end());
            }
        }
        return result.equals(text.replace("\r\n", "\n")) ? text : result;
    }
}
