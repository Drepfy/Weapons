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
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
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
 * Starforged, the star-forged double axe: a lance from the sky, and a cage of stars.
 * <ul>
 *   <li><b>Star Lance</b>: a lance of starlight is hurled where you look and pierces the first
 *   enemy it reaches: hurt, pinned in place for a moment and left glowing.</li>
 *   <li><b>Celestial Prison</b>: a ring of stars closes round you. Everyone caught inside cannot get
 *   out (walking, Ender Pearls and chorus fruit are stopped); then the stars collapse on them.</li>
 *   <li><b>Starlight</b> (passive): axe hits on enemies in the air hit harder.</li>
 * </ul>
 */
final class Starforged implements Kit, Listener {

    private static final Color STAR_GOLD = Color.fromRGB(255, 220, 110);
    private static final Color SKY = Color.fromRGB(120, 200, 255);
    private static final int STARS = 8;

    private final LegendaryPlugin plugin;
    private final Map<UUID, Lance> lances = new HashMap<>();
    private final Map<UUID, Prison> prisons = new HashMap<>();

    Starforged(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    private static final class Lance {
        final Player player;
        final Vector direction;
        final Visuals.Effect star;
        /** Reached but could not be hurt (a no-PvP area): the lance flies on past them. */
        final Set<UUID> spared = new HashSet<>();
        Location at;
        double travelled;

        Lance(Player player, Location at, Vector direction, Visuals.Effect star) {
            this.player = player;
            this.at = at;
            this.direction = direction;
            this.star = star;
        }
    }

    private static final class Prison {
        final Player player;
        final Location center;
        final double radius;
        final long until;
        final List<LivingEntity> trapped;
        final Visuals.Effect rune;
        final List<Visuals.Effect> stars;

        Prison(Player player, Location center, double radius, long until, List<LivingEntity> trapped, Visuals.Effect rune,
               List<Visuals.Effect> stars) {
            this.player = player;
            this.center = center;
            this.radius = radius;
            this.until = until;
            this.trapped = trapped;
            this.rune = rune;
            this.stars = stars;
        }
    }

    @Override
    public WeaponType type() {
        return WeaponType.STARFORGED;
    }

    @Override
    public Result use(Player player, WeaponItems.Tag weapon, Ability ability) {
        return ability == Ability.CELESTIAL_PRISON ? imprison(player) : hurl(player);
    }

    @Override
    public long active(Player player, WeaponItems.Tag weapon, Ability ability, long now) {
        Prison prison = ability == Ability.CELESTIAL_PRISON ? prisons.get(player.getUniqueId()) : null;
        return prison == null ? 0 : Math.max(0, prison.until - now);
    }

    @Override
    public long activeLength(Ability ability) {
        return ability == Ability.CELESTIAL_PRISON ? plugin.settings().ability(ability).ticks("duration") : 0;
    }

    @Override
    public void forget(Player player) {
        Lance lance = lances.remove(player.getUniqueId());
        if (lance != null) {
            lance.star.remove();
        }
        Prison prison = prisons.remove(player.getUniqueId());
        if (prison != null) {
            prison.rune.remove();
            prison.stars.forEach(Visuals.Effect::remove);
        }
        for (Prison other : prisons.values()) {
            other.trapped.remove(player);
        }
    }

    @Override
    public void tick(long now) {
        for (Iterator<Lance> it = lances.values().iterator(); it.hasNext(); ) {
            Lance lance = it.next();
            if (!lance.player.isOnline() || lance.player.isDead() || lance.player.getWorld() != lance.at.getWorld()
                    || fly(lance)) {
                lance.star.vanish(0, 3);
                it.remove();
            }
        }
        for (Iterator<Prison> it = prisons.values().iterator(); it.hasNext(); ) {
            Prison prison = it.next();
            if (!prison.player.isOnline() || prison.player.isDead()) {
                prison.rune.remove();
                prison.stars.forEach(Visuals.Effect::remove);
                it.remove();
            } else if (now >= prison.until) {
                it.remove();
                collapse(prison);
            } else {
                hold(prison, now);
            }
        }
    }

    // ---- Star Lance ------------------------------------------------------------------------------------------

    private Result hurl(Player player) {
        Lance old = lances.remove(player.getUniqueId());
        if (old != null) {
            old.star.remove();
        }
        Location eye = player.getEyeLocation();
        Vector direction = eye.getDirection().normalize();
        Location start = eye.clone().add(direction.clone().multiply(1.0));
        Visuals.Effect star = plugin.visuals().spawn("star", start).billboard().size(0.4).send(0)
                .animate(1, 2, e -> e.size(1.3));
        lances.put(player.getUniqueId(), new Lance(player, start, direction, star));
        plugin.fx().sound(player.getLocation(), "star-lance");
        return Result.FIRED;
    }

    /** One tick of flight; true when it is over (it struck, hit a wall, or went its full range). */
    private boolean fly(Lance lance) {
        AbilitySettings settings = plugin.settings().ability(Ability.STAR_LANCE);
        double speed = settings.num("speed");
        double range = settings.num("range");
        Fx.View view = plugin.fx().view(lance.at);
        for (double step = 0; step < speed - 1.0E-6; step += 0.5) {
            double length = Math.min(0.5, speed - step);
            Location next = lance.at.clone().add(lance.direction.clone().multiply(length));
            if (Geo.solid(next)) {
                burst(lance.at, 0.8);
                return true;
            }
            List<LivingEntity> struck = plugin.hits().along(lance.player, lance.at, next, 0.7);
            lance.at = next;
            lance.travelled += length;
            view.dust(next, step % 1.0 < 0.5 ? STAR_GOLD : SKY, 1.3f, 2, 0.08);
            for (LivingEntity target : struck) {
                if (lance.spared.add(target.getUniqueId()) && strike(lance.player, target)) {
                    return true;
                }
            }
            if (lance.travelled >= range) {
                burst(lance.at, 0.6);
                return true;
            }
        }
        lance.star.moveTo(lance.at, 1);
        view.particle(Fx.END_ROD, lance.at, 2, 0.05, 0.05, 0.05, 0.01);
        return false;
    }

    /** @return whether the lance struck (false when the target could not be hurt there) */
    private boolean strike(Player player, LivingEntity target) {
        AbilitySettings settings = plugin.settings().ability(Ability.STAR_LANCE);
        if (!plugin.hits().hurt(player, target, settings.num("damage"))) {
            return false;
        }
        burst(Geo.middle(target), 1.6);
        Hits.effect(target, "slowness", settings.whole("slow-level"), settings.ticks("slow-duration"));
        Hits.effect(target, "glowing", 1, settings.ticks("glow"));
        target.setVelocity(new Vector(0, Math.min(0.0, target.getVelocity().getY()), 0)); // Pinned.
        return true;
    }

    private void burst(Location at, double size) {
        plugin.visuals().spawn("nova", at).billboard().size(0.3).send(0)
                .animate(1, 3, e -> e.size(size * 2.2))
                .vanish(6, 5);
        Fx.View view = plugin.fx().view(at);
        view.particle(Fx.FIREWORK, at, 16, 0.2, 0.2, 0.2, 0.15);
        view.dust(at, STAR_GOLD, 1.6f, 12, 0.3);
    }

    // ---- Celestial Prison ------------------------------------------------------------------------------------

    private Result imprison(Player player) {
        AbilitySettings settings = plugin.settings().ability(Ability.CELESTIAL_PRISON);
        Location center = player.getLocation();
        double radius = settings.num("radius");
        int ticks = settings.ticks("duration");
        List<LivingEntity> trapped = new ArrayList<>();
        for (LivingEntity target : plugin.hits().around(player, center.clone().add(0, 1.0, 0), radius)) {
            if (plugin.hits().allowed(player, target)) {
                trapped.add(target);
            }
        }
        Visuals.Effect rune = plugin.visuals().spawn("rune_star", center.clone().add(0, 0.06, 0)).size(0.5).send(0)
                .animate(1, 5, e -> e.size(radius * 2.0));
        List<Visuals.Effect> stars = new ArrayList<>();
        for (int i = 0; i < STARS; i++) {
            stars.add(plugin.visuals().spawn("star", ring(center, radius, i, 0)).billboard().size(0.2).send(0)
                    .animate(1, 4, e -> e.size(1.1)));
        }
        Prison old = prisons.put(player.getUniqueId(), new Prison(player, center.clone(), radius, plugin.tick() + ticks,
                trapped, rune, stars));
        if (old != null) {
            old.rune.remove();
            old.stars.forEach(Visuals.Effect::remove);
        }
        plugin.fx().sound(center, "celestial-prison");
        return Result.FIRED;
    }

    /** Where star {@code i} of the ring stands, turned {@code degrees} round the centre. */
    private static Location ring(Location center, double radius, int i, double degrees) {
        double angle = Math.toRadians(degrees + i * 360.0 / STARS);
        return center.clone().add(Math.cos(angle) * radius, 1.2, Math.sin(angle) * radius);
    }

    /** One tick of the prison: whoever is trapped is pushed back in, and the stars circle. */
    private void hold(Prison prison, long now) {
        for (Iterator<LivingEntity> it = prison.trapped.iterator(); it.hasNext(); ) {
            LivingEntity target = it.next();
            if (!target.isValid() || target.isDead() || target.getWorld() != prison.center.getWorld()) {
                it.remove();
                continue;
            }
            double distance = Geo.flatDistance(target.getLocation(), prison.center);
            if (distance > prison.radius - 0.7) {
                Vector in = prison.center.toVector().subtract(target.getLocation().toVector()).setY(0);
                if (in.lengthSquared() > 1.0E-4) {
                    target.setVelocity(in.normalize().multiply(0.55).setY(0.1));
                }
                Location edge = target.getLocation().add(0, 1.0, 0);
                plugin.fx().view(edge).dust(edge, SKY, 1.4f, 6, 0.3);
            }
        }
        if (now % 2 == 0) {
            double degrees = (now % 360) * 6.0;
            for (int i = 0; i < prison.stars.size(); i++) {
                prison.stars.get(i).moveTo(ring(prison.center, prison.radius, i, degrees), 2);
            }
        }
        if (now % 4 == 0) {
            Fx.View view = plugin.fx().view(prison.center);
            for (int i = 0; i < 12; i++) {
                double angle = Math.toRadians(i * 30 + now * 3);
                Location point = prison.center.clone().add(Math.cos(angle) * prison.radius, 0.3,
                        Math.sin(angle) * prison.radius);
                view.dust(point, i % 2 == 0 ? STAR_GOLD : SKY, 1.2f, 1, 0.05);
            }
        }
    }

    /** The prison closes: the stars fall in, and everyone inside is struck and thrown up. */
    private void collapse(Prison prison) {
        AbilitySettings settings = plugin.settings().ability(Ability.CELESTIAL_PRISON);
        Location core = prison.center.clone().add(0, 1.2, 0);
        for (Visuals.Effect star : prison.stars) {
            star.moveTo(core, 6);
            star.vanish(6, 4);
        }
        prison.rune.animate(1, 6, e -> e.size(0.3)).life(8);
        plugin.visuals().later(6, () -> {
            plugin.fx().sound(core, "celestial-prison-collapse");
            burst(core, prison.radius * 0.9);
            if (!prison.player.isOnline()) {
                return;
            }
            for (LivingEntity target : plugin.hits().around(prison.player, core, prison.radius + 0.5)) {
                if (plugin.hits().hurt(prison.player, target, settings.num("damage"))) {
                    Hits.knock(target, Geo.away(prison.center, target.getLocation(), new Vector(0, 0, 1)), 0.3,
                            settings.num("launch"));
                }
            }
        });
    }

    /** Ender Pearls and chorus fruit cannot get anyone out of a prison. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        PlayerTeleportEvent.TeleportCause cause = event.getCause();
        if (cause != PlayerTeleportEvent.TeleportCause.ENDER_PEARL && !cause.name().equals("CONSUMABLE_EFFECT")
                && !cause.name().equals("CHORUS_FRUIT")) {
            return;
        }
        for (Prison prison : prisons.values()) {
            if (prison.trapped.contains(event.getPlayer()) && event.getTo() != null
                    && (event.getTo().getWorld() != prison.center.getWorld()
                    || Geo.flatDistance(event.getTo(), prison.center) > prison.radius)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    // ---- Starlight (passive) ---------------------------------------------------------------------------------

    @Override
    @SuppressWarnings("deprecation") // isOnGround(): for players it is what their game says, which is all there is.
    public void melee(EntityDamageByEntityEvent event, Player attacker, LivingEntity target, WeaponItems.Tag weapon) {
        if (target.isOnGround()) {
            return;
        }
        event.setDamage(event.getDamage() * (1.0 + plugin.settings().ability(Ability.STARLIGHT).num("bonus")));
        Location at = Geo.middle(target);
        Fx.View view = plugin.fx().view(at);
        view.dust(at, STAR_GOLD, 1.3f, 8, 0.35);
        view.particle(Fx.END_ROD, at, 4, 0.2, 0.2, 0.2, 0.05);
    }
}
