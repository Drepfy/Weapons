package com.drepfy.staffvanish.command;

import com.drepfy.staffvanish.VanishModule;
import com.drepfy.staffvanish.VanishPermissions;
import com.drepfy.staffvanish.staff.InventorySpy;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** {@code /invsee <player>} and {@code /endersee <player>}. */
public final class SpyCommand implements TabExecutor {

    private final VanishModule module;

    public SpyCommand(VanishModule module) {
        this.module = module;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player staff)) {
            module.messages().send(sender, "players-only");
            return true;
        }
        boolean ender = command.getName().equalsIgnoreCase("endersee");
        if (!staff.hasPermission(ender ? VanishPermissions.ENDERSEE : VanishPermissions.INVSEE)) {
            module.messages().send(staff, "no-permission");
            return true;
        }
        if (args.length < 1) {
            module.messages().send(staff, ender ? "endersee-usage" : "invsee-usage");
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null || !module.manager().canSee(staff, target)) {
            module.messages().send(staff, "player-not-found", Placeholder.unparsed("input", args[0]));
            return true;
        }
        module.spy().open(staff, target, ender ? InventorySpy.Kind.ENDER_CHEST : InventorySpy.Kind.INVENTORY);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> names = new ArrayList<>();
        if (args.length != 1) {
            return names;
        }
        String prefix = args[0].toLowerCase(Locale.ROOT);
        for (Player player : Bukkit.getOnlinePlayers()) {
            if ((!(sender instanceof Player viewer) || module.manager().canSee(viewer, player))
                    && player.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                names.add(player.getName());
            }
        }
        return names;
    }
}
