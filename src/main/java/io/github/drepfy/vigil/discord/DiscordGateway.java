package io.github.drepfy.vigil.discord;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * The bot's live connection to Discord (the "gateway", a WebSocket). Keeps it alive with
 * heartbeats, resumes after a dropped connection so no events are lost, and reconnects
 * with a growing delay when Discord is unreachable. Everything runs on one background
 * thread; {@link Handler} methods are called there.
 */
public final class DiscordGateway {

    public static final String URL = "wss://gateway.discord.gg/?v=10&encoding=json";

    /** Server list, channels and interactions. */
    public static final int GUILDS = 1;
    /** Messages in server channels. */
    public static final int GUILD_MESSAGES = 1 << 9;
    /** The text of messages; must be switched on in the developer portal. */
    public static final int MESSAGE_CONTENT = 1 << 15;

    private static final long[] BACKOFF_MS = {1_000, 2_000, 5_000, 10_000, 30_000, 60_000};
    /** Closing with a code other than 1000/1001 keeps the session so it can be resumed. */
    private static final int RESUMABLE_CLOSE = 4000;

    /** Receives what Discord sends. Called on the gateway thread. */
    public interface Handler {
        void onReady(JsonObject ready);

        void onDispatch(String type, JsonObject data);

        /** The connection cannot work (wrong token, intents not allowed...); it stopped for good. */
        void onFatal(String reason);

        /** The Message Content intent is not switched on; the bot continues without message text. */
        default void onMessageContentDenied() {
        }
    }

    /** Opens WebSocket connections; replaceable for tests. */
    public interface Connector {
        CompletableFuture<Connection> connect(URI uri, Events events);
    }

    public interface Connection {
        void send(String text);

        void close(int code, String reason);
    }

    /** What a connection reports, in this order. May be called on any thread. */
    public interface Events {
        /** Before any text: Discord sends its first message the moment the connection is open. */
        void onOpen(Connection connection);

        void onText(String text);

        void onClosed(int code, String reason);
    }

    private final String token;
    private final Handler handler;
    private final Connector connector;
    private final Logger logger;
    private final String url;
    private final ScheduledExecutorService thread;
    /** Shortens every wait (reconnect delays) in tests. */
    double delayScale = 1.0;

    // Gateway thread only.
    private int intents;
    private JsonObject presence;
    private Connection connection;
    /** Increases with every connection; events of older connections are ignored. */
    private int generation;
    private ScheduledFuture<?> heartbeat;
    private boolean acked = true;
    private long sequence = -1;
    private String sessionId;
    private String resumeUrl;
    private int failures;
    private boolean running;

    public DiscordGateway(String token, int intents, JsonObject presence, Handler handler, Connector connector,
                          Logger logger) {
        this(URL, token, intents, presence, handler, connector, logger);
    }

    DiscordGateway(String url, String token, int intents, JsonObject presence, Handler handler, Connector connector,
                   Logger logger) {
        this.url = url;
        this.token = token;
        this.intents = intents;
        this.presence = presence;
        this.handler = handler;
        this.connector = connector;
        this.logger = logger;
        this.thread = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread result = new Thread(runnable, "Vigil-Discord-Gateway");
            result.setDaemon(true);
            return result;
        });
    }

    public void start() {
        run(() -> {
            if (!running) {
                running = true;
                connect(false);
            }
        });
    }

    /** Disconnects and stops the thread. */
    public void stop() {
        run(() -> {
            running = false;
            generation++;
            stopHeartbeat();
            if (connection != null) {
                connection.close(1000, "Shutting down");
                connection = null;
            }
        });
        thread.shutdown();
        try {
            thread.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        thread.shutdownNow();
    }

    /** Changes the text under the bot's name. */
    public void updatePresence(JsonObject presence) {
        run(() -> {
            this.presence = presence;
            if (connection != null && sessionId != null) {
                send(payload(3, presence));
            }
        });
    }

    /** The intents in use (Message Content is dropped when Discord refuses it). */
    int intents() {
        return intents;
    }

    // ---- connection ---------------------------------------------------------------------------------

    private void connect(boolean resume) {
        if (!running) {
            return;
        }
        int current = ++generation;
        String target = resume && resumeUrl != null ? withQuery(resumeUrl) : url;
        Events events = new Events() {
            @Override
            public void onOpen(Connection opened) {
                run(() -> opened(current, opened));
            }

            @Override
            public void onText(String text) {
                run(() -> {
                    if (current == generation) {
                        receive(text);
                    }
                });
            }

            @Override
            public void onClosed(int code, String reason) {
                run(() -> {
                    if (current == generation) {
                        closed(code, reason);
                    }
                });
            }
        };
        CompletableFuture<Connection> opening;
        try {
            opening = connector.connect(URI.create(target), events);
        } catch (RuntimeException e) {
            opening = CompletableFuture.failedFuture(e);
        }
        opening.whenComplete((opened, error) -> run(() -> {
            if (error != null) {
                if (current == generation && running) {
                    logger.fine("Discord: could not connect: " + error);
                    reconnectLater(true);
                }
                return;
            }
            opened(current, opened);
        }));
    }

    /** Called when the connection opens and again when connecting finishes; the first call wins. */
    private void opened(int current, Connection opened) {
        if (current != generation || !running) {
            if (opened != connection) {
                opened.close(1000, "Not needed");
            }
            return;
        }
        if (connection == null) {
            connection = opened;
        }
    }

    private void receive(String text) {
        JsonObject payload;
        try {
            payload = JsonParser.parseString(text).getAsJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            return;
        }
        int op = payload.has("op") ? payload.get("op").getAsInt() : -1;
        JsonElement data = payload.has("d") ? payload.get("d") : JsonNull.INSTANCE;
        switch (op) {
            case 10 -> hello(data.getAsJsonObject());
            case 11 -> acked = true;
            case 1 -> beat();
            case 7 -> reconnectNow(true);
            case 9 -> {
                boolean resumable = data.isJsonPrimitive() && data.getAsBoolean();
                if (!resumable) {
                    sessionId = null;
                    sequence = -1;
                }
                // Discord asks for a short random wait before identifying again.
                dropConnection();
                schedule(() -> connect(resumable),
                        (long) (ThreadLocalRandom.current().nextLong(1000, 5000) * delayScale));
            }
            case 0 -> dispatch(payload, data);
            default -> {
                // Other opcodes are not sent to bots.
            }
        }
    }

    private void hello(JsonObject data) {
        long interval = data.get("heartbeat_interval").getAsLong();
        stopHeartbeat();
        acked = true;
        long first = (long) (interval * ThreadLocalRandom.current().nextDouble());
        heartbeat = thread.scheduleAtFixedRate(this::heartbeatTick, first, interval, TimeUnit.MILLISECONDS);
        if (sessionId != null && sequence >= 0) {
            JsonObject resume = new JsonObject();
            resume.addProperty("token", token);
            resume.addProperty("session_id", sessionId);
            resume.addProperty("seq", sequence);
            send(payload(6, resume));
        } else {
            JsonObject identify = new JsonObject();
            identify.addProperty("token", token);
            identify.addProperty("intents", intents);
            JsonObject properties = new JsonObject();
            properties.addProperty("os", System.getProperty("os.name", "unknown"));
            properties.addProperty("browser", "Vigil");
            properties.addProperty("device", "Vigil");
            identify.add("properties", properties);
            if (presence != null) {
                identify.add("presence", presence);
            }
            send(payload(2, identify));
        }
    }

    private void dispatch(JsonObject payload, JsonElement data) {
        if (payload.has("s") && !payload.get("s").isJsonNull()) {
            sequence = payload.get("s").getAsLong();
        }
        String type = payload.has("t") && !payload.get("t").isJsonNull() ? payload.get("t").getAsString() : "";
        if (!data.isJsonObject()) {
            return;
        }
        JsonObject object = data.getAsJsonObject();
        switch (type) {
            case "READY" -> {
                sessionId = object.get("session_id").getAsString();
                resumeUrl = object.has("resume_gateway_url") ? object.get("resume_gateway_url").getAsString() : null;
                failures = 0;
                handler.onReady(object);
            }
            case "RESUMED" -> failures = 0;
            default -> handler.onDispatch(type, object);
        }
    }

    private void heartbeatTick() {
        if (!acked) {
            // No answer to the last heartbeat: the connection is dead even if it looks open.
            reconnectNow(true);
            return;
        }
        acked = false;
        beat();
    }

    private void beat() {
        send("{\"op\":1,\"d\":" + (sequence >= 0 ? Long.toString(sequence) : "null") + "}");
    }

    private void closed(int code, String reason) {
        connection = null;
        stopHeartbeat();
        switch (code) {
            case 4004 -> fatal("the bot token is wrong (discord.bot.token). Reset it in the developer portal and paste the new one.");
            case 4010, 4011, 4012, 4013 -> fatal("Discord refused the connection (" + code + " " + reason + ").");
            case 4014 -> {
                if ((intents & MESSAGE_CONTENT) != 0) {
                    intents &= ~MESSAGE_CONTENT;
                    handler.onMessageContentDenied();
                    reconnectLater(false);
                } else {
                    fatal("Discord refused the requested intents (4014).");
                }
            }
            case 4007, 4009 -> {
                sessionId = null;
                sequence = -1;
                reconnectLater(false);
            }
            default -> reconnectLater(true);
        }
    }

    private void fatal(String reason) {
        running = false;
        generation++;
        handler.onFatal(reason);
    }

    /** Drops the connection and reconnects at once (Discord asked, or the connection died). */
    private void reconnectNow(boolean resume) {
        dropConnection();
        schedule(() -> connect(resume), 0L);
    }

    private void reconnectLater(boolean resume) {
        long delay = (long) (BACKOFF_MS[Math.min(failures, BACKOFF_MS.length - 1)] * delayScale);
        failures++;
        dropConnection();
        schedule(() -> connect(resume), delay);
    }

    private void dropConnection() {
        stopHeartbeat();
        generation++;
        if (connection != null) {
            connection.close(RESUMABLE_CLOSE, "Reconnecting");
            connection = null;
        }
    }

    private void stopHeartbeat() {
        if (heartbeat != null) {
            heartbeat.cancel(false);
            heartbeat = null;
        }
    }

    private void send(String text) {
        if (connection != null) {
            connection.send(text);
        }
    }

    private void schedule(Runnable task, long delayMs) {
        try {
            thread.schedule(task, delayMs, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // Stopped.
        }
    }

    private void run(Runnable task) {
        try {
            thread.execute(() -> {
                try {
                    task.run();
                } catch (RuntimeException e) {
                    logger.log(java.util.logging.Level.WARNING, "Discord: unexpected error", e);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // Stopped.
        }
    }

    static String payload(int op, JsonElement data) {
        JsonObject payload = new JsonObject();
        payload.addProperty("op", op);
        payload.add("d", data);
        return payload.toString();
    }

    private static String withQuery(String base) {
        String trimmed = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return trimmed.contains("?") ? trimmed : trimmed + "/?v=10&encoding=json";
    }

    /** The text under the bot's name. */
    public static JsonObject presence(String status) {
        JsonObject presence = new JsonObject();
        presence.add("since", JsonNull.INSTANCE);
        JsonArray activities = new JsonArray();
        if (status != null && !status.isBlank()) {
            JsonObject activity = new JsonObject();
            activity.addProperty("name", "Custom Status");
            activity.addProperty("type", 4);
            activity.addProperty("state", status.length() > 128 ? status.substring(0, 128) : status);
            activities.add(activity);
        }
        presence.add("activities", activities);
        presence.addProperty("status", "online");
        presence.addProperty("afk", false);
        return presence;
    }

    // ---- the real WebSocket ---------------------------------------------------------------------------

    /** Java's built-in WebSocket client. */
    public static final class JdkConnector implements Connector {
        private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

        @Override
        public CompletableFuture<Connection> connect(URI uri, Events events) {
            Listener listener = new Listener(events);
            return client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(15))
                    .buildAsync(uri, listener).thenApply(listener::connection);
        }
    }

    private static final class Listener implements WebSocket.Listener {
        private final Events events;
        private final StringBuilder buffer = new StringBuilder();
        private JdkConnection connection;
        private boolean closed;

        Listener(Events events) {
            this.events = events;
        }

        /** One connection object per socket, whoever asks first. */
        synchronized Connection connection(WebSocket socket) {
            if (connection == null) {
                connection = new JdkConnection(socket);
            }
            return connection;
        }

        @Override
        public void onOpen(WebSocket socket) {
            events.onOpen(connection(socket));
            socket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String text = buffer.toString();
                buffer.setLength(0);
                events.onText(text);
            }
            socket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket socket, java.nio.ByteBuffer data, boolean last) {
            socket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket socket, int code, String reason) {
            report(code, reason);
            return null;
        }

        @Override
        public void onError(WebSocket socket, Throwable error) {
            report(-1, String.valueOf(error));
        }

        private synchronized void report(int code, String reason) {
            if (!closed) {
                closed = true;
                events.onClosed(code, reason);
            }
        }
    }

    private static final class JdkConnection implements Connection {
        private final WebSocket socket;
        /** Java's WebSocket allows one send at a time; sends wait for the previous one. */
        private CompletableFuture<?> last = CompletableFuture.completedFuture(null);

        JdkConnection(WebSocket socket) {
            this.socket = socket;
        }

        @Override
        public synchronized void send(String text) {
            last = last.handle((ignored, error) -> null)
                    .thenCompose(ignored -> socket.sendText(text, true));
        }

        @Override
        public synchronized void close(int code, String reason) {
            last.handle((ignored, error) -> null)
                    .thenCompose(ignored -> socket.sendClose(code, reason))
                    .orTimeout(5, TimeUnit.SECONDS)
                    .whenComplete((ignored, error) -> socket.abort());
        }
    }
}
