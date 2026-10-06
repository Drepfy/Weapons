package io.github.drepfy.combat.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Brings an older config.yml up to date, keeping everything else (and the comments) as the owner
 * left it.
 * <ul>
 *   <li>1.2.0: logging out in combat now kills (it used to be the default to keep the player
 *       alive), and the new command settings are added.</li>
 *   <li>1.3.0: the sounds for getting into and out of combat are added.</li>
 * </ul>
 */
public final class ConfigUpgrade {

    private static final Pattern LOGOUT = Pattern.compile("(?m)^(\\s*)logout:\\s*keep\\s*$");
    private static final Pattern LOGOUT_LINE = Pattern.compile("(?m)^(\\s*)logout:[^\\n]*$");
    private static final Pattern SOUNDS = Pattern.compile("(?m)^sounds:");
    private static final Pattern MESSAGES = Pattern.compile("(?m)^(# \\{seconds\\}[^\\n]*\\n)?messages:");

    public static final String SOUNDS_BLOCK = """
            # Sounds for the player concerned: "<sound> <volume> <pitch>", any Minecraft sound, or "" for none.
            sounds:
              # When you get into combat (once, not on every hit).
              combat-start: "block.note_block.bass 0.8 0.7"
              # When your combat time runs out.
              combat-end: "entity.experience_orb.pickup 0.6 1.2"

            """;

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
            if (!text.contains("block-commands:")) {
                logger.info("config.yml updated for 1.2.0: players who log out in combat now die and drop their "
                        + "items (combat.logout: kill), and commands are blocked in combat (combat.block-commands).");
            }
            if (!SOUNDS.matcher(text).find()) {
                logger.info("config.yml: added the sounds for getting into and out of combat (sounds).");
            }
            return true;
        } catch (IOException | RuntimeException e) {
            logger.warning("Could not update config.yml (" + e.getMessage() + "); the new settings use their defaults.");
            return false;
        }
    }

    public static String upgrade(String text) {
        String result = text.contains("block-commands:") ? text : commands(text);
        if (!SOUNDS.matcher(result).find()) {
            Matcher messages = MESSAGES.matcher(result);
            if (messages.find()) {
                result = result.substring(0, messages.start()) + SOUNDS_BLOCK + result.substring(messages.start());
            } else {
                result = result + (result.endsWith("\n") ? "\n" : "\n\n") + SOUNDS_BLOCK.stripTrailing() + "\n";
            }
        }
        return result;
    }

    /** 1.2.0. */
    private static String commands(String text) {
        String result = LOGOUT.matcher(text).replaceFirst("$1logout: kill");
        result = result.replace("#   keep: nothing else happens (default)", "#   keep: nothing else happens")
                .replace("#   kill: the player also dies where they logged out and drops their items (not when they",
                        "#   kill: the player also dies where they logged out and drops their items (default; not when they");
        Matcher line = LOGOUT_LINE.matcher(result);
        if (!line.find()) {
            return result;
        }
        String indent = line.group(1);
        String added = "\n" + indent + "# No commands in combat (/tpa, /rtp, /spawn, /ah...). The ones listed in allowed-commands"
                + "\n" + indent + "# still work. Staff with the permission combat.bypass.commands can use every command."
                + "\n" + indent + "block-commands: true"
                + "\n" + indent + "allowed-commands: [combat, ct, combattag]";
        return result.substring(0, line.end()) + added + result.substring(line.end());
    }
}
