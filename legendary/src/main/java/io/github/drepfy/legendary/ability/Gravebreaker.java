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
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.BoundingBox;
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
 * Gravebreaker, the executioner's axe: ground control and heavy hits.
 * <ul>
 *   <li><b>Earthsplitter</b>: a shockwave cracks along the ground, throwing players up and
 *   giving them Mining Fatigue. No block is ever changed: the cracks are only shown.</li>
 *   <li><b>Executioner's Mark</b>: a few axe hits on the same player mark them; the next
 *   Earthsplitter to hit them throws them much harder and uses up the mark.</li>
 * </ul>
 */
final class Gravebreaker implements Kit {

    private static final Color DUST_BROWN = Color.fromRGB(70, 55, 45);
    private static final Color BLOOD = Color.fromRGB(140, 0, 10);

    private final LegendaryPlugin plugin;
    /** Attacker → hits counted on their current target. */
    private final Map<UUID, Count> counts = new HashMap<>();
    /** Marked entity → mark. */
    private final Map<UUID, Mark> marks = new HashMap<>();

    Gravebreaker(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    private static final class Count {
        UUID target;
        int hits;
        long lastHit;
        long lastCounted = Long.MIN_VALUE / 2;
    }

    private record Mark(UUID by, String targetName, long until) {
    }

    @Override
    public WeaponType type() {
        return WeaponType.GRAVEBREAKER;
    }

    // ---- Executioner's Mark -------------------------------------------------------------------------------

    @Override
    public void landed(Player attacker, LivingEntity target, WeaponItems.Tag weapon) {
        AbilitySettings settings = plugin.settings().ability(Ability.EXECUTIONERS_MARK);
        long now = plugin.tick();
        Count count = counts.computeIfAbsent(attacker.getUniqueId(), key -> new Count());
        if (!target.getUniqueId().equals(count.target) || now - count.lastHit > settings.ticks("hit-window")) {
            count.target = target.getUniqueId();
            count.hits = 0;
            count.lastCounted = Long.MIN_VALUE / 2;
        }
        count.lastHit = now;
        if (now - count.lastCounted < settings.ticks("min-hit-gap")) {
            return;
        }
        count.lastCounted = now;
        count.hits++;
        if (count.hits < settings.whole("hits")) {
            return;
        }
        count.hits = 0;
        int duration = settings.ticks("duration");
        String name = target instanceof Player player ? player.getName() : target.getName();
        marks.put(target.getUniqueId(), new Mark(attacker.getUniqueId(), name, now + duration));
        plugin.fx().sound(target.getLocation(), "executioners-mark");
        Fx.View view = plugin.fx().view(target.getLocation());
        view.dust(target.getLocation().add(0, target.getHeight() + 0.4, 0), BLOOD, 2.0f, 16, 0.3);
        view.particle(Fx.SOUL, Geo.middle(target), 10, 0.3, 0.5, 0.3, 0.02);
        if (target instanceof Player victim) {
            plugin.send(victim, "marked", "time", Text.countdown(duration));
        }
        plugin.send(attacker, "mark-applied", "player", name, "time", Text.countdown(duration));
    }

    /** Uses up the mark on this entity. @return whether it was marked */
    private boolean consumeMark(Entity entity, long now) {
        Mark mark = marks.remove(entity.getUniqueId());
        return mark != null && now < mark.until();
    }

    @Override
    public void hud(Player player, WeaponItems.Tag weapon, List<String> parts, long now) {
        for (Mark mark : marks.values()) {
            if (mark.by().equals(player.getUniqueId()) && now < mark.until()) {
                parts.add(Text.format(plugin.settings().message("hud-mark"), "player", mark.targetName(),
                        "time", Text.countdown(mark.until() - now)));
                return;
            }
        }
        Count count = counts.get(player.getUniqueId());
        AbilitySettings settings = plugin.settings().ability(Ability.EXECUTIONERS_MARK);
        if (count != null && count.hits > 0 && now - count.lastHit <= settings.ticks("hit-window")) {
            parts.add(Text.format(plugin.settings().message("hud-mark-building"), "hits", count.hits,
                    "needed", settings.whole("hits")));
        }
    }

    @Override
    public void tick(long now) {
        if (now % 4 != 0 || marks.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<UUID, Mark>> it = marks.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Mark> entry = it.next();
            Entity entity = Bukkit.getEntity(entry.getKey());
            if (now >= entry.getValue().until() || !(entity instanceof LivingEntity living) || living.isDead()) {
                it.remove();
                continue;
            }
            // A slowly turning ring of blood over the marked player's head, seen by everyone.
            Location head = entity.getLocation().add(0, entity.getHeight() + 0.45, 0);
            Fx.View view = plugin.fx().view(head);
            for (int i = 0; i < 8; i++) {
                double angle = now * 0.15 + Math.PI * 2 * i / 8;
                view.dust(head.clone().add(Math.cos(angle) * 0.45, 0, Math.sin(angle) * 0.45), BLOOD, 1.0f, 1, 0.0);
            }
        }
    }

    @Override
    public void forget(Player player) {
        counts.remove(player.getUniqueId());
        marks.remove(player.getUniqueId());
    }

    // ---- Earthsplitter -------------------------------------------------------------------------------------

    @Override
    public Result use(Player player, WeaponItems.Tag weapon, Ability ability) {
        AbilitySettings settings = plugin.settings().ability(Ability.EARTHSPLITTER);
        Location start = player.getLocation();
        Vector direction = Geo.flat(start);
        Location floor = Geo.floorBelow(start.getWorld(), start.getX(), start.getY() - 0.01, start.getZ(), 2);
        if (floor == null) {
            plugin.hud().flash(player, Text.format(plugin.settings().message("no-target"), "range", 2));
            return Result.FAILED; // Nothing to smash in mid-air.
        }
        plugin.fx().sound(start, "earthsplitter");
        Block ground = start.getWorld().getBlockAt(floor.getBlockX(), floor.getBlockY() - 1, floor.getBlockZ());
        Fx.View view = plugin.fx().view(start);
        Location impact = floor.clone().add(direction.clone().multiply(0.8));
        view.debris(impact, ground.getBlockData(), 40, 0.6);
        view.particle(Fx.CLOUD, impact, 10, 0.4, 0.05, 0.4, 0.05);
        new Wave(player, start, direction, floor.getBlockY() - 1, settings).runTaskTimer(plugin, 0L, 1L);
        return Result.FIRED;
    }

    /** The crack running along the ground. */
    private final class Wave extends BukkitRunnable {
        private final Player player;
        private final Location start;
        private final Vector direction;
        private final Vector right;
        private final AbilitySettings settings;
        private final Set<UUID> hit = new HashSet<>();
        private final List<Location> cracked = new ArrayList<>();
        private final List<Integer> crackIds = new ArrayList<>();
        private int groundY;
        private double travelled = 0.5;
        private int steps;

        Wave(Player player, Location start, Vector direction, int groundY, AbilitySettings settings) {
            this.player = player;
            this.start = start;
            this.direction = direction;
            this.right = Geo.right(direction);
            this.groundY = groundY;
            this.settings = settings;
        }

        @Override
        public void run() {
            if (!player.isOnline()) {
                finish();
                return;
            }
            for (int step = 0; step < 2; step++) {
                travelled += settings.num("speed") / 2.0;
                if (travelled > settings.num("range")) {
                    finish();
                    return;
                }
                Location column = start.clone().add(direction.clone().multiply(travelled));
                int y = Geo.groundY(column.getWorld(), column.getX(), column.getZ(), groundY);
                if (y == Integer.MIN_VALUE) {
                    finish(); // A wall or a drop stops the crack.
                    return;
                }
                groundY = y;
                Location surface = new Location(column.getWorld(), column.getX(), y + 1.0, column.getZ());
                crack(surface, step == 0);
                strike(surface);
            }
        }

        private void crack(Location surface, boolean visuals) {
            World world = surface.getWorld();
            Block ground = world.getBlockAt(surface.getBlockX(), groundY, surface.getBlockZ());
            Fx.View view = plugin.fx().view(surface);
            view.debris(surface, ground.getBlockData(), 14, 0.5);
            if (!visuals) {
                return;
            }
            steps++;
            view.dust(surface.clone().add(0, 0.1, 0), DUST_BROWN, 1.6f, 6, 0.4);
            for (double side : new double[] {-0.9, 0, 0.9}) {
                Location block = world.getBlockAt(surface.clone().add(right.clone().multiply(side))
                        .subtract(0, 1, 0)).getLocation();
                if (!block.getBlock().getType().isSolid() || cracked.contains(block)) {
                    continue;
                }
                int id = plugin.fx().crackId();
                cracked.add(block);
                crackIds.add(id);
                view.crack(block, 0.55f + (float) Math.random() * 0.35f, id);
            }
            if (steps % 2 == 1) {
                plugin.fx().sound(surface, "earthsplitter-crack");
            }
        }

        private void strike(Location surface) {
            double half = settings.num("width") / 2.0;
            BoundingBox box = new BoundingBox(surface.getX() - half, surface.getY() - 0.5, surface.getZ() - half,
                    surface.getX() + half, surface.getY() + 2.5, surface.getZ() + half);
            long now = plugin.tick();
            for (Entity entity : surface.getWorld().getNearbyEntities(box)) {
                if (!(entity instanceof LivingEntity target) || hit.contains(entity.getUniqueId())
                        || !plugin.hits().canTarget(player, entity)) {
                    continue;
                }
                hit.add(entity.getUniqueId());
                if (!plugin.hits().hurt(player, target, settings.num("damage"))) {
                    continue;
                }
                boolean marked = consumeMark(entity, now);
                double multiplier = marked ? settings.num("marked-multiplier") : 1.0;
                double launch = Math.min(settings.num("max-launch"), settings.num("launch") * multiplier);
                Hits.knock(target, direction, settings.num("push") * multiplier, launch);
                int fatigue = settings.whole("fatigue-level");
                PotionEffectType type = Compat.effect("mining_fatigue");
                if (fatigue > 0 && type != null) {
                    target.addPotionEffect(new PotionEffect(type, settings.ticks("fatigue-duration"), fatigue - 1,
                            false, true, true));
                }
                Fx.View view = plugin.fx().view(entity.getLocation());
                view.debris(entity.getLocation(), surface.getWorld().getBlockAt(surface.getBlockX(), groundY,
                        surface.getBlockZ()).getBlockData(), 25, 0.4);
                if (marked) {
                    plugin.fx().sound(entity.getLocation(), "executioners-mark");
                    view.dust(Geo.middle(entity), BLOOD, 2.2f, 30, 0.6);
                    view.particle(Fx.SOUL, Geo.middle(entity), 15, 0.4, 0.6, 0.4, 0.05);
                }
            }
        }

        private void finish() {
            cancel();
            if (cracked.isEmpty()) {
                return;
            }
            List<Location> blocks = new ArrayList<>(cracked);
            List<Integer> ids = new ArrayList<>(crackIds);
            // The cracks fade after two seconds; the blocks themselves never changed.
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                Fx.View view = plugin.fx().view(blocks.get(0));
                for (int i = 0; i < blocks.size(); i++) {
                    view.crack(blocks.get(i), 0f, ids.get(i));
                }
            }, 40L);
        }
    }
}
