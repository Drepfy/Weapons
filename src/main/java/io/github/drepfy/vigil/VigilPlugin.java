package io.github.drepfy.vigil;

import io.github.drepfy.vigil.api.CheckType;
import io.github.drepfy.vigil.api.VigilApi;
import io.github.drepfy.vigil.check.CheckContext;
import io.github.drepfy.vigil.check.combat.HitAngleCheck;
import io.github.drepfy.vigil.check.combat.ReachCheck;
import io.github.drepfy.vigil.check.combat.WallHitCheck;
import io.github.drepfy.vigil.check.interaction.BlockReachCheck;
import io.github.drepfy.vigil.check.interaction.WallInteractCheck;
import io.github.drepfy.vigil.check.movement.FlightCheck;
import io.github.drepfy.vigil.check.movement.GroundSpoofCheck;
import io.github.drepfy.vigil.check.movement.SpeedCheck;
import io.github.drepfy.vigil.check.movement.TimerCheck;
import io.github.drepfy.vigil.check.movement.VerticalCheck;
import io.github.drepfy.vigil.command.VigilCommand;
import io.github.drepfy.vigil.compat.ServerCompat;
import io.github.drepfy.vigil.config.ConfigLoader;
import io.github.drepfy.vigil.config.Settings;
import io.github.drepfy.vigil.data.FlagRecord;
import io.github.drepfy.vigil.data.PlayerData;
import io.github.drepfy.vigil.data.PlayerDataManager;
import io.github.drepfy.vigil.env.BlockTraits;
import io.github.drepfy.vigil.env.DisturbanceRegistry;
import io.github.drepfy.vigil.env.WorldProbe;
import io.github.drepfy.vigil.listener.CombatListener;
import io.github.drepfy.vigil.listener.InteractionListener;
import io.github.drepfy.vigil.listener.LifecycleListener;
import io.github.drepfy.vigil.listener.MovementListener;
import io.github.drepfy.vigil.listener.OptionalHooks;
import io.github.drepfy.vigil.listener.WorldActivityListener;
import io.github.drepfy.vigil.moderation.ModerationCommand;
import io.github.drepfy.vigil.moderation.ModerationListener;
import io.github.drepfy.vigil.moderation.ModerationService;
import io.github.drepfy.vigil.review.ReviewService;
import io.github.drepfy.vigil.storage.FlagLogWriter;
import io.github.drepfy.vigil.storage.IoExecutor;
import io.github.drepfy.vigil.storage.PlayerRecord;
import io.github.drepfy.vigil.storage.PlayerRecordStore;
import io.github.drepfy.vigil.task.TickTask;
import io.github.drepfy.vigil.task.TpsMonitor;
import io.github.drepfy.vigil.util.Clock;
import io.github.drepfy.vigil.violation.AlertService;
import io.github.drepfy.vigil.violation.DebugService;
import io.github.drepfy.vigil.violation.PunishmentService;
import io.github.drepfy.vigil.violation.ViolationService;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

/**
 * Plugin entry point: wires all components together.
 *
 * <p>Failure policy: an unreadable configuration falls back to built-in defaults
 * (the file is never rewritten); a failure while wiring components disables the
 * plugin cleanly instead of leaving it half-running.
 */
public final class VigilPlugin extends JavaPlugin {

    private volatile Settings settings;
    private IoExecutor io;
    private PlayerDataManager players;
    private PlayerRecordStore records;
    private FlagLogWriter flagLog;
    private AlertService alerts;
    private ReviewService review;
    private DebugService debug;
    private TpsMonitor tps;
    private CheckContext checks;
    private LifecycleListener lifecycle;
    private HitAngleCheck hitAngle;
    private ModerationService moderation;
    private BukkitTask tickTask;
    private boolean started;

    @Override
    public void onEnable() {
        try {
            start();
            started = true;
        } catch (Throwable t) {
            getLogger().log(Level.SEVERE, "Vigil failed to start and will disable itself. No checks are running.", t);
            Bukkit.getPluginManager().disablePlugin(this);
        }
    }

    private void start() {
        saveDefaultConfig();
        settings = readSettings(null);
        logWarnings(settings.warnings());

        Path dataDir = getDataFolder().toPath();
        io = new IoExecutor(getLogger());
        players = new PlayerDataManager();
        records = new PlayerRecordStore(dataDir.resolve("data").resolve("players"), io, getLogger());
        flagLog = new FlagLogWriter(dataDir.resolve("logs"), io, getLogger());
        flagLog.applyRetention(settings.violations().logRetentionDays());
        tps = new TpsMonitor();
        ServerCompat compat = new ServerCompat(getLogger());
        WorldProbe probe = new WorldProbe(new BlockTraits(getLogger()));
        DisturbanceRegistry disturbances = new DisturbanceRegistry();

        alerts = new AlertService(this::settings, getLogger(), io, dataDir.resolve("data").resolve("staff.yml"));
        review = new ReviewService(this::settings, getLogger(), io, dataDir.resolve("data").resolve("cases.yml"),
                dataDir.resolve("data").resolve("cases-archive.log"), alerts);
        PunishmentService punishments = new PunishmentService(this::settings, getLogger(),
                line -> flagLog.append("[punishment] " + line));
        ViolationService violations = new ViolationService(this::settings, alerts, review, punishments, flagLog, tps,
                compat);
        debug = new DebugService(getLogger(), () -> settings.general().debug());
        checks = new CheckContext(this::settings, getLogger(), compat, probe, disturbances, tps, violations, debug,
                players);

        SpeedCheck speed = new SpeedCheck(checks);
        VerticalCheck vertical = new VerticalCheck(checks);
        GroundSpoofCheck groundSpoof = new GroundSpoofCheck(checks);
        TimerCheck timer = new TimerCheck(checks);
        FlightCheck flight = new FlightCheck(checks);
        hitAngle = new HitAngleCheck(checks);

        lifecycle = new LifecycleListener(checks, this::handleJoin, this::handleQuit);
        PluginManager pm = getServer().getPluginManager();
        pm.registerEvents(lifecycle, this);
        pm.registerEvents(new MovementListener(checks, getLogger(), speed, vertical, groundSpoof, timer), this);
        pm.registerEvents(new CombatListener(checks, lifecycle, new ReachCheck(checks), hitAngle,
                new WallHitCheck(checks)), this);
        pm.registerEvents(new InteractionListener(checks, new BlockReachCheck(checks), new WallInteractCheck(checks)),
                this);
        pm.registerEvents(new WorldActivityListener(disturbances), this);
        new OptionalHooks(this, checks, lifecycle, timer).registerAll();
        registerBypassPermissions(pm);

        moderation = new ModerationService(getLogger(), io, dataDir.resolve("data").resolve("punishments.yml"));
        ModerationListener moderationListener = new ModerationListener(this::settings, moderation);
        pm.registerEvents(moderationListener, this);
        ModerationCommand moderationCommand = new ModerationCommand(this::settings, moderation, moderationListener,
                getLogger());
        for (String name : List.of("ban", "tempban", "unban", "mute", "tempmute", "unmute", "warn", "kick",
                "punishments")) {
            PluginCommand moderationPluginCommand = getCommand(name);
            if (moderationPluginCommand != null) {
                moderationPluginCommand.setExecutor(moderationCommand);
                moderationPluginCommand.setTabCompleter(moderationCommand);
            }
        }

        PluginCommand command = getCommand("vigil");
        if (command != null) {
            VigilCommand executor = new VigilCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        } else {
            getLogger().severe("The /vigil command is missing from plugin.yml; staff commands are unavailable.");
        }

        getServer().getServicesManager().register(VigilApi.class, new VigilApiImpl(this), this, ServicePriority.Normal);

        // Players already online (e.g. after /reload).
        for (Player player : Bukkit.getOnlinePlayers()) {
            handleJoin(player);
        }
        tickTask = Bukkit.getScheduler().runTaskTimer(this,
                new TickTask(checks, flight, hitAngle, disturbances, () -> {
                    review.saveIfDirty();
                    moderation.saveIfDirty();
                }, this::saveDirtyRecords), 1L, 1L);

        getLogger().info("Vigil enabled: " + enabledChecks() + " of " + CheckType.values().length + " checks active"
                + (settings.general().passiveMode() ? " (passive mode)" : "")
                + (settings.punishments().enabled() ? ", automatic commands ON"
                + (settings.punishments().dryRun() ? " (dry-run)" : "") : ", automatic commands off") + ".");
    }

    @Override
    public void onDisable() {
        if (tickTask != null) {
            tickTask.cancel();
        }
        if (!started) {
            if (io != null) {
                io.shutdown(2000);
            }
            return;
        }
        try {
            getServer().getServicesManager().unregisterAll(this);
            List<PlayerRecord> pending = new ArrayList<>();
            for (PlayerData data : players.online()) {
                PlayerRecord record = data.record;
                if (record != null) {
                    pending.add(record);
                }
            }
            flagLog.close();
            io.shutdown(5000);
            // The IO thread has stopped: write whatever is still dirty synchronously.
            records.saveAllNow(pending);
            review.saveNow();
            moderation.saveNow();
            hitAngle.clear();
            players.clear();
        } catch (Throwable t) {
            getLogger().log(Level.WARNING, "Error while shutting down Vigil", t);
        }
    }

    // ---- configuration ---------------------------------------------------------------------

    /**
     * Reads config.yml. On failure: keeps {@code previous} when reloading, or falls back
     * to the bundled defaults on startup. The file on disk is never modified.
     */
    private Settings readSettings(Settings previous) {
        File file = new File(getDataFolder(), "config.yml");
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
            return ConfigLoader.load(yaml);
        } catch (IOException | InvalidConfigurationException | RuntimeException e) {
            if (previous != null) {
                throw new IllegalStateException(e.getMessage() == null ? e.toString() : e.getMessage(), e);
            }
            getLogger().severe("config.yml could not be read (" + e.getMessage() + "). Using built-in defaults; "
                    + "fix the file and run /vigil reload. The file was not modified.");
            return defaultsFromJar(e.getMessage());
        }
    }

    private Settings defaultsFromJar(String reason) {
        List<String> warnings = new ArrayList<>();
        warnings.add("config.yml unreadable, running on built-in defaults: " + reason);
        try (Reader reader = new InputStreamReader(getResource("config.yml"), StandardCharsets.UTF_8)) {
            return ConfigLoader.load(YamlConfiguration.loadConfiguration(reader), warnings);
        } catch (IOException | RuntimeException e) {
            return ConfigLoader.defaults(warnings);
        }
    }

    /**
     * Reloads the configuration atomically.
     *
     * @return the warnings of the new configuration
     * @throws IllegalStateException if the file is unreadable (the old settings stay active)
     */
    public List<String> reload() {
        Settings fresh = readSettings(settings);
        settings = fresh;
        checks.resetBreakers();
        logWarnings(fresh.warnings());
        return fresh.warnings();
    }

    private void logWarnings(List<String> warnings) {
        for (String warning : warnings) {
            getLogger().warning("[config] " + warning);
        }
    }

    private void registerBypassPermissions(PluginManager pm) {
        for (CheckType type : CheckType.values()) {
            if (pm.getPermission(type.bypassPermission()) == null) {
                try {
                    pm.addPermission(new Permission(type.bypassPermission(),
                            "Exempt from the " + type.displayName() + " check.", PermissionDefault.FALSE));
                } catch (IllegalArgumentException ignored) {
                    // Already registered by another path.
                }
            }
        }
    }

    // ---- player lifecycle -------------------------------------------------------------------

    private void handleJoin(Player player) {
        long now = Clock.now();
        PlayerData data = players.join(player, now);
        data.flight.reset(player.getLocation().getY(), true);
        if (data.record != null) {
            data.record.seen(player.getName());
            return;
        }
        records.loadOrCreate(player.getUniqueId(), player.getName()).thenAccept(record ->
                Bukkit.getScheduler().runTask(this, () -> attachRecord(player, data, record)));
    }

    private void attachRecord(Player player, PlayerData data, PlayerRecord record) {
        if (!isEnabled()) {
            return;
        }
        record.seen(player.getName());
        int limit = settings.violations().historySize();
        for (FlagRecord flag : data.pendingRecordFlags) {
            record.recordFlag(flag, limit);
        }
        data.pendingRecordFlags.clear();
        data.record = record;
        if (!player.isOnline()) {
            // Left before the record finished loading: the quit handler could not save it.
            records.saveAsync(record);
        }
    }

    private void handleQuit(Player player) {
        PlayerData data = players.quit(player.getUniqueId(), Clock.now());
        debug.removeViewer(player.getUniqueId());
        if (data != null && data.record != null) {
            data.record.seen(player.getName());
            records.saveAsync(data.record);
        }
    }

    private void saveDirtyRecords() {
        for (PlayerData data : players.online()) {
            PlayerRecord record = data.record;
            if (record != null) {
                records.saveAsync(record);
            }
        }
    }

    private int enabledChecks() {
        int count = 0;
        for (CheckType type : CheckType.values()) {
            if (settings.check(type).enabled()) {
                count++;
            }
        }
        return settings.general().enabled() ? count : 0;
    }

    // ---- accessors for commands and the API --------------------------------------------------

    public Settings settings() {
        return settings;
    }

    public PlayerDataManager players() {
        return players;
    }

    public PlayerRecordStore records() {
        return records;
    }

    public AlertService alerts() {
        return alerts;
    }

    public ReviewService review() {
        return review;
    }

    public DebugService debugService() {
        return debug;
    }

    public TpsMonitor tpsMonitor() {
        return tps;
    }

    public CheckContext checks() {
        return checks;
    }

    public LifecycleListener lifecycle() {
        return lifecycle;
    }

    public IoExecutor io() {
        return io;
    }

    public ModerationService moderation() {
        return moderation;
    }
}
