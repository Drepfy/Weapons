package io.github.drepfy.lifesteal.config;

import org.bukkit.Material;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Everything in config.yml, checked and immutable. A reload swaps the whole snapshot.
 *
 * @param startHearts        hearts of a new player
 * @param minHearts          nobody goes below this
 * @param maxHearts          nobody goes above this (more becomes a Heart item)
 * @param perKill            hearts taken from the victim per kill
 * @param loseOnNaturalDeath dying without a player killer also costs hearts
 * @param healGainedHearts   gained hearts are filled, not just empty containers
 * @param disabledWorlds     worlds where nothing is stolen
 * @param cooldownMs         the same killer cannot steal from the same victim again for this long
 * @param cooldownBothWays   the cooldown also stops the victim taking the heart straight back
 */
public record Settings(int startHearts,
                       int minHearts,
                       int maxHearts,
                       int perKill,
                       boolean loseOnNaturalDeath,
                       boolean healGainedHearts,
                       Set<String> disabledWorlds,
                       long cooldownMs,
                       boolean cooldownBothWays,
                       Withdraw withdraw,
                       HeartItem item,
                       Recipe recipe,
                       ResourcePack resourcePack,
                       AltProtection alts,
                       Effects effects,
                       boolean logToFile,
                       Messages messages,
                       List<String> warnings) {

    public Settings {
        disabledWorlds = Set.copyOf(disabledWorlds);
        warnings = List.copyOf(warnings);
    }

    /** @param maxPerCommand most hearts one /withdraw may take */
    public record Withdraw(boolean enabled, int maxPerCommand) {
    }

    /**
     * How the Heart item looks.
     *
     * @param customModelData model number for the resource pack (0 = none)
     * @param glow            shine like an enchanted item
     */
    public record HeartItem(Material material, String name, List<String> lore, int customModelData, boolean glow) {
        public HeartItem {
            lore = List.copyOf(lore);
        }
    }

    /**
     * The crafting recipe for a Heart.
     *
     * @param shape       up to 3 rows of up to 3 letters (space = empty)
     * @param ingredients letter → material
     */
    public record Recipe(boolean enabled, List<String> shape, Map<Character, Material> ingredients) {
        public Recipe {
            shape = List.copyOf(shape);
            ingredients = Map.copyOf(ingredients);
        }
    }

    /**
     * The Heart texture pack sent to players.
     *
     * @param url  direct download link ("" = not sent by the plugin)
     * @param sha1 SHA-1 of the pack ("" = the bundled pack's)
     */
    public record ResourcePack(String url, String sha1, boolean required, String prompt) {
    }

    /**
     * Stops heart farming with alternate accounts.
     *
     * @param sharedIp         accounts that used the same IP address count as one person
     * @param rememberIpMs     how long an IP address is remembered
     * @param maxAccountsPerIp IPs used by more accounts are shared networks and ignored
     * @param minPlaytimeMs    accounts that played less neither gain nor lose hearts
     * @param notifyStaff      tell lifesteal.notify when a kill is not counted
     */
    public record AltProtection(boolean enabled, boolean sharedIp, long rememberIpMs, int maxAccountsPerIp,
                                long minPlaytimeMs, boolean notifyStaff) {
    }

    /**
     * What players see and hear when hearts change hands.
     *
     * @param titles    a title in the middle of the screen with the hearts won or lost
     * @param particles hearts float up round a player who gains one (seen by everyone near)
     * @param gain      played to a player who gains hearts (null = none)
     * @param lose      played to a player who loses hearts (null = none)
     */
    public record Effects(boolean titles, boolean particles, SoundSpec gain, SoundSpec lose) {
    }

    /** A vanilla (or resource pack) sound by its key, e.g. {@code entity.player.levelup}. */
    public record SoundSpec(String key, float volume, float pitch) {
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
