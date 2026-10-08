package io.github.drepfy.legendary.ability;

import io.github.drepfy.legendary.Ability;
import io.github.drepfy.legendary.LegendaryPlugin;
import io.github.drepfy.legendary.WeaponType;
import io.github.drepfy.legendary.config.AbilitySettings;
import io.github.drepfy.legendary.item.WeaponItems;
import io.papermc.paper.event.entity.EntityPushedByEntityAttackEvent;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Crush, the war axe: timing. It rewards catching players in the air. Its look is weight and
 * azure power: the charged axe crackles, and a slam ends in a real impact where the player
 * lands: cracked ground, a column of dust, shards of the ground thrown up.
 * <ul>
 *   <li><b>Heavy</b> (passive): axe hits knock players back a little further, and a full-strength
 *   hit on a player in the air knocks them down and does a little extra damage.</li>
 *   <li><b>Crush</b> (Shift + F): the next full-strength hit on a player is an impact: they are
 *   slammed into the ground, taking more damage the higher up they were, and the ground breaks
 *   where they land.</li>
 * </ul>
 */
final class Crush implements Kit, Listener {

    private static final Color AZURE = Color.fromRGB(80, 190, 255);
    private static final Color STEEL = Color.fromRGB(200, 210, 225);
    /** How far down the ground is looked for, in blocks. */
    private static final int DEPTH = 32;

    private final LegendaryPlugin plugin;
    private final Armed crushes = new Armed();
    /** Attacker → what the hit now being dealt will do (decided before it lands). */
    private final Map<UUID, Impact> impacts = new HashMap<>();
    /** Player hit by Heavy this tick → who hit them: their knockback is made stronger. */
    private final Map<UUID, Shove> shoves = new HashMap<>();
    /** Players slammed down: the ground cracks when they land. */
    private final List<Landing> landings = new ArrayList<>();

    Crush(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    /** {@code crush}: the ability; otherwise Heavy's knock-down of a player in the air. */
    private record Impact(UUID target, boolean crush, double height) {
    }

    private record Shove(UUID attacker, long tick) {
    }

    private record Landing(Player target, long until, boolean big) {
    }

    @Override
    public WeaponType type() {
        return WeaponType.CRUSH;
    }

    @Override
    public long active(Player player, long now) {
        return crushes.left(player, now);
    }

    @Override
    public long activeLength() {
        return plugin.settings().ability(Ability.CRUSH).ticks("window");
    }

    @Override
    public void forget(Player player) {
        crushes.forget(player);
        impacts.remove(player.getUniqueId());
        shoves.remove(player.getUniqueId());
        landings.removeIf(landing -> landing.target().equals(player));
    }

    // ---- Crush -------------------------------------------------------------------------------------------------

    /** The axe is charged: azure sparks crackle off its head and a deep hum builds. */
    @Override
    public Result use(Player player, WeaponItems.Tag weapon) {
        int window = plugin.settings().ability(Ability.CRUSH).ticks("window");
        crushes.arm(player, plugin.tick() + window);
        plugin.fx().sound(player.getLocation(), "crush");
        Location hand = Geo.hand(player).add(0, 0.35, 0);
        Fx.View view = plugin.fx().view(hand);
        view.particle(Fx.SPARK, hand, 16, 0.25, 0.3, 0.25, 0.15);
        view.dust(hand, AZURE, 1.2f, 8, 0.25);
        view.dust(hand, STEEL, 0.9f, 4, 0.2);
        return Result.FIRED;
    }

    /** While a Crush waits: the axe head keeps crackling. */
    private void charged(Player player, long now) {
        Location hand = Geo.hand(player).add(0, 0.35, 0);
        Fx.View view = plugin.fx().view(hand);
        if (now % 3 == 0) {
            view.particle(Fx.SPARK, hand, 2, 0.15, 0.2, 0.15, 0.05);
        }
        if (now % 8 == 0) {
            view.dust(hand, AZURE, 0.8f, 1, 0.1);
        }
    }

    /** Extra damage of a Crush on a player this high above the ground (health points). */
    double crushDamage(double height) {
        AbilitySettings settings = plugin.settings().ability(Ability.CRUSH);
        double hearts = settings.num("damage") + settings.num("damage-per-block") * Math.max(0, height);
        return 2.0 * Math.min(settings.num("max-damage"), hearts);
    }

    @Override
    public void melee(Swing swing) {
        Player attacker = swing.attacker();
        impacts.remove(attacker.getUniqueId());
        if (!swing.charged()) {
            return;
        }
        Player target = swing.target();
        double height = height(target);
        if (crushes.armed(attacker, plugin.tick())) {
            impacts.put(attacker.getUniqueId(), new Impact(target.getUniqueId(), true, height));
        } else if (height >= plugin.settings().ability(Ability.HEAVY).num("airborne")) {
            impacts.put(attacker.getUniqueId(), new Impact(target.getUniqueId(), false, height));
        }
    }

    @Override
    public void landed(Swing swing) {
        Player attacker = swing.attacker();
        Player target = swing.target();
        Impact impact = impacts.remove(attacker.getUniqueId());
        if (impact == null || !impact.target().equals(target.getUniqueId())) {
            // A plain axe hit: Heavy makes the knockback that follows it stronger.
            shoves.put(target.getUniqueId(), new Shove(attacker.getUniqueId(), plugin.tick()));
            return;
        }
        double damage;
        double slam;
        if (impact.crush()) {
            crushes.spend(attacker);
            AbilitySettings settings = plugin.settings().ability(Ability.CRUSH);
            damage = crushDamage(impact.height());
            slam = settings.num("slam");
            plugin.fx().sound(target.getLocation(), "crush-hit");
        } else {
            AbilitySettings settings = plugin.settings().ability(Ability.HEAVY);
            damage = settings.num("damage") * 2.0;
            slam = settings.num("slam");
        }
        hitFx(target, impact.crush());
        boolean big = impact.crush();
        // After the hit's own knockback has been given: down they go.
        plugin.visuals().later(1, () -> {
            if (!attacker.isOnline() || !plugin.hits().hurt(attacker, target, damage)) {
                return;
            }
            Vector velocity = target.getVelocity();
            target.setVelocity(new Vector(velocity.getX() * 0.25, -slam, velocity.getZ() * 0.25));
            landings.add(new Landing(target, plugin.tick() + 60, big));
        });
    }

    /** Heavy: the knockback of a plain axe hit goes further. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onKnockback(EntityPushedByEntityAttackEvent event) {
        Shove shove = shoves.get(event.getEntity().getUniqueId());
        if (shove == null || shove.tick() != plugin.tick() || !event.getPushedBy().getUniqueId().equals(shove.attacker())) {
            return;
        }
        double factor = plugin.settings().ability(Ability.HEAVY).num("knockback");
        Vector knockback = event.getKnockback();
        event.setKnockback(new Vector(knockback.getX() * factor, knockback.getY(), knockback.getZ() * factor));
    }

    @Override
    public void tick(long now) {
        crushes.expire(now);
        for (Player player : crushes.waiting()) {
            charged(player, now);
        }
        shoves.values().removeIf(shove -> shove.tick() < now);
        for (Iterator<Landing> it = landings.iterator(); it.hasNext(); ) {
            Landing landing = it.next();
            Player target = landing.target();
            if (!target.isOnline() || target.isDead()) {
                it.remove();
            } else if (height(target) <= 0.15 || now >= landing.until()) {
                it.remove();
                impact(target, landing.big());
            } else if (landing.big() && now % 2 == 0) {
                Location at = Geo.middle(target);              // sparks trail them down
                plugin.fx().view(at).particle(Fx.SPARK, at, 3, 0.2, 0.3, 0.2, 0.02);
            }
        }
    }

    // ---- looks ------------------------------------------------------------------------------------------------

    /** The moment of the hit: azure sparks and a flash of steel. */
    private void hitFx(Player target, boolean big) {
        Location at = Geo.middle(target);
        Fx.View view = plugin.fx().view(at);
        view.particle(Fx.SPARK, at, big ? 24 : 8, 0.35, 0.45, 0.35, 0.25);
        view.particle(Fx.CRIT, at, big ? 14 : 6, 0.35, 0.5, 0.35, 0.4);
        if (big) {
            view.dust(at, AZURE, 1.6f, 14, 0.4);
        }
    }

    /**
     * They hit the ground: the ground cracks open round them, a column of dust and stone bursts
     * up, and shards of the ground itself are thrown into the air and fall back. Only shown: no
     * block is changed.
     */
    private void impact(Player target, boolean big) {
        Location feet = target.getLocation();
        Location ground = feet.clone();
        ground.setY(Math.floor(feet.getY() - height(target) + 1.0E-3));
        Location at = ground.clone().add(0, 0.03, 0);
        BlockData block = groundBlock(ground);
        double size = big ? 4.4 : 2.4;
        plugin.visuals().spawn("crush_crater", at).turn(Math.floorMod(target.getEntityId() * 37, 360)).size(0.5).send(0)
                .animate(1, 2, e -> e.size(size))
                .vanish(big ? 36 : 20, 10);
        Fx.View view = plugin.fx().view(at);
        view.particle(Fx.PILLAR, at.clone().add(0, 0.1, 0), big ? 40 : 14, size / 5, 0.1, size / 5, 0.0, block);
        view.debris(at.clone().add(0, 0.1, 0), block, big ? 40 : 16, size / 4);
        if (big) {
            plugin.fx().sound(at, "crush-impact");
            view.particle(Fx.EXPLOSION, at.clone().add(0, 0.3, 0), 1, 0, 0, 0, 0);
            view.particle(Fx.SPARK, at.clone().add(0, 0.2, 0), 20, size / 4, 0.1, size / 4, 0.1);
        } else {
            plugin.fx().sound(at, "heavy-thud");
        }
        int shards = big ? 6 : 3;
        for (int k = 0; k < shards; k++) {
            double angle = Math.toRadians(360.0 * k / shards + Math.floorMod(target.getEntityId(), 60));
            double reach = (big ? 1.6 : 1.0) * (0.8 + 0.4 * ((k * 7) % 5) / 4.0);
            Vector out = new Vector(Math.cos(angle), 0, Math.sin(angle));
            double piece = (big ? 0.32 : 0.24) * (0.8 + 0.1 * (k % 3));
            Location start = at.clone().add(out.clone().multiply(0.3));
            Location top = at.clone().add(out.clone().multiply(reach * 0.55)).add(0, (big ? 1.4 : 0.8) + 0.2 * (k % 2), 0);
            Location land = at.clone().add(out.clone().multiply(reach)).add(0, 0.05, 0);
            int spin = 70 + 40 * (k % 3);
            int yaw = k * 50;
            plugin.visuals().block(block, start).size(piece).tumble(0, yaw, 0).send(0)
                    .glide(1, top, 5)
                    .animate(1, 5, e -> e.tumble(spin, yaw + spin, spin / 2.0))
                    .glide(6, land, 6)
                    .animate(6, 6, e -> e.tumble(spin * 2, yaw + spin * 2, spin))
                    .vanish(16, 6);
        }
    }

    private static BlockData groundBlock(Location ground) {
        Block below = ground.clone().subtract(0, 0.5, 0).getBlock();
        Material type = below.getType();
        return (type.isSolid() ? type : Material.STONE).createBlockData();
    }

    /**
     * How far above the ground a player's feet are, in blocks (the highest ground under any
     * corner of them counts, so a player at the edge of a block is not "in the air").
     */
    static double height(Player player) {
        Location feet = player.getLocation();
        World world = feet.getWorld();
        if (world == null) {
            return 0;
        }
        double best = DEPTH;
        double half = Math.min(0.3, player.getWidth() / 2.0);
        for (double dx : new double[]{-half, half}) {
            for (double dz : new double[]{-half, half}) {
                int x = (int) Math.floor(feet.getX() + dx);
                int z = (int) Math.floor(feet.getZ() + dz);
                int top = (int) Math.floor(feet.getY() - 1.0E-3);
                for (int y = top; y >= Math.max(world.getMinHeight(), top - DEPTH); y--) {
                    if (world.getBlockAt(x, y, z).getType().isSolid()) {
                        best = Math.min(best, Math.max(0, feet.getY() - (y + 1)));
                        break;
                    }
                }
            }
        }
        return best;
    }
}
