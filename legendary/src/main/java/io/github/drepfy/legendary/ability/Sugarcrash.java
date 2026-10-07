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
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Sugarcrash, the candy scythe: reach, speed and a scythe that comes back.
 * <ul>
 *   <li><b>Candy Reaper</b>: the scythe is thrown spinning where you look, cuts everything it
 *   passes, and flies back to you, dragging whoever it catches on the way back.</li>
 *   <li><b>Sugar Rush</b>: for a few seconds you streak through the air where you look as a
 *   whirl of candy. Arrows bounce off, everyone you ram is hurt and thrown aside, and it ends in
 *   a candy burst. Press again to burst early.</li>
 *   <li><b>Sugar High</b> (passive): Speed while the scythe is in your hand, and its hits slow
 *   the target down.</li>
 * </ul>
 */
final class Sugarcrash implements Kit, Listener {

    private static final Color PINK = Color.fromRGB(255, 92, 165);
    private static final Color CANDY_RED = Color.fromRGB(225, 30, 60);
    private static final Color SUGAR = Color.fromRGB(255, 245, 250);
    /** A thrown scythe gives up coming back after this long (it then just vanishes). */
    private static final int MAX_FLIGHT = 100;

    private final LegendaryPlugin plugin;
    private final Map<UUID, Thrown> thrown = new HashMap<>();
    private final Map<UUID, Rush> rushes = new HashMap<>();
    /** Players given Sugar High's Speed (so it is only taken away from them). */
    private final Set<UUID> sped = new HashSet<>();

    Sugarcrash(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    private static final class Thrown {
        final Player player;
        final Vector direction;
        final Visuals.Effect blade;
        final Set<UUID> hitOut = new HashSet<>();
        final Set<UUID> hitBack = new HashSet<>();
        Location at;
        double travelled;
        boolean returning;
        int age;

        Thrown(Player player, Location at, Vector direction, Visuals.Effect blade) {
            this.player = player;
            this.at = at;
            this.direction = direction;
            this.blade = blade;
        }
    }

    private static final class Rush {
        final Player player;
        final long until;
        final List<Visuals.Effect> rings;
        final Set<UUID> rammed = new HashSet<>();

        Rush(Player player, long until, List<Visuals.Effect> rings) {
            this.player = player;
            this.until = until;
            this.rings = rings;
        }
    }

    @Override
    public WeaponType type() {
        return WeaponType.SUGARCRASH;
    }

    @Override
    public Result use(Player player, WeaponItems.Tag weapon, Ability ability) {
        return ability == Ability.SUGAR_RUSH ? rush(player) : reap(player);
    }

    @Override
    public boolean recast(Player player, WeaponItems.Tag weapon, Ability ability) {
        Rush rush = ability == Ability.SUGAR_RUSH ? rushes.remove(player.getUniqueId()) : null;
        if (rush == null) {
            return false;
        }
        end(rush); // Pressed again: burst now.
        return true;
    }

    @Override
    public long active(Player player, WeaponItems.Tag weapon, Ability ability, long now) {
        if (ability == Ability.SUGAR_RUSH) {
            Rush rush = rushes.get(player.getUniqueId());
            return rush == null ? 0 : Math.max(0, rush.until - now);
        }
        return 0;
    }

    @Override
    public long activeLength(Ability ability) {
        return ability == Ability.SUGAR_RUSH ? plugin.settings().ability(ability).ticks("duration") : 0;
    }

    @Override
    public void forget(Player player) {
        Thrown scythe = thrown.remove(player.getUniqueId());
        if (scythe != null) {
            scythe.blade.remove();
        }
        Rush rush = rushes.remove(player.getUniqueId());
        if (rush != null) {
            rush.rings.forEach(Visuals.Effect::remove);
        }
    }

    @Override
    public void tick(long now) {
        for (Iterator<Thrown> it = thrown.values().iterator(); it.hasNext(); ) {
            Thrown scythe = it.next();
            if (!scythe.player.isOnline() || scythe.player.isDead() || scythe.player.getWorld() != scythe.at.getWorld()
                    || fly(scythe)) {
                scythe.blade.vanish(0, 3);
                it.remove();
            }
        }
        for (Iterator<Rush> it = rushes.values().iterator(); it.hasNext(); ) {
            Rush rush = it.next();
            if (!rush.player.isOnline() || rush.player.isDead()) {
                rush.rings.forEach(Visuals.Effect::remove);
                it.remove();
            } else if (now >= rush.until) {
                it.remove();
                end(rush);
            } else {
                streak(rush, now);
            }
        }
        if (now % 5 == 0) {
            sugarHigh();
        }
    }

    // ---- Candy Reaper ------------------------------------------------------------------------------------

    private Result reap(Player player) {
        Thrown old = thrown.remove(player.getUniqueId());
        if (old != null) {
            old.blade.remove();
        }
        Location eye = player.getEyeLocation();
        Vector direction = eye.getDirection().normalize();
        Location start = eye.clone().add(direction.clone().multiply(1.2)).subtract(0, 0.4, 0);
        String model = plugin.settings().look(WeaponType.SUGARCRASH).itemModel();
        Visuals.Effect blade = model.isEmpty() ? plugin.visuals().spawn("candy_burst", start)
                : plugin.visuals().model(model, start);
        blade.size(0.85).orient(spin(0)).send(0);
        thrown.put(player.getUniqueId(), new Thrown(player, start, direction, blade));
        plugin.fx().sound(player.getLocation(), "candy-reaper");
        return Result.FIRED;
    }

    /** Lying flat and turned {@code degrees} about the vertical: a scythe spinning like a boomerang. */
    private static Quaternionf spin(double degrees) {
        return new Quaternionf().rotateY((float) Math.toRadians(degrees)).rotateX((float) Math.toRadians(90));
    }

    /** One tick of flight; true when it is over. */
    private boolean fly(Thrown scythe) {
        AbilitySettings settings = plugin.settings().ability(Ability.CANDY_REAPER);
        scythe.age++;
        if (scythe.age > MAX_FLIGHT) {
            return true;
        }
        if (!scythe.returning) {
            double speed = settings.num("speed");
            Location next = scythe.at.clone().add(scythe.direction.clone().multiply(speed));
            if (Geo.solid(next) || scythe.travelled + speed > settings.num("range")) {
                scythe.returning = true; // A wall or the end of its reach: it turns back.
            } else {
                scythe.at = next;
                scythe.travelled += speed;
            }
        } else {
            Location home = scythe.player.getLocation().add(0, 1.0, 0);
            Vector back = home.toVector().subtract(scythe.at.toVector());
            double distance = back.length();
            double speed = settings.num("return-speed");
            if (distance <= speed + 0.8) {
                Location at = scythe.player.getLocation().add(0, 1.0, 0);
                plugin.fx().view(at).dust(at, PINK, 1.3f, 10, 0.4);
                return true; // Caught.
            }
            scythe.at = scythe.at.clone().add(back.multiply(speed / distance));
        }
        scythe.blade.orient(spin(scythe.age * 45.0)).send(1);
        scythe.blade.moveTo(scythe.at, 1);
        Fx.View view = plugin.fx().view(scythe.at);
        view.dust(scythe.at, scythe.age % 2 == 0 ? CANDY_RED : SUGAR, 1.4f, 3, 0.25);
        Set<UUID> already = scythe.returning ? scythe.hitBack : scythe.hitOut;
        for (LivingEntity target : plugin.hits().around(scythe.player, scythe.at, 1.3)) {
            if (!already.add(target.getUniqueId())) {
                continue;
            }
            if (plugin.hits().hurt(scythe.player, target, settings.num("damage"))) {
                Location at = Geo.middle(target);
                view.dust(at, PINK, 1.5f, 12, 0.35);
                view.particle(Fx.CRIT, at, 8, 0.3, 0.4, 0.3, 0.3);
                if (scythe.returning && settings.num("pull") > 0) {
                    Vector toward = scythe.player.getLocation().toVector().subtract(target.getLocation().toVector());
                    Hits.knock(target, toward, settings.num("pull"), 0.3);
                }
            }
        }
        return false;
    }

    // ---- Sugar Rush ----------------------------------------------------------------------------------------

    private Result rush(Player player) {
        AbilitySettings settings = plugin.settings().ability(Ability.SUGAR_RUSH);
        int ticks = settings.ticks("duration");
        List<Visuals.Effect> rings = new ArrayList<>();
        double[][] layout = {{0.3, 2.2}, {1.2, 1.7}};
        for (double[] ring : layout) {
            Vector offset = new Vector(0, ring[0], 0);
            rings.add(plugin.visuals().spawn("candy_ring", player.getLocation().add(offset))
                    .size(0.4).send(0)
                    .animate(1, 4, e -> e.size(ring[1]))
                    .follow(player, offset, ticks + 2));
        }
        rushes.put(player.getUniqueId(), new Rush(player, plugin.tick() + ticks, rings));
        plugin.abilities().softLanding(player, ticks + 60);
        plugin.fx().sound(player.getLocation(), "sugar-rush");
        return Result.FIRED;
    }

    /** One tick of the rush: carried along where the player looks, ramming whoever is in the way. */
    private void streak(Rush rush, long now) {
        Player player = rush.player;
        AbilitySettings settings = plugin.settings().ability(Ability.SUGAR_RUSH);
        Vector velocity = player.getLocation().getDirection().normalize().multiply(settings.num("speed"));
        velocity.setY(Math.max(-0.5, Math.min(0.6, velocity.getY())));
        player.setVelocity(velocity);
        player.setFallDistance(0f);
        if (now % 3 == 0) {
            for (int i = 0; i < rush.rings.size(); i++) {
                double angle = (i % 2 == 0 ? 1 : -1) * (now / 3) * 110.0;
                rush.rings.get(i).turn(angle).send(3);
            }
        }
        Location middle = Geo.middle(player);
        Fx.View view = plugin.fx().view(middle);
        view.dust(middle, now % 2 == 0 ? PINK : SUGAR, 1.6f, 4, 0.5);
        for (LivingEntity target : plugin.hits().around(player, middle, 1.7)) {
            if (!rush.rammed.add(target.getUniqueId())) {
                continue;
            }
            if (plugin.hits().hurt(player, target, settings.num("damage"))) {
                Vector aside = Geo.away(player.getLocation(), target.getLocation(), Geo.flat(player.getLocation()));
                Hits.knock(target, aside, settings.num("knockback"), 0.4);
                view.dust(Geo.middle(target), CANDY_RED, 1.5f, 12, 0.35);
            }
        }
    }

    /** The rush is over: the candy bursts. */
    private void end(Rush rush) {
        Player player = rush.player;
        AbilitySettings settings = plugin.settings().ability(Ability.SUGAR_RUSH);
        double radius = settings.num("burst-radius");
        for (Visuals.Effect ring : rush.rings) {
            ring.animate(1, 3, e -> e.size(radius * 2.4)).vanish(5, 5);
        }
        if (!player.isOnline()) {
            return;
        }
        Vector slow = player.getVelocity().multiply(0.3);
        player.setVelocity(slow);
        Location center = player.getLocation().add(0, 1.0, 0);
        plugin.visuals().spawn("candy_burst", player.getLocation().add(0, 0.1, 0)).size(1.0).send(0)
                .animate(1, 5, e -> e.size(Math.max(1.0, radius) * 2.4).turn(180))
                .vanish(9, 6);
        Fx.View view = plugin.fx().view(center);
        view.particle(Fx.FIREWORK, center, 30, radius / 2, 0.5, radius / 2, 0.15);
        view.dust(center, PINK, 2.0f, 30, Math.max(0.5, radius / 2));
        if (radius <= 0 || settings.num("burst-damage") <= 0) {
            return;
        }
        for (LivingEntity target : plugin.hits().around(player, center, radius)) {
            if (plugin.hits().hurt(player, target, settings.num("burst-damage"))) {
                Hits.knock(target, Geo.away(player.getLocation(), target.getLocation(), Geo.flat(player.getLocation())),
                        1.0, 0.45);
            }
        }
    }

    /** Arrows and other shots bounce off a player in a sugar rush. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onShot(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Projectile projectile && event.getEntity() instanceof Player player
                && rushes.containsKey(player.getUniqueId()) && !plugin.hits().probing()) {
            event.setCancelled(true);
            projectile.setVelocity(projectile.getVelocity().multiply(-0.6));
            plugin.fx().view(projectile.getLocation()).particle(Fx.CRIT, projectile.getLocation(), 8, 0.1, 0.1, 0.1, 0.2);
        }
    }

    // ---- Sugar High (passive) -------------------------------------------------------------------------------

    /** Speed while the scythe is in hand, gone soon after it is put away. */
    private void sugarHigh() {
        int level = plugin.settings().ability(Ability.SUGAR_HIGH).whole("speed-level");
        PotionEffectType speed = Compat.effect("speed");
        if (speed == null) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            WeaponItems.Tag held = player.isDead() ? null : plugin.items().read(player.getInventory().getItemInMainHand());
            if (level > 0 && held != null && held.type() == WeaponType.SUGARCRASH) {
                // Ambient and without swirls, like a beacon's: it is part of holding the scythe.
                player.addPotionEffect(new PotionEffect(speed, 40, level - 1, true, false, true));
                sped.add(player.getUniqueId());
            } else if (sped.remove(player.getUniqueId())) {
                PotionEffect current = player.getPotionEffect(speed);
                if (current != null && current.isAmbient() && current.getDuration() <= 40) {
                    player.removePotionEffect(speed);
                }
            }
        }
        sped.removeIf(id -> Bukkit.getPlayer(id) == null);
    }

    @Override
    public void landed(Player attacker, LivingEntity target, WeaponItems.Tag weapon) {
        AbilitySettings settings = plugin.settings().ability(Ability.SUGAR_HIGH);
        Hits.effect(target, "slowness", settings.whole("slow-level"), settings.ticks("slow-duration"));
        Location at = Geo.middle(target);
        plugin.fx().view(at).dust(at, PINK, 1.0f, 4, 0.3);
    }
}
