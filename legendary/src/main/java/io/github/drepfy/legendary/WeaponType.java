package io.github.drepfy.legendary;

import org.bukkit.Material;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** The five legendary weapons. Right-click uses the first ability, sneak + right-click the second. */
public enum WeaponType {

    KUROGANE("kurogane", Material.NETHERITE_SWORD, Ability.CRESCENT_DRAW, null, Ability.UNBROKEN_EDGE),
    SUGARCRASH("sugarcrash", Material.NETHERITE_SWORD, Ability.SUGAR_RUSH, Ability.SWEET_SHOCK, null),
    RIFTBLADE("riftblade", Material.NETHERITE_SWORD, Ability.RIFT_SLASH, Ability.RIFT_RECALL, null),
    GRAVEBREAKER("gravebreaker", Material.NETHERITE_AXE, Ability.EARTHSPLITTER, null, Ability.EXECUTIONERS_MARK),
    STARFORGED("starforged", Material.NETHERITE_AXE, Ability.ASTRAL_IMPACT, Ability.GRAVITY_WELL, null);

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

    /** Right-click. */
    public Ability primary() {
        return primary;
    }

    /** Sneak + right-click; the weapons with one ability use it for both. */
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

    /** The right-click abilities, in order. */
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
