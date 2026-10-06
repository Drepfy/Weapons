package io.github.drepfy.combat;

import io.github.drepfy.combat.config.ConfigUpgrade;
import io.github.drepfy.combat.config.Settings;
import io.github.drepfy.combat.config.SettingsLoader;
import io.github.drepfy.combat.hook.CombatPlaceholders;
import io.github.drepfy.combat.util.ActionBar;
import io.github.drepfy.combat.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Combat timer and Ender Pearl cooldown.
 *
 * <ul>
 *   <li>Hitting a player puts both in combat for 60 seconds, shown above the hotbar
 *   ({@code ⚔ Combat: 45s}). Nothing but time, death or the kill rule ends it.</li>
 *   <li>Killing a player who wears armor ends your combat with them; killing a naked player
 *   does not.</li>
 *   <li>Ender Pearls have a 15 second cooldown per player.</li>
 *   <li>Both are kept on the server and survive reconnects and restarts.</li>
 *   <li>No commands in combat (except /combat), and logging out in combat kills the player
 *   (their items drop where they logged out).</li>
 * </ul>
 *
 * <p>Other plugins: {@code Bukkit.getServicesManager().load(CombatPlugin.class).isInCombat(player)}.
 */
public class CombatPlugin extends JavaPlugin {

    private record Shown(int seconds, long at) {
    }

    private volatile Settings settings;
    private volatile LongSupplier clock = System::currentTimeMillis;
    private final CombatTracker tracker = new CombatTracker();
    /** When each player's Ender Pearl cooldown ends (wall clock, so it also runs while offline). */
    private final Map<UUID, Long> pearls = new HashMap<>();
    private final Map<UUID, Shown> shown = new HashMap<>();
    /** Players being killed right now for logging out in combat (their items must drop). */
    private final java.util.Set<UUID> loggingOut = new java.util.HashSet<>();
    private final Map<UUID, Long> lastRefusal = new HashMap<>();
    /** When each player was last told they cannot do something (one message a second). */
    private final Map<String, Long> lastNotice = new HashMap<>();
    private final Map<UUID, org.bukkit.Location[]> selections = new HashMap<>();
    private CombatListener combatListener;
    private ZoneStore zones;
    private DataFile data;
    private ExecutorService io;
    private boolean dirty;
    private long ticks;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        ConfigUpgrade.upgrade(new File(getDataFolder(), "config.yml").toPath(), getLogger());
        settings = readSettings(false);
        settings.warnings().forEach(warning -> getLogger().warning("[config] " + warning));
        io = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "Combat-IO");
            thread.setDaemon(true);
            return thread;
        });
        data = new DataFile(getDataFolder().toPath().resolve("data.yml"), getLogger());
        data.load(tracker, pearls, now());

        zones = new ZoneStore(getDataFolder().toPath().resolve("zones.yml"), getLogger());
        combatListener = new CombatListener(this);
        getServer().getPluginManager().registerEvents(combatListener, this);
        getServer().getPluginManager().registerEvents(new PearlListener(this), this);
        getServer().getPluginManager().registerEvents(new MovementListener(this), this);
        CombatCommand command = new CombatCommand(this);
        PluginCommand pluginCommand = getCommand("combat");
        if (pluginCommand != null) {
            pluginCommand.setExecutor(command);
            pluginCommand.setTabCompleter(command);
        }
        getServer().getServicesManager().register(CombatPlugin.class, this, this, ServicePriority.Normal);
        if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            try {
                CombatPlaceholders.hook(this);
            } catch (LinkageError | RuntimeException e) {
                getLogger().warning("Could not add the PlaceholderAPI placeholders: " + e);
            }
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            loggedIn(player); // After /reload.
        }
        Bukkit.getScheduler().runTaskTimer(this, this::tick, 1L, 1L);
        getLogger().info("Enabled: combat " + settings.combatMs() / 1000 + "s, Ender Pearl cooldown "
                + settings.pearlMs() / 1000 + "s, logging out in combat: "
                + settings.logout().name().toLowerCase(java.util.Locale.ROOT) + ", " + zones.all().size()
                + " safe zone(s).");
    }

    @Override
    public void onDisable() {
        if (data == null) {
            return;
        }
        long now = now();
        for (Player player : Bukkit.getOnlinePlayers()) {
            tracker.pause(player.getUniqueId(), now);
            ActionBar.clear(player);
            player.removeMetadata(IN_COMBAT, this);
        }
        io.shutdown();
        try {
            io.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        data.write(data.snapshot(tracker, pearls, now));
        getServer().getServicesManager().unregisterAll(this);
    }

    // ---- combat ------------------------------------------------------------------------------------

    /** {@code attacker} hurt {@code victim}: both are in combat with each other. */
    void fight(Player attacker, Player victim) {
        if (attacker == null || attacker.equals(victim) || attacker.hasMetadata("NPC") || victim.hasMetadata("NPC")) {
            return;
        }
        long now = now();
        long duration = settings.combatMs();
        tracker.tag(attacker.getUniqueId(), victim.getUniqueId(), now, duration);
        tracker.tag(victim.getUniqueId(), attacker.getUniqueId(), now, duration);
        dirty = true;
    }

    /** Ends all of a player's combat (death, or staff). */
    void endCombat(Player player, boolean tell) {
        boolean wasTold = tracker.announced(player.getUniqueId());
        tracker.clear(player.getUniqueId());
        shown.remove(player.getUniqueId());
        dirty = true;
        if (player.isOnline()) {
            ActionBar.clear(player);
            if (tell && wasTold) {
                send(player, "combat-end");
                play(player, settings.sounds().end());
            }
        }
    }

    /** Whether this death is the punishment for logging out in combat. */
    boolean dyingForLoggingOut(Player player) {
        return loggingOut.contains(player.getUniqueId());
    }

    void loggedOut(Player player, boolean kicked) {
        long now = now();
        UUID uuid = player.getUniqueId();
        if (tracker.inCombat(uuid, now) && settings.logout() == Settings.LogoutRule.KILL && !kicked
                && !serverStopping() && !player.isDead()) {
            String text = Text.color(message("prefix")) + Text.format(message("combat-logout-kill"),
                    "player", player.getName());
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (!online.equals(player)) {
                    online.sendMessage(text);
                }
            }
            getLogger().info(player.getName() + " logged out in combat and was killed.");
            creditKill(player, now);
            loggingOut.add(uuid);
            try {
                player.setHealth(0.0);
            } finally {
                loggingOut.remove(uuid);
            }
        }
        tracker.pause(uuid, now);
        shown.remove(uuid);
        lastRefusal.remove(uuid);
        lastNotice.keySet().removeIf(key -> key.startsWith(uuid + ":"));
        dirty = true;
    }

    /** The name of the player this one fought last, while still in combat (null if none). */
    public String lastOpponent(Player player) {
        UUID latest = null;
        long longest = 0;
        for (Map.Entry<UUID, Long> fight : tracker.opponents(player.getUniqueId(), now()).entrySet()) {
            if (fight.getValue() > longest) {
                latest = fight.getKey();
                longest = fight.getValue();
            }
        }
        if (latest == null) {
            return null;
        }
        return latest.equals(CombatTracker.STAFF) ? "staff" : Bukkit.getOfflinePlayer(latest).getName();
    }

    /**
     * The game only remembers who hit a player for 5 seconds. Someone who logs out later in the
     * fight still dies at the hands of their latest opponent (kill credit, a stolen heart).
     */
    private void creditKill(Player player, long now) {
        if (player.getKiller() != null) {
            return;
        }
        Player opponent = null;
        long latest = Long.MIN_VALUE;
        for (Map.Entry<UUID, Long> fight : tracker.opponents(player.getUniqueId(), now).entrySet()) {
            Player other = fight.getKey().equals(CombatTracker.STAFF) ? null : Bukkit.getPlayer(fight.getKey());
            if (other != null && other.isOnline() && !other.isDead() && fight.getValue() > latest) {
                opponent = other;
                latest = fight.getValue();
            }
        }
        if (opponent != null) {
            try {
                player.setKiller(opponent);
            } catch (LinkageError | RuntimeException ignored) {
                // Spigot has no setKiller: the death counts without a killer there.
            }
        }
    }

    void loggedIn(Player player) {
        long now = now();
        UUID uuid = player.getUniqueId();
        tracker.resume(uuid, now);
        long left = tracker.remaining(uuid, now);
        if (left > 0) {
            send(player, "combat-rejoin", "seconds", seconds(left));
        }
        showPearlCooldown(player, pearlCooldown(player));
    }

    /** Every tick: end finished fights and keep the action bar up to date. */
    private void tick() {
        long now = now();
        for (UUID uuid : tracker.expire(now)) {
            shown.remove(uuid);
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                if (!barClaimed(player)) {
                    ActionBar.clear(player);
                }
                send(player, "combat-end");
                play(player, settings.sounds().end());
            }
            dirty = true;
        }
        for (UUID uuid : new ArrayList<>(tracker.players())) {
            Player player = Bukkit.getPlayer(uuid);
            long left = tracker.remaining(uuid, now);
            if (player == null || left <= 0) {
                continue;
            }
            if (tracker.announce(uuid)) {
                send(player, "combat-start");
                play(player, settings.sounds().start());
            }
            if (barClaimed(player)) {
                // Another plugin shows the combat time in its own bar (Legendary weapons).
                shown.remove(uuid);
                continue;
            }
            int seconds = seconds(left);
            Shown last = shown.get(uuid);
            // Every new second, and at least once a second so the bar never fades.
            if (last == null || last.seconds() != seconds || now - last.at() >= 1000) {
                ActionBar.send(player, Text.format(message("action-bar"), "seconds", seconds));
                shown.put(uuid, new Shown(seconds, now));
            }
        }
        flagCombat(now);
        if (ticks % 5 == 0) {
            stopGliding();
        }
        if (ticks % 10 == 0 && settings.zones().showBorder() && !zones.all().isEmpty()) {
            showBorders();
        }
        if (++ticks % 100 == 0) {
            combatListener.forgetOld(now);
            pearls.values().removeIf(until -> until <= now);
            save();
        }
    }

    // ---- movement in combat ------------------------------------------------------------------------------

    /** Whether an elytra or riptide is refused right now: in combat, and (with a radius) near an opponent. */
    boolean movementBlocked(Player player, Settings.Movement rule) {
        if (!rule.blocked() || !isInCombat(player)) {
            return false;
        }
        return rule.radius() <= 0 || opponentWithin(player, rule.radius());
    }

    /** Whether a player this one is fighting is within {@code radius} blocks. */
    boolean opponentWithin(Player player, double radius) {
        org.bukkit.Location at = player.getLocation();
        for (UUID opponent : tracker.opponents(player.getUniqueId(), now()).keySet()) {
            Player other = opponent.equals(CombatTracker.STAFF) ? null : Bukkit.getPlayer(opponent);
            if (other != null && other.getWorld().equals(player.getWorld())
                    && other.getLocation().distanceSquared(at) <= radius * radius) {
                return true;
            }
        }
        return false;
    }

    /** Players who were already gliding when the rule started to apply come down. */
    private void stopGliding() {
        for (UUID uuid : new ArrayList<>(tracker.players())) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isGliding() && movementBlocked(player, settings.elytra())) {
                player.setGliding(false);
                refuse(player, "elytra-blocked");
            }
        }
    }

    /** Tells the player once a second at most (holding the button would spam otherwise). */
    void refuse(Player player, String key, Object... pairs) {
        long now = now();
        String id = player.getUniqueId() + ":" + key;
        Long last = lastNotice.get(id);
        if (last == null || now - last >= 1000 || now < last) {
            lastNotice.put(id, now);
            send(player, key, pairs);
        }
    }

    void refuseZone(Player player, SafeZone zone) {
        refuse(player, "zone-blocked", "zone", zone.name(), "seconds", seconds(combatRemaining(player)));
    }

    ZoneStore zones() {
        return zones;
    }

    /** {@code /combat zone pos1|pos2}: the corners picked by each staff member. */
    org.bukkit.Location[] selection(Player player) {
        return selections.computeIfAbsent(player.getUniqueId(), key -> new org.bukkit.Location[2]);
    }

    /** A red wall of particles where a player in combat is close to a safe zone (only they see it). */
    private void showBorders() {
        double reach = settings.zones().borderDistance();
        for (UUID uuid : new ArrayList<>(tracker.players())) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !isInCombat(player)) {
                continue;
            }
            org.bukkit.Location at = player.getLocation();
            for (SafeZone zone : zones.all()) {
                if (!zone.world().equals(player.getWorld().getName()) || zone.contains(at)
                        || zone.distance(at.getX(), at.getZ()) > reach) {
                    continue;
                }
                Border.draw(player, zone, reach);
            }
        }
    }

    // ---- Ender Pearls ---------------------------------------------------------------------------------

    /** Milliseconds until the player may throw an Ender Pearl (0 = now). */
    public long pearlCooldown(Player player) {
        Long until = pearls.get(player.getUniqueId());
        return until == null ? 0L : Math.max(0L, until - now());
    }

    void pearlThrown(Player player) {
        long cooldown = settings.pearlMs();
        if (cooldown <= 0) {
            return;
        }
        pearls.put(player.getUniqueId(), now() + cooldown);
        if (settings.pearlResetsTimer()) {
            // Pearling away does not run the clock down: the 60 seconds start again.
            tracker.refresh(player.getUniqueId(), now(), settings.combatMs());
        }
        dirty = true;
        showPearlCooldown(player, cooldown);
        // The game sets its own 1 second cooldown after the throw; replace it with ours.
        Bukkit.getScheduler().runTask(this, () -> showPearlCooldown(player, pearlCooldown(player)));
    }

    void pearlRefused(Player player, long left) {
        showPearlCooldown(player, left);
        long now = now();
        Long last = lastRefusal.get(player.getUniqueId());
        if (last == null || now - last >= 1000 || now < last) {
            lastRefusal.put(player.getUniqueId(), now);
            send(player, "pearl-cooldown", "seconds", seconds(left));
        }
    }

    /** The grey sweep over the pearl in the hotbar. Only for show: the server enforces the cooldown itself. */
    private void showPearlCooldown(Player player, long left) {
        if (settings.pearlOverlay() && left > 0 && player.isOnline()) {
            try {
                itemCooldown.accept(player, (int) Math.min(Integer.MAX_VALUE, (left + 49) / 50));
            } catch (RuntimeException | LinkageError ignored) {
                // A server without item cooldowns: the pearl is still refused.
            }
        }
    }

    /** Sets the client's item cooldown (replaced in tests). */
    java.util.function.ObjIntConsumer<Player> itemCooldown =
            (player, ticks) -> player.setCooldown(Material.ENDER_PEARL, ticks);

    /**
     * Player metadata another plugin sets while it draws the action bar itself; its value is
     * the epoch millisecond it expires. The Legendary plugin uses it and puts the combat time
     * in front of its own bar, so the two never keep replacing each other.
     */
    public static final String BAR_CLAIM = "vanillasmp:actionbar";

    /**
     * Player metadata set while a player is in combat, for scripts:
     * {@code if metadata value "incombat" of player is set} (Skript).
     */
    public static final String IN_COMBAT = "incombat";

    /** Keeps the in-combat flag in step with the timer. */
    private void flagCombat(long now) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            boolean fighting = tracker.inCombat(player.getUniqueId(), now);
            if (fighting != player.hasMetadata(IN_COMBAT)) {
                if (fighting) {
                    player.setMetadata(IN_COMBAT, new org.bukkit.metadata.FixedMetadataValue(this, true));
                } else {
                    player.removeMetadata(IN_COMBAT, this);
                }
            }
        }
    }

    static boolean barClaimed(Player player) {
        for (org.bukkit.metadata.MetadataValue value : player.getMetadata(BAR_CLAIM)) {
            if (value.value() instanceof Long until && until > System.currentTimeMillis()) {
                return true;
            }
        }
        return false;
    }

    // ---- API -------------------------------------------------------------------------------------------

    public boolean isInCombat(Player player) {
        return tracker.inCombat(player.getUniqueId(), now());
    }

    /** Milliseconds of combat left (0 = not in combat). */
    public long combatRemaining(Player player) {
        return tracker.remaining(player.getUniqueId(), now());
    }

    // ---- shared -----------------------------------------------------------------------------------------

    /**
     * Reads config.yml again.
     *
     * @return the warnings
     */
    public List<String> reload() {
        settings = readSettings(true);
        settings.warnings().forEach(warning -> getLogger().warning("[config] " + warning));
        return settings.warnings();
    }

    private Settings readSettings(boolean reloading) {
        File file = new File(getDataFolder(), "config.yml");
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
            return SettingsLoader.load(yaml);
        } catch (IOException | InvalidConfigurationException | RuntimeException e) {
            if (reloading) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            getLogger().severe("config.yml could not be read (" + e.getMessage() + "); using the defaults.");
            return SettingsLoader.load(new YamlConfiguration());
        }
    }

    private void save() {
        if (!dirty || io.isShutdown()) {
            return;
        }
        dirty = false;
        String snapshot = data.snapshot(tracker, pearls, now());
        try {
            io.execute(() -> data.write(snapshot));
        } catch (java.util.concurrent.RejectedExecutionException e) {
            dirty = true;
        }
    }

    private static void play(Player player, io.github.drepfy.combat.config.Settings.SoundSpec sound) {
        if (sound == null) {
            return;
        }
        try {
            player.playSound(player.getLocation(), sound.key(), sound.volume(), sound.pitch());
        } catch (RuntimeException | LinkageError ignored) {
            // Only a sound.
        }
    }

    /** Paper knows when the server is shutting down (everyone is kicked, nobody logged out to escape). */
    private static boolean serverStopping() {
        try {
            return (boolean) Bukkit.getServer().getClass().getMethod("isStopping").invoke(Bukkit.getServer());
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    public static int seconds(long millis) {
        return (int) ((millis + 999) / 1000);
    }

    String message(String key) {
        return settings.messages().get(key);
    }

    void send(Player player, String key, Object... pairs) {
        String template = message(key);
        if (template != null && !template.isEmpty()) {
            player.sendMessage(Text.color(message("prefix")) + Text.format(template, pairs));
        }
    }

    public Settings settings() {
        return settings;
    }

    CombatTracker tracker() {
        return tracker;
    }

    public long now() {
        return clock.getAsLong();
    }

    /** Tests only. */
    void setClock(LongSupplier clock) {
        this.clock = clock;
    }
}
