package io.github.drepfy.legendary.ability;

import io.github.drepfy.legendary.Ability;
import io.github.drepfy.legendary.LegendaryPlugin;
import io.github.drepfy.legendary.WeaponType;
import io.github.drepfy.legendary.config.AbilitySettings;
import io.github.drepfy.legendary.item.WeaponItems;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * The Candy Cane: control the ground and slow them down.
 * <ul>
 *   <li><b>Sticky Sweet</b> (passive): a hit may stick to them: Slowness I for a moment. It never
 *   stacks, and the same player cannot be stuck again until a few seconds have passed.</li>
 *   <li><b>Sugar Trap</b> (Shift + F): up to five sugar traps pop out round you and wait on the
 *   ground. An enemy who walks over one is poisoned, made dizzy and slowed, and the trap is gone.
 *   Your own traps never catch you.</li>
 * </ul>
 */
final class CandyCane implements Kit {

    private static final Color PINK = Color.fromRGB(255, 92, 165);
    private static final Color SUGAR = Color.fromRGB(255, 245, 250);
    private static final Color CANDY_RED = Color.fromRGB(225, 30, 60);
    /** How close (sideways) to a trap's middle a player's feet must come to set it off. */
    private static final double TRIGGER = 0.8;

    private final LegendaryPlugin plugin;
    private final List<Trap> traps = new ArrayList<>();
    /** Player → tick from which Sticky Sweet may slow them again. */
    private final Map<UUID, Long> stuck = new HashMap<>();
    private final Random random = new Random();

    CandyCane(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    private record Trap(UUID owner, Location at, long until, Visuals.Effect model) {
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
            if (trap.owner().equals(player.getUniqueId())) {
                left = Math.max(left, trap.until() - now);
            }
        }
        return left;
    }

    @Override
    public long activeLength() {
        return plugin.settings().ability(Ability.SUGAR_TRAP).ticks("lifetime");
    }

    @Override
    public void forget(Player player) {
        for (Iterator<Trap> it = traps.iterator(); it.hasNext(); ) {
            Trap trap = it.next();
            if (trap.owner().equals(player.getUniqueId())) {
                trap.model().remove();
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
        long until = plugin.tick() + settings.ticks("lifetime");
        int life = settings.ticks("lifetime");
        Location from = player.getLocation().add(0, 0.3, 0);
        for (Location spot : spots) {
            Visuals.Effect model = plugin.visuals().spawn("sugar_trap", from).size(0.3).send(0)
                    .animate(1, 6, e -> e.size(1.1).turn(180))
                    .glide(1, spot, 6);
            for (int t = 10; t < life; t += 10) {
                int step = t / 10;
                model.animate(t, 10, e -> e.turn(180 + step * 36));
            }
            model.life(life + 5);
            traps.add(new Trap(player.getUniqueId(), spot, until, model));
        }
        plugin.fx().sound(player.getLocation(), "sugar-trap");
        Fx.View view = plugin.fx().view(from);
        view.dust(from, PINK, 1.3f, 16, 0.8);
        view.dust(from, SUGAR, 1.0f, 12, 0.8);
        return Result.FIRED;
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
            Location spot = new Location(world, x, ground + 1.02, z);
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
            Player owner = Bukkit.getPlayer(trap.owner());
            if (owner == null || !owner.isOnline() || now >= trap.until()) {
                trap.model().vanish(0, 5);
                it.remove();
                continue;
            }
            if (now % 10 == 0) {
                Location top = trap.at().clone().add(0, 0.15, 0);
                plugin.fx().view(top).dust(top, now % 20 == 0 ? PINK : SUGAR, 0.8f, 2, 0.25);
            }
            Player victim = victim(owner, trap);
            if (victim != null) {
                spring(owner, victim, trap, settings);
                it.remove();
            }
        }
    }

    /** An enemy walking over the trap, if any (protection plugins are asked first). */
    private Player victim(Player owner, Trap trap) {
        Location at = trap.at();
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

    private void spring(Player owner, Player victim, Trap trap, AbilitySettings settings) {
        Hits.effect(victim, "poison", settings.whole("poison-level"), settings.ticks("poison-duration"));
        Hits.effect(victim, "nausea", 1, settings.ticks("nausea-duration"));
        Hits.effect(victim, "slowness", settings.whole("slow-level"), settings.ticks("slow-duration"));
        trap.model().animate(1, 2, e -> e.size(1.6)).vanish(3, 3);
        Location at = trap.at().clone().add(0, 0.2, 0);
        plugin.visuals().spawn("candy_burst", at).size(0.5).send(0)
                .animate(1, 4, e -> e.size(2.6).turn(120))
                .vanish(7, 5);
        plugin.fx().sound(at, "sugar-trap-trigger");
        Fx.View view = plugin.fx().view(at);
        view.dust(at.clone().add(0, 0.6, 0), PINK, 1.5f, 18, 0.5);
        view.dust(at.clone().add(0, 0.6, 0), SUGAR, 1.2f, 14, 0.5);
        view.dust(at.clone().add(0, 0.6, 0), CANDY_RED, 1.2f, 8, 0.4);
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
        plugin.fx().view(at).dust(at, PINK, 1.3f, 10, 0.35);
    }
}
