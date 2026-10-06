package io.github.drepfy.lifesteal.heart;

import io.github.drepfy.lifesteal.alt.AltProtection;
import io.github.drepfy.lifesteal.api.HeartStealEvent;
import io.github.drepfy.lifesteal.config.Settings;
import io.github.drepfy.lifesteal.data.LifestealStore;
import io.github.drepfy.lifesteal.util.Durations;
import io.github.drepfy.lifesteal.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.Plugin;

import java.net.InetSocketAddress;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The heart rules on death, plus applying hearts when players join or respawn.
 *
 * <p>A player kill takes {@code per-kill} hearts from the victim (never below the minimum)
 * and gives them to the killer (never above the maximum; the rest drops as Heart items
 * where the victim died). It does nothing when the alt account protection objects, when
 * the killer stole from this victim within the cooldown, or when the victim is at the
 * minimum. The death itself is always a normal Minecraft death.
 */
public final class KillListener implements Listener {

    public static final String NOTIFY_PERMISSION = "lifesteal.notify";

    private final Plugin plugin;
    private final Supplier<Settings> settings;
    private final LifestealStore store;
    private final HeartService hearts;
    private final AltProtection alts;
    private final HeartEffects effects;
    private final LongSupplier clock;
    private final Consumer<String> log;

    public KillListener(Plugin plugin, Supplier<Settings> settings, LifestealStore store, HeartService hearts,
                        AltProtection alts, HeartEffects effects, LongSupplier clock, Consumer<String> log) {
        this.plugin = plugin;
        this.settings = settings;
        this.store = store;
        this.hearts = hearts;
        this.alts = alts;
        this.effects = effects;
        this.clock = clock;
        this.log = log;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Settings config = settings.get();
        if (isNpc(victim) || config.disabledWorlds().contains(victim.getWorld().getName())) {
            return;
        }
        Player killer = victim.getKiller();
        if (killer == null || killer.equals(victim) || isNpc(killer)) {
            naturalDeath(victim, config);
            return;
        }
        long now = clock.getAsLong();

        String reason = alts.check(killer, victim);
        if (reason != null) {
            send(killer, config.messages().get("alt-killer"));
            send(victim, config.messages().get("alt-victim"));
            if (config.alts().notifyStaff()) {
                String alert = Text.format(config.messages().get("alt-staff"), "killer", killer.getName(),
                        "victim", victim.getName(), "reason", reason);
                for (Player staff : Bukkit.getOnlinePlayers()) {
                    if (staff.hasPermission(NOTIFY_PERMISSION)) {
                        staff.sendMessage(alert);
                    }
                }
            }
            plugin.getLogger().info(killer.getName() + " killed " + victim.getName() + "; not counted: " + reason);
            log.accept(killer.getName() + " killed " + victim.getName() + ": not counted (" + reason + ")");
            return;
        }

        long until = store.cooldownUntil(killer.getUniqueId(), victim.getUniqueId());
        if (config.cooldownBothWays()) {
            until = Math.max(until, store.cooldownUntil(victim.getUniqueId(), killer.getUniqueId()));
        }
        if (until > now) {
            send(killer, config.messages().get("cooldown-killer"), "victim", victim.getName(),
                    "time", Durations.format(until - now));
            send(victim, config.messages().get("cooldown-victim"), "killer", killer.getName());
            log.accept(killer.getName() + " killed " + victim.getName() + ": cooldown ("
                    + Durations.format(until - now) + " left)");
            return;
        }

        int victimHearts = hearts.hearts(victim);
        int take = Math.min(config.perKill(), victimHearts - config.minHearts());
        if (take <= 0) {
            send(killer, config.messages().get("victim-at-min-killer"), "victim", victim.getName(),
                    "min", config.minHearts());
            send(victim, config.messages().get("victim-at-min-victim"), "min", config.minHearts());
            return;
        }
        HeartStealEvent steal = new HeartStealEvent(killer, victim, take);
        Bukkit.getPluginManager().callEvent(steal);
        if (steal.isCancelled()) {
            return;
        }

        int victimAfter = hearts.set(victim, victimHearts - take, false);
        int killerBefore = hearts.hearts(killer);
        int gain = Math.max(0, Math.min(take, config.maxHearts() - killerBefore));
        int killerAfter = gain > 0 ? hearts.set(killer, killerBefore + gain, config.healGainedHearts()) : killerBefore;
        int dropped = take - gain;
        if (dropped > 0) {
            hearts.drop(victim.getLocation(), dropped);
        }
        store.setCooldown(killer.getUniqueId(), victim.getUniqueId(), now + config.cooldownMs());

        if (gain > 0) {
            send(killer, config.messages().get("steal-killer"), "victim", victim.getName(), "hearts", killerAfter);
            effects.gained(killer, gain, victim.getName(), killerAfter);
        }
        if (dropped > 0) {
            send(killer, config.messages().get("steal-killer-at-max"), "victim", victim.getName(),
                    "max", config.maxHearts());
        }
        send(victim, config.messages().get("steal-victim"), "killer", killer.getName(), "hearts", victimAfter);
        effects.lost(victim, take, killer.getName(), victimAfter);
        log.accept(killer.getName() + " killed " + victim.getName() + ": " + victim.getName() + " " + victimHearts
                + " -> " + victimAfter + ", " + killer.getName() + " " + killerBefore + " -> " + killerAfter
                + (dropped > 0 ? ", " + dropped + " Heart item(s) dropped" : ""));
    }

    private void naturalDeath(Player victim, Settings config) {
        if (!config.loseOnNaturalDeath()) {
            return;
        }
        int before = hearts.hearts(victim);
        int take = Math.min(config.perKill(), before - config.minHearts());
        if (take <= 0) {
            return;
        }
        int after = hearts.set(victim, before - take, false);
        send(victim, config.messages().get("natural-death"), "hearts", after);
        effects.lost(victim, take, null, after);
        log.accept(victim.getName() + " died: " + before + " -> " + after);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        hearts.apply(player);
        InetSocketAddress address = player.getAddress();
        alts.recordJoin(player, address != null ? address.getAddress() : null);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        // After the respawn has finished: full health with the current number of hearts.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline() && !player.isDead()) {
                hearts.apply(player);
                org.bukkit.attribute.AttributeInstance max =
                        player.getAttribute(io.github.drepfy.lifesteal.util.Compat.MAX_HEALTH);
                if (max != null) {
                    player.setHealth(max.getValue());
                }
            }
        });
    }

    /** Citizens and similar plugins mark their fake players. */
    private static boolean isNpc(Player player) {
        return player.hasMetadata("NPC");
    }

    private void send(Player player, String template, Object... pairs) {
        if (template == null || template.isEmpty()) {
            return;
        }
        player.sendMessage(Text.color(settings.get().messages().get("prefix")) + Text.format(template, pairs));
    }
}
