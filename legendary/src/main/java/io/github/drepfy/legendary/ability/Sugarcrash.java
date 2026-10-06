package io.github.drepfy.legendary.ability;

import io.github.drepfy.legendary.Ability;
import io.github.drepfy.legendary.LegendaryPlugin;
import io.github.drepfy.legendary.WeaponType;
import io.github.drepfy.legendary.config.AbilitySettings;
import io.github.drepfy.legendary.item.WeaponItems;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Sugarcrash, the candy war-scythe: mobility and burst tempo.
 * <ul>
 *   <li><b>Sugar Rush</b>: a candy-streaked dash that bowls through players, then a burst of
 *   speed and faster swings.</li>
 *   <li><b>Candy Cyclone</b>: the scythe spins into a candy-striped tornado around the player,
 *   dragging nearby players in and shredding them, then bursts outwards.</li>
 * </ul>
 */
final class Sugarcrash implements Kit {

    private static final Color PINK = Color.fromRGB(255, 92, 165);
    private static final Color CANDY_RED = Color.fromRGB(225, 30, 60);
    private static final Color SUGAR = Color.fromRGB(255, 245, 250);

    private final LegendaryPlugin plugin;
    private final Map<UUID, Rush> rushes = new HashMap<>();
    /** Caster → the tick their speed burst ends (for the boss bar). */
    private final Map<UUID, Long> buffs = new HashMap<>();
    private final Map<UUID, Cyclone> cyclones = new HashMap<>();

    Sugarcrash(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    private static final class Rush {
        final Player player;
        final long until;
        final Vector direction;
        final Set<UUID> hit = new HashSet<>();

        Rush(Player player, long until, Vector direction) {
            this.player = player;
            this.until = until;
            this.direction = direction;
        }
    }

    private static final class Cyclone {
        final Player player;
        final long until;
        long nextHit;
        final List<Visuals.Effect> rings = new ArrayList<>();
        int turn;

        Cyclone(Player player, long until, long nextHit) {
            this.player = player;
            this.until = until;
            this.nextHit = nextHit;
        }
    }

    @Override
    public WeaponType type() {
        return WeaponType.SUGARCRASH;
    }

    @Override
    public Result use(Player player, WeaponItems.Tag weapon, Ability ability) {
        return ability == Ability.SUGAR_RUSH ? rush(player) : cyclone(player);
    }

    @Override
    public long active(Player player, WeaponItems.Tag weapon, Ability ability, long now) {
        Long until;
        if (ability == Ability.SUGAR_RUSH) {
            until = buffs.get(player.getUniqueId());
        } else {
            Cyclone cyclone = cyclones.get(player.getUniqueId());
            until = cyclone == null ? null : cyclone.until;
        }
        return until == null ? 0 : Math.max(0, until - now);
    }

    @Override
    public long activeLength(Ability ability) {
        AbilitySettings settings = plugin.settings().ability(ability);
        return ability == Ability.SUGAR_RUSH ? settings.ticks("dash-time") + settings.ticks("buff-duration")
                : settings.ticks("duration");
    }

    @Override
    public void forget(Player player) {
        rushes.remove(player.getUniqueId());
        buffs.remove(player.getUniqueId());
        Cyclone cyclone = cyclones.remove(player.getUniqueId());
        if (cyclone != null) {
            cyclone.rings.forEach(Visuals.Effect::remove);
        }
    }

    @Override
    public void tick(long now) {
        buffs.values().removeIf(until -> until <= now);
        for (Iterator<Rush> it = rushes.values().iterator(); it.hasNext(); ) {
            Rush rush = it.next();
            if (!rush.player.isOnline() || rush.player.isDead()) {
                it.remove();
                continue;
            }
            rushTick(rush, now);
            if (now >= rush.until) {
                it.remove();
                rushEnd(rush.player);
            }
        }
        for (Iterator<Cyclone> it = cyclones.values().iterator(); it.hasNext(); ) {
            Cyclone cyclone = it.next();
            if (!cyclone.player.isOnline() || cyclone.player.isDead()) {
                cyclone.rings.forEach(Visuals.Effect::remove);
                it.remove();
                continue;
            }
            cycloneTick(cyclone, now);
            if (now >= cyclone.until) {
                it.remove();
                burst(cyclone);
            }
        }
    }

    // ---- Sugar Rush --------------------------------------------------------------------------------------

    private Result rush(Player player) {
        AbilitySettings settings = plugin.settings().ability(Ability.SUGAR_RUSH);
        Vector direction = player.getLocation().getDirection();
        direction.setY(Math.max(-0.15, Math.min(0.3, direction.getY())));
        if (direction.lengthSquared() < 1.0E-6) {
            direction = Geo.flat(player.getLocation());
        }
        direction.normalize();
        Vector velocity = direction.clone().multiply(settings.num("dash-speed"));
        velocity.setY(velocity.getY() + 0.18);
        player.setVelocity(velocity);
        player.setFallDistance(0f);
        long now = plugin.tick();
        rushes.put(player.getUniqueId(), new Rush(player, now + settings.ticks("dash-time"), direction));
        buffs.put(player.getUniqueId(), now + settings.ticks("dash-time") + settings.ticks("buff-duration"));
        plugin.fx().sound(player.getLocation(), "sugar-rush");
        plugin.visuals().spawn("candy_burst", player.getLocation().add(0, 0.08, 0)).size(0.5).send(0)
                .animate(1, 5, e -> e.size(3.5).turn(90))
                .vanish(6, 4);
        return Result.FIRED;
    }

    private void rushTick(Rush rush, long now) {
        Player player = rush.player;
        Location at = player.getLocation().add(0, 0.9, 0);
        Fx.View view = plugin.fx().view(at);
        view.dust(at, now % 2 == 0 ? PINK : SUGAR, 1.4f, 6, 0.35);
        view.dust(at, CANDY_RED, 1.0f, 3, 0.3);
        view.particle(Fx.FIREWORK, at, 2, 0.2, 0.2, 0.2, 0.02);
        if (now % 2 == 0) {
            plugin.visuals().spawn("sprinkles", at).billboard().size(0.9).send(0)
                    .animate(1, 6, e -> e.size(0.2).offset(0, -0.4, 0))
                    .life(8);
        }
        AbilitySettings settings = plugin.settings().ability(Ability.SUGAR_RUSH);
        for (LivingEntity target : plugin.hits().around(player, at, 1.4)) {
            if (!rush.hit.add(target.getUniqueId())) {
                continue;
            }
            if (plugin.hits().hurt(player, target, settings.num("damage"))) {
                // Bowled aside: away from the dash line, and a little forward.
                Vector away = Geo.away(player.getLocation(), target.getLocation(), Geo.right(Geo.flat(player.getLocation())));
                Vector push = away.multiply(0.8).add(rush.direction.clone().setY(0).multiply(0.4));
                Hits.knock(target, push, settings.num("knockback"), settings.num("lift"));
                plugin.fx().sound(target.getLocation(), "sugarcrash-hit");
                plugin.visuals().spawn("candy_burst", Geo.middle(target)).billboard().size(0.3).send(0)
                        .animate(1, 3, e -> e.size(1.6))
                        .vanish(4, 3);
            }
        }
    }

    private void rushEnd(Player player) {
        AbilitySettings settings = plugin.settings().ability(Ability.SUGAR_RUSH);
        int ticks = settings.ticks("buff-duration");
        Hits.effect(player, "speed", settings.whole("speed-level"), ticks);
        Hits.effect(player, "haste", settings.whole("haste-level"), ticks);
        plugin.fx().sound(player.getLocation(), "sugar-rush-end");
    }

    // ---- Candy Cyclone -------------------------------------------------------------------------------------

    private Result cyclone(Player player) {
        AbilitySettings settings = plugin.settings().ability(Ability.CANDY_CYCLONE);
        long now = plugin.tick();
        int duration = settings.ticks("duration");
        Cyclone cyclone = new Cyclone(player, now + duration, now + 1);
        double radius = settings.num("radius");
        // Three candy rings stacked into a tornado, each spinning its own way.
        double[][] rings = {{0.25, radius * 2.0}, {1.05, radius * 1.6}, {1.85, radius * 1.15}};
        for (double[] ring : rings) {
            Vector offset = new Vector(0, ring[0], 0);
            Visuals.Effect effect = plugin.visuals().spawn("candy_ring", player.getLocation().add(offset))
                    .size(0.4).send(0)
                    .animate(1, 5, e -> e.size(ring[1]))
                    .follow(player, offset, duration);
            cyclone.rings.add(effect);
        }
        cyclones.put(player.getUniqueId(), cyclone);
        plugin.fx().sound(player.getLocation(), "candy-cyclone");
        return Result.FIRED;
    }

    private void cycloneTick(Cyclone cyclone, long now) {
        Player player = cyclone.player;
        if (now % 4 == 0) {
            cyclone.turn += 1;
            for (int i = 0; i < cyclone.rings.size(); i++) {
                int sign = i % 2 == 0 ? 1 : -1;
                double angle = sign * cyclone.turn * 100.0;
                cyclone.rings.get(i).turn(angle).send(4);
            }
        }
        AbilitySettings settings = plugin.settings().ability(Ability.CANDY_CYCLONE);
        double radius = settings.num("radius");
        Location center = player.getLocation().add(0, 1.0, 0);
        Fx.View view = plugin.fx().view(center);
        double spin = now * 0.6;
        for (int i = 0; i < 3; i++) {
            double a = spin + i * (Math.PI * 2 / 3);
            Location point = center.clone().add(Math.cos(a) * radius * 0.8, (i - 1) * 0.6, Math.sin(a) * radius * 0.8);
            view.dust(point, i == 0 ? PINK : i == 1 ? SUGAR : CANDY_RED, 1.5f, 2, 0.1);
        }
        if (now < cyclone.nextHit) {
            return;
        }
        cyclone.nextHit = now + settings.ticks("interval");
        for (LivingEntity target : plugin.hits().around(player, center, radius)) {
            Vector in = player.getLocation().toVector().subtract(target.getLocation().toVector()).setY(0);
            if (plugin.hits().hurt(player, target, settings.num("damage"))) {
                if (in.lengthSquared() > 0.25) {
                    Vector pull = in.normalize().multiply(settings.num("pull"));
                    target.setVelocity(target.getVelocity().multiply(0.5).add(pull.setY(0.05)));
                }
                plugin.fx().sound(target.getLocation(), "candy-cyclone-hit");
                view.dust(Geo.middle(target), PINK, 1.2f, 5, 0.25);
            }
        }
    }

    private void burst(Cyclone cyclone) {
        Player player = cyclone.player;
        AbilitySettings settings = plugin.settings().ability(Ability.CANDY_CYCLONE);
        double radius = settings.num("radius") + 0.5;
        for (Visuals.Effect ring : cyclone.rings) {
            ring.animate(1, 3, e -> e.size(radius * 2.6)).vanish(4, 3);
        }
        Location center = player.getLocation().add(0, 1.0, 0);
        plugin.fx().sound(center, "candy-burst");
        plugin.visuals().spawn("candy_burst", player.getLocation().add(0, 0.1, 0)).size(1.0).send(0)
                .animate(1, 5, e -> e.size(radius * 2.4).turn(180))
                .vanish(6, 4);
        Fx.View view = plugin.fx().view(center);
        view.particle(Fx.FIREWORK, center, 40, radius / 2, 0.6, radius / 2, 0.15);
        view.dust(center, PINK, 2.0f, 40, radius / 2);
        if (settings.num("burst-damage") <= 0) {
            return;
        }
        for (LivingEntity target : plugin.hits().around(player, center, radius)) {
            if (plugin.hits().hurt(player, target, settings.num("burst-damage"))) {
                Vector away = Geo.away(player.getLocation(), target.getLocation(), Geo.flat(player.getLocation()));
                Hits.knock(target, away, settings.num("burst-knockback"), settings.num("burst-lift"));
            }
        }
    }
}
