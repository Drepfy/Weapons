package io.github.drepfy.legendary.ability;

import io.github.drepfy.legendary.Ability;
import io.github.drepfy.legendary.LegendaryPlugin;
import io.github.drepfy.legendary.WeaponType;
import io.github.drepfy.legendary.config.AbilitySettings;
import io.github.drepfy.legendary.item.WeaponItems;
import io.github.drepfy.legendary.util.Text;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Starforged, the celestial axe: area control and gravity.
 * <ul>
 *   <li><b>Starfall</b>: a rune circle opens where the player looks; after a warning, stars rain
 *   down inside it one after another, each bursting on impact.</li>
 *   <li><b>Singularity</b>: a black hole opens where the player looks and drags everyone near it
 *   towards its heart, then collapses into a nova that throws them all away.</li>
 * </ul>
 * The two never overlap: while stars are falling no black hole can open and the other way round,
 * so nobody can be held in place under the stars.
 */
final class Starforged implements Kit {

    private static final Color GOLD = Color.fromRGB(255, 214, 110);
    private static final Color SKY = Color.fromRGB(110, 225, 255);
    private static final Color INDIGO = Color.fromRGB(95, 60, 235);
    private static final Color DEEP = Color.fromRGB(20, 10, 55);

    private final LegendaryPlugin plugin;
    /** Caster → the tick their last star lands (for the boss bar). */
    private final Map<UUID, Long> falls = new HashMap<>();
    private final Map<UUID, Hole> holes = new HashMap<>();
    private final Random random = new Random();

    Starforged(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    private static final class Hole {
        final Player player;
        final Location center;
        final long until;
        final Visuals.Effect core;
        final Visuals.Effect disk;
        final Set<UUID> allowed = new HashSet<>();
        final Set<UUID> refused = new HashSet<>();
        int turn;

        Hole(Player player, Location center, long until, Visuals.Effect core, Visuals.Effect disk) {
            this.player = player;
            this.center = center;
            this.until = until;
            this.core = core;
            this.disk = disk;
        }
    }

    @Override
    public WeaponType type() {
        return WeaponType.STARFORGED;
    }

    @Override
    public Result use(Player player, WeaponItems.Tag weapon, Ability ability) {
        return ability == Ability.STARFALL ? starfall(player, weapon) : singularity(player, weapon);
    }

    @Override
    public long active(Player player, WeaponItems.Tag weapon, Ability ability, long now) {
        Long until;
        if (ability == Ability.STARFALL) {
            until = falls.get(player.getUniqueId());
        } else {
            Hole hole = holes.get(player.getUniqueId());
            until = hole == null ? null : hole.until;
        }
        return until == null ? 0 : Math.max(0, until - now);
    }

    @Override
    public long activeLength(Ability ability) {
        AbilitySettings settings = plugin.settings().ability(ability);
        return ability == Ability.STARFALL ? starfallLength(settings) : settings.ticks("duration");
    }

    private static long starfallLength(AbilitySettings settings) {
        return settings.ticks("warning") + (long) (settings.whole("stars") - 1) * Math.max(1, settings.ticks("interval")) + 6;
    }

    @Override
    public void tick(long now) {
        falls.values().removeIf(until -> until <= now);
        for (Iterator<Hole> it = holes.values().iterator(); it.hasNext(); ) {
            Hole hole = it.next();
            if (now >= hole.until) {
                it.remove();
                collapse(hole);
            } else {
                drag(hole, now);
            }
        }
    }

    private Location target(Player player, double range) {
        Location target = Geo.target(player, range);
        if (target == null) {
            plugin.hud().notice(player, Text.format(plugin.settings().message("no-target"), "range", Text.number(range)));
        }
        return target;
    }

    // ---- Starfall ----------------------------------------------------------------------------------------

    private Result starfall(Player player, WeaponItems.Tag weapon) {
        AbilitySettings settings = plugin.settings().ability(Ability.STARFALL);
        Location center = target(player, settings.num("range"));
        if (center == null) {
            return Result.FAILED;
        }
        long now = plugin.tick();
        long length = starfallLength(settings);
        AbilitySettings hole = plugin.settings().ability(Ability.SINGULARITY);
        plugin.abilities().cooldowns().atLeast(weapon.id(), Ability.SINGULARITY, now, length + hole.ticks("lockout"));
        falls.put(player.getUniqueId(), now + length);
        double radius = settings.num("radius");
        int warning = settings.ticks("warning");
        plugin.fx().sound(center, "starfall");
        // The rune circle: opens, turns while the stars fall, then closes.
        Visuals.Effect circle = plugin.visuals().spawn("rune_star", center.clone().add(0, 0.06, 0)).size(0.5).send(0)
                .animate(1, 6, e -> e.size(radius * 2.0));
        for (int t = 8; t < length; t += 8) {
            int step = t / 8;
            circle.animate(t, 8, e -> e.turn(step * 45));
        }
        circle.vanish((int) length + 4, 6);
        int stars = settings.whole("stars");
        int interval = Math.max(1, settings.ticks("interval"));
        Map<UUID, Integer> hits = new HashMap<>();
        for (int i = 0; i < stars; i++) {
            Location landing = i == 0 ? center.clone() : Geo.scatter(center, radius * 0.8, random);
            Location floor = Geo.floorBelow(center.getWorld(), landing.getX(), center.getY() + 2, landing.getZ(), 5);
            Location spot = floor != null ? floor : landing;
            plugin.visuals().later(warning + i * interval - 6, () -> star(player, spot, hits));
        }
        return Result.FIRED;
    }

    /** One star: it falls for 6 ticks, then bursts. */
    private void star(Player player, Location spot, Map<UUID, Integer> hits) {
        Location sky = spot.clone().add(random.nextDouble() * 4 - 2, 14, random.nextDouble() * 4 - 2);
        Visuals.Effect star = plugin.visuals().spawn("star", sky).billboard().size(1.4).send(0);
        plugin.visuals().later(1, () -> star.moveTo(spot.clone().add(0, 0.6, 0), 5));
        plugin.fx().sound(sky, "starfall-star");
        plugin.visuals().later(6, () -> {
            star.animate(1, 2, e -> e.size(2.6)).vanish(3, 2);
            impact(player, spot, hits);
        });
    }

    private void impact(Player player, Location spot, Map<UUID, Integer> hits) {
        AbilitySettings settings = plugin.settings().ability(Ability.STARFALL);
        double radius = settings.num("star-radius");
        plugin.fx().sound(spot, "starfall-impact");
        plugin.visuals().spawn("nova", spot.clone().add(0, 0.08, 0)).size(0.4).turn(random.nextInt(360)).send(0)
                .animate(1, 4, e -> e.size(radius * 2.4))
                .vanish(5, 3);
        Fx.View view = plugin.fx().view(spot);
        Location up = spot.clone().add(0, 0.4, 0);
        view.particle(Fx.FLASH, up, 1, 0, 0, 0, 0);
        view.particle(Fx.FIREWORK, up, 25, 0.3, 0.3, 0.3, 0.18);
        view.particle(Fx.END_ROD, up, 12, 0.4, 0.4, 0.4, 0.1);
        view.dust(up, GOLD, 1.5f, 12, 0.6);
        view.dust(up, SKY, 1.5f, 12, 0.6);
        if (!player.isOnline()) {
            return;
        }
        int max = settings.whole("max-hits");
        for (LivingEntity target : plugin.hits().around(player, spot.clone().add(0, 0.8, 0), radius)) {
            int count = hits.getOrDefault(target.getUniqueId(), 0);
            if (count >= max) {
                continue;
            }
            if (plugin.hits().hurt(player, target, settings.num("damage"))) {
                hits.put(target.getUniqueId(), count + 1);
                Vector away = Geo.away(spot, target.getLocation(), Geo.flat(player.getLocation()));
                Hits.knock(target, away, 0.25, settings.num("launch"));
                plugin.fx().sound(target.getLocation(), "starforged-hit");
            }
        }
    }

    // ---- Singularity ---------------------------------------------------------------------------------------

    private Result singularity(Player player, WeaponItems.Tag weapon) {
        AbilitySettings settings = plugin.settings().ability(Ability.SINGULARITY);
        Location ground = target(player, settings.num("range"));
        if (ground == null) {
            return Result.FAILED;
        }
        long now = plugin.tick();
        int duration = settings.ticks("duration");
        plugin.abilities().cooldowns().atLeast(weapon.id(), Ability.STARFALL, now, duration + settings.ticks("lockout"));
        Location center = ground.clone().add(0, 1.6, 0);
        double radius = settings.num("radius");
        Visuals.Effect core = plugin.visuals().spawn("black_hole", center).billboard().size(0.1).send(0)
                .animate(1, 6, e -> e.size(2.4));
        Visuals.Effect disk = plugin.visuals().spawn("accretion", center).size(0.1).send(0)
                .animate(1, 8, e -> e.size(radius * 0.9));
        holes.put(player.getUniqueId(), new Hole(player, center, now + duration, core, disk));
        plugin.fx().sound(center, "singularity");
        return Result.FIRED;
    }

    private void drag(Hole hole, long now) {
        AbilitySettings settings = plugin.settings().ability(Ability.SINGULARITY);
        double radius = settings.num("radius");
        if (now % 4 == 0) {
            hole.turn++;
            hole.disk.turn(hole.turn * 70.0).send(4);
        }
        Fx.View view = plugin.fx().view(hole.center);
        view.particle(Fx.REVERSE_PORTAL, hole.center, 10, 0.3, 0.3, 0.3, 0.02);
        for (int i = 0; i < 3; i++) {
            double a = now * 0.35 + i * (Math.PI * 2 / 3);
            Location point = hole.center.clone().add(Math.cos(a) * radius * 0.6, 0, Math.sin(a) * radius * 0.6);
            view.dust(point, i == 0 ? SKY : i == 1 ? INDIGO : GOLD, 1.3f, 2, 0.15);
        }
        view.dust(hole.center, DEEP, 2.2f, 3, 0.3);
        if (!hole.player.isOnline()) {
            return;
        }
        double pull = settings.num("pull");
        for (LivingEntity target : plugin.hits().around(hole.player, hole.center, radius)) {
            UUID id = target.getUniqueId();
            if (hole.refused.contains(id)) {
                continue;
            }
            if (!hole.allowed.contains(id)) {
                // The first touch hurts a little: that is what protection plugins get to refuse.
                if (!plugin.hits().hurt(hole.player, target, settings.num("grip-damage"))) {
                    hole.refused.add(id);
                    continue;
                }
                hole.allowed.add(id);
                plugin.fx().sound(target.getLocation(), "starforged-hit");
            }
            Vector in = hole.center.toVector().subtract(Geo.middle(target).toVector());
            double distance = in.length();
            if (distance < 0.8) {
                target.setVelocity(target.getVelocity().multiply(0.3));
                continue;
            }
            double strength = pull * (0.6 + 0.4 * (1 - Math.min(1, distance / radius)));
            Vector velocity = target.getVelocity().multiply(0.55).add(in.normalize().multiply(strength));
            velocity.setY(Math.max(-0.35, Math.min(0.3, velocity.getY())));
            target.setVelocity(velocity);
            if (now % 10 == 0) {
                Hits.effect(target, "slowness", 1, 12);
            }
        }
    }

    private void collapse(Hole hole) {
        AbilitySettings settings = plugin.settings().ability(Ability.SINGULARITY);
        double radius = settings.num("radius");
        hole.core.animate(1, 3, e -> e.size(0.01)).life(5);
        hole.disk.animate(1, 3, e -> e.size(0.01)).life(5);
        plugin.visuals().later(3, () -> {
            plugin.fx().sound(hole.center, "singularity-nova");
            plugin.visuals().spawn("nova", hole.center.clone().add(0, -1.5, 0)).size(0.5).send(0)
                    .animate(1, 5, e -> e.size(radius * 2.4).turn(90))
                    .vanish(6, 4);
            plugin.visuals().spawn("star", hole.center).billboard().size(0.5).send(0)
                    .animate(1, 3, e -> e.size(5.0))
                    .vanish(4, 3);
            Fx.View view = plugin.fx().view(hole.center);
            view.particle(Fx.FLASH, hole.center, 1, 0, 0, 0, 0);
            view.particle(Fx.SONIC_BOOM, hole.center, 1, 0, 0, 0, 0);
            view.particle(Fx.END_ROD, hole.center, 40, 0.5, 0.5, 0.5, 0.35);
            if (!hole.player.isOnline()) {
                return;
            }
            for (LivingEntity target : plugin.hits().around(hole.player, hole.center, radius)) {
                if (hole.refused.contains(target.getUniqueId())) {
                    continue;
                }
                if (plugin.hits().hurt(hole.player, target, settings.num("nova-damage"))) {
                    Vector away = Geo.away(hole.center, target.getLocation(), Geo.flat(hole.player.getLocation()));
                    Hits.knock(target, away, settings.num("nova-knockback"), settings.num("nova-lift"));
                    plugin.fx().sound(target.getLocation(), "starforged-hit");
                }
            }
        });
    }
}
