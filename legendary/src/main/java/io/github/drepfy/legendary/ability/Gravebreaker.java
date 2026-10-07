package io.github.drepfy.legendary.ability;

import io.github.drepfy.legendary.Ability;
import io.github.drepfy.legendary.LegendaryPlugin;
import io.github.drepfy.legendary.WeaponType;
import io.github.drepfy.legendary.config.AbilitySettings;
import io.github.drepfy.legendary.item.WeaponItems;
import io.github.drepfy.legendary.util.Compat;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Gravebreaker, the executioner's axe: the ground itself, and an iron will.
 * <ul>
 *   <li><b>Earthsplitter</b>: the axe splits the ground, and a fissure tears forward; rock bursts
 *   up along it, launching and slowing whoever stands there. No block is changed.</li>
 *   <li><b>Iron Bastion</b>: brace for a few seconds: far less damage and no knockback. Then (or
 *   when pressed again) a shockwave is released that hits everyone round you, harder for every
 *   bit of damage you took while braced.</li>
 *   <li><b>Headsman</b> (passive): axe hits on players low on health hit harder, and a kill
 *   gives you Regeneration.</li>
 * </ul>
 */
final class Gravebreaker implements Kit, Listener {

    private final LegendaryPlugin plugin;
    private final NamespacedKey braceKey;
    private final Map<UUID, Bastion> bastions = new HashMap<>();
    private final Random random = new Random();

    Gravebreaker(LegendaryPlugin plugin) {
        this.plugin = plugin;
        this.braceKey = new NamespacedKey(plugin, "iron_bastion");
    }

    private static final class Bastion {
        final Player player;
        final long until;
        final Visuals.Effect ring;
        double stored;

        Bastion(Player player, long until, Visuals.Effect ring) {
            this.player = player;
            this.until = until;
            this.ring = ring;
        }
    }

    @Override
    public WeaponType type() {
        return WeaponType.GRAVEBREAKER;
    }

    @Override
    public Result use(Player player, WeaponItems.Tag weapon, Ability ability) {
        return ability == Ability.IRON_BASTION ? brace(player) : split(player);
    }

    @Override
    public boolean recast(Player player, WeaponItems.Tag weapon, Ability ability) {
        Bastion bastion = ability == Ability.IRON_BASTION ? bastions.remove(player.getUniqueId()) : null;
        if (bastion == null) {
            return false;
        }
        release(bastion); // Pressed again: let it out now.
        return true;
    }

    @Override
    public long active(Player player, WeaponItems.Tag weapon, Ability ability, long now) {
        Bastion bastion = ability == Ability.IRON_BASTION ? bastions.get(player.getUniqueId()) : null;
        return bastion == null ? 0 : Math.max(0, bastion.until - now);
    }

    @Override
    public long activeLength(Ability ability) {
        return ability == Ability.IRON_BASTION ? plugin.settings().ability(ability).ticks("duration") : 0;
    }

    @Override
    public void forget(Player player) {
        Bastion bastion = bastions.remove(player.getUniqueId());
        if (bastion != null) {
            bastion.ring.remove();
            Compat.unshakable(player, braceKey, false);
        }
    }

    @Override
    public void tick(long now) {
        for (Iterator<Bastion> it = bastions.values().iterator(); it.hasNext(); ) {
            Bastion bastion = it.next();
            if (!bastion.player.isOnline() || bastion.player.isDead()) {
                bastion.ring.remove();
                Compat.unshakable(bastion.player, braceKey, false);
                it.remove();
            } else if (now >= bastion.until) {
                it.remove();
                release(bastion);
            } else if (now % 4 == 0) {
                Location at = bastion.player.getLocation().add(0, 0.2, 0);
                plugin.fx().view(at).particle(Fx.FLAME, at, 4, 0.6, 0.1, 0.6, 0.01);
            }
        }
    }

    // ---- Earthsplitter ---------------------------------------------------------------------------------------

    private Result split(Player player) {
        AbilitySettings settings = plugin.settings().ability(Ability.EARTHSPLITTER);
        Location start = player.getLocation();
        World world = start.getWorld();
        Vector direction = Geo.flat(start);
        int ground = start.getBlockY() - 1;
        Set<UUID> hit = new HashSet<>();
        int placed = 0;
        for (double d = 1.0; d <= settings.num("range") + 1.0E-6; d += 1.0) {
            Location point = start.clone().add(direction.clone().multiply(d));
            int y = Geo.groundY(world, point.getX(), point.getZ(), ground);
            if (y == Integer.MIN_VALUE) {
                break; // A wall or a drop: the fissure stops there.
            }
            ground = y;
            Location crack = new Location(world, point.getX(), y + 1.0, point.getZ());
            plugin.visuals().later(placed / 2, () -> erupt(player, crack, direction, hit));
            placed++;
        }
        if (placed == 0) {
            return Result.FAILED;
        }
        plugin.fx().sound(start, "earthsplitter");
        plugin.visuals().spawn("shockwave", start.clone().add(0, 0.06, 0)).size(0.8).send(0)
                .animate(1, 4, e -> e.size(3.2))
                .vanish(9, 5);
        return Result.FIRED;
    }

    /** Rock bursts up at one point of the fissure. */
    private void erupt(Player player, Location crack, Vector direction, Set<UUID> hit) {
        if (!player.isOnline()) {
            return;
        }
        AbilitySettings settings = plugin.settings().ability(Ability.EARTHSPLITTER);
        BlockData rock = groundBlock(crack);
        Vector side = Geo.right(direction);
        for (int i = 0; i < 2; i++) {
            double across = (i == 0 ? -0.35 : 0.35) + (random.nextDouble() - 0.5) * 0.2;
            Location at = crack.clone().add(side.clone().multiply(across));
            float size = (float) (0.55 + random.nextDouble() * 0.3);
            Visuals.Effect shard = plugin.visuals().block(rock, at).size(size).offset(-size / 2, -1.1, -size / 2).send(0);
            shard.animate(1, 3, e -> e.offset(-size / 2, 0.2 + random.nextDouble() * 0.5, -size / 2).turn(random.nextInt(50) - 25));
            shard.animate(18, 6, e -> e.offset(-size / 2, -1.2, -size / 2));
            shard.life(25);
        }
        plugin.visuals().spawn("shockwave", crack.clone().add(0, 0.06, 0)).turn(random.nextInt(360)).size(0.6).send(0)
                .animate(1, 3, e -> e.size(2.2))
                .vanish(6, 4);
        Fx.View view = plugin.fx().view(crack);
        view.debris(crack.clone().add(0, 0.1, 0), rock, 24, 0.5);
        view.particle(Fx.CLOUD, crack.clone().add(0, 0.2, 0), 3, 0.3, 0.05, 0.3, 0.02);
        double width = settings.num("width");
        for (LivingEntity target : plugin.hits().around(player, crack.clone().add(0, 1.0, 0), width + 0.6)) {
            double dy = target.getLocation().getY() - crack.getY();
            if (dy < -0.6 || dy > 2.5 || Geo.flatDistance(target.getLocation(), crack) > width
                    || !hit.add(target.getUniqueId())) {
                continue;
            }
            if (plugin.hits().hurt(player, target, settings.num("damage"))) {
                Hits.knock(target, direction, 0.3, settings.num("launch"));
                Hits.effect(target, "slowness", settings.whole("slow-level"), settings.ticks("slow-duration"));
            }
        }
    }

    private static BlockData groundBlock(Location at) {
        Block below = at.clone().subtract(0, 0.5, 0).getBlock();
        Material type = below.getType();
        return (type.isSolid() ? type : Material.DEEPSLATE).createBlockData();
    }

    // ---- Iron Bastion ----------------------------------------------------------------------------------------

    private Result brace(Player player) {
        AbilitySettings settings = plugin.settings().ability(Ability.IRON_BASTION);
        int ticks = settings.ticks("duration");
        Hits.effect(player, "resistance", settings.whole("resistance-level"), ticks);
        Compat.unshakable(player, braceKey, true);
        Visuals.Effect ring = plugin.visuals().spawn("ember_ring", player.getLocation().add(0, 0.08, 0)).size(0.5).send(0)
                .animate(1, 4, e -> e.size(2.6))
                .follow(player, new Vector(0, 0.08, 0), ticks + 2);
        for (int t = 8; t < ticks; t += 8) {
            int step = t / 8;
            ring.animate(t, 8, e -> e.size(step % 2 == 0 ? 2.6 : 3.0).turn(step * 45));
        }
        Bastion old = bastions.put(player.getUniqueId(), new Bastion(player, plugin.tick() + ticks, ring));
        if (old != null) {
            old.ring.remove();
        }
        plugin.fx().sound(player.getLocation(), "iron-bastion");
        return Result.FIRED;
    }

    /** Damage taken while braced is stored for the release. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHurt(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && !plugin.hits().probing()) {
            Bastion bastion = bastions.get(player.getUniqueId());
            if (bastion != null) {
                bastion.stored += Math.max(0.0, event.getFinalDamage());
            }
        }
    }

    private void release(Bastion bastion) {
        Player player = bastion.player;
        Compat.unshakable(player, braceKey, false);
        AbilitySettings settings = plugin.settings().ability(Ability.IRON_BASTION);
        double radius = settings.num("radius");
        bastion.ring.animate(1, 3, e -> e.size(radius * 1.6)).vanish(5, 5);
        if (!player.isOnline() || player.isDead()) {
            return;
        }
        Location center = player.getLocation();
        plugin.fx().sound(center, "iron-bastion-release");
        plugin.visuals().spawn("shockwave", center.clone().add(0, 0.06, 0)).size(1.0).turn(random.nextInt(360)).send(0)
                .animate(1, 6, e -> e.size(radius * 2.2))
                .vanish(18, 8);
        Fx.View view = plugin.fx().view(center);
        view.particle(Fx.EXPLOSION, center.clone().add(0, 0.5, 0), 2, 0.6, 0.2, 0.6, 0);
        view.debris(center.clone().add(0, 0.2, 0), groundBlock(center), 60, radius / 2);
        double damage = settings.num("damage") + Math.min(bastion.stored, settings.num("stored-cap"));
        if (damage <= 0) {
            return;
        }
        for (LivingEntity target : plugin.hits().around(player, center.clone().add(0, 0.8, 0), radius)) {
            if (plugin.hits().hurt(player, target, damage)) {
                Vector away = Geo.away(center, target.getLocation(), Geo.flat(center));
                Hits.knock(target, away, settings.num("knockback"), settings.num("lift"));
            }
        }
    }

    /** A crash or a plugin reload mid-brace: never leave the player knockback-proof. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Compat.unshakable(event.getPlayer(), braceKey, false);
    }

    // ---- Headsman (passive) ----------------------------------------------------------------------------------

    @Override
    public void melee(EntityDamageByEntityEvent event, Player attacker, LivingEntity target, WeaponItems.Tag weapon) {
        if (!(target instanceof Player)) {
            return;
        }
        AbilitySettings settings = plugin.settings().ability(Ability.HEADSMAN);
        double max = Compat.maxHealth(target);
        if (target.getHealth() < max * settings.num("threshold")) {
            event.setDamage(event.getDamage() * (1.0 + settings.num("bonus")));
            Location at = Geo.middle(target);
            plugin.fx().view(at).particle(Fx.SOUL, at, 6, 0.3, 0.4, 0.3, 0.02);
        }
    }

    /** A kill with the axe in hand: Regeneration. */
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
        AbilitySettings settings = plugin.settings().ability(Ability.HEADSMAN);
        Hits.effect(killer, "regeneration", settings.whole("regen-level"), settings.ticks("regen-duration"));
        Location at = killer.getLocation().add(0, 0.1, 0);
        plugin.visuals().spawn("ember_ring", at).size(0.5).send(0)
                .animate(1, 4, e -> e.size(3.6))
                .vanish(9, 6);
    }
}
