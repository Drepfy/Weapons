package io.github.drepfy.legendary.ability;

import io.github.drepfy.legendary.Ability;
import io.github.drepfy.legendary.LegendaryPlugin;
import io.github.drepfy.legendary.WeaponType;
import io.github.drepfy.legendary.config.AbilitySettings;
import io.github.drepfy.legendary.item.WeaponItems;
import io.github.drepfy.legendary.util.Compat;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Kurogane, the katana: precision and sustained combat.
 * <ul>
 *   <li><b>Crimson Flash</b>: an iaido dash. The player vanishes in a crimson streak and appears
 *   up to 8 blocks ahead; everyone they passed through is cut a moment later and bleeds.</li>
 *   <li><b>Blood Moon</b>: a crimson moon rises over the player. For a few seconds every sword
 *   hit cuts a second time and heals them.</li>
 * </ul>
 */
final class Kurogane implements Kit {

    private static final Color CRIMSON = Color.fromRGB(215, 25, 55);
    private static final Color STEEL = Color.fromRGB(240, 240, 248);

    private final LegendaryPlugin plugin;
    /** Caster → the tick their Blood Moon sets. */
    private final Map<UUID, Long> moons = new HashMap<>();
    /** Caster → the tick of their last extra cut (no spam clicking). */
    private final Map<UUID, Long> lastCut = new HashMap<>();
    /** Casters whose hit this tick got the extra cut (healed once it lands). */
    private final Map<UUID, UUID> cutting = new HashMap<>();
    /** Bleeding target → who cut it and how long. */
    private final Map<UUID, Bleed> bleeds = new HashMap<>();

    Kurogane(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    private static final class Bleed {
        final UUID attacker;
        final LivingEntity target;
        final double damage;
        long until;
        long next;

        Bleed(UUID attacker, LivingEntity target, double damage, long until, long next) {
            this.attacker = attacker;
            this.target = target;
            this.damage = damage;
            this.until = until;
            this.next = next;
        }
    }

    @Override
    public WeaponType type() {
        return WeaponType.KUROGANE;
    }

    @Override
    public Result use(Player player, WeaponItems.Tag weapon, Ability ability) {
        return ability == Ability.CRIMSON_FLASH ? flash(player) : moon(player);
    }

    @Override
    public long active(Player player, WeaponItems.Tag weapon, Ability ability, long now) {
        if (ability != Ability.BLOOD_MOON) {
            return 0;
        }
        Long until = moons.get(player.getUniqueId());
        return until == null ? 0 : Math.max(0, until - now);
    }

    @Override
    public long activeLength(Ability ability) {
        return ability == Ability.BLOOD_MOON ? plugin.settings().ability(Ability.BLOOD_MOON).ticks("duration") : 0;
    }

    /** Whether a target is bleeding (for tests and the inspect command). */
    boolean bleeding(LivingEntity target) {
        return bleeds.containsKey(target.getUniqueId());
    }

    @Override
    public void forget(Player player) {
        moons.remove(player.getUniqueId());
        lastCut.remove(player.getUniqueId());
        cutting.remove(player.getUniqueId());
        bleeds.remove(player.getUniqueId());
    }

    @Override
    public void tick(long now) {
        moons.values().removeIf(until -> until <= now);
        for (Iterator<Bleed> it = bleeds.values().iterator(); it.hasNext(); ) {
            Bleed bleed = it.next();
            if (now >= bleed.until || !bleed.target.isValid() || bleed.target.isDead()) {
                it.remove();
                continue;
            }
            Fx.View view = plugin.fx().view(bleed.target.getLocation());
            if (now % 3 == 0) {
                view.dust(Geo.middle(bleed.target), CRIMSON, 1.0f, 2, 0.25);
            }
            if (now < bleed.next) {
                continue;
            }
            bleed.next = now + 20;
            Player attacker = Bukkit.getPlayer(bleed.attacker);
            if (attacker == null || !attacker.isOnline()) {
                it.remove();
                continue;
            }
            if (plugin.hits().hurt(attacker, bleed.target, bleed.damage)) {
                view.particle(Fx.BLOCK, Geo.middle(bleed.target), 10, 0.2, 0.3, 0.2, 0.1,
                        Material.REDSTONE_BLOCK.createBlockData());
                plugin.fx().sound(bleed.target.getLocation(), "bleed");
            }
        }
    }

    // ---- Crimson Flash -----------------------------------------------------------------------------------

    private Result flash(Player player) {
        AbilitySettings settings = plugin.settings().ability(Ability.CRIMSON_FLASH);
        Location start = player.getLocation();
        Vector direction = Geo.flat(start);
        Location end = Geo.dash(start, direction, settings.num("range"));
        double length = Geo.flatDistance(start, end);
        if (length < 1.0) {
            return Result.FAILED; // A wall right in front: nothing happens, no cooldown.
        }
        // Who stands in the way, before anyone moves.
        double reach = settings.num("width") / 2.0;
        List<LivingEntity> path = plugin.hits().along(player, start.clone().add(0, 0.9, 0),
                end.clone().add(0, 0.9, 0), reach);
        Location destination = end.clone();
        destination.setYaw(start.getYaw());
        destination.setPitch(start.getPitch());
        if (!player.teleport(destination, PlayerTeleportEvent.TeleportCause.PLUGIN)) {
            return Result.FAILED; // A safe zone or region refused it.
        }
        player.setFallDistance(0f);
        plugin.fx().sound(start, "crimson-flash");
        plugin.fx().sound(destination, "crimson-flash-end");
        drawFlash(start, destination, direction, length);

        int delay = settings.ticks("delay");
        double damage = settings.num("damage");
        double bleedDamage = settings.num("bleed-damage");
        int bleedTicks = settings.ticks("bleed-duration");
        Runnable cut = () -> {
            for (LivingEntity target : path) {
                if (!target.isValid() || target.isDead() || !player.isOnline()) {
                    continue;
                }
                if (plugin.hits().hurt(player, target, damage)) {
                    cutFx(target, 35);
                    plugin.fx().sound(target.getLocation(), "kurogane-hit");
                    if (bleedDamage > 0 && bleedTicks > 0) {
                        long now = plugin.tick();
                        bleeds.put(target.getUniqueId(),
                                new Bleed(player.getUniqueId(), target, bleedDamage, now + bleedTicks + 1, now + 20));
                    }
                }
            }
            if (!path.isEmpty()) {
                plugin.fx().sound(destination, "crimson-cut");
            }
        };
        if (delay <= 0) {
            cut.run();
        } else {
            plugin.visuals().later(delay, cut);
        }
        return Result.FIRED;
    }

    /** A crimson streak along the path, after-images and sparks. */
    private void drawFlash(Location start, Location end, Vector direction, double length) {
        Location middle = start.clone().add(end).multiply(0.5).add(0, 1.0, 0);
        Visuals visuals = plugin.visuals();
        visuals.spawn("crimson_streak", middle).facing(direction).size(0.2, 1.4, 0.2).send(0)
                .animate(1, 3, e -> e.size(1.0, 1.6, length + 1.0))
                .vanish(10, 6);
        visuals.spawn("crimson_slash", end.clone().add(0, 1.1, 0)).facing(direction).tilt(-18).size(0.6).send(0)
                .animate(1, 3, e -> e.size(3.2))
                .vanish(5, 4);
        Fx.View view = plugin.fx().view(middle);
        for (double d = 0; d <= length; d += 0.5) {
            Location point = start.clone().add(direction.clone().multiply(d)).add(0, 1.0, 0);
            view.dust(point, d % 1.0 < 0.5 ? CRIMSON : STEEL, 1.2f, 2, 0.15);
            if (d % 2.0 < 0.5) {
                view.particle(Fx.SWEEP, point, 1, 0, 0, 0, 0);
            }
        }
        view.particle(Fx.CRIT, end.clone().add(0, 1, 0), 20, 0.3, 0.5, 0.3, 0.3);
    }

    /** The cut opening on a target: a crimson X that flashes and fades. */
    private void cutFx(LivingEntity target, int tilt) {
        Location at = Geo.middle(target);
        plugin.visuals().spawn("crimson_cut", at).billboard().tilt(tilt).size(0.3).send(0)
                .animate(1, 2, e -> e.size(1.8))
                .vanish(6, 4);
        Fx.View view = plugin.fx().view(at);
        view.dust(at, CRIMSON, 1.5f, 12, 0.35);
        view.particle(Fx.CRIT, at, 10, 0.3, 0.4, 0.3, 0.3);
    }

    // ---- Blood Moon ---------------------------------------------------------------------------------------

    private Result moon(Player player) {
        AbilitySettings settings = plugin.settings().ability(Ability.BLOOD_MOON);
        long now = plugin.tick();
        int duration = settings.ticks("duration");
        moons.put(player.getUniqueId(), now + duration);
        lastCut.remove(player.getUniqueId());
        plugin.fx().sound(player.getLocation(), "blood-moon");
        Visuals visuals = plugin.visuals();
        // The moon over their head for as long as it lasts.
        Vector above = new Vector(0, 2.9, 0);
        visuals.spawn("blood_moon", player.getLocation().add(above)).billboard().size(0.2).send(0)
                .animate(1, 6, e -> e.size(1.6))
                .follow(player, above, duration)
                .vanish(duration, 6);
        // A rune circle flares at their feet.
        visuals.spawn("rune_crimson", player.getLocation().add(0, 0.06, 0)).size(0.5).send(0)
                .animate(1, 6, e -> e.size(5.0).turn(60))
                .animate(8, 10, e -> e.size(5.6).turn(120))
                .vanish(18, 6);
        Fx.View view = plugin.fx().view(player.getLocation());
        view.dust(player.getLocation().add(0, 1, 0), CRIMSON, 1.6f, 30, 0.8);
        return Result.FIRED;
    }

    @Override
    public void melee(EntityDamageByEntityEvent event, Player attacker, LivingEntity target, WeaponItems.Tag weapon) {
        long now = plugin.tick();
        Long until = moons.get(attacker.getUniqueId());
        if (until == null || until <= now) {
            return;
        }
        AbilitySettings settings = plugin.settings().ability(Ability.BLOOD_MOON);
        Long last = lastCut.get(attacker.getUniqueId());
        if (last != null && now - last < settings.ticks("min-hit-gap")) {
            return; // Spam clicking does not cut twice.
        }
        lastCut.put(attacker.getUniqueId(), now);
        event.setDamage(event.getDamage() + settings.num("bonus-damage"));
        cutting.put(attacker.getUniqueId(), target.getUniqueId());
    }

    @Override
    public void landed(Player attacker, LivingEntity target, WeaponItems.Tag weapon) {
        UUID cut = cutting.remove(attacker.getUniqueId());
        if (cut == null || !cut.equals(target.getUniqueId())) {
            return;
        }
        Compat.heal(attacker, plugin.settings().ability(Ability.BLOOD_MOON).num("heal"));
        cutFx(target, plugin.tick() % 2 == 0 ? 30 : -30);
        plugin.fx().sound(target.getLocation(), "blood-moon-cut");
        plugin.fx().view(attacker.getLocation()).dust(attacker.getLocation().add(0, 1.2, 0), CRIMSON, 1.1f, 6, 0.3);
    }
}
