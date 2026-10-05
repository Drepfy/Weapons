package io.github.drepfy.legendary.item;

import io.github.drepfy.legendary.Ability;
import io.github.drepfy.legendary.WeaponType;
import io.github.drepfy.legendary.config.Settings;
import io.github.drepfy.legendary.util.Compat;
import io.github.drepfy.legendary.util.Text;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
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
        for (String line : look.lore()) {
            lore.add(line.isEmpty() ? "" : "§r" + Text.color(fill(line, values)));
        }
        meta.setLore(lore);
        meta.setCustomModelData(look.customModelData() > 0 ? look.customModelData() : null);
        if (!look.itemModel().isEmpty()) {
            Compat.itemModel(meta, NamespacedKey.fromString(look.itemModel()));
        } else {
            try {
                if (meta.hasItemModel()) {
                    meta.setItemModel(null);
                }
            } catch (RuntimeException | LinkageError ignored) {
                // No item models before 1.21.2.
            }
        }
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
        PersistentDataContainer data = meta.getPersistentDataContainer();
        data.set(idKey, PersistentDataType.STRING, id.toString());
        data.set(typeKey, PersistentDataType.STRING, type.key());
        data.set(lookKey, PersistentDataType.STRING, lookHash(type, id));
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
        return Integer.toHexString((settings.get().look(type).toString() + placeholders(type, id)).hashCode());
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
