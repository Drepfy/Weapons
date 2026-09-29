package io.github.drepfy.vigil;

import io.github.drepfy.vigil.api.CheckType;
import io.github.drepfy.vigil.api.VigilApi;
import io.github.drepfy.vigil.check.CheckContext;
import io.github.drepfy.vigil.check.combat.AutoClickerCheck;
import io.github.drepfy.vigil.check.combat.KillAuraCheck;
import io.github.drepfy.vigil.check.combat.MaceCheck;
import io.github.drepfy.vigil.check.combat.NoSwingCheck;
import io.github.drepfy.vigil.check.combat.ReachCheck;
import io.github.drepfy.vigil.check.combat.WallHitCheck;
import io.github.drepfy.vigil.check.interaction.BlockReachCheck;
import io.github.drepfy.vigil.check.interaction.ChestAuraCheck;
import io.github.drepfy.vigil.check.interaction.FastPlaceCheck;
import io.github.drepfy.vigil.check.interaction.InteractCheck;
import io.github.drepfy.vigil.check.interaction.InventoryCheck;
import io.github.drepfy.vigil.check.interaction.NukerCheck;
import io.github.drepfy.vigil.check.interaction.XrayCheck;
import io.github.drepfy.vigil.check.movement.FlightCheck;
import io.github.drepfy.vigil.check.movement.NoFallCheck;
import io.github.drepfy.vigil.check.movement.NoSlowCheck;
import io.github.drepfy.vigil.check.movement.SpeedCheck;
import io.github.drepfy.vigil.check.movement.StepCheck;
import io.github.drepfy.vigil.check.movement.TimerCheck;
import io.github.drepfy.vigil.check.movement.VelocityCheck;
import io.github.drepfy.vigil.command.VigilCommand;
import io.github.drepfy.vigil.compat.PaperAntiXraySetup;
import io.github.drepfy.vigil.compat.ServerCompat;
import io.github.drepfy.vigil.config.ConfigLoader;
import io.github.drepfy.vigil.config.Settings;
import io.github.drepfy.vigil.data.FlagRecord;
import io.github.drepfy.vigil.data.PlayerData;
import io.github.drepfy.vigil.data.PlayerDataManager;
import io.github.drepfy.vigil.env.BlockTraits;
import io.github.drepfy.vigil.env.DisturbanceRegistry;
import io.github.drepfy.vigil.env.BlockHider;
import io.github.drepfy.vigil.env.WorldProbe;
import io.github.drepfy.vigil.listener.ClientListener;
import io.github.drepfy.vigil.listener.CombatListener;
import io.github.drepfy.vigil.listener.InteractionListener;
import io.github.drepfy.vigil.listener.LifecycleListener;
import io.github.drepfy.vigil.listener.MovementListener;
import io.github.drepfy.vigil.listener.OptionalHooks;
import io.github.drepfy.vigil.listener.WorldActivityListener;
import io.github.drepfy.vigil.moderation.ModerationCommand;
import io.github.drepfy.vigil.moderation.ModerationListener;
import io.github.drepfy.vigil.moderation.ModerationService;
import io.github.drepfy.vigil.storage.FlagLogWriter;
import io.github.drepfy.vigil.storage.IoExecutor;
import io.github.drepfy.vigil.storage.PlayerRecord;
import io.github.drepfy.vigil.storage.PlayerRecordStore;
import io.github.drepfy.vigil.task.TickTask;
import io.github.drepfy.vigil.task.TpsMonitor;
import io.github.drepfy.vigil.util.Clock;
import io.github.drepfy.vigil.violation.AlertService;
import io.github.drepfy.vigil.violation.AutoBanService;
import io.github.drepfy.vigil.violation.DebugService;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
public class VigilPlugin extends JavaPlugin {

    private volatile Settings settings;
    private IoExecutor io;
    private PlayerDataManager players;
    private PlayerRecordStore records;
    private FlagLogWriter flagLog;
    private AlertService alerts;
    private DebugService debug;
    private TpsMonitor tps;
    private CheckContext checks;
    private LifecycleListener lifecycle;
    private KillAuraCheck killAura;
    private NoSwingCheck noSwing;
    private ModerationService moderation;
    private BlockHider blockHider;
    private AutoBanService autoBan;
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
        migrateLegacyConfig();
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
        PluginManager pm = getServer().getPluginManager();

        moderation = new ModerationService(getLogger(), io, dataDir.resolve("data").resolve("punishments.yml"));
        ModerationListener moderationListener = new ModerationListener(this::settings, moderation);
        pm.registerEvents(moderationListener, this);

        alerts = new AlertService(this::settings, getLogger(), io, dataDir.resolve("data").resolve("staff.yml"));
        autoBan = new AutoBanService(this, this::settings, moderation, moderationListener, alerts,
                getLogger(), line -> flagLog.append(line));
        ViolationService violations = new ViolationService(this::settings, alerts, autoBan, flagLog, tps, compat);
        debug = new DebugService(getLogger(), () -> settings.general().debug());
        checks = new CheckContext(this::settings, getLogger(), compat, probe, disturbances, tps, violations, debug,
                players);

        SpeedCheck speed = new SpeedCheck(checks);
        StepCheck step = new StepCheck(checks);
        NoFallCheck noFall = new NoFallCheck(checks);
        TimerCheck timer = new TimerCheck(checks);
        FlightCheck flight = new FlightCheck(checks);
        VelocityCheck velocity = new VelocityCheck(checks);
        NoSlowCheck noSlow = new NoSlowCheck(checks);
        killAura = new KillAuraCheck(checks);
        noSwing = new NoSwingCheck(checks);

        lifecycle = new LifecycleListener(checks, velocity, this::handleJoin, this::handleQuit);
        pm.registerEvents(lifecycle, this);
        pm.registerEvents(new MovementListener(checks, getLogger(), speed, step, noFall, timer), this);
        CombatListener combat = new CombatListener(checks, lifecycle, new ReachCheck(checks), killAura,
                new WallHitCheck(checks), noSwing, new AutoClickerCheck(checks), new MaceCheck(checks));
        pm.registerEvents(combat, this);
        InteractionListener interaction = new InteractionListener(checks, new BlockReachCheck(checks),
                new ChestAuraCheck(checks), new InteractCheck(checks), new FastPlaceCheck(checks),
                new NukerCheck(checks), new XrayCheck(checks), new InventoryCheck(checks));
        pm.registerEvents(interaction, this);
        pm.registerEvents(new WorldActivityListener(disturbances), this);
        pm.registerEvents(new ClientListener(this, this::settings, alerts), this);
        new OptionalHooks(this, checks, lifecycle, combat, interaction, timer).registerAll();
        registerBypassPermissions(pm);

        ModerationCommand moderationCommand = new ModerationCommand(this::settings, moderation, moderationListener,
                getLogger());
        for (String name : List.of("ban", "unban", "mute", "unmute", "warn", "kick")) {
            PluginCommand moderationPluginCommand = getCommand(name);
            if (moderationPluginCommand != null) {
                moderationPluginCommand.setExecutor(moderationCommand);
                moderationPluginCommand.setTabCompleter(moderationCommand);
            }
        }

        PluginCommand command = getCommand("ac");
        if (command != null) {
            VigilCommand executor = new VigilCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        } else {
            getLogger().severe("The /ac command is missing from plugin.yml; staff commands are unavailable.");
        }

        getServer().getServicesManager().register(VigilApi.class, new VigilApiImpl(this), this, ServicePriority.Normal);

        setUpAntiXray();
        if (ServerCompat.classExists("io.papermc.paper.event.packet.PlayerChunkLoadEvent")) {
            blockHider = new BlockHider(this, this::settings, probe);
            pm.registerEvents(blockHider, this);
            blockHider.start();
        } else if (settings.antiEsp().hideStorage() || settings.antiEsp().hideOres()) {
            getLogger().info("Hiding chests and ores from ESP/x-ray needs Paper; it is off on this server.");
        }

        // Players already online (e.g. after /reload).
        for (Player player : Bukkit.getOnlinePlayers()) {
            handleJoin(player);
        }
        TickTask.Checks driven = new TickTask.Checks(flight, velocity, noSlow, noFall, killAura, noSwing);
        tickTask = Bukkit.getScheduler().runTaskTimer(this,
                new TickTask(checks, driven, disturbances, moderation::saveIfDirty, this::saveDirtyRecords), 1L, 1L);

        Settings.AutoBan autoBanSettings = settings.autoBan();
        getLogger().info("Enabled: " + enabledChecks() + " of " + CheckType.values().length + " checks active"
                + (settings.general().passiveMode() ? " (passive mode: no setbacks, no bans)" : "")
                + ", auto-ban " + (autoBanSettings.enabled() && !settings.general().passiveMode() ? "ON" : "off")
                + ". Staff commands: /ac, /ban, /mute, /warn, /kick.");
    }

    @Override
    public void onDisable() {
        if (tickTask != null) {
            tickTask.cancel();
        }
        if (blockHider != null) {
            try {
                blockHider.stop();
            } catch (Throwable t) {
                getLogger().log(Level.WARNING, "Could not show hidden storage blocks again", t);
            }
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
            moderation.saveNow();
            killAura.clear();
            noSwing.clear();
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
                    + "fix the file and run /ac reload. The file was not modified.");
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

    /** Checks Paper's own anti-xray and switches it on once if allowed (takes effect after a restart). */
    private void setUpAntiXray() {
        try {
            Path serverRoot = getDataFolder().getAbsoluteFile().toPath().getParent().getParent();
            List<PaperAntiXraySetup.WorldInfo> worlds = new ArrayList<>();
            for (org.bukkit.World world : Bukkit.getWorlds()) {
                try {
                    worlds.add(new PaperAntiXraySetup.WorldInfo(world.getName(), world.getWorldFolder().toPath(),
                            world.getEnvironment()));
                } catch (RuntimeException e) {
                    getLogger().fine("Anti-xray: skipping world " + world.getName() + ": " + e);
                }
            }
            PaperAntiXraySetup.run(serverRoot, getDataFolder().toPath().resolve("data").resolve("paper-anti-xray.yml"),
                    worlds, settings.antiXray().setupPaper(), getLogger());
        } catch (RuntimeException e) {
            getLogger().log(Level.WARNING, "Anti-xray setup failed", e);
        }
    }

    /**
     * A config.yml from Vigil 1.x is moved to {@code config-1.x-backup.yml} and replaced
     * by the new, shorter file. Moderation settings, messages and the basic anti-cheat
     * switches are carried over; check tuning is not (the new defaults detect far more).
     */
    private void migrateLegacyConfig() {
        File file = new File(getDataFolder(), "config.yml");
        if (!file.isFile()) {
            return;
        }
        YamlConfiguration old = new YamlConfiguration();
        try {
            old.load(file);
        } catch (IOException | InvalidConfigurationException | RuntimeException e) {
            return; // Reported by readSettings; never touch an unreadable file.
        }
        if (!ConfigLoader.isLegacyLayout(old)) {
            return;
        }
        File backup = new File(getDataFolder(), "config-1.x-backup.yml");
        for (int i = 2; backup.exists(); i++) {
            backup = new File(getDataFolder(), "config-1.x-backup-" + i + ".yml");
        }
        try {
            Files.move(file.toPath(), backup.toPath(), StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            getLogger().warning("config.yml is from Vigil 1.x but could not be backed up (" + e.getMessage()
                    + "); it is used as is. Delete it to get the new config.yml.");
            return;
        }
        saveResource("config.yml", true);
        try {
            YamlConfiguration fresh = new YamlConfiguration();
            fresh.load(file);
            int copied = 0;
            copied += copyLeaves(old, fresh, "moderation", "moderation");
            copied += copyLeaves(old, fresh, "messages", "messages");
            copied += copyValue(old, "messages.prefix", fresh, "prefix");
            copied += copyValue(old, "general.enabled", fresh, "anticheat.enabled");
            copied += copyValue(old, "general.disabled-worlds", fresh, "anticheat.disabled-worlds");
            copied += copyValue(old, "general.exempt-creative-and-spectator", fresh,
                    "anticheat.exempt-creative-and-spectator");
            fresh.save(file);
            getLogger().warning("Your config.yml was from Vigil 1.x. It was saved as " + backup.getName()
                    + " and replaced by the new, shorter config.yml (" + copied
                    + " moderation/message settings kept). Auto-ban is now ON; see the anticheat section.");
        } catch (IOException | InvalidConfigurationException | RuntimeException e) {
            getLogger().warning("Could not carry old settings into the new config.yml: " + e.getMessage());
        }
    }

    private static int copyLeaves(YamlConfiguration from, YamlConfiguration to, String fromPath, String toPath) {
        org.bukkit.configuration.ConfigurationSection section = from.getConfigurationSection(fromPath);
        if (section == null) {
            return 0;
        }
        int copied = 0;
        for (String key : section.getKeys(true)) {
            if (!section.isConfigurationSection(key)) {
                copied += copyValue(from, fromPath + "." + key, to, toPath + "." + key);
            }
        }
        return copied;
    }

    private static int copyValue(YamlConfiguration from, String fromPath, YamlConfiguration to, String toPath) {
        Object value = from.get(fromPath);
        if (value == null || value.equals(to.get(toPath))) {
            return 0;
        }
        if (toPath.startsWith("messages.") && !to.contains(toPath)) {
            return 0; // Messages that no longer exist.
        }
        to.set(toPath, value);
        return 1;
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
        if (data.autoBanned) {
            // Only possible after the ban was lifted: start over with a clean slate.
            data.autoBanned = false;
            data.resetViolations(null);
        }
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

    public AutoBanService autoBan() {
        return autoBan;
    }

    /** Anti-ESP / anti-xray block hiding, or {@code null} when not on Paper. */
    public BlockHider blockHider() {
        return blockHider;
    }
}
