package io.github.drepfy.vigil.discord;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.drepfy.vigil.VigilPlugin;
import io.github.drepfy.vigil.config.ConfigLoader;
import io.github.drepfy.vigil.config.Settings;
import io.github.drepfy.vigil.moderation.DiscordNotifier;
import io.github.drepfy.vigil.moderation.Punishment;
import io.github.drepfy.vigil.moderation.PunishmentType;
import io.github.drepfy.vigil.ticket.Ticket;
import io.github.drepfy.vigil.ticket.TicketCategory;
import io.github.drepfy.vigil.ticket.TicketMessage;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The bot against a local fake of Discord, on a simulated server. */
class DiscordBotTest {

    private static final String SERVER = "100000000000000001";
    private static final String STAFF_ROLE = "200000000000000002";
    private static final String CATEGORY = "300000000000000003";
    private static final String LOG = "300000000000000004";
    private static final String CHAT = "300000000000000005";
    private static final String PUNISHMENTS = "300000000000000006";
    private static final String ALERTS = "300000000000000007";
    private static final String APP = "400000000000000008";
    private static final String BOT_USER = "400000000000000009";
    private static final String BOB = "500000000000000010";
    private static final String ALICE = "500000000000000011";
    private static final String ELSEWHERE = "600000000000000012";

    private final AtomicInteger interactions = new AtomicInteger();
    private final java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong(1_000_000);
    private ServerMock server;
    private VigilPlugin plugin;
    private FakeDiscord discord;
    private DiscordBot bot;
    private volatile Settings settings;

    @BeforeEach
    void setUp() throws Exception {
        io.github.drepfy.vigil.util.Clock.setTestSource(clock::get);
        server = MockBukkit.mock();
        plugin = MockBukkit.load(VigilPlugin.class);
        discord = new FakeDiscord();
        settings = settings("");
        bot = new DiscordBot(plugin, () -> settings, plugin.moderation(), plugin.tickets(), () -> 19.5,
                Logger.getLogger("test"), discord.url(), (uri, events) -> new CompletableFuture<>());
        bot.ticketChannels().deleteDelaySeconds = 0;
        server.getPluginManager().registerEvents(bot, plugin);
        bot.start();
    }

    @AfterEach
    void tearDown() {
        bot.stop();
        MockBukkit.unmock();
        discord.close();
        io.github.drepfy.vigil.util.Clock.setTestSource(null);
    }

    /** config.yml with the bot switched on; {@code extra} is more YAML for discord.bot. */
    private Settings settings(String punishmentsChannel) throws Exception {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "config.yml"));
        yaml.set("discord.bot.enabled", true);
        yaml.set("discord.bot.token", "test-token");
        yaml.set("discord.bot.server-id", SERVER);
        yaml.set("discord.bot.staff-roles", List.of(STAFF_ROLE));
        yaml.set("discord.bot.punishments-channel", punishmentsChannel.isEmpty() ? PUNISHMENTS : punishmentsChannel);
        yaml.set("discord.bot.alerts-channel", ALERTS);
        yaml.set("discord.bot.tickets-category", CATEGORY);
        yaml.set("discord.bot.ticket-log-channel", LOG);
        yaml.set("discord.bot.chat-channel", CHAT);
        Settings loaded = ConfigLoader.load(yaml);
        assertEquals(List.of(), loaded.warnings());
        assertTrue(loaded.discord().bot().active());
        return loaded;
    }

    private void ready() {
        JsonObject ready = JsonParser.parseString("{\"session_id\":\"s\",\"user\":{\"id\":\"" + BOT_USER
                + "\",\"username\":\"Vigil\"},\"application\":{\"id\":\"" + APP + "\"},\"guilds\":[{\"id\":\""
                + SERVER + "\",\"unavailable\":true}]}").getAsJsonObject();
        bot.ready(bot.session(), ready);
    }

    private void dispatch(String type, JsonObject data) {
        bot.dispatch(bot.session(), type, data);
    }

    /** Runs server ticks until the condition holds (Discord answers arrive on other threads). */
    private void await(String what, BooleanSupplier condition) {
        long end = System.currentTimeMillis() + 8000;
        while (System.currentTimeMillis() < end) {
            server.getScheduler().performTicks(1);
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        StringBuilder sent = new StringBuilder();
        for (FakeDiscord.Request request : discord.requests()) {
            String body = request.body().replace('\n', ' ');
            sent.append("\n  ").append(request.method()).append(' ').append(request.path()).append(' ')
                    .append(body, 0, Math.min(160, body.length()));
        }
        throw new AssertionError("timed out waiting for " + what + "; requests:" + sent);
    }

    private FakeDiscord.Request awaitRequest(String method, String pathPart, String bodyPart) {
        FakeDiscord.Request[] found = new FakeDiscord.Request[1];
        await(method + " " + pathPart + " containing " + bodyPart, () -> {
            for (FakeDiscord.Request request : discord.requests(method, pathPart)) {
                if (bodyPart == null || request.body().contains(bodyPart)) {
                    found[0] = request;
                    return true;
                }
            }
            return false;
        });
        return found[0];
    }

    private JsonObject interaction(int type, JsonObject data, String userId, String name, boolean staff,
                                   String channel) {
        JsonObject interaction = new JsonObject();
        int number = interactions.incrementAndGet();
        interaction.addProperty("id", "70000000000000000" + number);
        interaction.addProperty("token", "token-" + number);
        interaction.addProperty("application_id", APP);
        interaction.addProperty("type", type);
        interaction.addProperty("guild_id", SERVER);
        interaction.addProperty("channel_id", channel);
        JsonObject member = new JsonObject();
        JsonObject user = new JsonObject();
        user.addProperty("id", userId);
        user.addProperty("username", name);
        member.add("user", user);
        JsonArray roles = new JsonArray();
        if (staff) {
            roles.add(STAFF_ROLE);
        }
        member.add("roles", roles);
        member.addProperty("permissions", "0");
        interaction.add("member", member);
        interaction.add("data", data);
        return interaction;
    }

    private static JsonObject command(String name, String... options) {
        JsonObject data = new JsonObject();
        data.addProperty("name", name);
        JsonArray list = new JsonArray();
        for (int i = 0; i + 1 < options.length; i += 2) {
            JsonObject option = new JsonObject();
            option.addProperty("name", options[i]);
            option.addProperty("type", 3);
            option.addProperty("value", options[i + 1]);
            list.add(option);
        }
        data.add("options", list);
        return data;
    }

    private static JsonObject button(String customId) {
        JsonObject data = new JsonObject();
        data.addProperty("custom_id", customId);
        data.addProperty("component_type", 2);
        return data;
    }

    /** What the bot answered to an interaction (the callback). */
    private JsonObject callback(JsonObject interaction) {
        String path = "/interactions/" + interaction.get("id").getAsString() + "/";
        return awaitRequest("POST", path, null).json();
    }

    /** The final answer after "thinking". */
    private JsonObject edited(JsonObject interaction) {
        String path = "/webhooks/" + APP + "/" + interaction.get("token").getAsString() + "/messages/@original";
        return awaitRequest("PATCH", path, null).json();
    }

    private JsonObject message(String channel, String author, String authorId, String content) {
        JsonObject data = new JsonObject();
        data.addProperty("id", "800000000000000001");
        data.addProperty("guild_id", SERVER);
        data.addProperty("channel_id", channel);
        data.addProperty("type", 0);
        data.addProperty("content", content);
        JsonObject user = new JsonObject();
        user.addProperty("id", authorId);
        user.addProperty("username", author);
        data.add("author", user);
        data.add("member", new JsonObject());
        return data;
    }

    private static List<String> messages(PlayerMock player) {
        List<String> result = new ArrayList<>();
        String message;
        while ((message = player.nextMessage()) != null) {
            result.add(ChatColor.stripColor(message));
        }
        return result;
    }

    private void awaitMessage(PlayerMock player, String text) {
        List<String> seen = new ArrayList<>();
        await(player.getName() + " seeing '" + text + "' (saw " + seen + ")", () -> {
            seen.addAll(messages(player));
            return seen.stream().anyMatch(line -> line.contains(text));
        });
    }

    // ---- tests ---------------------------------------------------------------------------------------

    @Test
    void addsItsSlashCommandsWhenOnline() {
        ready();
        FakeDiscord.Request request = awaitRequest("PUT", "/applications/" + APP + "/guilds/" + SERVER + "/commands", null);
        assertEquals("Bot test-token", request.authorization());
        List<String> names = new ArrayList<>();
        for (JsonElement command : request.jsonElement().getAsJsonArray()) {
            names.add(command.getAsJsonObject().get("name").getAsString());
        }
        assertTrue(names.containsAll(List.of("lookup", "ban", "unban", "mute", "unmute", "warn", "unwarn", "kick",
                "online", "status", "tickets", "ticketpanel", "reply", "claim", "close")), names.toString());
        for (JsonElement command : request.jsonElement().getAsJsonArray()) {
            assertTrue(command.getAsJsonObject().get("description").getAsString().length() <= 100);
        }
        awaitRequest("POST", "/channels/" + CHAT + "/messages", "The server is online.");
    }

    @Test
    void staffCanBanFromDiscord() {
        ready();
        PlayerMock griefer = server.addPlayer("Griefer");
        JsonObject ban = interaction(2, command("ban", "player", "Griefer", "reason", "Griefing"), BOB, "Bob", true,
                "650000000000000001");
        dispatch("INTERACTION_CREATE", ban);
        JsonObject thinking = callback(ban);
        assertEquals(5, thinking.get("type").getAsInt());
        assertEquals(64, thinking.getAsJsonObject("data").get("flags").getAsInt(), "only Bob sees the answer");
        String answer = edited(ban).get("content").getAsString();
        assertTrue(answer.startsWith("You have banned player Griefer for Griefing for 3 days"), answer);
        Punishment active = plugin.moderation().activeBan(griefer.getUniqueId());
        assertNotNull(active);
        assertEquals("Bob (Discord)", active.staff());
        assertFalse(griefer.isOnline(), "kicked with the ban screen");

        JsonObject unban = interaction(2, command("unban", "player", "Griefer", "reason", "False_Ban"), BOB, "Bob",
                true, "650000000000000001");
        dispatch("INTERACTION_CREATE", unban);
        assertTrue(edited(unban).get("content").getAsString().contains("unbanned player Griefer for False Ban"));
        assertNull(plugin.moderation().activeBan(griefer.getUniqueId()));
    }

    @Test
    void onlyStaffMayPunish() {
        ready();
        PlayerMock target = server.addPlayer("Target");
        JsonObject attempt = interaction(2, command("ban", "player", "Target"), ALICE, "Alice", false, "650000000000000001");
        dispatch("INTERACTION_CREATE", attempt);
        JsonObject answer = callback(attempt);
        assertEquals(4, answer.get("type").getAsInt());
        assertTrue(answer.getAsJsonObject("data").get("content").getAsString().contains("Only staff"));
        assertNull(plugin.moderation().activeBan(target.getUniqueId()));

        // Server administrators count as staff without a staff role.
        JsonObject admin = interaction(2, command("mute", "player", "Target", "duration", "1h", "reason", "Spam"), ALICE,
                "Alice", false, "650000000000000001");
        admin.getAsJsonObject("member").addProperty("permissions", "8");
        dispatch("INTERACTION_CREATE", admin);
        assertTrue(edited(admin).get("content").getAsString().contains("You have muted player Target for Spam for 1 hour"));

        // Protected players cannot be punished from Discord either.
        PlayerMock owner = server.addPlayer("Owner");
        owner.addAttachment(plugin, "vigil.protect", true);
        JsonObject protectedBan = interaction(2, command("ban", "player", "Owner"), BOB, "Bob", true, "650000000000000001");
        dispatch("INTERACTION_CREATE", protectedBan);
        assertTrue(edited(protectedBan).get("content").getAsString().contains("You cannot punish Owner"));
        assertNull(plugin.moderation().activeBan(owner.getUniqueId()));

        JsonObject badTime = interaction(2, command("ban", "player", "Target", "duration", "soon"), BOB, "Bob", true,
                "650000000000000001");
        dispatch("INTERACTION_CREATE", badTime);
        assertTrue(edited(badTime).get("content").getAsString().contains("'soon' is not a time"));

        JsonObject warn = interaction(2, command("warn", "player", "Target", "time", "1d", "reason", "Spam"), BOB, "Bob",
                true, "650000000000000001");
        dispatch("INTERACTION_CREATE", warn);
        assertTrue(edited(warn).get("content").getAsString().contains("You have warned player Target for Spam for 1 day"));
    }

    @Test
    void lookupShowsAPlayersRecord() {
        ready();
        PlayerMock steve = server.addPlayer("Steve");
        plugin.moderation().mute(steve.getUniqueId(), "Steve", "Spam", "Mod", 3_600_000L);
        plugin.moderation().warn(steve.getUniqueId(), "Steve", "Swearing", "Mod", 86_400_000L);
        JsonObject lookup = interaction(2, command("lookup", "player", "Steve"), BOB, "Bob", true, "650000000000000001");
        dispatch("INTERACTION_CREATE", lookup);
        JsonObject embed = edited(lookup).getAsJsonArray("embeds").get(0).getAsJsonObject();
        assertEquals("Steve", embed.get("title").getAsString());
        String fields = embed.getAsJsonArray("fields").toString();
        assertTrue(fields.contains("Online"), fields);
        assertTrue(fields.contains("Spam (#1)"), fields);
        assertTrue(fields.contains("\"Active warnings\",\"value\":\"1\""), fields);
        assertTrue(fields.contains("Warning: Swearing (1 day) by Mod"), fields);

        JsonObject unknown = interaction(2, command("lookup", "player", "Nobody"), BOB, "Bob", true, "650000000000000001");
        dispatch("INTERACTION_CREATE", unknown);
        assertTrue(edited(unknown).get("content").getAsString().contains("Nobody called 'Nobody'"));

        JsonObject online = interaction(2, command("online"), ALICE, "Alice", false, "650000000000000001");
        dispatch("INTERACTION_CREATE", online);
        JsonObject list = edited(online).getAsJsonArray("embeds").get(0).getAsJsonObject();
        assertTrue(list.get("title").getAsString().startsWith("Players online: 1/"), list.toString());
        assertEquals("Steve", list.get("description").getAsString());
        assertFalse(callback(online).getAsJsonObject().has("data"), "everyone sees /online");

        JsonObject status = interaction(2, command("status"), ALICE, "Alice", false, "650000000000000001");
        dispatch("INTERACTION_CREATE", status);
        assertTrue(edited(status).toString().contains("19.5"), "TPS is shown");
    }

    @Test
    void inGameTicketsGetAPrivateChannel() {
        ready();
        PlayerMock steve = server.addPlayer("Steve");
        PlayerMock admin = server.addPlayer("Admin");
        admin.setOp(true);
        steve.performCommand("ticket My house was griefed");

        FakeDiscord.Request create = awaitRequest("POST", "/guilds/" + SERVER + "/channels", null);
        JsonObject channel = create.json();
        assertEquals("ticket-1-steve", channel.get("name").getAsString());
        assertEquals(CATEGORY, channel.get("parent_id").getAsString());
        String overwrites = channel.getAsJsonArray("permission_overwrites").toString();
        assertTrue(overwrites.contains("{\"id\":\"" + SERVER + "\",\"type\":0,\"allow\":\"0\",\"deny\":\"1024\"}"),
                "hidden from everyone: " + overwrites);
        assertTrue(overwrites.contains("{\"id\":\"" + STAFF_ROLE + "\",\"type\":0,\"allow\":\"117760\""), overwrites);
        assertTrue(overwrites.contains("{\"id\":\"" + BOT_USER + "\",\"type\":1,\"allow\":\"117760\""), overwrites);

        Ticket ticket = plugin.tickets().get(1);
        await("the channel to be linked", () -> ticket.channelId() != null);
        String channelId = ticket.channelId();
        JsonObject intro = awaitRequest("POST", "/channels/" + channelId + "/messages", "Ticket #1: Support").json();
        assertEquals("<@&" + STAFF_ROLE + ">", intro.get("content").getAsString(), "staff are pinged");
        assertEquals("[\"" + STAFF_ROLE + "\"]", intro.getAsJsonObject("allowed_mentions").getAsJsonArray("roles").toString());
        assertTrue(intro.toString().contains("vigil:close:1"), "Close button");
        awaitRequest("POST", "/channels/" + channelId + "/messages", "**Steve** (in game): My house was griefed");

        // Staff answer on Discord; Steve reads it in game.
        JsonObject typed = message(channelId, "Bob", BOB, "Hello <@" + ALICE + ">, checking now");
        JsonArray mentions = new JsonArray();
        JsonObject alice = new JsonObject();
        alice.addProperty("id", ALICE);
        alice.addProperty("username", "Alice");
        mentions.add(alice);
        typed.add("mentions", mentions);
        typed.getAsJsonObject("member").addProperty("nick", "Bobby");
        dispatch("MESSAGE_CREATE", typed);
        awaitMessage(steve, "[Ticket #1] Bobby (Discord): Hello @Alice, checking now");
        assertTrue(ticket.messages().get(1).staff());

        // Staff in game answer; it appears on Discord.
        admin.performCommand("tickets reply 1 Fixed it");
        awaitRequest("POST", "/channels/" + channelId + "/messages", "**Admin** (staff) (in game): Fixed it");
        // Steve answers in game (after the message cooldown).
        clock.addAndGet(3000);
        steve.performCommand("ticket Thank you!");
        awaitRequest("POST", "/channels/" + channelId + "/messages", "**Steve** (in game): Thank you!");

        // Close button on Discord.
        JsonObject close = interaction(3, button("vigil:close:1"), BOB, "Bob", true, channelId);
        dispatch("INTERACTION_CREATE", close);
        await("the ticket to close", () -> !ticket.isOpen());
        assertEquals("Bob (Discord)", ticket.closedBy());
        awaitMessage(steve, "Your ticket #1 was closed by Bob (Discord)");
        awaitRequest("POST", "/channels/" + channelId + "/messages", "Ticket closed by **Bob (Discord)**");
        awaitRequest("DELETE", "/channels/" + channelId, null);
        FakeDiscord.Request log = awaitRequest("POST", "/channels/" + LOG + "/messages", "Ticket #1 closed: Support");
        assertTrue(log.contentType().startsWith("multipart/form-data"));
        assertTrue(log.body().contains("Steve (in game): My house was griefed"), log.body());
        assertTrue(log.body().contains("Bobby (staff): Hello @Alice, checking now"), log.body());
        assertTrue(log.body().contains("Admin (staff) (in game): Fixed it"), log.body());
    }

    @Test
    void discordUsersOpenTicketsWithTheForm() {
        ready();
        PlayerMock admin = server.addPlayer("Admin");
        admin.setOp(true);
        messages(admin);
        JsonObject press = interaction(3, button("vigil:open:report"), ALICE, "alice_k", false, "650000000000000099");
        dispatch("INTERACTION_CREATE", press);
        JsonObject form = callback(press);
        assertEquals(9, form.get("type").getAsInt(), "the form opens");
        assertEquals("vigil:form:report", form.getAsJsonObject("data").get("custom_id").getAsString());
        assertEquals(3, form.getAsJsonObject("data").getAsJsonArray("components").size());

        JsonObject data = JsonParser.parseString("{\"custom_id\":\"vigil:form:report\",\"components\":["
                + "{\"type\":1,\"components\":[{\"type\":4,\"custom_id\":\"name\",\"value\":\"Alice\"}]},"
                + "{\"type\":1,\"components\":[{\"type\":4,\"custom_id\":\"player\",\"value\":\"Cheater\"}]},"
                + "{\"type\":18,\"component\":{\"type\":4,\"custom_id\":\"text\",\"value\":\"He flies\\nover my base\"}}]}")
                .getAsJsonObject();
        JsonObject submit = interaction(5, data, ALICE, "alice_k", false, "650000000000000099");
        dispatch("INTERACTION_CREATE", submit);
        String answer = edited(submit).get("content").getAsString();
        assertTrue(answer.startsWith("Your ticket is open: <#"), answer);
        Ticket ticket = plugin.tickets().get(1);
        assertEquals(TicketCategory.REPORT, ticket.category());
        assertEquals("Cheater", ticket.about());
        assertEquals("Alice", ticket.minecraftName());
        assertEquals(ALICE, ticket.creatorDiscordId());
        assertEquals("He flies over my base", ticket.messages().get(0).text());
        String overwrites = discord.requests("POST", "/guilds/" + SERVER + "/channels").get(0).json()
                .getAsJsonArray("permission_overwrites").toString();
        assertTrue(overwrites.contains("{\"id\":\"" + ALICE + "\",\"type\":1,\"allow\":\"117760\""),
                "Alice can see her ticket: " + overwrites);
        String channelId = ticket.channelId();
        JsonObject intro = awaitRequest("POST", "/channels/" + channelId + "/messages", "Ticket #1").json();
        assertTrue(intro.get("content").getAsString().contains("<@" + ALICE + ">"));
        assertTrue(intro.toString().contains("Reported player"), intro.toString());
        awaitRequest("POST", "/channels/" + channelId + "/messages", "**alice\\\\_k**: He flies over my base");
        awaitMessage(admin, "[Ticket #1] alice_k (Discord) opened a ticket (Report a player: Cheater): He flies");

        // One open ticket at a time.
        JsonObject again = interaction(3, button("vigil:open:support"), ALICE, "alice_k", false, "650000000000000099");
        dispatch("INTERACTION_CREATE", again);
        assertTrue(callback(again).getAsJsonObject("data").get("content").getAsString()
                .contains("You already have an open ticket: <#" + channelId + ">"));

        // Alice writes in her channel: not staff.
        dispatch("MESSAGE_CREATE", message(channelId, "alice_k", ALICE, "Any news?"));
        await("Alice's message", () -> ticket.messages().size() == 2);
        assertFalse(ticket.messages().get(1).staff());
        awaitMessage(admin, "[Ticket #1] alice_k (Discord): Any news?");

        // Someone else cannot close it; Alice can.
        JsonObject stranger = interaction(2, command("close"), BOB, "Bob", false, channelId);
        dispatch("INTERACTION_CREATE", stranger);
        assertTrue(callback(stranger).getAsJsonObject("data").get("content").getAsString().contains("Only staff and"));
        JsonObject close = interaction(2, command("close"), ALICE, "alice_k", false, channelId);
        dispatch("INTERACTION_CREATE", close);
        assertTrue(edited(close).get("content").getAsString().contains("Ticket #1 is closed"));
        assertEquals("Closed by the player", ticket.closeReason());
        assertNull(plugin.tickets().openTicketOfDiscordUser(ALICE));
    }

    @Test
    void staffReplyAndClaimWithSlashCommands() {
        ready();
        PlayerMock steve = server.addPlayer("Steve");
        steve.performCommand("ticket Help");
        Ticket ticket = plugin.tickets().get(1);
        await("the channel", () -> ticket.channelId() != null);
        JsonObject reply = interaction(2, command("reply", "message", "Coming"), BOB, "Bob", true, ticket.channelId());
        dispatch("INTERACTION_CREATE", reply);
        assertEquals("**Bob** (staff): Coming", edited(reply).get("content").getAsString());
        awaitMessage(steve, "[Ticket #1] Bob (Discord): Coming");
        assertEquals(0, discord.requests("POST", "/channels/" + ticket.channelId() + "/messages").stream()
                .filter(request -> request.body().contains("Coming")).count(), "the answer is not posted a second time");

        JsonObject claim = interaction(2, command("claim"), BOB, "Bob", true, ticket.channelId());
        dispatch("INTERACTION_CREATE", claim);
        assertTrue(edited(claim).get("content").getAsString().contains("You are now handling ticket #1"));
        assertEquals("Bob (Discord)", ticket.claimedBy());
        awaitMessage(steve, "Ticket #1 is now handled by Bob (Discord)");
        awaitRequest("POST", "/channels/" + ticket.channelId() + "/messages", "now handled by **Bob (Discord)**");

        JsonObject outside = interaction(2, command("reply", "message", "x"), BOB, "Bob", true, "650000000000000001");
        dispatch("INTERACTION_CREATE", outside);
        assertTrue(callback(outside).getAsJsonObject("data").get("content").getAsString().contains("in a ticket channel"));
    }

    @Test
    void deletingATicketChannelClosesTheTicket() {
        ready();
        PlayerMock steve = server.addPlayer("Steve");
        steve.performCommand("ticket Help");
        Ticket ticket = plugin.tickets().get(1);
        await("the channel", () -> ticket.channelId() != null);
        String channelId = ticket.channelId();
        JsonObject deleted = new JsonObject();
        deleted.addProperty("id", channelId);
        dispatch("CHANNEL_DELETE", deleted);
        await("the ticket to close", () -> !ticket.isOpen());
        assertEquals("Discord", ticket.closedBy());
        awaitRequest("POST", "/channels/" + LOG + "/messages", "Ticket #1 closed");
        assertTrue(discord.requests("DELETE", "/channels/" + channelId).isEmpty(), "already gone");
    }

    @Test
    void ticketsOpenedWhileOfflineGetAChannelOnceOnline() {
        PlayerMock steve = server.addPlayer("Steve");
        steve.performCommand("ticket Help");
        server.getScheduler().performTicks(5);
        assertTrue(discord.requests("POST", "/guilds/").isEmpty(), "not connected yet");
        ready();
        awaitRequest("POST", "/guilds/" + SERVER + "/channels", "ticket-1-steve");
    }

    @Test
    void ticketPanelHasAButtonPerKind() {
        ready();
        JsonObject panel = interaction(2, command("ticketpanel"), BOB, "Bob", true, "650000000000000077");
        dispatch("INTERACTION_CREATE", panel);
        JsonObject posted = awaitRequest("POST", "/channels/650000000000000077/messages", "vigil:open:support").json();
        String buttons = posted.getAsJsonArray("components").toString();
        assertTrue(buttons.contains("vigil:open:report") && buttons.contains("vigil:open:appeal"), buttons);
        assertEquals("The ticket panel was posted.", edited(panel).get("content").getAsString());

        discord.answer(request -> request.path().contains("/channels/650000000000000078/"), 403,
                "{\"message\":\"Missing Permissions\",\"code\":50013}");
        JsonObject denied = interaction(2, command("ticketpanel"), BOB, "Bob", true, "650000000000000078");
        dispatch("INTERACTION_CREATE", denied);
        assertTrue(edited(denied).get("content").getAsString().contains("missing a permission"));
    }

    @Test
    void punishmentsAndAlertsArePosted() throws Exception {
        Punishment ban = new Punishment(7, PunishmentType.BAN, java.util.UUID.randomUUID(), "Cheater",
                "Cheating (Flying)", "Anti-Cheat", System.currentTimeMillis(), 30L * 86_400_000L, false, null, null, 0L);
        DiscordNotifier notifier = new DiscordNotifier(() -> settings, Logger.getLogger("test"));
        notifier.setBot(bot);
        notifier.punishment(ban);
        JsonObject posted = awaitRequest("POST", "/channels/" + PUNISHMENTS + "/messages", "Anti-Cheat banned: Cheater").json();
        assertEquals(0, posted.getAsJsonObject("allowed_mentions").getAsJsonArray("parse").size());

        for (int i = 0; i < 12; i++) {
            notifier.alert("Flyer", "Flying", String.valueOf(i), "hovering");
        }
        server.getScheduler().performTicks(25);
        await("two alert messages", () -> discord.requests("POST", "/channels/" + ALERTS + "/messages").size() == 2);
        int embeds = 0;
        for (FakeDiscord.Request request : discord.requests("POST", "/channels/" + ALERTS + "/messages")) {
            embeds += request.json().getAsJsonArray("embeds").size();
        }
        assertEquals(12, embeds, "ten alerts to a message");

        // Without a bot channel for them, punishments go to the webhook (not set here: nowhere).
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "config.yml"));
        yaml.set("discord.bot.enabled", true);
        yaml.set("discord.bot.token", "test-token");
        yaml.set("discord.bot.server-id", SERVER);
        settings = ConfigLoader.load(yaml);
        assertFalse(bot.postPunishment(DiscordNotifier.punishmentEmbed(ban, "Permanent")));
        assertFalse(bot.postAlert(DiscordNotifier.punishmentEmbed(ban, "Permanent")));
    }

    @Test
    void chatGoesBothWays() {
        ready();
        PlayerMock steve = server.addPlayer("Steve");
        server.getPluginManager().callEvent(new AsyncPlayerChatEvent(false, steve, "hello @everyone",
                new HashSet<>(server.getOnlinePlayers())));
        FakeDiscord.Request chat = awaitRequest("POST", "/channels/" + CHAT + "/messages", "**Steve:** hello @everyone");
        assertTrue(chat.body().contains("**Steve** joined the server."), chat.body());
        assertEquals(0, chat.json().getAsJsonObject("allowed_mentions").getAsJsonArray("parse").size(), "no pings");

        dispatch("MESSAGE_CREATE", message(CHAT, "Bob", BOB, "hi there &c<:wave:123>"));
        awaitMessage(steve, "Discord | Bob: hi there &c:wave:");

        JsonObject fromBot = message(CHAT, "OtherBot", "500000000000000099", "spam");
        fromBot.getAsJsonObject("author").addProperty("bot", true);
        dispatch("MESSAGE_CREATE", fromBot);
        JsonObject otherServer = message(CHAT, "Eve", "500000000000000098", "from elsewhere");
        otherServer.addProperty("guild_id", ELSEWHERE);
        dispatch("MESSAGE_CREATE", otherServer);
        server.getScheduler().performTicks(5);
        List<String> seen = messages(steve);
        assertFalse(seen.stream().anyMatch(line -> line.contains("spam") || line.contains("from elsewhere")), seen.toString());
    }

    @Test
    void suggestsNamesTimesAndReasons() {
        ready();
        server.addPlayer("Griefer");
        server.addPlayer("Grace");
        JsonObject data = JsonParser.parseString("{\"name\":\"ban\",\"options\":[{\"name\":\"player\",\"type\":3,"
                + "\"value\":\"gri\",\"focused\":true}]}").getAsJsonObject();
        JsonObject players = interaction(4, data, BOB, "Bob", true, "650000000000000001");
        dispatch("INTERACTION_CREATE", players);
        JsonObject answer = callback(players);
        assertEquals(8, answer.get("type").getAsInt());
        assertEquals("[{\"name\":\"Griefer\",\"value\":\"Griefer\"}]",
                answer.getAsJsonObject("data").getAsJsonArray("choices").toString());

        JsonObject reasonData = JsonParser.parseString("{\"name\":\"ban\",\"options\":[{\"name\":\"player\",\"type\":3,"
                + "\"value\":\"Griefer\"},{\"name\":\"reason\",\"type\":3,\"value\":\"che\",\"focused\":true}]}")
                .getAsJsonObject();
        JsonObject reasons = interaction(4, reasonData, BOB, "Bob", true, "650000000000000001");
        dispatch("INTERACTION_CREATE", reasons);
        assertTrue(callback(reasons).toString().contains("\"Cheating\""));

        JsonObject member = interaction(4, data, ALICE, "Alice", false, "650000000000000001");
        dispatch("INTERACTION_CREATE", member);
        assertEquals("[]", callback(member).getAsJsonObject("data").getAsJsonArray("choices").toString(),
                "members who are not staff get no suggestions");
    }

    @Test
    void otherServersAreRefused() {
        ready();
        JsonObject foreign = interaction(2, command("online"), BOB, "Bob", true, "650000000000000001");
        foreign.addProperty("guild_id", ELSEWHERE);
        dispatch("INTERACTION_CREATE", foreign);
        assertTrue(callback(foreign).getAsJsonObject("data").get("content").getAsString().contains("its own server"));
    }

    @Test
    void remoteStaffCollectAnswers() {
        List<String> output = new ArrayList<>();
        CommandSender sender = RemoteSender.create("Bob (Discord)", output);
        sender.sendMessage("one");
        sender.sendMessage(new String[] {"two", "three"});
        assertEquals(List.of("one", "two", "three"), output);
        assertEquals("Bob (Discord)", sender.getName());
        assertTrue(sender.hasPermission("vigil.ban"));
        assertFalse(sender.isOp());
        assertEquals(sender, sender);
        assertNotEquals(sender, RemoteSender.create("Bob (Discord)", output));
        assertEquals(System.identityHashCode(sender), sender.hashCode());
    }

    @Test
    void channelNamesAndTranscripts() {
        assertEquals("steve-123", DiscordTickets.slug("Steve_123"));
        assertEquals("player", DiscordTickets.slug("___"));
        assertEquals("abcdefghijklmnopqrst", DiscordTickets.slug("abcdefghijklmnopqrstuvwxyz"));
        Ticket ticket = plugin.tickets().open(TicketCategory.SUPPORT, null, "bob", BOB, null, null, null, "Hi",
                TicketMessage.Origin.FORM);
        String transcript = DiscordTickets.transcript(ticket);
        assertTrue(transcript.startsWith("Ticket #1 - Support\nOpened by bob (Discord)"), transcript);
        assertTrue(transcript.contains("] bob: Hi"), transcript);
        assertEquals("**a\\_b** (staff) (in game): x", DiscordTickets.format("a_b", true, TicketMessage.Origin.GAME, "x"));
    }
}
