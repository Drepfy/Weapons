package io.github.drepfy.vigil.violation;

import io.github.drepfy.vigil.api.CheckType;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Streams check diagnostics to staff who asked for them with /ac debug.
 * Message text is only built when someone is listening.
 */
public final class DebugService {

    /** target → (viewer → check filter, null = all checks). */
    private final Map<UUID, Map<UUID, CheckType>> viewers = new HashMap<>();
    private final Logger logger;
    private final BooleanSupplier consoleDebug;

    public DebugService(Logger logger, BooleanSupplier consoleDebug) {
        this.logger = logger;
        this.consoleDebug = consoleDebug;
    }

    /** Toggles a subscription and returns whether the viewer is now subscribed. */
    public boolean toggle(UUID viewer, UUID target, CheckType filter) {
        Map<UUID, CheckType> subscribers = viewers.computeIfAbsent(target, key -> new HashMap<>());
        if (subscribers.containsKey(viewer) && subscribers.get(viewer) == filter) {
            subscribers.remove(viewer);
            if (subscribers.isEmpty()) {
                viewers.remove(target);
            }
            return false;
        }
        subscribers.put(viewer, filter);
        return true;
    }

    public void removeViewer(UUID viewer) {
        Iterator<Map<UUID, CheckType>> iterator = viewers.values().iterator();
        while (iterator.hasNext()) {
            Map<UUID, CheckType> subscribers = iterator.next();
            subscribers.remove(viewer);
            if (subscribers.isEmpty()) {
                iterator.remove();
            }
        }
    }

    public boolean isWatched(UUID target) {
        return viewers.containsKey(target) || consoleDebug.getAsBoolean();
    }

    public void debug(Player target, CheckType type, Supplier<String> message) {
        Map<UUID, CheckType> subscribers = viewers.get(target.getUniqueId());
        boolean console = consoleDebug.getAsBoolean();
        if (subscribers == null && !console) {
            return;
        }
        String text = message.get();
        if (console) {
            logger.info("[debug] " + target.getName() + " " + type.displayName() + ": " + text);
        }
        if (subscribers == null) {
            return;
        }
        String line = ChatColor.DARK_GRAY + "[" + ChatColor.GOLD + "dbg" + ChatColor.DARK_GRAY + "] "
                + ChatColor.GRAY + target.getName() + " " + ChatColor.YELLOW + type.displayName()
                + ChatColor.DARK_GRAY + ": " + ChatColor.WHITE + text;
        for (Map.Entry<UUID, CheckType> entry : subscribers.entrySet()) {
            if (entry.getValue() != null && entry.getValue() != type) {
                continue;
            }
            Player viewer = Bukkit.getPlayer(entry.getKey());
            if (viewer != null) {
                viewer.sendMessage(line);
            }
        }
    }
}
