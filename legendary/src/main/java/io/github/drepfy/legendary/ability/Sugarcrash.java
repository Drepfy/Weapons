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
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Sugarcrash, the candy cane: mobility and burst tempo.
 * <ul>
 *   <li><b>Sugar Rush</b>: Speed and Haste (faster attacks) for a few seconds with a candy
 *   trail; the cooldown starts when it wears off.</li>
 *   <li><b>Sweet Shock</b>: a shockwave around the player knocks everyone back and slows them.</li>
 * </ul>
 */
final class Sugarcrash implements Kit {

    static final Color[] CANDY = {Color.fromRGB(255, 45, 75), Color.WHITE, Color.fromRGB(255, 140, 210),
            Color.fromRGB(120, 255, 180)};

    private final LegendaryPlugin plugin;
    private final Map<UUID, Rush> rushes = new HashMap<>();

    Sugarcrash(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    private static final class Rush {
        final UUID weapon;
        final long until;
        Location last;
        int colour;

        Rush(UUID weapon, long until, Location last) {
            this.weapon = weapon;
            this.until = until;
            this.last = last;
        }
    }

    @Override
    public WeaponType type() {
        return WeaponType.SUGARCRASH;
    }

    @Override
    public Result use(Player player, WeaponItems.Tag weapon, Ability ability) {
        return ability == Ability.SUGAR_RUSH ? rush(player, weapon) : shock(player);
    }

    // ---- Sugar Rush -----------------------------------------------------------------------------------------

    private Result rush(Player player, WeaponItems.Tag weapon) {
        AbilitySettings settings = plugin.settings().ability(Ability.SUGAR_RUSH);
        long now = plugin.tick();
        int ticks = settings.ticks("duration");
        effect(player, "speed", settings.whole("speed-level"), ticks);
        effect(player, "haste", settings.whole("haste-level"), ticks);
        rushes.put(player.getUniqueId(), new Rush(weapon.id(), now + ticks, player.getLocation()));
        // The cooldown starts when the rush wears off.
        plugin.abilities().cooldowns().start(weapon.id(), Ability.SUGAR_RUSH, now, ticks + settings.ticks("cooldown"));
        plugin.fx().sound(player.getLocation(), "sugar-rush");
        Fx.View view = plugin.fx().view(player.getLocation());
        for (int i = 0; i < 24; i++) {
            double angle = Math.PI * 2 * i / 24;
            Location point = player.getLocation().add(Math.cos(angle) * 0.9, 1.0 + Math.sin(angle * 3) * 0.3,
                    Math.sin(angle) * 0.9);
            view.dust(point, CANDY[i % CANDY.length], 1.2f, 1, 0.02);
        }
        view.particle(Fx.FIREWORK, player.getLocation().add(0, 1, 0), 12, 0.3, 0.4, 0.3, 0.08);
        return Result.HANDLED;
    }

    private static void effect(LivingEntity entity, String key, int level, int ticks) {
        if (level <= 0 || ticks <= 0) {
            return;
        }
        PotionEffectType type = Compat.effect(key);
        if (type != null) {
            entity.addPotionEffect(new PotionEffect(type, ticks, level - 1, false, true, true));
        }
    }

    @Override
    public long active(Player player, WeaponItems.Tag weapon, Ability ability, long now) {
        if (ability != Ability.SUGAR_RUSH) {
            return 0;
        }
        Rush rush = rushes.get(player.getUniqueId());
        return rush != null && rush.weapon.equals(weapon.id()) ? Math.max(0, rush.until - now) : 0;
    }

    @Override
    public void tick(long now) {
        Iterator<Map.Entry<UUID, Rush>> it = rushes.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Rush> entry = it.next();
            Player player = Bukkit.getPlayer(entry.getKey());
            Rush rush = entry.getValue();
            if (player == null || !player.isOnline() || player.isDead()) {
                it.remove();
                continue;
            }
            if (now >= rush.until) {
                it.remove();
                plugin.fx().sound(player.getLocation(), "sugar-rush-end");
                plugin.send(player, "sugar-rush-end");
                continue;
            }
            if (now % 2 == 0) {
                Location at = player.getLocation();
                if (rush.last == null || !rush.last.getWorld().equals(at.getWorld()) || rush.last.distanceSquared(at) > 0.01) {
                    Fx.View view = plugin.fx().view(at);
                    for (int i = 0; i < 3; i++) {
                        view.dust(at.clone().add(0, 0.15 + i * 0.25, 0), CANDY[(rush.colour + i) % CANDY.length], 1.0f, 1, 0.12);
                    }
                    rush.colour++;
                }
                rush.last = at;
            }
        }
    }

    // ---- Sweet Shock -----------------------------------------------------------------------------------------

    private Result shock(Player player) {
        AbilitySettings settings = plugin.settings().ability(Ability.SWEET_SHOCK);
        Location center = player.getLocation();
        double radius = settings.num("radius");
        plugin.fx().sound(center, "sweet-shock");
        Vector facing = Geo.flat(center);
        Location eye = center.clone().add(0, 1.0, 0);
        for (Entity entity : center.getWorld().getNearbyEntities(center, radius, 2.5, radius)) {
            if (!(entity instanceof LivingEntity target) || !plugin.hits().canTarget(player, entity)
                    || Geo.flatDistance(center, entity.getLocation()) > radius || !Geo.clear(eye, Geo.middle(entity))) {
                continue;
            }
            if (plugin.hits().hurt(player, target, settings.num("damage"))) {
                Hits.knock(target, Geo.away(center, entity.getLocation(), facing), settings.num("knockback"),
                        settings.num("lift"));
                effect(target, "slowness", settings.whole("slow-level"), settings.ticks("slow-duration"));
                Fx.View view = plugin.fx().view(entity.getLocation());
                for (Color colour : CANDY) {
                    view.dust(Geo.middle(entity), colour, 1.3f, 4, 0.35);
                }
            }
        }
        new Wave(center, radius).runTaskTimer(plugin, 0L, 1L);
        return Result.FIRED;
    }

    /** The ring of candy rolling outwards. */
    private final class Wave extends BukkitRunnable {
        private final Location center;
        private final double radius;
        private int frame;

        Wave(Location center, double radius) {
            this.center = center;
            this.radius = radius;
        }

        @Override
        public void run() {
            frame++;
            double r = radius * frame / 6.0;
            Fx.View view = plugin.fx().view(center);
            int points = (int) Math.max(12, r * 9);
            for (int i = 0; i < points; i++) {
                double angle = Math.PI * 2 * i / points;
                Location point = center.clone().add(Math.cos(angle) * r, 0.2, Math.sin(angle) * r);
                view.dust(point, CANDY[(i + frame) % CANDY.length], 1.4f, 1, 0.03);
            }
            if (frame == 1) {
                view.particle(Fx.FIREWORK, center.clone().add(0, 0.3, 0), 30, 0.4, 0.1, 0.4, 0.25);
            }
            if (frame >= 6) {
                cancel();
            }
        }
    }
}
