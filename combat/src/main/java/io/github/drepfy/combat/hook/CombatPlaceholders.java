package io.github.drepfy.combat.hook;

import io.github.drepfy.combat.CombatPlugin;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * PlaceholderAPI placeholders, for scoreboards, tab lists and chat plugins:
 * <pre>
 * %combat_in_combat%   true or false
 * %combat_time%        seconds of combat left (0 out of combat)
 * %combat_pearl%       seconds until the next Ender Pearl (0 = ready)
 * %combat_opponent%    the player they fought last ("" out of combat)
 * </pre>
 * Plugins may ask from other threads: they get the values of the last half second, read on
 * the server thread; on the server thread itself the values are live.
 */
public final class CombatPlaceholders extends PlaceholderExpansion {

    private record Values(int time, int pearl, String opponent) {
    }

    private final CombatPlugin plugin;
    private volatile Map<UUID, Values> snapshot = Map.of();

    public CombatPlaceholders(CombatPlugin plugin) {
        this.plugin = plugin;
    }

    /** Registers the placeholders (only call when PlaceholderAPI is enabled). */
    public static void hook(CombatPlugin plugin) {
        CombatPlaceholders placeholders = new CombatPlaceholders(plugin);
        if (placeholders.register()) {
            placeholders.refresh();
            Bukkit.getScheduler().runTaskTimer(plugin, placeholders::refresh, 10L, 10L);
            plugin.getLogger().info("PlaceholderAPI: %combat_time%, %combat_in_combat% and more are ready.");
        }
    }

    /** Reads the values other threads get (server thread, twice a second). */
    public void refresh() {
        Map<UUID, Values> values = new HashMap<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            values.put(player.getUniqueId(), live(player));
        }
        snapshot = Map.copyOf(values);
    }

    private Values live(Player player) {
        String opponent = plugin.lastOpponent(player);
        return new Values(CombatPlugin.seconds(plugin.combatRemaining(player)),
                CombatPlugin.seconds(plugin.pearlCooldown(player)), opponent == null ? "" : opponent);
    }

    @Override
    public String getIdentifier() {
        return "combat";
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
        if (!key.equals("in_combat") && !key.equals("time") && !key.equals("pearl") && !key.equals("opponent")) {
            return null; // Not one of ours: PlaceholderAPI leaves it as it is.
        }
        Values values = null;
        if (player != null) {
            Player online = player.getPlayer();
            values = Bukkit.isPrimaryThread() && online != null ? live(online) : snapshot.get(player.getUniqueId());
        }
        if (values == null) {
            values = new Values(0, 0, "");
        }
        return switch (key) {
            case "in_combat" -> String.valueOf(values.time() > 0);
            case "time" -> String.valueOf(values.time());
            case "pearl" -> String.valueOf(values.pearl());
            default -> values.opponent();
        };
    }
}
