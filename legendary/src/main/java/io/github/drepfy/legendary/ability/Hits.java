package io.github.drepfy.legendary.ability;

import io.github.drepfy.legendary.config.Settings;
import io.github.drepfy.legendary.util.Compat;
import org.bukkit.GameMode;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.ComplexEntityPart;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.util.Vector;

import java.util.function.Supplier;

/**
 * Ability damage that plays by the server's rules.
 *
 * <p>Every hit is dealt in the attacker's name, like a sword hit, so protection plugins
 * (no-PvP regions, claims, spawn), PvP being off, combat tagging, kill credit and death
 * messages all work as usual, and armour reduces it. Knockback and effects are only applied
 * when the hit was allowed: a player standing in a protected area is never pushed, slowed
 * or pulled.
 */
public final class Hits implements Listener {

    /** Deals the damage; replaced in tests, where the simulated server does not fire damage events. */
    public interface Damager {
        void damage(LivingEntity target, double amount, Player attacker);
    }

    private final Supplier<Settings> settings;
    private Damager damager = (target, amount, attacker) -> target.damage(amount, attacker);
    private Hit pending;

    public Hits(Supplier<Settings> settings) {
        this.settings = settings;
    }

    public void setDamager(Damager damager) {
        this.damager = damager;
    }

    private static final class Hit {
        final Player attacker;
        final Entity target;
        boolean landed;

        Hit(Player attacker, Entity target) {
            this.attacker = attacker;
            this.target = target;
        }
    }

    /** True while an ability hit is being dealt (so it is not mistaken for a sword hit). */
    public boolean inAbility() {
        return pending != null;
    }

    /** Whether abilities may hit this entity at all. */
    public boolean canTarget(Player attacker, Entity entity) {
        if (!(entity instanceof LivingEntity living) || entity.equals(attacker) || living.isDead()
                || entity instanceof ArmorStand || entity instanceof ComplexEntityPart) {
            return false;
        }
        if (entity instanceof Player player) {
            GameMode mode = player.getGameMode();
            return mode != GameMode.CREATIVE && mode != GameMode.SPECTATOR && attacker.canSee(player)
                    && attacker.getWorld().getPVP();
        }
        return switch (settings.get().hitMobs()) {
            case NONE -> false;
            case HOSTILE -> entity instanceof Enemy;
            case ALL -> !(entity instanceof Tameable pet && pet.isTamed() && attacker.equals(pet.getOwner()));
        };
    }

    /**
     * Hurts the target in the attacker's name.
     *
     * @return whether the hit was allowed (no plugin cancelled it); only then may it be pushed,
     *         slowed or pulled
     */
    public boolean hurt(Player attacker, LivingEntity target, double amount) {
        if (!canTarget(attacker, target)) {
            return false;
        }
        int invulnerable = target.getNoDamageTicks();
        // A sword hit just before would otherwise swallow the ability. Each ability hits a
        // target once per use, so this cannot be used to stack hits.
        target.setNoDamageTicks(0);
        Hit outer = pending;
        Hit hit = new Hit(attacker, target);
        pending = hit;
        try {
            damager.damage(target, amount, attacker);
        } finally {
            pending = outer;
        }
        if (!hit.landed && target.isValid() && !target.isDead()) {
            target.setNoDamageTicks(invulnerable);
        }
        return hit.landed;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDamage(EntityDamageByEntityEvent event) {
        Hit hit = pending;
        if (hit != null && event.getEntity().equals(hit.target) && event.getDamager().equals(hit.attacker)) {
            hit.landed = !event.isCancelled();
        }
    }

    /**
     * Pushes along a flat direction and lifts. Knockback resistance (netherite armour) softens
     * the push like it does for a sword hit, but not the lift.
     */
    public static void knock(LivingEntity target, Vector direction, double strength, double lift) {
        Vector flat = direction.clone().setY(0);
        if (flat.lengthSquared() > 1.0E-6) {
            flat.normalize().multiply(strength * (1.0 - Compat.knockbackResistance(target)));
        }
        target.setVelocity(flat.setY(lift));
    }
}
