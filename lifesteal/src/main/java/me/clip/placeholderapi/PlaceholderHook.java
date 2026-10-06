package me.clip.placeholderapi;

import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

/**
 * Compile-time stand-in for PlaceholderAPI's class of the same name (same signatures as
 * PlaceholderAPI 2.11 and newer). It is left out of the plugin jar: on the server the real one,
 * from PlaceholderAPI, is used.
 */
public abstract class PlaceholderHook {

    public String onRequest(final OfflinePlayer player, final String params) {
        return null;
    }

    public String onPlaceholderRequest(final Player player, final String params) {
        return null;
    }
}
