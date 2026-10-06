package io.github.drepfy.legendary.ability;

import io.github.drepfy.legendary.Ability;
import io.github.drepfy.legendary.LegendaryPlugin;
import io.github.drepfy.legendary.WeaponType;
import io.github.drepfy.legendary.config.AbilitySettings;
import io.github.drepfy.legendary.item.WeaponItems;
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
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Gravebreaker, the executioner's axe: ground control and heavy hits.
 * <ul>
 *   <li><b>Executioner's Leap</b>: a leap high and forward, then a slam that cracks the ground
 *   in a ring: everyone near the impact is hurt (most at the centre), thrown up and slowed. The
 *   leaper takes no fall damage from it. No block is changed.</li>
 *   <li><b>Grave Rise</b>: gravestones burst out of the ground one after another in a line,
 *   launching whoever stands on them and tiring them.</li>
 * </ul>
 */
final class Gravebreaker implements Kit, Listener {

    private final LegendaryPlugin plugin;
    private final Map<UUID, Long> leaps = new HashMap<>();
    /** Leaper → the tick until which their fall is not hurting them. */
    private final Map<UUID, Long> softLanding = new HashMap<>();
    private final Random random = new Random();

    Gravebreaker(LegendaryPlugin plugin) {
        this.plugin = plugin;
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
    public void forget(Player player) {
        leaps.remove(player.getUniqueId());
        softLanding.remove(player.getUniqueId());
    }

    @Override
    public void tick(long now) {
        softLanding.values().removeIf(until -> until <= now);
        for (Iterator<Map.Entry<UUID, Long>> it = leaps.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Long> leap = it.next();
            Player player = plugin.getServer().getPlayer(leap.getKey());
            if (player == null || player.isDead()) {
                it.remove();
                continue;
            }
            long flying = now - leap.getValue();
            if (flying >= 6 && (landed(player) || flying >= 40)) {
                it.remove();
                slam(player);
            } else if (flying % 2 == 0) {
                plugin.fx().view(player.getLocation()).particle(Fx.CLOUD, player.getLocation(), 3, 0.2, 0.1, 0.2, 0.01);
            }
        }
    }

    private static boolean landed(Player player) {
        Location feet = player.getLocation();
        return Geo.solid(feet.clone().subtract(0, 0.08, 0)) && player.getVelocity().getY() <= 0.05;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFall(EntityDamageEvent event) {
        if (event.getCause() == EntityDamageEvent.DamageCause.FALL && event.getEntity() instanceof Player player
                && (softLanding.containsKey(player.getUniqueId()) || leaps.containsKey(player.getUniqueId()))) {
            event.setCancelled(true);
        }
    }

    // ---- Executioner's Leap ----------------------------------------------------------------------------------

    private Result leap(Player player) {
        AbilitySettings settings = plugin.settings().ability(Ability.EXECUTIONERS_LEAP);
        Vector velocity = Geo.flat(player.getLocation()).multiply(settings.num("leap-forward"));
        velocity.setY(settings.num("leap-up"));
        player.setVelocity(velocity);
        player.setFallDistance(0f);
        long now = plugin.tick();
        leaps.put(player.getUniqueId(), now);
        softLanding.put(player.getUniqueId(), now + 80);
        plugin.fx().sound(player.getLocation(), "executioners-leap");
        plugin.visuals().spawn("shockwave", player.getLocation().add(0, 0.06, 0)).size(0.8).send(0)
                .animate(1, 4, e -> e.size(2.2))
                .vanish(5, 3);
        return Result.FIRED;
    }

    private void slam(Player player) {
        AbilitySettings settings = plugin.settings().ability(Ability.EXECUTIONERS_LEAP);
        softLanding.put(player.getUniqueId(), plugin.tick() + 10);
        player.setFallDistance(0f);
        Location center = player.getLocation();
        double radius = settings.num("radius");
        plugin.fx().sound(center, "executioners-slam");
        Visuals visuals = plugin.visuals();
        visuals.spawn("shockwave", center.clone().add(0, 0.06, 0)).size(1.0).turn(random.nextInt(360)).send(0)
                .animate(1, 6, e -> e.size(radius * 2.2))
                .vanish(16, 6);
        visuals.spawn("ember_ring", center.clone().add(0, 0.1, 0)).size(0.6).send(0)
                .animate(1, 4, e -> e.size(radius * 1.4))
                .vanish(5, 4);
        // Rocks thrown up from the crater.
        BlockData ground = groundBlock(center);
        for (int i = 0; i < 7; i++) {
            Location at = Geo.scatter(center, 1.6, random).add(0, 0.3, 0);
            Vector out = Geo.away(center, at, Geo.flat(center)).multiply(0.9 + random.nextDouble());
            float size = (float) (0.35 + random.nextDouble() * 0.35);
            Visuals.Effect rock = visuals.block(ground, at).size(size).offset(-size / 2, -size / 2, -size / 2).send(0);
            rock.animate(1, 6, e -> e.offset(out.getX() - size / 2, 1.2 + random.nextDouble() - size / 2, out.getZ() - size / 2)
                    .turn(random.nextInt(360)));
            rock.animate(7, 6, e -> e.offset(out.getX() * 1.6 - size / 2, -0.6, out.getZ() * 1.6 - size / 2));
            rock.vanish(13, 3);
        }
        Fx.View view = plugin.fx().view(center);
        view.particle(Fx.EXPLOSION, center.clone().add(0, 0.5, 0), 2, 0.6, 0.2, 0.6, 0);
        view.debris(center.clone().add(0, 0.2, 0), ground, 80, radius / 2);
        view.particle(Fx.CLOUD, center.clone().add(0, 0.2, 0), 30, radius / 2.5, 0.1, radius / 2.5, 0.05);
        for (LivingEntity target : plugin.hits().around(player, center.clone().add(0, 0.6, 0), radius)) {
            double distance = target.getLocation().distance(center);
            double falloff = Math.min(1.0, distance / radius);
            double damage = settings.num("damage") + (settings.num("edge-damage") - settings.num("damage")) * falloff;
            if (plugin.hits().hurt(player, target, Math.max(0.5, damage))) {
                Vector away = Geo.away(center, target.getLocation(), Geo.flat(center));
                Hits.knock(target, away, settings.num("push"), settings.num("launch"));
                Hits.effect(target, "slowness", settings.whole("slow-level"), settings.ticks("slow-duration"));
                plugin.fx().sound(target.getLocation(), "gravebreaker-hit");
            }
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
            placed++;
        }
        if (placed == 0) {
            return Result.FAILED;
        }
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
        BlockData ground = groundBlock(stone);
        Fx.View view = plugin.fx().view(stone);
        view.debris(stone.clone().add(0, 0.1, 0), ground, 30, 0.5);
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
                Hits.effect(target, "mining_fatigue", settings.whole("fatigue-level"), settings.ticks("fatigue-duration"));
                plugin.fx().sound(target.getLocation(), "gravebreaker-hit");
            }
        }
    }
}
