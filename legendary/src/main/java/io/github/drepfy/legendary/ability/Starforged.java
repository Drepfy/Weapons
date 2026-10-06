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
import org.bukkit.event.entity.EntityDamageByEntityEvent;
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
 *   down inside it one after another, each bursting on impact. The circle follows the player's
 *   aim while the stars fall, so it can chase whoever runs.</li>
 *   <li><b>Singularity</b>: a black hole opens where the player looks and drags everyone near it
 *   towards its heart, then collapses into a nova that throws them all away and slows them.</li>
 *   <li><b>Starstruck</b> (passive): every fourth hit in a row on the same target calls a small
 *   star down on it.</li>
 * </ul>
 * Starfall and Singularity never overlap: while stars are falling no black hole can open and the
 * other way round, so nobody can be held in place under the stars.
 */
final class Starforged implements Kit {

    private static final Color GOLD = Color.fromRGB(255, 214, 110);
    private static final Color SKY = Color.fromRGB(110, 225, 255);
    private static final Color INDIGO = Color.fromRGB(95, 60, 235);
    private static final Color DEEP = Color.fromRGB(20, 10, 55);

    private final LegendaryPlugin plugin;
    private final Map<UUID, Fall> falls = new HashMap<>();
    private final Map<UUID, Hole> holes = new HashMap<>();
    private final Combos combos = new Combos();
    private final Random random = new Random();

    Starforged(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    private static final class Fall {
        final Player player;
        final long until;
        final Visuals.Effect circle;
        final Map<UUID, Integer> hits = new HashMap<>();
        Location center;

        Fall(Player player, Location center, long until, Visuals.Effect circle) {
            this.player = player;
            this.center = center;
            this.until = until;
            this.circle = circle;
        }
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
            Fall fall = falls.get(player.getUniqueId());
            until = fall == null ? null : fall.until;
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
    public void forget(Player player) {
        falls.remove(player.getUniqueId());
        combos.forget(player.getUniqueId());
    }

    @Override
    public void tick(long now) {
        for (Iterator<Fall> it = falls.values().iterator(); it.hasNext(); ) {
            Fall fall = it.next();
            if (now >= fall.until) {
                it.remove();
            } else {
                follow(fall);
            }
        }
        if (now % 200 == 0) {
            combos.prune(now, 400);
        }
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
        Fall fall = new Fall(player, center, now + length, circle);
        falls.put(player.getUniqueId(), fall);
        int stars = settings.whole("stars");
        int interval = Math.max(1, settings.ticks("interval"));
        for (int i = 0; i < stars; i++) {
            boolean first = i == 0;
            plugin.visuals().later(warning + i * interval - 6, () -> {
                // Where the circle is now: it follows the caster's aim.
                Location landing = first ? fall.center.clone() : Geo.scatter(fall.center, radius * 0.8, random);
                Location floor = Geo.floorBelow(landing.getWorld(), landing.getX(), fall.center.getY() + 2, landing.getZ(), 5);
                star(player, floor != null ? floor : landing, fall.hits);
            });
        }
        return Result.FIRED;
    }

    /** The circle glides towards where the caster is looking. */
    private void follow(Fall fall) {
        double speed = plugin.settings().ability(Ability.STARFALL).num("follow-speed");
        if (speed <= 0 || !fall.player.isOnline()) {
            return;
        }
        Location aim = Geo.target(fall.player, plugin.settings().ability(Ability.STARFALL).num("range"));
        if (aim == null || aim.getWorld() != fall.center.getWorld()) {
            return;
        }
        Vector way = aim.toVector().subtract(fall.center.toVector());
        double distance = way.length();
        if (distance < 0.05) {
            return;
        }
        fall.center = distance <= speed ? aim : fall.center.clone().add(way.multiply(speed / distance));
        fall.circle.moveTo(fall.center.clone().add(0, 0.06, 0), 1);
    }

    /** One star: it falls for 6 ticks, then bursts. */
    private void star(Player player, Location spot, Map<UUID, Integer> hits) {
        Location sky = spot.clone().add(random.nextDouble() * 4 - 2, 14, random.nextDouble() * 4 - 2);
        Visuals.Effect star = plugin.visuals().spawn("star", sky).billboard().size(1.9).send(0);
        plugin.visuals().later(1, () -> star.moveTo(spot.clone().add(0, 0.6, 0), 5));
        plugin.fx().sound(sky, "starfall-star");
        plugin.visuals().later(6, () -> {
            star.animate(1, 2, e -> e.size(3.4)).vanish(5, 4);
            impact(player, spot, hits);
        });
    }

    private void impact(Player player, Location spot, Map<UUID, Integer> hits) {
        AbilitySettings settings = plugin.settings().ability(Ability.STARFALL);
        double radius = settings.num("star-radius");
        plugin.fx().sound(spot, "starfall-impact");
        plugin.visuals().spawn("nova", spot.clone().add(0, 0.08, 0)).size(0.4).turn(random.nextInt(360)).send(0)
                .animate(1, 4, e -> e.size(radius * 2.4))
                .vanish(9, 6);
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
                .animate(1, 6, e -> e.size(3.0));
        Visuals.Effect disk = plugin.visuals().spawn("accretion", center).size(0.1).send(0)
                .animate(1, 8, e -> e.size(radius * 1.1));
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
                    .vanish(10, 6);
            plugin.visuals().spawn("star", hole.center).billboard().size(0.5).send(0)
                    .animate(1, 3, e -> e.size(6.5))
                    .vanish(7, 5);
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
                    Hits.effect(target, "slowness", settings.whole("slow-level"), settings.ticks("slow-duration"));
                    plugin.fx().sound(target.getLocation(), "starforged-hit");
                }
            }
        });
    }

    // ---- Starstruck (passive) ------------------------------------------------------------------------------

    @Override
    public void melee(EntityDamageByEntityEvent event, Player attacker, LivingEntity target, WeaponItems.Tag weapon) {
        AbilitySettings settings = plugin.settings().ability(Ability.STARSTRUCK);
        if (combos.hit(attacker.getUniqueId(), target.getUniqueId(), plugin.tick(), settings.whole("hits"),
                settings.ticks("window"), settings.ticks("min-hit-gap"))) {
            plugin.visuals().later(Math.max(0, settings.ticks("delay") - 5), () -> smallStar(attacker, target));
        }
    }

    /** A small star falls on the target and bursts. */
    private void smallStar(Player attacker, LivingEntity target) {
        if (!target.isValid() || target.isDead() || !attacker.isOnline()) {
            return;
        }
        Location sky = target.getLocation().add(random.nextDouble() - 0.5, 9, random.nextDouble() - 0.5);
        Visuals.Effect star = plugin.visuals().spawn("star", sky).billboard().size(1.3).send(0);
        plugin.visuals().later(1, () -> star.moveTo(Geo.middle(target), 4));
        plugin.fx().sound(sky, "starfall-star");
        plugin.visuals().later(5, () -> {
            star.animate(1, 2, e -> e.size(2.8)).vanish(5, 4);
            AbilitySettings settings = plugin.settings().ability(Ability.STARSTRUCK);
            Location at = target.isValid() ? target.getLocation() : sky;
            plugin.fx().sound(at, "starstruck");
            Fx.View view = plugin.fx().view(at);
            view.particle(Fx.FIREWORK, at.clone().add(0, 1, 0), 15, 0.3, 0.3, 0.3, 0.15);
            view.dust(at.clone().add(0, 1, 0), GOLD, 1.4f, 10, 0.5);
            if (!attacker.isOnline() || settings.num("damage") <= 0) {
                return;
            }
            for (LivingEntity hit : plugin.hits().around(attacker, at.clone().add(0, 0.9, 0), settings.num("radius"))) {
                if (plugin.hits().hurt(attacker, hit, settings.num("damage"))) {
                    Hits.knock(hit, Geo.away(at, hit.getLocation(), Geo.flat(attacker.getLocation())), 0.2,
                            settings.num("launch"));
                }
            }
        });
    }
}
