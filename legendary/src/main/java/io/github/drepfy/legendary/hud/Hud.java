package io.github.drepfy.legendary.hud;

import io.github.drepfy.legendary.Ability;
import io.github.drepfy.legendary.LegendaryPlugin;
import io.github.drepfy.legendary.WeaponType;
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

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * While a legendary is held, a boss bar for each of its abilities at the top of the screen:
 * the ability's name and how long until it can be used again, the bar filling up as it
 * recharges (and running down while an ability lasts, such as Blood Moon). The action bar is
 * left to other plugins (the Combat plugin's timer).
 */
public final class Hud {

    /** How long the bar flashes when an ability is used too early. */
    private static final int SHAKE_TICKS = 6;

    private final LegendaryPlugin plugin;
    private final Map<UUID, Bars> bars = new HashMap<>();
    /** player:ability → the tick its flash ends. */
    private final Map<String, Long> shakes = new HashMap<>();

    public Hud(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    /** One player's bars, for the weapon they are holding. */
    private static final class Bars {
        final WeaponType type;
        final Map<Ability, BossBar> byAbility = new EnumMap<>(Ability.class);

        Bars(WeaponType type) {
            this.type = type;
        }
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
        Bars current = bars.get(player.getUniqueId());
        return current == null ? Map.of() : Map.copyOf(current.byAbility);
    }

    /** Every couple of ticks. */
    public void update(long now) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            update(player, now);
        }
        for (Iterator<Map.Entry<UUID, Bars>> it = bars.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Bars> entry = it.next();
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) {
                entry.getValue().byAbility.values().forEach(BossBar::removeAll);
                it.remove();
            }
        }
        shakes.values().removeIf(until -> until <= now);
    }

    private void update(Player player, long now) {
        Settings settings = plugin.settings();
        WeaponItems.Tag tag = plugin.items().read(player.getInventory().getItemInMainHand());
        if (tag == null || !settings.bossBars() || player.isDead()) {
            hide(player);
            return;
        }
        Bars current = bars.get(player.getUniqueId());
        if (current == null || current.type != tag.type()) {
            hide(player);
            current = new Bars(tag.type());
            bars.put(player.getUniqueId(), current);
        }
        Settings.Look look = settings.look(tag.type());
        for (Ability ability : tag.type().actives()) {
            BossBar bar = current.byAbility.get(ability);
            if (bar == null) {
                bar = Bukkit.createBossBar("", look.barColor(), BarStyle.SOLID);
                bar.addPlayer(player);
                current.byAbility.put(ability, bar);
            }
            draw(player, tag, ability, bar, look, now);
        }
    }

    private void draw(Player player, WeaponItems.Tag tag, Ability ability, BossBar bar, Settings.Look look, long now) {
        Settings settings = plugin.settings();
        AbilitySettings ability0 = settings.ability(ability);
        String name = ability0.name();
        String color = Text.color(look.barText());
        long active = plugin.abilities().active(player, tag, ability, now);
        long left = plugin.abilities().cooldown(tag, ability, now);
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
        Bars current = bars.remove(player.getUniqueId());
        if (current != null) {
            current.byAbility.values().forEach(BossBar::removeAll);
        }
    }

    public void clearAll() {
        for (Bars current : bars.values()) {
            current.byAbility.values().forEach(BossBar::removeAll);
        }
        bars.clear();
    }
}
