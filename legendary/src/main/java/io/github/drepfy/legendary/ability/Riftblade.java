package io.github.drepfy.legendary.ability;

import io.github.drepfy.legendary.Ability;
import io.github.drepfy.legendary.LegendaryPlugin;
import io.github.drepfy.legendary.WeaponType;
import io.github.drepfy.legendary.config.AbilitySettings;
import io.github.drepfy.legendary.item.WeaponItems;
import io.github.drepfy.legendary.util.Text;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Riftblade, the void sword: space and positioning.
 * <ul>
 *   <li><b>Void Rend</b>: tears a rift open a few blocks ahead. It drags nearby players towards
 *   it for a moment, then snaps shut, hurting and darkening everyone close to it.</li>
 *   <li><b>Rift Swap</b>: swaps places with the player (or monster) you look at, through the
 *   void. With nobody in sight, blinks forward instead.</li>
 * </ul>
 */
final class Riftblade implements Kit {

    private static final Color VIOLET = Color.fromRGB(165, 80, 255);
    private static final Color VOID = Color.fromRGB(30, 8, 60);

    private final LegendaryPlugin plugin;
    private final Map<UUID, Rend> rends = new HashMap<>();

    Riftblade(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    private static final class Rend {
        final Player player;
        final Location center;
        final long snap;
        final Visuals.Effect rift;
        final Set<UUID> allowed = new HashSet<>();
        final Set<UUID> refused = new HashSet<>();

        Rend(Player player, Location center, long snap, Visuals.Effect rift) {
            this.player = player;
            this.center = center;
            this.snap = snap;
            this.rift = rift;
        }
    }

    @Override
    public WeaponType type() {
        return WeaponType.RIFTBLADE;
    }

    @Override
    public Result use(Player player, WeaponItems.Tag weapon, Ability ability) {
        return ability == Ability.VOID_REND ? rend(player) : swap(player);
    }

    @Override
    public long active(Player player, WeaponItems.Tag weapon, Ability ability, long now) {
        Rend rend = ability == Ability.VOID_REND ? rends.get(player.getUniqueId()) : null;
        return rend == null ? 0 : Math.max(0, rend.snap - now);
    }

    @Override
    public long activeLength(Ability ability) {
        return ability == Ability.VOID_REND ? plugin.settings().ability(Ability.VOID_REND).ticks("pull-time") : 0;
    }

    @Override
    public void tick(long now) {
        for (Iterator<Rend> it = rends.values().iterator(); it.hasNext(); ) {
            Rend rend = it.next();
            if (now >= rend.snap) {
                it.remove();
                snap(rend);
            } else {
                pull(rend, now);
            }
        }
    }

    // ---- Void Rend ---------------------------------------------------------------------------------------

    private Result rend(Player player) {
        AbilitySettings settings = plugin.settings().ability(Ability.VOID_REND);
        Location start = player.getLocation();
        Vector direction = Geo.flat(start);
        Location end = Geo.dash(start, direction, settings.num("distance"));
        Location center = end.clone().add(0, 1.2, 0);
        Visuals.Effect rift = plugin.visuals().spawn("rift", center).facing(direction).size(0.08, 3.0, 1.0).send(0)
                .animate(1, 4, e -> e.size(1.9, 3.6, 1.0));
        plugin.visuals().spawn("void_portal", center.clone().add(direction.clone().multiply(-0.05)))
                .facing(direction).size(0.2).send(0)
                .animate(1, 5, e -> e.size(2.6))
                .vanish(6 + settings.ticks("pull-time"), 4);
        long snap = plugin.tick() + settings.ticks("pull-time");
        rends.put(player.getUniqueId(), new Rend(player, center, snap, rift));
        plugin.fx().sound(center, "void-rend");
        return Result.FIRED;
    }

    private void pull(Rend rend, long now) {
        AbilitySettings settings = plugin.settings().ability(Ability.VOID_REND);
        Fx.View view = plugin.fx().view(rend.center);
        view.particle(Fx.REVERSE_PORTAL, rend.center, 14, 0.4, 1.4, 0.4, 0.05);
        view.particle(Fx.PORTAL, rend.center, 20, 1.6, 1.2, 1.6, 0.6);
        view.dust(rend.center, VOID, 2.0f, 4, 0.5);
        if (!rend.player.isOnline()) {
            return;
        }
        double pull = settings.num("pull");
        for (LivingEntity target : plugin.hits().around(rend.player, rend.center, settings.num("pull-radius"))) {
            UUID id = target.getUniqueId();
            if (rend.refused.contains(id)) {
                continue;
            }
            if (!rend.allowed.contains(id)) {
                if (!plugin.hits().allowed(rend.player, target)) {
                    rend.refused.add(id);
                    continue;
                }
                rend.allowed.add(id);
            }
            Vector in = rend.center.toVector().subtract(Geo.middle(target).toVector());
            double distance = in.length();
            if (distance < 0.6) {
                continue;
            }
            Vector velocity = target.getVelocity().multiply(0.6).add(in.normalize().multiply(pull));
            velocity.setY(Math.max(-0.4, Math.min(0.4, velocity.getY())));
            target.setVelocity(velocity);
            if (now % 4 == 0) {
                view.dust(Geo.middle(target), VIOLET, 1.0f, 3, 0.3);
            }
        }
    }

    private void snap(Rend rend) {
        AbilitySettings settings = plugin.settings().ability(Ability.VOID_REND);
        rend.rift.animate(1, 3, e -> e.size(0.02, 4.2, 1.0)).life(5);
        plugin.visuals().spawn("void_burst", rend.center).billboard().size(0.4).send(0)
                .animate(1, 3, e -> e.size(settings.num("radius") * 2.2))
                .vanish(4, 3);
        plugin.fx().sound(rend.center, "void-rend-snap");
        Fx.View view = plugin.fx().view(rend.center);
        view.particle(Fx.FLASH, rend.center, 1, 0, 0, 0, 0);
        view.particle(Fx.REVERSE_PORTAL, rend.center, 60, 0.8, 1.2, 0.8, 0.3);
        if (!rend.player.isOnline()) {
            return;
        }
        int darkness = settings.ticks("darkness");
        for (LivingEntity target : plugin.hits().around(rend.player, rend.center, settings.num("radius"))) {
            if (plugin.hits().hurt(rend.player, target, settings.num("damage"))) {
                Hits.effect(target, "darkness", 1, darkness);
                plugin.fx().sound(target.getLocation(), "riftblade-hit");
                view.dust(Geo.middle(target), VIOLET, 1.4f, 10, 0.3);
            }
        }
    }

    // ---- Rift Swap ----------------------------------------------------------------------------------------

    private Result swap(Player player) {
        AbilitySettings settings = plugin.settings().ability(Ability.RIFT_SWAP);
        LivingEntity target = sighted(player, settings.num("range"));
        Location from = player.getLocation();
        if (target == null) {
            return blink(player, settings);
        }
        if (!plugin.hits().allowed(player, target)) {
            plugin.hud().notice(player, Text.color(plugin.settings().message("swap-refused")));
            return Result.FAILED;
        }
        Location there = target.getLocation();
        Location mine = from.clone();
        mine.setYaw(there.getYaw());
        mine.setPitch(there.getPitch());
        Location theirs = there.clone();
        theirs.setYaw(from.getYaw());
        theirs.setPitch(from.getPitch());
        if (!target.teleport(mine, PlayerTeleportEvent.TeleportCause.PLUGIN)) {
            plugin.hud().notice(player, Text.color(plugin.settings().message("swap-refused")));
            return Result.FAILED;
        }
        if (!player.teleport(theirs, PlayerTeleportEvent.TeleportCause.PLUGIN)) {
            target.teleport(there, PlayerTeleportEvent.TeleportCause.PLUGIN); // Put them back.
            plugin.hud().notice(player, Text.color(plugin.settings().message("swap-refused")));
            return Result.FAILED;
        }
        player.setFallDistance(0f);
        target.setFallDistance(0f);
        portal(from);
        portal(there);
        if (plugin.hits().hurt(player, target, settings.num("damage"))) {
            Hits.effect(target, "nausea", 1, settings.ticks("nausea"));
            plugin.fx().sound(target.getLocation(), "riftblade-hit");
        }
        plugin.fx().sound(from, "rift-swap");
        plugin.fx().sound(there, "rift-swap");
        return Result.FIRED;
    }

    private Result blink(Player player, AbilitySettings settings) {
        Location from = player.getLocation();
        Vector direction = Geo.flat(from);
        Location to = Geo.dash(from, direction, settings.num("blink"));
        if (Geo.flatDistance(from, to) < 1.0) {
            return Result.FAILED;
        }
        to.setYaw(from.getYaw());
        to.setPitch(from.getPitch());
        if (!player.teleport(to, PlayerTeleportEvent.TeleportCause.PLUGIN)) {
            return Result.FAILED;
        }
        player.setFallDistance(0f);
        portal(from);
        portal(to);
        plugin.fx().sound(from, "rift-swap");
        return Result.FIRED;
    }

    /** A portal swirling open and shut where someone went through. */
    private void portal(Location at) {
        Location middle = at.clone().add(0, 1.0, 0);
        plugin.visuals().spawn("void_portal", middle).billboard().size(0.2).send(0)
                .animate(1, 3, e -> e.size(2.4))
                .vanish(8, 5);
        Fx.View view = plugin.fx().view(middle);
        view.particle(Fx.REVERSE_PORTAL, middle, 40, 0.4, 0.9, 0.4, 0.1);
        view.dust(middle, VOID, 1.8f, 20, 0.5);
        view.dust(middle, VIOLET, 1.2f, 15, 0.6);
    }

    /** The first player (or monster) in the line of sight, up to the first wall. */
    private LivingEntity sighted(Player player, double range) {
        Location eye = player.getEyeLocation();
        World world = eye.getWorld();
        Vector direction = eye.getDirection().normalize();
        double reach = range;
        for (double d = 0.5; d <= range; d += 0.25) {
            Location point = eye.clone().add(direction.clone().multiply(d));
            if (Geo.solid(point)) {
                reach = d;
                break;
            }
        }
        if (world == null) {
            return null;
        }
        List<LivingEntity> line = plugin.hits().along(player, eye, eye.clone().add(direction.multiply(reach)), 0.7);
        for (LivingEntity entity : line) {
            if (Geo.clear(eye, Geo.middle(entity))) {
                return entity;
            }
        }
        return null;
    }
}
