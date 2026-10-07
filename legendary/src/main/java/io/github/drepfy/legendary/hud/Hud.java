package io.github.drepfy.legendary.hud;

import io.github.drepfy.legendary.Ability;
import io.github.drepfy.legendary.LegendaryPlugin;
import io.github.drepfy.legendary.config.AbilitySettings;
import io.github.drepfy.legendary.config.Settings;
import io.github.drepfy.legendary.item.WeaponItems;
import io.github.drepfy.legendary.util.ActionBar;
import io.github.drepfy.legendary.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A boss bar at the top of the screen for each legendary ability that is running or recharging:
 * the ability's name and how long until it can be used again, the bar filling up as it recharges
 * (and running down while an ability lasts or can be pressed again). The bars stay while the
 * weapon is in the inventory, so switching to another item does not hide a cooldown. A ready
 * ability has no bar, unless display.boss-bars-when-ready is on (then the weapon in hand shows
 * all its bars). The action bar is left to other plugins (the Combat plugin's timer).
 */
public final class Hud {

    /** How long the bar flashes when an ability is used too early. */
    private static final int SHAKE_TICKS = 6;
    /** How often each inventory is looked through for legendaries not in hand (ticks). */
    private static final int CARRIED_EVERY = 10;

    private final LegendaryPlugin plugin;
    /** player → (weapon:ability → its bar), in the order they appeared. */
    private final Map<UUID, Map<String, Shown>> bars = new HashMap<>();
    /** player → the legendaries in their inventory, looked up every {@link #CARRIED_EVERY} ticks. */
    private final Map<UUID, List<WeaponItems.Tag>> carried = new HashMap<>();
    /** player:ability → the tick its flash ends. */
    private final Map<String, Long> shakes = new HashMap<>();

    public Hud(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    private record Shown(Ability ability, BossBar bar) {
    }

    /** A short message above the hotbar (nothing to aim at, a swap refused...). */
    public void notice(Player player, String text) {
        if (text != null && !text.isEmpty()) {
            ActionBar.send(player, text);
        }
    }

    /** Flashes the ability's bar: it was used before it was ready. */
    public void shake(Player player, Ability ability) {
        shakes.put(player.getUniqueId() + ":" + ability.key(), plugin.tick() + SHAKE_TICKS);
    }

    /** The bars a player sees right now (for tests). */
    public Map<Ability, BossBar> bars(Player player) {
        Map<String, Shown> shown = bars.get(player.getUniqueId());
        Map<Ability, BossBar> byAbility = new LinkedHashMap<>();
        if (shown != null) {
            shown.values().forEach(bar -> byAbility.putIfAbsent(bar.ability(), bar.bar()));
        }
        return byAbility;
    }

    /** Every couple of ticks. */
    public void update(long now) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            update(player, now);
        }
        for (Iterator<Map.Entry<UUID, Map<String, Shown>>> it = bars.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Map<String, Shown>> entry = it.next();
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) {
                entry.getValue().values().forEach(shown -> shown.bar().removeAll());
                it.remove();
            }
        }
        carried.keySet().removeIf(id -> Bukkit.getPlayer(id) == null);
        shakes.values().removeIf(until -> until <= now);
    }

    private void update(Player player, long now) {
        Settings settings = plugin.settings();
        UUID id = player.getUniqueId();
        WeaponItems.Tag tag = player.isDead() ? null : plugin.items().read(player.getInventory().getItemInMainHand());
        if (!settings.bossBars() || player.isDead()) {
            hide(player);
            return;
        }
        // The one in hand first, then the rest of the inventory (looked through now and then).
        List<WeaponItems.Tag> weapons = new ArrayList<>();
        if (tag != null) {
            weapons.add(tag);
        }
        List<WeaponItems.Tag> inInventory = carried.get(id);
        if (inInventory == null || now % CARRIED_EVERY == 0) {
            inInventory = plugin.tracker().carried(player);
            carried.put(id, inInventory);
        }
        for (WeaponItems.Tag other : inInventory) {
            if (tag == null || !other.id().equals(tag.id())) {
                weapons.add(other);
            }
        }
        Map<String, Shown> shown = bars.get(id);
        Set<String> wanted = new HashSet<>();
        for (WeaponItems.Tag weapon : weapons) {
            Settings.Look look = settings.look(weapon.type());
            for (Ability ability : weapon.type().actives()) {
                long active = plugin.abilities().active(player, weapon, ability, now);
                long left = plugin.abilities().cooldown(weapon, ability, now);
                if (active <= 0 && left <= 0 && !(weapon == tag && settings.barsWhenReady())) {
                    continue; // Ready: no bar (it only shows while the ability runs or recharges).
                }
                String key = weapon.id() + ":" + ability.key();
                if (!wanted.add(key)) {
                    continue;
                }
                if (shown == null) {
                    shown = new LinkedHashMap<>();
                    bars.put(id, shown);
                }
                Shown bar = shown.get(key);
                if (bar == null) {
                    bar = new Shown(ability, Bukkit.createBossBar("", look.barColor(), BarStyle.SOLID));
                    bar.bar().addPlayer(player);
                    shown.put(key, bar);
                }
                draw(player, weapon, ability, bar.bar(), look, now, active, left);
            }
        }
        if (shown != null) {
            for (Iterator<Map.Entry<String, Shown>> it = shown.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<String, Shown> entry = it.next();
                if (!wanted.contains(entry.getKey())) {
                    entry.getValue().bar().removeAll();
                    it.remove();
                }
            }
            if (shown.isEmpty()) {
                bars.remove(id);
            }
        }
    }

    private void draw(Player player, WeaponItems.Tag tag, Ability ability, BossBar bar, Settings.Look look, long now,
                      long active, long left) {
        Settings settings = plugin.settings();
        AbilitySettings ability0 = settings.ability(ability);
        String name = ability0.name();
        String color = Text.color(look.barText());
        String title;
        double progress;
        if (active > 0) {
            long length = Math.max(active, plugin.abilities().activeLength(tag, ability));
            title = Text.format(settings.message("bar-active"), "color", color, "ability", name,
                    "time", Text.countdown(active));
            progress = (double) active / length;
        } else if (left > 0) {
            long total = Math.max(left, ability0.ticks("cooldown"));
            title = Text.format(settings.message("bar-cooldown"), "color", color, "ability", name,
                    "time", Text.countdown(left));
            progress = 1.0 - (double) left / total;
        } else {
            title = Text.format(settings.message("bar-ready"), "color", color, "ability", name);
            progress = 1.0;
        }
        Long shake = shakes.get(player.getUniqueId() + ":" + ability.key());
        BarColor barColor = shake != null && now < shake ? BarColor.WHITE : look.barColor();
        if (bar.getColor() != barColor) {
            bar.setColor(barColor);
        }
        if (!title.equals(bar.getTitle())) {
            bar.setTitle(title);
        }
        bar.setProgress(Math.max(0.0, Math.min(1.0, progress)));
        if (!bar.isVisible()) {
            bar.setVisible(true);
        }
    }

    private void hide(Player player) {
        Map<String, Shown> shown = bars.remove(player.getUniqueId());
        if (shown != null) {
            shown.values().forEach(bar -> bar.bar().removeAll());
        }
    }

    public void clearAll() {
        carried.clear();
        for (Map<String, Shown> shown : bars.values()) {
            shown.values().forEach(bar -> bar.bar().removeAll());
        }
        bars.clear();
    }
}
