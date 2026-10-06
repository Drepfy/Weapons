package io.github.drepfy.legendary.ability;

import io.github.drepfy.legendary.Ability;
import io.github.drepfy.legendary.LegendaryPlugin;
import io.github.drepfy.legendary.WeaponType;
import io.github.drepfy.legendary.config.AbilitySettings;
import io.github.drepfy.legendary.item.WeaponItems;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Sugarcrash, the candy war-scythe: reach, mobility and burst tempo.
 * <ul>
 *   <li><b>Candy Hook</b>: throws a candy-cane hook on a candy rope. A player (or monster) it
 *   catches is yanked to you and stunned for a moment; a wall or the ground it catches pulls you
 *   to it instead (no fall damage).</li>
 *   <li><b>Candy Cyclone</b>: the scythe spins into a candy-striped tornado around the player,
 *   who can keep moving (faster). It drags nearby players in and shreds them, deflects arrows,
 *   then bursts outwards.</li>
 *   <li><b>Sugar High</b> (passive): each hit adds a sugar stack and speeds you up; at full
 *   stacks the next hit is a Sugar Crash, a candy explosion round the target.</li>
 * </ul>
 */
final class Sugarcrash implements Kit, Listener {

    private static final Color PINK = Color.fromRGB(255, 92, 165);
    private static final Color CANDY_RED = Color.fromRGB(225, 30, 60);
    private static final Color SUGAR = Color.fromRGB(255, 245, 250);

    private final LegendaryPlugin plugin;
    private final List<Hook> hooks = new ArrayList<>();
    private final Map<UUID, Cyclone> cyclones = new HashMap<>();
    private final Map<UUID, Sugar> sugar = new HashMap<>();
    /** Attacker → the target their Sugar Crash is landing on. */
    private final Map<UUID, UUID> crashing = new HashMap<>();

    Sugarcrash(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    private static final class Hook {
        final Player player;
        final Vector direction;
        final Visuals.Effect effect;
        Location position;
        double travelled;

        Hook(Player player, Location position, Vector direction, Visuals.Effect effect) {
            this.player = player;
            this.position = position;
            this.direction = direction;
            this.effect = effect;
        }
    }

    private static final class Cyclone {
        final Player player;
        final long until;
        long nextHit;
        final List<Visuals.Effect> rings = new ArrayList<>();
        int turn;

        Cyclone(Player player, long until, long nextHit) {
            this.player = player;
            this.until = until;
            this.nextHit = nextHit;
        }
    }

    private static final class Sugar {
        int stacks;
        long until;
        long last = Long.MIN_VALUE / 2;
    }

    @Override
    public WeaponType type() {
        return WeaponType.SUGARCRASH;
    }

    @Override
    public Result use(Player player, WeaponItems.Tag weapon, Ability ability) {
        return ability == Ability.CANDY_HOOK ? hook(player) : cyclone(player);
    }

    @Override
    public long active(Player player, WeaponItems.Tag weapon, Ability ability, long now) {
        if (ability != Ability.CANDY_CYCLONE) {
            return 0;
        }
        Cyclone cyclone = cyclones.get(player.getUniqueId());
        return cyclone == null ? 0 : Math.max(0, cyclone.until - now);
    }

    @Override
    public long activeLength(Ability ability) {
        return ability == Ability.CANDY_CYCLONE ? plugin.settings().ability(ability).ticks("duration") : 0;
    }

    /** Sugar stacks a player has (for tests). */
    int stacks(Player player) {
        Sugar state = sugar.get(player.getUniqueId());
        return state == null ? 0 : state.stacks;
    }

    @Override
    public void forget(Player player) {
        hooks.removeIf(hook -> {
            if (hook.player.equals(player)) {
                hook.effect.remove();
                return true;
            }
            return false;
        });
        Cyclone cyclone = cyclones.remove(player.getUniqueId());
        if (cyclone != null) {
            cyclone.rings.forEach(Visuals.Effect::remove);
        }
        sugar.remove(player.getUniqueId());
        crashing.remove(player.getUniqueId());
    }

    @Override
    public void tick(long now) {
        for (Iterator<Hook> it = hooks.iterator(); it.hasNext(); ) {
            Hook hook = it.next();
            if (!hook.player.isOnline() || hook.player.isDead() || hookTick(hook)) {
                it.remove();
            }
        }
        for (Iterator<Cyclone> it = cyclones.values().iterator(); it.hasNext(); ) {
            Cyclone cyclone = it.next();
            if (!cyclone.player.isOnline() || cyclone.player.isDead()) {
                cyclone.rings.forEach(Visuals.Effect::remove);
                it.remove();
                continue;
            }
            cycloneTick(cyclone, now);
            if (now >= cyclone.until) {
                it.remove();
                burst(cyclone);
            }
        }
        sugar.values().removeIf(state -> now >= state.until);
    }

    // ---- Candy Hook --------------------------------------------------------------------------------------

    private Result hook(Player player) {
        Location eye = player.getEyeLocation();
        Vector direction = eye.getDirection().normalize();
        Location start = eye.clone().add(direction.clone().multiply(0.6)).subtract(0, 0.25, 0);
        Visuals.Effect effect = plugin.visuals().spawn("candy_hook", start).billboard().size(0.9).send(0);
        hooks.add(new Hook(player, start, direction, effect));
        plugin.fx().sound(player.getLocation(), "candy-hook");
        return Result.FIRED;
    }

    /** Moves a hook on; true when it is done. */
    private boolean hookTick(Hook hook) {
        AbilitySettings settings = plugin.settings().ability(Ability.CANDY_HOOK);
        Player player = hook.player;
        World world = hook.position.getWorld();
        if (world == null || !world.equals(player.getWorld())) {
            hook.effect.remove();
            return true;
        }
        double step = settings.num("speed");
        Location from = hook.position.clone();
        Location to = from.clone().add(hook.direction.clone().multiply(step));
        // A wall or the ground on the way: the hook bites into it.
        Location wall = null;
        Location last = from.clone();
        for (double d = 0.25; d <= step + 1.0E-6; d += 0.25) {
            Location point = from.clone().add(hook.direction.clone().multiply(d));
            if (Geo.solid(point)) {
                wall = last;
                break;
            }
            last = point;
        }
        Location reached = wall != null ? wall : to;
        List<LivingEntity> caught = plugin.hits().along(player, from, reached, 0.6);
        rope(player, reached);
        if (!caught.isEmpty()) {
            yank(hook, caught.get(0));
            return true;
        }
        if (wall != null) {
            grapple(hook, wall);
            return true;
        }
        hook.position = to;
        hook.travelled += step;
        hook.effect.moveTo(to, 1);
        if (hook.travelled >= settings.num("range")) {
            hook.effect.moveTo(player.getEyeLocation().subtract(0, 0.4, 0), 4).vanish(3, 2);
            return true;
        }
        return false;
    }

    /** The candy rope from the hand to the hook. */
    private void rope(Player player, Location to) {
        Location from = player.getEyeLocation().subtract(0, 0.4, 0);
        Vector way = to.toVector().subtract(from.toVector());
        double length = way.length();
        if (length < 0.1) {
            return;
        }
        way.multiply(1.0 / length);
        Fx.View view = plugin.fx().view(from);
        int i = 0;
        for (double d = 0.5; d < length; d += 0.5, i++) {
            view.dust(from.clone().add(way.clone().multiply(d)), i % 2 == 0 ? CANDY_RED : SUGAR, 0.8f, 1, 0.0);
        }
    }

    private void yank(Hook hook, LivingEntity target) {
        AbilitySettings settings = plugin.settings().ability(Ability.CANDY_HOOK);
        Player player = hook.player;
        Location at = Geo.middle(target);
        hook.effect.moveTo(at, 1);
        if (!plugin.hits().hurt(player, target, Math.max(0.01, settings.num("damage")))) {
            hook.effect.vanish(2, 2);
            return; // Protected: not pulled.
        }
        Vector way = player.getLocation().toVector().subtract(target.getLocation().toVector()).setY(0);
        double distance = way.length();
        if (distance > 1.5) {
            double speed = Math.min(2.2, 0.35 + distance * 0.11) * settings.num("yank");
            Vector velocity = way.normalize().multiply(speed);
            velocity.setY(0.3 + Math.min(0.4, distance * 0.02));
            target.setVelocity(velocity);
        }
        int stun = settings.ticks("stun");
        if (stun > 0) {
            plugin.visuals().later(6, () -> {
                if (target.isValid() && !target.isDead()) {
                    stun(target, stun);
                }
            });
        }
        hook.effect.moveTo(player.getEyeLocation().subtract(0, 0.4, 0), 5).vanish(5, 2);
        plugin.fx().sound(target.getLocation(), "candy-hook-catch");
        plugin.visuals().spawn("candy_burst", at).billboard().size(0.3).send(0)
                .animate(1, 3, e -> e.size(1.5))
                .vanish(4, 3);
    }

    /** Stunned: candy stars circle their head. */
    private void stun(LivingEntity target, int ticks) {
        Hits.stun(target, ticks);
        Vector above = new Vector(0, target.getHeight() + 0.25, 0);
        Visuals.Effect ring = plugin.visuals().spawn("stun_ring", target.getLocation().add(above)).billboard().size(0.2).send(0)
                .animate(1, 3, e -> e.size(1.1))
                .follow(target, above, ticks);
        for (int t = 4; t < ticks; t += 4) {
            double size = (t / 4) % 2 == 0 ? 1.1 : 1.0;
            ring.animate(t, 4, e -> e.size(size));
        }
        ring.vanish(ticks, 3);
    }

    private void grapple(Hook hook, Location point) {
        AbilitySettings settings = plugin.settings().ability(Ability.CANDY_HOOK);
        Player player = hook.player;
        hook.effect.moveTo(point, 1).vanish(10, 3);
        Vector way = point.toVector().subtract(player.getLocation().toVector());
        double distance = way.length();
        if (distance < 1.5 || settings.num("grapple") <= 0) {
            return;
        }
        Vector velocity = way.normalize().multiply(Math.min(2.4, 0.5 + distance * 0.09) * settings.num("grapple"));
        velocity.setY(velocity.getY() + 0.35);
        player.setVelocity(velocity);
        player.setFallDistance(0f);
        plugin.abilities().softLanding(player, 60);
        plugin.fx().sound(point, "candy-hook-catch");
        plugin.fx().view(point).particle(Fx.CRIT, point, 12, 0.2, 0.2, 0.2, 0.2);
    }

    // ---- Candy Cyclone -------------------------------------------------------------------------------------

    private Result cyclone(Player player) {
        AbilitySettings settings = plugin.settings().ability(Ability.CANDY_CYCLONE);
        long now = plugin.tick();
        int duration = settings.ticks("duration");
        Cyclone cyclone = new Cyclone(player, now + duration, now + 1);
        double radius = settings.num("radius");
        // Three candy rings stacked into a tornado, each spinning its own way.
        double[][] rings = {{0.25, radius * 2.0}, {1.05, radius * 1.6}, {1.85, radius * 1.15}};
        for (double[] ring : rings) {
            Vector offset = new Vector(0, ring[0], 0);
            Visuals.Effect effect = plugin.visuals().spawn("candy_ring", player.getLocation().add(offset))
                    .size(0.4).send(0)
                    .animate(1, 5, e -> e.size(ring[1]))
                    .follow(player, offset, duration);
            cyclone.rings.add(effect);
        }
        cyclones.put(player.getUniqueId(), cyclone);
        Hits.effect(player, "speed", settings.whole("speed-level"), duration);
        plugin.fx().sound(player.getLocation(), "candy-cyclone");
        return Result.FIRED;
    }

    private void cycloneTick(Cyclone cyclone, long now) {
        Player player = cyclone.player;
        if (now % 4 == 0) {
            cyclone.turn += 1;
            for (int i = 0; i < cyclone.rings.size(); i++) {
                int sign = i % 2 == 0 ? 1 : -1;
                double angle = sign * cyclone.turn * 100.0;
                cyclone.rings.get(i).turn(angle).send(4);
            }
        }
        AbilitySettings settings = plugin.settings().ability(Ability.CANDY_CYCLONE);
        double radius = settings.num("radius");
        Location center = player.getLocation().add(0, 1.0, 0);
        Fx.View view = plugin.fx().view(center);
        double spin = now * 0.6;
        for (int i = 0; i < 3; i++) {
            double a = spin + i * (Math.PI * 2 / 3);
            Location point = center.clone().add(Math.cos(a) * radius * 0.8, (i - 1) * 0.6, Math.sin(a) * radius * 0.8);
            view.dust(point, i == 0 ? PINK : i == 1 ? SUGAR : CANDY_RED, 1.5f, 2, 0.1);
        }
        if (now < cyclone.nextHit) {
            return;
        }
        cyclone.nextHit = now + settings.ticks("interval");
        for (LivingEntity target : plugin.hits().around(player, center, radius)) {
            Vector in = player.getLocation().toVector().subtract(target.getLocation().toVector()).setY(0);
            if (plugin.hits().hurt(player, target, settings.num("damage"))) {
                if (in.lengthSquared() > 0.25) {
                    Vector pull = in.normalize().multiply(settings.num("pull"));
                    target.setVelocity(target.getVelocity().multiply(0.5).add(pull.setY(0.05)));
                }
                plugin.fx().sound(target.getLocation(), "candy-cyclone-hit");
                view.dust(Geo.middle(target), PINK, 1.2f, 5, 0.25);
            }
        }
    }

    /** Arrows and other shots bounce off the cyclone. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onShot(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Projectile projectile && event.getEntity() instanceof Player player
                && cyclones.containsKey(player.getUniqueId()) && !plugin.hits().probing()) {
            event.setCancelled(true);
            projectile.setVelocity(projectile.getVelocity().multiply(-0.6));
            plugin.fx().sound(player.getLocation(), "candy-cyclone-hit");
            plugin.fx().view(projectile.getLocation()).particle(Fx.CRIT, projectile.getLocation(), 8, 0.1, 0.1, 0.1, 0.2);
        }
    }

    private void burst(Cyclone cyclone) {
        Player player = cyclone.player;
        AbilitySettings settings = plugin.settings().ability(Ability.CANDY_CYCLONE);
        double radius = settings.num("radius") + 0.5;
        for (Visuals.Effect ring : cyclone.rings) {
            ring.animate(1, 3, e -> e.size(radius * 2.6)).vanish(4, 3);
        }
        Location center = player.getLocation().add(0, 1.0, 0);
        plugin.fx().sound(center, "candy-burst");
        plugin.visuals().spawn("candy_burst", player.getLocation().add(0, 0.1, 0)).size(1.0).send(0)
                .animate(1, 5, e -> e.size(radius * 2.4).turn(180))
                .vanish(6, 4);
        Fx.View view = plugin.fx().view(center);
        view.particle(Fx.FIREWORK, center, 40, radius / 2, 0.6, radius / 2, 0.15);
        view.dust(center, PINK, 2.0f, 40, radius / 2);
        if (settings.num("burst-damage") <= 0) {
            return;
        }
        for (LivingEntity target : plugin.hits().around(player, center, radius)) {
            if (plugin.hits().hurt(player, target, settings.num("burst-damage"))) {
                Vector away = Geo.away(player.getLocation(), target.getLocation(), Geo.flat(player.getLocation()));
                Hits.knock(target, away, settings.num("burst-knockback"), settings.num("burst-lift"));
            }
        }
    }

    // ---- Sugar High (passive) --------------------------------------------------------------------------------

    @Override
    public void melee(EntityDamageByEntityEvent event, Player attacker, LivingEntity target, WeaponItems.Tag weapon) {
        AbilitySettings settings = plugin.settings().ability(Ability.SUGAR_HIGH);
        long now = plugin.tick();
        Sugar state = sugar.computeIfAbsent(attacker.getUniqueId(), id -> new Sugar());
        if (now - state.last < settings.ticks("min-hit-gap")) {
            return; // Spam clicks do not count.
        }
        state.last = now;
        state.until = now + settings.ticks("stack-duration");
        int max = settings.whole("stacks");
        if (state.stacks >= max) {
            // Full: this hit is the Sugar Crash.
            state.stacks = 0;
            event.setDamage(event.getDamage() + settings.num("crash-damage"));
            crashing.put(attacker.getUniqueId(), target.getUniqueId());
            return;
        }
        state.stacks++;
        // Faster with the sugar: Speed I from a third of the stacks, Speed II from two thirds.
        int level = state.stacks * 3 >= max * 2 ? 2 : state.stacks * 3 >= max ? 1 : 0;
        Hits.effect(attacker, "speed", level, settings.ticks("stack-duration"));
        Location at = Geo.middle(attacker);
        plugin.fx().view(at).dust(at, state.stacks == max ? CANDY_RED : PINK, 1.0f, 2 + state.stacks, 0.35);
        if (state.stacks == max) {
            plugin.fx().sound(attacker.getLocation(), "sugar-high-full");
        }
    }

    @Override
    public void landed(Player attacker, LivingEntity target, WeaponItems.Tag weapon) {
        UUID crash = crashing.remove(attacker.getUniqueId());
        if (crash == null || !crash.equals(target.getUniqueId())) {
            return;
        }
        AbilitySettings settings = plugin.settings().ability(Ability.SUGAR_HIGH);
        Location at = Geo.middle(target);
        plugin.fx().sound(at, "sugar-crash");
        plugin.visuals().spawn("candy_burst", at).billboard().size(0.4).send(0)
                .animate(1, 3, e -> e.size(settings.num("splash-radius") * 2.0))
                .vanish(4, 3);
        Fx.View view = plugin.fx().view(at);
        view.particle(Fx.FIREWORK, at, 25, 0.4, 0.4, 0.4, 0.2);
        view.dust(at, PINK, 1.8f, 20, 0.8);
        Hits.knock(target, Geo.away(attacker.getLocation(), target.getLocation(), Geo.flat(attacker.getLocation())),
                settings.num("knockback"), 0.35);
        if (settings.num("splash-damage") <= 0) {
            return;
        }
        for (LivingEntity other : plugin.hits().around(attacker, at, settings.num("splash-radius"))) {
            if (!other.equals(target) && plugin.hits().hurt(attacker, other, settings.num("splash-damage"))) {
                Hits.knock(other, Geo.away(at, other.getLocation(), Geo.flat(attacker.getLocation())),
                        settings.num("knockback"), 0.35);
            }
        }
    }
}
