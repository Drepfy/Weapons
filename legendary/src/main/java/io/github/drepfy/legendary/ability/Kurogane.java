package io.github.drepfy.legendary.ability;

import io.github.drepfy.legendary.Ability;
import io.github.drepfy.legendary.LegendaryPlugin;
import io.github.drepfy.legendary.WeaponType;
import io.github.drepfy.legendary.config.AbilitySettings;
import io.github.drepfy.legendary.item.WeaponItems;
import io.github.drepfy.legendary.util.Compat;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Kurogane, the katana: precision, counters and bleeding.
 * <ul>
 *   <li><b>Crimson Flash</b>: an iaido dash. The player vanishes in a crimson streak and appears
 *   up to 8 blocks ahead; everyone they passed through is cut a moment later and bleeds. It has
 *   two charges: press again within a few seconds to flash a second time.</li>
 *   <li><b>Iaido</b>: a counter stance. The first attack that would hit the player is blocked,
 *   and they vanish and reappear behind the attacker with a deep cut that heals them. If nothing
 *   comes, the stance is released as a crimson crescent.</li>
 *   <li><b>Crimson Edge</b> (passive): every third hit in a row on the same target cuts deep:
 *   extra damage and bleeding.</li>
 * </ul>
 */
final class Kurogane implements Kit, Listener {

    private static final Color CRIMSON = Color.fromRGB(215, 25, 55);
    private static final Color DARK = Color.fromRGB(80, 0, 14);
    private static final Color STEEL = Color.fromRGB(240, 240, 248);

    private final LegendaryPlugin plugin;
    /** Weapon → its open Crimson Flash chain (charges left). */
    private final Map<UUID, Chain> chains = new HashMap<>();
    /** Player → their Iaido stance. */
    private final Map<UUID, Stance> stances = new HashMap<>();
    private final Combos combos = new Combos();
    /** Attacker → the target their Crimson Edge cut is landing on. */
    private final Map<UUID, UUID> cutting = new HashMap<>();
    /** Bleeding target → who cut it and how long. */
    private final Map<UUID, Bleed> bleeds = new HashMap<>();

    Kurogane(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    private static final class Chain {
        final Player player;
        int left;
        long until;

        Chain(Player player, int left, long until) {
            this.player = player;
            this.left = left;
            this.until = until;
        }
    }

    private static final class Stance {
        final Player player;
        final long until;

        Stance(Player player, long until) {
            this.player = player;
            this.until = until;
        }
    }

    private static final class Bleed {
        final UUID attacker;
        final LivingEntity target;
        final double damage;
        final long until;
        long next;

        Bleed(UUID attacker, LivingEntity target, double damage, long until, long next) {
            this.attacker = attacker;
            this.target = target;
            this.damage = damage;
            this.until = until;
            this.next = next;
        }
    }

    @Override
    public WeaponType type() {
        return WeaponType.KUROGANE;
    }

    @Override
    public Result use(Player player, WeaponItems.Tag weapon, Ability ability) {
        if (ability == Ability.IAIDO) {
            return stance(player);
        }
        if (!flash(player)) {
            return Result.FAILED; // A wall right in front, or a refused teleport: nothing spent.
        }
        AbilitySettings settings = plugin.settings().ability(Ability.CRIMSON_FLASH);
        int charges = settings.whole("charges");
        if (charges <= 1) {
            return Result.FIRED;
        }
        chains.put(weapon.id(), new Chain(player, charges - 1, plugin.tick() + settings.ticks("recast-window")));
        return Result.HANDLED; // The cooldown starts once the charges are used or the window closes.
    }

    @Override
    public boolean recast(Player player, WeaponItems.Tag weapon, Ability ability) {
        Chain chain = ability == Ability.CRIMSON_FLASH ? chains.get(weapon.id()) : null;
        if (chain == null || !chain.player.equals(player)) {
            return false;
        }
        if (flash(player)) {
            chain.left--;
            chain.until = plugin.tick() + plugin.settings().ability(Ability.CRIMSON_FLASH).ticks("recast-window");
            if (chain.left <= 0) {
                chains.remove(weapon.id());
                plugin.abilities().startCooldown(weapon.id(), Ability.CRIMSON_FLASH);
            }
        }
        return true;
    }

    @Override
    public long active(Player player, WeaponItems.Tag weapon, Ability ability, long now) {
        if (ability == Ability.CRIMSON_FLASH) {
            Chain chain = chains.get(weapon.id());
            return chain == null ? 0 : Math.max(0, chain.until - now);
        }
        Stance stance = stances.get(player.getUniqueId());
        return stance == null ? 0 : Math.max(0, stance.until - now);
    }

    @Override
    public long activeLength(Ability ability) {
        AbilitySettings settings = plugin.settings().ability(ability);
        return ability == Ability.CRIMSON_FLASH ? settings.ticks("recast-window") : settings.ticks("stance");
    }

    /** Whether a target is bleeding (for tests). */
    boolean bleeding(LivingEntity target) {
        return bleeds.containsKey(target.getUniqueId());
    }

    @Override
    public void forget(Player player) {
        for (Iterator<Map.Entry<UUID, Chain>> it = chains.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Chain> chain = it.next();
            if (chain.getValue().player.equals(player)) {
                it.remove();
                plugin.abilities().startCooldown(chain.getKey(), Ability.CRIMSON_FLASH);
            }
        }
        stances.remove(player.getUniqueId());
        combos.forget(player.getUniqueId());
        cutting.remove(player.getUniqueId());
        bleeds.remove(player.getUniqueId());
    }

    @Override
    public void tick(long now) {
        for (Iterator<Map.Entry<UUID, Chain>> it = chains.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Chain> chain = it.next();
            if (now >= chain.getValue().until) {
                it.remove();
                plugin.abilities().startCooldown(chain.getKey(), Ability.CRIMSON_FLASH);
            }
        }
        for (Iterator<Stance> it = stances.values().iterator(); it.hasNext(); ) {
            Stance stance = it.next();
            if (!stance.player.isOnline() || stance.player.isDead()) {
                it.remove();
            } else if (now >= stance.until) {
                it.remove();
                release(stance);
            } else if (now % 2 == 0) {
                stanceFx(stance.player, now);
            }
        }
        for (Iterator<Bleed> it = bleeds.values().iterator(); it.hasNext(); ) {
            Bleed bleed = it.next();
            if (now >= bleed.until || !bleed.target.isValid() || bleed.target.isDead()) {
                it.remove();
                continue;
            }
            Fx.View view = plugin.fx().view(bleed.target.getLocation());
            if (now % 3 == 0) {
                view.fade(Geo.middle(bleed.target), CRIMSON, DARK, 0.9f, 2, 0.25);
            }
            if (now < bleed.next) {
                continue;
            }
            bleed.next = now + 20;
            Player attacker = Bukkit.getPlayer(bleed.attacker);
            if (attacker == null || !attacker.isOnline()) {
                it.remove();
                continue;
            }
            if (plugin.hits().hurt(attacker, bleed.target, bleed.damage)) {
                view.particle(Fx.BLOCK, Geo.middle(bleed.target), 10, 0.2, 0.3, 0.2, 0.1,
                        Material.REDSTONE_BLOCK.createBlockData());
                plugin.fx().sound(bleed.target.getLocation(), "bleed");
            }
        }
        if (now % 200 == 0) {
            combos.prune(now, 400);
        }
    }

    private void bleed(Player attacker, LivingEntity target, double damage, int ticks) {
        if (damage > 0 && ticks > 0) {
            long now = plugin.tick();
            bleeds.put(target.getUniqueId(), new Bleed(attacker.getUniqueId(), target, damage, now + ticks + 1, now + 20));
        }
    }

    // ---- Crimson Flash -----------------------------------------------------------------------------------

    private boolean flash(Player player) {
        AbilitySettings settings = plugin.settings().ability(Ability.CRIMSON_FLASH);
        Location start = player.getLocation();
        Vector direction = Geo.flat(start);
        Location end = Geo.dash(start, direction, settings.num("range"));
        double length = Geo.flatDistance(start, end);
        if (length < 1.0) {
            return false;
        }
        // Who stands in the way, before anyone moves.
        double reach = settings.num("width") / 2.0;
        List<LivingEntity> path = plugin.hits().along(player, start.clone().add(0, 0.9, 0),
                end.clone().add(0, 0.9, 0), reach);
        Location destination = end.clone();
        destination.setYaw(start.getYaw());
        destination.setPitch(start.getPitch());
        if (!player.teleport(destination, PlayerTeleportEvent.TeleportCause.PLUGIN)) {
            return false; // A safe zone or region refused it.
        }
        player.setFallDistance(0f);
        plugin.fx().sound(start, "crimson-flash");
        plugin.fx().sound(destination, "crimson-flash-end");
        drawFlash(start, destination, direction, length);

        double damage = settings.num("damage");
        double bleedDamage = settings.num("bleed-damage");
        int bleedTicks = settings.ticks("bleed-duration");
        Runnable cut = () -> {
            for (LivingEntity target : path) {
                if (!target.isValid() || target.isDead() || !player.isOnline()) {
                    continue;
                }
                if (plugin.hits().hurt(player, target, damage)) {
                    cutFx(target, direction, 35);
                    plugin.fx().sound(target.getLocation(), "kurogane-hit");
                    bleed(player, target, bleedDamage, bleedTicks);
                }
            }
            if (!path.isEmpty()) {
                plugin.fx().sound(destination, "crimson-cut");
            }
        };
        int delay = settings.ticks("delay");
        if (delay <= 0) {
            cut.run();
        } else {
            plugin.visuals().later(delay, cut);
        }
        return true;
    }

    /** A crimson streak along the path with a white-hot core, sweep sparks, and a slash at the end. */
    private void drawFlash(Location start, Location end, Vector direction, double length) {
        Location from = start.clone().add(0, 1.0, 0);
        Location to = end.clone().add(0, 1.0, 0);
        Fx.View view = plugin.fx().view(from.clone().add(to).multiply(0.5));
        view.fade(Shapes.line(from, to, 0.16), CRIMSON, DARK, 1.5f);
        view.dust(Shapes.line(from, to, 0.3), STEEL, 0.6f);
        for (double d = 0.6; d < length; d += 1.6) {
            view.particle(Fx.SWEEP, from.clone().add(direction.clone().multiply(d)), 1, 0, 0, 0, 0);
        }
        view.particle(Fx.CRIT, to, 18, 0.3, 0.5, 0.3, 0.35);
        slash(to.clone().add(direction.clone().multiply(0.6)), direction, 2.0, -18, 3);
    }

    /**
     * A sword stroke drawn as it is swung: a crescent of crimson dust that sweeps across over a
     * few ticks, white at its edge, with sweep sparks.
     */
    private void slash(Location center, Vector facing, double radius, double tilt, int ticks) {
        List<Location> arc = Shapes.arc(center, facing, radius, -80, 80, tilt, 34);
        List<Location> inner = Shapes.arc(center, facing, radius * 0.86, -70, 70, tilt, 26);
        Fx.View view = plugin.fx().view(center);
        int parts = Math.max(1, ticks);
        for (int k = 0; k < parts; k++) {
            List<Location> outerPart = arc.subList(arc.size() * k / parts, arc.size() * (k + 1) / parts);
            List<Location> innerPart = inner.subList(inner.size() * k / parts, inner.size() * (k + 1) / parts);
            Runnable draw = () -> {
                view.fade(outerPart, STEEL, CRIMSON, 1.1f);
                view.fade(innerPart, CRIMSON, DARK, 1.4f);
                if (!outerPart.isEmpty()) {
                    view.particle(Fx.SWEEP, outerPart.get(outerPart.size() / 2), 1, 0, 0, 0, 0);
                }
            };
            if (k == 0) {
                draw.run();
            } else {
                plugin.visuals().later(k, draw);
            }
        }
    }

    /** The cut opening on a target: a crimson X across them, red chips and sparks. */
    private void cutFx(LivingEntity target, Vector facing, int tilt) {
        Location at = Geo.middle(target);
        Vector right = Geo.right(Shapes.flat(facing));
        Fx.View view = plugin.fx().view(at);
        for (double angle : new double[]{tilt + 45.0, tilt - 45.0}) {
            double a = Math.toRadians(angle);
            Vector half = right.clone().multiply(Math.cos(a) * 0.75).add(new Vector(0, Math.sin(a) * 0.75, 0));
            view.fade(Shapes.line(at.clone().subtract(half), at.clone().add(half), 0.1), STEEL, CRIMSON, 0.9f);
        }
        view.particle(Fx.BLOCK, at, 14, 0.2, 0.3, 0.2, 0.15, Material.REDSTONE_BLOCK.createBlockData());
        view.particle(Fx.CRIT, at, 10, 0.3, 0.4, 0.3, 0.3);
    }

    /** Iaido's stance: a slowly turning crimson ring at the feet, and a few motes rising. */
    private void stanceFx(Player player, long now) {
        Location feet = player.getLocation().add(0, 0.1, 0);
        Fx.View view = plugin.fx().view(feet);
        view.fade(Shapes.ring(feet, 1.25, 26, now * 0.12), CRIMSON, DARK, 1.0f);
        view.dust(Shapes.ring(feet, 0.95, 4, -now * 0.2), STEEL, 0.7f);
        view.fade(feet.clone().add(0, 0.9, 0), CRIMSON, DARK, 0.8f, 2, 0.5);
    }

    // ---- Iaido -------------------------------------------------------------------------------------------

    private Result stance(Player player) {
        AbilitySettings settings = plugin.settings().ability(Ability.IAIDO);
        int ticks = settings.ticks("stance");
        stances.put(player.getUniqueId(), new Stance(player, plugin.tick() + ticks));
        Location chest = player.getLocation().add(0, 1.1, 0);
        Fx.View view = plugin.fx().view(chest);
        view.fade(Shapes.ring(player.getLocation().add(0, 0.1, 0), 1.6, 34, 0), STEEL, CRIMSON, 1.2f);
        view.particle(Fx.MAGIC_CRIT, chest, 12, 0.3, 0.4, 0.3, 0.15);
        stanceFx(player, plugin.tick());
        Hits.effect(player, "slowness", 3, ticks);
        plugin.fx().sound(player.getLocation(), "iaido");
        return Result.FIRED;
    }

    /** The first attack on a player in stance is blocked, and answered. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onAttacked(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player player) || plugin.hits().probing()) {
            return;
        }
        Stance stance = stances.get(player.getUniqueId());
        if (stance == null || plugin.tick() >= stance.until) {
            return;
        }
        LivingEntity attacker = source(event.getDamager());
        if (attacker == null || attacker.equals(player)) {
            return;
        }
        event.setCancelled(true);
        stances.remove(player.getUniqueId());
        org.bukkit.potion.PotionEffectType slowness = Compat.effect("slowness");
        if (slowness != null) {
            player.removePotionEffect(slowness);
        }
        plugin.fx().sound(player.getLocation(), "iaido-parry");
        Fx.View view = plugin.fx().view(player.getLocation());
        Location chest = player.getLocation().add(0, 1.2, 0);
        view.particle(Fx.CRIT, chest, 25, 0.4, 0.5, 0.4, 0.45);
        view.particle(Fx.MAGIC_CRIT, chest, 15, 0.3, 0.4, 0.3, 0.3);
        view.dust(Shapes.standingRing(chest, Geo.flat(player.getLocation()), 0.9, 22, 0), STEEL, 1.0f);
        AbilitySettings settings = plugin.settings().ability(Ability.IAIDO);
        if (attacker.getWorld() != player.getWorld()
                || attacker.getLocation().distance(player.getLocation()) > settings.num("reach")
                || !plugin.hits().allowed(player, attacker)) {
            return; // Blocked, but no counter.
        }
        // Vanish and reappear behind them.
        Location from = player.getLocation();
        Location behind = behind(attacker);
        if (behind != null && player.teleport(behind, PlayerTeleportEvent.TeleportCause.PLUGIN)) {
            player.setFallDistance(0f);
            Location a = from.clone().add(0, 1.0, 0);
            Location b = behind.clone().add(0, 1.0, 0);
            view.fade(Shapes.line(a, b, 0.16), CRIMSON, DARK, 1.4f);
            view.dust(Shapes.line(a, b, 0.3), STEEL, 0.6f);
        }
        if (plugin.hits().hurt(player, attacker, settings.num("damage"))) {
            AbilitySettings edge = plugin.settings().ability(Ability.CRIMSON_EDGE);
            bleed(player, attacker, edge.num("bleed-damage"), edge.ticks("bleed-duration"));
            Compat.heal(player, settings.num("heal"));
            Location at = Geo.middle(attacker);
            Vector facing = Geo.flat(player.getLocation());
            slash(at.clone().subtract(facing.clone().multiply(0.9)), facing, 1.7, 65, 3);
            cutFx(attacker, facing, -40);
            plugin.fx().sound(at, "iaido-counter");
        }
    }

    /** Who is behind an attack: the attacker, or whoever shot the arrow. */
    private static LivingEntity source(Entity damager) {
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof LivingEntity shooter) {
            return shooter;
        }
        return damager instanceof LivingEntity living ? living : null;
    }

    /** Standing room just behind an entity, facing its back; null when there is none. */
    private static Location behind(LivingEntity target) {
        Location at = target.getLocation();
        Vector facing = Geo.flat(at);
        for (double back : new double[]{1.3, 0.9}) {
            Location spot = at.clone().subtract(facing.clone().multiply(back));
            if (Geo.standable(spot)) {
                spot.setYaw(at.getYaw());
                spot.setPitch(0f);
                return spot;
            }
        }
        return null;
    }

    /** Nothing came: the stance is released as a crimson crescent. */
    private void release(Stance stance) {
        Player player = stance.player;
        AbilitySettings settings = plugin.settings().ability(Ability.IAIDO);
        Location start = player.getLocation().add(0, 0.9, 0);
        Vector direction = Geo.flat(player.getLocation());
        Location end = start.clone().add(direction.clone().multiply(settings.num("slash-range")));
        // The crescent flies out and fades: drawn a little further on each tick.
        Fx.View view = plugin.fx().view(start);
        double range = settings.num("slash-range");
        int steps = Math.max(2, (int) Math.round(range / 1.2));
        for (int k = 0; k < steps; k++) {
            Location center = start.clone().add(direction.clone().multiply(0.4 + range * k / steps)).add(0, 0.2, 0);
            float size = 1.4f - 0.6f * k / steps;
            plugin.visuals().later(k, () -> {
                List<Location> arc = Shapes.arc(center, direction, 1.5, -75, 75, 10, 28);
                view.fade(arc, STEEL, CRIMSON, size);
                view.particle(Fx.SWEEP, center.clone().add(direction.clone().multiply(1.5)), 1, 0, 0, 0, 0);
            });
        }
        plugin.fx().sound(player.getLocation(), "crimson-cut");
        if (settings.num("slash-damage") <= 0) {
            return;
        }
        for (LivingEntity target : plugin.hits().along(player, start, end, 1.4)) {
            if (plugin.hits().hurt(player, target, settings.num("slash-damage"))) {
                cutFx(target, direction, 20);
                plugin.fx().sound(target.getLocation(), "kurogane-hit");
            }
        }
    }

    // ---- Crimson Edge (passive) ------------------------------------------------------------------------------

    @Override
    public void melee(EntityDamageByEntityEvent event, Player attacker, LivingEntity target, WeaponItems.Tag weapon) {
        AbilitySettings settings = plugin.settings().ability(Ability.CRIMSON_EDGE);
        if (combos.hit(attacker.getUniqueId(), target.getUniqueId(), plugin.tick(), settings.whole("hits"),
                settings.ticks("window"), settings.ticks("min-hit-gap"))) {
            event.setDamage(event.getDamage() + settings.num("bonus-damage"));
            cutting.put(attacker.getUniqueId(), target.getUniqueId());
        }
    }

    @Override
    public void landed(Player attacker, LivingEntity target, WeaponItems.Tag weapon) {
        UUID cut = cutting.remove(attacker.getUniqueId());
        if (cut == null || !cut.equals(target.getUniqueId())) {
            return;
        }
        AbilitySettings settings = plugin.settings().ability(Ability.CRIMSON_EDGE);
        bleed(attacker, target, settings.num("bleed-damage"), settings.ticks("bleed-duration"));
        cutFx(target, Geo.flat(attacker.getLocation()), plugin.tick() % 2 == 0 ? 30 : -30);
        plugin.fx().sound(target.getLocation(), "crimson-edge");
    }
}
