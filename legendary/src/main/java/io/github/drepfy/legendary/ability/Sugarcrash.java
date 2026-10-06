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
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
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
 *   <li><b>Candy Barrage</b>: candy canes appear and float round the player's head. Each swing
 *   of the scythe (or Shift + F again) fires one where they look; the cooldown starts when they
 *   are all fired or the time is up.</li>
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
    /** Weapon → the candy canes floating round its holder's head. */
    private final Map<UUID, Barrage> barrages = new HashMap<>();
    /** Candy canes in flight. */
    private final List<Cane> canes = new ArrayList<>();
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

    private static final class Barrage {
        final Player player;
        final long until;
        /** How many there were at first (their places round the head). */
        final int total;
        final List<Visuals.Effect> floating = new ArrayList<>();
        long nextShot;

        Barrage(Player player, long until, int total) {
            this.player = player;
            this.until = until;
            this.total = total;
        }
    }

    private static final class Cane {
        final Player player;
        final Vector direction;
        final Visuals.Effect effect;
        Location position;
        double travelled;

        Cane(Player player, Location position, Vector direction, Visuals.Effect effect) {
            this.player = player;
            this.position = position;
            this.direction = direction;
            this.effect = effect;
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
        return ability == Ability.CANDY_HOOK ? hook(player) : barrage(player, weapon);
    }

    @Override
    public boolean recast(Player player, WeaponItems.Tag weapon, Ability ability) {
        Barrage barrage = ability == Ability.CANDY_BARRAGE ? barrages.get(weapon.id()) : null;
        if (barrage == null || !barrage.player.equals(player)) {
            return false;
        }
        fire(weapon.id(), barrage);
        return true;
    }

    @Override
    public long active(Player player, WeaponItems.Tag weapon, Ability ability, long now) {
        Barrage barrage = ability == Ability.CANDY_BARRAGE ? barrages.get(weapon.id()) : null;
        return barrage == null || !barrage.player.equals(player) ? 0 : Math.max(0, barrage.until - now);
    }

    @Override
    public long activeLength(Ability ability) {
        return ability == Ability.CANDY_BARRAGE ? plugin.settings().ability(ability).ticks("duration") : 0;
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
        for (Iterator<Map.Entry<UUID, Barrage>> it = barrages.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Barrage> barrage = it.next();
            if (barrage.getValue().player.equals(player)) {
                it.remove();
                barrage.getValue().floating.forEach(Visuals.Effect::remove);
                plugin.abilities().startCooldown(barrage.getKey(), Ability.CANDY_BARRAGE);
            }
        }
        canes.removeIf(cane -> {
            if (cane.player.equals(player)) {
                cane.effect.remove();
                return true;
            }
            return false;
        });
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
        for (Iterator<Map.Entry<UUID, Barrage>> it = barrages.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Barrage> entry = it.next();
            Barrage barrage = entry.getValue();
            if (!barrage.player.isOnline() || barrage.player.isDead() || now >= barrage.until
                    || barrage.floating.isEmpty()) {
                it.remove();
                barrage.floating.forEach(cane -> cane.vanish(0, 3));
                plugin.abilities().startCooldown(entry.getKey(), Ability.CANDY_BARRAGE);
                continue;
            }
            circle(barrage, now, 2);
        }
        for (Iterator<Cane> it = canes.iterator(); it.hasNext(); ) {
            Cane cane = it.next();
            if (!cane.player.isOnline() || cane.player.isDead() || caneTick(cane)) {
                it.remove();
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

    // ---- Candy Barrage ------------------------------------------------------------------------------------

    private Result barrage(Player player, WeaponItems.Tag weapon) {
        Barrage old = barrages.remove(weapon.id());
        if (old != null) {
            // Still floating over whoever had the scythe before: theirs ends, the cooldown starts.
            old.floating.forEach(cane -> cane.vanish(0, 3));
            plugin.abilities().startCooldown(weapon.id(), Ability.CANDY_BARRAGE);
            return Result.HANDLED;
        }
        AbilitySettings settings = plugin.settings().ability(Ability.CANDY_BARRAGE);
        long now = plugin.tick();
        int count = settings.whole("canes");
        Barrage barrage = new Barrage(player, now + settings.ticks("duration"), count);
        for (int i = 0; i < count; i++) {
            Location at = player.getLocation().add(0, player.getHeight() + 0.4, 0);
            barrage.floating.add(plugin.visuals().spawn("candy_hook", at).billboard().size(0.05).send(0)
                    .animate(1 + i, 3, e -> e.size(0.6)));
        }
        circle(barrage, now, 1);
        barrages.put(weapon.id(), barrage);
        plugin.fx().sound(player.getLocation(), "candy-barrage");
        Location head = player.getLocation().add(0, player.getHeight() + 0.4, 0);
        plugin.fx().view(head).dust(head, PINK, 1.2f, 16, 0.6);
        return Result.HANDLED; // The cooldown starts once the canes are fired or the time is up.
    }

    /** Keeps the floating canes in a slowly turning ring over the player's head, bobbing a little. */
    private void circle(Barrage barrage, long now, int glide) {
        Player player = barrage.player;
        Location head = player.getLocation().add(0, player.getHeight() + 0.45, 0);
        double radius = 0.55 + 0.06 * barrage.total;
        for (int i = 0; i < barrage.floating.size(); i++) {
            double angle = Math.PI * 2 * i / barrage.total + now * 0.08;
            double bob = Math.sin(now * 0.2 + i) * 0.08;
            barrage.floating.get(i).moveTo(head.clone().add(Math.cos(angle) * radius, bob, Math.sin(angle) * radius), glide);
        }
    }

    /** A swing with the scythe in hand fires the next cane. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwing(PlayerAnimationEvent event) {
        if (barrages.isEmpty() || event.getAnimationType() != PlayerAnimationType.ARM_SWING) {
            return;
        }
        Player player = event.getPlayer();
        WeaponItems.Tag held = plugin.items().read(player.getInventory().getItemInMainHand());
        Barrage barrage = held == null ? null : barrages.get(held.id());
        if (barrage != null && barrage.player.equals(player)) {
            fire(held.id(), barrage);
        }
    }

    /** Fires the next floating cane where the player looks. */
    private void fire(UUID weapon, Barrage barrage) {
        long now = plugin.tick();
        if (now < barrage.nextShot || barrage.floating.isEmpty()) {
            return;
        }
        AbilitySettings settings = plugin.settings().ability(Ability.CANDY_BARRAGE);
        barrage.nextShot = now + settings.ticks("shot-gap");
        Player player = barrage.player;
        Visuals.Effect effect = barrage.floating.remove(barrage.floating.size() - 1);
        Location eye = player.getEyeLocation();
        Vector direction = eye.getDirection().normalize();
        Location start = eye.clone().add(direction.clone().multiply(0.7)).add(0, 0.15, 0);
        effect.size(0.7).send(1).moveTo(start, 1);
        canes.add(new Cane(player, start, direction, effect));
        plugin.fx().sound(player.getLocation(), "candy-barrage-shot", 0.05f * barrage.floating.size());
        if (barrage.floating.isEmpty()) {
            barrages.remove(weapon);
            plugin.abilities().startCooldown(weapon, Ability.CANDY_BARRAGE);
        }
    }

    /** Moves a cane on; true when it is done. */
    private boolean caneTick(Cane cane) {
        AbilitySettings settings = plugin.settings().ability(Ability.CANDY_BARRAGE);
        Player player = cane.player;
        World world = cane.position.getWorld();
        if (world == null || !world.equals(player.getWorld())) {
            cane.effect.remove();
            return true;
        }
        double step = settings.num("speed");
        Location from = cane.position.clone();
        Location to = from.clone().add(cane.direction.clone().multiply(step));
        Location wall = null;
        Location last = from.clone();
        for (double d = 0.25; d <= step + 1.0E-6; d += 0.25) {
            Location point = from.clone().add(cane.direction.clone().multiply(d));
            if (Geo.solid(point)) {
                wall = last;
                break;
            }
            last = point;
        }
        Location reached = wall != null ? wall : to;
        // A candy-striped trail.
        Fx.View view = plugin.fx().view(from);
        double length = reached.distance(from);
        int i = 0;
        for (double d = 0.0; d < length; d += 0.6, i++) {
            view.dust(from.clone().add(cane.direction.clone().multiply(d)), i % 2 == 0 ? CANDY_RED : SUGAR, 0.9f, 1, 0.0);
        }
        List<LivingEntity> caught = plugin.hits().along(player, from, reached, 0.5);
        if (!caught.isEmpty()) {
            LivingEntity target = caught.get(0);
            Location at = Geo.middle(target);
            cane.effect.moveTo(at, 1).vanish(1, 2);
            if (plugin.hits().hurt(player, target, settings.num("damage"))) {
                if (settings.num("knockback") > 0) {
                    Hits.knock(target, cane.direction, settings.num("knockback"), 0.15);
                }
                plugin.fx().sound(at, "candy-barrage-hit");
                plugin.visuals().spawn("candy_burst", at).billboard().size(0.2).send(0)
                        .animate(1, 3, e -> e.size(1.1))
                        .vanish(4, 3);
                plugin.fx().view(at).dust(at, PINK, 1.2f, 8, 0.3);
            }
            return true;
        }
        if (wall != null) {
            cane.effect.moveTo(wall, 1).vanish(2, 3);
            plugin.fx().view(wall).particle(Fx.CRIT, wall, 8, 0.15, 0.15, 0.15, 0.15);
            return true;
        }
        cane.position = to;
        cane.travelled += step;
        cane.effect.moveTo(to, 1);
        if (cane.travelled >= settings.num("range")) {
            cane.effect.vanish(0, 3);
            return true;
        }
        return false;
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
        plugin.fx().sound(attacker.getLocation(), "sugar-high-stack", 0.12f * state.stacks); // Higher each stack.
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
