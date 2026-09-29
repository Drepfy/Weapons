package io.github.drepfy.vigil.violation;

import io.github.drepfy.vigil.config.CheckSettings;
import io.github.drepfy.vigil.config.Settings;
import io.github.drepfy.vigil.data.FlagRecord;
import io.github.drepfy.vigil.data.PlayerData;
import io.github.drepfy.vigil.moderation.Durations;
import io.github.drepfy.vigil.moderation.ModerationListener;
import io.github.drepfy.vigil.moderation.ModerationService;
import io.github.drepfy.vigil.moderation.Punishment;
import io.github.drepfy.vigil.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Bans a player automatically once a check's violation level reaches its {@code ban-at}.
 *
 * <p>The violation level only grows through repeated, buffered flags and decays over
 * time, so a single uncertain detection can never reach it. Players with
 * {@code vigil.protect} are never banned automatically (staff are told instead), and
 * nothing is banned in passive mode.
 */
public final class AutoBanService {

    public static final String STAFF_NAME = "Anti-Cheat";
    public static final String PROTECT_PERMISSION = "vigil.protect";

    private final Plugin plugin;
    private final Supplier<Settings> settings;
    private final ModerationService moderation;
    private final ModerationListener formatter;
    private final AlertService alerts;
    private final Logger logger;
    private final Consumer<String> auditLog;

    public AutoBanService(Plugin plugin, Supplier<Settings> settings, ModerationService moderation,
                          ModerationListener formatter, AlertService alerts, Logger logger, Consumer<String> auditLog) {
        this.plugin = plugin;
        this.settings = settings;
        this.moderation = moderation;
        this.formatter = formatter;
        this.alerts = alerts;
        this.logger = logger;
        this.auditLog = auditLog;
    }

    public void onFlag(Player player, PlayerData data, FlagRecord flag, CheckSettings check) {
        Settings config = settings.get();
        Settings.AutoBan autoBan = config.autoBan();
        if (!autoBan.enabled() || config.general().passiveMode() || check.banVl() <= 0 || flag.vl() < check.banVl()
                || data.autoBanned) {
            return;
        }
        data.autoBanned = true;
        String reason = Text.replace(autoBan.reason(), "reason", flag.check().reason(),
                "check", flag.check().displayName());
        String prefix = config.messages().get("prefix");

        if (player.hasPermission(PROTECT_PERMISSION)) {
            String text = Text.color(prefix + "&e" + player.getName() + " &7reached the auto-ban limit for &c"
                    + flag.check().reason() + " &7but is protected (vigil.protect).");
            alerts.notify(AlertService.ALERT_PERMISSION, text);
            logger.info(ChatColor.stripColor(text));
            return;
        }

        String permanent = config.messages().get("permanent");
        auditLog.accept("[auto-ban] " + player.getName() + " (" + player.getUniqueId() + ") for " + reason
                + ", " + Durations.format(autoBan.durationMs(), permanent) + ", " + flag.check().id()
                + " VL " + Text.num(flag.vl()));
        if (!autoBan.command().isEmpty()) {
            String command = Text.replace(autoBan.command(), "player", player.getName(),
                    "uuid", player.getUniqueId(), "reason", reason,
                    "duration", autoBan.durationMs() == Durations.PERMANENT ? "" : Durations.compact(autoBan.durationMs()));
            // Run on the next tick: never kick or ban from inside a movement/combat event.
            Bukkit.getScheduler().runTask(plugin, () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command));
        } else {
            Punishment ban = moderation.ban(player.getUniqueId(), player.getName(), reason, STAFF_NAME,
                    autoBan.durationMs());
            String screen = formatter.screen("ban-screen", ban);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) {
                    player.kickPlayer(screen);
                }
            });
        }

        String announcement = Text.color(prefix + Text.replace(config.messages().get("auto-banned"),
                "player", player.getName(), "reason", flag.check().reason(), "check", flag.check().displayName(),
                "duration", Durations.format(autoBan.durationMs(), permanent)));
        logger.info(ChatColor.stripColor(announcement));
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (!online.equals(player) && (autoBan.broadcast() || online.hasPermission(AlertService.ALERT_PERMISSION))) {
                online.sendMessage(announcement);
            }
        }
    }
}
