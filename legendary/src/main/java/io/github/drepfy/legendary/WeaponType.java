package io.github.drepfy.legendary;

import org.bukkit.Material;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The four legendary weapons. Each has one ability, used with Shift + F (or sneak + right-click),
 * and one passive that works on its own.
 */
public enum WeaponType {

    KATANA("katana", Material.NETHERITE_SWORD, Ability.DRAW, Ability.BLEED),
    CANDY_CANE("candycane", Material.NETHERITE_SWORD, Ability.SUGAR_TRAP, Ability.STICKY_SWEET),
    CRUSH("crush", Material.NETHERITE_AXE, Ability.CRUSH, Ability.HEAVY),
    REAPER("reaper", Material.NETHERITE_SWORD, Ability.REAP, Ability.EXECUTION);

    /**
     * Weapons of older versions that live on as one of these (3.0): their items and registry
     * entries are read as the new weapon, which takes over the same id and holder.
     */
    private static final Map<String, String> REPLACED = Map.of(
            "kurogane", "katana", "sugarcrash", "candycane", "gravebreaker", "crush",
            "wyrmfang", "reaper", "riftblade", "reaper");

    /** Weapons of older versions that have no successor: their items are taken away. */
    private static final Set<String> RETIRED = Set.of("starforged");

    private final String key;
    private final Material material;
    private final Ability active;
    private final Ability passive;

    WeaponType(String key, Material material, Ability active, Ability passive) {
        this.key = key;
        this.material = material;
        this.active = active;
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

    /** The ability (Shift + F). */
    public Ability active() {
        return active;
    }

    public Ability passive() {
        return passive;
    }

    /** The abilities used with a key (one per weapon). */
    public List<Ability> actives() {
        return List.of(active);
    }

    public List<Ability> abilities() {
        return List.of(active, passive);
    }

    public static WeaponType byKey(String key) {
        String wanted = normal(key);
        if (wanted == null) {
            return null;
        }
        wanted = REPLACED.getOrDefault(wanted, wanted);
        for (WeaponType type : values()) {
            if (type.key.equals(wanted)) {
                return type;
            }
        }
        return null;
    }

    /** Whether this was a weapon of an older version that no longer exists (its items are taken away). */
    public static boolean retired(String key) {
        String wanted = normal(key);
        return wanted != null && RETIRED.contains(wanted);
    }

    /** "Candy Cane", "candy-cane" and "candy_cane" all mean candycane. */
    private static String normal(String key) {
        return key == null ? null : key.trim().toLowerCase(Locale.ROOT).replace("_", "").replace("-", "").replace(" ", "");
    }

    public static boolean isWeaponMaterial(Material material) {
        return material == Material.NETHERITE_SWORD || material == Material.NETHERITE_AXE;
    }
}
