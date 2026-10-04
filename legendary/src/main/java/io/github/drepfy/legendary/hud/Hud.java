package io.github.drepfy.legendary.hud;

import io.github.drepfy.legendary.Ability;
import io.github.drepfy.legendary.LegendaryPlugin;
import io.github.drepfy.legendary.config.Settings;
import io.github.drepfy.legendary.item.WeaponItems;
import io.github.drepfy.legendary.util.ActionBar;
import io.github.drepfy.legendary.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The cooldowns above the hotbar while a legendary is held:
 * {@code Crescent Draw 4.2s | Edge ■■□□}. When the Combat plugin is installed, its combat
 * timer is shown in front, and Combat steps back while this bar is up (they would otherwise
 * keep replacing each other).
 */
public final class Hud {

    /**
     * Player metadata other plugins can check: while its value (epoch milliseconds) is in the
     * future, another plugin is drawing the action bar.
     */
    public static final String CLAIM = "vanillasmp:actionbar";
    private static final long CLAIM_MS = 1500;
    private static final int FLASH_TICKS = 30;

    private final LegendaryPlugin plugin;
    private final Map<UUID, Flash> flashes = new HashMap<>();
    private final Set<UUID> showing = new HashSet<>();
    /** weapon:ability → the tick it was last seen cooling down (for the ready chime). */
    private final Map<String, Long> cooling = new HashMap<>();
    private Plugin combat;
    private Method combatRemaining;

    public Hud(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    private record Flash(String text, long until) {
    }

    /** Shows a short message in place of the cooldowns (if the player is holding a legendary) or in chat. */
    public void flash(Player player, String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        if (plugin.settings().actionBar() && plugin.items().isLegendary(player.getInventory().getItemInMainHand())) {
            flashes.put(player.getUniqueId(), new Flash(text, plugin.tick() + FLASH_TICKS));
            update(player, plugin.tick());
        } else {
            player.sendMessage(text);
        }
    }

    /** Every few ticks. */
    public void update(long now) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            update(player, now);
        }
        showing.removeIf(uuid -> Bukkit.getPlayer(uuid) == null);
        flashes.keySet().removeIf(uuid -> Bukkit.getPlayer(uuid) == null);
    }

    private void update(Player player, long now) {
        Settings settings = plugin.settings();
        WeaponItems.Tag tag = plugin.items().read(player.getInventory().getItemInMainHand());
        if (tag == null || !settings.actionBar() || player.isDead()) {
            if (showing.remove(player.getUniqueId())) {
                release(player);
            }
            return;
        }
        List<String> parts = new ArrayList<>();
        long combatMs = combatRemaining(player);
        if (combatMs > 0) {
            parts.add(combatText(combatMs));
        }
        Flash flash = flashes.get(player.getUniqueId());
        if (flash != null && now < flash.until()) {
            parts.add(flash.text());
        } else {
            flashes.remove(player.getUniqueId());
            for (Ability ability : tag.type().actives()) {
                parts.add(status(player, tag, ability, now));
            }
            plugin.abilities().hudParts(player, tag, parts, now);
        }
        ActionBar.send(player, String.join(Text.color(settings.message("hud-separator")), parts));
        player.setMetadata(CLAIM, new FixedMetadataValue(plugin, System.currentTimeMillis() + CLAIM_MS));
        showing.add(player.getUniqueId());
    }

    private String status(Player player, WeaponItems.Tag tag, Ability ability, long now) {
        Settings settings = plugin.settings();
        String name = settings.ability(ability).name();
        String key = tag.id() + ":" + ability.key();
        long active = plugin.abilities().active(player, tag, ability, now);
        if (active > 0) {
            return Text.format(settings.message("hud-active"), "ability", name, "time", Text.countdown(active));
        }
        long left = plugin.abilities().cooldown(tag, ability, now);
        if (left > 0) {
            cooling.put(key, now);
            return Text.format(settings.message("hud-cooldown"), "ability", name, "time", Text.countdown(left));
        }
        Long seen = cooling.remove(key);
        // Only when it was cooling a moment ago, not on switching back to the weapon later.
        if (seen != null && now - seen <= 8 && settings.readySound()) {
            plugin.fx().soundTo(player, "ready");
        }
        return Text.format(settings.message("hud-ready"), "ability", name);
    }

    /** Stops drawing for this player; Combat takes the bar back at once. */
    private void release(Player player) {
        player.removeMetadata(CLAIM, plugin);
        if (combatRemaining(player) <= 0) {
            ActionBar.clear(player);
        }
    }

    public void clearAll() {
        for (UUID uuid : showing) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                release(player);
            }
        }
        showing.clear();
    }

    // ---- the Combat plugin (optional) ----------------------------------------------------------------------

    private long combatRemaining(Player player) {
        Plugin found = Bukkit.getPluginManager().getPlugin("Combat");
        if (found == null || !found.isEnabled()) {
            return 0;
        }
        try {
            if (found != combat) {
                combat = found;
                combatRemaining = found.getClass().getMethod("combatRemaining", Player.class);
            }
            Object value = combatRemaining.invoke(found, player);
            return value instanceof Long millis ? millis : 0;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return 0;
        }
    }

    private String combatText(long millis) {
        String format = combat == null ? null : combat.getConfig().getString("messages.action-bar");
        if (format == null || format.isEmpty()) {
            format = "&c⚔ Combat: &f{seconds}s";
        }
        return Text.format(format, "seconds", (millis + 999) / 1000);
    }
}
