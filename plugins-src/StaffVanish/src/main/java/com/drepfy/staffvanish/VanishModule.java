package com.drepfy.staffvanish;

import com.drepfy.staffvanish.command.VanishCommand;
import com.drepfy.staffvanish.listener.ConnectionListener;
import com.drepfy.staffvanish.listener.FlightListener;
import com.drepfy.staffvanish.listener.ProtectionListener;
import com.drepfy.staffvanish.listener.ServerListListener;
import com.drepfy.staffvanish.selector.SelectorItem;
import com.drepfy.staffvanish.selector.SelectorListener;
import java.util.Objects;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.jspecify.annotations.Nullable;

/** Wires the vanish feature together and owns its lifecycle. */
public final class VanishModule {

    private final JavaPlugin plugin;
    private final VanishStorage storage;
    private final FallProtection fallProtection = new FallProtection();
    private final VanishManager manager;
    private final SelectorItem selectorItem;
    private final StaffTeleporter teleporter;
    private final com.drepfy.staffvanish.staff.StaffPreferences preferences;
    private final com.drepfy.staffvanish.staff.VanishEffects effects;
    private com.drepfy.staffvanish.staff.InventorySpy spy;
    private com.drepfy.staffvanish.staff.StaffPanel panel;
    // Volatile: read from the async chat and server list ping threads.
    private volatile VanishSettings settings;
    private volatile Messages messages;
    private @Nullable BukkitTask refreshTask;

    public VanishModule(JavaPlugin plugin) {
        this.plugin = plugin;
        this.settings = VanishSettings.load(plugin.getConfig(), plugin.getLogger());
        this.messages = loadMessages();
        this.storage = new VanishStorage(plugin.getDataFolder().toPath().resolve("vanish-data.yml"), plugin.getLogger());
        this.manager = new VanishManager(this, plugin, storage, fallProtection);
        this.selectorItem = new SelectorItem(plugin, this);
        this.teleporter = new StaffTeleporter(this);
        this.preferences = new com.drepfy.staffvanish.staff.StaffPreferences(plugin, this);
        this.effects = new com.drepfy.staffvanish.staff.VanishEffects(plugin, this);
    }

    public void enable() {
        storage.load();

        PluginManager plugins = plugin.getServer().getPluginManager();
        plugins.registerEvents(new ConnectionListener(this), plugin);
        plugins.registerEvents(new ProtectionListener(this), plugin);
        plugins.registerEvents(new FlightListener(this, plugin, fallProtection), plugin);
        plugins.registerEvents(new ServerListListener(this), plugin);
        plugins.registerEvents(new SelectorListener(this, plugin), plugin);
        spy = new com.drepfy.staffvanish.staff.InventorySpy(plugin, this);
        panel = new com.drepfy.staffvanish.staff.StaffPanel(plugin, this);
        plugins.registerEvents(spy, plugin);
        plugins.registerEvents(panel, plugin);
        plugins.registerEvents(new com.drepfy.staffvanish.staff.SilentContainers(plugin, this), plugin);
        com.drepfy.staffvanish.command.SpyCommand spyCommand = new com.drepfy.staffvanish.command.SpyCommand(this);
        for (String name : new String[] {"invsee", "endersee"}) {
            PluginCommand spyExecutor = plugin.getCommand(name);
            if (spyExecutor != null) {
                spyExecutor.setExecutor(spyCommand);
                spyExecutor.setTabCompleter(spyCommand);
            }
        }

        PluginCommand command = Objects.requireNonNull(plugin.getCommand("vanish"), "vanish command missing from plugin.yml");
        VanishCommand executor = new VanishCommand(this);
        command.setExecutor(executor);
        command.setTabCompleter(executor);

        // Players are already online when the plugin is enabled by a reload.
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            manager.restore(player);
        }
        scheduleRefresh();
    }

    public void disable() {
        if (refreshTask != null) {
            refreshTask.cancel();
            refreshTask = null;
        }
        effects.clearAll();
        // On shutdown everyone is about to be disconnected, and their vanish state is already saved.
        if (!plugin.getServer().isStopping()) {
            manager.suspendAll();
        }
    }

    public void reload() {
        plugin.reloadConfig();
        settings = VanishSettings.load(plugin.getConfig(), plugin.getLogger());
        messages = loadMessages();
        manager.reapplySettings();
        scheduleRefresh();
    }

    public VanishSettings settings() {
        return settings;
    }

    public Messages messages() {
        return messages;
    }

    public VanishManager manager() {
        return manager;
    }

    public SelectorItem selectorItem() {
        return selectorItem;
    }

    public StaffTeleporter teleporter() {
        return teleporter;
    }

    public com.drepfy.staffvanish.staff.StaffPreferences preferences() {
        return preferences;
    }

    public com.drepfy.staffvanish.staff.VanishEffects effects() {
        return effects;
    }

    public com.drepfy.staffvanish.staff.InventorySpy spy() {
        return spy;
    }

    public com.drepfy.staffvanish.staff.StaffPanel panel() {
        return panel;
    }

    public VanishStorage storage() {
        return storage;
    }

    private Messages loadMessages() {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("messages");
        return new Messages(section != null ? section : plugin.getConfig().createSection("messages"));
    }

    private void scheduleRefresh() {
        if (refreshTask != null) {
            refreshTask.cancel();
            refreshTask = null;
        }
        int interval = settings.refreshIntervalTicks();
        if (interval > 0) {
            refreshTask = plugin.getServer().getScheduler().runTaskTimer(plugin, manager::refresh, interval, interval);
        }
    }
}
