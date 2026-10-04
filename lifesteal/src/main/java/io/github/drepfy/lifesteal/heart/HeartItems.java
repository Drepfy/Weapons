package io.github.drepfy.lifesteal.heart;

import io.github.drepfy.lifesteal.config.Settings;
import io.github.drepfy.lifesteal.util.Compat;
import io.github.drepfy.lifesteal.util.Text;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * The Heart item: red dye (by default) with a hidden tag that marks it as a real Heart.
 * Only the tag counts, so red dye renamed to "Heart" in an anvil is still red dye.
 */
public final class HeartItems {

    private final NamespacedKey key;
    private final Supplier<Settings> settings;

    public HeartItems(Plugin plugin, Supplier<Settings> settings) {
        this.key = new NamespacedKey(plugin, "heart");
        this.settings = settings;
    }

    /** {@code amount} Heart items (one stack; at most the material's stack size). */
    public ItemStack create(int amount) {
        Settings.HeartItem config = settings.get().item();
        ItemStack item = new ItemStack(config.material(), Math.max(1, amount));
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.setDisplayName(Text.color("&r" + config.name()));
        List<String> lore = new ArrayList<>();
        for (String line : config.lore()) {
            lore.add(Text.color("&r" + line));
        }
        meta.setLore(lore);
        if (config.customModelData() > 0) {
            meta.setCustomModelData(config.customModelData());
        }
        if (config.glow() && !Compat.glint(meta)) {
            // Before 1.20.5: an enchantment nobody can see.
            Enchantment unbreaking = Enchantment.getByKey(NamespacedKey.minecraft("unbreaking"));
            if (unbreaking != null) {
                meta.addEnchant(unbreaking, 1, true);
                meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            }
        }
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    /** As many stacks as needed for {@code amount} Hearts. */
    public List<ItemStack> createStacks(int amount) {
        List<ItemStack> stacks = new ArrayList<>();
        int max = Math.max(1, settings.get().item().material().getMaxStackSize());
        int left = amount;
        while (left > 0) {
            int size = Math.min(max, left);
            stacks.add(create(size));
            left -= size;
        }
        return stacks;
    }

    public boolean isHeart(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    public boolean containsHeart(ItemStack[] items) {
        if (items == null) {
            return false;
        }
        for (ItemStack item : items) {
            if (isHeart(item)) {
                return true;
            }
        }
        return false;
    }

    public NamespacedKey key() {
        return key;
    }
}
