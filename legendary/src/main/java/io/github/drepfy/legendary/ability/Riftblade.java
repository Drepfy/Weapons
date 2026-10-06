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
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
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
 * Riftblade, the void sword: space and positioning.
 * <ul>
 *   <li><b>Void Rend</b>: tears a rift open a few blocks ahead. It drags nearby players towards
 *   it, then snaps shut: everyone close to it is hurt, blinded by Darkness and lifted helplessly
 *   into the air.</li>
 *   <li><b>Rift Swap</b>: swaps places with the player (or monster) you look at, through the
 *   void. With nobody in sight, blinks forward instead. A void echo stays where you were: press
 *   again within a few seconds to return to it.</li>
 *   <li><b>Phase Shift</b> (passive): sometimes an attack passes straight through you, and you
 *   slip a few blocks aside through a small rift.</li>
 * </ul>
 */
final class Riftblade implements Kit, Listener {

    private static final Color VIOLET = Color.fromRGB(165, 80, 255);
    private static final Color VOID = Color.fromRGB(30, 8, 60);

    private final LegendaryPlugin plugin;
    private final Map<UUID, Rend> rends = new HashMap<>();
    /** Weapon → the echo its last Rift Swap left behind. */
    private final Map<UUID, Echo> echoes = new HashMap<>();
    private final Random random = new Random();

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

    private static final class Echo {
        final Player player;
        final Location at;
        final long until;
        final Visuals.Effect effect;

        Echo(Player player, Location at, long until, Visuals.Effect effect) {
            this.player = player;
            this.at = at;
            this.until = until;
            this.effect = effect;
        }
    }

    @Override
    public WeaponType type() {
        return WeaponType.RIFTBLADE;
    }

    @Override
    public Result use(Player player, WeaponItems.Tag weapon, Ability ability) {
        if (ability == Ability.VOID_REND) {
            return rend(player);
        }
        Location from = player.getLocation();
        Result result = swap(player);
        int echo = plugin.settings().ability(Ability.RIFT_SWAP).ticks("echo");
        if (result != Result.FIRED || echo <= 0) {
            return result;
        }
        Location at = from.clone().add(0, 1.0, 0);
        Visuals.Effect effect = plugin.visuals().spawn("void_portal", at).billboard().size(0.2).send(0)
                .animate(1, 4, e -> e.size(2.0));
        echoes.put(weapon.id(), new Echo(player, from, plugin.tick() + echo, effect));
        return Result.HANDLED; // The cooldown starts once the echo is used or fades.
    }

    @Override
    public boolean recast(Player player, WeaponItems.Tag weapon, Ability ability) {
        Echo echo = ability == Ability.RIFT_SWAP ? echoes.get(weapon.id()) : null;
        if (echo == null || !echo.player.equals(player)) {
            return false;
        }
        echoes.remove(weapon.id());
        plugin.abilities().startCooldown(weapon.id(), Ability.RIFT_SWAP);
        echo.effect.animate(1, 3, e -> e.size(0.01)).life(5);
        Location from = player.getLocation();
        Location back = echo.at.clone();
        back.setYaw(from.getYaw());
        back.setPitch(from.getPitch());
        if (back.getWorld() == from.getWorld() && player.teleport(back, PlayerTeleportEvent.TeleportCause.PLUGIN)) {
            player.setFallDistance(0f);
            portal(from);
            portal(back);
            plugin.fx().sound(from, "rift-return");
            plugin.fx().sound(back, "rift-return");
        }
        return true;
    }

    @Override
    public long active(Player player, WeaponItems.Tag weapon, Ability ability, long now) {
        if (ability == Ability.RIFT_SWAP) {
            Echo echo = echoes.get(weapon.id());
            return echo == null ? 0 : Math.max(0, echo.until - now);
        }
        Rend rend = rends.get(player.getUniqueId());
        return rend == null ? 0 : Math.max(0, rend.snap - now);
    }

    @Override
    public long activeLength(Ability ability) {
        AbilitySettings settings = plugin.settings().ability(ability);
        return ability == Ability.VOID_REND ? settings.ticks("pull-time") : settings.ticks("echo");
    }

    @Override
    public void forget(Player player) {
        rends.remove(player.getUniqueId());
        for (Iterator<Map.Entry<UUID, Echo>> it = echoes.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Echo> echo = it.next();
            if (echo.getValue().player.equals(player)) {
                it.remove();
                echo.getValue().effect.remove();
                plugin.abilities().startCooldown(echo.getKey(), Ability.RIFT_SWAP);
            }
        }
    }

    @Override
    public void tick(long now) {
        for (Iterator<Map.Entry<UUID, Echo>> it = echoes.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Echo> entry = it.next();
            Echo echo = entry.getValue();
            if (now >= echo.until) {
                it.remove();
                echo.effect.animate(1, 4, e -> e.size(0.01)).life(6);
                plugin.abilities().startCooldown(entry.getKey(), Ability.RIFT_SWAP);
            } else if (now % 4 == 0) {
                echo.effect.turn(now * 9.0).send(4);
                Location at = echo.at.clone().add(0, 1.0, 0);
                plugin.fx().view(at).particle(Fx.REVERSE_PORTAL, at, 6, 0.3, 0.6, 0.3, 0.02);
            }
        }
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
        Visuals.Effect rift = plugin.visuals().spawn("rift", center).facing(direction).size(0.1, 3.6, 1.0).send(0)
                .animate(1, 4, e -> e.size(2.4, 4.4, 1.0));
        plugin.visuals().spawn("void_portal", center.clone().add(direction.clone().multiply(-0.05)))
                .facing(direction).size(0.2).send(0)
                .animate(1, 5, e -> e.size(3.2))
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
                .vanish(8, 6);
        plugin.fx().sound(rend.center, "void-rend-snap");
        Fx.View view = plugin.fx().view(rend.center);
        view.particle(Fx.FLASH, rend.center, 1, 0, 0, 0, 0);
        view.particle(Fx.REVERSE_PORTAL, rend.center, 60, 0.8, 1.2, 0.8, 0.3);
        if (!rend.player.isOnline()) {
            return;
        }
        int darkness = settings.ticks("darkness");
        int lift = settings.ticks("lift");
        for (LivingEntity target : plugin.hits().around(rend.player, rend.center, settings.num("radius"))) {
            if (plugin.hits().hurt(rend.player, target, settings.num("damage"))) {
                Hits.effect(target, "darkness", 1, darkness);
                Hits.effect(target, "levitation", 2, lift);
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
                .animate(1, 3, e -> e.size(3.0))
                .vanish(12, 7);
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

    // ---- Phase Shift (passive) ---------------------------------------------------------------------------------

    /** Sometimes an attack on a Riftblade holder passes through: they slip aside through a rift. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onAttacked(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player player) || event.getDamager().equals(player)
                || plugin.hits().probing()) {
            return;
        }
        WeaponItems.Tag weapon = plugin.items().read(player.getInventory().getItemInMainHand());
        if (weapon == null || weapon.type() != WeaponType.RIFTBLADE) {
            return;
        }
        AbilitySettings settings = plugin.settings().ability(Ability.PHASE_SHIFT);
        long now = plugin.tick();
        if (plugin.abilities().cooldowns().remaining(weapon.id(), Ability.PHASE_SHIFT, now) > 0
                || random.nextDouble() >= settings.num("chance")) {
            return;
        }
        event.setCancelled(true);
        plugin.abilities().startCooldown(weapon.id(), Ability.PHASE_SHIFT);
        // Aside: across the line of the attack, whichever side has room.
        Location from = player.getLocation();
        Vector attack = from.toVector().subtract(event.getDamager().getLocation().toVector()).setY(0);
        Vector side = Geo.right(attack.lengthSquared() < 1.0E-4 ? Geo.flat(from) : attack.normalize());
        if (random.nextBoolean()) {
            side.multiply(-1);
        }
        Location to = null;
        for (Vector way : new Vector[]{side, side.clone().multiply(-1)}) {
            Location spot = Geo.dash(from, way, settings.num("distance"));
            if (Geo.flatDistance(from, spot) >= 1.0) {
                to = spot;
                break;
            }
        }
        plugin.fx().sound(from, "phase-shift");
        portal(from);
        if (to != null) {
            to.setYaw(from.getYaw());
            to.setPitch(from.getPitch());
            if (player.teleport(to, PlayerTeleportEvent.TeleportCause.PLUGIN)) {
                player.setFallDistance(0f);
                portal(to);
            }
        }
    }
}
