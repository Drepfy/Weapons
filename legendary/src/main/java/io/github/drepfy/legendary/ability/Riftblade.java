package io.github.drepfy.legendary.ability;

import io.github.drepfy.legendary.Ability;
import io.github.drepfy.legendary.LegendaryPlugin;
import io.github.drepfy.legendary.WeaponType;
import io.github.drepfy.legendary.config.AbilitySettings;
import io.github.drepfy.legendary.item.WeaponItems;
import io.github.drepfy.legendary.util.Compat;
import io.github.drepfy.legendary.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Riftblade, the void sword: space and positioning.
 * <ul>
 *   <li><b>Rift Slash</b>: a rift travels forward along the ground, hurting, throwing back and
 *   briefly distorting the vision of whoever it passes through.</li>
 *   <li><b>Rift Recall</b>: mark a spot, then return to it within a few seconds. Everyone can
 *   see the mark, and blocking it collapses the rift.</li>
 * </ul>
 */
final class Riftblade implements Kit {

    private static final Color VOID = Color.fromRGB(20, 0, 30);
    private static final Color VIOLET = Color.fromRGB(170, 60, 255);

    private final LegendaryPlugin plugin;
    private final Map<UUID, Mark> marks = new HashMap<>();

    Riftblade(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    private record Mark(UUID weapon, Location at, long expires) {
    }

    @Override
    public WeaponType type() {
        return WeaponType.RIFTBLADE;
    }

    @Override
    public Result use(Player player, WeaponItems.Tag weapon, Ability ability) {
        return ability == Ability.RIFT_SLASH ? slash(player) : recall(player, weapon);
    }

    // ---- Rift Slash ----------------------------------------------------------------------------------------

    private Result slash(Player player) {
        AbilitySettings settings = plugin.settings().ability(Ability.RIFT_SLASH);
        Vector direction = Geo.flat(player.getLocation());
        Location origin = player.getLocation().add(direction.clone().multiply(0.5));
        plugin.fx().sound(player.getLocation(), "rift-slash");
        new Tear(player, origin, direction, settings).runTaskTimer(plugin, 0L, 1L);
        return Result.FIRED;
    }

    /** The rift: a standing tear in space moving forward. */
    private final class Tear extends BukkitRunnable {
        private final Player player;
        private final Location origin;
        private final Vector direction;
        private final Vector right;
        private final AbilitySettings settings;
        private final double halfWidth;
        private final double height;
        private final Set<UUID> hit = new HashSet<>();
        private double travelled;

        Tear(Player player, Location origin, Vector direction, AbilitySettings settings) {
            this.player = player;
            this.origin = origin;
            this.direction = direction;
            this.right = Geo.right(direction);
            this.settings = settings;
            this.halfWidth = settings.num("width") / 2.0;
            this.height = settings.num("height");
        }

        @Override
        public void run() {
            if (!player.isOnline()) {
                cancel();
                return;
            }
            Location base = null;
            for (int step = 0; step < 2; step++) {
                travelled += settings.num("speed") / 2.0;
                base = origin.clone().add(direction.clone().multiply(travelled));
                if (travelled > settings.num("range") || Geo.solid(base.clone().add(0, 1.0, 0))) {
                    collapse(base);
                    cancel();
                    return;
                }
                strike(base);
            }
            draw(base);
        }

        private void strike(Location base) {
            Location middle = base.clone().add(0, height / 2.0, 0);
            BoundingBox box = BoundingBox.of(middle, halfWidth, height / 2.0, halfWidth);
            for (Entity entity : base.getWorld().getNearbyEntities(box)) {
                if (!(entity instanceof LivingEntity target) || hit.contains(entity.getUniqueId())
                        || !plugin.hits().canTarget(player, entity)) {
                    continue;
                }
                double along = entity.getLocation().toVector().subtract(base.toVector()).dot(direction);
                if (Math.abs(along) > 0.9) {
                    continue;
                }
                hit.add(entity.getUniqueId());
                if (plugin.hits().hurt(player, target, settings.num("damage"))) {
                    Hits.knock(target, direction, settings.num("knockback"), settings.num("lift"));
                    int distortion = settings.ticks("distortion");
                    PotionEffectType nausea = Compat.effect("nausea");
                    if (distortion > 0 && nausea != null) {
                        target.addPotionEffect(new PotionEffect(nausea, distortion, 0, false, false, true));
                    }
                    plugin.fx().sound(target.getLocation(), "rift-hit");
                    Fx.View view = plugin.fx().view(target.getLocation());
                    view.particle(Fx.REVERSE_PORTAL, Geo.middle(target), 25, 0.3, 0.5, 0.3, 0.05);
                    view.particle(Fx.INK, Geo.middle(target), 4, 0.2, 0.3, 0.2, 0.02);
                }
            }
        }

        /** A jagged vertical tear: black core, violet edges. */
        private void draw(Location base) {
            Fx.View view = plugin.fx().view(base);
            for (double h = 0.1; h <= height; h += 0.35) {
                double jitter = Math.sin((h + travelled) * 3.1) * 0.12;
                for (double w = -halfWidth; w <= halfWidth + 1.0E-6; w += 0.42) {
                    Location point = base.clone().add(right.clone().multiply(w + jitter)).add(0, h, 0);
                    boolean edge = Math.abs(w) > halfWidth - 0.45 || h < 0.3 || h > height - 0.35;
                    view.dust(point, edge ? VIOLET : VOID, edge ? 1.0f : 1.4f, 1, 0.02);
                }
            }
            Location middle = base.clone().add(0, height / 2.0, 0);
            view.particle(Fx.REVERSE_PORTAL, middle, 10, halfWidth / 2, height / 3, 0.1, 0.02);
            view.particle(Fx.PORTAL, middle, 8, halfWidth / 2, height / 3, 0.1, 0.4);
        }

        private void collapse(Location base) {
            if (base != null) {
                plugin.fx().view(base).particle(Fx.REVERSE_PORTAL, base.clone().add(0, 1, 0), 30, 0.4, 0.6, 0.4, 0.1);
            }
        }
    }

    // ---- Rift Recall ---------------------------------------------------------------------------------------

    private Result recall(Player player, WeaponItems.Tag weapon) {
        AbilitySettings settings = plugin.settings().ability(Ability.RIFT_RECALL);
        long now = plugin.tick();
        Mark mark = marks.get(player.getUniqueId());
        if (mark != null && (!mark.weapon().equals(weapon.id()) || !mark.at().getWorld().equals(player.getWorld()))) {
            fade(player, mark);
            mark = null;
        }
        if (mark == null) {
            int window = settings.ticks("window");
            Location at = player.getLocation().clone();
            marks.put(player.getUniqueId(), new Mark(weapon.id(), at, now + window));
            plugin.fx().sound(at, "rift-mark");
            plugin.fx().view(at).particle(Fx.REVERSE_PORTAL, at.clone().add(0, 1, 0), 40, 0.3, 0.8, 0.3, 0.05);
            plugin.hud().flash(player, Text.format(plugin.settings().message("rift-marked"), "time",
                    Text.countdown(window)));
            return Result.HANDLED; // The cooldown starts once the mark is used or fades.
        }
        double distance = player.getLocation().distance(mark.at());
        double max = settings.num("max-distance");
        if (distance > max) {
            plugin.hud().flash(player, Text.format(plugin.settings().message("rift-too-far"), "distance",
                    Math.round(distance), "max", Text.number(max)));
            return Result.HANDLED;
        }
        if (Geo.solid(mark.at().clone().add(0, 0.1, 0)) || Geo.solid(mark.at().clone().add(0, 1.2, 0))) {
            marks.remove(player.getUniqueId());
            plugin.abilities().cooldowns().start(weapon.id(), Ability.RIFT_RECALL, now, settings.ticks("cooldown"));
            plugin.fx().view(mark.at()).particle(Fx.INK, mark.at().clone().add(0, 1, 0), 20, 0.3, 0.5, 0.3, 0.05);
            plugin.hud().flash(player, Text.color(plugin.settings().message("rift-blocked")));
            return Result.HANDLED;
        }
        Location from = player.getLocation();
        Location to = mark.at().clone();
        to.setYaw(from.getYaw());
        to.setPitch(from.getPitch());
        if (player.isInsideVehicle()) {
            player.leaveVehicle();
        }
        // A plugin teleport: spawn protection, combat safe zones and region plugins can refuse it.
        if (!player.teleport(to, PlayerTeleportEvent.TeleportCause.PLUGIN)) {
            plugin.hud().flash(player, Text.color(plugin.settings().message("rift-failed")));
            return Result.HANDLED;
        }
        player.setFallDistance(0f);
        player.setVelocity(new Vector());
        marks.remove(player.getUniqueId());
        plugin.abilities().cooldowns().start(weapon.id(), Ability.RIFT_RECALL, now, settings.ticks("cooldown"));
        for (Location at : new Location[] {from, to}) {
            plugin.fx().sound(at, "rift-recall");
            Fx.View view = plugin.fx().view(at);
            view.particle(Fx.REVERSE_PORTAL, at.clone().add(0, 1, 0), 50, 0.3, 0.8, 0.3, 0.1);
            view.dust(at.clone().add(0, 1, 0), VIOLET, 1.5f, 12, 0.4);
        }
        return Result.HANDLED;
    }

    private void fade(Player player, Mark mark) {
        marks.remove(player.getUniqueId());
        AbilitySettings settings = plugin.settings().ability(Ability.RIFT_RECALL);
        plugin.abilities().cooldowns().atLeast(mark.weapon(), Ability.RIFT_RECALL, plugin.tick(),
                settings.ticks("cooldown"));
        if (player.isOnline()) {
            plugin.send(player, "rift-faded");
        }
    }

    @Override
    public long active(Player player, WeaponItems.Tag weapon, Ability ability, long now) {
        if (ability != Ability.RIFT_RECALL) {
            return 0;
        }
        Mark mark = marks.get(player.getUniqueId());
        return mark != null && mark.weapon().equals(weapon.id()) ? Math.max(0, mark.expires() - now) : 0;
    }

    @Override
    public void tick(long now) {
        Iterator<Map.Entry<UUID, Mark>> it = marks.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Mark> entry = it.next();
            Mark mark = entry.getValue();
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || now >= mark.expires() || !mark.at().getWorld().equals(player.getWorld())) {
                it.remove();
                AbilitySettings settings = plugin.settings().ability(Ability.RIFT_RECALL);
                plugin.abilities().cooldowns().atLeast(mark.weapon(), Ability.RIFT_RECALL, now,
                        settings.ticks("cooldown"));
                if (player != null) {
                    plugin.send(player, "rift-faded");
                }
                continue;
            }
            if (now % 5 == 0) {
                // Everyone can see where the rift will return its owner.
                Location at = mark.at();
                Fx.View view = plugin.fx().view(at);
                double spin = now * 0.3;
                for (int i = 0; i < 6; i++) {
                    double angle = spin + Math.PI * 2 * i / 6;
                    view.dust(at.clone().add(Math.cos(angle) * 0.5, 0.1 + i * 0.3, Math.sin(angle) * 0.5),
                            i % 2 == 0 ? VIOLET : VOID, 1.1f, 1, 0.01);
                }
                view.particle(Fx.REVERSE_PORTAL, at.clone().add(0, 1, 0), 6, 0.2, 0.7, 0.2, 0.01);
            }
        }
    }

    @Override
    public void forget(Player player) {
        Mark mark = marks.get(player.getUniqueId());
        if (mark != null) {
            fade(player, mark);
        }
    }
}
