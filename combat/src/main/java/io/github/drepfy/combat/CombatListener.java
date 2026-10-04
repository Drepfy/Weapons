package io.github.drepfy.combat;

import io.github.drepfy.combat.config.Settings;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.AreaEffectCloudApplyEvent;
import org.bukkit.event.entity.EntityDamageByBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * What puts players in combat, what ends it, and what never does.
 *
 * <p>Hitting another player in any way (melee, arrows, tridents, harmful potions, TNT, end
 * crystals, respawn anchors, tamed wolves) puts both in combat. Only time, death and the kill
 * rule end it. Commands, inventories, teleports, world changes and logging out change nothing:
 * the timer lives on the server and is paused while the player is offline.
 */
final class CombatListener implements Listener {

    private final CombatPlugin plugin;
    private final Attackers attackers = new Attackers();
    /** Armor worn at the moment of death, read before anything else can change the inventory. */
    private final Map<UUID, Boolean> armorAtDeath = new HashMap<>();
    private final Set<UUID> kicked = new HashSet<>();

    CombatListener(CombatPlugin plugin) {
        this.plugin = plugin;
    }

    // ---- entering combat ---------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        long now = plugin.now();
        if (event.getEntity() instanceof EnderCrystal crystal) {
            Player hitter = attackers.of(event, now);
            if (hitter != null) {
                attackers.crystalHit(crystal, hitter, now);
            }
            return;
        }
        if (event.getEntity() instanceof Player victim) {
            plugin.fight(attackers.of(event, now), victim);
        }
    }

    /** Respawn anchor and bed explosions (the game does not say who set them off). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockDamage(EntityDamageByBlockEvent event) {
        if (event.getCause() == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION && event.getEntity() instanceof Player victim) {
            plugin.fight(attackers.blockExplosion(victim.getLocation(), plugin.now()), victim);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null) {
            attackers.blockUsed(event.getClickedBlock(), event.getPlayer(), plugin.now());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSplash(PotionSplashEvent event) {
        if (!(event.getPotion().getShooter() instanceof Player thrower) || !Attackers.harmful(effects(event.getPotion()))) {
            return;
        }
        for (LivingEntity hit : event.getAffectedEntities()) {
            if (hit instanceof Player victim && event.getIntensity(hit) > 0) {
                plugin.fight(thrower, victim);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCloud(AreaEffectCloudApplyEvent event) {
        AreaEffectCloud cloud = event.getEntity();
        if (!(cloud.getSource() instanceof Player thrower) || !Attackers.harmful(effects(cloud))) {
            return;
        }
        for (LivingEntity hit : event.getAffectedEntities()) {
            if (hit instanceof Player victim) {
                plugin.fight(thrower, victim);
            }
        }
    }

    /** The potion's effects, also read from the item (its base potion) in case the server lists only extra ones. */
    private static List<PotionEffect> effects(org.bukkit.entity.ThrownPotion potion) {
        List<PotionEffect> effects = new ArrayList<>(potion.getEffects());
        try {
            if (potion.getItem().getItemMeta() instanceof org.bukkit.inventory.meta.PotionMeta meta) {
                effects.addAll(meta.getCustomEffects());
                if (meta.getBasePotionType() != null) {
                    effects.addAll(meta.getBasePotionType().getPotionEffects());
                }
            }
        } catch (LinkageError | RuntimeException ignored) {
            // Before 1.20.5: the server's own list.
        }
        return effects;
    }

    private static List<PotionEffect> effects(AreaEffectCloud cloud) {
        List<PotionEffect> effects = new ArrayList<>(cloud.getCustomEffects());
        try {
            if (cloud.getBasePotionType() != null) {
                effects.addAll(cloud.getBasePotionType().getPotionEffects());
            }
        } catch (LinkageError | RuntimeException ignored) {
            // Before 1.20.5 only the custom effects are known.
        }
        return effects;
    }

    // ---- death and the kill rule --------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDeathFirst(PlayerDeathEvent event) {
        armorAtDeath.put(event.getEntity().getUniqueId(), wearsArmor(event.getEntity(), plugin.settings().armor()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Boolean armored = armorAtDeath.remove(victim.getUniqueId());
        // Paper lets plugins cancel a death; Spigot does not (so no isCancelled() to call there).
        if (((Object) event) instanceof Cancellable cancellable && cancellable.isCancelled()) {
            return; // Not dead after all.
        }
        plugin.endCombat(victim, false);
        Player killer = victim.getKiller();
        if (killer == null || killer.equals(victim)) {
            return;
        }
        if (armored == null) {
            armored = wearsArmor(victim, plugin.settings().armor());
        }
        if (armored) {
            // A fair fight is over: the kill does not keep the killer in combat.
            plugin.tracker().removeOpponent(killer.getUniqueId(), victim.getUniqueId());
        }
        // A naked player's killer stays in combat (the killing hit already started 60 seconds).
    }

    /** Whether the player wears armor in the helmet, chestplate, leggings or boots slot. */
    static boolean wearsArmor(Player player, Settings.ArmorRule rule) {
        EntityEquipment equipment = player.getEquipment();
        if (equipment == null) {
            return false;
        }
        for (ItemStack item : new ItemStack[] {equipment.getHelmet(), equipment.getChestplate(),
                equipment.getLeggings(), equipment.getBoots()}) {
            if (item == null || item.getType().isAir() || item.getAmount() <= 0) {
                continue;
            }
            if (rule == Settings.ArmorRule.ANY_ITEM || isArmorPiece(item.getType())) {
                return true;
            }
        }
        return false;
    }

    static boolean isArmorPiece(Material type) {
        String name = type.name();
        return name.endsWith("_HELMET") || name.endsWith("_CHESTPLATE") || name.endsWith("_LEGGINGS")
                || name.endsWith("_BOOTS");
    }

    // ---- logging out and back in ------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKick(PlayerKickEvent event) {
        kicked.add(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        boolean wasKicked = kicked.remove(player.getUniqueId());
        plugin.loggedOut(player, wasKicked);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        plugin.loggedIn(event.getPlayer());
    }

    void forgetOld(long now) {
        attackers.forgetOld(now);
        if (armorAtDeath.size() > 100) {
            armorAtDeath.keySet().removeIf(uuid -> Bukkit.getPlayer(uuid) == null);
        }
    }
}
