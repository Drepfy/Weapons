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
        final Visuals.Effect ring;

        Stance(Player player, long until, Visuals.Effect ring) {
            this.player = player;
            this.until = until;
            this.ring = ring;
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
        Stance stance = stances.remove(player.getUniqueId());
        if (stance != null) {
            stance.ring.remove();
        }
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
                stance.ring.remove();
                it.remove();
            } else if (now >= stance.until) {
                it.remove();
                release(stance);
            } else if (now % 3 == 0) {
                Location at = stance.player.getLocation().add(0, 1.0, 0);
                plugin.fx().view(at).dust(at, CRIMSON, 1.0f, 3, 0.45);
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
                view.dust(Geo.middle(bleed.target), CRIMSON, 1.0f, 2, 0.25);
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
                    cutFx(target, 35);
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

    /** A crimson streak along the path, after-images and sparks. */
    private void drawFlash(Location start, Location end, Vector direction, double length) {
        Location middle = start.clone().add(end).multiply(0.5).add(0, 1.0, 0);
        Visuals visuals = plugin.visuals();
        visuals.spawn("crimson_streak", middle).facing(direction).size(0.3, 1.8, 0.3).send(0)
                .animate(1, 3, e -> e.size(1.4, 2.2, length + 1.5))
                .vanish(16, 8);
        visuals.spawn("crimson_slash", end.clone().add(0, 1.1, 0)).facing(direction).tilt(-18).size(0.8).send(0)
                .animate(1, 3, e -> e.size(4.2))
                .vanish(9, 6);
        Fx.View view = plugin.fx().view(middle);
        for (double d = 0; d <= length; d += 0.5) {
            Location point = start.clone().add(direction.clone().multiply(d)).add(0, 1.0, 0);
            view.dust(point, d % 1.0 < 0.5 ? CRIMSON : STEEL, 1.2f, 2, 0.15);
            if (d % 2.0 < 0.5) {
                view.particle(Fx.SWEEP, point, 1, 0, 0, 0, 0);
            }
        }
        view.particle(Fx.CRIT, end.clone().add(0, 1, 0), 20, 0.3, 0.5, 0.3, 0.3);
    }

    /** The cut opening on a target: a crimson X that flashes and fades. */
    private void cutFx(LivingEntity target, int tilt) {
        Location at = Geo.middle(target);
        plugin.visuals().spawn("crimson_cut", at).billboard().tilt(tilt).size(0.4).send(0)
                .animate(1, 2, e -> e.size(2.6))
                .vanish(10, 6);
        Fx.View view = plugin.fx().view(at);
        view.dust(at, CRIMSON, 1.5f, 12, 0.35);
        view.particle(Fx.CRIT, at, 10, 0.3, 0.4, 0.3, 0.3);
    }

    // ---- Iaido -------------------------------------------------------------------------------------------

    private Result stance(Player player) {
        AbilitySettings settings = plugin.settings().ability(Ability.IAIDO);
        int ticks = settings.ticks("stance");
        Visuals.Effect ring = plugin.visuals().spawn("rune_crimson", player.getLocation().add(0, 0.06, 0)).size(0.4).send(0)
                .animate(1, 4, e -> e.size(3.2).turn(90))
                .follow(player, new Vector(0, 0.06, 0), ticks);
        for (int t = 6; t < ticks; t += 6) {
            int step = t / 6;
            ring.animate(t, 6, e -> e.turn(90 + step * 60));
        }
        ring.vanish(ticks + 1, 4);
        stances.put(player.getUniqueId(), new Stance(player, plugin.tick() + ticks, ring));
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
        stance.ring.animate(1, 2, e -> e.size(5.5)).vanish(5, 5);
        org.bukkit.potion.PotionEffectType slowness = Compat.effect("slowness");
        if (slowness != null) {
            player.removePotionEffect(slowness);
        }
        plugin.fx().sound(player.getLocation(), "iaido-parry");
        Fx.View view = plugin.fx().view(player.getLocation());
        view.particle(Fx.CRIT, player.getLocation().add(0, 1.2, 0), 25, 0.4, 0.5, 0.4, 0.4);
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
            Vector way = behind.toVector().subtract(from.toVector()).setY(0);
            double length = way.length();
            if (length > 0.5) {
                plugin.visuals().spawn("crimson_streak", from.clone().add(behind).multiply(0.5).add(0, 1.0, 0))
                        .facing(way).size(1.3, 2.0, length).send(0).vanish(10, 7);
            }
        }
        if (plugin.hits().hurt(player, attacker, settings.num("damage"))) {
            AbilitySettings edge = plugin.settings().ability(Ability.CRIMSON_EDGE);
            bleed(player, attacker, edge.num("bleed-damage"), edge.ticks("bleed-duration"));
            Compat.heal(player, settings.num("heal"));
            Location at = Geo.middle(attacker);
            plugin.visuals().spawn("crimson_slash", at).facing(Geo.flat(player.getLocation())).tilt(30).size(1.0).send(0)
                    .animate(1, 2, e -> e.size(4.6))
                    .vanish(8, 6);
            cutFx(attacker, -40);
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
        plugin.visuals().spawn("crimson_slash", player.getLocation().add(direction.clone().multiply(2.0)).add(0, 1.1, 0))
                .facing(direction).tilt(10).size(1.2).send(0)
                .animate(1, 3, e -> e.size(6.0))
                .vanish(9, 6);
        plugin.fx().sound(player.getLocation(), "crimson-cut");
        if (settings.num("slash-damage") <= 0) {
            return;
        }
        for (LivingEntity target : plugin.hits().along(player, start, end, 1.4)) {
            if (plugin.hits().hurt(player, target, settings.num("slash-damage"))) {
                cutFx(target, 20);
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
        cutFx(target, plugin.tick() % 2 == 0 ? 30 : -30);
        plugin.fx().sound(target.getLocation(), "crimson-edge");
    }
}
