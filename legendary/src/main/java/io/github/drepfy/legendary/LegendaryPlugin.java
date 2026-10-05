package io.github.drepfy.legendary;

import io.github.drepfy.legendary.ability.Abilities;
import io.github.drepfy.legendary.ability.Fx;
import io.github.drepfy.legendary.ability.Hits;
import io.github.drepfy.legendary.command.LegendaryCommand;
import io.github.drepfy.legendary.config.Settings;
import io.github.drepfy.legendary.config.SettingsLoader;
import io.github.drepfy.legendary.config.TextUpdate;
import io.github.drepfy.legendary.guard.StorageGuard;
import io.github.drepfy.legendary.guard.Tracker;
import io.github.drepfy.legendary.hud.Hud;
import io.github.drepfy.legendary.item.WeaponItems;
import io.github.drepfy.legendary.registry.WeaponRecord;
import io.github.drepfy.legendary.registry.WeaponRegistry;
import io.github.drepfy.legendary.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Five legendary netherite weapons (Kurogane, Sugarcrash, Riftblade, Gravebreaker and
 * Starforged), each with its own abilities, tracked one by one so they cannot be duplicated,
 * stored or passed between alt accounts.
 */
public class LegendaryPlugin extends JavaPlugin {

    private Settings settings;
    private YamlConfiguration defaults;
    private WeaponItems items;
    private WeaponRegistry registry;
    private Tracker tracker;
    private Abilities abilities;
    private Hits hits;
    private Fx fx;
    private Hud hud;
    private LongSupplier clock = System::currentTimeMillis;
    private long ticks;
    private final Map<String, Long> notices = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        defaults = resource("config.yml");
        updateOldTexts();
        settings = readSettings(false);
        items = new WeaponItems(this, this::settings);
        registry = new WeaponRegistry(getLogger(), getDataFolder().toPath(), this::now);
        fx = new Fx(this::settings);
        hits = new Hits(this::settings);
        hud = new Hud(this);
        tracker = new Tracker(this);
        abilities = new Abilities(this);

        var manager = getServer().getPluginManager();
        manager.registerEvents(tracker, this);
        manager.registerEvents(new StorageGuard(this), this);
        manager.registerEvents(hits, this);
        manager.registerEvents(abilities, this);
        for (Listener listener : abilities.extraListeners()) {
            manager.registerEvents(listener, this);
        }
        PluginCommand command = getCommand("legendary");
        if (command != null) {
            LegendaryCommand executor = new LegendaryCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }
        Bukkit.getScheduler().runTaskTimer(this, this::onTick, 1L, 1L);
        Bukkit.getScheduler().runTask(this, tracker::scan); // Players already online (after /reload).

        long out = registry.all().stream().filter(WeaponRecord::exists).count();
        getLogger().info("Enabled: " + WeaponType.values().length + " legendary weapons, " + out
                + " in the world.");
    }

    @Override
    public void onDisable() {
        if (hud != null) {
            hud.clearAll();
        }
        if (registry != null) {
            registry.close(ipCutoff());
        }
    }

    private void onTick() {
        ticks++;
        abilities.tick(ticks);
        if (ticks % 4 == 0) {
            hud.update(ticks);
        }
        if (ticks % 10 == 0) {
            tracker.checkGround();
        }
        if (ticks % 40 == 0) {
            tracker.scan();
        }
        if (ticks % 1200 == 0) {
            registry.save(ipCutoff());
            notices.clear();
        }
    }

    private long ipCutoff() {
        return now() - (settings == null ? 0 : settings.alts().rememberMs());
    }

    // ---- config -------------------------------------------------------------------------------------------

    /**
     * Reads config.yml again and updates every legendary in players' hands to match it.
     *
     * @return the warnings
     */
    public List<String> reload() {
        reloadConfig();
        updateOldTexts();
        settings = readSettings(true);
        tracker.scan();
        return settings.warnings();
    }

    private Settings readSettings(boolean reloading) {
        Settings loaded;
        try {
            loaded = SettingsLoader.load(getConfig(), defaults);
        } catch (RuntimeException e) {
            getLogger().severe("config.yml could not be read (" + e.getMessage() + "); using the defaults.");
            loaded = SettingsLoader.load(new YamlConfiguration(), defaults);
        }
        for (String warning : loaded.warnings()) {
            getLogger().warning("config.yml: " + warning);
        }
        return loaded;
    }

    /**
     * Lore and messages still worded exactly as an older version shipped them are switched to the
     * new defaults (and saved); anything changed by hand stays as it is.
     */
    private void updateOldTexts() {
        int updated = TextUpdate.apply(getConfig(), resource("previous-text.yml"), defaults);
        if (updated > 0) {
            saveConfig();
            getLogger().info("Updated " + updated + " weapon text(s) in config.yml to the new, simpler wording."
                    + " Texts you had changed yourself were kept.");
        }
    }

    private YamlConfiguration resource(String name) {
        try (InputStream in = getResource(name)) {
            if (in != null) {
                return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
        } catch (java.io.IOException ignored) {
            // Fall through.
        }
        return new YamlConfiguration();
    }

    // ---- messages -----------------------------------------------------------------------------------------

    /** A message from config.yml with the prefix; nothing when it is set to "". */
    public void send(CommandSender to, String key, Object... pairs) {
        String message = settings.message(key);
        if (!message.isEmpty()) {
            to.sendMessage(Text.format(settings.prefix() + message, pairs));
        }
    }

    /** The same, at most once a second per player and message (for things that repeat while clicking). */
    public void notice(Player player, String key, Object... pairs) {
        String id = player.getUniqueId() + ":" + key;
        long now = now();
        Long last = notices.get(id);
        if (last != null && now - last < 1000 && now >= last) {
            return;
        }
        notices.put(id, now);
        send(player, key, pairs);
    }

    /** Tells online staff (legendary.alerts), the console and history.log. */
    public void alert(String message) {
        String text = Text.format(settings.message("alert"), "message", Text.color("&7" + message));
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission("legendary.alerts")) {
                player.sendMessage(text);
            }
        }
        String plain = ChatColor.stripColor(Text.color(message));
        getLogger().info(plain);
        registry.log("ALERT " + plain);
    }

    // ---- shared -------------------------------------------------------------------------------------------

    public Settings settings() {
        return settings;
    }

    public WeaponItems items() {
        return items;
    }

    public WeaponRegistry registry() {
        return registry;
    }

    public Tracker tracker() {
        return tracker;
    }

    public Abilities abilities() {
        return abilities;
    }

    public Hits hits() {
        return hits;
    }

    public Fx fx() {
        return fx;
    }

    public Hud hud() {
        return hud;
    }

    /** Server ticks since the plugin started (abilities and cooldowns count in these). */
    public long tick() {
        return ticks;
    }

    /** Wall-clock time in epoch milliseconds (replaced in tests). */
    public long now() {
        return clock.getAsLong();
    }

    void setClock(LongSupplier clock) {
        this.clock = clock;
    }
}
