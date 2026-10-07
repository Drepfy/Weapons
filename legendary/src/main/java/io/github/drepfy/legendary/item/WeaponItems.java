package io.github.drepfy.legendary.item;

import io.github.drepfy.legendary.Ability;
import io.github.drepfy.legendary.WeaponType;
import io.github.drepfy.legendary.config.Settings;
import io.github.drepfy.legendary.util.Compat;
import io.github.drepfy.legendary.util.Text;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Makes and recognises legendary weapons. Each one carries its type and a unique id in its
 * item data; the name and lore are only for show, so renaming or editing them changes nothing.
 */
public final class WeaponItems {

    private final NamespacedKey idKey;
    private final NamespacedKey typeKey;
    private final NamespacedKey lookKey;
    private final Supplier<Settings> settings;

    public WeaponItems(Plugin plugin, Supplier<Settings> settings) {
        this.idKey = new NamespacedKey(plugin, "id");
        this.typeKey = new NamespacedKey(plugin, "weapon");
        this.lookKey = new NamespacedKey(plugin, "look");
        this.settings = settings;
    }

    /** What a legendary item is: which weapon, and which copy. */
    public record Tag(UUID id, WeaponType type) {
        public String shortId() {
            return WeaponItems.shortId(id);
        }
    }

    public ItemStack create(WeaponType type, UUID id) {
        ItemStack item = new ItemStack(type.material());
        ItemMeta meta = item.getItemMeta();
        apply(meta, type, id);
        item.setItemMeta(meta);
        return item;
    }

    /** @return the weapon this item is, or null for any other item */
    public Tag read(ItemStack item) {
        if (item == null || !WeaponType.isWeaponMaterial(item.getType()) || !item.hasItemMeta()) {
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return null;
        }
        PersistentDataContainer data = meta.getPersistentDataContainer();
        String id = data.get(idKey, PersistentDataType.STRING);
        String type = data.get(typeKey, PersistentDataType.STRING);
        if (id == null || type == null) {
            return null;
        }
        WeaponType weapon = WeaponType.byKey(type);
        try {
            return weapon == null ? null : new Tag(UUID.fromString(id), weapon);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * The name of a legendary of an older version that no longer exists (it is taken away), or
     * null for any other item.
     */
    public String retired(ItemStack item) {
        if (item == null || !WeaponType.isWeaponMaterial(item.getType()) || !item.hasItemMeta()) {
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        String type = meta == null ? null : meta.getPersistentDataContainer().get(typeKey, PersistentDataType.STRING);
        if (!WeaponType.retired(type)) {
            return null;
        }
        String name = meta.hasDisplayName() ? org.bukkit.ChatColor.stripColor(meta.getDisplayName()).trim() : "";
        return name.isEmpty() ? type.substring(0, 1).toUpperCase(Locale.ROOT) + type.substring(1) : name;
    }

    public boolean isLegendary(ItemStack item) {
        return read(item) != null;
    }

    /**
     * Brings the name, lore, model and enchantments in line with config.yml (after a reload).
     *
     * @return whether the item changed
     */
    public boolean refresh(ItemStack item) {
        Tag tag = read(item);
        if (tag == null) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        String look = lookHash(tag.type(), tag.id());
        if (look.equals(meta.getPersistentDataContainer().get(lookKey, PersistentDataType.STRING))) {
            return false;
        }
        apply(meta, tag.type(), tag.id());
        item.setItemMeta(meta);
        return true;
    }

    /** The coloured weapon name, for messages. */
    public String displayName(WeaponType type) {
        return Text.color(settings.get().look(type).name());
    }

    public static String shortId(UUID id) {
        return id.toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }

    private void apply(ItemMeta meta, WeaponType type, UUID id) {
        Settings.Look look = settings.get().look(type);
        Map<String, String> values = placeholders(type, id);
        // A leading reset keeps the name and lore from turning italic.
        meta.setDisplayName("§r" + Text.color(fill(look.name(), values)));
        List<String> lore = new ArrayList<>();
        boolean listsEnchantments = false;
        boolean listsAttack = false;
        for (String line : look.lore()) {
            if (line.trim().equals("{enchantments}")) {
                listsEnchantments = true;
                lore.addAll(enchantmentLines(look));
            } else if (line.trim().equals("{attack}")) {
                listsAttack = true;
                lore.addAll(attackLines(type, look));
            } else {
                lore.add(line.isEmpty() ? "" : "§r" + Text.color(fill(line, values)));
            }
        }
        meta.setLore(lore);
        meta.setCustomModelData(look.customModelData() > 0 ? look.customModelData() : null);
        modern(meta, look);
        meta.setUnbreakable(look.unbreakable());
        for (Enchantment enchantment : new ArrayList<>(meta.getEnchants().keySet())) {
            meta.removeEnchant(enchantment);
        }
        for (Map.Entry<String, Integer> enchantment : look.enchantments().entrySet()) {
            Enchantment enchant = Compat.enchantment(enchantment.getKey());
            if (enchant != null) {
                meta.addEnchant(enchant, enchantment.getValue(), true);
            }
        }
        // The lore lists the enchantments (and unbreakable) in the weapon's own style instead.
        if (listsEnchantments) {
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_UNBREAKABLE);
        } else {
            meta.removeItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_UNBREAKABLE);
        }
        // The lore shows the real attack damage instead of vanilla's (which leaves Sharpness out).
        if (listsAttack) {
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        } else {
            meta.removeItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        }
        PersistentDataContainer data = meta.getPersistentDataContainer();
        data.set(idKey, PersistentDataType.STRING, id.toString());
        data.set(typeKey, PersistentDataType.STRING, type.key());
        data.set(lookKey, PersistentDataType.STRING, lookHash(type, id));
    }

    /** 1.21.2+: the resource pack model, the weapon's own tooltip frame, and the shimmer. */
    private static void modern(ItemMeta meta, Settings.Look look) {
        try {
            meta.setItemModel(look.itemModel().isEmpty() ? null : NamespacedKey.fromString(look.itemModel()));
        } catch (RuntimeException | LinkageError ignored) {
            // No item models before 1.21.2: custom model data does the job.
        }
        try {
            meta.setTooltipStyle(look.tooltipStyle().isEmpty() ? null : NamespacedKey.fromString(look.tooltipStyle()));
        } catch (RuntimeException | LinkageError ignored) {
            // No tooltip styles before 1.21.2.
        }
        try {
            // Always shimmering (like the Heart items), whatever it is enchanted with; or never.
            meta.setEnchantmentGlintOverride(look.glint());
        } catch (RuntimeException | LinkageError ignored) {
            // No glint override before 1.20.5.
        }
    }

    /** "Sharpness VII  ✦  Fire Aspect II", two to a line, then "Unbreakable". */
    private List<String> enchantmentLines(Settings.Look look) {
        Settings current = settings.get();
        String format = current.message("lore-enchantment");
        String separator = Text.color(current.message("lore-separator"));
        List<String> names = new ArrayList<>();
        for (Map.Entry<String, Integer> enchantment : look.enchantments().entrySet()) {
            names.add(Text.format(format, "name", enchantmentName(enchantment.getKey()), "level",
                    roman(enchantment.getValue())));
        }
        if (look.unbreakable() && !current.message("lore-unbreakable").isEmpty()) {
            names.add(Text.color(current.message("lore-unbreakable")));
        }
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < names.size(); i += 2) {
            String line = names.get(i) + (i + 1 < names.size() ? separator + names.get(i + 1) : "");
            lines.add("§r" + line);
        }
        return lines;
    }

    /**
     * The vanilla attack lines, with the real numbers: "When in Main Hand:", " 12 Attack Damage",
     * " 1.6 Attack Speed". Vanilla shows a Sharpness VII netherite sword as 8 Attack Damage, but
     * it hits for 12 (Sharpness adds 0.5 per level plus 0.5).
     */
    private List<String> attackLines(WeaponType type, Settings.Look look) {
        Settings current = settings.get();
        List<String> lines = new ArrayList<>();
        String header = current.message("lore-attack-header");
        if (!header.isEmpty()) {
            lines.add("§r" + Text.color(header));
        }
        String damage = current.message("lore-attack-damage");
        if (!damage.isEmpty()) {
            lines.add("§r" + Text.format(damage, "damage", number(attackDamage(type, look))));
        }
        String speed = current.message("lore-attack-speed");
        if (!speed.isEmpty()) {
            lines.add("§r" + Text.format(speed, "speed", number(type.attackSpeed())));
        }
        return lines;
    }

    /**
     * What a fully charged hit does before armour: the weapon's own damage plus Sharpness, times
     * the legendary melee bonus ({@code melee-damage}).
     */
    public double attackDamage(WeaponType type, Settings.Look look) {
        return baseAttackDamage(type, look) * settings.get().meleeDamage();
    }

    /** The weapon's own damage plus Sharpness, as vanilla counts it. */
    public static double baseAttackDamage(WeaponType type, Settings.Look look) {
        return type.attackDamage() + sharpnessDamage(look);
    }

    /** What the weapon's Sharpness adds to a fully charged hit (0.5 a level plus 0.5). */
    public static double sharpnessDamage(Settings.Look look) {
        int sharpness = 0;
        for (Map.Entry<String, Integer> enchantment : look.enchantments().entrySet()) {
            String key = enchantment.getKey();
            if (key.equals("sharpness") || key.equals("minecraft:sharpness")) {
                sharpness = enchantment.getValue();
            }
        }
        return sharpness > 0 ? 0.5 * sharpness + 0.5 : 0.0;
    }

    /** 12.0 → "12", 11.5 → "11.5". */
    static String number(double value) {
        double rounded = Math.round(value * 10.0) / 10.0;
        return rounded == Math.rint(rounded) ? Long.toString((long) rounded) : Double.toString(rounded);
    }

    /** "fire_aspect" → "Fire Aspect". */
    static String enchantmentName(String key) {
        StringBuilder name = new StringBuilder();
        for (String word : key.toLowerCase(Locale.ROOT).split("_")) {
            if (!word.isEmpty()) {
                name.append(name.length() == 0 ? "" : " ").append(Character.toUpperCase(word.charAt(0)))
                        .append(word.substring(1));
            }
        }
        return name.toString();
    }

    static String roman(int level) {
        if (level <= 0 || level > 3999) {
            return Integer.toString(level);
        }
        int[] values = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
        String[] numerals = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            while (level >= values[i]) {
                out.append(numerals[i]);
                level -= values[i];
            }
        }
        return out.toString();
    }

    private Map<String, String> placeholders(WeaponType type, UUID id) {
        Map<String, String> values = new TreeMap<>();
        values.put("id", shortId(id));
        values.put("key", settings.get().controls().key());
        values.put("sneak-key", settings.get().controls().sneakKey());
        for (Ability ability : type.abilities()) {
            settings.get().ability(ability).placeholders(values);
        }
        return values;
    }

    private String lookHash(WeaponType type, UUID id) {
        Settings current = settings.get();
        return Integer.toHexString((current.look(type).toString() + placeholders(type, id)
                + current.message("lore-enchantment") + current.message("lore-separator")
                + current.message("lore-unbreakable") + current.message("lore-attack-header")
                + current.message("lore-attack-damage") + current.message("lore-attack-speed") + current.meleeDamage()
                + "/4").hashCode());
    }

    private static String fill(String text, Map<String, String> values) {
        if (text.indexOf('{') < 0) {
            return text;
        }
        String result = text;
        for (Map.Entry<String, String> value : values.entrySet()) {
            result = result.replace("{" + value.getKey() + "}", value.getValue());
        }
        return result;
    }
}
