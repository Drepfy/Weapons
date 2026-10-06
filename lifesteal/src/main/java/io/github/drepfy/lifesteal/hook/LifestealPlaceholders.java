package io.github.drepfy.lifesteal.hook;

import io.github.drepfy.lifesteal.LifestealPlugin;
import io.github.drepfy.lifesteal.data.LifestealStore;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * PlaceholderAPI placeholders, for scoreboards, tab lists, holograms and chat plugins:
 * <pre>
 * %lifesteal_hearts%            the player's hearts
 * %lifesteal_max%, _min, _start the limits in config.yml
 * %lifesteal_top_1_name%        the player with the most hearts (1 to 10)
 * %lifesteal_top_1_hearts%      their hearts
 * </pre>
 * The top list is read once a second. Plugins may ask for a player's hearts from other threads:
 * they get the value of the last second; on the server thread the value is live.
 */
public final class LifestealPlaceholders extends PlaceholderExpansion {

    private static final int TOP = 10;

    private record Snapshot(Map<UUID, Integer> hearts, List<String> names, List<Integer> topHearts) {
    }

    private final LifestealPlugin plugin;
    private volatile Snapshot snapshot = new Snapshot(Map.of(), List.of(), List.of());

    public LifestealPlaceholders(LifestealPlugin plugin) {
        this.plugin = plugin;
    }

    /** Registers the placeholders (only call when PlaceholderAPI is enabled). */
    public static void hook(LifestealPlugin plugin) {
        LifestealPlaceholders placeholders = new LifestealPlaceholders(plugin);
        if (placeholders.register()) {
            placeholders.refresh();
            Bukkit.getScheduler().runTaskTimer(plugin, placeholders::refresh, 20L, 20L);
            plugin.getLogger().info("PlaceholderAPI: %lifesteal_hearts%, %lifesteal_top_1_name% and more are ready.");
        }
    }

    /** Reads the values other threads get (server thread, once a second). */
    public void refresh() {
        Map<UUID, Integer> hearts = new HashMap<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            hearts.put(player.getUniqueId(), plugin.hearts().hearts(player));
        }
        List<String> names = new ArrayList<>();
        List<Integer> top = new ArrayList<>();
        for (Map.Entry<UUID, LifestealStore.Entry> entry : plugin.store().ranking()) {
            if (names.size() == TOP) {
                break;
            }
            names.add(String.valueOf(entry.getValue().name()));
            top.add(entry.getValue().hearts());
        }
        snapshot = new Snapshot(Map.copyOf(hearts), List.copyOf(names), List.copyOf(top));
    }

    @Override
    public String getIdentifier() {
        return "lifesteal";
    }

    @Override
    public String getAuthor() {
        return "Drepfy";
    }

    @Override
    @SuppressWarnings("deprecation") // getDescription(): Paper's replacement is not on Spigot.
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true; // Stays registered through /papi reload.
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        String key = params.toLowerCase(Locale.ROOT);
        switch (key) {
            case "max":
                return String.valueOf(plugin.settings().maxHearts());
            case "min":
                return String.valueOf(plugin.settings().minHearts());
            case "start":
                return String.valueOf(plugin.settings().startHearts());
            case "hearts":
                return hearts(player);
            default:
                break;
        }
        if (key.startsWith("top_")) {
            return top(key);
        }
        return null; // Not one of ours: PlaceholderAPI leaves it as it is.
    }

    private String hearts(OfflinePlayer player) {
        if (player == null) {
            return "";
        }
        if (Bukkit.isPrimaryThread()) {
            Player online = player.getPlayer();
            int hearts = online != null ? plugin.hearts().hearts(online) : plugin.hearts().hearts(player.getUniqueId());
            return hearts < 0 ? String.valueOf(plugin.settings().startHearts()) : String.valueOf(hearts);
        }
        Integer hearts = snapshot.hearts().get(player.getUniqueId());
        return hearts != null ? String.valueOf(hearts) : "";
    }

    /** top_3_name, top_3_hearts. */
    private String top(String key) {
        String[] parts = key.split("_");
        if (parts.length != 3 || !(parts[2].equals("name") || parts[2].equals("hearts"))) {
            return null;
        }
        int rank;
        try {
            rank = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            return null;
        }
        if (rank < 1 || rank > TOP) {
            return null;
        }
        Snapshot now = snapshot;
        if (rank > now.names().size()) {
            return parts[2].equals("name") ? "-" : "0";
        }
        return parts[2].equals("name") ? now.names().get(rank - 1) : String.valueOf(now.topHearts().get(rank - 1));
    }
}
