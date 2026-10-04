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
        HitMobs hitMobs,
        boolean actionBar,
        boolean readySound,
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

    /** Which mobs the abilities hit besides players. */
    public enum HitMobs {
        HOSTILE, ALL, NONE
    }

    /** How a weapon looks. Name and lore are uncoloured config text. */
    public record Look(String name, List<String> lore, int customModelData, String itemModel, boolean unbreakable,
                       Map<String, Integer> enchantments) {
    }

    public record SoundSpec(String key, float volume, float pitch) {
    }
}
