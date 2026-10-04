package io.github.drepfy.legendary.ability;

import io.github.drepfy.legendary.Ability;
import io.github.drepfy.legendary.LegendaryPlugin;
import io.github.drepfy.legendary.WeaponType;
import io.github.drepfy.legendary.config.AbilitySettings;
import io.github.drepfy.legendary.item.WeaponItems;
import io.github.drepfy.legendary.util.Text;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Starforged, the celestial axe: area control and gravity.
 * <ul>
 *   <li><b>Astral Impact</b>: a warning circle appears where the player looks; a moment later a
 *   star strikes it, hurting and launching everyone inside.</li>
 *   <li><b>Gravity Well</b>: a field that drags players towards its centre for a few seconds,
 *   then bursts outward.</li>
 * </ul>
 * The two never overlap: while a star is falling no well can open and the other way round,
 * so nobody can be held in place under a strike.
 */
final class Starforged implements Kit, Listener {

    private static final Color GOLD = Color.fromRGB(255, 215, 110);
    private static final Color SKY = Color.fromRGB(110, 215, 255);
    private static final Color INDIGO = Color.fromRGB(90, 60, 230);
    private static final Color DEEP = Color.fromRGB(35, 20, 90);

    private final LegendaryPlugin plugin;
    /** Caster → when their star lands or their well closes (for the action bar). */
    private final Map<UUID, Long> strikes = new HashMap<>();
    private final Map<UUID, Long> wells = new HashMap<>();
    /** How far each player in a well moved up or down last tick. */
    private final Map<UUID, Double> vertical = new HashMap<>();
    private int openWells;

    Starforged(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public WeaponType type() {
        return WeaponType.STARFORGED;
    }

    @Override
    public Result use(Player player, WeaponItems.Tag weapon, Ability ability) {
        return ability == Ability.ASTRAL_IMPACT ? strike(player, weapon) : well(player, weapon);
    }

    @Override
    public long active(Player player, WeaponItems.Tag weapon, Ability ability, long now) {
        Long until = (ability == Ability.ASTRAL_IMPACT ? strikes : wells).get(player.getUniqueId());
        return until == null ? 0 : Math.max(0, until - now);
    }

    @Override
    public void forget(Player player) {
        vertical.remove(player.getUniqueId());
    }

    private Location target(Player player, double range) {
        Location target = Geo.target(player, range);
        if (target == null) {
            plugin.hud().flash(player, Text.format(plugin.settings().message("no-target"), "range", Text.number(range)));
        }
        return target;
    }

    // ---- Astral Impact -------------------------------------------------------------------------------------

    private Result strike(Player player, WeaponItems.Tag weapon) {
        AbilitySettings settings = plugin.settings().ability(Ability.ASTRAL_IMPACT);
        Location center = target(player, settings.num("range"));
        if (center == null) {
            return Result.FAILED;
        }
        long now = plugin.tick();
        int warning = settings.ticks("warning");
        AbilitySettings well = plugin.settings().ability(Ability.GRAVITY_WELL);
        plugin.abilities().cooldowns().atLeast(weapon.id(), Ability.GRAVITY_WELL, now, warning + well.ticks("lockout"));
        strikes.put(player.getUniqueId(), now + warning);
        plugin.fx().sound(center, "astral-warning");
        new Star(player, center, warning, settings).runTaskTimer(plugin, 0L, 1L);
        return Result.FIRED;
    }

    /** The warning circle, then the star. */
    private final class Star extends BukkitRunnable {
        private final Player player;
        private final Location center;
        private final int warning;
        private final AbilitySettings settings;
        private int age;

        Star(Player player, Location center, int warning, AbilitySettings settings) {
            this.player = player;
            this.center = center;
            this.warning = warning;
            this.settings = settings;
        }

        @Override
        public void run() {
            if (age >= warning) {
                cancel();
                strikes.remove(player.getUniqueId());
                land();
                return;
            }
            if (age % 2 == 0) {
                warn();
            }
            age++;
        }

        /** The circle everyone sees, with a ring closing in as the star gets near. */
        private void warn() {
            double radius = settings.num("radius");
            Fx.View view = plugin.fx().view(center);
            int points = (int) Math.max(24, radius * 10);
            double spin = age * 0.08;
            for (int i = 0; i < points; i++) {
                double angle = spin + Math.PI * 2 * i / points;
                view.dust(center.clone().add(Math.cos(angle) * radius, 0.15, Math.sin(angle) * radius), GOLD, 1.3f, 1, 0);
            }
            double inner = radius * (1.0 - (double) age / warning);
            for (int i = 0; i < 12; i++) {
                double angle = -spin + Math.PI * 2 * i / 12;
                view.particle(Fx.END_ROD, center.clone().add(Math.cos(angle) * inner, 0.2, Math.sin(angle) * inner),
                        1, 0, 0, 0, 0);
            }
            view.dust(center.clone().add(0, 0.2 + (double) age / warning * 6, 0), SKY, 1.5f, 2, 0.05);
        }

        private void land() {
            Fx.View view = plugin.fx().view(center);
            for (double y = 14; y >= 0; y -= 0.5) {
                view.particle(Fx.END_ROD, center.clone().add(0, y, 0), 1, 0.05, 0, 0.05, 0);
            }
            view.particle(Fx.FLASH, center.clone().add(0, 0.5, 0), 1, 0, 0, 0, 0);
            view.particle(Fx.EXPLOSION, center.clone().add(0, 0.5, 0), 1, 0, 0, 0, 0);
            view.particle(Fx.FIREWORK, center.clone().add(0, 0.6, 0), 70, 0.3, 0.3, 0.3, 0.35);
            view.particle(Fx.SPARK, center.clone().add(0, 0.6, 0), 40, settings.num("radius") / 2, 0.4,
                    settings.num("radius") / 2, 0.1);
            for (int i = 0; i < 30; i++) {
                double angle = Math.PI * 2 * i / 30;
                view.dust(center.clone().add(Math.cos(angle) * settings.num("radius"), 0.3,
                        Math.sin(angle) * settings.num("radius")), i % 2 == 0 ? GOLD : SKY, 1.8f, 1, 0.05);
            }
            plugin.fx().sound(center, "astral-impact");
            if (!player.isOnline() || !player.getWorld().equals(center.getWorld())) {
                return;
            }
            double radius = settings.num("radius");
            Location above = center.clone().add(0, 1.0, 0);
            for (Entity entity : center.getWorld().getNearbyEntities(center, radius + 0.5, 3, radius + 0.5)) {
                if (!(entity instanceof LivingEntity target) || !plugin.hits().canTarget(player, entity)
                        || Geo.flatDistance(center, entity.getLocation()) > radius
                        || entity.getLocation().getY() < center.getY() - 1.5 || !Geo.clear(above, Geo.middle(entity))) {
                    continue;
                }
                if (plugin.hits().hurt(player, target, settings.num("damage"))) {
                    Hits.knock(target, Geo.away(center, entity.getLocation(), new Vector()), settings.num("push"),
                            settings.num("launch"));
                    plugin.fx().view(entity.getLocation()).particle(Fx.END_ROD, Geo.middle(entity), 12, 0.3, 0.5, 0.3, 0.1);
                }
            }
        }
    }

    // ---- Gravity Well -------------------------------------------------------------------------------------

    private Result well(Player player, WeaponItems.Tag weapon) {
        AbilitySettings settings = plugin.settings().ability(Ability.GRAVITY_WELL);
        Location ground = target(player, settings.num("range"));
        if (ground == null) {
            return Result.FAILED;
        }
        long now = plugin.tick();
        int duration = settings.ticks("duration");
        plugin.abilities().cooldowns().atLeast(weapon.id(), Ability.ASTRAL_IMPACT, now,
                duration + settings.ticks("lockout"));
        wells.put(player.getUniqueId(), now + duration);
        Location center = ground.clone().add(0, 1.0, 0);
        // The axe's power thrown to the spot.
        Location hand = player.getEyeLocation().subtract(0, 0.3, 0);
        Vector line = center.toVector().subtract(hand.toVector());
        double length = line.length();
        Fx.View view = plugin.fx().view(hand);
        if (length > 0.01) {
            line.multiply(1.0 / length);
            for (double d = 0; d < length; d += 0.6) {
                view.dust(hand.clone().add(line.clone().multiply(d)), INDIGO, 1.0f, 1, 0.02);
            }
        }
        plugin.fx().sound(center, "gravity-well");
        openWells++;
        new Well(player, center, duration, settings).runTaskTimer(plugin, 0L, 1L);
        return Result.FIRED;
    }

    /** The field: pulls for its whole life, then bursts. */
    private final class Well extends BukkitRunnable {
        private final Player player;
        private final Location center;
        private final int duration;
        private final AbilitySettings settings;
        private final double radius;
        /** Gripped: the grip hit was allowed. Spared: protected (or not allowed to be hit). */
        private final Set<UUID> gripped = new HashSet<>();
        private final Set<UUID> spared = new HashSet<>();
        private int age;

        Well(Player player, Location center, int duration, AbilitySettings settings) {
            this.player = player;
            this.center = center;
            this.duration = duration;
            this.settings = settings;
            this.radius = settings.num("radius");
        }

        @Override
        public void run() {
            if (!player.isOnline() || !player.getWorld().equals(center.getWorld())) {
                close();
                return;
            }
            if (age >= duration) {
                close();
                pulse();
                return;
            }
            for (Entity entity : center.getWorld().getNearbyEntities(center, radius, radius / 2 + 1.5, radius)) {
                if (!(entity instanceof LivingEntity target) || spared.contains(entity.getUniqueId())
                        || Geo.flatDistance(center, entity.getLocation()) > radius
                        || !plugin.hits().canTarget(player, entity)) {
                    continue;
                }
                if (!gripped.contains(entity.getUniqueId())) {
                    if (plugin.hits().hurt(player, target, settings.num("grip-damage"))) {
                        gripped.add(entity.getUniqueId());
                        plugin.fx().view(entity.getLocation()).particle(Fx.REVERSE_PORTAL, Geo.middle(entity), 20,
                                0.3, 0.5, 0.3, 0.05);
                    } else {
                        spared.add(entity.getUniqueId());
                        continue;
                    }
                }
                if (age % 2 == 0) {
                    pull(target);
                }
            }
            if (age % 2 == 0) {
                draw();
            }
            if (age % 20 == 0) {
                plugin.fx().sound(center, "gravity-hum");
            }
            age++;
        }

        private void pull(LivingEntity target) {
            Vector to = center.toVector().subtract(target.getLocation().toVector()).setY(0);
            if (to.lengthSquared() < 0.64) {
                return; // At the centre already.
            }
            to.normalize().multiply(settings.num("pull"));
            double y;
            if (target instanceof Player player) {
                // The well drags players down as well as in: a jump is cut short, a fall is not slowed.
                double dy = vertical.getOrDefault(player.getUniqueId(), 0.0);
                y = player.isOnGround() ? 0.0 : (dy > 0 ? dy * 0.5 : dy);
            } else {
                y = target.getVelocity().getY();
            }
            target.setVelocity(to.setY(y));
        }

        /** The edge of the field, and particles streaming into its centre. */
        private void draw() {
            Fx.View view = plugin.fx().view(center);
            double spin = age * 0.06;
            int points = (int) Math.max(30, radius * 8);
            for (int i = 0; i < points; i++) {
                double angle = spin + Math.PI * 2 * i / points;
                view.dust(center.clone().add(Math.cos(angle) * radius, -0.8, Math.sin(angle) * radius),
                        i % 3 == 0 ? INDIGO : DEEP, 1.3f, 1, 0);
            }
            for (int i = 0; i < 10; i++) {
                double angle = -spin * 2 + Math.PI * 2 * i / 10;
                double r = radius * (0.4 + 0.6 * ((i * 37 + age) % 10) / 10.0);
                // A portal particle drifts from the offset back to where it is spawned: into the centre.
                view.particle(Fx.PORTAL, center, 0, Math.cos(angle) * r, 0.2, Math.sin(angle) * r, 1.0);
            }
            view.particle(Fx.END_ROD, center.clone().add(Math.cos(spin * 4) * 0.4, Math.sin(spin * 3) * 0.3,
                    Math.sin(spin * 4) * 0.4), 1, 0, 0, 0, 0);
            view.particle(Fx.DRAGON_BREATH, center, 2, 0.2, 0.2, 0.2, 0.01);
        }

        private void pulse() {
            Fx.View view = plugin.fx().view(center);
            view.particle(Fx.SONIC_BOOM, center, 1, 0, 0, 0, 0);
            view.particle(Fx.EXPLOSION, center, 1, 0, 0, 0, 0);
            for (int i = 0; i < 40; i++) {
                double angle = Math.PI * 2 * i / 40;
                view.dust(center.clone().add(Math.cos(angle) * radius * 0.8, -0.5, Math.sin(angle) * radius * 0.8),
                        i % 2 == 0 ? INDIGO : SKY, 1.8f, 1, 0.1);
            }
            plugin.fx().sound(center, "gravity-pulse");
            for (Entity entity : center.getWorld().getNearbyEntities(center, radius, radius / 2 + 1.5, radius)) {
                if (!(entity instanceof LivingEntity target) || spared.contains(entity.getUniqueId())
                        || Geo.flatDistance(center, entity.getLocation()) > radius
                        || !plugin.hits().canTarget(player, entity)) {
                    continue;
                }
                if (plugin.hits().hurt(player, target, settings.num("pulse-damage"))) {
                    Hits.knock(target, Geo.away(center, entity.getLocation(), Geo.flat(player.getLocation())),
                            settings.num("pulse-knockback"), settings.num("pulse-lift"));
                }
            }
        }

        private void close() {
            cancel();
            wells.remove(player.getUniqueId());
            openWells = Math.max(0, openWells - 1);
            if (openWells == 0) {
                vertical.clear();
            }
        }
    }

    /** Only while a well is open: how players move up and down (the server does not know their speed). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (openWells > 0 && event.getTo() != null) {
            vertical.put(event.getPlayer().getUniqueId(), event.getTo().getY() - event.getFrom().getY());
        }
    }
}
