package com.drepfy.staffvanish.listener;

import com.drepfy.staffvanish.VanishModule;
import com.drepfy.staffvanish.VanishSettings;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerAttemptPickupItemEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerPickupArrowEvent;
import org.bukkit.event.world.GenericGameEvent;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * Stops vanished players from giving themselves away through the world, and stops the world from noticing them.
 * Each rule can be turned off under {@code vanish.protection} in the config.
 */
public final class ProtectionListener implements Listener {

    private final VanishModule module;

    public ProtectionListener(VanishModule module) {
        this.module = module;
    }

    /** Vanished players can't be hurt at all, not even by /kill or the void. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!protection().invulnerable() || !(event.getEntity() instanceof Player player) || !isVanished(player)) {
            return;
        }
        event.setCancelled(true);
        if (event.getCause() == EntityDamageEvent.DamageCause.VOID) {
            catchFromVoid(player);
        }
    }

    /** Undoes anything that still kills a vanished player, such as another plugin setting their health to 0. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDying(PlayerDeathEvent event) {
        Player player = event.getPlayer();
        if (!protection().invulnerable() || !isVanished(player)) {
            return;
        }
        AttributeInstance maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
        event.setReviveHealth(maxHealth != null ? maxHealth.getValue() : 1);
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHunger(FoodLevelChangeEvent event) {
        if (protection().noHunger() && event.getEntity() instanceof Player player && isVanished(player)
                && event.getFoodLevel() < player.getFoodLevel()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onAttemptPickup(PlayerAttemptPickupItemEvent event) {
        if (protection().noItemPickup() && isVanished(event.getPlayer())) {
            event.setFlyAtPlayer(false);
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (protection().noItemPickup() && isVanished(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPickupArrow(PlayerPickupArrowEvent event) {
        if (protection().noItemPickup() && isVanished(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    /** Pressure plates, tripwires, farmland, turtle eggs, redstone ore, ... */
    @EventHandler(priority = EventPriority.LOW)
    public void onPhysicalInteract(PlayerInteractEvent event) {
        if (event.getAction() == Action.PHYSICAL && protection().noPhysicalInteraction()
                && isVanished(event.getPlayer())) {
            event.setUseInteractedBlock(Event.Result.DENY);
            event.setUseItemInHand(Event.Result.DENY);
        }
    }

    /** Covers mobs choosing a target and experience orbs choosing a player to fly towards. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onTarget(EntityTargetEvent event) {
        if (protection().noMobTargeting() && isVanished(event.getTarget())) {
            event.setCancelled(true);
        }
    }

    /** Vibrations picked up by sculk sensors, sculk shriekers and wardens. May run off the main thread. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onGameEvent(GenericGameEvent event) {
        if (protection().noSculkDetection() && isVanished(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    /** A cancelled hit lets the projectile carry on as if nobody was there. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onProjectileHit(ProjectileHitEvent event) {
        Entity hit = event.getHitEntity();
        if (protection().projectilesPassThrough() && isVanished(hit)
                && !hit.equals(event.getEntity().getShooter())) {
            event.setCancelled(true);
        }
    }

    /** Paper still shows a hidden player's chat to everyone, so it has to be stopped here. Runs asynchronously. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (protection().blockChat() && isVanished(event.getPlayer())) {
            event.setCancelled(true);
            module.messages().send(event.getPlayer(), "chat-blocked");
        }
    }

    /**
     * Vanilla {@code /msg} and friends find players by exact name even when they're hidden, and the reply would
     * confirm the vanished player is online. Answer as if they were offline instead.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPrivateMessage(PlayerCommandPreprocessEvent event) {
        if (module.manager().vanishedOnline().isEmpty()) {
            return;
        }
        String[] words = event.getMessage().split(" ", 3);
        if (words.length < 2) {
            return;
        }
        String label = words[0].substring(1).toLowerCase(Locale.ROOT);
        label = label.substring(label.indexOf(':') + 1);
        if (!protection().privateMessageCommands().contains(label)) {
            return;
        }
        Player target = Bukkit.getPlayerExact(words[1]);
        if (target != null && !module.manager().canSee(event.getPlayer(), target)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(Component.translatable("argument.entity.notfound.player", NamedTextColor.RED));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        if (isVanished(event.getPlayer())) {
            event.deathMessage(null);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        if (isVanished(event.getPlayer())) {
            event.message(null);
        }
    }

    /** Stops a vanished player falling through the void: they fly, or go to spawn if they can't. */
    private static void catchFromVoid(Player player) {
        player.setFallDistance(0);
        if (player.getAllowFlight()) {
            player.setVelocity(new Vector());
            player.setFlying(true);
        } else {
            player.teleportAsync(player.getWorld().getSpawnLocation());
        }
    }

    private VanishSettings.Protection protection() {
        return module.settings().protection();
    }

    private boolean isVanished(@Nullable Entity entity) {
        return entity instanceof Player player && module.manager().isVanished(player.getUniqueId());
    }
}
