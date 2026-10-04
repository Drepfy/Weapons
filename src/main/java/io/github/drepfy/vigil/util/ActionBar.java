package io.github.drepfy.vigil.util;

import io.github.drepfy.vigil.compat.ServerCompat;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** Text above the hotbar. Works on Spigot and Paper. */
public final class ActionBar {

    /** The client fades a single action bar after about 2 seconds; resending keeps it up for about 5. */
    private static final long[] RESEND_TICKS = {40L, 80L};
    /** Paper ships Adventure; Spigot does not, so its classes may only be touched when present. */
    private static final boolean ADVENTURE =
            ServerCompat.classExists("net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer");

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
        if (ADVENTURE) {
            try {
                Paper.send(player, text);
                return;
            } catch (LinkageError | RuntimeException ignored) {
                // Fall back to the Spigot API below.
            }
        }
        try {
            player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(text));
        } catch (LinkageError | RuntimeException ignored) {
            // Cosmetic only; the chat message is still sent.
        }
    }

    /** Only loaded on servers with Adventure, so Spigot never has to resolve these classes. */
    private static final class Paper {
        /** Reads {@code §} codes including hex colours ({@code §x§r§r§g§g§b§b}, used by gradients). */
        private static final net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer SERIALIZER =
                net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.builder().character('§')
                        .hexColors().useUnusualXRepeatedCharacterHexFormat().build();

        static void send(Player player, String text) {
            player.sendActionBar(SERIALIZER.deserialize(text));
        }
    }
}
