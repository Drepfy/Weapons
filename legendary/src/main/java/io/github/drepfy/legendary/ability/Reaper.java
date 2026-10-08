package io.github.drepfy.legendary.ability;

import io.github.drepfy.legendary.Ability;
import io.github.drepfy.legendary.LegendaryPlugin;
import io.github.drepfy.legendary.WeaponType;
import io.github.drepfy.legendary.config.AbilitySettings;
import io.github.drepfy.legendary.item.WeaponItems;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The Reaper: the finisher. It is strongest against players who are already low. Its look is
 * souls and a spectral scythe: souls are drawn into the blade, the low are marked for the reaper
 * alone, and a reap is a ghostly scythe circling the victim before it cuts, their soul torn
 * upwards in a spiral.
 * <ul>
 *   <li><b>Execution</b> (passive): hits on a player below 6 hearts do a little extra damage.</li>
 *   <li><b>Reap</b> (Shift + F): the next full-strength hit on a player below 8 hearts reaps them
 *   for heavy extra damage. Hits on healthier players do not use it up: it waits for a target
 *   that is low enough, or until it runs out. It is ordinary damage: it never kills outright,
 *   and totems save as usual.</li>
 * </ul>
 */
final class Reaper implements Kit {

    private static final Color SOUL = Color.fromRGB(90, 255, 210);
    private static final Color DUSK = Color.fromRGB(70, 40, 110);
    /** How far the reaper senses players low enough to reap (blocks). */
    private static final double SENSE = 24.0;

    private final LegendaryPlugin plugin;
    private final Armed reaps = new Armed();
    /** Attacker → the extra damage of the hit now being dealt (health points) and whether it reaps. */
    private final Map<UUID, Extra> extras = new HashMap<>();

    Reaper(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    private record Extra(UUID target, double damage, boolean reap) {
    }

    @Override
    public WeaponType type() {
        return WeaponType.REAPER;
    }

    @Override
    public long active(Player player, long now) {
        return reaps.left(player, now);
    }

    @Override
    public long activeLength() {
        return plugin.settings().ability(Ability.REAP).ticks("window");
    }

    @Override
    public void forget(Player player) {
        reaps.forget(player);
        extras.remove(player.getUniqueId());
    }

    @Override
    public void tick(long now) {
        reaps.expire(now);
        for (Player player : reaps.waiting()) {
            hunting(player, now);
        }
    }

    // ---- Reap ----------------------------------------------------------------------------------------------------

    /** The reaper calls for souls: they stream in from all round and vanish into the blade. */
    @Override
    public Result use(Player player, WeaponItems.Tag weapon) {
        int window = plugin.settings().ability(Ability.REAP).ticks("window");
        reaps.arm(player, plugin.tick() + window);
        plugin.fx().sound(player.getLocation(), "reap");
        Location hand = Geo.hand(player);
        Fx.View view = plugin.fx().view(hand);
        for (int k = 0; k < 14; k++) {
            double a = Math.toRadians(k * 137.5);
            double y = 1 - 2 * (k + 0.5) / 14;
            double r = Math.sqrt(1 - y * y);
            Vector out = new Vector(Math.cos(a) * r, y * 0.6, Math.sin(a) * r).normalize();
            Location from = hand.clone().add(out.clone().multiply(2.4));
            view.fly(Fx.SOUL, from, out.multiply(-1), 0.14, null);
        }
        view.particle(Fx.SCULK_SOUL, hand, 4, 0.15, 0.2, 0.15, 0.02);
        return Result.FIRED;
    }

    /**
     * While a Reap waits: a dark wisp curls from the blade (everyone sees it), and every player
     * low enough to reap is marked with a soul flame over their head that only the reaper sees.
     */
    private void hunting(Player reaper, long now) {
        if (now % 5 == 0) {
            Location hand = Geo.hand(reaper);
            plugin.fx().view(hand).particle(Fx.SMOKE, hand, 1, 0.05, 0.1, 0.05, 0.01);
        }
        if (now % 8 != 0) {
            return;
        }
        double below = plugin.settings().ability(Ability.REAP).num("below") * 2.0;
        Fx.View mine = plugin.fx().to(reaper);
        for (Player other : reaper.getWorld().getPlayers()) {
            if (other.getLocation().distanceSquared(reaper.getLocation()) > SENSE * SENSE
                    || other.getHealth() >= below || !plugin.hits().canTarget(reaper, other)) {
                continue;
            }
            Location head = other.getLocation().add(0, other.getHeight() + 0.45, 0);
            mine.particle(Fx.SOUL_FLAME, head, 2, 0.12, 0.08, 0.12, 0.005);
        }
    }

    @Override
    public void melee(Swing swing) {
        Player attacker = swing.attacker();
        Player target = swing.target();
        extras.remove(attacker.getUniqueId());
        double health = target.getHealth();
        double damage = 0;
        AbilitySettings execution = plugin.settings().ability(Ability.EXECUTION);
        if (health < execution.num("below") * 2.0) {
            damage += execution.num("damage") * 2.0;
        }
        AbilitySettings reap = plugin.settings().ability(Ability.REAP);
        boolean reaping = swing.charged() && reaps.armed(attacker, plugin.tick()) && health < reap.num("below") * 2.0;
        if (reaping) {
            damage += reap.num("damage") * 2.0;
        }
        if (damage > 0) {
            extras.put(attacker.getUniqueId(), new Extra(target.getUniqueId(), damage, reaping));
        }
    }

    @Override
    public void landed(Swing swing) {
        Player attacker = swing.attacker();
        Player target = swing.target();
        Extra extra = extras.remove(attacker.getUniqueId());
        if (extra == null || !extra.target().equals(target.getUniqueId())) {
            return;
        }
        if (extra.reap()) {
            reaps.spend(attacker);
            harvest(attacker, target);
        } else {
            Location at = Geo.middle(target);           // Execution: a dark wisp leaves them
            Fx.View view = plugin.fx().view(at);
            view.particle(Fx.SMOKE, at, 4, 0.2, 0.3, 0.2, 0.02);
            view.particle(Fx.SCULK_SOUL, at, 1, 0.2, 0.3, 0.2, 0.02);
        }
        plugin.visuals().later(1, () -> {
            if (attacker.isOnline()) {
                plugin.hits().hurt(attacker, target, extra.damage());
            }
        });
    }

    /**
     * The reap, round the victim: a spectral scythe circles them (following them as they move),
     * sweeps through them, and their soul is torn upwards in a spiral.
     */
    private void harvest(Player attacker, Player target) {
        Vector from = Geo.middle(attacker).toVector().subtract(Geo.middle(target).toVector());
        double start = Math.atan2(from.getZ(), from.getX());
        Location first = orbit(target, start, 0);
        Visuals.Effect scythe = plugin.visuals().spawn("reap_scythe", first).billboard().size(0.5).send(0)
                .animate(1, 3, e -> e.size(2.0));
        int laps = 10;
        for (int t = 1; t <= laps; t++) {
            int step = t;
            plugin.visuals().later(t, () -> {
                if (target.isValid()) {
                    scythe.moveTo(orbit(target, start + step * Math.toRadians(40), step), 1);
                }
            });
            scythe.animate(t, 1, e -> e.tilt(-35 + 14 * step));
        }
        scythe.vanish(laps + 1, 3);
        plugin.visuals().later(laps - 3, () -> {
            if (!target.isValid()) {
                return;
            }
            Location at = Geo.middle(target);
            plugin.fx().sound(at, "reap-strike");
            plugin.visuals().spawn("reap_slash", at).billboard().tilt(-20).size(0.6).send(0)
                    .animate(1, 3, e -> e.size(4.0).tilt(10))
                    .vanish(8, 5);
            Fx.View view = plugin.fx().view(at);
            view.particle(Fx.SCULK_SOUL, at, 8, 0.3, 0.5, 0.3, 0.05);
            view.dust(at, SOUL, 1.5f, 14, 0.4);
            view.dust(at, DUSK, 1.5f, 10, 0.45);
        });
        for (int t = 0; t < 16; t++) {
            int step = t;
            plugin.visuals().later(laps - 3 + t, () -> {
                if (!target.isValid()) {
                    return;
                }
                Location feet = target.getLocation();
                Fx.View view = plugin.fx().view(feet);
                for (int strand = 0; strand < 2; strand++) {
                    double a = step * Math.toRadians(50) + strand * Math.PI;
                    double r = 0.65 - step * 0.025;
                    Location p = feet.clone().add(Math.cos(a) * r, 0.2 + step * 0.14, Math.sin(a) * r);
                    view.particle(Fx.SOUL, p, 1, 0, 0, 0, 0.0);
                }
            });
        }
    }

    /** A point on the scythe's circle round the victim, at chest height and gently rising. */
    private static Location orbit(Player target, double angle, int step) {
        Location at = Geo.middle(target);
        return at.add(Math.cos(angle) * 1.15, 0.25 * Math.sin(step * 0.6), Math.sin(angle) * 1.15);
    }
}
