package io.github.drepfy.legendary.ability;

import io.github.drepfy.legendary.config.Settings;
import io.github.drepfy.legendary.util.Compat;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.Location;
import org.bukkit.World;
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
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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
    private boolean probing;

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

    /**
     * True while protection plugins are only being asked whether an attack would be allowed: the
     * attack event then is not a real attack, and counters and dodges must ignore it.
     */
    public boolean probing() {
        return probing;
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

    /** Everything abilities may hit within a radius of a point (a sphere around the body's middle). */
    public List<LivingEntity> around(Player attacker, Location center, double radius) {
        List<LivingEntity> found = new ArrayList<>();
        World world = center.getWorld();
        if (world == null) {
            return found;
        }
        for (Entity entity : world.getNearbyEntities(center, radius + 1, radius + 2, radius + 1)) {
            if (entity instanceof LivingEntity living && canTarget(attacker, entity)) {
                Location middle = entity.getLocation().add(0, entity.getHeight() / 2.0, 0);
                double reach = radius + entity.getWidth() / 2.0;
                if (middle.distanceSquared(center) <= reach * reach) {
                    found.add(living);
                }
            }
        }
        found.sort(Comparator.comparingDouble(e -> e.getLocation().distanceSquared(center)));
        return found;
    }

    /** Everything abilities may hit along a line (within {@code reach} of it), nearest the start first. */
    public List<LivingEntity> along(Player attacker, Location from, Location to, double reach) {
        List<LivingEntity> found = new ArrayList<>();
        World world = from.getWorld();
        if (world == null) {
            return found;
        }
        BoundingBox box = BoundingBox.of(from, to).expand(reach + 1.0, 2.0, reach + 1.0);
        Vector a = from.toVector();
        Vector ab = to.toVector().subtract(a);
        double lengthSquared = Math.max(1.0E-6, ab.lengthSquared());
        for (Entity entity : world.getNearbyEntities(box)) {
            if (!(entity instanceof LivingEntity living) || !canTarget(attacker, entity)) {
                continue;
            }
            Vector middle = entity.getLocation().toVector().add(new Vector(0, entity.getHeight() / 2.0, 0));
            double t = Math.max(0.0, Math.min(1.0, middle.clone().subtract(a).dot(ab) / lengthSquared));
            Vector closest = a.clone().add(ab.clone().multiply(t));
            double dx = middle.getX() - closest.getX();
            double dz = middle.getZ() - closest.getZ();
            double flat = Math.sqrt(dx * dx + dz * dz);
            if (flat <= reach + entity.getWidth() / 2.0 && Math.abs(middle.getY() - closest.getY()) <= 1.6) {
                found.add(living);
            }
        }
        found.sort(Comparator.comparingDouble(e -> e.getLocation().distanceSquared(from)));
        return found;
    }

    /**
     * Whether the attacker may affect the target at all here (pulling it, swapping places), asked
     * without hurting it: protection plugins see an attack and may refuse it.
     */
    public boolean allowed(Player attacker, LivingEntity target) {
        if (!canTarget(attacker, target)) {
            return false;
        }
        Hit outer = pending;
        boolean outerProbing = probing;
        pending = new Hit(attacker, target);
        probing = true;
        try {
            EntityDamageByEntityEvent probe = probe(attacker, target);
            Bukkit.getPluginManager().callEvent(probe);
            return !probe.isCancelled();
        } catch (RuntimeException | LinkageError e) {
            return true;
        } finally {
            pending = outer;
            probing = outerProbing;
        }
    }

    /** An attack event that is only asked about, never applied. */
    private static EntityDamageByEntityEvent probe(Player attacker, LivingEntity target) {
        try {
            DamageSource source = DamageSource.builder(DamageType.GENERIC).withCausingEntity(attacker)
                    .withDirectEntity(attacker).build();
            return new EntityDamageByEntityEvent(attacker, target, EntityDamageEvent.DamageCause.CUSTOM, source, 0.0);
        } catch (RuntimeException | LinkageError e) {
            return legacyProbe(attacker, target); // Before 1.20.5: no damage sources.
        }
    }

    @SuppressWarnings({"deprecation", "removal"})
    private static EntityDamageByEntityEvent legacyProbe(Player attacker, LivingEntity target) {
        return new EntityDamageByEntityEvent(attacker, target, EntityDamageEvent.DamageCause.CUSTOM, 0.0);
    }

    /** A potion effect by its vanilla id, if the level is above 0. */
    public static void effect(LivingEntity target, String id, int level, int ticks) {
        if (level <= 0 || ticks <= 0) {
            return;
        }
        PotionEffectType type = Compat.effect(id);
        if (type != null) {
            target.addPotionEffect(new PotionEffect(type, ticks, level - 1, false, true, true));
        }
    }

    /** Stunned: too slow to walk anywhere for a moment (they can still turn and swing). */
    public static void stun(LivingEntity target, int ticks) {
        effect(target, "slowness", 7, ticks);
        if (target instanceof Player player) {
            player.setSprinting(false);
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
