package io.github.drepfy.lifesteal;

import io.github.drepfy.lifesteal.alt.AltProtection;
import io.github.drepfy.lifesteal.command.HeartsCommand;
import io.github.drepfy.lifesteal.command.LifestealCommand;
import io.github.drepfy.lifesteal.command.WithdrawCommand;
import io.github.drepfy.lifesteal.config.Settings;
import io.github.drepfy.lifesteal.config.SettingsLoader;
import io.github.drepfy.lifesteal.data.LifestealStore;
import io.github.drepfy.lifesteal.heart.CrafterListener;
import io.github.drepfy.lifesteal.heart.HeartEffects;
import io.github.drepfy.lifesteal.heart.HeartItemListener;
import io.github.drepfy.lifesteal.heart.HeartItems;
import io.github.drepfy.lifesteal.heart.HeartRecipe;
import io.github.drepfy.lifesteal.heart.HeartService;
import io.github.drepfy.lifesteal.heart.KillListener;
import io.github.drepfy.lifesteal.util.Compat;
import io.github.drepfy.lifesteal.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.function.LongSupplier;
import java.util.logging.Level;

/**
 * Lifesteal: players steal hearts from the players they kill.
 *
 * <p>Other plugins can get it with {@code Bukkit.getServicesManager().load(LifestealPlugin.class)}
 * to read or change hearts, or to add alt account checks.
 */
public class LifestealPlugin extends JavaPlugin implements Listener {

    public static final String PACK_FILE = "Lifesteal-ResourcePack.zip";
    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private volatile Settings settings;
    private volatile LongSupplier clock = System::currentTimeMillis;
    private LifestealStore store;
    private HeartItems items;
    private HeartService hearts;
    private AltProtection alts;
    private HeartRecipe recipe;
    private HeartItemListener itemListener;
    private byte[] packHash;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        settings = readSettings(false);
        logWarnings(settings.warnings());

        store = new LifestealStore(getLogger(), getDataFolder().toPath().resolve("data.yml"));
        items = new HeartItems(this, this::settings);
        hearts = new HeartService(this::settings, store, items);
        alts = new AltProtection(this::settings, store, this::now, getLogger());
        recipe = new HeartRecipe(this, items);
        recipe.apply(settings);
        HeartEffects effects = new HeartEffects(this::settings);
        itemListener = new HeartItemListener(this::settings, items, hearts, effects, this::now, this::log);

        getServer().getPluginManager().registerEvents(new KillListener(this, this::settings, store, hearts, alts,
                effects, this::now, this::log), this);
        getServer().getPluginManager().registerEvents(itemListener, this);
        if (Compat.classExists("org.bukkit.event.block.CrafterCraftEvent")) {
            getServer().getPluginManager().registerEvents(new CrafterListener(items), this);
        }
        getServer().getPluginManager().registerEvents(this, this);

        register("withdraw", new WithdrawCommand(this::settings, hearts, this::log));
        register("hearts", new HeartsCommand(this::settings, store, hearts));
        register("lifesteal", new LifestealCommand(this));
        if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            try {
                io.github.drepfy.lifesteal.hook.LifestealPlaceholders.hook(this);
            } catch (LinkageError | RuntimeException e) {
                getLogger().warning("Could not add the PlaceholderAPI placeholders: " + e);
            }
        }

        preparePack();
        getServer().getServicesManager().register(LifestealPlugin.class, this, this, ServicePriority.Normal);

        // Saves at most a second after a change; files are written off the main thread.
        Bukkit.getScheduler().runTaskTimer(this, this::save, 20L, 20L);
        for (Player player : Bukkit.getOnlinePlayers()) {
            hearts.apply(player);
            recipe.discover(player);
        }
        Settings config = settings;
        getLogger().info("Enabled: " + config.startHearts() + " starting hearts, " + config.minHearts() + "-"
                + config.maxHearts() + ", " + config.perKill() + " per kill, cooldown "
                + io.github.drepfy.lifesteal.util.Durations.format(config.cooldownMs()) + ", alt protection "
                + (config.alts().enabled() ? "on" : "off") + ".");
    }

    @Override
    public void onDisable() {
        if (recipe != null) {
            recipe.unregister();
        }
        if (store != null) {
            store.close(now(), ipCutoff());
        }
        getServer().getServicesManager().unregisterAll(this);
    }

    private void register(String name, Object executor) {
        PluginCommand command = getCommand(name);
        if (command == null) {
            getLogger().severe("/" + name + " is missing from plugin.yml");
            return;
        }
        command.setExecutor((CommandExecutor) executor);
        command.setTabCompleter((TabCompleter) executor);
    }

    private void save() {
        store.save(now(), ipCutoff());
    }

    private long ipCutoff() {
        return now() - settings.alts().rememberIpMs();
    }

    // ---- configuration ---------------------------------------------------------------------------------

    /**
     * Reads config.yml again and applies it to everyone online.
     *
     * @return the warnings about the new settings
     * @throws IllegalStateException when the file cannot be read (the old settings stay)
     */
    public List<String> reload() {
        Settings fresh = readSettings(true);
        settings = fresh;
        logWarnings(fresh.warnings());
        recipe.apply(fresh);
        preparePack();
        for (Player player : Bukkit.getOnlinePlayers()) {
            hearts.apply(player);
            recipe.discover(player);
        }
        return fresh.warnings();
    }

    private Settings readSettings(boolean reloading) {
        File file = new File(getDataFolder(), "config.yml");
        io.github.drepfy.lifesteal.config.ConfigUpgrade.upgrade(file.toPath(), getLogger());
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
            return SettingsLoader.load(yaml);
        } catch (IOException | InvalidConfigurationException | RuntimeException e) {
            if (reloading) {
                throw new IllegalStateException(e.getMessage() == null ? e.toString() : e.getMessage(), e);
            }
            getLogger().severe("config.yml could not be read (" + e.getMessage() + "); using the defaults. Fix the "
                    + "file and run /lifesteal reload. The file was not changed.");
            return SettingsLoader.load(new YamlConfiguration());
        }
    }

    private void logWarnings(List<String> warnings) {
        for (String warning : warnings) {
            getLogger().warning("[config] " + warning);
        }
    }

    // ---- resource pack -------------------------------------------------------------------------------------

    /**
     * Puts the Heart texture pack in the plugin folder (once; an owner's own version is kept)
     * and remembers its SHA-1, which players' games use to check the download.
     */
    private void preparePack() {
        Path target = getDataFolder().toPath().resolve(PACK_FILE);
        try {
            if (!Files.exists(target)) {
                try (InputStream in = getResource(PACK_FILE)) {
                    if (in == null) {
                        return;
                    }
                    Files.createDirectories(target.getParent());
                    Files.write(target, in.readAllBytes());
                }
            }
            String configured = settings.resourcePack().sha1();
            packHash = configured.isEmpty() ? MessageDigest.getInstance("SHA-1").digest(Files.readAllBytes(target))
                    : HexFormat.of().parseHex(configured);
        } catch (IOException | java.security.NoSuchAlgorithmException | RuntimeException e) {
            getLogger().log(Level.WARNING, "Could not prepare " + PACK_FILE, e);
        }
    }

    /** SHA-1 of the resource pack that is sent ({@code null} if there is none). */
    public byte[] packHash() {
        return packHash == null ? null : packHash.clone();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        recipe.discover(player);
        Settings.ResourcePack pack = settings.resourcePack();
        if (!pack.url().isEmpty() && packHash != null) {
            // Added next to the server's own pack (server.properties). Sending it the old way
            // would replace every other pack, the legendary weapons' included.
            java.util.UUID id = java.util.UUID.nameUUIDFromBytes(("lifesteal:" + pack.url()).getBytes(StandardCharsets.UTF_8));
            try {
                player.addResourcePack(id, pack.url(), packHash, Text.color(pack.prompt()), pack.required());
            } catch (LinkageError e) {
                sendOldWay(player, pack); // Before 1.20.3 a player has only one pack anyway.
            } catch (RuntimeException e) {
                getLogger().fine("Could not send the resource pack: " + e);
            }
        }
    }

    @SuppressWarnings("deprecation")
    private void sendOldWay(Player player, Settings.ResourcePack pack) {
        try {
            player.setResourcePack(pack.url(), packHash, Text.color(pack.prompt()), pack.required());
        } catch (LinkageError | RuntimeException e) {
            getLogger().fine("Could not send the resource pack: " + e);
        }
    }

    // ---- shared -----------------------------------------------------------------------------------------------

    /** Appends a line to {@code logs/lifesteal.log} (when {@code log-to-file} is on). */
    public void log(String line) {
        if (settings.logToFile() && store != null) {
            store.appendLog(getDataFolder().toPath().resolve("logs").resolve("lifesteal.log"),
                    "[" + LocalDateTime.now().format(LOG_TIME) + "] " + line);
        }
    }

    public long now() {
        return clock.getAsLong();
    }

    /** Tests only: replaces the clock. */
    void setClock(LongSupplier clock) {
        this.clock = clock;
    }

    public Settings settings() {
        return settings;
    }

    public LifestealStore store() {
        return store;
    }

    public HeartService hearts() {
        return hearts;
    }

    public HeartItems items() {
        return items;
    }

    public AltProtection alts() {
        return alts;
    }

    public HeartRecipe recipe() {
        return recipe;
    }

    public HeartItemListener itemListener() {
        return itemListener;
    }
}
