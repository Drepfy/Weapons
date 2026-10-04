package io.github.drepfy.combat.config;

import java.util.List;
import java.util.Map;

/**
 * config.yml, checked and immutable. A reload swaps the whole snapshot.
 *
 * @param combatMs     how long combat lasts after the last hit
 * @param armor        what counts as "wearing armor" for the kill rule
 * @param logout       what happens to a player who logs out in combat
 * @param pearlMs      Ender Pearl cooldown
 * @param pearlOverlay show the cooldown on the pearl in the hotbar (the grey sweep)
 */
public record Settings(long combatMs,
                       ArmorRule armor,
                       LogoutRule logout,
                       long pearlMs,
                       boolean pearlOverlay,
                       Messages messages,
                       List<String> warnings) {

    public Settings {
        warnings = List.copyOf(warnings);
    }

    /** What counts as wearing armor when you kill someone. */
    public enum ArmorRule {
        /** A helmet, chestplate, leggings or boots (elytra, pumpkins and heads are not armor). */
        ARMOR_PIECES,
        /** Anything in a helmet, chestplate, leggings or boots slot. */
        ANY_ITEM
    }

    /** A player who logs out in combat. */
    public enum LogoutRule {
        /** The timer is paused and continues when they come back. */
        KEEP,
        /** They die where they logged out (their items drop); the attacker gets the kill. */
        KILL
    }

    public record Messages(Map<String, String> values) {
        public Messages {
            values = Map.copyOf(values);
        }

        public String get(String key) {
            String value = values.get(key);
            return value != null ? value : SettingsLoader.DEFAULT_MESSAGES.getOrDefault(key, key);
        }
    }
}
