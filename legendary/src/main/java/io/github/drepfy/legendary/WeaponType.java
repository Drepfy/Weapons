package io.github.drepfy.legendary;

import org.bukkit.Material;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** The five legendary weapons. F (or right-click) uses the first ability, Shift + F the second. */
public enum WeaponType {

    KUROGANE("kurogane", Material.NETHERITE_SWORD, Ability.CRIMSON_FLASH, Ability.IAIDO, Ability.CRIMSON_EDGE),
    SUGARCRASH("sugarcrash", Material.NETHERITE_SWORD, Ability.CANDY_HOOK, Ability.CANDY_BARRAGE, Ability.SUGAR_HIGH),
    RIFTBLADE("riftblade", Material.NETHERITE_SWORD, Ability.VOID_REND, Ability.RIFT_SWAP, Ability.PHASE_SHIFT),
    GRAVEBREAKER("gravebreaker", Material.NETHERITE_AXE, Ability.EXECUTIONERS_LEAP, Ability.GRAVE_RISE, Ability.LAST_RITES),
    STARFORGED("starforged", Material.NETHERITE_AXE, Ability.STARFALL, Ability.SINGULARITY, Ability.STARSTRUCK);

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
