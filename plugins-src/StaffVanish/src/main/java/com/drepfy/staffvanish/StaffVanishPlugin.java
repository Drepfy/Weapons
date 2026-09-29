package com.drepfy.staffvanish;

import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;

// Not final: MockBukkit subclasses the main class in tests.
public class StaffVanishPlugin extends JavaPlugin {

    private @Nullable VanishModule vanish;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        vanish = new VanishModule(this);
        vanish.enable();
    }

    @Override
    public void onDisable() {
        if (vanish != null) {
            vanish.disable();
            vanish = null;
        }
    }

    /**
     * @return the vanish module, or {@code null} while the plugin is disabled
     */
    public @Nullable VanishModule vanish() {
        return vanish;
    }
}
