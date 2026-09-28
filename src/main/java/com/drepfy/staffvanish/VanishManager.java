package com.drepfy.staffvanish;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

/**
 * Owns vanish state: who is vanished, who can see them, and everything that changes while a player is vanished.
 * <p>
 * Visibility uses Bukkit's per-viewer {@link Player#hidePlayer(Plugin, Player)}, which also takes vanished players
 * out of the tab list, entity tracking, sounds and command suggestions of players who can't see them.
 */
public final class VanishManager {

    /** Metadata other plugins (EssentialsX, DiscordSRV, TAB, ...) check to tell whether a player is vanished. */
    public static final String METADATA_KEY = "vanished";

    private static final double FORGET_TARGET_RADIUS = 64;

    public enum JoinOutcome {
        VISIBLE,
        VANISHED,
        /** The player was vanished when they left, but may no longer vanish, so they are visible again. */
        PERMISSION_REVOKED
    }

    private final VanishModule module;
    private final Plugin plugin;
    private final VanishStorage storage;
    private final FallProtection fallProtection;
    /** Vanished players who are online. Concurrent because chat and server list pings are handled off-thread. */
    private final Set<UUID> online = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Session> sessions = new HashMap<>();

    /**
     * What vanishing changed on an online player, so it can be put back when they reappear. None of these values
     * survive a reconnect, so they aren't persisted.
     */
    private static final class Session {
        private final boolean collidable;
        private final boolean sleepingIgnored;
        private final boolean affectsSpawning;
        private final Component listName;
        private boolean listNameChanged;

        private Session(Player player) {
            this.collidable = player.isCollidable();
            this.sleepingIgnored = player.isSleepingIgnored();
            this.affectsSpawning = player.getAffectsSpawning();
            this.listName = player.playerListName();
        }

        private @Nullable Component originalListName(Player player) {
            // An unset list name reads back as the plain name; restore it as unset so later display changes apply.
            return listName.equals(Component.text(player.getName())) ? null : listName;
        }
    }

    VanishManager(VanishModule module, Plugin plugin, VanishStorage storage, FallProtection fallProtection) {
        this.module = module;
        this.plugin = plugin;
        this.storage = storage;
        this.fallProtection = fallProtection;
    }

    public boolean isVanished(Player player) {
        return online.contains(player.getUniqueId());
    }

    /**
     * Whether an online player is vanished. Safe to call from any thread.
     */
    public boolean isVanished(UUID id) {
        return online.contains(id);
    }

    /**
     * Whether a player is vanished, whether or not they are online. Safe to call from any thread.
     */
    public boolean isMarkedVanished(UUID id) {
        return storage.contains(id);
    }

    /** A live, thread-safe view of the vanished players who are online. */
    public Set<UUID> vanishedOnline() {
        return Collections.unmodifiableSet(online);
    }

    public List<Player> vanishedPlayers() {
        List<Player> players = new ArrayList<>(online.size());
        for (UUID id : online) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                players.add(player);
            }
        }
        return players;
    }

    public boolean canSee(Player viewer, Player target) {
        return viewer.getUniqueId().equals(target.getUniqueId())
                || !isVanished(target)
                || viewer.hasPermission(VanishPermissions.SEE);
    }

    /**
     * @param actor who caused the change; they get their own feedback, so they're left out of staff notifications
     * @return {@code false} if the player was already vanished
     */
    public boolean vanish(Player player, @Nullable CommandSender actor) {
        if (isVanished(player)) {
            return false;
        }
        storage.put(player.getUniqueId(), new VanishStorage.Entry(player.getName(), hasOwnFlight(player)));
        apply(player);
        if (module.settings().fakeJoinLeaveMessages()) {
            sendToUnprivileged(player,
                    Component.translatable("multiplayer.player.left", NamedTextColor.YELLOW, player.displayName()));
        }
        notifyStaff("staff-vanished", player, actor);
        return true;
    }

    /**
     * @param actor who caused the change; they get their own feedback, so they're left out of staff notifications
     * @return {@code false} if the player wasn't vanished
     */
    public boolean unvanish(Player player, @Nullable CommandSender actor) {
        if (!isVanished(player)) {
            return false;
        }
        VanishStorage.Entry entry = storage.remove(player.getUniqueId());
        reveal(player);
        restoreFlight(player, entry != null && entry.restoreFlight());
        if (module.settings().fakeJoinLeaveMessages()) {
            sendToUnprivileged(player,
                    Component.translatable("multiplayer.player.joined", NamedTextColor.YELLOW, player.displayName()));
        }
        notifyStaff("staff-unvanished", player, actor);
        return true;
    }

    /**
     * Sets up a joining player. Runs during {@code PlayerJoinEvent}, before the server announces the player in other
     * players' tab lists and before their entity is tracked, so nothing leaks.
     */
    public JoinOutcome handleJoin(Player player) {
        for (Player vanished : vanishedPlayers()) {
            if (!vanished.equals(player)) {
                updateVisibility(player, vanished);
            }
        }
        return restore(player);
    }

    /**
     * Applies a player's saved vanish state. Used when they join and when the plugin is enabled with players online.
     */
    public JoinOutcome restore(Player player) {
        UUID id = player.getUniqueId();
        VanishStorage.Entry entry = storage.get(id);
        if (entry != null && player.hasPermission(VanishPermissions.USE)) {
            if (!entry.name().equals(player.getName())) {
                storage.put(id, new VanishStorage.Entry(player.getName(), entry.restoreFlight()));
            }
            apply(player);
            return JoinOutcome.VANISHED;
        }

        // Visible: clear anything left from an earlier session. Viewers remember hidden players by UUID, so players
        // who stayed online may still hide this one, and a crash can leave a selector in the inventory.
        setVanishedMetadata(player, false);
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (!other.equals(player)) {
                other.showPlayer(plugin, player);
            }
        }
        module.selectorItem().strip(player);
        if (entry == null) {
            return JoinOutcome.VISIBLE;
        }
        storage.remove(id);
        restoreFlight(player, entry.restoreFlight());
        return JoinOutcome.PERMISSION_REVOKED;
    }

    /**
     * Forgets session state for a player who is leaving. Their saved vanish state is kept, so they come back vanished.
     * The metadata is kept too, so other plugins' quit listeners still see them as vanished.
     *
     * @return whether the player was vanished
     */
    public boolean handleQuit(Player player) {
        UUID id = player.getUniqueId();
        sessions.remove(id);
        fallProtection.clear(id);
        return online.remove(id);
    }

    /**
     * Re-checks permissions: vanished players who lost permission reappear, and viewers whose permission changed start
     * or stop seeing vanished players. Also refreshes the action bar reminder.
     */
    public void refresh() {
        Component actionBar = module.settings().actionBarReminder() ? module.messages().get("action-bar") : null;
        for (Player vanished : vanishedPlayers()) {
            if (!vanished.hasPermission(VanishPermissions.USE)) {
                unvanish(vanished, null);
                module.messages().send(vanished, "permission-revoked");
                continue;
            }
            if (actionBar != null) {
                vanished.sendActionBar(actionBar);
            }
            for (Player other : Bukkit.getOnlinePlayers()) {
                if (!other.equals(vanished)) {
                    updateVisibility(other, vanished);
                }
            }
        }
    }

    /** Brings vanished players in line with freshly reloaded settings. */
    public void reapplySettings() {
        for (Player player : vanishedPlayers()) {
            Session session = sessions.get(player.getUniqueId());
            if (session != null) {
                applyListName(player, session);
            }
            // Turning flight off only affects players who vanish from now on; nobody drops out of the sky.
            if (module.settings().flight()) {
                player.setAllowFlight(true);
            }
            if (!equipSelector(player)) {
                module.selectorItem().strip(player);
            }
        }
    }

    /**
     * Makes vanished players visible without forgetting that they're vanished, for when the plugin is disabled while
     * the server keeps running. Their flight is left alone so nobody drops out of the sky, and {@link #restore} hides
     * them again once the plugin is back.
     */
    public void suspendAll() {
        for (Player player : vanishedPlayers()) {
            reveal(player);
        }
    }

    /** Gives flight back to a vanished player after something reset it, such as a game mode change or respawn. */
    public void reapplyFlight(Player player, boolean wasFlying) {
        if (!isVanished(player) || !module.settings().flight()) {
            return;
        }
        player.setAllowFlight(true);
        if (wasFlying) {
            player.setFlying(true);
        }
    }

    /**
     * Gives the player the staff selector, if it's enabled and they may teleport.
     *
     * @return whether the player may have the selector
     */
    public boolean equipSelector(Player player) {
        if (!module.settings().selector().enabled() || !player.hasPermission(VanishPermissions.TELEPORT)) {
            return false;
        }
        if (!module.selectorItem().give(player)) {
            module.messages().send(player, "inventory-full");
        }
        return true;
    }

    public void updateVisibility(Player viewer, Player target) {
        if (canSee(viewer, target)) {
            viewer.showPlayer(plugin, target);
        } else {
            viewer.hidePlayer(plugin, target);
        }
    }

    /**
     * Tells staff who can see vanished players (and the console) about {@code subject}.
     *
     * @param exclude a sender who already got their own feedback
     */
    public void notifyStaff(String key, Player subject, @Nullable CommandSender exclude) {
        Component message = module.messages().get(key, Placeholder.unparsed("player", subject.getName()));
        if (message == null) {
            return;
        }
        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (!staff.equals(subject) && !staff.equals(exclude) && staff.hasPermission(VanishPermissions.SEE)) {
                staff.sendMessage(message);
            }
        }
        if (!(exclude instanceof ConsoleCommandSender)) {
            Bukkit.getConsoleSender().sendMessage(message);
        }
    }

    private void apply(Player player) {
        UUID id = player.getUniqueId();
        online.add(id);
        setVanishedMetadata(player, true);

        Session session = new Session(player);
        sessions.put(id, session);
        player.setCollidable(false);
        player.setSleepingIgnored(true);
        player.setAffectsSpawning(false);
        applyListName(player, session);
        if (module.settings().flight()) {
            player.setAllowFlight(true);
        }

        for (Player other : Bukkit.getOnlinePlayers()) {
            if (!other.equals(player)) {
                updateVisibility(other, player);
            }
        }
        if (module.settings().protection().noMobTargeting()) {
            forgetAsTarget(player);
        }
        equipSelector(player);
    }

    /** Undoes {@link #apply} except for flight, which depends on why the player is becoming visible. */
    private void reveal(Player player) {
        UUID id = player.getUniqueId();
        online.remove(id);
        setVanishedMetadata(player, false);

        Session session = sessions.remove(id);
        if (session != null) {
            player.setCollidable(session.collidable);
            player.setSleepingIgnored(session.sleepingIgnored);
            player.setAffectsSpawning(session.affectsSpawning);
            if (session.listNameChanged) {
                player.playerListName(session.originalListName(player));
            }
        }

        for (Player other : Bukkit.getOnlinePlayers()) {
            if (!other.equals(player)) {
                other.showPlayer(plugin, player);
            }
        }
        module.selectorItem().strip(player);
    }

    private void applyListName(Player player, Session session) {
        String format = module.settings().tabListFormat();
        if (format.isEmpty()) {
            if (session.listNameChanged) {
                player.playerListName(session.originalListName(player));
                session.listNameChanged = false;
            }
            return;
        }
        player.playerListName(module.messages().parse(format, Placeholder.component("name", session.listName)));
        session.listNameChanged = true;
    }

    // Paper deprecates the metadata API, but the "vanished" metadata is still how other plugins detect vanish.
    @SuppressWarnings("deprecation")
    private void setVanishedMetadata(Player player, boolean vanished) {
        if (vanished) {
            player.setMetadata(METADATA_KEY, new FixedMetadataValue(plugin, true));
        } else {
            player.removeMetadata(METADATA_KEY, plugin);
        }
    }

    private void restoreFlight(Player player, boolean hadFlight) {
        if (hadFlight || grantsFlight(player.getGameMode()) || !player.getAllowFlight()) {
            return;
        }
        if (player.isFlying()) {
            fallProtection.protect(player);
        }
        player.setAllowFlight(false);
    }

    private void sendToUnprivileged(Player subject, Component message) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!player.equals(subject) && !player.hasPermission(VanishPermissions.SEE)) {
                player.sendMessage(message);
            }
        }
    }

    /** Whether the player can fly for a reason other than their game mode, e.g. another plugin's /fly. */
    private static boolean hasOwnFlight(Player player) {
        return player.getAllowFlight() && !grantsFlight(player.getGameMode());
    }

    private static boolean grantsFlight(GameMode mode) {
        return mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR;
    }

    private static void forgetAsTarget(Player player) {
        for (Entity entity : player.getNearbyEntities(FORGET_TARGET_RADIUS, FORGET_TARGET_RADIUS, FORGET_TARGET_RADIUS)) {
            if (entity instanceof Mob mob && player.equals(mob.getTarget())) {
                mob.setTarget(null);
            }
        }
    }
}
