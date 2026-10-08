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
 * The Katana: precision and bleeding. Its look is steel and crimson: a glint of light when the
 * blade is drawn, blossom petals while the cut waits, thin crossing cuts when it lands, blood
 * dripping while they bleed.
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

    /**
     * The blade is half drawn: a flash of light runs along the steel, a line of glints follows
     * the edge, and while the cut waits a few blossom petals drift from the holder.
     */
    @Override
    public Result use(Player player, WeaponItems.Tag weapon) {
        int window = plugin.settings().ability(Ability.DRAW).ticks("window");
        draws.arm(player, plugin.tick() + window);
        plugin.fx().sound(player.getLocation(), "draw");
        Location hand = Geo.hand(player);
        plugin.visuals().spawn("katana_glint", hand).billboard().size(0.15).send(0)
                .animate(1, 2, e -> e.size(1.6))
                .animate(3, 3, e -> e.size(2.2, 0.35, 2.2))
                .vanish(6, 3);
        Vector along = Geo.flat(player.getLocation()).add(new Vector(0, 0.55, 0)).normalize();
        Fx.View view = plugin.fx().view(hand);
        for (int k = 1; k <= 6; k++) {
            Location p = hand.clone().add(along.clone().multiply(0.22 * k));
            view.dust(p, k % 2 == 0 ? STEEL : CRIMSON, 0.7f, 1, 0.0);
        }
        view.particle(Fx.SHINE, hand, 6, 0.15, 0.25, 0.15, 0.05);
        return Result.FIRED;
    }

    /** While a Draw waits: blossom petals drift down round the holder and the blade glints. */
    private void waiting(Player player, long now) {
        Location at = player.getLocation();
        Fx.View view = plugin.fx().view(at);
        if (now % 5 == 0) {
            view.particle(Fx.PETAL, at.clone().add(0, 1.9, 0), 1, 0.45, 0.2, 0.45, 0.0);
        }
        if (now % 4 == 0) {
            view.dust(Geo.hand(player), CRIMSON, 0.6f, 1, 0.04);
        }
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

    /**
     * The draw cut, the way an iai cut lands: a razor-thin flash of steel through the target, a
     * second cut crossing it an instant later, then the crimson.
     */
    private void drawCut(Player attacker, Player target) {
        Location at = Geo.middle(target);
        plugin.fx().sound(at, "draw-strike");
        cut(at, -24, 4.6);
        plugin.visuals().later(2, () -> {
            if (!target.isValid()) {
                return;
            }
            Location now = Geo.middle(target);
            cut(now, 180 + 30, 4.2);                   // back the other way, crossing the first
            Fx.View view = plugin.fx().view(now);
            view.dust(now, CRIMSON, 1.0f, 8, 0.35);
            view.particle(Fx.DRIP, now, 8, 0.3, 0.35, 0.3, 0, blood());
            view.particle(Fx.CRIT, now, 6, 0.3, 0.4, 0.3, 0.3);
        });
    }

    /**
     * One cut, as quick and thin as a real one: a razor line sweeps across in two ticks (its
     * start held still while its end runs out), then closes up to nothing in two more. It faces
     * whoever looks at it, at {@code angle} degrees on their screen.
     */
    private void cut(Location at, double angle, double length) {
        double rad = Math.toRadians(angle);
        double first = 0.3 * length;
        double back = (length - first) / 2;
        plugin.visuals().spawn("katana_cut", at).billboard().tilt(angle)
                .shift(-Math.cos(rad) * back, -Math.sin(rad) * back, 0).size(first, 0.3, 1).send(0)
                .animate(1, 2, e -> e.shift(0, 0, 0).size(length, 0.26, 1))
                .animate(3, 2, e -> e.size(length * 1.04, 0.015, 1))
                .life(6);
    }

    private static org.bukkit.block.data.BlockData blood;

    /** Blood drips: falling dust of red concrete. */
    static org.bukkit.block.data.BlockData blood() {
        if (blood == null) {
            blood = Material.RED_CONCRETE.createBlockData();
        }
        return blood;
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
        plugin.fx().view(at).particle(Fx.DRIP, at, 4, 0.2, 0.3, 0.2, 0, blood());
    }

    @Override
    public void tick(long now) {
        draws.expire(now);
        for (Player player : draws.waiting()) {
            waiting(player, now);
        }
        AbilitySettings settings = plugin.settings().ability(Ability.BLEED);
        for (Iterator<Bleed> it = bleeds.values().iterator(); it.hasNext(); ) {
            Bleed bleed = it.next();
            Player target = bleed.target;
            if (now > bleed.until || !target.isOnline() || target.isDead()) { // The last second counts too.
                it.remove();
                continue;
            }
            Fx.View view = plugin.fx().view(target.getLocation());
            if (now % 6 == 0) {
                view.particle(Fx.DRIP, Geo.middle(target), 1, 0.2, 0.3, 0.2, 0, blood());
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
                Location body = Geo.middle(target);
                view.particle(Fx.DRIP, body, 5, 0.22, 0.35, 0.22, 0, blood());
                view.dust(body, CRIMSON, 1.0f, 2, 0.25);
            }
        }
    }
}
