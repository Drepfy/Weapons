package io.github.drepfy.vigil.discord;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.drepfy.vigil.moderation.DiscordNotifier;
import io.github.drepfy.vigil.moderation.Durations;
import io.github.drepfy.vigil.moderation.Punishment;
import io.github.drepfy.vigil.ticket.Ticket;
import io.github.drepfy.vigil.ticket.TicketCategory;
import io.github.drepfy.vigil.ticket.TicketMessage;
import io.github.drepfy.vigil.ticket.TicketService;
import io.github.drepfy.vigil.util.Text;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Every ticket gets a private Discord channel (staff roles, the bot and, for tickets opened
 * on Discord, the user who opened it). Messages go both ways; a closed ticket's channel is
 * deleted and its conversation is posted to the ticket log. Server thread, apart from
 * {@link #channelDeleted}.
 */
final class DiscordTickets implements TicketService.Observer {

    /** Seconds between "this ticket is closed" and the channel disappearing. */
    long deleteDelaySeconds = 10;
    private static final long MEMBER_ALLOW = DiscordBot.VIEW_CHANNEL | DiscordBot.SEND_MESSAGES | DiscordBot.EMBED_LINKS
            | DiscordBot.ATTACH_FILES | DiscordBot.READ_HISTORY;
    private static final int GREY = 0x95A5A6;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm", Locale.ENGLISH)
            .withZone(ZoneId.systemDefault());

    private final DiscordBot bot;
    private final Set<Integer> creating = new HashSet<>();
    private final Map<Integer, List<Consumer<String>>> waiting = new HashMap<>();
    /** Closed while the bot was offline: their channels are cleaned up once it is back. */
    private final Set<Integer> pendingClose = new LinkedHashSet<>();
    /** Channels deleted on Discord (nothing to post in or delete). */
    private final Set<String> deleted = new HashSet<>();

    DiscordTickets(DiscordBot bot) {
        this.bot = bot;
    }

    // ---- ticket changes ----------------------------------------------------------------------------

    @Override
    public void opened(Ticket ticket) {
        createChannel(ticket);
    }

    @Override
    public void message(Ticket ticket, TicketMessage message) {
        DiscordBot.Session session = bot.session();
        if (session != null && ticket.channelId() != null && message.needsDiscordPost()) {
            bot.post(session, ticket.channelId(), Json.message(format(message.author(), message.staff(),
                    message.origin(), message.text())), "tickets-category");
        }
    }

    @Override
    public void claimed(Ticket ticket) {
        DiscordBot.Session session = bot.session();
        if (session != null && ticket.channelId() != null) {
            bot.post(session, ticket.channelId(), Json.message("This ticket is now handled by **"
                    + Json.escape(ticket.claimedBy()) + "**."), "tickets-category");
        }
    }

    @Override
    public void closed(Ticket ticket) {
        DiscordBot.Session session = bot.session();
        if (session == null) {
            pendingClose.add(ticket.id());
            return;
        }
        closeOnDiscord(session, ticket);
    }

    /** After (re)connecting: channels for tickets opened meanwhile, clean-up for tickets closed meanwhile. */
    void resync(DiscordBot.Session session) {
        for (Ticket ticket : bot.tickets().open()) {
            if (ticket.channelId() == null) {
                createChannel(ticket);
            }
        }
        for (Integer id : pendingClose) {
            Ticket ticket = bot.tickets().get(id);
            if (ticket != null && !ticket.isOpen()) {
                closeOnDiscord(session, ticket);
            }
        }
        pendingClose.clear();
    }

    // ---- from Discord ---------------------------------------------------------------------------------

    /** A message typed in a ticket channel. */
    void messageFromDiscord(int id, String authorId, String name, String text) {
        Ticket ticket = bot.tickets().get(id);
        if (ticket == null || !ticket.isOpen()) {
            return;
        }
        boolean staff = authorId == null || !authorId.equals(ticket.creatorDiscordId());
        bot.tickets().reply(ticket, name, staff, TicketMessage.Origin.DISCORD, text);
    }

    /** A ticket channel was deleted on Discord: the ticket is closed. Gateway thread. */
    void channelDeleted(String channelId) {
        Integer id = bot.tickets().ticketForChannel(channelId);
        if (id == null) {
            return;
        }
        bot.sync(() -> {
            Ticket ticket = bot.tickets().get(id);
            if (ticket != null && ticket.isOpen() && channelId.equals(ticket.channelId())) {
                deleted.add(channelId);
                bot.tickets().close(ticket, "Discord", "The ticket channel was deleted");
            }
        });
    }

    /**
     * The ticket form was sent.
     *
     * @param whenReady told the new channel's ID (or {@code null} if it could not be created)
     * @return an answer for the user when no ticket was opened, else {@code null}
     */
    String openFromForm(DiscordInteractions.Caller caller, TicketCategory category, Map<String, String> values,
                        Consumer<String> whenReady) {
        if (!bot.settings().tickets().enabled()) {
            return "Tickets are turned off.";
        }
        Integer existing = bot.tickets().openTicketOfDiscordUser(caller.id());
        if (existing != null) {
            return "You already have an open ticket (#" + existing + ").";
        }
        String minecraftName = Text.plain(values.getOrDefault("name", ""), 16).replace(" ", "");
        String about = switch (category) {
            case REPORT -> Text.plain(values.getOrDefault("player", ""), 16).replace(" ", "");
            case APPEAL -> minecraftName;
            default -> "";
        };
        String text = Text.plain(values.getOrDefault("text", ""), TicketService.MAX_MESSAGE_LENGTH);
        if (text.isEmpty()) {
            return "Please describe your request.";
        }
        Ticket ticket = bot.tickets().open(category, null, caller.name(), caller.id(),
                minecraftName.isEmpty() ? null : minecraftName, about.isEmpty() ? null : about, null, text,
                TicketMessage.Origin.FORM);
        if (ticket.channelId() != null) {
            whenReady.accept(ticket.channelId());
        } else if (creating.contains(ticket.id())) {
            waiting.computeIfAbsent(ticket.id(), key -> new ArrayList<>()).add(whenReady);
        } else {
            whenReady.accept(null);
        }
        return null;
    }

    // ---- channels ---------------------------------------------------------------------------------------

    private void createChannel(Ticket ticket) {
        DiscordBot.Session session = bot.session();
        if (!ticket.isOpen() || ticket.channelId() != null || creating.contains(ticket.id())
                || session == null || !session.ready || session.botUserId == null) {
            return; // Without a connected bot, resync() creates it later.
        }
        creating.add(ticket.id());
        bot.request(session, "POST", "/guilds/" + session.serverId + "/channels", channelBody(session, ticket))
                .whenComplete((result, error) -> bot.sync(() -> {
                    creating.remove(ticket.id());
                    String channelId = error == null && result != null && result.isJsonObject()
                            ? Json.str(result.getAsJsonObject(), "id") : null;
                    if (channelId == null) {
                        bot.warn("ticket-channel", "could not create a channel for ticket #" + ticket.id() + ": "
                                + DiscordBot.describe(error) + ". The bot needs the Manage Channels permission"
                                + (bot.config().ticketsCategory().isEmpty() ? "" : " in the tickets category") + ".");
                        answerWaiting(ticket.id(), null);
                        return;
                    }
                    bot.tickets().setChannel(ticket, channelId);
                    // The bot may have reconnected meanwhile (/ac reload): post with the running one.
                    DiscordBot.Session current = bot.session() != null ? bot.session() : session;
                    bot.post(current, channelId, intro(current, ticket), "tickets-category");
                    for (TicketMessage message : ticket.messages()) {
                        if (message.needsDiscordPost()) {
                            bot.post(current, channelId, Json.message(format(message.author(), message.staff(),
                                    message.origin(), message.text())), "tickets-category");
                        }
                    }
                    if (ticket.claimedBy() != null) {
                        claimed(ticket);
                    }
                    answerWaiting(ticket.id(), channelId);
                    if (!ticket.isOpen()) {
                        closeOnDiscord(current, ticket); // Closed while the channel was being made.
                    }
                }));
    }

    private void answerWaiting(int id, String channelId) {
        List<Consumer<String>> callbacks = waiting.remove(id);
        if (callbacks != null) {
            for (Consumer<String> callback : callbacks) {
                callback.accept(channelId);
            }
        }
    }

    /** Private: hidden from everyone, visible to staff roles, the bot and the Discord user who opened it. */
    JsonObject channelBody(DiscordBot.Session session, Ticket ticket) {
        JsonObject body = new JsonObject();
        body.addProperty("name", "ticket-" + ticket.id() + "-" + slug(ticket.creatorName()));
        body.addProperty("type", 0);
        body.addProperty("topic", Json.cut("Ticket #" + ticket.id() + " | " + ticket.category().display() + " | "
                + ticket.creatorName() + (ticket.openedInGame() ? " (in game)" : ""), 1000));
        String category = bot.config().ticketsCategory();
        if (!category.isEmpty()) {
            body.addProperty("parent_id", category);
        }
        JsonArray overwrites = new JsonArray();
        overwrites.add(overwrite(session.serverId, 0, 0L, DiscordBot.VIEW_CHANNEL)); // @everyone
        overwrites.add(overwrite(session.botUserId, 1, MEMBER_ALLOW, 0L));
        for (String role : bot.config().staffRoles()) {
            overwrites.add(overwrite(role, 0, MEMBER_ALLOW, 0L));
        }
        if (ticket.creatorDiscordId() != null) {
            overwrites.add(overwrite(ticket.creatorDiscordId(), 1, MEMBER_ALLOW, 0L));
        }
        body.add("permission_overwrites", overwrites);
        return body;
    }

    private static JsonObject overwrite(String id, int type, long allow, long deny) {
        JsonObject overwrite = new JsonObject();
        overwrite.addProperty("id", id);
        overwrite.addProperty("type", type);
        overwrite.addProperty("allow", Long.toString(allow));
        overwrite.addProperty("deny", Long.toString(deny));
        return overwrite;
    }

    /** The first message in a ticket channel: what it is about, and Claim/Close buttons. */
    private JsonObject intro(DiscordBot.Session session, Ticket ticket) {
        JsonObject embed = new JsonObject();
        embed.addProperty("title", "Ticket #" + ticket.id() + ": " + ticket.category().display());
        embed.addProperty("color", DiscordNotifier.BLUE);
        embed.addProperty("description", ticket.openedInGame()
                ? "Opened in game by **" + Json.escape(ticket.creatorName()) + "**. Messages here are sent to the "
                + "player in game, and their answers appear here."
                : "Opened by <@" + ticket.creatorDiscordId() + ">. Staff will answer here.");
        JsonArray fields = new JsonArray();
        if (!ticket.openedInGame() && ticket.minecraftName() != null) {
            fields.add(Json.field("Minecraft name", Json.escape(ticket.minecraftName()), true));
        }
        if (ticket.about() != null && ticket.category() == TicketCategory.REPORT) {
            fields.add(Json.field("Reported player", Json.escape(ticket.about()), true));
        }
        if (ticket.location() != null) {
            String[] parts = ticket.location().split(";");
            if (parts.length >= 4) {
                fields.add(Json.field("Location", parts[0] + " " + parts[1] + ", " + parts[2] + ", " + parts[3], true));
            }
        }
        if (ticket.category() == TicketCategory.APPEAL && ticket.minecraftName() != null) {
            Punishment ban = bot.moderation().activeBanByName(ticket.minecraftName());
            String permanent = bot.settings().messages().get("permanent");
            fields.add(Json.field("Active ban", ban == null ? "None found for this name"
                    : Json.escape(Text.strip(ban.reason())) + " (#" + ban.id() + ") by " + Json.escape(ban.staff()) + ", "
                    + Durations.format(ban.durationMs(), permanent), false));
        }
        embed.add("fields", fields);
        JsonObject footer = new JsonObject();
        footer.addProperty("text", "Staff: Claim to handle it, Close when done (or /close <reason>).");
        embed.add("footer", footer);

        JsonObject message = Json.embedMessage(embed);
        message.add("components", Json.row(
                Json.button(1, "Claim", "vigil:claim:" + ticket.id()),
                Json.button(4, "Close", "vigil:close:" + ticket.id())));
        // Staff roles (and the user who opened it) are told about the new ticket.
        StringBuilder pings = new StringBuilder();
        JsonObject mentions = Json.noMentions();
        JsonArray roles = new JsonArray();
        if (bot.config().pingStaff()) {
            for (String role : bot.config().staffRoles()) {
                pings.append("<@&").append(role).append("> ");
                roles.add(role);
            }
        }
        JsonArray users = new JsonArray();
        if (ticket.creatorDiscordId() != null) {
            pings.append("<@").append(ticket.creatorDiscordId()).append('>');
            users.add(ticket.creatorDiscordId());
        }
        mentions.add("roles", roles);
        mentions.add("users", users);
        message.add("allowed_mentions", mentions);
        if (pings.length() > 0) {
            message.addProperty("content", pings.toString().trim());
        }
        return message;
    }

    /** Posts the close notice, deletes the channel shortly after and logs the conversation. */
    private void closeOnDiscord(DiscordBot.Session session, Ticket ticket) {
        String channel = ticket.channelId();
        if (channel != null && !deleted.remove(channel)) {
            bot.post(session, channel, Json.message("Ticket closed by **" + Json.escape(ticket.closedBy()) + "**"
                    + (ticket.closeReason() != null ? ": " + ticket.closeReason() : "") + ". This channel will be "
                    + "deleted in " + deleteDelaySeconds + " seconds."), "tickets-category");
            CompletableFuture.delayedExecutor(deleteDelaySeconds, TimeUnit.SECONDS).execute(() ->
                    bot.request(session, "DELETE", "/channels/" + channel, null).exceptionally(error -> {
                        bot.warn("ticket-delete", "could not delete the channel of ticket #" + ticket.id() + ": "
                                + DiscordBot.describe(error));
                        return null;
                    }));
        }
        String log = bot.config().ticketLogChannel();
        if (!log.isEmpty()) {
            session.rest.upload("/channels/" + log + "/messages", summary(ticket), "ticket-" + ticket.id() + ".txt",
                    transcript(ticket)).exceptionally(error -> {
                        bot.warn("ticket-log-channel", "could not post in discord.bot.ticket-log-channel: "
                                + DiscordBot.describe(error));
                        return null;
                    });
        }
    }

    private static JsonObject summary(Ticket ticket) {
        JsonObject embed = new JsonObject();
        embed.addProperty("title", "Ticket #" + ticket.id() + " closed: " + ticket.category().display());
        embed.addProperty("color", GREY);
        JsonArray fields = new JsonArray();
        fields.add(Json.field("Opened by", Json.escape(ticket.creatorName())
                + (ticket.openedInGame() ? " (in game)" : " (<@" + ticket.creatorDiscordId() + ">)"), true));
        if (ticket.about() != null) {
            fields.add(Json.field("About", Json.escape(ticket.about()), true));
        }
        fields.add(Json.field("Handled by", ticket.claimedBy() != null ? Json.escape(ticket.claimedBy()) : "-", true));
        fields.add(Json.field("Closed by", Json.escape(ticket.closedBy()), true));
        fields.add(Json.field("Reason", ticket.closeReason(), true));
        fields.add(Json.field("Messages", String.valueOf(ticket.messages().size()), true));
        embed.add("fields", fields);
        return Json.embedMessage(embed);
    }

    /** The whole conversation as plain text. */
    static String transcript(Ticket ticket) {
        StringBuilder out = new StringBuilder();
        out.append("Ticket #").append(ticket.id()).append(" - ").append(ticket.category().display()).append('\n');
        out.append("Opened by ").append(ticket.creatorName()).append(ticket.openedInGame() ? " (in game)" : " (Discord)")
                .append(" on ").append(TIME.format(Instant.ofEpochMilli(ticket.created()))).append('\n');
        if (ticket.minecraftName() != null && !ticket.openedInGame()) {
            out.append("Minecraft name: ").append(ticket.minecraftName()).append('\n');
        }
        if (ticket.about() != null) {
            out.append("About: ").append(ticket.about()).append('\n');
        }
        if (ticket.claimedBy() != null) {
            out.append("Handled by: ").append(ticket.claimedBy()).append('\n');
        }
        if (!ticket.isOpen()) {
            out.append("Closed by ").append(ticket.closedBy()).append(" on ")
                    .append(TIME.format(Instant.ofEpochMilli(ticket.closed())))
                    .append(ticket.closeReason() != null ? ": " + ticket.closeReason() : "").append('\n');
        }
        out.append('\n');
        for (TicketMessage message : ticket.messages()) {
            out.append('[').append(TIME.format(Instant.ofEpochMilli(message.time()))).append("] ")
                    .append(message.author()).append(message.staff() ? " (staff)" : "")
                    .append(message.origin() == TicketMessage.Origin.GAME ? " (in game)" : "")
                    .append(": ").append(message.text()).append('\n');
        }
        return out.toString();
    }

    /** One ticket message as posted on Discord. */
    static String format(String author, boolean staff, TicketMessage.Origin origin, String text) {
        String who = "**" + Json.escape(author) + "**" + (staff ? " (staff)" : "")
                + (origin == TicketMessage.Origin.GAME ? " (in game)" : "");
        return Json.cut(who + ": " + text, 2000);
    }

    /** "Steve_123" → "steve-123" (Discord channel names). */
    static String slug(String name) {
        String slug = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (slug.length() > 20) {
            slug = slug.substring(0, 20).replaceAll("-+$", "");
        }
        return slug.isEmpty() ? "player" : slug;
    }
}
