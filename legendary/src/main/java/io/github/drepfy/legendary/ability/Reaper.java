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
 * souls: they are drawn into the blade, the low are marked for the reaper alone, and the heart
 * of a reap is the victim's soul torn out of them, spiralling up round them in glowing wisps
 * until it breaks free above their head. A small spectral scythe circling them and its cut
 * lead into it, kept quiet so the soul stands out.
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
    /** How far the reaper senses players low enough to reap (blocks). */
    private static final double SENSE = 24.0;
    /** The wisps of a reaped soul, and how long they take to spiral up out of the victim (ticks). */
    private static final int STRANDS = 3;
    private static final int RISE = 26;

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
        for (int k = 0; k < 9; k++) {
            double a = Math.toRadians(k * 137.5);
            double y = 1 - 2 * (k + 0.5) / 9;
            double r = Math.sqrt(1 - y * y);
            Vector out = new Vector(Math.cos(a) * r, y * 0.6, Math.sin(a) * r).normalize();
            Location from = hand.clone().add(out.clone().multiply(2.4));
            view.fly(Fx.SOUL, from, out.multiply(-1), 0.14, null);
        }
        view.particle(Fx.SCULK_SOUL, hand, 2, 0.15, 0.2, 0.15, 0.02);
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
     * The reap, round the victim: a small spectral scythe circles them (following them as they
     * move) and cuts, and their soul is torn out of them.
     */
    private void harvest(Player attacker, Player target) {
        Vector from = Geo.middle(attacker).toVector().subtract(Geo.middle(target).toVector());
        double start = Math.atan2(from.getZ(), from.getX());
        Visuals.Effect scythe = plugin.visuals().spawn("reap_scythe", orbit(target, start, 0)).billboard().size(0.4)
                .send(0).animate(1, 3, e -> e.size(1.3));
        int laps = 7;
        for (int t = 1; t <= laps; t++) {
            int step = t;
            plugin.visuals().later(t, () -> {
                if (target.isValid()) {
                    scythe.moveTo(orbit(target, start + step * Math.toRadians(45), step), 1);
                }
            });
            scythe.animate(t, 1, e -> e.tilt(-35 + 16 * step));
        }
        scythe.vanish(laps + 1, 2);
        plugin.visuals().later(laps - 2, () -> {
            if (!target.isValid()) {
                return;
            }
            Location at = Geo.middle(target);
            plugin.fx().sound(at, "reap-strike");
            plugin.visuals().spawn("reap_slash", at).billboard().tilt(-20).size(0.5).send(0)
                    .animate(1, 2, e -> e.size(2.0).tilt(10))
                    .vanish(4, 3);
            plugin.fx().view(at).particle(Fx.SCULK_SOUL, at, 2, 0.25, 0.4, 0.25, 0.02);
            soulSpiral(target);
        });
    }

    /**
     * The heart of a reap: the victim's soul torn out of them. Three glowing wisps of it spiral
     * up round them (following them as they move), closing in as they climb and each leaving a
     * ribbon of soul-light behind it; above their head they meet, and the soul breaks free and
     * rises away.
     */
    private void soulSpiral(Player target) {
        Visuals.Effect[] wisps = new Visuals.Effect[STRANDS];
        for (int s = 0; s < STRANDS; s++) {
            wisps[s] = plugin.visuals().spawn("reap_soul", spiral(target, s, 0)).billboard().size(0.1).send(0)
                    .animate(1, 4, e -> e.size(0.5));
        }
        for (int t = 1; t <= RISE; t++) {
            int step = t;
            plugin.visuals().later(t, () -> {
                if (!target.isValid()) {
                    return;
                }
                Fx.View view = plugin.fx().view(target.getLocation());
                for (int s = 0; s < STRANDS; s++) {
                    Location was = spiral(target, s, step - 1);
                    Location now = spiral(target, s, step);
                    wisps[s].moveTo(now, 1);
                    Vector gap = now.toVector().subtract(was.toVector());
                    for (int k = 0; k < 3; k++) {
                        view.dust(was.clone().add(gap.clone().multiply(k / 3.0)), SOUL, 0.8f, 1, 0.0);
                    }
                    if ((step + s) % 3 == 0) {
                        view.particle(Fx.SOUL_FLAME, now, 1, 0.02, 0.02, 0.02, 0.005);
                    }
                }
            });
        }
        plugin.visuals().later(RISE + 1, () -> {
            if (!target.isValid()) {
                for (Visuals.Effect wisp : wisps) {
                    wisp.vanish(0, 3);
                }
                return;
            }
            Location crown = target.getLocation().add(0, target.getHeight() + 1.5, 0);
            plugin.fx().sound(crown, "reap-soul");
            Fx.View view = plugin.fx().view(crown);
            view.particle(Fx.SOUL, crown, 6, 0.15, 0.15, 0.15, 0.04);
            view.particle(Fx.SCULK_SOUL, crown, 3, 0.1, 0.1, 0.1, 0.03);
            for (Visuals.Effect wisp : wisps) {
                wisp.moveTo(crown, 2);
            }
            wisps[1].vanish(2, 2);
            wisps[2].vanish(2, 2);
            wisps[0].animate(2, 10, e -> e.size(0.9))           // the soul, whole again, rises away
                    .glide(3, crown.clone().add(0, 1.8, 0), 12)
                    .vanish(12, 5);
        });
    }

    /** Where wisp {@code strand} of a reaped soul is, {@code step} ticks into its climb. */
    private static Location spiral(Player target, int strand, int step) {
        double f = (double) step / RISE;
        double angle = strand * 2 * Math.PI / STRANDS + step * Math.toRadians(26);
        double radius = 0.9 * Math.pow(1 - f, 0.8) + 0.12;
        double up = 0.15 + Math.pow(f, 1.3) * (target.getHeight() + 1.5);
        return target.getLocation().add(Math.cos(angle) * radius, up, Math.sin(angle) * radius);
    }

    /** A point on the scythe's circle round the victim, at chest height and gently rising. */
    private static Location orbit(Player target, double angle, int step) {
        Location at = Geo.middle(target);
        return at.add(Math.cos(angle) * 1.15, 0.25 * Math.sin(step * 0.6), Math.sin(angle) * 1.15);
    }
}
