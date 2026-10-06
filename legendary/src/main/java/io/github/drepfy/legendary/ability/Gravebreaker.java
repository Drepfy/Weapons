package io.github.drepfy.legendary.ability;

import io.github.drepfy.legendary.Ability;
import io.github.drepfy.legendary.LegendaryPlugin;
import io.github.drepfy.legendary.WeaponType;
import io.github.drepfy.legendary.config.AbilitySettings;
import io.github.drepfy.legendary.item.WeaponItems;
import io.github.drepfy.legendary.util.Compat;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Gravebreaker, the executioner's axe: ground control and finishing blows.
 * <ul>
 *   <li><b>Executioner's Leap</b>: a leap high and forward, then a slam where you land: everyone
 *   near the impact is hurt (most at the centre), thrown up and slowed. Press again in the air to
 *   dive at where you look, for a bigger slam. No fall damage, and no block is changed.</li>
 *   <li><b>Grave Rise</b>: gravestones burst out of the ground one after another in a line,
 *   launching and weighing down whoever stands on them; the last one bursts as a tomb.</li>
 *   <li><b>Last Rites</b> (passive): axe hits on players low on health hit harder, and a kill
 *   makes the leap ready again.</li>
 * </ul>
 */
final class Gravebreaker implements Kit, Listener {

    private final LegendaryPlugin plugin;
    private final Map<UUID, Leap> leaps = new HashMap<>();
    private final Random random = new Random();

    Gravebreaker(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    private static final class Leap {
        final long start;
        boolean dived;

        Leap(long start) {
            this.start = start;
        }
    }

    @Override
    public WeaponType type() {
        return WeaponType.GRAVEBREAKER;
    }

    @Override
    public Result use(Player player, WeaponItems.Tag weapon, Ability ability) {
        return ability == Ability.EXECUTIONERS_LEAP ? leap(player) : rise(player);
    }

    @Override
    public boolean recast(Player player, WeaponItems.Tag weapon, Ability ability) {
        Leap leap = ability == Ability.EXECUTIONERS_LEAP ? leaps.get(player.getUniqueId()) : null;
        if (leap == null) {
            return false;
        }
        if (!leap.dived && plugin.tick() - leap.start >= 3) {
            dive(player, leap);
        }
        return true;
    }

    @Override
    public long active(Player player, WeaponItems.Tag weapon, Ability ability, long now) {
        Leap leap = ability == Ability.EXECUTIONERS_LEAP ? leaps.get(player.getUniqueId()) : null;
        return leap == null ? 0 : Math.max(1, 40 - (now - leap.start));
    }

    @Override
    public long activeLength(Ability ability) {
        return ability == Ability.EXECUTIONERS_LEAP ? 40 : 0;
    }

    @Override
    public void forget(Player player) {
        leaps.remove(player.getUniqueId());
    }

    @Override
    public void tick(long now) {
        for (Iterator<Map.Entry<UUID, Leap>> it = leaps.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Leap> entry = it.next();
            Player player = plugin.getServer().getPlayer(entry.getKey());
            if (player == null || player.isDead()) {
                it.remove();
                continue;
            }
            Leap leap = entry.getValue();
            long flying = now - leap.start;
            if (flying >= 4 && (landed(player) || flying >= 40)) {
                it.remove();
                slam(player, leap.dived);
            } else if (flying % 2 == 0) {
                plugin.fx().view(player.getLocation()).particle(leap.dived ? Fx.FLAME : Fx.CLOUD,
                        player.getLocation(), 3, 0.2, 0.1, 0.2, 0.01);
            }
        }
    }

    private static boolean landed(Player player) {
        Location feet = player.getLocation();
        return Geo.solid(feet.clone().subtract(0, 0.08, 0)) && player.getVelocity().getY() <= 0.05;
    }

    // ---- Executioner's Leap ----------------------------------------------------------------------------------

    private Result leap(Player player) {
        AbilitySettings settings = plugin.settings().ability(Ability.EXECUTIONERS_LEAP);
        Vector velocity = Geo.flat(player.getLocation()).multiply(settings.num("leap-forward"));
        velocity.setY(settings.num("leap-up"));
        player.setVelocity(velocity);
        player.setFallDistance(0f);
        leaps.put(player.getUniqueId(), new Leap(plugin.tick()));
        plugin.abilities().softLanding(player, 80);
        plugin.fx().sound(player.getLocation(), "executioners-leap");
        plugin.visuals().spawn("shockwave", player.getLocation().add(0, 0.06, 0)).size(0.8).send(0)
                .animate(1, 4, e -> e.size(2.8))
                .vanish(9, 5);
        return Result.FIRED;
    }

    /** Pressed again in the air: plunge at where the player looks. */
    private void dive(Player player, Leap leap) {
        AbilitySettings settings = plugin.settings().ability(Ability.EXECUTIONERS_LEAP);
        leap.dived = true;
        Location target = Geo.target(player, settings.num("dive-range"));
        Vector way;
        if (target != null) {
            way = target.toVector().subtract(player.getLocation().toVector());
        } else {
            way = player.getLocation().getDirection().setY(-1.0); // Nothing in reach: straight down and ahead.
        }
        if (way.lengthSquared() < 1.0E-4) {
            way = new Vector(0, -1, 0);
        }
        Vector velocity = way.normalize().multiply(settings.num("dive-speed"));
        velocity.setY(Math.min(-0.6, velocity.getY()));
        player.setVelocity(velocity);
        player.setFallDistance(0f);
        plugin.abilities().softLanding(player, 60);
        plugin.fx().sound(player.getLocation(), "executioners-dive");
        Location at = player.getLocation().add(0, 1.0, 0);
        plugin.visuals().spawn("ember_ring", at).billboard().size(0.6).send(0)
                .animate(1, 3, e -> e.size(3.0))
                .vanish(8, 5);
    }

    private void slam(Player player, boolean dived) {
        AbilitySettings settings = plugin.settings().ability(Ability.EXECUTIONERS_LEAP);
        plugin.abilities().softLanding(player, 10);
        player.setFallDistance(0f);
        Location center = player.getLocation();
        double bonus = dived ? settings.num("dive-bonus") : 0;
        double radius = settings.num("radius") * (dived ? 1.3 : 1.0);
        plugin.fx().sound(center, "executioners-slam");
        Visuals visuals = plugin.visuals();
        visuals.spawn("shockwave", center.clone().add(0, 0.06, 0)).size(1.0).turn(random.nextInt(360)).send(0)
                .animate(1, 6, e -> e.size(radius * 2.2))
                .vanish(24, 10);
        visuals.spawn("ember_ring", center.clone().add(0, 0.1, 0)).size(0.6).send(0)
                .animate(1, 4, e -> e.size(radius * 1.4))
                .vanish(9, 6);
        rocks(center, dived ? 11 : 7);
        Fx.View view = plugin.fx().view(center);
        view.particle(Fx.EXPLOSION, center.clone().add(0, 0.5, 0), dived ? 4 : 2, 0.6, 0.2, 0.6, 0);
        view.debris(center.clone().add(0, 0.2, 0), groundBlock(center), 80, radius / 2);
        view.particle(Fx.CLOUD, center.clone().add(0, 0.2, 0), 30, radius / 2.5, 0.1, radius / 2.5, 0.05);
        for (LivingEntity target : plugin.hits().around(player, center.clone().add(0, 0.6, 0), radius)) {
            double distance = target.getLocation().distance(center);
            double falloff = Math.min(1.0, distance / radius);
            double damage = settings.num("damage") + (settings.num("edge-damage") - settings.num("damage")) * falloff;
            if (plugin.hits().hurt(player, target, Math.max(0.5, damage + bonus))) {
                Vector away = Geo.away(center, target.getLocation(), Geo.flat(center));
                Hits.knock(target, away, settings.num("push"), settings.num("launch"));
                Hits.effect(target, "slowness", settings.whole("slow-level"), settings.ticks("slow-duration"));
                plugin.fx().sound(target.getLocation(), "gravebreaker-hit");
            }
        }
    }

    /** Rocks thrown up from a crater (only shown: no block is changed). */
    private void rocks(Location center, int count) {
        BlockData ground = groundBlock(center);
        for (int i = 0; i < count; i++) {
            Location at = Geo.scatter(center, 1.6, random).add(0, 0.3, 0);
            Vector out = Geo.away(center, at, Geo.flat(center)).multiply(0.9 + random.nextDouble());
            float size = (float) (0.35 + random.nextDouble() * 0.35);
            Visuals.Effect rock = plugin.visuals().block(ground, at).size(size).offset(-size / 2, -size / 2, -size / 2).send(0);
            rock.animate(1, 6, e -> e.offset(out.getX() - size / 2, 1.2 + random.nextDouble() - size / 2, out.getZ() - size / 2)
                    .turn(random.nextInt(360)));
            rock.animate(7, 6, e -> e.offset(out.getX() * 1.6 - size / 2, -0.6, out.getZ() * 1.6 - size / 2));
            rock.vanish(13, 3);
        }
    }

    private static BlockData groundBlock(Location at) {
        Block below = at.clone().subtract(0, 0.5, 0).getBlock();
        Material type = below.getType();
        return (type.isSolid() ? type : Material.DEEPSLATE).createBlockData();
    }

    // ---- Grave Rise ------------------------------------------------------------------------------------------

    private Result rise(Player player) {
        AbilitySettings settings = plugin.settings().ability(Ability.GRAVE_RISE);
        Location start = player.getLocation();
        World world = start.getWorld();
        Vector direction = Geo.flat(start);
        int stones = settings.whole("stones");
        double spacing = settings.num("range") / stones;
        int ground = start.getBlockY() - 1;
        int interval = Math.max(1, settings.ticks("interval"));
        Set<UUID> hit = new HashSet<>();
        Location lastStone = null;
        int placed = 0;
        for (int i = 1; i <= stones; i++) {
            Location point = start.clone().add(direction.clone().multiply(i * spacing));
            int y = Geo.groundY(world, point.getX(), point.getZ(), ground);
            if (y == Integer.MIN_VALUE) {
                break; // A wall or a drop: the line stops there.
            }
            ground = y;
            Location stone = new Location(world, point.getX(), y + 1.0, point.getZ());
            plugin.visuals().later((i - 1) * interval, () -> erupt(player, stone, direction, hit));
            lastStone = stone;
            placed++;
        }
        if (placed == 0) {
            return Result.FAILED;
        }
        Location tomb = lastStone;
        plugin.visuals().later((placed - 1) * interval + 5, () -> tomb(player, tomb));
        plugin.fx().sound(start, "grave-rise");
        return Result.FIRED;
    }

    private void erupt(Player player, Location stone, Vector direction, Set<UUID> hit) {
        if (!player.isOnline()) {
            return;
        }
        AbilitySettings settings = plugin.settings().ability(Ability.GRAVE_RISE);
        plugin.visuals().spawn("gravestone", stone.clone().add(0, 0.7, 0)).facing(direction.clone().multiply(-1))
                .turn(random.nextInt(21) - 10).size(1.5).offset(0, -1.6, 0).send(0)
                .animate(1, 3, e -> e.offset(0, 0.05, 0))
                .animate(30, 8, e -> e.offset(0, -1.7, 0))
                .life(39);
        plugin.fx().sound(stone, "grave-rise-stone");
        Fx.View view = plugin.fx().view(stone);
        view.debris(stone.clone().add(0, 0.1, 0), groundBlock(stone), 30, 0.5);
        view.particle(Fx.SOUL, stone.clone().add(0, 0.6, 0), 4, 0.3, 0.3, 0.3, 0.02);
        double width = settings.num("width");
        for (LivingEntity target : plugin.hits().around(player, stone.clone().add(0, 1.0, 0), width + 0.6)) {
            double dy = target.getLocation().getY() - stone.getY();
            if (dy < -0.6 || dy > 2.5 || Geo.flatDistance(target.getLocation(), stone) > width
                    || !hit.add(target.getUniqueId())) {
                continue;
            }
            if (plugin.hits().hurt(player, target, settings.num("damage"))) {
                Hits.knock(target, direction, 0.25, settings.num("launch"));
                Hits.effect(target, "slowness", settings.whole("slow-level"), settings.ticks("slow-duration"));
                Hits.effect(target, "mining_fatigue", settings.whole("fatigue-level"), settings.ticks("fatigue-duration"));
                plugin.fx().sound(target.getLocation(), "gravebreaker-hit");
            }
        }
    }

    /** The last gravestone bursts as a tomb: a ring of force round it. */
    private void tomb(Player player, Location at) {
        AbilitySettings settings = plugin.settings().ability(Ability.GRAVE_RISE);
        double radius = settings.num("tomb-radius");
        if (!player.isOnline() || radius <= 0) {
            return;
        }
        plugin.fx().sound(at, "executioners-slam");
        plugin.visuals().spawn("shockwave", at.clone().add(0, 0.06, 0)).size(0.6).send(0)
                .animate(1, 4, e -> e.size(radius * 2.2))
                .vanish(16, 8);
        rocks(at, 6);
        plugin.fx().view(at).particle(Fx.SOUL, at.clone().add(0, 0.5, 0), 20, radius / 2, 0.3, radius / 2, 0.04);
        if (settings.num("tomb-damage") <= 0) {
            return;
        }
        for (LivingEntity target : plugin.hits().around(player, at.clone().add(0, 0.8, 0), radius)) {
            if (plugin.hits().hurt(player, target, settings.num("tomb-damage"))) {
                Hits.knock(target, Geo.away(at, target.getLocation(), Geo.flat(player.getLocation())), 0.8, 0.5);
            }
        }
    }

    // ---- Last Rites (passive) --------------------------------------------------------------------------------

    @Override
    public void melee(EntityDamageByEntityEvent event, Player attacker, LivingEntity target, WeaponItems.Tag weapon) {
        if (!(target instanceof Player)) {
            return;
        }
        AbilitySettings settings = plugin.settings().ability(Ability.LAST_RITES);
        double max = Compat.maxHealth(target);
        if (target.getHealth() < max * settings.num("threshold")) {
            event.setDamage(event.getDamage() * (1.0 + settings.num("bonus")));
            Location at = Geo.middle(target);
            plugin.fx().view(at).particle(Fx.SOUL, at, 6, 0.3, 0.4, 0.3, 0.02);
            plugin.fx().sound(at, "last-rites");
        }
    }

    /** A kill with the axe in hand: the leap is ready again. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKill(PlayerDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer == null || killer.equals(event.getEntity())) {
            return;
        }
        WeaponItems.Tag weapon = plugin.items().read(killer.getInventory().getItemInMainHand());
        if (weapon == null || weapon.type() != WeaponType.GRAVEBREAKER) {
            return;
        }
        plugin.abilities().cooldowns().start(weapon.id(), Ability.EXECUTIONERS_LEAP, plugin.tick(), 0);
        plugin.fx().sound(killer.getLocation(), "last-rites");
        Location at = killer.getLocation().add(0, 0.1, 0);
        plugin.visuals().spawn("ember_ring", at).size(0.5).send(0)
                .animate(1, 4, e -> e.size(3.6))
                .vanish(9, 6);
    }
}
