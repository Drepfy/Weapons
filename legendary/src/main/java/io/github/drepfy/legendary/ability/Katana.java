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
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * The Katana: precision and bleeding.
 * <ul>
 *   <li><b>Bleed</b> (passive): every hit on a player makes them bleed for a few seconds. Hitting
 *   them again starts it over; it never stacks.</li>
 *   <li><b>Draw</b> (Shift + F): for a few seconds the blade is half drawn. The next full-strength
 *   hit on a player cuts for extra damage: a share of the health they have left.</li>
 * </ul>
 */
final class Katana implements Kit {

    static final Color CRIMSON = Color.fromRGB(220, 20, 50);
    private static final Color STEEL = Color.fromRGB(240, 240, 248);

    private final LegendaryPlugin plugin;
    private final Armed draws = new Armed();
    /** Bleeding player → who cut them and until when. One each: a new cut only starts it over. */
    private final Map<UUID, Bleed> bleeds = new HashMap<>();
    /** Attacker → Draw's extra damage for the hit now being dealt (health points). */
    private final Map<UUID, Double> drawing = new HashMap<>();

    Katana(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    private static final class Bleed {
        final UUID attacker;
        final Player target;
        long until;
        long next;

        Bleed(UUID attacker, Player target, long until, long next) {
            this.attacker = attacker;
            this.target = target;
            this.until = until;
            this.next = next;
        }
    }

    @Override
    public WeaponType type() {
        return WeaponType.KATANA;
    }

    @Override
    public long active(Player player, long now) {
        return draws.left(player, now);
    }

    @Override
    public long activeLength() {
        return plugin.settings().ability(Ability.DRAW).ticks("window");
    }

    @Override
    public void forget(Player player) {
        draws.forget(player);
        drawing.remove(player.getUniqueId());
        bleeds.remove(player.getUniqueId());
    }

    // ---- Draw ------------------------------------------------------------------------------------------------

    @Override
    public Result use(Player player, WeaponItems.Tag weapon) {
        AbilitySettings settings = plugin.settings().ability(Ability.DRAW);
        int window = settings.ticks("window");
        Visuals.Effect ring = plugin.visuals().spawn("draw_sigil", player.getLocation().add(0, 0.06, 0)).size(0.4).send(0)
                .animate(1, 4, e -> e.size(2.6).turn(90))
                .follow(player, new Vector(0, 0.06, 0), window);
        for (int t = 8; t < window; t += 8) {
            int step = t / 8;
            ring.animate(t, 8, e -> e.turn(90 + step * 45));
        }
        draws.arm(player, plugin.tick() + window, ring);
        plugin.fx().sound(player.getLocation(), "draw");
        Location at = player.getLocation().add(0, 1.0, 0);
        Fx.View view = plugin.fx().view(at);
        view.dust(at, CRIMSON, 1.4f, 24, 0.6);
        view.dust(at, STEEL, 1.0f, 10, 0.5);
        return Result.FIRED;
    }

    /** Draw's extra damage: a share of the health the target has left, between min and max (hearts). */
    double drawDamage(Player target) {
        AbilitySettings settings = plugin.settings().ability(Ability.DRAW);
        double hearts = settings.num("percent") * target.getHealth() / 2.0;
        return 2.0 * Math.max(settings.num("min"), Math.min(settings.num("max"), hearts));
    }

    @Override
    public void melee(Swing swing) {
        drawing.remove(swing.attacker().getUniqueId());
        if (swing.charged() && draws.armed(swing.attacker(), plugin.tick())) {
            drawing.put(swing.attacker().getUniqueId(), drawDamage(swing.target()));
        }
    }

    @Override
    public void landed(Swing swing) {
        Player attacker = swing.attacker();
        Player target = swing.target();
        bleed(attacker, target);
        Double extra = drawing.remove(attacker.getUniqueId());
        if (extra == null) {
            return;
        }
        draws.spend(attacker);
        plugin.visuals().later(1, () -> {
            if (attacker.isOnline() && plugin.hits().hurt(attacker, target, extra)) {
                drawCut(attacker, target);
            }
        });
    }

    /** The draw cut: a wide crimson crescent through the target and a flash of steel. */
    private void drawCut(Player attacker, Player target) {
        Location at = Geo.middle(target);
        Vector direction = Geo.flat(attacker.getLocation());
        plugin.visuals().spawn("crimson_slash", at.clone().add(direction.clone().multiply(-0.3))).facing(direction)
                .tilt(-22).size(0.8).send(0)
                .animate(1, 3, e -> e.size(4.4))
                .vanish(8, 6);
        plugin.visuals().spawn("crimson_cut", at).billboard().tilt(30).size(0.5).send(0)
                .animate(1, 2, e -> e.size(3.0))
                .vanish(9, 6);
        plugin.fx().sound(at, "draw-strike");
        Fx.View view = plugin.fx().view(at);
        view.dust(at, CRIMSON, 1.6f, 20, 0.45);
        view.particle(Fx.CRIT, at, 18, 0.35, 0.5, 0.35, 0.35);
        view.particle(Fx.BLOCK, at, 16, 0.25, 0.4, 0.25, 0.1, Material.REDSTONE_BLOCK.createBlockData());
    }

    // ---- Bleed (passive) ---------------------------------------------------------------------------------------

    private void bleed(Player attacker, Player target) {
        AbilitySettings settings = plugin.settings().ability(Ability.BLEED);
        int duration = settings.ticks("duration");
        if (duration <= 0 || settings.num("damage") <= 0) {
            return;
        }
        long now = plugin.tick();
        Bleed bleed = bleeds.get(target.getUniqueId());
        if (bleed != null) {
            // Hit again: it starts over (no second bleed, and the next tick of damage is not moved).
            bleed.until = now + duration;
            return;
        }
        bleeds.put(target.getUniqueId(), new Bleed(attacker.getUniqueId(), target, now + duration,
                now + Math.max(1, settings.ticks("interval"))));
        Location at = Geo.middle(target);
        plugin.visuals().spawn("crimson_cut", at).billboard().tilt(-35).size(0.3).send(0)
                .animate(1, 2, e -> e.size(1.8))
                .vanish(6, 5);
    }

    @Override
    public void tick(long now) {
        draws.expire(now);
        AbilitySettings settings = plugin.settings().ability(Ability.BLEED);
        for (Iterator<Bleed> it = bleeds.values().iterator(); it.hasNext(); ) {
            Bleed bleed = it.next();
            Player target = bleed.target;
            if (now > bleed.until || !target.isOnline() || target.isDead()) { // The last second counts too.
                it.remove();
                continue;
            }
            Fx.View view = plugin.fx().view(target.getLocation());
            if (now % 4 == 0) {
                view.dust(Geo.middle(target), CRIMSON, 1.0f, 2, 0.25);
            }
            if (now < bleed.next) {
                continue;
            }
            bleed.next = now + Math.max(1, settings.ticks("interval"));
            Player attacker = Bukkit.getPlayer(bleed.attacker);
            if (attacker == null || !attacker.isOnline()) {
                it.remove();
                continue;
            }
            if (plugin.hits().hurt(attacker, target, settings.num("damage") * 2.0)) {
                view.particle(Fx.BLOCK, Geo.middle(target), 8, 0.2, 0.3, 0.2, 0.1, Material.REDSTONE_BLOCK.createBlockData());
            }
        }
    }
}
