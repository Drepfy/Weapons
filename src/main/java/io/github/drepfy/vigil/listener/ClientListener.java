package io.github.drepfy.vigil.listener;

import io.github.drepfy.vigil.config.Settings;
import io.github.drepfy.vigil.util.Glob;
import io.github.drepfy.vigil.util.Text;
import io.github.drepfy.vigil.violation.AlertService;
import io.github.drepfy.vigil.violation.AutoBanService;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.bukkit.plugin.Plugin;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Kicks clients that identify themselves as hacked clients (client brand) or register
 * the plugin channels of hacked clients and world downloaders. This is passive: it only
 * reads what every client sends when it joins. With {@code block-all-mods} every
 * Fabric/Forge/Quilt client is kicked, which blocks mods such as Freecam, Tweakeroo,
 * Item Scroller and Meteor, but also harmless ones like Sodium.
 */
public final class ClientListener implements Listener {

    private static final List<String> MOD_LOADERS = List.of("*fabric*", "*forge*", "*quilt*");
    /** The brand arrives with or shortly after the join. */
    private static final long BRAND_DELAY_TICKS = 20;

    private final Plugin plugin;
    private final Supplier<Settings> settings;
    private final AlertService alerts;
    private final Logger logger;

    public ClientListener(Plugin plugin, Supplier<Settings> settings, AlertService alerts) {
        this.plugin = plugin;
        this.settings = settings;
        this.alerts = alerts;
        this.logger = plugin.getLogger();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                check(player, brand(player), player.getListeningPluginChannels());
            }
        }, BRAND_DELAY_TICKS);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChannel(PlayerRegisterChannelEvent event) {
        Player player = event.getPlayer();
        if (player.isOnline()) {
            check(player, null, Set.of(event.getChannel()));
        }
    }

    /**
     * Kicks the player if the brand or a channel is blocked.
     *
     * @return what was detected, or {@code null} if the client is allowed
     */
    public String check(Player player, String brand, Collection<String> channels) {
        Settings.ClientCheck config = settings.get().clientCheck();
        if (!config.enabled() || player.hasPermission(AutoBanService.PROTECT_PERMISSION)) {
            return null;
        }
        String detected = null;
        if (brand != null) {
            if (Glob.matchesAny(config.blockedBrands(), brand)) {
                detected = "hacked client '" + brand + "'";
            } else if (config.blockModLoaders() && Glob.matchesAny(MOD_LOADERS, brand)) {
                detected = "modded client '" + brand + "'";
            }
        }
        if (detected == null) {
            for (String channel : channels) {
                if (Glob.matchesAny(config.blockedChannels(), channel)) {
                    detected = "mod channel '" + channel + "'";
                    break;
                }
            }
        }
        if (detected == null) {
            return null;
        }
        Settings.Messages messages = settings.get().messages();
        String client = detected;
        player.kickPlayer(Text.color(Text.replace(messages.get("client-blocked"), "client", client)));
        String alert = Text.color(messages.get("prefix") + Text.replace(messages.get("client-blocked-alert"),
                "player", player.getName(), "client", client));
        alerts.notify(AlertService.ALERT_PERMISSION, alert);
        logger.info(ChatColor.stripColor(alert));
        return client;
    }

    /** Paper knows the client brand; elsewhere only channels are checked. */
    private static String brand(Player player) {
        try {
            return player.getClientBrandName();
        } catch (LinkageError | RuntimeException e) {
            return null;
        }
    }
}
