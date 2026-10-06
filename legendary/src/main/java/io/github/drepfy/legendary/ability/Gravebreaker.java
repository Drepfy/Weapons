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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
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
        Location feet = player.getLocation().add(0, 0.1, 0);
        Fx.View view = plugin.fx().view(feet);
        view.particle(Fx.GUST, feet, 1, 0, 0, 0, 0);
        view.debris(feet, groundBlock(feet), 30, 0.6);
        view.particle(Fx.CLOUD, feet, 16, 0.5, 0.05, 0.5, 0.06);
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
        Fx.View view = plugin.fx().view(at);
        view.particle(Fx.GUST, at, 1, 0, 0, 0, 0);
        view.particle(Fx.FLAME, at, 24, 0.4, 0.6, 0.4, 0.08);
        // A trail of fire behind the plunge, for as long as it falls.
        for (int t = 1; t <= 12; t += 1) {
            plugin.visuals().later(t, () -> {
                if (player.isOnline() && leaps.containsKey(player.getUniqueId())) {
                    Location body = player.getLocation().add(0, 0.9, 0);
                    Fx.View trail = plugin.fx().view(body);
                    trail.particle(Fx.FLAME, body, 6, 0.25, 0.4, 0.25, 0.02);
                    trail.particle(Fx.SMOKE, body, 2, 0.2, 0.3, 0.2, 0.01);
                }
            });
        }
    }

    private void slam(Player player, boolean dived) {
        AbilitySettings settings = plugin.settings().ability(Ability.EXECUTIONERS_LEAP);
        plugin.abilities().softLanding(player, 10);
        player.setFallDistance(0f);
        Location center = player.getLocation();
        double bonus = dived ? settings.num("dive-bonus") : 0;
        double radius = settings.num("radius") * (dived ? 1.3 : 1.0);
        plugin.fx().sound(center, "executioners-slam");
        rocks(center, dived ? 11 : 7);
        Fx.View view = plugin.fx().view(center);
        view.particle(Fx.EXPLOSION, center.clone().add(0, 0.5, 0), dived ? 4 : 2, 0.6, 0.2, 0.6, 0);
        view.debris(center.clone().add(0, 0.2, 0), groundBlock(center), 60, 0.8);
        shockwave(center, radius, true);
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

    /**
     * A shockwave rolling out over the ground: a ring of the ground's own debris (with embers when
     * it is the slam) growing to the edge, cracks on the blocks it passes, dust and smoke.
     */
    private void shockwave(Location center, double radius, boolean embers) {
        BlockData ground = groundBlock(center);
        Location level = center.clone().add(0, 0.15, 0);
        int steps = 5;
        for (int k = 1; k <= steps; k++) {
            double r = radius * k / steps;
            plugin.visuals().later(k - 1, () -> {
                Fx.View view = plugin.fx().view(level);
                for (Location point : Shapes.ring(level, r, (int) (8 + r * 7), r)) {
                    view.debris(point, ground, 3, 0.15);
                    if (embers) {
                        view.particle(Fx.FLAME, point, 1, 0.1, 0.05, 0.1, 0.02);
                    }
                }
                view.particle(Fx.CLOUD, level, (int) (4 + r * 2), r * 0.6, 0.05, r * 0.6, 0.02);
            });
        }
        // Cracks across the ground blocks it reached (only shown), mending after a moment.
        Fx.View view = plugin.fx().view(center);
        List<Location> cracked = new ArrayList<>();
        int r = (int) Math.ceil(radius);
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz > radius * radius) {
                    continue;
                }
                Location block = center.clone().add(dx, -0.5, dz).getBlock().getLocation();
                if (block.getBlock().getType().isSolid()) {
                    cracked.add(block);
                }
            }
        }
        List<Integer> ids = new ArrayList<>();
        for (Location block : cracked) {
            double far = block.clone().add(0.5, 0, 0.5).distance(center) / Math.max(1.0, radius);
            int id = plugin.fx().crackId();
            ids.add(id);
            view.crack(block, (float) Math.max(0.1, 0.8 - 0.6 * far + random.nextDouble() * 0.2), id);
        }
        plugin.visuals().later(40, () -> {
            for (int i = 0; i < cracked.size(); i++) {
                view.crack(cracked.get(i), 0f, ids.get(i));
            }
        });
        if (embers) {
            view.particle(Fx.LAVA, level, 10, radius / 3, 0.1, radius / 3, 0);
            view.particle(Fx.EMBER_SMOKE, level, 6, radius / 3, 0.1, radius / 3, 0.02);
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
        gravestone(stone, direction);
        plugin.fx().sound(stone, "grave-rise-stone");
        Fx.View view = plugin.fx().view(stone);
        view.debris(stone.clone().add(0, 0.1, 0), groundBlock(stone), 30, 0.5);
        view.particle(Fx.SOUL, stone.clone().add(0, 0.6, 0), 4, 0.3, 0.3, 0.3, 0.02);
        view.particle(Fx.SMOKE, stone.clone().add(0, 0.2, 0), 3, 0.3, 0.1, 0.3, 0.01);
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

    /**
     * A gravestone of real deepslate rising out of the ground: a polished slab with a tiled cap,
     * turned across the line, sinking back after a moment.
     */
    private void gravestone(Location stone, Vector direction) {
        boolean alongX = Math.abs(direction.getX()) > Math.abs(direction.getZ());
        double[][] parts = {
                // width, height, depth, bottom
                {0.8, 1.05, 0.24, 0.0},
                {0.92, 0.16, 0.32, 1.05},
                {0.5, 0.14, 0.26, 1.21},
        };
        Material[] blocks = {Material.POLISHED_DEEPSLATE, Material.DEEPSLATE_TILES, Material.CHISELED_DEEPSLATE};
        Location base = stone.getBlock().getLocation().add(0.5, 0, 0.5);
        for (int i = 0; i < parts.length; i++) {
            double[] p = parts[i];
            float sx = (float) (alongX ? p[2] : p[0]);
            float sz = (float) (alongX ? p[0] : p[2]);
            float sy = (float) p[1];
            float y = (float) p[3];
            // Starts below the ground, rises smoothly, and sinks back later.
            Visuals.Effect part = plugin.visuals().block(blocks[i].createBlockData(), base)
                    .size(sx, sy, sz).offset(-sx / 2, y - 1.45f, -sz / 2).send(0);
            part.animate(1, 4, e -> e.offset(-sx / 2, y, -sz / 2));
            part.animate(30, 8, e -> e.offset(-sx / 2, y - 1.5f, -sz / 2));
            part.life(39);
        }
    }

    /** The last gravestone bursts as a tomb: a ring of force round it. */
    private void tomb(Player player, Location at) {
        AbilitySettings settings = plugin.settings().ability(Ability.GRAVE_RISE);
        double radius = settings.num("tomb-radius");
        if (!player.isOnline() || radius <= 0) {
            return;
        }
        plugin.fx().sound(at, "grave-tomb");
        rocks(at, 6);
        shockwave(at, radius, false);
        Fx.View view = plugin.fx().view(at);
        view.particle(Fx.SOUL, at.clone().add(0, 0.5, 0), 20, radius / 2, 0.3, radius / 2, 0.04);
        view.particle(Fx.SCULK_SOUL, at.clone().add(0, 0.8, 0), 12, 0.4, 0.5, 0.4, 0.05);
        view.along(Fx.SOUL_FIRE, Shapes.ring(at.clone().add(0, 0.15, 0), radius, (int) (10 + radius * 6), 0), 0.02);
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
        Fx.View view = plugin.fx().view(at);
        view.along(Fx.SOUL_FIRE, Shapes.ring(at, 1.4, 22, 0), 0.01);
        view.particle(Fx.SOUL, at.clone().add(0, 1.0, 0), 12, 0.4, 0.6, 0.4, 0.03);
    }
}
