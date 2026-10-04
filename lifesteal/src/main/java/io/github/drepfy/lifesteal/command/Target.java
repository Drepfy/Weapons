package io.github.drepfy.lifesteal.command;

import io.github.drepfy.lifesteal.data.LifestealStore;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.UUID;

/** A player named in a command: online, or known from an earlier visit (no web lookups). */
record Target(UUID uuid, String name, Player online) {

    static Target find(LifestealStore store, String input) {
        Player online = Bukkit.getPlayerExact(input);
        if (online != null) {
            return new Target(online.getUniqueId(), online.getName(), online);
        }
        UUID known = store.findByName(input);
        if (known == null) {
            try {
                known = UUID.fromString(input);
            } catch (IllegalArgumentException e) {
                return null;
            }
            if (store.entry(known) == null) {
                return null;
            }
        }
        LifestealStore.Entry entry = store.entry(known);
        return new Target(known, entry.name() != null ? entry.name() : input, Bukkit.getPlayer(known));
    }
}
