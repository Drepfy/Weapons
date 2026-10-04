package io.github.drepfy.vigil.discord;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.drepfy.vigil.config.Settings;
import io.github.drepfy.vigil.moderation.DiscordNotifier;
import io.github.drepfy.vigil.moderation.ModerationService;
import io.github.drepfy.vigil.ticket.TicketService;
import io.github.drepfy.vigil.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Discord bot: posts punishments and alerts, runs ticket channels, gives staff slash
 * commands and bridges chat. Off unless {@code discord.bot} has a token and a server.
 *
 * <p>Threads: Discord events arrive on the gateway thread and anything that touches the
 * game or the tickets is moved to the server thread; requests to Discord are sent on the
 * REST thread. Nothing here ever makes the server wait for Discord.
 */
public final class DiscordBot implements Listener, DiscordNotifier.BotChannel {

    static final long ADMINISTRATOR = 1L << 3;
    static final long MANAGE_CHANNELS = 1L << 4;
    static final long VIEW_CHANNEL = 1L << 10;
    static final long SEND_MESSAGES = 1L << 11;
    static final long EMBED_LINKS = 1L << 14;
    static final long ATTACH_FILES = 1L << 15;
    static final long READ_HISTORY = 1L << 16;
    static final long MENTION_EVERYONE = 1L << 17;
    /** What the bot needs: see channels, post, create and delete ticket channels, mention staff roles. */
    static final long INVITE_PERMISSIONS = VIEW_CHANNEL | SEND_MESSAGES | EMBED_LINKS | ATTACH_FILES | READ_HISTORY
            | MANAGE_CHANNELS | MENTION_EVERYONE;

    private static final long WARNING_INTERVAL_MS = 5 * 60_000L;
    private static final int CHAT_MESSAGE_LIMIT = 1900;
    private static final int QUEUE_LIMIT = 200;
    private static final Pattern MENTION = Pattern.compile("<(@!?|@&|#)(\\d+)>");
    private static final Pattern EMOJI = Pattern.compile("<a?:(\\w+):\\d+>");

    /** One run of the bot with one token; replaced when the token or server changes. */
    static final class Session {
        final String token;
        final String serverId;
        final boolean readMessages;
        final DiscordRest rest;
        DiscordGateway gateway;
        volatile String applicationId;
        volatile String botUserId;
        volatile boolean ready;

        Session(Settings.Bot config, DiscordRest rest) {
            this.token = config.token();
            this.serverId = config.serverId();
            this.readMessages = config.readMessages();
            this.rest = rest;
        }

        boolean matches(Settings.Bot config) {
            return config.active() && token.equals(config.token()) && serverId.equals(config.serverId())
                    && readMessages == config.readMessages();
        }
    }

    private final JavaPlugin plugin;
    private final Supplier<Settings> settings;
    private final ModerationService moderation;
    private final TicketService tickets;
    private final DoubleSupplier tps;
    private final Logger logger;
    private final String restBase;
    private final DiscordGateway.Connector connector;
    private final DiscordInteractions interactions;
    private final DiscordTickets ticketChannels;
    private final Map<String, Long> warned = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<String> chat = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<JsonObject> alerts = new ConcurrentLinkedQueue<>();
    private volatile Session session;
    private BukkitTask task;
    private int taskRuns;
    private String lastStatus;
    private boolean announcedStart;

    public DiscordBot(JavaPlugin plugin, Supplier<Settings> settings, ModerationService moderation, TicketService tickets,
                      DoubleSupplier tps, Logger logger) {
        this(plugin, settings, moderation, tickets, tps, logger, DiscordRest.API, new DiscordGateway.JdkConnector());
    }

    DiscordBot(JavaPlugin plugin, Supplier<Settings> settings, ModerationService moderation, TicketService tickets,
               DoubleSupplier tps, Logger logger, String restBase, DiscordGateway.Connector connector) {
        this.plugin = plugin;
        this.settings = settings;
        this.moderation = moderation;
        this.tickets = tickets;
        this.tps = tps;
        this.logger = logger;
        this.restBase = restBase;
        this.connector = connector;
        this.interactions = new DiscordInteractions(this);
        this.ticketChannels = new DiscordTickets(this);
        tickets.addObserver(ticketChannels);
    }

    // ---- lifecycle (server thread) ----------------------------------------------------------------

    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
        apply();
    }

    /** After /ac reload: reconnects only when the token, server or read-messages changed. */
    public void reload() {
        apply();
        updatePresence(true);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        Session current = session;
        session = null;
        if (current == null) {
            return;
        }
        flush(current);
        String chatChannel = config().chatChannel();
        if (announcedStart && !chatChannel.isEmpty()) {
            post(current, chatChannel, Json.message("The server has stopped."), "chat-channel");
        }
        current.gateway.stop();
        current.rest.shutdown(3000);
    }

    private void apply() {
        Settings.Bot config = config();
        Session current = session;
        if (current != null && current.matches(config)) {
            return;
        }
        if (current != null) {
            session = null;
            current.gateway.stop();
            current.rest.shutdown(0);
            logger.info("Discord: the bot settings changed, reconnecting.");
        }
        if (!config.active()) {
            return;
        }
        Session next = new Session(config, new DiscordRest(restBase, config.token()));
        int intents = DiscordGateway.GUILDS
                | (config.readMessages() ? DiscordGateway.GUILD_MESSAGES | DiscordGateway.MESSAGE_CONTENT : 0);
        lastStatus = statusText();
        next.gateway = new DiscordGateway(config.token(), intents, DiscordGateway.presence(lastStatus),
                new Handler(next), connector, logger);
        session = next;
        next.gateway.start();
        logger.info("Discord: connecting the bot...");
    }

    /** Every second: send queued chat and alerts; every minute: refresh the player count. */
    private void tick() {
        Session current = session;
        if (current == null) {
            return;
        }
        flush(current);
        if (++taskRuns % 60 == 0) {
            updatePresence(false);
        }
    }

    private void updatePresence(boolean force) {
        Session current = session;
        if (current == null) {
            return;
        }
        String text = statusText();
        if (force || !text.equals(lastStatus)) {
            lastStatus = text;
            current.gateway.updatePresence(DiscordGateway.presence(text));
        }
    }

    private String statusText() {
        return config().status().replace("{online}", String.valueOf(Bukkit.getOnlinePlayers().size()));
    }

    // ---- gateway events (gateway thread) --------------------------------------------------------------

    private final class Handler implements DiscordGateway.Handler {
        private final Session owner;

        Handler(Session owner) {
            this.owner = owner;
        }

        @Override
        public void onReady(JsonObject ready) {
            if (session == owner) {
                DiscordBot.this.ready(owner, ready);
            }
        }

        @Override
        public void onDispatch(String type, JsonObject data) {
            if (session == owner) {
                dispatch(owner, type, data);
            }
        }

        @Override
        public void onFatal(String reason) {
            owner.ready = false;
            logger.severe("Discord: the bot stopped: " + reason);
        }

        @Override
        public void onMessageContentDenied() {
            logger.warning("Discord: \"Message Content Intent\" is switched off in the Discord developer portal, so the "
                    + "bot cannot read messages in ticket and chat channels. Switch it on (your application > Bot), "
                    + "or set discord.bot.read-messages to false. Tickets can still be answered with /reply.");
        }
    }

    void ready(Session owner, JsonObject ready) {
        JsonObject user = Json.obj(ready, "user");
        JsonObject application = Json.obj(ready, "application");
        owner.botUserId = Json.str(user, "id");
        owner.applicationId = application != null ? Json.str(application, "id") : owner.botUserId;
        owner.ready = true;
        boolean inServer = false;
        for (JsonElement guild : Json.arr(ready, "guilds")) {
            if (guild.isJsonObject() && owner.serverId.equals(Json.str(guild.getAsJsonObject(), "id"))) {
                inServer = true;
            }
        }
        logger.info("Discord: the bot is online as " + Json.str(user, "username") + ".");
        if (!inServer) {
            logger.warning("Discord: the bot is not in the server " + owner.serverId + " (discord.bot.server-id). "
                    + "Invite it with this link: " + inviteUrl(owner));
        }
        interactions.register(owner);
        sync(() -> {
            ticketChannels.resync(owner);
            String chatChannel = config().chatChannel();
            if (!announcedStart && !chatChannel.isEmpty()) {
                announcedStart = true;
                post(owner, chatChannel, Json.message("The server is online."), "chat-channel");
            }
        });
    }

    void dispatch(Session owner, String type, JsonObject data) {
        try {
            switch (type) {
                case "INTERACTION_CREATE" -> interactions.handle(owner, data);
                case "MESSAGE_CREATE" -> message(owner, data);
                case "CHANNEL_DELETE" -> ticketChannels.channelDeleted(Json.str(data, "id"));
                default -> {
                    // Not used.
                }
            }
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "Discord: could not handle " + type, e);
        }
    }

    /** A message typed on Discord: chat channel or ticket channel. */
    private void message(Session owner, JsonObject data) {
        JsonObject author = Json.obj(data, "author");
        if (author == null || !owner.serverId.equals(Json.str(data, "guild_id")) || data.has("webhook_id")
                || "true".equals(Json.str(author, "bot"))) {
            return;
        }
        String type = Json.str(data, "type");
        if (type != null && !type.equals("0") && !type.equals("19")) {
            return; // Joins, pins and other system messages.
        }
        String text = messageText(data);
        String channel = Json.str(data, "channel_id");
        if (text.isEmpty() || channel == null) {
            return;
        }
        String name = displayName(Json.obj(data, "member"), author);
        if (channel.equals(config().chatChannel())) {
            sync(() -> chatFromDiscord(name, text));
            return;
        }
        Integer ticket = tickets.ticketForChannel(channel);
        if (ticket != null) {
            String authorId = Json.str(author, "id");
            sync(() -> ticketChannels.messageFromDiscord(ticket, authorId, name, text));
        }
    }

    /** The text of a message as players should see it: mentions as names, attachments as links. */
    static String messageText(JsonObject data) {
        String content = Json.str(data, "content");
        content = content == null ? "" : content;
        Map<String, String> names = new java.util.HashMap<>();
        for (JsonElement element : Json.arr(data, "mentions")) {
            if (element.isJsonObject()) {
                JsonObject user = element.getAsJsonObject();
                names.put(Json.str(user, "id"), displayName(Json.obj(user, "member"), user));
            }
        }
        Matcher matcher = MENTION.matcher(content);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String kind = matcher.group(1);
            String replacement = switch (kind) {
                case "#" -> "#channel";
                case "@&" -> "@role";
                default -> "@" + names.getOrDefault(matcher.group(2), "user");
            };
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        String text = EMOJI.matcher(out.toString()).replaceAll(":$1:");
        StringBuilder result = new StringBuilder(Text.plain(text, 1000));
        for (JsonElement element : Json.arr(data, "attachments")) {
            if (element.isJsonObject()) {
                String url = Json.str(element.getAsJsonObject(), "url");
                if (url != null) {
                    result.append(result.length() == 0 ? "" : " ").append(url);
                }
            }
        }
        return result.toString().trim();
    }

    /** Server nickname, else display name, else user name. */
    static String displayName(JsonObject member, JsonObject user) {
        String nick = Json.str(member, "nick");
        if (nick != null && !nick.isBlank()) {
            return Text.plain(nick, 32);
        }
        String global = Json.str(user, "global_name");
        if (global != null && !global.isBlank()) {
            return Text.plain(global, 32);
        }
        String username = Json.str(user, "username");
        return username != null ? Text.plain(username, 32) : "unknown";
    }

    /** Staff: a staff role, or Administrator. */
    boolean isStaff(JsonObject member) {
        if (member == null) {
            return false;
        }
        String permissions = Json.str(member, "permissions");
        if (permissions != null) {
            try {
                if (new BigInteger(permissions).testBit(3)) {
                    return true;
                }
            } catch (NumberFormatException ignored) {
                // Roles below.
            }
        }
        List<String> staffRoles = config().staffRoles();
        for (JsonElement role : Json.arr(member, "roles")) {
            if (role.isJsonPrimitive() && staffRoles.contains(role.getAsString())) {
                return true;
            }
        }
        return false;
    }

    // ---- punishments, alerts and chat --------------------------------------------------------------------

    @Override
    public boolean postPunishment(JsonObject embed) {
        Session current = session;
        String channel = config().punishmentsChannel();
        if (current == null || channel.isEmpty()) {
            return false;
        }
        post(current, channel, Json.embedMessage(embed), "punishments-channel");
        return true;
    }

    @Override
    public boolean postAlert(JsonObject embed) {
        Session current = session;
        if (current == null || config().alertsChannel().isEmpty()) {
            return false;
        }
        if (alerts.size() < QUEUE_LIMIT) {
            alerts.add(embed);
        }
        return true;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        queueChat("**" + Json.escape(event.getPlayer().getName()) + ":** " + Text.plain(event.getMessage(), 500));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (!vanished(event.getPlayer())) {
            queueChat("**" + Json.escape(event.getPlayer().getName()) + "** joined the server.");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        if (!vanished(event.getPlayer())) {
            queueChat("**" + Json.escape(event.getPlayer().getName()) + "** left the server.");
        }
    }

    private void queueChat(String line) {
        if (session != null && !config().chatChannel().isEmpty() && chat.size() < QUEUE_LIMIT) {
            chat.add(line);
        }
    }

    /** Vanish plugins mark hidden staff with the "vanished" metadata. */
    private static boolean vanished(Player player) {
        for (MetadataValue value : player.getMetadata("vanished")) {
            if (value.asBoolean()) {
                return true;
            }
        }
        return false;
    }

    /** Chat lines are joined into as few messages as possible; alerts go ten to a message. */
    private void flush(Session current) {
        String chatChannel = config().chatChannel();
        StringBuilder block = new StringBuilder();
        String line;
        while ((line = chat.poll()) != null) {
            if (chatChannel.isEmpty()) {
                continue;
            }
            if (block.length() > 0 && block.length() + line.length() + 1 > CHAT_MESSAGE_LIMIT) {
                post(current, chatChannel, Json.message(block.toString()), "chat-channel");
                block.setLength(0);
            }
            block.append(block.length() == 0 ? "" : "\n").append(line);
        }
        if (block.length() > 0) {
            post(current, chatChannel, Json.message(block.toString()), "chat-channel");
        }
        String alertsChannel = config().alertsChannel();
        while (!alerts.isEmpty()) {
            com.google.gson.JsonArray embeds = new com.google.gson.JsonArray();
            JsonObject embed;
            while (embeds.size() < 10 && (embed = alerts.poll()) != null) {
                embeds.add(embed);
            }
            if (alertsChannel.isEmpty()) {
                continue;
            }
            JsonObject message = new JsonObject();
            message.add("embeds", embeds);
            message.add("allowed_mentions", Json.noMentions());
            post(current, alertsChannel, message, "alerts-channel");
        }
    }

    /** Discord chat shown to everyone in game. Server thread. */
    private void chatFromDiscord(String name, String text) {
        String line = Text.colorWith(settings.get().messages().get("discord-chat"), "name", name,
                "message", Text.plain(text, 256));
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendMessage(line);
        }
        logger.info(ChatColor.stripColor(line));
    }

    // ---- shared helpers ------------------------------------------------------------------------------------

    /** Runs a punishment command for a Discord staff member and returns what it answered. Server thread. */
    String runCommand(String staffName, String name, List<String> args) {
        PluginCommand command = plugin.getCommand(name);
        if (command == null || command.getExecutor() == null) {
            return "This command is not available.";
        }
        List<String> output = new ArrayList<>();
        command.getExecutor().onCommand(RemoteSender.create(staffName, output), command, name,
                args.toArray(new String[0]));
        String prefix = ChatColor.stripColor(Text.color(settings.get().messages().get("prefix"))).trim();
        StringBuilder out = new StringBuilder();
        for (String line : output) {
            String plain = ChatColor.stripColor(line).trim();
            if (!prefix.isEmpty() && plain.startsWith(prefix)) {
                plain = plain.substring(prefix.length()).trim();
            }
            out.append(plain).append('\n');
        }
        return out.length() == 0 ? "Done." : out.toString().trim();
    }

    void post(Session current, String channel, JsonObject message, String what) {
        current.rest.request("POST", "/channels/" + channel + "/messages", message)
                .exceptionally(error -> {
                    warn(what, "could not post in discord.bot." + what + ": " + describe(error));
                    return null;
                });
    }

    /** Runs on the server thread (dropped while the plugin is shutting down). */
    void sync(Runnable task) {
        try {
            if (plugin.isEnabled()) {
                Bukkit.getScheduler().runTask(plugin, task);
            }
        } catch (RuntimeException ignored) {
            // Disabled meanwhile.
        }
    }

    /** Logs a problem at most every few minutes per kind, so a broken setting does not flood the console. */
    void warn(String key, String message) {
        long now = System.currentTimeMillis();
        Long last = warned.get(key);
        if (last == null || now - last > WARNING_INTERVAL_MS) {
            warned.put(key, now);
            logger.warning("Discord: " + message);
        }
    }

    /** A Discord error in plain words, with what to do about it. */
    static String describe(Throwable error) {
        Throwable cause = error;
        while ((cause instanceof CompletionException || cause instanceof java.util.concurrent.ExecutionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof DiscordRest.DiscordException discord) {
            return switch (discord.code()) {
                case 50013 -> "the bot is missing a permission there (Missing Permissions)";
                case 50001 -> "the bot cannot see that channel (Missing Access)";
                case 10003 -> "that channel does not exist (check the channel ID)";
                case 10004 -> "the bot is not in that server (check discord.bot.server-id)";
                default -> discord.status() == 401 ? "the bot token is wrong (discord.bot.token)"
                        : discord.getMessage() + (discord.code() != 0 ? " (code " + discord.code() + ")" : "");
            };
        }
        if (cause instanceof RejectedExecutionException) {
            return "too many messages are waiting to be sent";
        }
        return cause == null ? "unknown error" : String.valueOf(cause.getMessage() != null ? cause.getMessage() : cause);
    }

    static String inviteUrl(Session current) {
        return "https://discord.com/oauth2/authorize?client_id=" + current.applicationId
                + "&scope=bot+applications.commands&permissions=" + INVITE_PERMISSIONS;
    }

    CompletableFuture<JsonElement> request(Session current, String method, String path, JsonElement body) {
        return current.rest.request(method, path, body);
    }

    Settings.Bot config() {
        return settings.get().discord().bot();
    }

    Settings settings() {
        return settings.get();
    }

    Session session() {
        return session;
    }

    JavaPlugin plugin() {
        return plugin;
    }

    ModerationService moderation() {
        return moderation;
    }

    TicketService tickets() {
        return tickets;
    }

    DiscordTickets ticketChannels() {
        return ticketChannels;
    }

    double tps() {
        return tps.getAsDouble();
    }

    Logger logger() {
        return logger;
    }
}
