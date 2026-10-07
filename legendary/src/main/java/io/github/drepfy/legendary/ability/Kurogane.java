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
import java.util.concurrent.ThreadLocalRandom;

/**
 * Kurogane, the katana: speed, bleeding and a storm of cuts.
 * <ul>
 *   <li><b>Phantom Step</b>: a dash where you look. Everyone you pass through is cut and bleeds,
 *   and you are fast for a few seconds after.</li>
 *   <li><b>Crimson Tempest</b>: for a few seconds every sword hit also sends a crimson crescent
 *   flying forward, cutting everything in its path.</li>
 *   <li><b>Crimson Hunger</b> (passive): sword hits may make the target bleed, and hitting a
 *   bleeding target heals you.</li>
 * </ul>
 */
final class Kurogane implements Kit {

    private static final Color CRIMSON = Color.fromRGB(215, 25, 55);
    private static final Color STEEL = Color.fromRGB(240, 240, 248);

    private final LegendaryPlugin plugin;
    /** Player → their running Crimson Tempest. */
    private final Map<UUID, Tempest> tempests = new HashMap<>();
    /** Bleeding target → who cut it and how long. */
    private final Map<UUID, Bleed> bleeds = new HashMap<>();
    /** Attacker → what the sword hit now being dealt will do when it lands. */
    private final Map<UUID, Pending> pending = new HashMap<>();

    Kurogane(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    private static final class Tempest {
        final Player player;
        final long until;
        final Visuals.Effect ring;
        long nextCrescent;

        Tempest(Player player, long until, Visuals.Effect ring) {
            this.player = player;
            this.until = until;
            this.ring = ring;
        }
    }

    private static final class Bleed {
        final UUID attacker;
        final LivingEntity target;
        final double damage;
        final long until;
        long next;

        Bleed(UUID attacker, LivingEntity target, double damage, long until, long next) {
            this.attacker = attacker;
            this.target = target;
            this.damage = damage;
            this.until = until;
            this.next = next;
        }
    }

    /** One sword hit's extras, decided before it lands and applied after. */
    private record Pending(UUID target, boolean heal, boolean bleed, boolean crescent) {
    }

    @Override
    public WeaponType type() {
        return WeaponType.KUROGANE;
    }

    @Override
    public Result use(Player player, WeaponItems.Tag weapon, Ability ability) {
        return ability == Ability.CRIMSON_TEMPEST ? tempest(player) : step(player);
    }

    @Override
    public long active(Player player, WeaponItems.Tag weapon, Ability ability, long now) {
        Tempest tempest = ability == Ability.CRIMSON_TEMPEST ? tempests.get(player.getUniqueId()) : null;
        return tempest == null ? 0 : Math.max(0, tempest.until - now);
    }

    @Override
    public long activeLength(Ability ability) {
        return ability == Ability.CRIMSON_TEMPEST ? plugin.settings().ability(ability).ticks("duration") : 0;
    }

    @Override
    public void forget(Player player) {
        Tempest tempest = tempests.remove(player.getUniqueId());
        if (tempest != null) {
            tempest.ring.remove();
        }
        pending.remove(player.getUniqueId());
        bleeds.remove(player.getUniqueId());
    }

    @Override
    public void tick(long now) {
        for (Iterator<Tempest> it = tempests.values().iterator(); it.hasNext(); ) {
            Tempest tempest = it.next();
            if (!tempest.player.isOnline() || tempest.player.isDead() || now >= tempest.until) {
                tempest.ring.vanish(0, 5);
                it.remove();
            } else if (now % 3 == 0) {
                Location at = tempest.player.getLocation().add(0, 1.0, 0);
                plugin.fx().view(at).dust(at, CRIMSON, 1.1f, 3, 0.5);
            }
        }
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
            }
        }
    }

    private void bleed(Player attacker, LivingEntity target, double damage, int ticks) {
        if (damage > 0 && ticks > 0 && target.isValid() && !target.isDead()) {
            long now = plugin.tick();
            bleeds.put(target.getUniqueId(), new Bleed(attacker.getUniqueId(), target, damage, now + ticks + 1, now + 20));
        }
    }

    // ---- Phantom Step ------------------------------------------------------------------------------------

    private Result step(Player player) {
        AbilitySettings settings = plugin.settings().ability(Ability.PHANTOM_STEP);
        Location start = player.getLocation();
        Vector direction = Geo.flat(start);
        Location end = Geo.dash(start, direction, settings.num("range"));
        double length = Geo.flatDistance(start, end);
        if (length < 1.0) {
            return Result.FAILED; // A wall right in front: nothing spent.
        }
        // Who stands in the way, before anyone moves.
        List<LivingEntity> path = plugin.hits().along(player, start.clone().add(0, 0.9, 0),
                end.clone().add(0, 0.9, 0), settings.num("width") / 2.0);
        Location destination = end.clone();
        destination.setYaw(start.getYaw());
        destination.setPitch(start.getPitch());
        if (!player.teleport(destination, PlayerTeleportEvent.TeleportCause.PLUGIN)) {
            return Result.FAILED; // A safe zone or region refused it.
        }
        player.setFallDistance(0f);
        plugin.fx().sound(start, "phantom-step");
        drawStep(start, destination, direction, length);
        Hits.effect(player, "speed", settings.whole("speed-level"), settings.ticks("speed-duration"));
        double damage = settings.num("damage");
        double bleedDamage = settings.num("bleed-damage");
        int bleedTicks = settings.ticks("bleed-duration");
        // The cuts open a moment after the dash, as the streak fades.
        plugin.visuals().later(3, () -> {
            if (!player.isOnline()) {
                return;
            }
            for (LivingEntity target : path) {
                if (target.isValid() && !target.isDead() && plugin.hits().hurt(player, target, damage)) {
                    cutFx(target, 35);
                    bleed(player, target, bleedDamage, bleedTicks);
                }
            }
        });
        return Result.FIRED;
    }

    /** A crimson streak along the path, after-images and sparks. */
    private void drawStep(Location start, Location end, Vector direction, double length) {
        Location middle = start.clone().add(end).multiply(0.5).add(0, 1.0, 0);
        Visuals visuals = plugin.visuals();
        visuals.spawn("crimson_streak", middle).facing(direction).size(0.3, 1.8, 0.3).send(0)
                .animate(1, 3, e -> e.size(1.4, 2.2, length + 1.5))
                .vanish(16, 8);
        visuals.spawn("crimson_slash", end.clone().add(0, 1.1, 0)).facing(direction).tilt(-18).size(0.8).send(0)
                .animate(1, 3, e -> e.size(4.2))
                .vanish(9, 6);
        Fx.View view = plugin.fx().view(middle);
        for (double d = 0; d <= length; d += 0.5) {
            Location point = start.clone().add(direction.clone().multiply(d)).add(0, 1.0, 0);
            view.dust(point, d % 1.0 < 0.5 ? CRIMSON : STEEL, 1.2f, 2, 0.15);
        }
        view.particle(Fx.CRIT, end.clone().add(0, 1, 0), 20, 0.3, 0.5, 0.3, 0.3);
    }

    /** The cut opening on a target: a crimson X that flashes and fades. */
    private void cutFx(LivingEntity target, int tilt) {
        Location at = Geo.middle(target);
        plugin.visuals().spawn("crimson_cut", at).billboard().tilt(tilt).size(0.4).send(0)
                .animate(1, 2, e -> e.size(2.6))
                .vanish(10, 6);
        Fx.View view = plugin.fx().view(at);
        view.dust(at, CRIMSON, 1.5f, 12, 0.35);
        view.particle(Fx.CRIT, at, 10, 0.3, 0.4, 0.3, 0.3);
    }

    // ---- Crimson Tempest -----------------------------------------------------------------------------------

    private Result tempest(Player player) {
        AbilitySettings settings = plugin.settings().ability(Ability.CRIMSON_TEMPEST);
        int ticks = settings.ticks("duration");
        Visuals.Effect ring = plugin.visuals().spawn("rune_crimson", player.getLocation().add(0, 0.06, 0)).size(0.4).send(0)
                .animate(1, 4, e -> e.size(3.4).turn(90))
                .follow(player, new Vector(0, 0.06, 0), ticks);
        for (int t = 6; t < ticks; t += 6) {
            int step = t / 6;
            ring.animate(t, 6, e -> e.turn(90 + step * 60));
        }
        Tempest old = tempests.put(player.getUniqueId(), new Tempest(player, plugin.tick() + ticks, ring));
        if (old != null) {
            old.ring.remove();
        }
        Hits.effect(player, "speed", settings.whole("speed-level"), ticks);
        plugin.fx().sound(player.getLocation(), "crimson-tempest");
        Location at = player.getLocation().add(0, 1.0, 0);
        plugin.fx().view(at).dust(at, CRIMSON, 1.6f, 30, 0.8);
        return Result.FIRED;
    }

    /** A crimson crescent flies forward from a sword hit, cutting everything in its path (up to a wall). */
    private void crescent(Player player) {
        AbilitySettings settings = plugin.settings().ability(Ability.CRIMSON_TEMPEST);
        Location from = player.getLocation().add(0, 1.0, 0);
        Vector direction = Geo.flat(player.getLocation());
        Location to = Geo.dash(player.getLocation(), direction, settings.num("range")).add(0, 1.0, 0);
        plugin.visuals().spawn("crimson_slash", from.clone().add(direction.clone().multiply(1.2))).facing(direction)
                .tilt(ThreadLocalRandom.current().nextInt(-35, 36)).size(1.2).send(0)
                .animate(1, 2, e -> e.size(3.6))
                .glide(1, to, 6)
                .vanish(7, 4);
        for (LivingEntity target : plugin.hits().along(player, from, to, settings.num("width") / 2.0)) {
            if (plugin.hits().hurt(player, target, settings.num("damage"))) {
                cutFx(target, -30);
            }
        }
    }

    // ---- Crimson Hunger (passive) and the tempest's crescents --------------------------------------------------

    @Override
    public void melee(EntityDamageByEntityEvent event, Player attacker, LivingEntity target, WeaponItems.Tag weapon) {
        // A hit some plugin cancelled never lands: what it would have done must not carry over.
        pending.remove(attacker.getUniqueId());
        AbilitySettings hunger = plugin.settings().ability(Ability.CRIMSON_HUNGER);
        boolean heal = bleeds.containsKey(target.getUniqueId());
        boolean bleed = ThreadLocalRandom.current().nextDouble() < hunger.num("chance");
        boolean crescent = false;
        Tempest tempest = tempests.get(attacker.getUniqueId());
        long now = plugin.tick();
        if (tempest != null && now < tempest.until && now >= tempest.nextCrescent) {
            crescent = true;
            tempest.nextCrescent = now + plugin.settings().ability(Ability.CRIMSON_TEMPEST).ticks("gap");
        }
        if (heal || bleed || crescent) {
            pending.put(attacker.getUniqueId(), new Pending(target.getUniqueId(), heal, bleed, crescent));
        }
    }

    @Override
    public void landed(Player attacker, LivingEntity target, WeaponItems.Tag weapon) {
        Pending hit = pending.remove(attacker.getUniqueId());
        if (hit == null || !hit.target().equals(target.getUniqueId())) {
            return;
        }
        AbilitySettings hunger = plugin.settings().ability(Ability.CRIMSON_HUNGER);
        if (hit.heal() && hunger.num("heal") > 0) {
            Compat.heal(attacker, hunger.num("heal"));
            Location at = attacker.getLocation().add(0, 1.2, 0);
            plugin.fx().view(at).dust(at, CRIMSON, 1.2f, 6, 0.4);
        }
        if (hit.bleed()) {
            bleed(attacker, target, hunger.num("bleed-damage"), hunger.ticks("bleed-duration"));
            cutFx(target, plugin.tick() % 2 == 0 ? 30 : -30);
        }
        if (hit.crescent()) {
            crescent(attacker);
        }
    }
}
