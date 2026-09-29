package com.drepfy.staffvanish.staff;

import com.drepfy.staffvanish.VanishModule;
import com.drepfy.staffvanish.staff.StaffPreferences.Option;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The cosmetic side of being vanished: boss bar, night vision, glowing (seen only by staff, because only staff can
 * see vanished players) and the title and sound when vanish is toggled.
 * <p>
 * Night vision and glowing are saved with the player by the game, so a marker in their persistent data records that
 * this plugin added them. That way they're removed after a crash or reconnect, and effects from beacons, potions or
 * other plugins are never touched.
 */
public final class VanishEffects {

    private static final Title.Times TITLE_TIMES =
            Title.Times.times(Duration.ofMillis(150), Duration.ofMillis(1200), Duration.ofMillis(350));

    private final VanishModule module;
    private final NamespacedKey nightVisionKey;
    private final NamespacedKey glowKey;
    private final Map<UUID, BossBar> bossBars = new HashMap<>();

    public VanishEffects(Plugin plugin, VanishModule module) {
        this.module = module;
        this.nightVisionKey = new NamespacedKey(plugin, "added_night_vision");
        this.glowKey = new NamespacedKey(plugin, "added_glow");
    }

    /** Brings the player's effects in line with whether they're vanished and their preferences. */
    public void refresh(Player player) {
        boolean vanished = module.manager().isVanished(player);
        StaffPreferences preferences = module.preferences();

        boolean nightVision = vanished && preferences.get(player, Option.NIGHT_VISION);
        if (nightVision) {
            if (!player.hasPotionEffect(PotionEffectType.NIGHT_VISION)) {
                player.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION,
                        PotionEffect.INFINITE_DURATION, 0, false, false, false));
                mark(player, nightVisionKey, true);
            }
        } else if (marked(player, nightVisionKey)) {
            player.removePotionEffect(PotionEffectType.NIGHT_VISION);
            mark(player, nightVisionKey, false);
        }

        boolean glow = vanished && preferences.get(player, Option.GLOW);
        if (glow) {
            if (!player.isGlowing()) {
                player.setGlowing(true);
                mark(player, glowKey, true);
            }
        } else if (marked(player, glowKey)) {
            player.setGlowing(false);
            mark(player, glowKey, false);
        }

        Component title = vanished && preferences.get(player, Option.BOSS_BAR) ? bossBarTitle(player) : null;
        BossBar bar = bossBars.get(player.getUniqueId());
        if (title == null) {
            if (bar != null) {
                player.hideBossBar(bar);
                bossBars.remove(player.getUniqueId());
            }
        } else if (bar == null) {
            bar = BossBar.bossBar(title, 1f, BossBar.Color.BLUE, BossBar.Overlay.PROGRESS);
            bossBars.put(player.getUniqueId(), bar);
            player.showBossBar(bar);
        } else {
            bar.name(title);
        }
    }

    /** Drops in-memory state for a player who is leaving. Saved effects are cleaned up by {@link #refresh} on join. */
    public void forget(Player player) {
        BossBar bar = bossBars.remove(player.getUniqueId());
        if (bar != null) {
            player.hideBossBar(bar);
        }
    }

    /** Hides every boss bar, for when the plugin is disabled. */
    public void clearAll() {
        for (Map.Entry<UUID, BossBar> entry : bossBars.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null) {
                player.hideBossBar(entry.getValue());
            }
        }
        bossBars.clear();
    }

    /** Title and sound for the player whose vanish state was just toggled. */
    public void announceToggle(Player player, boolean vanished) {
        if (module.settings().staffTools().toggleTitle()) {
            String prefix = vanished ? "title-vanished" : "title-visible";
            Component main = module.messages().get(prefix);
            Component sub = module.messages().get(prefix + "-subtitle");
            if (main != null || sub != null) {
                player.showTitle(Title.title(main != null ? main : Component.empty(),
                        sub != null ? sub : Component.empty(), TITLE_TIMES));
            }
        }
        if (module.settings().staffTools().toggleSound()) {
            player.playSound(player.getLocation(),
                    vanished ? Sound.BLOCK_BEACON_DEACTIVATE : Sound.BLOCK_BEACON_ACTIVATE, 0.6f, 1.6f);
        }
    }

    private Component bossBarTitle(Player player) {
        int hiddenFrom = 0;
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (!viewer.equals(player) && !module.manager().canSee(viewer, player)) {
                hiddenFrom++;
            }
        }
        Component title = module.messages().get("boss-bar",
                Placeholder.unparsed("hidden", Integer.toString(hiddenFrom)),
                Placeholder.unparsed("level", Integer.toString(module.manager().level(player))));
        return title != null ? title : Component.text("VANISHED");
    }

    private boolean marked(Player player, NamespacedKey key) {
        return player.getPersistentDataContainer().has(key);
    }

    private void mark(Player player, NamespacedKey key, boolean value) {
        if (value) {
            player.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        } else {
            player.getPersistentDataContainer().remove(key);
        }
    }
}
