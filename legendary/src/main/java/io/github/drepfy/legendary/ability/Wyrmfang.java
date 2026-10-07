package io.github.drepfy.legendary.ability;

import io.github.drepfy.legendary.Ability;
import io.github.drepfy.legendary.LegendaryPlugin;
import io.github.drepfy.legendary.WeaponType;
import io.github.drepfy.legendary.config.AbilitySettings;
import io.github.drepfy.legendary.item.WeaponItems;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Wyrmfang, the jade dragon greatsword (it took the Riftblade's place in 2.0): venom and fire.
 * <ul>
 *   <li><b>Wyrm Lunge</b>: a low leap forward on dragon wings. The first enemy you reach is
 *   seized: hurt, thrown up and slowed.</li>
 *   <li><b>Dragon's Breath</b>: for a moment you breathe green dragon fire where you look; everyone
 *   in the cone is burned again and again and poisoned.</li>
 *   <li><b>Venom Fang</b> (passive): sword hits poison the target.</li>
 * </ul>
 */
final class Wyrmfang implements Kit {

    private static final Color JADE = Color.fromRGB(50, 215, 120);
    private static final Color VENOM = Color.fromRGB(160, 255, 90);
    private static final Color GOLD = Color.fromRGB(245, 195, 70);

    private final LegendaryPlugin plugin;
    private final Map<UUID, Lunge> lunges = new HashMap<>();
    private final Map<UUID, Breath> breaths = new HashMap<>();

    Wyrmfang(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    private static final class Lunge {
        final Player player;
        final long start;
        /** Reached but could not be hurt (a no-PvP area): the lunge goes on past them. */
        final Set<UUID> spared = new HashSet<>();

        Lunge(Player player, long start) {
            this.player = player;
            this.start = start;
        }
    }

    private static final class Breath {
        final Player player;
        final long until;
        long next;

        Breath(Player player, long until, long next) {
            this.player = player;
            this.until = until;
            this.next = next;
        }
    }

    @Override
    public WeaponType type() {
        return WeaponType.WYRMFANG;
    }

    @Override
    public Result use(Player player, WeaponItems.Tag weapon, Ability ability) {
        return ability == Ability.DRAGONS_BREATH ? breathe(player) : lunge(player);
    }

    @Override
    public long active(Player player, WeaponItems.Tag weapon, Ability ability, long now) {
        Breath breath = ability == Ability.DRAGONS_BREATH ? breaths.get(player.getUniqueId()) : null;
        return breath == null ? 0 : Math.max(0, breath.until - now);
    }

    @Override
    public long activeLength(Ability ability) {
        return ability == Ability.DRAGONS_BREATH ? plugin.settings().ability(ability).ticks("duration") : 0;
    }

    @Override
    public void forget(Player player) {
        lunges.remove(player.getUniqueId());
        breaths.remove(player.getUniqueId());
    }

    @Override
    public void tick(long now) {
        for (Iterator<Lunge> it = lunges.values().iterator(); it.hasNext(); ) {
            Lunge lunge = it.next();
            if (!lunge.player.isOnline() || lunge.player.isDead() || reach(lunge, now)) {
                it.remove();
            }
        }
        for (Iterator<Breath> it = breaths.values().iterator(); it.hasNext(); ) {
            Breath breath = it.next();
            if (!breath.player.isOnline() || breath.player.isDead() || now >= breath.until) {
                it.remove();
            } else if (now >= breath.next) {
                breath.next = now + Math.max(1, plugin.settings().ability(Ability.DRAGONS_BREATH).ticks("interval"));
                pulse(breath.player);
            }
        }
    }

    // ---- Wyrm Lunge ------------------------------------------------------------------------------------------

    private Result lunge(Player player) {
        AbilitySettings settings = plugin.settings().ability(Ability.WYRM_LUNGE);
        Vector direction = Geo.flat(player.getLocation());
        Vector velocity = direction.clone().multiply(settings.num("forward"));
        velocity.setY(settings.num("up"));
        player.setVelocity(velocity);
        player.setFallDistance(0f);
        plugin.abilities().softLanding(player, 80);
        lunges.put(player.getUniqueId(), new Lunge(player, plugin.tick()));
        plugin.fx().sound(player.getLocation(), "wyrm-lunge");
        Location at = player.getLocation().add(0, 1.0, 0);
        plugin.visuals().spawn("wyrm_slash", at.clone().add(direction.clone().multiply(0.8))).facing(direction).tilt(-20)
                .size(1.0).send(0)
                .animate(1, 3, e -> e.size(3.8))
                .vanish(7, 5);
        Fx.View view = plugin.fx().view(at);
        view.particle(Fx.DRAGON_BREATH, at, 25, 0.5, 0.4, 0.5, 0.02);
        view.dust(at, JADE, 1.6f, 16, 0.6);
        return Result.FIRED;
    }

    /** One tick of the lunge; true when it is over (someone was seized, it landed, or it ran out). */
    private boolean reach(Lunge lunge, long now) {
        AbilitySettings settings = plugin.settings().ability(Ability.WYRM_LUNGE);
        Player player = lunge.player;
        long flying = now - lunge.start;
        Location middle = Geo.middle(player);
        Fx.View view = plugin.fx().view(middle);
        view.dust(middle, flying % 2 == 0 ? JADE : GOLD, 1.3f, 3, 0.3);
        view.particle(Fx.DRAGON_BREATH, middle, 2, 0.2, 0.2, 0.2, 0.01);
        for (LivingEntity target : plugin.hits().around(player, middle, 1.7)) {
            if (!lunge.spared.contains(target.getUniqueId())) {
                if (seize(player, target)) {
                    return true;
                }
                lunge.spared.add(target.getUniqueId());
            }
        }
        boolean grounded = flying >= 5 && Geo.solid(player.getLocation().subtract(0, 0.08, 0))
                && player.getVelocity().getY() <= 0.05;
        return grounded || flying >= settings.ticks("reach-time");
    }

    /** @return whether the target was seized (false when it could not be hurt there) */
    private boolean seize(Player player, LivingEntity target) {
        AbilitySettings settings = plugin.settings().ability(Ability.WYRM_LUNGE);
        if (!plugin.hits().hurt(player, target, settings.num("damage"))) {
            return false;
        }
        player.setVelocity(player.getVelocity().multiply(0.2));
        Hits.knock(target, Geo.flat(player.getLocation()), 0.3, settings.num("launch"));
        Hits.effect(target, "slowness", settings.whole("slow-level"), settings.ticks("slow-duration"));
        Location at = Geo.middle(target);
        plugin.visuals().spawn("wyrm_claw", at).billboard().tilt(-25).size(0.5).send(0)
                .animate(1, 2, e -> e.size(3.0))
                .vanish(9, 6);
        Fx.View view = plugin.fx().view(at);
        view.dust(at, VENOM, 1.6f, 18, 0.4);
        view.particle(Fx.CRIT, at, 14, 0.3, 0.4, 0.3, 0.3);
        return true;
    }

    // ---- Dragon's Breath -------------------------------------------------------------------------------------

    private Result breathe(Player player) {
        AbilitySettings settings = plugin.settings().ability(Ability.DRAGONS_BREATH);
        long now = plugin.tick();
        breaths.put(player.getUniqueId(), new Breath(player, now + settings.ticks("duration"), now));
        plugin.fx().sound(player.getLocation(), "dragons-breath");
        return Result.FIRED;
    }

    /** One gust of fire: everyone in the cone in front of the player's face is burned and poisoned. */
    private void pulse(Player player) {
        AbilitySettings settings = plugin.settings().ability(Ability.DRAGONS_BREATH);
        Location eye = player.getEyeLocation();
        Vector look = eye.getDirection().normalize();
        double range = settings.num("range");
        Location mouth = eye.clone().add(look.clone().multiply(0.9)).subtract(0, 0.15, 0);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < 2; i++) {
            Vector spread = look.clone().add(new Vector(random.nextDouble(-0.18, 0.18), random.nextDouble(-0.12, 0.12),
                    random.nextDouble(-0.18, 0.18))).normalize();
            Location far = mouth.clone().add(spread.multiply(range * (0.75 + random.nextDouble() * 0.25)));
            plugin.visuals().spawn("dragon_flame", mouth).billboard().tilt(random.nextInt(360)).size(0.45).send(0)
                    .animate(1, 7, e -> e.size(2.6 + random.nextDouble()))
                    .glide(1, far, 7)
                    .vanish(8, 3);
        }
        Fx.View view = plugin.fx().view(eye);
        for (double d = 1.0; d <= range; d += 1.5) {
            Location point = mouth.clone().add(look.clone().multiply(d));
            double width = 0.15 + d * Math.tan(Math.toRadians(settings.num("angle"))) * 0.5;
            view.particle(Fx.DRAGON_BREATH, point, 3, width, width * 0.6, width, 0.01);
        }
        double cosine = Math.cos(Math.toRadians(settings.num("angle")));
        for (LivingEntity target : plugin.hits().around(player, eye, range)) {
            Location middle = Geo.middle(target);
            Vector toward = middle.toVector().subtract(eye.toVector());
            double distance = toward.length();
            if (distance < 1.0E-3 || toward.multiply(1.0 / distance).dot(look) < cosine || !Geo.clear(eye, middle)) {
                continue;
            }
            if (plugin.hits().hurt(player, target, settings.num("damage"))) {
                Hits.effect(target, "poison", settings.whole("poison-level"), settings.ticks("poison-duration"));
                view.dust(middle, VENOM, 1.2f, 4, 0.3);
            }
        }
    }

    // ---- Venom Fang (passive) --------------------------------------------------------------------------------

    @Override
    public void landed(Player attacker, LivingEntity target, WeaponItems.Tag weapon) {
        AbilitySettings settings = plugin.settings().ability(Ability.VENOM_FANG);
        Hits.effect(target, "poison", settings.whole("poison-level"), settings.ticks("poison-duration"));
        Location at = Geo.middle(target);
        plugin.fx().view(at).dust(at, VENOM, 1.0f, 5, 0.3);
    }
}
