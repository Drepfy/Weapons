package io.github.drepfy.vigil.util;

import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** Chat lines that run or suggest a command when clicked. Plain text where that is not supported. */
public final class Chat {

    /**
     * A piece of a chat line.
     *
     * @param colored text with {@code §} colours
     * @param hover   tooltip ({@code &} colours), or {@code null}
     * @param command command for a click, or {@code null}
     * @param suggest put the command into the chat box instead of running it
     */
    public record Part(String colored, String hover, String command, boolean suggest) {
        public static Part text(String colored) {
            return new Part(colored, null, null, false);
        }

        public static Part run(String colored, String hover, String command) {
            return new Part(colored, hover, command, false);
        }

        public static Part suggest(String colored, String hover, String command) {
            return new Part(colored, hover, command, true);
        }
    }

    private Chat() {
    }

    /** Clicking runs {@code command}. */
    public static void run(CommandSender to, String colored, String hover, String command) {
        line(to, Part.run(colored, hover, command));
    }

    /** Clicking puts {@code command} into the chat box. */
    public static void suggest(CommandSender to, String colored, String hover, String command) {
        line(to, Part.suggest(colored, hover, command));
    }

    /** One chat line made of parts. */
    public static void line(CommandSender to, Part... parts) {
        if (to instanceof Player player) {
            try {
                List<BaseComponent> components = new ArrayList<>();
                for (Part part : parts) {
                    TextComponent component = new TextComponent(TextComponent.fromLegacyText(part.colored()));
                    if (part.hover() != null) {
                        component.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                new net.md_5.bungee.api.chat.hover.content.Text(TextComponent.fromLegacyText(
                                        Text.color(part.hover())))));
                    }
                    if (part.command() != null) {
                        component.setClickEvent(new ClickEvent(part.suggest() ? ClickEvent.Action.SUGGEST_COMMAND
                                : ClickEvent.Action.RUN_COMMAND, part.command()));
                    }
                    components.add(component);
                }
                player.spigot().sendMessage(components.toArray(new BaseComponent[0]));
                return;
            } catch (LinkageError | RuntimeException ignored) {
                // No chat components on this server: plain text below.
            }
        }
        StringBuilder plain = new StringBuilder();
        for (Part part : parts) {
            plain.append(part.colored());
        }
        to.sendMessage(plain.toString());
    }
}
