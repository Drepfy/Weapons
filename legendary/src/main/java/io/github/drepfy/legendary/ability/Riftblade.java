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
    private static final Color STARLIGHT = Color.fromRGB(235, 215, 255);

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
        final long opened;
        final Vector facing;
        final Set<UUID> allowed = new HashSet<>();
        final Set<UUID> refused = new HashSet<>();

        Rend(Player player, Location center, long opened, long snap, Vector facing) {
            this.player = player;
            this.center = center;
            this.opened = opened;
            this.snap = snap;
            this.facing = facing;
        }
    }

    private static final class Echo {
        final Player player;
        final Location at;
        final long until;

        Echo(Player player, Location at, long until) {
            this.player = player;
            this.at = at;
            this.until = until;
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
        echoes.put(weapon.id(), new Echo(player, from, plugin.tick() + echo));
        echoFx(from, plugin.tick());
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
                Location at = echo.at.clone().add(0, 1.0, 0);
                plugin.fx().view(at).particle(Fx.REVERSE_PORTAL, at, 30, 0.2, 0.6, 0.2, 0.15); // It closes.
                plugin.abilities().startCooldown(entry.getKey(), Ability.RIFT_SWAP);
            } else if (now % 3 == 0) {
                echoFx(echo.at, now);
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
        long now = plugin.tick();
        Rend rend = new Rend(player, center, now, now + settings.ticks("pull-time"), direction);
        rends.put(player.getUniqueId(), rend);
        riftFx(rend, now);
        plugin.fx().sound(center, "void-rend");
        return Result.FIRED;
    }

    private void pull(Rend rend, long now) {
        AbilitySettings settings = plugin.settings().ability(Ability.VOID_REND);
        Fx.View view = plugin.fx().view(rend.center);
        riftFx(rend, now);
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
            if (now % 3 == 0) {
                // A thread of void dragging them in.
                view.fade(Shapes.line(Geo.middle(target), rend.center, 0.35), VIOLET, VOID, 0.8f);
            }
        }
    }

    private void snap(Rend rend) {
        AbilitySettings settings = plugin.settings().ability(Ability.VOID_REND);
        plugin.fx().sound(rend.center, "void-rend-snap");
        Fx.View view = plugin.fx().view(rend.center);
        // It snaps shut: a sonic shockwave, a shell of void bursting out to the edge of the blast.
        view.particle(Fx.SONIC_BOOM, rend.center, 1, 0, 0, 0, 0);
        view.particle(Fx.REVERSE_PORTAL, rend.center, 70, 0.6, 1.0, 0.6, 0.35);
        view.particle(Fx.WITCH, rend.center, 25, 0.8, 0.8, 0.8, 0.1);
        double radius = settings.num("radius");
        for (int k = 1; k <= 3; k++) {
            double r = radius * k / 3.0;
            plugin.visuals().later(k - 1, () -> view.fade(Shapes.sphere(rend.center, r, (int) (14 + r * 10)), VIOLET, VOID, 1.3f));
        }
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

    /** A rift swirling open and shut where someone went through: a twisting column of void. */
    private void portal(Location at) {
        Location middle = at.clone().add(0, 1.0, 0);
        Fx.View view = plugin.fx().view(middle);
        view.fade(Shapes.spiral(at.clone().add(0, 0.05, 0), 0.65, 2.1, 2.0, 36, 0), VIOLET, VOID, 1.2f);
        view.fade(Shapes.spiral(at.clone().add(0, 0.05, 0), 0.65, 2.1, 2.0, 36, Math.PI), STARLIGHT, VIOLET, 0.8f);
        view.particle(Fx.REVERSE_PORTAL, middle, 40, 0.3, 0.8, 0.3, 0.12);
        plugin.visuals().later(2, () -> view.fade(Shapes.ring(at.clone().add(0, 0.1, 0), 1.1, 22, 0), VIOLET, VOID, 1.0f));
    }

    /** The void tear of Void Rend: a jagged crack standing in the air, growing as it opens. */
    private void riftFx(Rend rend, long now) {
        double open = Math.min(1.0, (now - rend.opened + 1) / 5.0);
        double half = 1.9 * open;
        Vector right = Geo.right(rend.facing);
        Fx.View view = plugin.fx().view(rend.center);
        Random jitter = new Random(rend.opened); // The same jagged shape every tick.
        Location last = null;
        for (double y = -half; y <= half + 1.0E-6; y += 0.25) {
            double zig = (jitter.nextDouble() - 0.5) * 0.45 * (1 - Math.abs(y) / 2.2);
            Location point = rend.center.clone().add(right.clone().multiply(zig)).add(0, y, 0);
            if (last != null) {
                view.dust(Shapes.line(last, point, 0.12), VOID, 1.4f);
            }
            view.dust(point, VIOLET, 0.9f, 1, 0.05);
            last = point;
        }
        if (now % 2 == 0) {
            view.fade(Shapes.standingRing(rend.center, rend.facing, 1.5 * open, 26, now * 0.15), VIOLET, VOID, 1.0f);
        }
        view.particle(Fx.REVERSE_PORTAL, rend.center, 10, 0.3, half * 0.6, 0.3, 0.06);
        view.particle(Fx.PORTAL, rend.center, 14, 1.4, 1.1, 1.4, 0.6);
    }

    /** Rift Swap's echo: a small turning rift where you were, waiting for you to come back. */
    private void echoFx(Location at, long now) {
        Location middle = at.clone().add(0, 1.0, 0);
        Fx.View view = plugin.fx().view(middle);
        view.fade(Shapes.standingRing(middle, new Vector(Math.cos(now * 0.1), 0, Math.sin(now * 0.1)), 0.7, 18, now * 0.2),
                VIOLET, VOID, 0.9f);
        view.particle(Fx.REVERSE_PORTAL, middle, 4, 0.2, 0.5, 0.2, 0.02);
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
