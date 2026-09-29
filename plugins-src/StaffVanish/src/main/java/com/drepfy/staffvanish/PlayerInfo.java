package com.drepfy.staffvanish;

import java.util.Locale;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/** Placeholders describing a player, for the selector menu and the inspect message. */
public final class PlayerInfo {

    private PlayerInfo() {
    }

    /**
     * Provides {@code <world> <x> <y> <z> <health> <food> <gamemode> <ping>}.
     */
    public static TagResolver placeholders(Player player) {
        Location location = player.getLocation();
        String gameMode = player.getGameMode().name().toLowerCase(Locale.ROOT);
        return TagResolver.resolver(
                Placeholder.unparsed("world", player.getWorld().getName()),
                Placeholder.unparsed("x", Integer.toString(location.getBlockX())),
                Placeholder.unparsed("y", Integer.toString(location.getBlockY())),
                Placeholder.unparsed("z", Integer.toString(location.getBlockZ())),
                Placeholder.unparsed("health", String.format(Locale.ROOT, "%.1f", player.getHealth())),
                Placeholder.unparsed("food", Integer.toString(player.getFoodLevel())),
                Placeholder.unparsed("gamemode", Character.toUpperCase(gameMode.charAt(0)) + gameMode.substring(1)),
                Placeholder.unparsed("ping", Integer.toString(player.getPing())));
    }
}
