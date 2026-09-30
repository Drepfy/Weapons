package io.github.drepfy.vigil.violation;

import io.github.drepfy.vigil.config.CheckSettings;
import io.github.drepfy.vigil.config.Settings;
import io.github.drepfy.vigil.data.FlagRecord;
import io.github.drepfy.vigil.data.PlayerData;
import io.github.drepfy.vigil.storage.AtomicFiles;
import io.github.drepfy.vigil.storage.IoExecutor;
import io.github.drepfy.vigil.util.Clock;
import io.github.drepfy.vigil.util.Text;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Sends rate-limited staff alerts and keeps each staff member's alert toggle.
 */
public final class AlertService {

    public static final String ALERT_PERMISSION = "vigil.alerts";

    private final Supplier<Settings> settings;
    private final Logger logger;
    private final IoExecutor io;
    private final Path preferencesFile;
    private final Set<UUID> disabled = ConcurrentHashMap.newKeySet();

    private final io.github.drepfy.vigil.moderation.DiscordNotifier discord;

    public AlertService(Supplier<Settings> settings, Logger logger, IoExecutor io, Path preferencesFile,
                        io.github.drepfy.vigil.moderation.DiscordNotifier discord) {
        this.discord = discord;
        this.settings = settings;
        this.logger = logger;
        this.io = io;
        this.preferencesFile = preferencesFile;
        loadPreferences();
    }

    /** Called for every recorded flag; decides whether staff should hear about it. */
    public void onFlag(Player player, PlayerData data, FlagRecord flag, CheckSettings check, double tps) {
        Settings.Alerts config = settings.get().alerts();
        if (!config.enabled() || flag.vl() < check.alertVl()) {
            return;
        }
        long now = Clock.now();
        Long last = data.lastAlertMs.get(flag.check());
        if (last != null && now - last < config.cooldownMs()) {
            data.suppressedAlerts.merge(flag.check(), 1, Integer::sum);
            return;
        }
        data.lastAlertMs.put(flag.check(), now);
        Integer suppressed = data.suppressedAlerts.remove(flag.check());

        String message = Text.color(settings.get().messages().get("prefix") + Text.replace(config.format(),
                "player", player.getName(),
                "reason", flag.check().reason(),
                "check", flag.check().displayName(),
                "vl", Text.num(Math.round(flag.vl() * 10) / 10.0),
                "detail", flag.detail(),
                "ping", flag.ping(),
                "tps", Text.num(tps),
                "ban-at", check.banVl() > 0 ? Text.num(check.banVl()) : "-"))
                + (suppressed != null && suppressed > 0 ? Text.color(" &8(+" + suppressed + " more)") : "");

        List<String> hover = List.of(
                "&7Player: &f" + player.getName(),
                "&7Flagged for: &f" + flag.check().reason() + " &8(" + flag.check().displayName() + " check)",
                "&7VL: &f" + Text.num(flag.vl()) + (check.banVl() > 0 && settings.get().autoBan().enabled()
                        ? " &8(auto-ban at " + Text.num(check.banVl()) + ")" : " &8(no auto-ban)"),
                "&7Evidence: &f" + flag.detail(),
                "&7Location: &f" + flag.world() + " " + Text.num(flag.x()) + ", " + Text.num(flag.y()) + ", "
                        + Text.num(flag.z()),
                "&7Ping: &f" + flag.ping() + "ms &7TPS: &f" + Text.num(tps),
                "",
                "&eClick to run /ac check " + player.getName());

        broadcast(message, String.join("\n", hover), "/ac check " + player.getName(), config.clickable());
        if (discord != null) {
            discord.alert(player.getName(), flag.check().reason(), Text.num(Math.round(flag.vl() * 10) / 10.0),
                    flag.detail());
        }
        if (config.console()) {
            logger.info(org.bukkit.ChatColor.stripColor(message) + " - " + flag.detail());
        }
    }

    /** Sends a message to every online staff member who has alerts enabled. */
    public void broadcast(String coloredMessage, String hoverLegacy, String command, boolean clickable) {
        BaseComponent[] components = null;
        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (!staff.hasPermission(ALERT_PERMISSION) || disabled.contains(staff.getUniqueId())) {
                continue;
            }
            if (clickable && command != null) {
                if (components == null) {
                    components = clickable(coloredMessage, hoverLegacy, command);
                }
                if (components != null) {
                    try {
                        staff.spigot().sendMessage(components);
                        continue;
                    } catch (LinkageError | RuntimeException e) {
                        // Chat components not supported by this server: plain text below.
                        components = null;
                        clickable = false;
                    }
                }
            }
            staff.sendMessage(coloredMessage);
        }
    }

    /** Sends to staff holding a specific permission who have alerts enabled. */
    public void notify(String permission, String coloredMessage) {
        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (staff.hasPermission(permission) && !disabled.contains(staff.getUniqueId())) {
                staff.sendMessage(coloredMessage);
            }
        }
    }

    public boolean isEnabledFor(UUID uuid) {
        return !disabled.contains(uuid);
    }

    /** Toggles alerts for a staff member and returns the new state. */
    public boolean toggle(UUID uuid) {
        boolean nowEnabled;
        if (disabled.remove(uuid)) {
            nowEnabled = true;
        } else {
            disabled.add(uuid);
            nowEnabled = false;
        }
        savePreferences();
        return nowEnabled;
    }

    private BaseComponent[] clickable(String message, String hover, String command) {
        try {
            TextComponent root = new TextComponent(TextComponent.fromLegacyText(message));
            root.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                    new net.md_5.bungee.api.chat.hover.content.Text(TextComponent.fromLegacyText(Text.color(hover)))));
            root.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command));
            return new BaseComponent[] {root};
        } catch (LinkageError | RuntimeException e) {
            // Chat component API unavailable on this server: fall back to plain text.
            return null;
        }
    }

    private void loadPreferences() {
        if (!Files.exists(preferencesFile)) {
            return;
        }
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.loadFromString(Files.readString(preferencesFile, StandardCharsets.UTF_8));
            for (String raw : yaml.getStringList("alerts-disabled")) {
                try {
                    disabled.add(UUID.fromString(raw));
                } catch (IllegalArgumentException ignored) {
                    // Skip malformed entries.
                }
            }
        } catch (IOException | InvalidConfigurationException | RuntimeException e) {
            Path moved = AtomicFiles.quarantine(preferencesFile);
            logger.warning("Staff preferences unreadable (" + e.getMessage() + "); moved to "
                    + (moved != null ? moved.getFileName() : "<move failed>") + ". Alerts default to on.");
        }
    }

    private void savePreferences() {
        List<String> snapshot = new ArrayList<>();
        for (UUID uuid : disabled) {
            snapshot.add(uuid.toString());
        }
        io.execute("save staff preferences", () -> {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.set("alerts-disabled", snapshot);
            try {
                AtomicFiles.write(preferencesFile, yaml.saveToString());
            } catch (IOException e) {
                logger.warning("Could not save staff preferences: " + e.getMessage());
            }
        });
    }
}
