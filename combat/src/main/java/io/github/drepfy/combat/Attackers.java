package io.github.drepfy.combat;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.AnimalTamer;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LightningStrike;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Tameable;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.projectiles.ProjectileSource;

import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Works out which player is behind a hit: a sword, a bow, a trident, a potion, TNT, an end
 * crystal or a respawn anchor/bed explosion they set off, or their tamed wolf.
 */
final class Attackers {

    /** Effects that only hurt (vanilla names, the same on every version). */
    private static final Set<String> HARMFUL = Set.of("poison", "instant_damage", "weakness", "slowness",
            "mining_fatigue", "nausea", "blindness", "hunger", "wither", "levitation", "unluck", "bad_omen",
            "darkness", "infested", "oozing", "weaving", "wind_charged", "glowing");
    /** How long an end crystal or an anchor/bed someone used is linked to them. */
    private static final long TRIGGER_MEMORY_MS = 5_000L;
    private static final double EXPLOSION_RANGE = 12.0;

    private record Trigger(UUID player, long at, Location where) {
    }

    private final Map<UUID, Trigger> crystals = new HashMap<>();
    private final Map<Long, Trigger> blocks = new HashMap<>();
    private long nextBlockId;

    /** The player behind a hit, or {@code null} (mobs, the environment...). */
    Player of(EntityDamageByEntityEvent event, long now) {
        Player fromSource = causingPlayer(event);
        if (fromSource != null) {
            return fromSource;
        }
        return of(event.getDamager(), now);
    }

    /** The player behind an entity that did damage, or {@code null}. */
    Player of(Entity damager, long now) {
        if (damager instanceof Player player) {
            return player;
        }
        if (damager instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            return shooter instanceof Player player ? player : null;
        }
        if (damager instanceof TNTPrimed tnt) {
            return tnt.getSource() instanceof Player player ? player : null;
        }
        if (damager instanceof AreaEffectCloud cloud) {
            return cloud.getSource() instanceof Player player ? player : null;
        }
        if (damager instanceof Tameable pet && pet.isTamed()) {
            AnimalTamer owner = pet.getOwner();
            return owner instanceof Player player && player.isOnline() ? player : null;
        }
        if (damager instanceof EnderCrystal crystal) {
            Trigger trigger = crystals.get(crystal.getUniqueId());
            return trigger != null && now - trigger.at() <= TRIGGER_MEMORY_MS ? online(trigger.player()) : null;
        }
        if (damager instanceof LightningStrike lightning) {
            try {
                Entity causing = lightning.getCausingEntity();
                return causing instanceof Player player ? player : null;
            } catch (LinkageError e) {
                return null;
            }
        }
        return null;
    }

    /** 1.20.4+: the game itself says who caused the damage (e.g. who hit the end crystal). */
    private static Player causingPlayer(EntityDamageByEntityEvent event) {
        try {
            Entity causing = event.getDamageSource().getCausingEntity();
            return causing instanceof Player player ? player : null;
        } catch (LinkageError | RuntimeException e) {
            return null;
        }
    }

    /** Someone hit an end crystal: its explosion is theirs. */
    void crystalHit(EnderCrystal crystal, Player player, long now) {
        crystals.put(crystal.getUniqueId(), new Trigger(player.getUniqueId(), now, crystal.getLocation()));
    }

    /** Someone used a respawn anchor or a bed: an explosion there is theirs. */
    void blockUsed(Block block, Player player, long now) {
        Material type = block.getType();
        if (type == Material.RESPAWN_ANCHOR || type.name().endsWith("_BED")) {
            blocks.put(nextBlockId++, new Trigger(player.getUniqueId(), now, block.getLocation().add(0.5, 0.5, 0.5)));
        }
    }

    /** The player who set off a block explosion near {@code where}, or {@code null}. */
    Player blockExplosion(Location where, long now) {
        Trigger best = null;
        for (Trigger trigger : blocks.values()) {
            if (now - trigger.at() <= TRIGGER_MEMORY_MS && trigger.where().getWorld() != null
                    && trigger.where().getWorld().equals(where.getWorld())
                    && trigger.where().distanceSquared(where) <= EXPLOSION_RANGE * EXPLOSION_RANGE
                    && (best == null || trigger.at() > best.at())) {
                best = trigger;
            }
        }
        return best == null ? null : online(best.player());
    }

    void forgetOld(long now) {
        crystals.values().removeIf(trigger -> now - trigger.at() > TRIGGER_MEMORY_MS);
        for (Iterator<Trigger> it = blocks.values().iterator(); it.hasNext(); ) {
            if (now - it.next().at() > TRIGGER_MEMORY_MS) {
                it.remove();
            }
        }
    }

    /** Whether any of the effects only hurts. */
    static boolean harmful(Collection<PotionEffect> effects) {
        for (PotionEffect effect : effects) {
            try {
                if (HARMFUL.contains(effect.getType().getKey().getKey().toLowerCase(Locale.ROOT))) {
                    return true;
                }
            } catch (RuntimeException | LinkageError ignored) {
                // Unknown effect: not counted.
            }
        }
        return false;
    }

    private static Player online(UUID uuid) {
        return org.bukkit.Bukkit.getPlayer(uuid);
    }
}
