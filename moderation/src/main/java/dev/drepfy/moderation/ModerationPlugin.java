package dev.drepfy.moderation;

import dev.drepfy.moderation.cache.PunishmentCache;
import dev.drepfy.moderation.command.AdminCommand;
import dev.drepfy.moderation.command.CheckCommand;
import dev.drepfy.moderation.command.HistoryCommand;
import dev.drepfy.moderation.command.LiftCommand;
import dev.drepfy.moderation.command.PunishCommand;
import dev.drepfy.moderation.command.UnwarnCommand;
import dev.drepfy.moderation.config.Messages;
import dev.drepfy.moderation.config.Settings;
import dev.drepfy.moderation.enforce.ConnectionListener;
import dev.drepfy.moderation.enforce.LegacyChatListener;
import dev.drepfy.moderation.enforce.MuteListener;
import dev.drepfy.moderation.enforce.VoiceMuteEnforcer;
import dev.drepfy.moderation.log.AuditLog;
import dev.drepfy.moderation.model.PunishmentType;
import dev.drepfy.moderation.service.Placeholders;
import dev.drepfy.moderation.service.PunishmentService;
import dev.drepfy.moderation.service.TargetResolver;
import dev.drepfy.moderation.storage.Database;
import dev.drepfy.moderation.storage.Storage;
import dev.drepfy.moderation.util.Cooldowns;
import dev.drepfy.moderation.voice.VoiceChatHook;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.logging.Level;

public final class ModerationPlugin extends JavaPlugin {

    /** How long punishments of players who left stay cached (covers relogs and slow logins). */
    private static final long IDLE_EVICTION_MILLIS = 10 * 60_000L;

    private volatile Settings settings;
    private volatile Messages messages;
    private final PunishmentCache cache = new PunishmentCache();
    private final Cooldowns cooldowns = new Cooldowns();
    private AuditLog audit;
    private Storage storage;
    private TargetResolver targets;
    private PunishmentService service;
    private MuteListener muteListener;
    private LegacyChatListener legacyChatListener;
    private VoiceChatHook voiceHook;
    private BukkitTask syncTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (!new File(getDataFolder(), "messages.yml").exists()) {
            saveResource("messages.yml", false);
        }
        settings = Settings.load(getConfig(), getLogger());
        messages = loadMessages();

        audit = new AuditLog(getDataFolder().toPath().resolve("logs"), Clock.systemDefaultZone(), getLogger(), settings.logging());
        audit.purgeOldFiles();
        storage = openStorage();

        targets = new TargetResolver(this);
        service = new PunishmentService(this, cache, targets);
        muteListener = new MuteListener(this, cache, service);
        legacyChatListener = new LegacyChatListener(muteListener);
        getServer().getPluginManager().registerEvents(new ConnectionListener(this, cache, service), this);
        getServer().getPluginManager().registerEvents(muteListener, this);
        if (settings.blockLegacyChatEvent()) {
            getServer().getPluginManager().registerEvents(legacyChatListener, this);
        }
        registerCommands();

        voiceHook = VoiceChatHook.register(this, new VoiceMuteEnforcer(this, cache, service));
        if (voiceHook == null && getServer().getPluginManager().getPlugin("voicechat") == null) {
            getLogger().info("Simple Voice Chat isn't installed; voice mutes are recorded but can't be enforced");
        }

        // Players already online (e.g. after a plugin reload) never went through the login check.
        loadOnlinePlayersBlocking();
        startSyncTask();
        audit.record("STARTUP", "StaffModeration " + getPluginMeta().getVersion() + " enabled; storage: "
                + storage.describe() + "; voice chat: " + voiceChatStatus());
    }

    @Override
    public void onDisable() {
        if (syncTask != null) {
            syncTask.cancel();
        }
        if (voiceHook != null) {
            voiceHook.disable();
        }
        if (storage != null) {
            storage.close();
        }
        if (audit != null) {
            audit.record("SHUTDOWN", "StaffModeration disabled");
            audit.close();
        }
    }

    private Storage openStorage() {
        String consequence = settings.denyLoginOnDatabaseError()
                ? "Logins will be refused (except for operators) until this is fixed and the server restarted."
                : "Punishments will NOT be enforced until this is fixed and the server restarted.";
        if (settings.storage() == null) {
            getLogger().severe("Invalid storage settings in config.yml: " + settings.storageError() + ". " + consequence);
            return Storage.unavailable(settings.storageError(), getLogger());
        }
        try {
            Database database = Database.open(settings.storage(), getDataFolder().toPath());
            getLogger().info("Connected to " + database.description());
            return Storage.of(database, settings.storage().effectivePoolSize(), getLogger());
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Could not open the punishment database. " + consequence, e);
            return Storage.unavailable(String.valueOf(e.getMessage()), getLogger());
        }
    }

    private Messages loadMessages() {
        try (InputStream defaults = getResource("messages.yml")) {
            return Messages.load(new File(getDataFolder(), "messages.yml"), defaults, getLogger());
        } catch (IOException e) {
            throw new IllegalStateException("Could not read the bundled messages.yml", e);
        }
    }

    private void registerCommands() {
        register("ban", new PunishCommand(this, PunishmentType.BAN));
        register("mute", new PunishCommand(this, PunishmentType.MUTE));
        register("voicemute", new PunishCommand(this, PunishmentType.VOICE_MUTE));
        register("warn", new PunishCommand(this, PunishmentType.WARN));
        register("kick", new PunishCommand(this, PunishmentType.KICK));
        register("unban", new LiftCommand(this, PunishmentType.BAN));
        register("unmute", new LiftCommand(this, PunishmentType.MUTE));
        register("unvoicemute", new LiftCommand(this, PunishmentType.VOICE_MUTE));
        register("unwarn", new UnwarnCommand(this));
        register("history", new HistoryCommand(this, HistoryCommand.Mode.HISTORY));
        register("warnings", new HistoryCommand(this, HistoryCommand.Mode.WARNINGS));
        register("check", new CheckCommand(this));
        register("moderation", new AdminCommand(this));
    }

    private <T extends CommandExecutor & TabCompleter> void register(String name, T handler) {
        PluginCommand command = getCommand(name);
        if (command == null) {
            throw new IllegalStateException("Command /" + name + " is missing from plugin.yml");
        }
        command.setExecutor(handler);
        command.setTabCompleter(handler);
    }

    // ------------------------------------------------------------------------------- reloading

    public void reload(CommandSender sender) {
        Settings newSettings;
        Messages newMessages;
        try {
            reloadConfig();
            newSettings = Settings.load(getConfig(), getLogger());
            newMessages = loadMessages();
        } catch (RuntimeException e) {
            getLogger().log(Level.SEVERE, "Reload failed", e);
            messages.send(sender, "general.reload-failed",
                    Placeholders.text("error", String.valueOf(e.getMessage())));
            return;
        }
        if (!Objects.equals(newSettings.storage(), settings.storage())) {
            getLogger().warning("Storage settings changed; restart the server to apply them");
        }
        Settings old = settings;
        settings = newSettings;
        messages = newMessages;
        audit.configure(newSettings.logging());
        muteListener.reconfigure();
        if (old.blockLegacyChatEvent() != newSettings.blockLegacyChatEvent()) {
            HandlerList.unregisterAll(legacyChatListener);
            if (newSettings.blockLegacyChatEvent()) {
                getServer().getPluginManager().registerEvents(legacyChatListener, this);
            }
        }
        if (old.syncIntervalSeconds() != newSettings.syncIntervalSeconds()) {
            startSyncTask();
        }
        messages.send(sender, "general.reloaded");
        audit.record("RELOAD", "Configuration reloaded by " + sender.getName());
    }

    // --------------------------------------------------------------------------- synchronising

    private void startSyncTask() {
        if (syncTask != null) {
            syncTask.cancel();
            syncTask = null;
        }
        long ticks = settings.syncIntervalSeconds() * 20L;
        if (ticks > 0) {
            syncTask = getServer().getScheduler().runTaskTimer(this, this::syncOnlinePlayers, ticks, ticks);
        }
    }

    /**
     * Re-reads online players' punishments so punishments issued elsewhere (another server sharing
     * the database, manual edits) are enforced, and kicks anyone who has become banned.
     */
    private void syncOnlinePlayers() {
        long now = System.currentTimeMillis();
        cache.evictIdle(id -> Bukkit.getPlayer(id) != null, now, IDLE_EVICTION_MILLIS);
        List<UUID> online = onlinePlayerIds();
        if (!online.isEmpty() && storage.available()) {
            whenComplete(loadPlayers(online), (ignored, error) -> {
                if (error == null) {
                    kickBannedPlayers("found during database sync");
                }
            });
        }
    }

    /** Loads the given players' punishments into the cache. */
    public CompletableFuture<Void> loadPlayers(Collection<UUID> players) {
        long token = cache.beginSnapshot();
        long now = System.currentTimeMillis();
        List<UUID> ids = List.copyOf(players);
        return storage.submit(store -> store.findInForce(ids, now)).thenAccept(found -> {
            long applied = System.currentTimeMillis();
            for (UUID id : ids) {
                cache.applySnapshot(id, found.getOrDefault(id, List.of()), token, applied);
            }
        }).whenComplete((ignored, error) -> {
            if (error != null) {
                service.fail(null, "loading punishments for " + ids.size() + " player(s)", error);
            }
        });
    }

    private void loadOnlinePlayersBlocking() {
        List<UUID> online = onlinePlayerIds();
        if (online.isEmpty()) {
            return;
        }
        try {
            loadPlayers(online).get(settings.loginTimeoutSeconds(), TimeUnit.SECONDS);
            kickBannedPlayers("found while loading online players");
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Could not load punishments for online players", e);
        }
    }

    private void kickBannedPlayers(String context) {
        long now = System.currentTimeMillis();
        for (Player player : new ArrayList<>(Bukkit.getOnlinePlayers())) {
            cache.find(player.getUniqueId(), PunishmentType.BAN, now).ifPresent(ban -> {
                player.kick(messages.render("ban.screen", service.placeholders(ban)));
                audit.record("BAN-ENFORCED", "Kicked " + player.getName() + ": ban #" + ban.id() + " " + context);
            });
        }
    }

    private static List<UUID> onlinePlayerIds() {
        List<UUID> ids = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            ids.add(player.getUniqueId());
        }
        return ids;
    }

    // --------------------------------------------------------------------------------- threads

    /** Runs a task on the main thread (immediately if already on it). Dropped once the plugin is disabled. */
    public void runOnMain(Runnable task) {
        if (!isEnabled()) {
            return;
        }
        if (Bukkit.isPrimaryThread()) {
            task.run();
            return;
        }
        try {
            getServer().getScheduler().runTask(this, task);
        } catch (RuntimeException e) {
            // Disabled between the check and scheduling; nothing left to do.
        }
    }

    /** Handles a future's result on the main thread. */
    public <T> void whenComplete(CompletableFuture<T> future, BiConsumer<T, Throwable> callback) {
        future.whenComplete((value, error) -> runOnMain(() -> callback.accept(value, error)));
    }

    // ------------------------------------------------------------------------------- accessors

    public Settings settings() {
        return settings;
    }

    public Messages messages() {
        return messages;
    }

    public AuditLog audit() {
        return audit;
    }

    public Storage storage() {
        return storage;
    }

    public PunishmentCache cache() {
        return cache;
    }

    public Cooldowns cooldowns() {
        return cooldowns;
    }

    public TargetResolver targets() {
        return targets;
    }

    public PunishmentService service() {
        return service;
    }

    public boolean voiceChatEnforced() {
        return voiceHook != null;
    }

    public String voiceChatStatus() {
        return voiceHook != null ? voiceHook.describe() : "not installed (voice mutes are not enforced)";
    }
}
