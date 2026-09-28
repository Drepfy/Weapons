package dev.drepfy.moderation.service;

import com.destroystokyo.paper.profile.PlayerProfile;
import dev.drepfy.moderation.ModerationPlugin;
import dev.drepfy.moderation.Permissions;
import dev.drepfy.moderation.model.PlayerRecord;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * Turns what staff typed into a player. Names must match exactly (case-insensitively): partial
 * matches like "Ste" for "Steve" are deliberately not supported, so the wrong player can't be
 * punished by accident.
 */
public final class TargetResolver {

    /**
     * @param exempt whether the player has (or, if offline, last had) {@code moderation.exempt}
     */
    public record Target(UUID uniqueId, String name, boolean exempt) {
    }

    private static final Pattern JAVA_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final int MAX_INPUT = 36;

    private final ModerationPlugin plugin;

    public TargetResolver(ModerationPlugin plugin) {
        this.plugin = plugin;
    }

    /** An online player with exactly this name or UUID. Main thread only. */
    public Optional<Target> online(String input) {
        UUID id = parseUuid(input);
        Player player = id != null ? Bukkit.getPlayer(id) : Bukkit.getPlayerExact(input);
        if (player == null) {
            return Optional.empty();
        }
        return Optional.of(new Target(player.getUniqueId(), player.getName(), player.hasPermission(Permissions.EXEMPT)));
    }

    /** Online players first, then players who have joined before, then (optionally) Mojang. Call on the main thread. */
    public CompletableFuture<Optional<Target>> resolve(String input) {
        Optional<Target> online = online(input);
        if (online.isPresent()) {
            return CompletableFuture.completedFuture(online);
        }
        if (input.isEmpty() || input.length() > MAX_INPUT) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        UUID id = parseUuid(input);
        return plugin.storage()
                .submit(store -> id != null ? store.findPlayer(id) : store.findPlayerByName(input))
                .thenCompose(record -> record.isPresent()
                        ? CompletableFuture.completedFuture(record.map(TargetResolver::fromRecord))
                        : lookupUnknown(input, id));
    }

    private CompletableFuture<Optional<Target>> lookupUnknown(String input, UUID id) {
        if (!plugin.settings().allowUnknownPlayers() || (id == null && !JAVA_NAME.matcher(input).matches())) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        CompletableFuture<Optional<Target>> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                future.complete(lookupProfile(input, id));
            } catch (RuntimeException e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    // Runs off the main thread: may make a request to Mojang.
    private static Optional<Target> lookupProfile(String input, UUID id) {
        if (id == null) {
            OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(input);
            if (cached != null) {
                String name = cached.getName() != null ? cached.getName() : input;
                return Optional.of(new Target(cached.getUniqueId(), name, false));
            }
        }
        PlayerProfile profile = id != null ? Bukkit.createProfile(id) : Bukkit.createProfile(input);
        if (profile.complete(false) && profile.getId() != null) {
            return Optional.of(new Target(profile.getId(), profile.getName() != null ? profile.getName() : input, false));
        }
        // A UUID is unambiguous even when no name can be found for it.
        return id != null ? Optional.of(new Target(id, id.toString(), false)) : Optional.empty();
    }

    private static Target fromRecord(PlayerRecord record) {
        return new Target(record.uniqueId(), record.name(), record.exempt());
    }

    static UUID parseUuid(String input) {
        if (input.length() != 36) {
            return null;
        }
        try {
            return UUID.fromString(input);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
