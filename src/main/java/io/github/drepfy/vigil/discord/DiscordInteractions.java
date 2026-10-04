package io.github.drepfy.vigil.discord;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.drepfy.vigil.config.Settings;
import io.github.drepfy.vigil.moderation.DiscordNotifier;
import io.github.drepfy.vigil.moderation.Durations;
import io.github.drepfy.vigil.moderation.ModerationCommand;
import io.github.drepfy.vigil.moderation.Punishment;
import io.github.drepfy.vigil.moderation.PunishmentType;
import io.github.drepfy.vigil.moderation.ReasonPreset;
import io.github.drepfy.vigil.ticket.Ticket;
import io.github.drepfy.vigil.ticket.TicketCategory;
import io.github.drepfy.vigil.ticket.TicketMessage;
import io.github.drepfy.vigil.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * Slash commands, buttons and forms. Called on the gateway thread; anything that touches
 * the game runs on the server thread and answers Discord afterwards.
 */
final class DiscordInteractions {

    private static final Set<String> MODERATION = Set.of("ban", "unban", "mute", "unmute", "warn", "unwarn", "kick");
    private static final List<String> DURATIONS = List.of("1h", "6h", "12h", "1d", "3d", "7d", "14d", "30d", "perm");
    private static final List<String> WARN_TIMES = List.of("1h", "6h", "12h", "1d", "3d", "7d", "10d");
    private static final int APPLICATION_COMMAND = 2;
    private static final int COMPONENT = 3;
    private static final int AUTOCOMPLETE = 4;
    private static final int MODAL_SUBMIT = 5;
    private static final int REPLY = 4;
    private static final int DEFERRED_REPLY = 5;
    private static final int AUTOCOMPLETE_RESULT = 8;
    private static final int MODAL = 9;

    /** Who used a command or button. */
    record Caller(String id, String name, boolean staff) {
        /** Name for punishments and tickets, e.g. "Bob (Discord)". */
        String staffName() {
            return name + " (Discord)";
        }
    }

    private final DiscordBot bot;

    DiscordInteractions(DiscordBot bot) {
        this.bot = bot;
    }

    // ---- registration ------------------------------------------------------------------------------

    /** Adds the slash commands to the server (replaces older versions of them). */
    void register(DiscordBot.Session session) {
        String path = "/applications/" + session.applicationId + "/guilds/" + session.serverId + "/commands";
        bot.request(session, "PUT", path, definitions()).whenComplete((result, error) -> {
            if (error != null) {
                bot.warn("commands", "could not add the slash commands: " + DiscordBot.describe(error)
                        + ". Invite the bot with this link (it includes the commands permission): "
                        + DiscordBot.inviteUrl(session));
            }
        });
    }

    static JsonArray definitions() {
        JsonArray list = new JsonArray();
        JsonObject player = option("player", "Minecraft name", true, true);
        JsonObject reason = option("reason", "Reason or preset (e.g. Cheating)", false, true);
        list.add(command("lookup", "Show a player's punishments and status", player));
        list.add(command("ban", "Ban a player", player,
                option("duration", "e.g. 7d, 30d or perm (empty: the preset's time)", false, true), reason));
        list.add(command("unban", "Unban a player", player, reason));
        list.add(command("mute", "Mute a player", player,
                option("duration", "e.g. 30m, 1d or perm (empty: the preset's time)", false, true), reason));
        list.add(command("unmute", "Unmute a player", player, reason));
        list.add(command("warn", "Warn a player for a set time", player,
                option("time", "How long the warning counts, e.g. 1d", true, true), reason));
        list.add(command("unwarn", "Remove a player's newest warning", player, reason));
        list.add(command("kick", "Kick a player", player, reason));
        list.add(command("online", "Players online"));
        list.add(command("status", "Server status"));
        list.add(command("tickets", "Open tickets"));
        list.add(command("ticketpanel", "Post the ticket buttons in this channel"));
        list.add(command("reply", "Answer in this ticket", option("message", "Your message", true, false)));
        list.add(command("claim", "Handle this ticket"));
        list.add(command("close", "Close this ticket", option("reason", "Why it is closed", false, false)));
        return list;
    }

    private static JsonObject command(String name, String description, JsonObject... options) {
        JsonObject command = new JsonObject();
        command.addProperty("name", name);
        command.addProperty("description", description);
        command.addProperty("type", 1);
        JsonArray list = new JsonArray();
        for (JsonObject option : options) {
            list.add(option);
        }
        command.add("options", list);
        return command;
    }

    private static JsonObject option(String name, String description, boolean required, boolean autocomplete) {
        JsonObject option = new JsonObject();
        option.addProperty("type", 3);
        option.addProperty("name", name);
        option.addProperty("description", description);
        option.addProperty("required", required);
        if (autocomplete) {
            option.addProperty("autocomplete", true);
        }
        return option;
    }

    // ---- dispatch -----------------------------------------------------------------------------------

    void handle(DiscordBot.Session session, JsonObject interaction) {
        String rawType = Json.str(interaction, "type");
        int type = rawType == null ? 0 : Integer.parseInt(rawType);
        if (!session.serverId.equals(Json.str(interaction, "guild_id"))) {
            if (type == APPLICATION_COMMAND || type == COMPONENT || type == MODAL_SUBMIT) {
                reply(session, interaction, "This bot only works on its own server.");
            }
            return;
        }
        switch (type) {
            case APPLICATION_COMMAND -> command(session, interaction);
            case COMPONENT -> component(session, interaction);
            case AUTOCOMPLETE -> autocomplete(session, interaction);
            case MODAL_SUBMIT -> form(session, interaction);
            default -> {
                // Pings are not sent over the gateway.
            }
        }
    }

    private Caller caller(JsonObject interaction) {
        JsonObject member = Json.obj(interaction, "member");
        JsonObject user = member != null ? Json.obj(member, "user") : Json.obj(interaction, "user");
        return new Caller(Json.str(user, "id"), DiscordBot.displayName(member, user), bot.isStaff(member));
    }

    private void command(DiscordBot.Session session, JsonObject interaction) {
        JsonObject data = Json.obj(interaction, "data");
        String name = Json.str(data, "name");
        Map<String, String> options = options(data);
        Caller caller = caller(interaction);
        if (name == null) {
            return;
        }
        switch (name) {
            case "online" -> deferThen(session, interaction, false, this::online);
            case "status" -> deferThen(session, interaction, false, this::status);
            case "reply", "close" -> ticketCommand(session, interaction, caller, name, options);
            default -> {
                if (!caller.staff()) {
                    reply(session, interaction, "Only staff can use this command.");
                    return;
                }
                if (MODERATION.contains(name)) {
                    deferThen(session, interaction, true, () -> moderate(caller, name, options));
                    return;
                }
                switch (name) {
                    case "lookup" -> deferThen(session, interaction, true,
                            () -> lookup(options.getOrDefault("player", "")));
                    case "tickets" -> deferThen(session, interaction, true, this::ticketList);
                    case "ticketpanel" -> panel(session, interaction);
                    case "claim" -> ticketCommand(session, interaction, caller, name, options);
                    default -> reply(session, interaction, "Unknown command.");
                }
            }
        }
    }

    private void component(DiscordBot.Session session, JsonObject interaction) {
        String id = Json.str(Json.obj(interaction, "data"), "custom_id");
        String[] parts = id == null ? new String[0] : id.split(":");
        if (parts.length != 3 || !parts[0].equals("vigil")) {
            return;
        }
        Caller caller = caller(interaction);
        switch (parts[1]) {
            case "open" -> openForm(session, interaction, caller, TicketCategory.fromKey(parts[2]));
            case "claim", "close" -> {
                Integer ticket = parseId(parts[2]);
                if (ticket == null) {
                    return;
                }
                boolean creator = ticket.equals(bot.tickets().openTicketOfDiscordUser(caller.id()));
                boolean allowed = parts[1].equals("claim") ? caller.staff() : caller.staff() || creator;
                if (!allowed) {
                    reply(session, interaction, "Only staff can do that.");
                    return;
                }
                if (parts[1].equals("claim")) {
                    deferThen(session, interaction, true, () -> claim(ticket, caller));
                } else {
                    deferThen(session, interaction, true, () -> close(ticket, caller, creator && !caller.staff(), null));
                }
            }
            default -> {
                // Unknown button.
            }
        }
    }

    // ---- moderation -----------------------------------------------------------------------------------

    /** Runs /ban, /mute... exactly like in game. Server thread. */
    private JsonObject moderate(Caller caller, String command, Map<String, String> options) {
        String player = options.getOrDefault("player", "").trim();
        if (player.isEmpty() || player.contains(" ")) {
            return Json.message("Enter one Minecraft name.");
        }
        List<String> args = new ArrayList<>();
        args.add(player);
        if (command.equals("ban") || command.equals("mute")) {
            String duration = options.getOrDefault("duration", "").trim();
            if (!duration.isEmpty()) {
                if (Durations.parse(duration) == null) {
                    return Json.message("'" + duration + "' is not a time. Use for example 30m, 12h, 7d or perm.");
                }
                args.add(duration);
            }
        } else if (command.equals("warn")) {
            args.add(options.getOrDefault("time", "").trim());
        }
        String reason = options.getOrDefault("reason", "").trim();
        if (!reason.isEmpty()) {
            args.addAll(Arrays.asList(reason.split("\\s+")));
        }
        return Json.message(bot.runCommand(caller.staffName(), command, args));
    }

    private JsonObject lookup(String input) {
        String name = input.trim();
        ModerationCommand.Target target = name.isEmpty() ? null : ModerationCommand.resolve(name);
        if (target == null) {
            return Json.message("Nobody called '" + Json.escape(name) + "' has played on the server.");
        }
        Settings config = bot.settings();
        UUID uuid = target.uuid();
        Punishment ban = bot.moderation().activeBan(uuid);
        Punishment mute = bot.moderation().activeMute(uuid);
        int warnings = bot.moderation().activeWarnings(uuid, config.moderation().warningsExpireMs()).size();
        List<Punishment> history = bot.moderation().history(uuid);
        DateTimeFormatter dates = DateTimeFormatter.ofPattern(config.moderation().dateFormat(), Locale.ENGLISH)
                .withZone(ZoneId.systemDefault());
        String permanent = config.messages().get("permanent");

        JsonObject embed = new JsonObject();
        embed.addProperty("title", Json.escape(target.name()));
        embed.addProperty("color", ban != null ? DiscordNotifier.RED : mute != null ? DiscordNotifier.ORANGE
                : DiscordNotifier.GREEN);
        JsonArray fields = new JsonArray();
        fields.add(Json.field("Status", target.online() != null ? "Online" : "Offline", true));
        fields.add(Json.field("Banned", ban == null ? "No" : active(ban, dates, permanent), true));
        fields.add(Json.field("Muted", mute == null ? "No" : active(mute, dates, permanent), true));
        fields.add(Json.field("Active warnings", String.valueOf(warnings), true));
        fields.add(Json.field("Punishments", String.valueOf(history.size()), true));
        StringBuilder recent = new StringBuilder();
        for (Punishment p : history.subList(0, Math.min(8, history.size()))) {
            recent.append("#").append(p.id()).append(' ').append(typeName(p.type())).append(": ")
                    .append(Json.escape(Text.strip(p.reason())));
            if (p.type() == PunishmentType.BAN || p.type() == PunishmentType.MUTE
                    || (p.type() == PunishmentType.WARN && p.durationMs() != 0L)) {
                recent.append(" (").append(Durations.format(p.durationMs(), permanent)).append(')');
            }
            recent.append(" by ").append(Json.escape(p.staff())).append(", ")
                    .append(dates.format(Instant.ofEpochMilli(p.createdEpochMs())))
                    .append(p.revoked() ? " (lifted)" : "").append('\n');
        }
        fields.add(Json.field("Recent", recent.length() == 0 ? "None" : recent.toString(), false));
        embed.add("fields", fields);
        JsonObject footer = new JsonObject();
        footer.addProperty("text", uuid.toString());
        embed.add("footer", footer);
        return Json.embedMessage(embed);
    }

    private static String active(Punishment p, DateTimeFormatter dates, String permanent) {
        return Json.escape(Text.strip(p.reason())) + " (#" + p.id() + ")\nBy " + Json.escape(p.staff()) + ", "
                + (p.isPermanent() ? permanent.toLowerCase(Locale.ROOT)
                : "until " + dates.format(Instant.ofEpochMilli(p.expiresEpochMs())));
    }

    private static String typeName(PunishmentType type) {
        return switch (type) {
            case BAN -> "Ban";
            case MUTE -> "Mute";
            case WARN -> "Warning";
            default -> "Kick";
        };
    }

    private JsonObject online() {
        List<String> names = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            names.add(player.getName());
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        JsonObject embed = new JsonObject();
        embed.addProperty("title", "Players online: " + names.size() + "/" + Bukkit.getMaxPlayers());
        embed.addProperty("color", DiscordNotifier.BLUE);
        List<String> escaped = new ArrayList<>();
        for (String name : names) {
            escaped.add(Json.escape(name));
        }
        embed.addProperty("description", names.isEmpty() ? "Nobody is online." : Json.cut(String.join(", ", escaped), 4000));
        return Json.embedMessage(embed);
    }

    private JsonObject status() {
        JsonObject embed = new JsonObject();
        embed.addProperty("title", "Server status");
        embed.addProperty("color", DiscordNotifier.GREEN);
        JsonArray fields = new JsonArray();
        fields.add(Json.field("Players", Bukkit.getOnlinePlayers().size() + "/" + Bukkit.getMaxPlayers(), true));
        fields.add(Json.field("TPS", Text.num(Math.min(20.0, bot.tps())), true));
        fields.add(Json.field("Open tickets", String.valueOf(bot.tickets().open().size()), true));
        fields.add(Json.field("Active bans", String.valueOf(bot.moderation().bannedNames().size()), true));
        fields.add(Json.field("Version", Bukkit.getBukkitVersion() + ", Vigil "
                + bot.plugin().getDescription().getVersion(), true));
        embed.add("fields", fields);
        return Json.embedMessage(embed);
    }

    private JsonObject ticketList() {
        List<Ticket> open = bot.tickets().open();
        JsonObject embed = new JsonObject();
        embed.addProperty("title", "Open tickets: " + open.size());
        embed.addProperty("color", DiscordNotifier.BLUE);
        StringBuilder lines = new StringBuilder();
        for (Ticket ticket : open) {
            lines.append("#").append(ticket.id()).append(' ').append(ticket.category().display()).append(" - ")
                    .append(Json.escape(ticket.creatorName())).append(ticket.openedInGame() ? " (in game)" : "")
                    .append(ticket.channelId() != null ? " <#" + ticket.channelId() + ">" : "")
                    .append(ticket.claimedBy() != null ? ", handled by " + Json.escape(ticket.claimedBy()) : "")
                    .append('\n');
        }
        embed.addProperty("description", open.isEmpty() ? "There are no open tickets." : Json.cut(lines.toString(), 4000));
        return Json.embedMessage(embed);
    }

    // ---- tickets ------------------------------------------------------------------------------------------

    private void panel(DiscordBot.Session session, JsonObject interaction) {
        respond(session, interaction, DEFERRED_REPLY, ephemeralFlag());
        String channel = Json.str(interaction, "channel_id");
        bot.request(session, "POST", "/channels/" + channel + "/messages", panelMessage())
                .whenComplete((result, error) -> edit(session, interaction, Json.message(error == null
                        ? "The ticket panel was posted."
                        : "Could not post the panel: " + DiscordBot.describe(error) + ".")));
    }

    static JsonObject panelMessage() {
        JsonObject embed = new JsonObject();
        embed.addProperty("title", "Support");
        embed.addProperty("description", "Need help, want to report a player or appeal a ban?\n"
                + "Press a button below. A private channel is opened for you and our staff.");
        embed.addProperty("color", DiscordNotifier.BLUE);
        JsonObject message = Json.embedMessage(embed);
        message.add("components", Json.row(
                Json.button(1, TicketCategory.SUPPORT.display(), "vigil:open:support"),
                Json.button(4, TicketCategory.REPORT.display(), "vigil:open:report"),
                Json.button(2, TicketCategory.APPEAL.display(), "vigil:open:appeal")));
        return message;
    }

    /** /reply, /claim and /close inside a ticket channel. */
    private void ticketCommand(DiscordBot.Session session, JsonObject interaction, Caller caller, String name,
                               Map<String, String> options) {
        Integer ticket = bot.tickets().ticketForChannel(Json.str(interaction, "channel_id"));
        if (ticket == null) {
            reply(session, interaction, "Use this command in a ticket channel.");
            return;
        }
        boolean creator = ticket.equals(bot.tickets().openTicketOfDiscordUser(caller.id()));
        if (!caller.staff() && !creator) {
            reply(session, interaction, "Only staff and the person who opened this ticket can do that.");
            return;
        }
        switch (name) {
            case "reply" -> {
                String text = Text.plain(options.getOrDefault("message", ""), 1000);
                if (text.isEmpty()) {
                    reply(session, interaction, "Write a message.");
                    return;
                }
                boolean staff = !creator;
                deferThen(session, interaction, false, () -> {
                    Ticket found = bot.tickets().get(ticket);
                    if (found == null || !bot.tickets().reply(found, caller.name(), staff, TicketMessage.Origin.DISCORD,
                            text)) {
                        return Json.message("This ticket is closed.");
                    }
                    return Json.message(DiscordTickets.format(caller.name(), staff, TicketMessage.Origin.DISCORD, text));
                });
            }
            case "claim" -> deferThen(session, interaction, true, () -> claim(ticket, caller));
            default -> deferThen(session, interaction, true,
                    () -> close(ticket, caller, creator && !caller.staff(), options.get("reason")));
        }
    }

    private JsonObject claim(int id, Caller caller) {
        Ticket ticket = bot.tickets().get(id);
        if (ticket == null || !bot.tickets().claim(ticket, caller.staffName())) {
            return Json.message("This ticket is closed.");
        }
        return Json.message("You are now handling ticket #" + id + ".");
    }

    private JsonObject close(int id, Caller caller, boolean byCreator, String reason) {
        Ticket ticket = bot.tickets().get(id);
        String why = reason != null && !reason.isBlank() ? Text.plain(reason, 200)
                : byCreator ? "Closed by the player" : bot.settings().messages().get("no-reason");
        if (ticket == null || !bot.tickets().close(ticket, caller.staffName(), why)) {
            return Json.message("This ticket is already closed.");
        }
        return Json.message("Ticket #" + id + " is closed.");
    }

    /** A ticket button: shows the form, unless the user already has an open ticket. */
    private void openForm(DiscordBot.Session session, JsonObject interaction, Caller caller, TicketCategory category) {
        if (category == null) {
            return;
        }
        if (!bot.settings().tickets().enabled()) {
            reply(session, interaction, "Tickets are turned off.");
            return;
        }
        Integer existing = bot.tickets().openTicketOfDiscordUser(caller.id());
        if (existing != null) {
            String channel = bot.tickets().channelOfOpenTicket(existing);
            reply(session, interaction, "You already have an open ticket" + (channel != null ? ": <#" + channel + ">"
                    : " (#" + existing + ")") + ".");
            return;
        }
        respond(session, interaction, MODAL, form(category));
    }

    static JsonObject form(TicketCategory category) {
        JsonObject modal = new JsonObject();
        modal.addProperty("custom_id", "vigil:form:" + category.key());
        modal.addProperty("title", category.display());
        JsonArray rows = new JsonArray();
        rows.add(input("name", "Your Minecraft name", 1, category != TicketCategory.SUPPORT, 3, 16));
        if (category == TicketCategory.REPORT) {
            rows.add(input("player", "Who are you reporting?", 1, true, 3, 16));
        }
        String question = switch (category) {
            case REPORT -> "What happened?";
            case APPEAL -> "Why should your ban be lifted?";
            default -> "How can we help?";
        };
        rows.add(input("text", question, 2, true, 5, 1000));
        modal.add("components", rows);
        return modal;
    }

    private static JsonObject input(String id, String label, int style, boolean required, int min, int max) {
        JsonObject input = new JsonObject();
        input.addProperty("type", 4);
        input.addProperty("custom_id", id);
        input.addProperty("label", label);
        input.addProperty("style", style);
        input.addProperty("required", required);
        input.addProperty("min_length", min);
        input.addProperty("max_length", max);
        JsonObject row = new JsonObject();
        row.addProperty("type", 1);
        JsonArray components = new JsonArray();
        components.add(input);
        row.add("components", components);
        return row;
    }

    /** A filled-in ticket form: opens the ticket and answers with its channel. */
    private void form(DiscordBot.Session session, JsonObject interaction) {
        JsonObject data = Json.obj(interaction, "data");
        String id = Json.str(data, "custom_id");
        if (id == null || !id.startsWith("vigil:form:")) {
            return;
        }
        TicketCategory category = TicketCategory.fromKey(id.substring("vigil:form:".length()));
        if (category == null) {
            return;
        }
        Map<String, String> values = new HashMap<>();
        collectInputs(Json.arr(data, "components"), values);
        Caller caller = caller(interaction);
        respond(session, interaction, DEFERRED_REPLY, ephemeralFlag());
        bot.sync(() -> {
            String answer = bot.ticketChannels().openFromForm(caller, category, values,
                    channel -> edit(session, interaction, Json.message(channel != null
                            ? "Your ticket is open: <#" + channel + ">. Staff will answer you there."
                            : "Your ticket was opened, but the bot could not create a channel for it. "
                            + "Please tell a staff member.")));
            if (answer != null) {
                edit(session, interaction, Json.message(answer));
            }
        });
    }

    /** Text inputs, also inside the newer "label" components. */
    static void collectInputs(JsonArray components, Map<String, String> values) {
        for (JsonElement element : components) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject component = element.getAsJsonObject();
            String id = Json.str(component, "custom_id");
            String value = Json.str(component, "value");
            if (id != null && value != null) {
                values.put(id, value);
            }
            collectInputs(Json.arr(component, "components"), values);
            JsonObject inner = Json.obj(component, "component");
            if (inner != null) {
                JsonArray single = new JsonArray();
                single.add(inner);
                collectInputs(single, values);
            }
        }
    }

    // ---- autocomplete ------------------------------------------------------------------------------------------

    private void autocomplete(DiscordBot.Session session, JsonObject interaction) {
        JsonObject data = Json.obj(interaction, "data");
        String command = Json.str(data, "name");
        String focused = null;
        String typed = "";
        for (JsonElement element : Json.arr(data, "options")) {
            JsonObject option = element.getAsJsonObject();
            if (option.has("focused") && option.get("focused").getAsBoolean()) {
                focused = Json.str(option, "name");
                typed = Json.str(option, "value");
            }
        }
        if (command == null || focused == null) {
            return;
        }
        String option = focused;
        String prefix = typed == null ? "" : typed.toLowerCase(Locale.ROOT);
        // Names of banned or muted players are only suggested to staff.
        boolean staff = caller(interaction).staff();
        bot.sync(() -> {
            List<String> choices = new ArrayList<>();
            for (String suggestion : staff ? suggestions(command, option) : List.<String>of()) {
                if (suggestion.toLowerCase(Locale.ROOT).startsWith(prefix) && choices.size() < 25) {
                    choices.add(suggestion);
                }
            }
            JsonArray list = new JsonArray();
            for (String choice : choices) {
                JsonObject entry = new JsonObject();
                entry.addProperty("name", choice);
                entry.addProperty("value", choice);
                list.add(entry);
            }
            JsonObject result = new JsonObject();
            result.add("choices", list);
            respond(session, interaction, AUTOCOMPLETE_RESULT, result);
        });
    }

    private List<String> suggestions(String command, String option) {
        switch (option) {
            case "player" -> {
                if (command.equals("unban")) {
                    return bot.moderation().bannedNames();
                }
                if (command.equals("unmute")) {
                    return bot.moderation().mutedNames();
                }
                List<String> names = new ArrayList<>();
                for (Player player : Bukkit.getOnlinePlayers()) {
                    names.add(player.getName());
                }
                names.sort(String.CASE_INSENSITIVE_ORDER);
                return names;
            }
            case "duration" -> {
                return DURATIONS;
            }
            case "time" -> {
                return WARN_TIMES;
            }
            case "reason" -> {
                PunishmentType type;
                try {
                    type = PunishmentType.valueOf(command.toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    return List.of();
                }
                List<String> names = new ArrayList<>();
                for (ReasonPreset preset : bot.settings().moderation().reasons(type)) {
                    names.add(preset.name());
                }
                return names;
            }
            default -> {
                return List.of();
            }
        }
    }

    // ---- answering ----------------------------------------------------------------------------------------------

    /** Answers at once, visible only to the user. */
    private void reply(DiscordBot.Session session, JsonObject interaction, String text) {
        respond(session, interaction, REPLY, Json.ephemeral(text));
    }

    /** "Thinking...", then the answer once the server thread has worked it out. */
    private void deferThen(DiscordBot.Session session, JsonObject interaction, boolean ephemeral,
                           Supplier<JsonObject> work) {
        respond(session, interaction, DEFERRED_REPLY, ephemeral ? ephemeralFlag() : null);
        bot.sync(() -> {
            JsonObject answer;
            try {
                answer = work.get();
            } catch (RuntimeException e) {
                bot.logger().log(Level.WARNING, "Discord: a command failed", e);
                answer = Json.message("Something went wrong: " + e.getMessage());
            }
            edit(session, interaction, answer);
        });
    }

    private void respond(DiscordBot.Session session, JsonObject interaction, int type, JsonObject data) {
        JsonObject body = new JsonObject();
        body.addProperty("type", type);
        if (data != null) {
            body.add("data", data);
        }
        String path = "/interactions/" + Json.str(interaction, "id") + "/" + Json.str(interaction, "token") + "/callback";
        bot.request(session, "POST", path, body).exceptionally(error -> {
            bot.warn("interaction", "could not answer a command: " + DiscordBot.describe(error));
            return null;
        });
    }

    private void edit(DiscordBot.Session session, JsonObject interaction, JsonObject message) {
        String application = Json.str(interaction, "application_id");
        String path = "/webhooks/" + (application != null ? application : session.applicationId) + "/"
                + Json.str(interaction, "token") + "/messages/@original";
        bot.request(session, "PATCH", path, message).exceptionally(error -> {
            bot.warn("interaction", "could not answer a command: " + DiscordBot.describe(error));
            return null;
        });
    }

    private static JsonObject ephemeralFlag() {
        JsonObject data = new JsonObject();
        data.addProperty("flags", Json.EPHEMERAL);
        return data;
    }

    /** Slash command options by name. */
    static Map<String, String> options(JsonObject data) {
        Map<String, String> options = new HashMap<>();
        for (JsonElement element : Json.arr(data, "options")) {
            if (element.isJsonObject()) {
                JsonObject option = element.getAsJsonObject();
                String name = Json.str(option, "name");
                String value = Json.str(option, "value");
                if (name != null && value != null) {
                    options.put(name, value);
                }
            }
        }
        return options;
    }

    private static Integer parseId(String text) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
