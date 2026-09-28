package com.drepfy.staffvanish;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.jspecify.annotations.Nullable;

/** Teleporting for vanished staff, used by {@code /vanish tp} and the staff selector. */
public final class StaffTeleporter {

    private static final Comparator<Player> BY_NAME = Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER);

    private final VanishModule module;
    /** The last player each staff member teleported to, so next/previous can continue from there. */
    private final Map<UUID, UUID> lastTargets = new HashMap<>();

    StaffTeleporter(VanishModule module) {
        this.module = module;
    }

    /**
     * Players the staff member can teleport to, sorted by name.
     */
    public List<Player> targets(Player staff) {
        VanishManager vanish = module.manager();
        boolean showVanished = module.settings().selector().menu().showVanished();
        return Bukkit.getOnlinePlayers().stream()
                .filter(player -> !player.equals(staff))
                .filter(player -> vanish.canSee(staff, player))
                .filter(player -> showVanished || !vanish.isVanished(player))
                .map(Player.class::cast)
                .sorted(BY_NAME)
                .toList();
    }

    /**
     * Checks that the staff member may teleport right now, telling them why not otherwise.
     */
    public boolean canTeleport(Player staff) {
        if (!staff.hasPermission(VanishPermissions.TELEPORT)) {
            module.messages().send(staff, "no-permission");
            return false;
        }
        if (!module.manager().isVanished(staff)) {
            module.messages().send(staff, "must-be-vanished");
            return false;
        }
        return true;
    }

    public void teleport(Player staff, Player target) {
        if (!canTeleport(staff)) {
            return;
        }
        if (!target.isOnline() || !module.manager().canSee(staff, target)) {
            module.messages().send(staff, "player-not-found", Placeholder.unparsed("input", target.getName()));
            return;
        }
        lastTargets.put(staff.getUniqueId(), target.getUniqueId());
        staff.teleportAsync(target.getLocation(), PlayerTeleportEvent.TeleportCause.PLUGIN).thenAccept(success ->
                module.messages().send(staff, success ? "teleported" : "teleport-failed",
                        Placeholder.unparsed("target", target.getName())));
    }

    /**
     * @param step {@code 1} for the next player, {@code -1} for the previous one
     * @return the player after (or before) the last one this staff member teleported to, wrapping around
     */
    public @Nullable Player cycle(Player staff, int step) {
        List<Player> targets = targets(staff);
        if (targets.isEmpty()) {
            return null;
        }
        UUID last = lastTargets.get(staff.getUniqueId());
        int index = -1;
        for (int i = 0; i < targets.size(); i++) {
            if (targets.get(i).getUniqueId().equals(last)) {
                index = i;
                break;
            }
        }
        if (index == -1) {
            return step > 0 ? targets.getFirst() : targets.getLast();
        }
        return targets.get(Math.floorMod(index + step, targets.size()));
    }

    /**
     * @return a random player, avoiding the last one teleported to when there's a choice
     */
    public @Nullable Player random(Player staff) {
        List<Player> targets = targets(staff);
        if (targets.isEmpty()) {
            return null;
        }
        UUID last = lastTargets.get(staff.getUniqueId());
        List<Player> choices = targets.size() > 1
                ? targets.stream().filter(player -> !player.getUniqueId().equals(last)).toList()
                : targets;
        return choices.get(ThreadLocalRandom.current().nextInt(choices.size()));
    }

    public void forget(UUID staff) {
        lastTargets.remove(staff);
    }
}
