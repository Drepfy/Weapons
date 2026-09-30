package io.github.drepfy.vigil.util;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** Text above the hotbar. Works on Spigot and Paper. */
public final class ActionBar {

    /** The client fades a single action bar after about 2 seconds; resending keeps it up for about 5. */
    private static final long[] RESEND_TICKS = {40L, 80L};

    private ActionBar() {
    }

    /** Shows colour-coded text ({@code &c&l...}) above the player's hotbar for a few seconds. */
    public static void show(Plugin plugin, Player player, String text) {
        String colored = Text.color(text);
        if (colored.isBlank()) {
            return; // An empty message in config.yml switches it off.
        }
        send(player, colored);
        for (long delay : RESEND_TICKS) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) {
                    send(player, colored);
                }
            }, delay);
        }
    }

    private static void send(Player player, String text) {
        try {
            // Paper.
            player.sendActionBar(LegacyComponentSerializer.legacySection().deserialize(text));
            return;
        } catch (LinkageError | UnsupportedOperationException ignored) {
            // Spigot: no Adventure API.
        }
        try {
            player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(text));
        } catch (UnsupportedOperationException | LinkageError ignored) {
            // Cosmetic only; the chat message is still sent.
        }
    }
}
