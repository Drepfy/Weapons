package com.drepfy.staffvanish.command;

import com.drepfy.staffvanish.Messages;
import com.drepfy.staffvanish.VanishManager;
import com.drepfy.staffvanish.VanishModule;
import com.drepfy.staffvanish.VanishPermissions;
import com.drepfy.staffvanish.VanishStorage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * {@code /vanish [player]}, {@code /vanish on|off [player]}, {@code /vanish list}, {@code /vanish tp <player>},
 * {@code /vanish stick} and {@code /vanish reload}.
 */
public final class VanishCommand implements TabExecutor {

    private final VanishModule module;

    public VanishCommand(VanishModule module) {
        this.module = module;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            if (sender instanceof Player player) {
                setVanished(sender, player, !module.manager().isVanished(player));
            } else {
                messages().send(sender, "usage");
            }
            return true;
        }

        String subcommand = args[0].toLowerCase(Locale.ROOT);
        switch (subcommand) {
            case "on", "off" -> {
                Player target = args.length > 1 ? findOther(sender, args[1]) : requirePlayer(sender);
                if (target != null) {
                    setVanished(sender, target, subcommand.equals("on"));
                }
            }
            case "list" -> list(sender);
            case "tp", "teleport" -> teleport(sender, args);
            case "stick", "selector", "rod" -> giveSelector(sender);
            case "reload" -> reload(sender);
            case "help" -> messages().send(sender, "usage");
            default -> {
                Player target = findOther(sender, args[0]);
                if (target != null) {
                    setVanished(sender, target, !module.manager().isVanished(target));
                }
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> options = new ArrayList<>();
        if (args.length == 1) {
            options.add("on");
            options.add("off");
            addIfPermitted(sender, options, VanishPermissions.LIST, "list");
            addIfPermitted(sender, options, VanishPermissions.TELEPORT, "tp");
            addIfPermitted(sender, options, VanishPermissions.TELEPORT, "stick");
            addIfPermitted(sender, options, VanishPermissions.RELOAD, "reload");
            if (sender.hasPermission(VanishPermissions.OTHERS)) {
                options.addAll(visiblePlayerNames(sender));
            }
        } else if (args.length == 2) {
            String subcommand = args[0].toLowerCase(Locale.ROOT);
            boolean playerArgument = switch (subcommand) {
                case "on", "off" -> sender.hasPermission(VanishPermissions.OTHERS);
                case "tp", "teleport" -> sender.hasPermission(VanishPermissions.TELEPORT);
                default -> false;
            };
            if (playerArgument) {
                options.addAll(visiblePlayerNames(sender));
            }
        }
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        options.removeIf(option -> !option.toLowerCase(Locale.ROOT).startsWith(prefix));
        return options;
    }

    private void setVanished(CommandSender sender, Player target, boolean vanish) {
        VanishManager manager = module.manager();
        boolean self = sender.equals(target);
        TagResolver targetName = Placeholder.unparsed("target", target.getName());
        TagResolver actorName = Placeholder.unparsed("player", sender.getName());

        if (vanish) {
            if (manager.isVanished(target)) {
                messages().send(sender, "already-vanished", targetName);
                return;
            }
            if (!target.hasPermission(VanishPermissions.USE)) {
                messages().send(sender, "target-not-staff", targetName);
                return;
            }
            manager.vanish(target, sender);
            messages().send(target, "vanished");
            if (!self) {
                messages().send(sender, "vanished-other", targetName);
                messages().send(target, "vanished-by", actorName);
            }
        } else {
            if (!manager.isVanished(target)) {
                messages().send(sender, "already-visible", targetName);
                return;
            }
            manager.unvanish(target, sender);
            messages().send(target, "unvanished");
            if (!self) {
                messages().send(sender, "unvanished-other", targetName);
                messages().send(target, "unvanished-by", actorName);
            }
        }
    }

    private void list(CommandSender sender) {
        if (!checkPermission(sender, VanishPermissions.LIST)) {
            return;
        }
        Map<UUID, VanishStorage.Entry> entries = module.storage().entries();
        if (entries.isEmpty()) {
            messages().send(sender, "list-empty");
            return;
        }
        Component offlineTag = messages().get("list-offline-tag");
        List<Component> names = new ArrayList<>(entries.size());
        entries.forEach((id, entry) -> {
            Player online = Bukkit.getPlayer(id);
            Component name = Component.text(online != null ? online.getName() : entry.name());
            names.add(online == null && offlineTag != null ? name.append(offlineTag) : name);
        });
        messages().send(sender, "list",
                Placeholder.unparsed("count", Integer.toString(names.size())),
                Placeholder.component("players", Component.join(JoinConfiguration.commas(true), names)));
    }

    private void teleport(CommandSender sender, String[] args) {
        Player staff = requirePlayer(sender);
        if (staff == null || !module.teleporter().canTeleport(staff)) {
            return;
        }
        if (args.length < 2) {
            messages().send(sender, "usage");
            return;
        }
        Player target = findVisible(sender, args[1]);
        if (target != null) {
            module.teleporter().teleport(staff, target);
        }
    }

    private void giveSelector(CommandSender sender) {
        Player player = requirePlayer(sender);
        if (player == null || !module.teleporter().canTeleport(player)) {
            return;
        }
        if (!module.settings().selector().enabled()) {
            messages().send(player, "selector-disabled");
        } else if (module.selectorItem().give(player)) {
            messages().send(player, "selector-given");
        } else {
            messages().send(player, "inventory-full");
        }
    }

    private void reload(CommandSender sender) {
        if (checkPermission(sender, VanishPermissions.RELOAD)) {
            module.reload();
            messages().send(sender, "reloaded");
        }
    }

    /** Finds a player to toggle; toggling anyone but yourself needs {@link VanishPermissions#OTHERS}. */
    private @Nullable Player findOther(CommandSender sender, String name) {
        Player target = findVisible(sender, name);
        if (target != null && !sender.equals(target) && !checkPermission(sender, VanishPermissions.OTHERS)) {
            return null;
        }
        return target;
    }

    /** Finds an online player by exact name, treating players the sender can't see as offline. */
    private @Nullable Player findVisible(CommandSender sender, String name) {
        Player target = Bukkit.getPlayerExact(name);
        if (target == null || (sender instanceof Player viewer && !module.manager().canSee(viewer, target))) {
            messages().send(sender, "player-not-found", Placeholder.unparsed("input", name));
            return null;
        }
        return target;
    }

    private @Nullable Player requirePlayer(CommandSender sender) {
        if (sender instanceof Player player) {
            return player;
        }
        messages().send(sender, "players-only");
        return null;
    }

    private boolean checkPermission(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) {
            return true;
        }
        messages().send(sender, "no-permission");
        return false;
    }

    private List<String> visiblePlayerNames(CommandSender sender) {
        List<String> names = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!(sender instanceof Player viewer) || module.manager().canSee(viewer, player)) {
                names.add(player.getName());
            }
        }
        return names;
    }

    private static void addIfPermitted(CommandSender sender, List<String> options, String permission, String option) {
        if (sender.hasPermission(permission)) {
            options.add(option);
        }
    }

    private Messages messages() {
        return module.messages();
    }
}
