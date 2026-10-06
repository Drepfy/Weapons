package io.github.drepfy.legendary.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * A help page in the server's style: a title between two lines, then one line per command. In
 * game a command can be clicked to type it into the chat box, and hovering over it says what it
 * does; the console gets the same page as plain lines.
 */
public final class HelpMenu {

    private static final String RULE = "&8&m" + " ".repeat(54);
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('§').hexColors().useUnusualXRepeatedCharacterHexFormat().build();

    private record Line(String text, String hover, String suggest) {
    }

    private final String title;
    private final List<Line> lines = new ArrayList<>();

    /** @param title the first line, e.g. "&c&lʟɪꜰᴇsᴛᴇᴀʟ &8| &7v1.1.0" */
    public HelpMenu(String title) {
        this.title = title;
    }

    /**
     * A command line.
     *
     * @param usage       like "/lifesteal sethearts <player> <amount>": the words before the first argument are what
     *                    a click types
     * @param description what it does
     */
    public HelpMenu command(String usage, String description) {
        String[] words = usage.trim().split(" ");
        StringBuilder fixed = new StringBuilder();
        StringBuilder args = new StringBuilder();
        for (String word : words) {
            boolean argument = !args.isEmpty() || word.startsWith("<") || word.startsWith("[") || word.contains("|");
            StringBuilder part = argument ? args : fixed;
            if (!part.isEmpty()) {
                part.append(' ');
            }
            part.append(word);
        }
        String shown = " &b" + fixed + (args.isEmpty() ? "" : " &3" + args) + " &8» &7" + description;
        String hover = "&b" + usage.trim() + "\n&7" + description + "\n\n&eClick to type it";
        lines.add(new Line(shown, hover, fixed + " "));
        return this;
    }

    /** A line of text without a command (how something works, a tip). */
    public HelpMenu note(String text) {
        lines.add(new Line(" " + text, null, null));
        return this;
    }

    public void send(CommandSender sender) {
        boolean clickable = sender instanceof Player;
        sender.sendMessage(Text.color(RULE));
        sender.sendMessage(Text.color(" " + title));
        boolean commands = false;
        for (Line line : lines) {
            commands |= line.suggest() != null;
            if (!clickable || line.suggest() == null || !sendClickable(sender, line)) {
                sender.sendMessage(Text.color(line.text()));
            }
        }
        if (clickable && commands) {
            sender.sendMessage(Text.color(" &8Click a command to type it."));
        }
        sender.sendMessage(Text.color(RULE));
    }

    private static boolean sendClickable(CommandSender sender, Line line) {
        try {
            Component text = LEGACY.deserialize(Text.color(line.text()))
                    .hoverEvent(HoverEvent.showText(LEGACY.deserialize(Text.color(line.hover()))))
                    .clickEvent(ClickEvent.suggestCommand(line.suggest()));
            sender.sendMessage(text);
            return true;
        } catch (LinkageError | RuntimeException e) {
            return false; // A server without Adventure (Spigot): plain lines instead.
        }
    }
}
