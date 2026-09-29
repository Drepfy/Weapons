package com.drepfy.staffvanish.staff;

import com.drepfy.staffvanish.VanishModule;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * Per-staff toggles for what happens while they're vanished. Stored on the player (persistent data), so they survive
 * reconnects and restarts without a data file. Unset options use the defaults from {@code staff-tools.defaults}.
 */
public final class StaffPreferences {

    public enum Option {
        ITEM_PICKUP,
        NIGHT_VISION,
        SILENT_CONTAINERS,
        BOSS_BAR,
        GLOW;

        public String configKey() {
            return name().toLowerCase(Locale.ROOT).replace('_', '-');
        }
    }

    private final VanishModule module;
    private final Map<Option, NamespacedKey> keys = new EnumMap<>(Option.class);

    public StaffPreferences(Plugin plugin, VanishModule module) {
        this.module = module;
        for (Option option : Option.values()) {
            keys.put(option, new NamespacedKey(plugin, "pref_" + option.name().toLowerCase(Locale.ROOT)));
        }
    }

    public boolean get(Player player, Option option) {
        Byte value = player.getPersistentDataContainer().get(keys.get(option), PersistentDataType.BYTE);
        return value != null ? value != 0 : module.settings().staffTools().defaultFor(option);
    }

    public void set(Player player, Option option, boolean enabled) {
        PersistentDataContainer data = player.getPersistentDataContainer();
        data.set(keys.get(option), PersistentDataType.BYTE, (byte) (enabled ? 1 : 0));
    }

    public boolean toggle(Player player, Option option) {
        boolean enabled = !get(player, option);
        set(player, option, enabled);
        return enabled;
    }
}
