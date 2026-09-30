package io.github.drepfy.vigil.violation;

import io.github.drepfy.vigil.config.CheckSettings;
import io.github.drepfy.vigil.config.Settings;
import io.github.drepfy.vigil.data.FlagRecord;
import io.github.drepfy.vigil.data.PlayerData;
import io.github.drepfy.vigil.moderation.DiscordNotifier;
import io.github.drepfy.vigil.moderation.Durations;
import io.github.drepfy.vigil.moderation.ModerationListener;
import io.github.drepfy.vigil.moderation.ModerationService;
import io.github.drepfy.vigil.moderation.Punishment;
import io.github.drepfy.vigil.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
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

    public static final String STAFF_NAME = ModerationListener.ANTI_CHEAT_STAFF;
    public static final String PROTECT_PERMISSION = "vigil.protect";
    /** How long the ban animation plays before the kick. */
    private static final long ANIMATION_TICKS = 60;

    private final Plugin plugin;
    private final Supplier<Settings> settings;
    private final ModerationService moderation;
    private final ModerationListener formatter;
    private final AlertService alerts;
    private final Logger logger;
    private final Consumer<String> auditLog;
    private final DiscordNotifier discord;

    public AutoBanService(Plugin plugin, Supplier<Settings> settings, ModerationService moderation,
                          ModerationListener formatter, AlertService alerts, Logger logger, Consumer<String> auditLog,
                          DiscordNotifier discord) {
        this.plugin = plugin;
        this.settings = settings;
        this.moderation = moderation;
        this.formatter = formatter;
        this.alerts = alerts;
        this.logger = logger;
        this.auditLog = auditLog;
        this.discord = discord;
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
        long duration = autoBan.durationFor(moderation.previousAutoBans(player.getUniqueId(), STAFF_NAME));
        auditLog.accept("[auto-ban] " + player.getName() + " (" + player.getUniqueId() + ") for " + reason
                + ", " + Durations.format(duration, permanent) + ", " + flag.check().id()
                + " VL " + Text.num(flag.vl()));
        long delay = 1L;
        if (autoBan.animation()) {
            // Held in place (no moving, hitting or building) while everyone watches the animation.
            data.frozenUntilMs = io.github.drepfy.vigil.util.Clock.now() + ANIMATION_TICKS * 50L + 1000L;
            playAnimation(player, flag.check().reason());
            delay = ANIMATION_TICKS;
        }
        if (!autoBan.command().isEmpty()) {
            String command = Text.replace(autoBan.command(), "player", player.getName(),
                    "uuid", player.getUniqueId(), "reason", reason,
                    "duration", duration == Durations.PERMANENT ? "" : Durations.compact(duration));
            // Never kick or ban from inside a movement/combat event.
            Bukkit.getScheduler().runTaskLater(plugin, () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command),
                    delay);
        } else {
            // Recorded now, so leaving during the animation does not help.
            Punishment ban = moderation.ban(player.getUniqueId(), player.getName(), reason, STAFF_NAME, duration);
            discord.punishment(ban);
            String screen = formatter.banScreen(ban);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) {
                    player.kickPlayer(screen);
                }
            }, delay);
        }

        String announcement = banner(config, player.getName(), flag.check().reason(), flag.check().displayName(),
                Durations.format(duration, permanent));
        logger.info(ChatColor.stripColor(announcement.replace("\n", " ")));
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (autoBan.broadcast() || online.hasPermission(AlertService.ALERT_PERMISSION)) {
                online.sendMessage(announcement);
            }
        }
    }

    /** The chat announcement; a one-line message gets the prefix, a multi-line banner stands alone. */
    public static String banner(Settings config, String player, String reason, String check, String duration) {
        String template = config.messages().get("auto-banned");
        String prefix = config.messages().get("prefix");
        if (!template.contains("{prefix}") && !template.contains("\n")) {
            template = prefix + template;
        }
        return Text.color(Text.replace(template, "prefix", prefix, "player", player, "reason", reason,
                "check", check, "duration", duration));
    }

    /**
     * The "caught cheating" effect: a lightning strike (harmless), an explosion cloud,
     * thunder, and a big red BANNED title on the cheater's screen.
     */
    public void playAnimation(Player player, String reason) {
        Settings.Messages messages = settings.get().messages();
        Location at = player.getLocation();
        World world = at.getWorld();
        if (world == null) {
            return;
        }
        try {
            world.strikeLightningEffect(at);
        } catch (LinkageError | RuntimeException ignored) {
            // Cosmetic only.
        }
        particle(world, at.clone().add(0, 1, 0), 1, 0.0, "EXPLOSION_EMITTER", "EXPLOSION_HUGE");
        particle(world, at.clone().add(0, 1, 0), 60, 0.6, "LARGE_SMOKE", "SMOKE_LARGE");
        particle(world, at.clone().add(0, 1, 0), 40, 0.8, "FLAME");
        sound(world, at, "minecraft:entity.lightning_bolt.thunder", 1.5f, 1.0f);
        sound(world, at, "minecraft:entity.generic.explode", 1.0f, 0.8f);
        try {
            player.sendTitle(Text.color(messages.get("ban-title")),
                    Text.color(Text.replace(messages.get("ban-subtitle"), "reason", reason)), 5, 60, 15);
        } catch (LinkageError | RuntimeException ignored) {
            // Cosmetic only.
        }
    }

    /** Particle names changed between versions: the first one that exists is used. */
    private static void particle(World world, Location at, int count, double spread, String... names) {
        for (String name : names) {
            try {
                org.bukkit.Particle particle = org.bukkit.Particle.valueOf(name);
                world.spawnParticle(particle, at, count, spread, spread, spread, 0.02);
                return;
            } catch (LinkageError | RuntimeException ignored) {
                // Not on this version (or needs extra data): try the next name.
            }
        }
    }

    private static void sound(World world, Location at, String key, float volume, float pitch) {
        try {
            world.playSound(at, key, volume, pitch);
        } catch (LinkageError | RuntimeException ignored) {
            // Cosmetic only.
        }
    }
}
