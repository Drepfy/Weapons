package io.github.drepfy.vigil.ticket;

import io.github.drepfy.vigil.config.Settings;
import io.github.drepfy.vigil.moderation.ModerationCommand;
import io.github.drepfy.vigil.util.Chat;
import io.github.drepfy.vigil.util.Clock;
import io.github.drepfy.vigil.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Tickets in game, and the in-game side of every ticket change:
 * <pre>
 * /ticket &lt;message&gt;               open a ticket, or add to your open ticket
 * /ticket reply [id] &lt;message&gt;    /ticket view [id]    /ticket close [id]    /ticket list
 * /report &lt;player&gt; &lt;reason&gt;       report a player (a ticket of its own)
 * /tickets                          staff: open tickets
 * /tickets view|reply|claim|close|tp &lt;id&gt; ...
 * </pre>
 * Staff (vigil.tickets) hear about new tickets and messages; players hear about replies,
 * also after they log in again.
 */
public final class TicketCommand implements CommandExecutor, TabCompleter, Listener, TicketService.Observer {

    public static final String STAFF_PERMISSION = "vigil.tickets";
    /** Open tickets one player may have at once. */
    public static final int MAX_OPEN_PER_PLAYER = 3;
    private static final long MESSAGE_COOLDOWN_MS = 2_000L;
    private static final long REPORT_COOLDOWN_MS = 60_000L;
    private static final int VIEW_MESSAGES = 12;
    /** Longest message preview in staff notifications. */
    private static final int PREVIEW = 200;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd MMM HH:mm", Locale.ENGLISH)
            .withZone(ZoneId.systemDefault());

    private final Plugin plugin;
    private final Supplier<Settings> settings;
    private final TicketService service;
    private final Map<UUID, Long> lastMessage = new HashMap<>();
    private final Map<UUID, Long> lastReport = new HashMap<>();

    public TicketCommand(Plugin plugin, Supplier<Settings> settings, TicketService service) {
        this.plugin = plugin;
        this.settings = settings;
        this.service = service;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        String permission = switch (name) {
            case "tickets" -> STAFF_PERMISSION;
            case "report" -> "vigil.report";
            default -> "vigil.ticket";
        };
        if (!sender.hasPermission(permission)) {
            send(sender, message("no-permission"));
            return true;
        }
        Settings.Tickets config = settings.get().tickets();
        if (!config.enabled() || (name.equals("report") && !config.reports())) {
            send(sender, message("tickets-disabled"));
            return true;
        }
        switch (name) {
            case "tickets" -> staff(sender, args);
            case "report" -> report(sender, args);
            default -> player(sender, args);
        }
        return true;
    }

    // ---- /ticket ---------------------------------------------------------------------------------------

    private void player(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            send(sender, "&cOnly players can open tickets. Staff use /tickets.");
            return;
        }
        List<Ticket> open = service.openOf(player.getUniqueId());
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        switch (sub) {
            case "" -> {
                if (open.size() == 1) {
                    view(player, open.get(0), false);
                } else if (open.isEmpty()) {
                    send(player, message("ticket-usage"));
                } else {
                    listOwn(player, open);
                }
            }
            case "list" -> {
                if (open.isEmpty()) {
                    send(player, message("ticket-none"));
                } else {
                    listOwn(player, open);
                }
            }
            case "view" -> {
                Ticket ticket = ownTicket(player, args, open, true);
                if (ticket != null) {
                    view(player, ticket, false);
                    service.markRead(ticket);
                }
            }
            case "close" -> {
                Ticket ticket = ownTicket(player, args, open, false);
                if (ticket != null) {
                    service.close(ticket, player.getName(), "Closed by the player");
                    send(player, Text.replace(message("ticket-closed"), "id", ticket.id()));
                }
            }
            case "reply" -> {
                // "/ticket reply 3 hi" answers ticket 3; "/ticket reply 2 people griefed me" is just text.
                Integer id = args.length > 2 ? parseId(args[1]) : null;
                Ticket chosen = null;
                for (Ticket candidate : open) {
                    if (id != null && candidate.id() == id) {
                        chosen = candidate;
                    }
                }
                String text = join(args, chosen != null ? 2 : 1);
                if (text.isEmpty()) {
                    send(player, "&cUsage: /ticket reply [id] <message>");
                    return;
                }
                Ticket ticket;
                if (chosen != null) {
                    ticket = chosen;
                } else if (open.size() == 1) {
                    ticket = open.get(0);
                } else if (open.isEmpty()) {
                    send(player, message("ticket-none"));
                    return;
                } else {
                    send(player, Text.replace(message("ticket-choose"), "id", open.get(open.size() - 1).id()));
                    return;
                }
                addMessage(player, ticket, text);
            }
            case "new" -> openTicket(player, open, join(args, 1));
            default -> {
                String text = join(args, 0);
                if (open.isEmpty()) {
                    openTicket(player, open, text);
                } else if (open.size() == 1) {
                    addMessage(player, open.get(0), text);
                } else {
                    send(player, Text.replace(message("ticket-choose"), "id", open.get(open.size() - 1).id()));
                }
            }
        }
    }

    private void openTicket(Player player, List<Ticket> open, String text) {
        if (text.isEmpty()) {
            send(player, message("ticket-usage"));
            return;
        }
        if (open.size() >= MAX_OPEN_PER_PLAYER) {
            send(player, Text.replace(message("ticket-too-many"), "count", open.size()));
            return;
        }
        if (onCooldown(player, lastMessage, MESSAGE_COOLDOWN_MS)) {
            return;
        }
        Ticket ticket = service.open(TicketCategory.SUPPORT, player.getUniqueId(), player.getName(), null, null, null,
                location(player), text, TicketMessage.Origin.GAME);
        send(player, Text.replace(message("ticket-opened"), "id", ticket.id()));
    }

    private void addMessage(Player player, Ticket ticket, String text) {
        if (onCooldown(player, lastMessage, MESSAGE_COOLDOWN_MS)) {
            return;
        }
        if (!service.reply(ticket, player.getName(), false, TicketMessage.Origin.GAME, text)) {
            send(player, message(ticket.isOpen() ? "ticket-full" : "ticket-none"));
            return;
        }
        send(player, Text.replace(message("ticket-added"), "id", ticket.id()));
    }

    /** The player's own ticket from {@code args[1]}, or their only open ticket. */
    private Ticket ownTicket(Player player, String[] args, List<Ticket> open, boolean closedToo) {
        if (args.length > 1) {
            Integer id = parseId(args[1]);
            Ticket ticket = id != null ? service.get(id) : null;
            if (ticket == null || !player.getUniqueId().equals(ticket.creatorUuid())
                    || (!closedToo && !ticket.isOpen())) {
                send(player, Text.replace(message("ticket-not-found"), "id", args[1]));
                return null;
            }
            return ticket;
        }
        if (open.size() == 1) {
            return open.get(0);
        }
        if (open.isEmpty()) {
            List<Ticket> unread = service.unreadOf(player.getUniqueId());
            if (closedToo && !unread.isEmpty()) {
                return unread.get(unread.size() - 1);
            }
            send(player, message("ticket-none"));
        } else {
            listOwn(player, open);
        }
        return null;
    }

    private void listOwn(Player player, List<Ticket> open) {
        send(player, Text.replace(message("tickets-header"), "count", open.size()));
        for (Ticket ticket : open) {
            Chat.run(player, Text.colorWith("  &b#{id} &7{category} &8- &7{count} message(s), last {ago}",
                            "id", ticket.id(), "category", ticket.category().display(),
                            "count", ticket.messages().size(), "ago", ago(ticket.lastActivity())),
                    "&7Click to read ticket #" + ticket.id(), "/ticket view " + ticket.id());
        }
    }

    // ---- /report ---------------------------------------------------------------------------------------

    private void report(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            send(sender, "&cOnly players can report players.");
            return;
        }
        if (args.length < 2) {
            send(player, message("report-usage"));
            return;
        }
        ModerationCommand.Target target = ModerationCommand.resolve(args[0]);
        if (target == null) {
            send(player, Text.replace(message("player-not-found"), "player", args[0]));
            return;
        }
        if (target.uuid().equals(player.getUniqueId())) {
            send(player, message("report-self"));
            return;
        }
        List<Ticket> open = service.openOf(player.getUniqueId());
        if (open.size() >= MAX_OPEN_PER_PLAYER) {
            send(player, Text.replace(message("ticket-too-many"), "count", open.size()));
            return;
        }
        if (onCooldown(player, lastReport, REPORT_COOLDOWN_MS)) {
            return;
        }
        Ticket ticket = service.open(TicketCategory.REPORT, player.getUniqueId(), player.getName(), null, null,
                target.name(), location(player), join(args, 1), TicketMessage.Origin.GAME);
        send(player, Text.replace(message("report-sent"), "player", target.name(), "id", ticket.id()));
    }

    // ---- /tickets (staff) --------------------------------------------------------------------------------

    private void staff(CommandSender sender, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "list";
        if (sub.equals("list")) {
            listAll(sender);
            return;
        }
        if (args.length < 2 || !List.of("view", "reply", "claim", "close", "tp").contains(sub)) {
            send(sender, "&cUsage: /tickets [view|reply|claim|close|tp] <id> ...");
            return;
        }
        Integer id = parseId(args[1]);
        Ticket ticket = id != null ? service.get(id) : null;
        if (ticket == null) {
            send(sender, Text.replace(message("ticket-not-found"), "id", args[1]));
            return;
        }
        String staffName = sender instanceof Player player ? player.getName() : "Console";
        switch (sub) {
            case "view" -> view(sender, ticket, true);
            case "reply" -> {
                String text = join(args, 2);
                if (text.isEmpty()) {
                    send(sender, "&cUsage: /tickets reply <id> <message>");
                } else if (!service.reply(ticket, staffName, true, TicketMessage.Origin.GAME, text)) {
                    send(sender, message(ticket.isOpen() ? "ticket-full" : "ticket-closed")
                            .replace("{id}", String.valueOf(ticket.id())));
                } else {
                    send(sender, Text.replace(message("ticket-added"), "id", ticket.id()));
                }
            }
            case "claim" -> {
                if (service.claim(ticket, staffName)) {
                    send(sender, Text.replace(message("ticket-claimed"), "id", ticket.id(), "staff", staffName));
                } else {
                    send(sender, Text.replace(message("ticket-closed"), "id", ticket.id()));
                }
            }
            case "close" -> {
                String reason = join(args, 2);
                service.close(ticket, staffName, reason.isEmpty() ? message("no-reason") : reason);
                send(sender, Text.replace(message("ticket-closed"), "id", ticket.id()));
            }
            default -> teleport(sender, ticket);
        }
    }

    private void listAll(CommandSender sender) {
        List<Ticket> open = service.open();
        if (open.isEmpty()) {
            send(sender, message("tickets-empty"));
            return;
        }
        send(sender, Text.replace(message("tickets-header"), "count", open.size()));
        for (Ticket ticket : open) {
            String template = "  &b#{id} &7{category}" + (ticket.about() != null ? " &8({about})" : "")
                    + " &8- &f{player}" + (ticket.openedInGame() ? "" : " &8(Discord)") + " &8- &7{count} msg, {ago}"
                    + (ticket.claimedBy() != null ? " &8- &7handled by &f{staff}" : "");
            Chat.run(sender, Text.colorWith(template, "id", ticket.id(), "category", ticket.category().display(),
                            "about", ticket.about(), "player", ticket.creatorName(), "count", ticket.messages().size(),
                            "ago", ago(ticket.lastActivity()), "staff", ticket.claimedBy()),
                    "&7Click to read ticket #" + ticket.id(), "/tickets view " + ticket.id());
        }
    }

    private void teleport(CommandSender sender, Ticket ticket) {
        if (!(sender instanceof Player player)) {
            send(sender, "&cOnly players can teleport.");
            return;
        }
        Location location = parseLocation(ticket.location());
        if (location == null) {
            send(sender, "&cTicket #" + ticket.id() + " has no location (it was opened on Discord).");
            return;
        }
        player.teleport(location);
        send(sender, "&7Teleported to where ticket &f#" + ticket.id() + " &7was opened.");
    }

    /** The conversation, newest last. Staff also get buttons. */
    private void view(CommandSender to, Ticket ticket, boolean staffView) {
        to.sendMessage(Text.color("&8&m          &r &bTicket #" + ticket.id() + " &8(" + ticket.category().display()
                + (ticket.isOpen() ? "" : ", closed") + ") &8&m          "));
        String info = "&7Opened by &f{player}" + (ticket.openedInGame() ? "" : " &8(Discord)") + " &7{ago}"
                + (ticket.about() != null ? " &8| &7About: &f{about}" : "")
                + (ticket.claimedBy() != null ? " &8| &7Handled by &f{staff}" : "");
        to.sendMessage(Text.colorWith(info, "player", ticket.creatorName(), "ago", ago(ticket.created()),
                "about", ticket.about(), "staff", ticket.claimedBy()));
        if (!ticket.openedInGame() && ticket.minecraftName() != null) {
            to.sendMessage(Text.colorWith("&7Minecraft name: &f{name}", "name", ticket.minecraftName()));
        }
        if (!ticket.isOpen()) {
            to.sendMessage(Text.colorWith("&7Closed by &f{staff} &8({reason})", "staff", ticket.closedBy(),
                    "reason", ticket.closeReason() == null ? "" : ticket.closeReason()));
        }
        List<TicketMessage> messages = ticket.messages();
        int from = Math.max(0, messages.size() - VIEW_MESSAGES);
        if (from > 0) {
            to.sendMessage(Text.color("&8(" + from + " earlier message(s) not shown)"));
        }
        for (TicketMessage line : messages.subList(from, messages.size())) {
            String author = line.author() + (line.origin() == TicketMessage.Origin.GAME ? "" : " (Discord)");
            to.sendMessage(Text.colorWith((line.staff() ? "&8{time} &b{author}&7: &f{text}" : "&8{time} &f{author}&7: &f{text}"),
                    "time", TIME.format(Instant.ofEpochMilli(line.time())), "author", author, "text", line.text()));
        }
        if (!ticket.isOpen()) {
            return;
        }
        if (staffView) {
            List<Chat.Part> parts = new ArrayList<>();
            parts.add(Chat.Part.suggest(Text.color("&a[Reply] "), "&7Answer the player", "/tickets reply " + ticket.id() + " "));
            if (ticket.claimedBy() == null) {
                parts.add(Chat.Part.run(Text.color("&e[Claim] "), "&7Handle this ticket", "/tickets claim " + ticket.id()));
            }
            parts.add(Chat.Part.suggest(Text.color("&c[Close] "), "&7Close with a reason", "/tickets close " + ticket.id() + " "));
            if (ticket.location() != null && to instanceof Player) {
                parts.add(Chat.Part.run(Text.color("&b[Teleport]"), "&7Go to where it was opened", "/tickets tp " + ticket.id()));
            }
            Chat.line(to, parts.toArray(new Chat.Part[0]));
        } else {
            Chat.line(to, Chat.Part.suggest(Text.color("&a[Reply] "), "&7Add a message", "/ticket reply " + ticket.id() + " "),
                    Chat.Part.suggest(Text.color("&c[Close]"), "&7Close your ticket", "/ticket close " + ticket.id()));
        }
    }

    // ---- in-game notifications ---------------------------------------------------------------------------

    @Override
    public void opened(Ticket ticket) {
        TicketMessage first = ticket.messages().get(0);
        String category = ticket.category().display() + (ticket.about() != null ? ": " + ticket.about() : "");
        String text = Text.colorWith(message("prefix") + message("ticket-staff-new"), "id", ticket.id(),
                "player", ticket.creatorName() + (ticket.openedInGame() ? "" : " (Discord)"), "category", category,
                "message", preview(first.text()));
        notifyStaff(text, ticket, ticket.openedInGame() ? ticket.creatorUuid() : null);
    }

    @Override
    public void message(Ticket ticket, TicketMessage line) {
        String author = line.author() + (line.origin() == TicketMessage.Origin.GAME ? "" : " (Discord)");
        Player writer = line.origin() == TicketMessage.Origin.GAME ? Bukkit.getPlayerExact(line.author()) : null;
        if (line.staff() && ticket.openedInGame()) {
            Player creator = Bukkit.getPlayer(ticket.creatorUuid());
            if (creator != null && !creator.equals(writer)) {
                Chat.suggest(creator, Text.colorWith(message("prefix") + message("ticket-reply"), "id", ticket.id(),
                        "author", author, "message", line.text()), "&7Click to answer",
                        "/ticket reply " + ticket.id() + " ");
            }
        }
        String text = Text.colorWith(message("prefix") + message("ticket-staff-message"), "id", ticket.id(),
                "author", author, "message", preview(line.text()));
        notifyStaff(text, ticket, writer != null ? writer.getUniqueId() : null);
    }

    @Override
    public void claimed(Ticket ticket) {
        Player creator = ticket.openedInGame() ? Bukkit.getPlayer(ticket.creatorUuid()) : null;
        if (creator != null && !creator.getName().equals(ticket.claimedBy())) {
            creator.sendMessage(Text.colorWith(message("prefix") + message("ticket-claimed"), "id", ticket.id(),
                    "staff", ticket.claimedBy()));
        }
    }

    @Override
    public void closed(Ticket ticket) {
        Player creator = ticket.openedInGame() ? Bukkit.getPlayer(ticket.creatorUuid()) : null;
        if (creator != null && !creator.getName().equals(ticket.closedBy())) {
            creator.sendMessage(closedNotice(ticket));
        }
    }

    private String closedNotice(Ticket ticket) {
        return Text.colorWith(message("prefix") + message("ticket-closed-notify"), "id", ticket.id(),
                "staff", ticket.closedBy(), "reason", ticket.closeReason() == null ? "" : ticket.closeReason());
    }

    private void notifyStaff(String colored, Ticket ticket, UUID except) {
        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (staff.hasPermission(STAFF_PERMISSION) && !staff.getUniqueId().equals(except)) {
                Chat.run(staff, colored, "&7Click to read ticket #" + ticket.id(), "/tickets view " + ticket.id());
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // After the join messages, so it is not missed.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline() || !settings.get().tickets().enabled()) {
                return;
            }
            for (Ticket ticket : service.unreadOf(player.getUniqueId())) {
                if (ticket.isOpen()) {
                    Chat.run(player, Text.color(message("prefix") + Text.replace(message("ticket-unread"), "id", ticket.id())),
                            "&7Click to read it", "/ticket view " + ticket.id());
                } else {
                    player.sendMessage(closedNotice(ticket));
                    service.markRead(ticket);
                }
            }
            int open = service.open().size();
            if (open > 0 && player.hasPermission(STAFF_PERMISSION)) {
                Chat.run(player, Text.color(message("prefix") + Text.replace(message("tickets-open-join"), "count", open)),
                        "&7Click to see them", "/tickets");
            }
        }, 40L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        lastMessage.remove(event.getPlayer().getUniqueId());
    }

    // ---- helpers -------------------------------------------------------------------------------------------

    private boolean onCooldown(Player player, Map<UUID, Long> last, long cooldownMs) {
        if (player.hasPermission(STAFF_PERMISSION)) {
            return false;
        }
        long now = Clock.now();
        Long previous = last.get(player.getUniqueId());
        if (previous != null && now - previous < cooldownMs) {
            send(player, message("ticket-cooldown"));
            return true;
        }
        last.put(player.getUniqueId(), now);
        return false;
    }

    static Integer parseId(String text) {
        String digits = text.startsWith("#") ? text.substring(1) : text;
        if (digits.isEmpty() || digits.length() > 9 || !digits.chars().allMatch(Character::isDigit)) {
            return null;
        }
        return Integer.parseInt(digits);
    }

    private static String location(Player player) {
        Location at = player.getLocation();
        World world = at.getWorld();
        if (world == null) {
            return null;
        }
        return world.getName() + ";" + Text.num(at.getX()) + ";" + Text.num(at.getY()) + ";" + Text.num(at.getZ())
                + ";" + Text.num(at.getYaw()) + ";" + Text.num(at.getPitch());
    }

    static Location parseLocation(String text) {
        if (text == null) {
            return null;
        }
        String[] parts = text.split(";");
        if (parts.length < 4) {
            return null;
        }
        World world = Bukkit.getWorld(parts[0]);
        if (world == null) {
            return null;
        }
        try {
            float yaw = parts.length > 4 ? Float.parseFloat(parts[4]) : 0f;
            float pitch = parts.length > 5 ? Float.parseFloat(parts[5]) : 0f;
            return new Location(world, Double.parseDouble(parts[1]), Double.parseDouble(parts[2]),
                    Double.parseDouble(parts[3]), yaw, pitch);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String ago(long epochMs) {
        return Text.duration(System.currentTimeMillis() - epochMs) + " ago";
    }

    private static String preview(String text) {
        return text.length() > PREVIEW ? text.substring(0, PREVIEW) + "..." : text;
    }

    private static String join(String[] args, int from) {
        return from >= args.length ? "" : String.join(" ", Arrays.copyOfRange(args, from, args.length)).trim();
    }

    private String message(String key) {
        return settings.get().messages().get(key);
    }

    private void send(CommandSender sender, String text) {
        sender.sendMessage(Text.color(message("prefix") + text));
    }

    // ---- tab completion ------------------------------------------------------------------------------------

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        List<String> options = new ArrayList<>();
        if (name.equals("tickets") && sender.hasPermission(STAFF_PERMISSION)) {
            if (args.length == 1) {
                options.addAll(List.of("list", "view", "reply", "claim", "close", "tp"));
            } else if (args.length == 2 && !args[0].equalsIgnoreCase("list")) {
                for (Ticket ticket : service.open()) {
                    options.add(String.valueOf(ticket.id()));
                }
            }
        } else if (name.equals("report") && sender.hasPermission("vigil.report")) {
            if (args.length == 1) {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (!player.equals(sender)) {
                        options.add(player.getName());
                    }
                }
            }
        } else if (name.equals("ticket") && sender.hasPermission("vigil.ticket") && args.length == 1) {
            options.addAll(List.of("reply", "view", "close", "list", "new"));
        } else if (name.equals("ticket") && sender instanceof Player player && args.length == 2
                && List.of("reply", "view", "close").contains(args[0].toLowerCase(Locale.ROOT))) {
            for (Ticket ticket : service.openOf(player.getUniqueId())) {
                options.add(String.valueOf(ticket.id()));
            }
        }
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                result.add(option);
            }
        }
        return result;
    }
}
