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
 * The Reaper: the finisher. It is strongest against players who are already low.
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
    }

    // ---- Reap ----------------------------------------------------------------------------------------------------

    @Override
    public Result use(Player player, WeaponItems.Tag weapon) {
        int window = plugin.settings().ability(Ability.REAP).ticks("window");
        Visuals.Effect ring = plugin.visuals().spawn("soul_ring", player.getLocation().add(0, 0.06, 0)).size(0.4).send(0)
                .animate(1, 5, e -> e.size(2.6).turn(-90))
                .follow(player, new Vector(0, 0.06, 0), window);
        for (int t = 8; t < window; t += 8) {
            int step = t / 8;
            ring.animate(t, 8, e -> e.turn(-90 - step * 40));
        }
        reaps.arm(player, plugin.tick() + window, ring);
        plugin.fx().sound(player.getLocation(), "reap");
        Location at = player.getLocation().add(0, 1.0, 0);
        Fx.View view = plugin.fx().view(at);
        view.particle(Fx.SOUL, at, 16, 0.5, 0.6, 0.5, 0.03);
        view.dust(at, SOUL, 1.2f, 14, 0.6);
        return Result.FIRED;
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
            reapFx(attacker, target);
        } else {
            Location at = Geo.middle(target);
            plugin.fx().view(at).particle(Fx.SOUL, at, 4, 0.25, 0.35, 0.25, 0.02);
        }
        plugin.visuals().later(1, () -> {
            if (attacker.isOnline()) {
                plugin.hits().hurt(attacker, target, extra.damage());
            }
        });
    }

    /** The reap: a spectral scythe sweeps through the target and souls rise. */
    private void reapFx(Player attacker, Player target) {
        Location at = Geo.middle(target);
        Vector direction = Geo.flat(attacker.getLocation());
        plugin.visuals().spawn("reap_slash", at.clone().add(direction.clone().multiply(-0.2))).billboard().tilt(-20).size(0.6).send(0)
                .animate(1, 3, e -> e.size(4.0).tilt(10))
                .vanish(9, 6);
        plugin.fx().sound(at, "reap-strike");
        Fx.View view = plugin.fx().view(at);
        view.particle(Fx.SOUL, at, 26, 0.4, 0.7, 0.4, 0.06);
        view.dust(at, SOUL, 1.6f, 18, 0.45);
        view.dust(at, DUSK, 1.6f, 14, 0.5);
    }
}
