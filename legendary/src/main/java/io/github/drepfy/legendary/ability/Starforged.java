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
import java.util.List;
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
    private static final Color WHITE = Color.fromRGB(255, 255, 255);

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
        final long opened;
        final long until;
        final Map<UUID, Integer> hits = new HashMap<>();
        Location center;

        Fall(Player player, Location center, long opened, long until) {
            this.player = player;
            this.center = center;
            this.opened = opened;
            this.until = until;
        }
    }

    private static final class Hole {
        final Player player;
        final Location center;
        final long opened;
        final long until;
        final Set<UUID> allowed = new HashSet<>();
        final Set<UUID> refused = new HashSet<>();

        Hole(Player player, Location center, long opened, long until) {
            this.player = player;
            this.center = center;
            this.opened = opened;
            this.until = until;
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
                if ((now - fall.opened) % 3 == 0) {
                    runeCircle(fall, now);
                }
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
        // The rune circle: drawn on the ground while the stars fall (see tick), turning slowly.
        Fall fall = new Fall(player, center, now, now + length);
        falls.put(player.getUniqueId(), fall);
        runeCircle(fall, now);
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
    }

    /**
     * Starfall's rune circle on the ground: a sky-blue ring, a gold inner ring and an eight-pointed
     * star between them, opening over the first few ticks and turning slowly.
     */
    private void runeCircle(Fall fall, long now) {
        double radius = plugin.settings().ability(Ability.STARFALL).num("radius")
                * Math.min(1.0, (now - fall.opened + 1) / 6.0);
        Location at = fall.center.clone().add(0, 0.15, 0);
        Fx.View view = plugin.fx().view(at);
        double turn = now * 0.03;
        view.fade(Shapes.ring(at, radius, (int) (14 + radius * 7), turn), SKY, INDIGO, 1.2f);
        view.dust(Shapes.ring(at, radius * 0.62, (int) (10 + radius * 4), -turn), GOLD, 0.9f);
        List<Location> points = Shapes.ring(at, radius * 0.84, 8, turn);
        for (int k = 0; k < 8; k++) {
            view.fade(Shapes.line(points.get(k), points.get((k + 3) % 8), 0.55), GOLD, SKY, 0.8f);
        }
        view.along(Fx.END_ROD, points, 0.0);
    }

    /**
     * A star falling from the sky to {@code spot} over {@code ticks}: a white-hot head in a blue
     * glow and a golden trail.
     */
    private void fallingStar(Location sky, Location spot, int ticks, double size) {
        Vector way = spot.toVector().subtract(sky.toVector());
        for (int t = 0; t < ticks; t++) {
            Location from = sky.clone().add(way.clone().multiply((double) t / ticks));
            Location head = sky.clone().add(way.clone().multiply((double) (t + 1) / ticks));
            plugin.visuals().later(t, () -> {
                Fx.View view = plugin.fx().view(head);
                view.fade(Shapes.line(from, head, 0.3), GOLD, INDIGO, (float) (0.9 * size));
                view.dust(head, WHITE, (float) (1.8 * size), 3, 0.08);
                view.dust(head, SKY, (float) (1.3 * size), 4, 0.25 * size);
                view.particle(Fx.END_ROD, head, 2, 0.1, 0.1, 0.1, 0.02);
                view.particle(Fx.FIREWORK, from, 2, 0.15, 0.15, 0.15, 0.02);
            });
        }
    }

    /** One star: it falls for 6 ticks, then bursts. */
    private void star(Player player, Location spot, Map<UUID, Integer> hits) {
        Location sky = spot.clone().add(random.nextDouble() * 4 - 2, 14, random.nextDouble() * 4 - 2);
        fallingStar(sky, spot.clone().add(0, 0.6, 0), 6, 1.0);
        plugin.fx().sound(sky, "starfall-star");
        plugin.visuals().later(6, () -> impact(player, spot, hits));
    }

    private void impact(Player player, Location spot, Map<UUID, Integer> hits) {
        AbilitySettings settings = plugin.settings().ability(Ability.STARFALL);
        double radius = settings.num("star-radius");
        plugin.fx().sound(spot, "starfall-impact");
        Fx.View view = plugin.fx().view(spot);
        Location up = spot.clone().add(0, 0.4, 0);
        Location ground = spot.clone().add(0, 0.15, 0);
        for (int k = 1; k <= 3; k++) {
            double r = radius * k / 3.0;
            plugin.visuals().later(k - 1, () -> view.fade(Shapes.ring(ground, r, (int) (10 + r * 8), r), SKY, INDIGO, 1.1f));
        }
        view.particle(Fx.EXPLOSION, up, 1, 0, 0, 0, 0);
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
        holes.put(player.getUniqueId(), new Hole(player, center, now, now + duration));
        plugin.fx().sound(center, "singularity");
        return Result.FIRED;
    }

    private void drag(Hole hole, long now) {
        AbilitySettings settings = plugin.settings().ability(Ability.SINGULARITY);
        double radius = settings.num("radius");
        Fx.View view = plugin.fx().view(hole.center);
        if ((now - hole.opened) % 2 == 0) {
            blackHole(hole, now, radius);
        }
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
            if (now % 3 == 0) {
                view.fade(Shapes.line(Geo.middle(target), hole.center, 0.4), INDIGO, DEEP, 0.8f);
            }
        }
    }

    private void collapse(Hole hole) {
        AbilitySettings settings = plugin.settings().ability(Ability.SINGULARITY);
        double radius = settings.num("radius");
        Fx.View implode = plugin.fx().view(hole.center);
        implode.fade(Shapes.sphere(hole.center, 1.2, 30), INDIGO, DEEP, 1.6f); // It shrinks to a point...
        implode.particle(Fx.REVERSE_PORTAL, hole.center, 40, 0.8, 0.8, 0.8, 0.4);
        plugin.visuals().later(3, () -> {
            plugin.fx().sound(hole.center, "singularity-nova");
            Fx.View view = plugin.fx().view(hole.center);
            // ...and bursts: a sonic boom, a flash of stars, shells of light out to the edge.
            view.particle(Fx.SONIC_BOOM, hole.center, 1, 0, 0, 0, 0);
            view.particle(Fx.EXPLOSION, hole.center, 3, 0.6, 0.6, 0.6, 0);
            view.particle(Fx.END_ROD, hole.center, 40, 0.5, 0.5, 0.5, 0.35);
            view.particle(Fx.FIREWORK, hole.center, 30, 0.4, 0.4, 0.4, 0.3);
            Location ground = hole.center.clone().add(0, -1.45, 0);
            for (int k = 1; k <= 4; k++) {
                double r = radius * k / 4.0;
                plugin.visuals().later(k - 1, () -> {
                    view.fade(Shapes.sphere(hole.center, r * 0.6, (int) (12 + r * 6)), SKY, INDIGO, 1.3f);
                    view.fade(Shapes.ring(ground, r, (int) (12 + r * 6), r), GOLD, INDIGO, 1.2f);
                });
            }
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

    /**
     * The black hole: a dark core ringed by a gold photon ring, and an accretion disk of two
     * glowing arms spiralling into it, growing as it opens.
     */
    private void blackHole(Hole hole, long now, double radius) {
        double open = Math.min(1.0, (now - hole.opened + 1) / 8.0);
        Fx.View view = plugin.fx().view(hole.center);
        view.dust(Shapes.sphere(hole.center, 0.5 * open, 14), DEEP, 2.4f);
        view.particle(Fx.INK, hole.center, 2, 0.15, 0.15, 0.15, 0.0);
        view.dust(Shapes.ring(hole.center, 0.85 * open, 16, now * 0.3), GOLD, 1.0f);
        double outer = radius * 0.55 * open;
        for (int arm = 0; arm < 2; arm++) {
            for (int i = 0; i < 18; i++) {
                double t = i / 17.0;
                double r = 1.0 + t * outer;
                double a = arm * Math.PI + now * 0.22 + t * 3.6;
                Location point = hole.center.clone().add(Math.cos(a) * r, 0, Math.sin(a) * r);
                Color colour = t < 0.35 ? GOLD : t < 0.7 ? SKY : INDIGO;
                view.dust(point, colour, (float) (1.4 - 0.5 * t), 1, 0);
            }
        }
        view.particle(Fx.REVERSE_PORTAL, hole.center, 8, outer * 0.5, 0.3, outer * 0.5, 0.05);
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
        fallingStar(sky, Geo.middle(target), 5, 0.75);
        plugin.fx().sound(sky, "starfall-star");
        plugin.visuals().later(5, () -> {
            AbilitySettings settings = plugin.settings().ability(Ability.STARSTRUCK);
            Location at = target.isValid() ? target.getLocation() : sky;
            plugin.fx().sound(at, "starstruck");
            Fx.View view = plugin.fx().view(at);
            view.particle(Fx.FIREWORK, at.clone().add(0, 1, 0), 15, 0.3, 0.3, 0.3, 0.15);
            view.particle(Fx.END_ROD, at.clone().add(0, 1, 0), 8, 0.2, 0.2, 0.2, 0.12);
            view.fade(Shapes.ring(at.clone().add(0, 0.15, 0), settings.num("radius"), 18, 0), GOLD, SKY, 1.1f);
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
