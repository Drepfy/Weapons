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
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Kurogane, the katana: precision and sustained combat.
 * <ul>
 *   <li><b>Crescent Draw</b>: a crescent slash flies forward, hitting everything it passes once.</li>
 *   <li><b>Unbroken Edge</b>: fully charged hits on the same target build Edge; each stack adds
 *   damage, it fades without a hit, and Crescent Draw spends it for a stronger slash.</li>
 * </ul>
 */
final class Kurogane implements Kit {

    private static final Color STEEL = Color.fromRGB(235, 235, 240);
    private static final Color CRIMSON = Color.fromRGB(200, 25, 50);

    private final LegendaryPlugin plugin;
    private final Map<UUID, Edge> edges = new HashMap<>();

    Kurogane(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    /** One player's Edge on their current target. */
    private static final class Edge {
        UUID target;
        int stacks;
        long lastHit;
        long lastCounted = Long.MIN_VALUE / 2;
    }

    @Override
    public WeaponType type() {
        return WeaponType.KUROGANE;
    }

    private AbilitySettings edgeSettings() {
        return plugin.settings().ability(Ability.UNBROKEN_EDGE);
    }

    /** Stacks the player has right now (on any target), 0 when faded. */
    int stacks(Player player, long now) {
        Edge edge = edges.get(player.getUniqueId());
        if (edge == null || now - edge.lastHit > edgeSettings().ticks("window")) {
            return 0;
        }
        return edge.stacks;
    }

    private int stacksOn(Player player, Entity target, long now) {
        Edge edge = edges.get(player.getUniqueId());
        return edge != null && target.getUniqueId().equals(edge.target) ? stacks(player, now) : 0;
    }

    @Override
    public void melee(EntityDamageByEntityEvent event, Player attacker, LivingEntity target, WeaponItems.Tag weapon) {
        int stacks = stacksOn(attacker, target, plugin.tick());
        if (stacks > 0) {
            event.setDamage(event.getDamage() * (1.0 + stacks * edgeSettings().num("damage-per-stack") / 100.0));
        }
    }

    @Override
    public void landed(Player attacker, LivingEntity target, WeaponItems.Tag weapon) {
        long now = plugin.tick();
        AbilitySettings settings = edgeSettings();
        Edge edge = edges.computeIfAbsent(attacker.getUniqueId(), key -> new Edge());
        boolean sameChain = target.getUniqueId().equals(edge.target) && now - edge.lastHit <= settings.ticks("window");
        int before = sameChain ? edge.stacks : 0;
        if (!sameChain) {
            edge.target = target.getUniqueId();
            edge.stacks = 0;
            edge.lastCounted = Long.MIN_VALUE / 2;
        }
        edge.lastHit = now;
        if (now - edge.lastCounted < settings.ticks("min-hit-gap")) {
            return; // Spam clicking keeps the chain alive but does not build it.
        }
        edge.lastCounted = now;
        edge.stacks = Math.min(settings.whole("max-stacks"), edge.stacks + 1);
        if (edge.stacks > before) {
            plugin.fx().sound(target.getLocation(), "edge-stack", 0.15f * edge.stacks);
            plugin.fx().view(target.getLocation()).particle(Fx.MAGIC_CRIT, Geo.middle(target), 6 + 2 * edge.stacks,
                    0.3, 0.4, 0.3, 0.2);
        }
    }

    @Override
    public void hud(Player player, WeaponItems.Tag weapon, List<String> parts, long now) {
        int max = edgeSettings().whole("max-stacks");
        int stacks = stacks(player, now);
        StringBuilder bar = new StringBuilder();
        for (int i = 0; i < max; i++) {
            bar.append(plugin.settings().message(i < stacks ? "hud-edge-on" : "hud-edge-off"));
        }
        parts.add(Text.format(plugin.settings().message("hud-edge"), "stacks", Text.color(bar.toString())));
    }

    @Override
    public void forget(Player player) {
        edges.remove(player.getUniqueId());
    }

    // ---- Crescent Draw ----------------------------------------------------------------------------------

    @Override
    public Result use(Player player, WeaponItems.Tag weapon, Ability ability) {
        AbilitySettings settings = plugin.settings().ability(Ability.CRESCENT_DRAW);
        long now = plugin.tick();
        int spent = stacks(player, now);
        edges.remove(player.getUniqueId());
        double damage = settings.num("damage") + spent * settings.num("damage-per-edge");
        Location origin = player.getEyeLocation().subtract(0, 0.4, 0);
        Vector direction = origin.getDirection();
        direction.setY(Math.max(-0.5, Math.min(0.5, direction.getY()))).normalize();
        plugin.fx().sound(player.getLocation(), "crescent-draw", spent > 0 ? 0.1f : 0f);
        new Crescent(player, origin, direction, damage, spent, settings).runTaskTimer(plugin, 0L, 1L);
        return Result.FIRED;
    }

    /** The slash flying forward. */
    private final class Crescent extends BukkitRunnable {
        private final Player player;
        private final Location origin;
        private final Vector direction;
        private final Vector flat;
        private final Vector right;
        private final double damage;
        private final int spent;
        private final double range;
        private final double speed;
        private final double halfWidth;
        private final double knockback;
        private final double lift;
        private final Set<UUID> hit = new HashSet<>();
        private double travelled = 0.6;

        Crescent(Player player, Location origin, Vector direction, double damage, int spent, AbilitySettings settings) {
            this.player = player;
            this.origin = origin;
            this.direction = direction;
            Vector facing = direction.clone().setY(0);
            this.flat = facing.lengthSquared() < 1.0E-6 ? Geo.flat(player.getLocation()) : facing.normalize();
            this.right = Geo.right(flat);
            this.damage = damage;
            this.spent = spent;
            this.range = settings.num("range");
            this.speed = settings.num("speed");
            this.halfWidth = settings.num("width") / 2.0;
            this.knockback = settings.num("knockback");
            this.lift = settings.num("lift");
        }

        @Override
        public void run() {
            if (!player.isOnline()) {
                cancel();
                return;
            }
            Location center = null;
            for (int step = 0; step < 2; step++) {
                travelled += speed / 2.0;
                center = origin.clone().add(direction.clone().multiply(travelled));
                if (travelled > range || Geo.solid(center)) {
                    burst(center);
                    cancel();
                    return;
                }
                strike(center);
            }
            draw(center);
        }

        private void strike(Location center) {
            World world = center.getWorld();
            BoundingBox box = BoundingBox.of(center, halfWidth, 1.4, halfWidth);
            for (Entity entity : world.getNearbyEntities(box)) {
                if (!(entity instanceof LivingEntity target) || hit.contains(entity.getUniqueId())
                        || !plugin.hits().canTarget(player, entity)) {
                    continue;
                }
                hit.add(entity.getUniqueId());
                if (plugin.hits().hurt(player, target, damage)) {
                    Hits.knock(target, flat, knockback, lift);
                    plugin.fx().sound(target.getLocation(), "crescent-hit");
                    Fx.View view = plugin.fx().view(target.getLocation());
                    view.particle(Fx.CRIT, Geo.middle(target), 14, 0.3, 0.4, 0.3, 0.3);
                    view.dust(Geo.middle(target), CRIMSON, 1.3f, 8, 0.3);
                }
            }
        }

        /** A bowed blade of light, its tips trailing behind. */
        private void draw(Location center) {
            Fx.View view = plugin.fx().view(center);
            Color edge = spent > 0 ? CRIMSON : STEEL;
            for (int i = -7; i <= 7; i++) {
                double s = i / 7.0;
                Vector offset = right.clone().multiply(s * halfWidth)
                        .add(direction.clone().multiply(0.7 * (1.0 - s * s)))
                        .add(new Vector(0, -0.15 * s, 0));
                Location point = center.clone().add(offset);
                view.dust(point, i % 2 == 0 ? edge : STEEL, 1.1f, 1, 0.02);
                if (i % 7 == 0) {
                    view.particle(Fx.SWEEP, point, 1, 0, 0, 0, 0);
                }
            }
            view.particle(Fx.CRIT, center, 3, halfWidth / 2, 0.1, halfWidth / 2, 0.05);
        }

        private void burst(Location center) {
            if (center != null) {
                plugin.fx().view(center).particle(Fx.SWEEP, center, 2, 0.4, 0.2, 0.4, 0);
            }
        }
    }
}
