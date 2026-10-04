package io.github.drepfy.combat.util;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.entity.Player;

/** Text above the hotbar, on Paper and Spigot. */
public final class ActionBar {

    /** Paper ships Adventure; Spigot does not, so its classes are only touched when present. */
    private static final boolean ADVENTURE = classExists("net.kyori.adventure.text.Component");

    private ActionBar() {
    }

    /** Shows {@code §}-coloured text; an empty text clears the bar at once. */
    public static void send(Player player, String colored) {
        if (ADVENTURE) {
            try {
                Paper.send(player, colored);
                return;
            } catch (LinkageError | RuntimeException ignored) {
                // Fall back to the Spigot API below.
            }
        }
        try {
            player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(colored));
        } catch (LinkageError | RuntimeException ignored) {
            // Cosmetic only.
        }
    }

    public static void clear(Player player) {
        send(player, "");
    }

    static boolean classExists(String name) {
        try {
            Class.forName(name, false, ActionBar.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    /** Only loaded on servers with Adventure. */
    private static final class Paper {
        private static final net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer SERIALIZER =
                net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.builder().character('§')
                        .hexColors().useUnusualXRepeatedCharacterHexFormat().build();

        static void send(Player player, String text) {
            player.sendActionBar(text.isEmpty() ? net.kyori.adventure.text.Component.empty()
                    : SERIALIZER.deserialize(text));
        }
    }
}
