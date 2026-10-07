package io.github.drepfy.legendary;

import org.bukkit.Material;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** The five legendary weapons. F (or right-click) uses the first ability, Shift + F the second. */
public enum WeaponType {

    KUROGANE("kurogane", Material.NETHERITE_SWORD, Ability.PHANTOM_STEP, Ability.CRIMSON_TEMPEST, Ability.CRIMSON_HUNGER),
    SUGARCRASH("sugarcrash", Material.NETHERITE_SWORD, Ability.CANDY_REAPER, Ability.SUGAR_RUSH, Ability.SUGAR_HIGH),
    /** Took the Riftblade's place in 2.0: every Riftblade became a Wyrmfang. */
    WYRMFANG("wyrmfang", Material.NETHERITE_SWORD, Ability.WYRM_LUNGE, Ability.DRAGONS_BREATH, Ability.VENOM_FANG),
    GRAVEBREAKER("gravebreaker", Material.NETHERITE_AXE, Ability.EARTHSPLITTER, Ability.IRON_BASTION, Ability.HEADSMAN),
    STARFORGED("starforged", Material.NETHERITE_AXE, Ability.STAR_LANCE, Ability.CELESTIAL_PRISON, Ability.STARLIGHT);

    /** Weapons that were replaced: their items and registry entries are read as the new one. */
    private static final java.util.Map<String, String> REPLACED = java.util.Map.of("riftblade", "wyrmfang");

    private final String key;
    private final Material material;
    private final Ability primary;
    private final Ability secondary;
    private final Ability passive;

    WeaponType(String key, Material material, Ability primary, Ability secondary, Ability passive) {
        this.key = key;
        this.material = material;
        this.primary = primary;
        this.secondary = secondary;
        this.passive = passive;
    }

    public String key() {
        return key;
    }

    public Material material() {
        return material;
    }

    /** A fully charged hit with the bare item, before enchantments (vanilla's tooltip number). */
    public double attackDamage() {
        return material == Material.NETHERITE_AXE ? 10.0 : 8.0;
    }

    /** Hits per second at full charge. */
    public double attackSpeed() {
        return material == Material.NETHERITE_AXE ? 1.0 : 1.6;
    }

    /** F, or right-click. */
    public Ability primary() {
        return primary;
    }

    /** Shift + F, or sneak + right-click; the weapons with one ability use it for both. */
    public Ability secondary() {
        return secondary != null ? secondary : primary;
    }

    public boolean hasSecondary() {
        return secondary != null;
    }

    /** May be null. */
    public Ability passive() {
        return passive;
    }

    /** The abilities used with a key, in order. */
    public List<Ability> actives() {
        return secondary == null ? List.of(primary) : List.of(primary, secondary);
    }

    public List<Ability> abilities() {
        List<Ability> all = new ArrayList<>(actives());
        if (passive != null) {
            all.add(passive);
        }
        return Collections.unmodifiableList(all);
    }

    public static WeaponType byKey(String key) {
        if (key == null) {
            return null;
        }
        String wanted = key.trim().toLowerCase(Locale.ROOT).replace("_", "-");
        wanted = REPLACED.getOrDefault(wanted, wanted);
        for (WeaponType type : values()) {
            if (type.key.equals(wanted)) {
                return type;
            }
        }
        return null;
    }

    public static boolean isWeaponMaterial(Material material) {
        return material == Material.NETHERITE_SWORD || material == Material.NETHERITE_AXE;
    }
}
