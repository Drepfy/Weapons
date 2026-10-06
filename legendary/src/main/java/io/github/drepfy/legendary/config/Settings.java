package io.github.drepfy.legendary.config;

import io.github.drepfy.legendary.Ability;
import io.github.drepfy.legendary.WeaponType;

import java.util.List;
import java.util.Map;

/** Everything read from {@code config.yml}. */
public record Settings(
        String prefix,
        boolean oneOfEach,
        boolean dropOnDeath,
        Alts alts,
        List<String> blockedCommands,
        Controls controls,
        HitMobs hitMobs,
        boolean bossBars,
        Map<WeaponType, Look> looks,
        Map<Ability, AbilitySettings> abilities,
        Map<String, List<SoundSpec>> sounds,
        Map<String, String> messages,
        List<String> warnings) {

    public AbilitySettings ability(Ability ability) {
        AbilitySettings settings = abilities.get(ability);
        return settings != null ? settings : AbilitySettings.defaults(ability);
    }

    public Look look(WeaponType type) {
        return looks.get(type);
    }

    public String message(String key) {
        return messages.getOrDefault(key, "");
    }

    public List<SoundSpec> sound(String key) {
        return sounds.getOrDefault(key, List.of());
    }

    /** Alt protection: no passing weapons between accounts on the same IP. */
    public record Alts(boolean enabled, long rememberMs, int maxAccountsPerIp) {
    }

    /** Which keys use the abilities. */
    public enum Controls {
        /** F (the swap-offhand key) and Shift + F. */
        OFFHAND("F", "Shift + F"),
        /** Right-click and sneak + right-click. */
        RIGHT_CLICK("Right-click", "Sneak + right-click"),
        /** Both of the above. */
        BOTH("F", "Shift + F");

        private final String key;
        private final String sneakKey;

        Controls(String key, String sneakKey) {
            this.key = key;
            this.sneakKey = sneakKey;
        }

        public boolean offhand() {
            return this != RIGHT_CLICK;
        }

        public boolean rightClick() {
            return this != OFFHAND;
        }

        /** How the first ability's key reads in lore and help: {key}. */
        public String key() {
            return key;
        }

        /** And the second's: {sneak-key}. */
        public String sneakKey() {
            return sneakKey;
        }
    }

    /** Which mobs the abilities hit besides players. */
    public enum HitMobs {
        HOSTILE, ALL, NONE
    }

    /**
     * How a weapon looks. Name and lore are uncoloured config text. {@code tooltipStyle} and
     * {@code itemModel} are resource pack ids ("" = none); {@code glint} is the enchantment shine.
     */
    public record Look(String name, List<String> lore, int customModelData, String itemModel, String tooltipStyle,
                       boolean glint, boolean unbreakable, Map<String, Integer> enchantments,
                       org.bukkit.boss.BarColor barColor, String barText) {
    }

    public record SoundSpec(String key, float volume, float pitch) {
    }
}
