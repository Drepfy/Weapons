package io.github.drepfy.legendary.ability;

import io.github.drepfy.legendary.config.Settings;
import io.github.drepfy.legendary.util.Compat;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The extra damage of the abilities and passives, played by the server's rules.
 *
 * <p>The weapons only ever affect players: players in creative or spectator mode, players the
 * attacker cannot see and everyone in a world with PvP off are left alone, and before a player
 * is trapped or slowed, protection plugins (no-PvP regions, claims, spawn) are asked as for an
 * attack. Extra damage always follows a sword or axe hit that landed (or a bleed that one
 * started), so kill credit, combat tags and Lifesteal hearts go to the attacker as usual.
 *
 * <p>With true-damage on (the default) the extra damage goes straight through armour and the
 * Protection enchantment: 1 heart takes 1 heart whatever the target wears (Resistance, absorption
 * hearts and totems still count). It never knocks the target back and never gives them extra
 * time without damage, so it does not get in the way of the next sword hit.
 */
public final class Hits implements Listener {

    /** Deals the damage; replaced in tests, where the simulated server does not fire damage events. */
    public interface Damager {
        void damage(LivingEntity target, double amount, Player attacker);
    }

    private final Supplier<Settings> settings;
    private Damager damager;
    private Hit pending;
    private boolean probing;

    public Hits(Supplier<Settings> settings) {
        this.settings = settings;
        this.damager = (target, amount, attacker) -> deal(target, amount, settings.get().trueDamage());
    }

    public void setDamager(Damager damager) {
        this.damager = damager;
    }

    private static final class Hit {
        final Entity target;
        boolean landed;

        Hit(Entity target) {
            this.target = target;
        }
    }

    /**
     * True while protection plugins are only being asked whether an attack would be allowed: the
     * attack event then is not a real attack.
     */
    public boolean probing() {
        return probing;
    }

    /** True while extra damage is being dealt (so it is not mistaken for a sword hit). */
    public boolean inAbility() {
        return pending != null;
    }

    /** Whether the weapons may affect this entity at all: other players only, never in creative or spectator. */
    public boolean canTarget(Player attacker, Entity entity) {
        if (!(entity instanceof Player player) || entity.equals(attacker) || player.isDead() || !player.isOnline()) {
            return false;
        }
        GameMode mode = player.getGameMode();
        return mode != GameMode.CREATIVE && mode != GameMode.SPECTATOR && attacker.canSee(player)
                && attacker.getWorld().equals(player.getWorld()) && attacker.getWorld().getPVP();
    }

    /**
     * Extra damage (health points) for the attacker's target. The target keeps the time without
     * damage it had and is not knocked back.
     *
     * @return whether it was dealt (no plugin cancelled it)
     */
    public boolean hurt(Player attacker, LivingEntity target, double amount) {
        if (amount <= 0 || !canTarget(attacker, target)) {
            return false;
        }
        int invulnerable = target.getNoDamageTicks();
        double lastDamage = lastDamage(target);
        target.setNoDamageTicks(0);
        Hit outer = pending;
        Hit hit = new Hit(target);
        pending = hit;
        try {
            damager.damage(target, amount, attacker);
        } finally {
            pending = outer;
        }
        if (target.isValid() && !target.isDead()) {
            // As if this damage had not happened as far as the next sword hit is concerned.
            target.setNoDamageTicks(invulnerable);
            if (!Double.isNaN(lastDamage)) {
                target.setLastDamage(lastDamage);
            }
        }
        return hit.landed;
    }

    /** The damage of the target's last hit (what a hit in its time without damage must beat), or NaN if unknown. */
    private static double lastDamage(LivingEntity target) {
        try {
            return target.getLastDamage();
        } catch (RuntimeException | LinkageError e) {
            return Double.NaN; // The simulated server in the tests does not keep it.
        }
    }

    /**
     * Damage without a position, so nothing is knocked back. True damage is magic damage (it goes
     * through armour), raised to make up for the Protection enchantment.
     */
    private static void deal(LivingEntity target, double amount, boolean trueDamage) {
        DamageSource source;
        try {
            source = DamageSource.builder(trueDamage ? DamageType.MAGIC : DamageType.GENERIC).build();
        } catch (RuntimeException | LinkageError e) {
            target.damage(amount); // Before 1.20.5: no damage sources.
            return;
        }
        target.damage(trueDamage ? throughProtection(target, amount) : amount, source);
    }

    /**
     * How much to deal so that {@code amount} is left after the Protection enchantment (each level
     * on each piece takes 4% off magic damage, at most 80%).
     */
    public static double throughProtection(LivingEntity target, double amount) {
        int levels = 0;
        try {
            EntityEquipment equipment = target.getEquipment();
            if (equipment != null) {
                for (ItemStack piece : equipment.getArmorContents()) {
                    if (piece != null) {
                        levels += piece.getEnchantmentLevel(Enchantment.PROTECTION);
                    }
                }
            }
        } catch (RuntimeException | LinkageError e) {
            return amount;
        }
        return amount / (1.0 - Math.min(20, levels) / 25.0);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDamage(EntityDamageEvent event) {
        Hit hit = pending;
        if (hit != null && event.getEntity().equals(hit.target)) {
            hit.landed = !event.isCancelled();
        }
    }

    /**
     * Whether the attacker may affect the target at all here (a trap, a slow), asked without
     * hurting it: protection plugins see an attack and may refuse it.
     */
    public boolean allowed(Player attacker, LivingEntity target) {
        if (!canTarget(attacker, target)) {
            return false;
        }
        Hit outer = pending;
        boolean outerProbing = probing;
        pending = new Hit(target);
        probing = true;
        try {
            EntityDamageByEntityEvent probe = probe(attacker, target);
            Bukkit.getPluginManager().callEvent(probe);
            return !probe.isCancelled();
        } catch (RuntimeException | LinkageError e) {
            return false; // Could not ask: better not to trap anyone in a protected area.
        } finally {
            pending = outer;
            probing = outerProbing;
        }
    }

    /**
     * An attack event that is only asked about, never applied. Built with the newest constructor
     * the server has: Paper marked the shorter ones for removal, so newer servers may not have
     * them.
     */
    private static EntityDamageByEntityEvent probe(Player attacker, LivingEntity target) {
        DamageSource source;
        try {
            source = DamageSource.builder(DamageType.GENERIC).withCausingEntity(attacker).withDirectEntity(attacker).build();
        } catch (RuntimeException | LinkageError e) {
            return legacyProbe(attacker, target); // Before 1.20.5: no damage sources.
        }
        try {
            Map<EntityDamageEvent.DamageModifier, Double> modifiers = new EnumMap<>(EntityDamageEvent.DamageModifier.class);
            modifiers.put(EntityDamageEvent.DamageModifier.BASE, 0.0);
            Map<EntityDamageEvent.DamageModifier, com.google.common.base.Function<? super Double, Double>> functions =
                    new EnumMap<>(EntityDamageEvent.DamageModifier.class);
            functions.put(EntityDamageEvent.DamageModifier.BASE, com.google.common.base.Functions.constant(-0.0));
            return new EntityDamageByEntityEvent(attacker, target, EntityDamageEvent.DamageCause.CUSTOM, source,
                    modifiers, functions, false);
        } catch (RuntimeException | LinkageError e) {
            return sourceProbe(attacker, target, source); // 1.20.5 to 1.21.3.
        }
    }

    @SuppressWarnings({"deprecation", "removal"})
    private static EntityDamageByEntityEvent sourceProbe(Player attacker, LivingEntity target, DamageSource source) {
        return new EntityDamageByEntityEvent(attacker, target, EntityDamageEvent.DamageCause.CUSTOM, source, 0.0);
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
}
