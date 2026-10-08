package io.github.drepfy.legendary.ability;

import io.github.drepfy.legendary.Ability;
import io.github.drepfy.legendary.LegendaryPlugin;
import io.github.drepfy.legendary.WeaponType;
import io.github.drepfy.legendary.config.AbilitySettings;
import io.github.drepfy.legendary.item.WeaponItems;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * The Candy Cane: control the ground and slow them down. Its look is sticky pink candy goo:
 * blobs flung from the cane splat into wobbling pink puddles, and goo bursts and clings.
 * <ul>
 *   <li><b>Sticky Sweet</b> (passive): a hit may stick to them: Slowness I for a moment. It never
 *   stacks, and the same player cannot be stuck again until a few seconds have passed.</li>
 *   <li><b>Sugar Trap</b> (Shift + F): up to five blobs of candy goo are flung out round you and
 *   splat on the ground as sticky pink puddles. An enemy who walks over one is poisoned, made
 *   dizzy and slowed, and the puddle bursts. Your own puddles never catch you.</li>
 * </ul>
 */
final class CandyCane implements Kit {

    private static final Color PINK = Color.fromRGB(255, 92, 165);
    private static final Color PALE = Color.fromRGB(255, 190, 220);
    private static final Color SUGAR = Color.fromRGB(255, 245, 250);
    /** How close (sideways) to a trap's middle a player's feet must come to set it off. */
    private static final double TRIGGER = 0.8;
    /** Ticks a blob is in the air before it lands. */
    private static final int FLIGHT = 8;

    private final LegendaryPlugin plugin;
    private final List<Trap> traps = new ArrayList<>();
    /** Player → tick from which Sticky Sweet may slow them again. */
    private final Map<UUID, Long> stuck = new HashMap<>();
    private final Random random = new Random();
    private ItemStack goo;

    CandyCane(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    /** A trap: flying until {@code active}, then a puddle until {@code until}. */
    private static final class Trap {
        final UUID owner;
        final Location at;
        final long active;
        final long until;
        Visuals.Effect model;

        Trap(UUID owner, Location at, long active, long until, Visuals.Effect model) {
            this.owner = owner;
            this.at = at;
            this.active = active;
            this.until = until;
            this.model = model;
        }
    }

    @Override
    public WeaponType type() {
        return WeaponType.CANDY_CANE;
    }

    /** Ticks until the player's traps are gone (while any are still out). */
    @Override
    public long active(Player player, long now) {
        long left = 0;
        for (Trap trap : traps) {
            if (trap.owner.equals(player.getUniqueId())) {
                left = Math.max(left, trap.until - now);
            }
        }
        return left;
    }

    @Override
    public long activeLength() {
        return plugin.settings().ability(Ability.SUGAR_TRAP).ticks("lifetime") + FLIGHT;
    }

    @Override
    public void forget(Player player) {
        for (Iterator<Trap> it = traps.iterator(); it.hasNext(); ) {
            Trap trap = it.next();
            if (trap.owner.equals(player.getUniqueId())) {
                trap.model.remove();
                it.remove();
            }
        }
        stuck.remove(player.getUniqueId());
    }

    // ---- Sugar Trap ----------------------------------------------------------------------------------------------

    @Override
    public Result use(Player player, WeaponItems.Tag weapon) {
        AbilitySettings settings = plugin.settings().ability(Ability.SUGAR_TRAP);
        List<Location> spots = spots(player, settings.whole("traps"), settings.num("radius"));
        if (spots.isEmpty()) {
            plugin.notice(player, "no-room");
            return Result.FAILED;
        }
        forget(player); // Any traps still out from before make way (only one set at a time).
        long now = plugin.tick();
        int life = settings.ticks("lifetime");
        Location hand = Geo.hand(player);
        plugin.fx().sound(player.getLocation(), "sugar-trap");
        plugin.fx().view(hand).particle(Fx.ITEM, hand, 8, 0.15, 0.15, 0.15, 0.08, goo());
        for (int i = 0; i < spots.size(); i++) {
            Location spot = spots.get(i);
            int delay = i;                                  // flung one after another
            Location peak = hand.clone().add(spot).multiply(0.5).add(0, 1.0 + 0.15 * hand.distance(spot), 0);
            Visuals.Effect blob = plugin.visuals().spawn("candy_goo", hand).billboard().size(0.25).send(0)
                    .animate(1, 3, e -> e.size(0.6))
                    .glide(1 + delay, peak, FLIGHT / 2)
                    .glide(1 + delay + FLIGHT / 2, spot.clone().add(0, 0.15, 0), FLIGHT / 2);
            Trap trap = new Trap(player.getUniqueId(), spot, now + FLIGHT + 1 + delay, now + FLIGHT + 1 + delay + life,
                    blob);
            traps.add(trap);
            plugin.visuals().later(FLIGHT + 1 + delay, () -> splat(trap, life));
        }
        return Result.FIRED;
    }

    /** A blob lands: it splats into a sticky puddle that wobbles gently on the ground. */
    private void splat(Trap trap, int life) {
        if (!traps.contains(trap)) {
            trap.model.remove();
            return;
        }
        trap.model.remove();
        Location at = trap.at.clone().add(0, 0.02, 0);
        // turned a little either way so no two look the same, but not so far that the light on the
        // goo (painted from the north) seems to come from somewhere else on each
        double turn = Math.toRadians((random.nextDouble() - 0.5) * 80);
        Vector way = new Vector(Math.sin(turn), 0, Math.cos(turn));
        Visuals.Effect puddle = plugin.visuals().spawn("candy_puddle", at).facing(way).size(0.2).send(0)
                .animate(1, 2, e -> e.size(1.45, 1, 1.3))
                .animate(3, 3, e -> e.size(0.95, 1, 1.0))
                .animate(6, 4, e -> e.size(1.05, 1, 1.0));
        for (int t = 12; t < life; t += 16) {
            int step = t;
            puddle.animate(t, 8, e -> e.size(1.08, 1, 0.97).turn(4 * Math.sin(step)))
                    .animate(t + 8, 8, e -> e.size(0.98, 1, 1.07).turn(-4 * Math.sin(step)));
        }
        puddle.life(life + 12);
        trap.model = puddle;
        plugin.fx().sound(at, "sugar-trap-splat");
        Fx.View view = plugin.fx().view(at);
        view.particle(Fx.ITEM, at.clone().add(0, 0.15, 0), 10, 0.25, 0.05, 0.25, 0.14, goo());
        view.dust(at.clone().add(0, 0.1, 0), PINK, 1.2f, 6, 0.3);
    }

    /**
     * Where the traps go: evenly round the player (the first straight ahead), each on the ground
     * with room above it, and not on the far side of a wall. Spots without ground are skipped.
     */
    private List<Location> spots(Player player, int count, double radius) {
        List<Location> spots = new ArrayList<>();
        Location center = player.getLocation();
        World world = center.getWorld();
        if (world == null) {
            return spots;
        }
        Vector ahead = Geo.flat(center);
        Location eye = center.clone().add(0, 0.6, 0);
        for (int i = 0; i < count; i++) {
            double angle = Math.toRadians(360.0 * i / count);
            Vector way = ahead.clone().rotateAroundY(angle);
            double x = center.getX() + way.getX() * radius;
            double z = center.getZ() + way.getZ() * radius;
            int ground = Geo.groundY(world, x, z, center.getBlockY() - 1);
            if (ground == Integer.MIN_VALUE) {
                continue;
            }
            Location spot = new Location(world, x, ground + 1.0, z);
            if (Geo.clear(eye, spot.clone().add(0, 0.5, 0))) {
                spots.add(spot);
            }
        }
        return spots;
    }

    @Override
    public void tick(long now) {
        AbilitySettings settings = plugin.settings().ability(Ability.SUGAR_TRAP);
        for (Iterator<Trap> it = traps.iterator(); it.hasNext(); ) {
            Trap trap = it.next();
            Player owner = Bukkit.getPlayer(trap.owner);
            if (owner == null || !owner.isOnline() || now >= trap.until) {
                dryUp(trap);
                it.remove();
                continue;
            }
            if (now < trap.active) {
                continue;                                   // still flying
            }
            int phase = Math.floorMod(trap.at.getBlockX() * 7 + trap.at.getBlockZ() * 13, 14);
            if ((now + phase) % 14 == 0) {                  // a sticky bubble now and then
                Location top = trap.at.clone().add(0, 0.12, 0);
                Fx.View view = plugin.fx().view(top);
                view.dust(top, (now / 14) % 2 == 0 ? PINK : PALE, 0.8f, 1, 0.22);
                if ((now + phase) % 28 == 0) {
                    view.particle(Fx.ITEM, top, 1, 0.2, 0.0, 0.2, 0.04, goo());
                }
            }
            Player victim = victim(owner, trap);
            if (victim != null) {
                spring(victim, trap, settings);
                it.remove();
            }
        }
    }

    /** The puddle dries up and is gone. */
    private void dryUp(Trap trap) {
        trap.model.vanish(0, 8);
        Location at = trap.at.clone().add(0, 0.1, 0);
        plugin.fx().view(at).dust(at, PALE, 0.7f, 3, 0.25);
    }

    /** An enemy walking over the trap, if any (protection plugins are asked first). */
    private Player victim(Player owner, Trap trap) {
        Location at = trap.at;
        World world = at.getWorld();
        if (world == null) {
            return null;
        }
        for (Entity entity : world.getNearbyEntities(at, TRIGGER, 1.0, TRIGGER)) {
            if (!(entity instanceof Player player) || !plugin.hits().canTarget(owner, player)) {
                continue;
            }
            Location feet = player.getLocation();
            double dy = feet.getY() - at.getY();
            if (dy < -0.5 || dy > 0.75 || Geo.flatDistance(feet, at) > TRIGGER) {
                continue;
            }
            if (plugin.hits().allowed(owner, player)) {
                return player;
            }
        }
        return null;
    }

    /** Stepped in: the goo bursts up round their feet and clings to them for a moment. */
    private void spring(Player victim, Trap trap, AbilitySettings settings) {
        Hits.effect(victim, "poison", settings.whole("poison-level"), settings.ticks("poison-duration"));
        Hits.effect(victim, "nausea", 1, settings.ticks("nausea-duration"));
        Hits.effect(victim, "slowness", settings.whole("slow-level"), settings.ticks("slow-duration"));
        // The foot sinks in: the goo squashes out along the way they stepped in, springs back
        // the other way, wobbles once more, and bursts.
        Vector way = victim.getLocation().toVector().subtract(trap.at.toVector()).setY(0);
        if (way.lengthSquared() < 1.0E-4) {
            way = Geo.flat(victim.getLocation());
        }
        way.normalize();
        trap.model.stop().stretch(way).size(1.05, 1, 1.05).send(0)
                .animate(1, 2, e -> e.size(0.82, 1, 1.62))
                .animate(3, 3, e -> e.size(1.44, 1, 0.96))
                .animate(6, 3, e -> e.size(1.1, 1, 1.36))
                .animate(9, 3, e -> e.size(1.28, 1, 1.18))
                .vanish(12, 4);
        Location at = trap.at.clone().add(0, 0.2, 0);
        plugin.fx().sound(at, "sugar-trap-trigger");
        Fx.View view = plugin.fx().view(at);
        view.particle(Fx.ITEM, at, 14, 0.3, 0.15, 0.3, 0.28, goo());
        for (int k = 0; k < 6; k++) {                   // goo squirting out from under the foot
            Vector out = way.clone().multiply(k % 2 == 0 ? 1 : -1).add(new Vector(0, 0.55 + 0.12 * k, 0));
            view.fly(Fx.ITEM, at, out, 0.22 + 0.03 * k, goo());
        }
        view.dust(at.clone().add(0, 0.4, 0), PINK, 1.5f, 12, 0.45);
        view.dust(at.clone().add(0, 0.4, 0), SUGAR, 1.1f, 6, 0.4);
        int stuckFor = Math.max(settings.ticks("slow-duration"), 20);
        for (int t = 4; t <= stuckFor; t += 4) {
            plugin.visuals().later(t, () -> {
                if (victim.isOnline() && !victim.isDead()) {
                    Location feet = victim.getLocation().add(0, 0.1, 0);
                    plugin.fx().view(feet).particle(Fx.ITEM, feet, 2, 0.2, 0.05, 0.2, 0.01, goo());
                }
            });
        }
    }

    /** Bits of candy goo as particles: the goo model's own texture (pink dye without the pack). */
    private ItemStack goo() {
        if (goo == null) {
            goo = new ItemStack(Material.PINK_DYE);
            ItemMeta meta = goo.getItemMeta();
            if (meta != null) {
                try {
                    meta.setItemModel(NamespacedKey.fromString("legendary:fx/candy_goo"));
                } catch (RuntimeException | LinkageError ignored) {
                    // Before item models: plain pink dye.
                }
                goo.setItemMeta(meta);
            }
        }
        return goo;
    }

    // ---- Sticky Sweet (passive) ----------------------------------------------------------------------------------

    @Override
    public void landed(Swing swing) {
        AbilitySettings settings = plugin.settings().ability(Ability.STICKY_SWEET);
        Player target = swing.target();
        long now = plugin.tick();
        Long free = stuck.get(target.getUniqueId());
        if (free != null && now < free) {
            return; // Still slowed (or only just free): a hit cannot keep them stuck.
        }
        if (random.nextDouble() >= settings.num("chance")) {
            return;
        }
        int duration = settings.ticks("duration");
        Hits.effect(target, "slowness", 1, duration);
        stuck.put(target.getUniqueId(), now + Math.max(duration, settings.ticks("immunity")));
        Location at = Geo.middle(target);
        plugin.fx().sound(at, "sticky-sweet");
        Fx.View view = plugin.fx().view(at);
        view.particle(Fx.ITEM, at, 7, 0.25, 0.3, 0.25, 0.12, goo());
        view.dust(at, PINK, 1.2f, 4, 0.3);
    }
}
