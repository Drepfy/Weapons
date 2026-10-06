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
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 *   <li><b>Sugar High</b> (passive): Speed I while the scythe is in hand. Each hit adds a sugar
 *   stack (Speed II from two thirds); at full stacks the next hit is a Sugar Crash, a candy
 *   explosion round the target.</li>
 * </ul>
 */
final class Sugarcrash implements Kit, Listener {

    private static final Color PINK = Color.fromRGB(255, 92, 165);
    private static final Color CANDY_RED = Color.fromRGB(225, 30, 60);
    private static final Color SUGAR = Color.fromRGB(255, 245, 250);
    private static final Color[] STARS = {PINK, SUGAR, Color.fromRGB(255, 70, 90)};
    private static final ItemStack SUGAR_BITS = new ItemStack(Material.SUGAR);

    private final LegendaryPlugin plugin;
    private final List<Hook> hooks = new ArrayList<>();
    /** Weapon → the candy canes floating round its holder's head. */
    private final Map<UUID, Barrage> barrages = new HashMap<>();
    /** Candy canes in flight. */
    private final List<Cane> canes = new ArrayList<>();
    private final Map<UUID, Sugar> sugar = new HashMap<>();
    /** Attacker → the target their Sugar Crash is landing on. */
    private final Map<UUID, UUID> crashing = new HashMap<>();
    /** Players given Speed for holding the scythe. */
    private final Set<UUID> rushing = new HashSet<>();

    Sugarcrash(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    private static final class Hook {
        final Player player;
        final Vector direction;
        Location position;
        double travelled;

        Hook(Player player, Location position, Vector direction) {
            this.player = player;
            this.position = position;
            this.direction = direction;
        }
    }

    private static final class Barrage {
        final Player player;
        final long until;
        /** How many there were at first (their places round the head). */
        final int total;
        /** How many still float over the head. */
        int left;
        long nextShot;

        Barrage(Player player, long until, int total) {
            this.player = player;
            this.until = until;
            this.total = total;
            this.left = total;
        }
    }

    private static final class Cane {
        final Player player;
        final Vector direction;
        Location position;
        double travelled;

        Cane(Player player, Location position, Vector direction) {
            this.player = player;
            this.position = position;
            this.direction = direction;
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
        hooks.removeIf(hook -> hook.player.equals(player));
        for (Iterator<Map.Entry<UUID, Barrage>> it = barrages.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Barrage> barrage = it.next();
            if (barrage.getValue().player.equals(player)) {
                it.remove();
                plugin.abilities().startCooldown(barrage.getKey(), Ability.CANDY_BARRAGE);
            }
        }
        canes.removeIf(cane -> cane.player.equals(player));
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
            if (!barrage.player.isOnline() || barrage.player.isDead() || now >= barrage.until || barrage.left <= 0) {
                it.remove();
                if (barrage.left > 0 && barrage.player.isOnline()) {
                    Location head = barrage.player.getLocation().add(0, barrage.player.getHeight() + 0.45, 0);
                    plugin.fx().view(head).item(head, SUGAR_BITS, 10, 0.5, 0.05); // The rest crumble.
                }
                plugin.abilities().startCooldown(entry.getKey(), Ability.CANDY_BARRAGE);
                continue;
            }
            if (now % 3 == 0) {
                circle(barrage, now);
            }
        }
        for (Iterator<Cane> it = canes.iterator(); it.hasNext(); ) {
            Cane cane = it.next();
            if (!cane.player.isOnline() || cane.player.isDead() || caneTick(cane)) {
                it.remove();
            }
        }
        sugar.values().removeIf(state -> now >= state.until);
        if (now % 5 == 0) {
            rush();
        }
    }

    /** Sugar High: Speed while the scythe is in hand, gone soon after it is put away. */
    private void rush() {
        int level = plugin.settings().ability(Ability.SUGAR_HIGH).whole("speed-level");
        PotionEffectType speed = Compat.effect("speed");
        if (speed == null) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            WeaponItems.Tag held = player.isDead() ? null : plugin.items().read(player.getInventory().getItemInMainHand());
            if (level > 0 && held != null && held.type() == WeaponType.SUGARCRASH) {
                // Ambient and without swirls, like a beacon's: it is part of holding the scythe.
                player.addPotionEffect(new PotionEffect(speed, 40, level - 1, true, false, true));
                rushing.add(player.getUniqueId());
            } else if (rushing.remove(player.getUniqueId())) {
                PotionEffect current = player.getPotionEffect(speed);
                if (current != null && current.isAmbient() && current.getDuration() <= 40) {
                    player.removePotionEffect(speed);
                }
            }
        }
        rushing.removeIf(id -> Bukkit.getPlayer(id) == null);
    }

    /**
     * A candy cane drawn in red and white stripes: a shaft of {@code length} along {@code axis}
     * from {@code base}, curling over at the top towards {@code side}.
     */
    private static void cane(Fx.View view, Location base, Vector axis, Vector side, double length, float size) {
        double r = length * 0.24;
        double step = 0.075;
        List<Location> points = new ArrayList<>();
        for (double t = 0; t <= length; t += step) {
            points.add(base.clone().add(axis.clone().multiply(t)));
        }
        Location top = base.clone().add(axis.clone().multiply(length)).add(side.clone().multiply(r));
        int arc = Math.max(4, (int) Math.round(Math.PI * r / step));
        for (int i = 1; i <= arc; i++) {
            double a = Math.PI - Math.PI * i / arc;
            points.add(top.clone().add(side.clone().multiply(r * Math.cos(a))).add(axis.clone().multiply(r * Math.sin(a))));
        }
        Location end = top.clone().add(side.clone().multiply(r));
        for (double t = step; t <= r * 0.7; t += step) {
            points.add(end.clone().subtract(axis.clone().multiply(t)));
        }
        for (int i = 0; i < points.size(); i++) {
            view.dust(points.get(i), (i / 2) % 2 == 0 ? CANDY_RED : SUGAR, size, 1, 0);
        }
    }

    /** A burst of sugar: crystals flying out, pink and white dust and sparkles. */
    private void candyBurst(Location at, double radius, int amount) {
        Fx.View view = plugin.fx().view(at);
        view.item(at, SUGAR_BITS, amount, 0.15, 0.2);
        List<Location> shell = Shapes.sphere(at, radius, amount * 2);
        for (int i = 0; i < shell.size(); i++) {
            view.dust(shell.get(i), i % 3 == 0 ? SUGAR : i % 3 == 1 ? PINK : CANDY_RED, 1.2f, 1, 0.05);
        }
        view.particle(Fx.FIREWORK, at, amount / 2, 0.1, 0.1, 0.1, 0.15);
    }

    /** Two directions square to {@code axis}, for drawing a cane along it. */
    private static Vector across(Vector axis) {
        Vector side = axis.clone().crossProduct(new Vector(0, 1, 0));
        if (side.lengthSquared() < 1.0E-4) {
            side = new Vector(1, 0, 0);
        }
        return side.normalize().crossProduct(axis).normalize(); // Up-ish, square to the axis.
    }

    // ---- Candy Hook --------------------------------------------------------------------------------------

    private Result hook(Player player) {
        Location eye = player.getEyeLocation();
        Vector direction = eye.getDirection().normalize();
        Location start = eye.clone().add(direction.clone().multiply(0.6)).subtract(0, 0.25, 0);
        hooks.add(new Hook(player, start, direction));
        cane(plugin.fx().view(start), start.clone().subtract(direction.clone().multiply(0.5)), direction, across(direction),
                0.6, 0.8f);
        plugin.fx().sound(player.getLocation(), "candy-hook");
        return Result.FIRED;
    }

    /** Moves a hook on; true when it is done. */
    private boolean hookTick(Hook hook) {
        AbilitySettings settings = plugin.settings().ability(Ability.CANDY_HOOK);
        Player player = hook.player;
        World world = hook.position.getWorld();
        if (world == null || !world.equals(player.getWorld())) {
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
        cane(plugin.fx().view(to), to.clone().subtract(hook.direction.clone().multiply(0.5)), hook.direction,
                across(hook.direction), 0.6, 0.8f);
        return hook.travelled >= settings.num("range");
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
        if (!plugin.hits().hurt(player, target, Math.max(0.01, settings.num("damage")))) {
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
        plugin.fx().sound(target.getLocation(), "candy-hook-catch");
        candyBurst(at, 0.9, 14);
    }

    /** Stunned: three candy stars circle their head for as long as it lasts. */
    private void stun(LivingEntity target, int ticks) {
        Hits.stun(target, ticks);
        for (int t = 0; t < ticks; t += 2) {
            int step = t;
            plugin.visuals().later(t, () -> {
                if (!target.isValid() || target.isDead()) {
                    return;
                }
                Location head = target.getLocation().add(0, target.getHeight() + 0.35, 0);
                Fx.View view = plugin.fx().view(head);
                List<Location> stars = Shapes.ring(head, 0.45, 3, step * 0.4);
                for (int i = 0; i < stars.size(); i++) {
                    view.dust(stars.get(i), STARS[i], 1.1f, 1, 0);
                }
            });
        }
    }

    private void grapple(Hook hook, Location point) {
        AbilitySettings settings = plugin.settings().ability(Ability.CANDY_HOOK);
        Player player = hook.player;
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
        Fx.View view = plugin.fx().view(point);
        view.particle(Fx.CRIT, point, 12, 0.2, 0.2, 0.2, 0.2);
        view.item(point, SUGAR_BITS, 8, 0.1, 0.12);
    }

    // ---- Candy Barrage ------------------------------------------------------------------------------------

    private Result barrage(Player player, WeaponItems.Tag weapon) {
        Barrage old = barrages.remove(weapon.id());
        if (old != null) {
            // Still floating over whoever had the scythe before: theirs ends, the cooldown starts.
            plugin.abilities().startCooldown(weapon.id(), Ability.CANDY_BARRAGE);
            return Result.HANDLED;
        }
        AbilitySettings settings = plugin.settings().ability(Ability.CANDY_BARRAGE);
        long now = plugin.tick();
        int count = settings.whole("canes");
        Barrage barrage = new Barrage(player, now + settings.ticks("duration"), count);
        barrages.put(weapon.id(), barrage);
        plugin.fx().sound(player.getLocation(), "candy-barrage");
        // The canes swirl up out of a puff of sugar.
        Location head = player.getLocation().add(0, player.getHeight() + 0.45, 0);
        Fx.View view = plugin.fx().view(head);
        view.item(head, SUGAR_BITS, 14, 0.4, 0.08);
        List<Location> swirl = Shapes.spiral(player.getLocation().add(0, 0.2, 0), 0.9, player.getHeight() + 0.2, 1.5, 30, 0);
        for (int i = 0; i < swirl.size(); i++) {
            view.dust(swirl.get(i), i % 2 == 0 ? CANDY_RED : SUGAR, 0.9f, 1, 0);
        }
        circle(barrage, now);
        return Result.HANDLED; // The cooldown starts once the canes are fired or the time is up.
    }

    /** The canes still floating stand in a ring over the player's head, curling outwards. */
    private void circle(Barrage barrage, long now) {
        Player player = barrage.player;
        Location head = player.getLocation().add(0, player.getHeight() + 0.25, 0);
        double radius = 0.5 + 0.06 * barrage.total;
        Fx.View view = plugin.fx().view(head);
        Vector up = new Vector(0, 1, 0);
        for (int i = 0; i < barrage.left; i++) {
            double angle = Math.PI * 2 * i / barrage.total + Math.toRadians(player.getLocation().getYaw());
            double bob = Math.sin(now * 0.15 + i) * 0.06;
            Vector out = new Vector(Math.cos(angle), 0, Math.sin(angle));
            cane(view, head.clone().add(out.clone().multiply(radius)).add(0, bob, 0), up, out, 0.55, 0.65f);
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
        if (now < barrage.nextShot || barrage.left <= 0) {
            return;
        }
        AbilitySettings settings = plugin.settings().ability(Ability.CANDY_BARRAGE);
        barrage.nextShot = now + settings.ticks("shot-gap");
        Player player = barrage.player;
        barrage.left--;
        Location eye = player.getEyeLocation();
        Vector direction = eye.getDirection().normalize();
        Location start = eye.clone().add(direction.clone().multiply(0.7)).add(0, 0.15, 0);
        canes.add(new Cane(player, start, direction));
        plugin.fx().view(start).item(start, SUGAR_BITS, 4, 0.1, 0.05);
        plugin.fx().sound(player.getLocation(), "candy-barrage-shot", 0.05f * barrage.left);
        if (barrage.left <= 0) {
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
        // The cane spinning through the air, and a candy-striped trail behind it.
        Fx.View view = plugin.fx().view(from);
        double length = reached.distance(from);
        int i = 0;
        for (double d = 0.0; d < length; d += 0.4, i++) {
            view.dust(from.clone().add(cane.direction.clone().multiply(d)), i % 2 == 0 ? CANDY_RED : SUGAR, 0.7f, 1, 0.0);
        }
        Vector spin = across(cane.direction).rotateAroundAxis(cane.direction, cane.travelled * 1.3);
        cane(view, reached.clone().subtract(cane.direction.clone().multiply(0.35)), cane.direction, spin, 0.5, 0.75f);
        List<LivingEntity> caught = plugin.hits().along(player, from, reached, 0.5);
        if (!caught.isEmpty()) {
            LivingEntity target = caught.get(0);
            Location at = Geo.middle(target);
            if (plugin.hits().hurt(player, target, settings.num("damage"))) {
                if (settings.num("knockback") > 0) {
                    Hits.knock(target, cane.direction, settings.num("knockback"), 0.15);
                }
                plugin.fx().sound(at, "candy-barrage-hit");
                candyBurst(at, 0.6, 10);
            }
            return true;
        }
        if (wall != null) {
            Fx.View hit = plugin.fx().view(wall);
            hit.particle(Fx.CRIT, wall, 8, 0.15, 0.15, 0.15, 0.15);
            hit.item(wall, SUGAR_BITS, 8, 0.1, 0.12); // It shatters.
            return true;
        }
        cane.position = to;
        cane.travelled += step;
        return cane.travelled >= settings.num("range");
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
        double splash = settings.num("splash-radius");
        candyBurst(at, 1.0, 22);
        // A ring of sugar rolling out over the ground to the edge of the blast.
        Location ground = target.getLocation().add(0, 0.15, 0);
        Fx.View view = plugin.fx().view(at);
        for (int k = 1; k <= 3; k++) {
            double r = splash * k / 3.0;
            plugin.visuals().later(k - 1, () -> {
                List<Location> ring = Shapes.ring(ground, r, (int) (10 + r * 8), r);
                for (int i = 0; i < ring.size(); i++) {
                    view.dust(ring.get(i), i % 2 == 0 ? PINK : SUGAR, 1.1f, 1, 0);
                }
            });
        }
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
