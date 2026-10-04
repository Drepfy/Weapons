package io.github.drepfy.vigil.discord;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscordGatewayTest {

    private static final String URL = "ws://gateway.test/?v=10&encoding=json";

    /** A WebSocket that records what the bot sends and lets the test play Discord. */
    private static final class FakeConnection implements DiscordGateway.Connection {
        final URI uri;
        final DiscordGateway.Events events;
        final BlockingQueue<JsonObject> sent = new LinkedBlockingQueue<>();
        volatile int closedWith = -1;

        FakeConnection(URI uri, DiscordGateway.Events events) {
            this.uri = uri;
            this.events = events;
        }

        @Override
        public void send(String text) {
            sent.add(JsonParser.parseString(text).getAsJsonObject());
        }

        @Override
        public void close(int code, String reason) {
            closedWith = code;
        }

        void receive(String json) {
            events.onText(json);
        }

        /** The next payload with this opcode (others are skipped). */
        JsonObject next(int op) throws InterruptedException {
            long end = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < end) {
                JsonObject payload = sent.poll(100, TimeUnit.MILLISECONDS);
                if (payload != null && payload.get("op").getAsInt() == op) {
                    return payload;
                }
            }
            throw new AssertionError("op " + op + " was not sent");
        }
    }

    private final BlockingQueue<FakeConnection> connections = new LinkedBlockingQueue<>();
    private final BlockingQueue<JsonObject> readies = new LinkedBlockingQueue<>();
    private final BlockingQueue<String> dispatches = new LinkedBlockingQueue<>();
    private final BlockingQueue<String> fatal = new LinkedBlockingQueue<>();
    private final AtomicBoolean contentDenied = new AtomicBoolean();
    private DiscordGateway gateway;

    private final DiscordGateway.Handler handler = new DiscordGateway.Handler() {
        @Override
        public void onReady(JsonObject ready) {
            readies.add(ready);
        }

        @Override
        public void onDispatch(String type, JsonObject data) {
            dispatches.add(type);
        }

        @Override
        public void onFatal(String reason) {
            fatal.add(reason);
        }

        @Override
        public void onMessageContentDenied() {
            contentDenied.set(true);
        }
    };

    private DiscordGateway start(int intents) {
        DiscordGateway.Connector connector = (uri, events) -> {
            FakeConnection connection = new FakeConnection(uri, events);
            events.onOpen(connection);
            connections.add(connection);
            return CompletableFuture.completedFuture(connection);
        };
        gateway = new DiscordGateway(URL, "secret", intents, DiscordGateway.presence("Watching 3 players"), handler,
                connector, Logger.getLogger("test"));
        gateway.delayScale = 0.01;
        gateway.start();
        return gateway;
    }

    @AfterEach
    void tearDown() {
        if (gateway != null) {
            gateway.stop();
        }
    }

    private FakeConnection connection() throws InterruptedException {
        FakeConnection connection = connections.poll(5, TimeUnit.SECONDS);
        assertNotNull(connection, "connected");
        return connection;
    }

    /** Hello, identify and READY: a connected bot with session "abc" at sequence 5. */
    private FakeConnection ready() throws InterruptedException {
        FakeConnection connection = connection();
        connection.receive("{\"op\":10,\"d\":{\"heartbeat_interval\":60000}}");
        connection.next(2);
        connection.receive("{\"op\":0,\"s\":5,\"t\":\"READY\",\"d\":{\"session_id\":\"abc\","
                + "\"resume_gateway_url\":\"ws://resume.test\",\"user\":{\"id\":\"1\"}}}");
        assertNotNull(readies.poll(5, TimeUnit.SECONDS));
        return connection;
    }

    @Test
    void identifiesAndKeepsTheConnectionAlive() throws Exception {
        start(DiscordGateway.GUILDS);
        FakeConnection connection = connection();
        assertEquals(URI.create(URL), connection.uri);
        connection.receive("{\"op\":10,\"d\":{\"heartbeat_interval\":100}}");
        JsonObject identify = connection.next(2).getAsJsonObject("d");
        assertEquals("secret", identify.get("token").getAsString());
        assertEquals(DiscordGateway.GUILDS, identify.get("intents").getAsInt());
        assertEquals("Vigil", identify.getAsJsonObject("properties").get("browser").getAsString());
        assertEquals("Watching 3 players", identify.getAsJsonObject("presence").getAsJsonArray("activities").get(0)
                .getAsJsonObject().get("state").getAsString());

        JsonObject beat = connection.next(1);
        assertTrue(beat.get("d").isJsonNull(), "no sequence yet");
        connection.receive("{\"op\":11}");
        connection.receive("{\"op\":0,\"s\":5,\"t\":\"READY\",\"d\":{\"session_id\":\"abc\",\"user\":{\"id\":\"1\"}}}");
        assertNotNull(readies.poll(5, TimeUnit.SECONDS));
        connection.receive("{\"op\":0,\"s\":6,\"t\":\"MESSAGE_CREATE\",\"d\":{\"content\":\"hi\"}}");
        assertEquals("MESSAGE_CREATE", dispatches.poll(5, TimeUnit.SECONDS));

        // Discord may ask for a heartbeat at any time; it carries the last sequence number.
        connection.sent.clear();
        connection.receive("{\"op\":1}");
        assertEquals(6, connection.next(1).get("d").getAsInt());
        connection.receive("{\"op\":11}");
        assertEquals(-1, connection.closedWith, "a healthy connection stays open");
    }

    @Test
    void resumesWhenDiscordAsksToReconnect() throws Exception {
        start(DiscordGateway.GUILDS);
        FakeConnection first = ready();
        first.receive("{\"op\":7,\"d\":null}");
        FakeConnection second = connection();
        assertEquals(4000, first.closedWith, "closed so that the session can be resumed");
        assertEquals("ws://resume.test/?v=10&encoding=json", second.uri.toString());
        second.receive("{\"op\":10,\"d\":{\"heartbeat_interval\":60000}}");
        JsonObject resume = second.next(6).getAsJsonObject("d");
        assertEquals("abc", resume.get("session_id").getAsString());
        assertEquals(5, resume.get("seq").getAsInt());
        assertEquals("secret", resume.get("token").getAsString());
    }

    @Test
    void droppedConnectionsAreResumed() throws Exception {
        start(DiscordGateway.GUILDS);
        FakeConnection first = ready();
        first.events.onClosed(1006, "connection reset");
        FakeConnection second = connection();
        second.receive("{\"op\":10,\"d\":{\"heartbeat_interval\":60000}}");
        assertEquals("abc", second.next(6).getAsJsonObject("d").get("session_id").getAsString());
        // Discord could not resume: start a new session.
        second.receive("{\"op\":9,\"d\":false}");
        FakeConnection third = connection();
        assertEquals(URI.create(URL), third.uri, "a new session starts at the normal address");
        third.receive("{\"op\":10,\"d\":{\"heartbeat_interval\":60000}}");
        assertEquals("secret", third.next(2).getAsJsonObject("d").get("token").getAsString());
    }

    @Test
    void deadConnectionsAreNoticed() throws Exception {
        start(DiscordGateway.GUILDS);
        FakeConnection first = connection();
        first.receive("{\"op\":10,\"d\":{\"heartbeat_interval\":50}}");
        first.next(2);
        first.next(1);
        // No heartbeat ACK: the next heartbeat finds the connection dead and reconnects.
        FakeConnection second = connection();
        assertEquals(4000, first.closedWith);
        assertNotNull(second);
    }

    @Test
    void aWrongTokenStopsForGood() throws Exception {
        start(DiscordGateway.GUILDS);
        FakeConnection first = connection();
        first.events.onClosed(4004, "Authentication failed.");
        String reason = fatal.poll(5, TimeUnit.SECONDS);
        assertNotNull(reason);
        assertTrue(reason.contains("token"), reason);
        assertNull(connections.poll(300, TimeUnit.MILLISECONDS), "no reconnecting with a wrong token");
    }

    @Test
    void worksWithoutTheMessageContentIntent() throws Exception {
        int intents = DiscordGateway.GUILDS | DiscordGateway.GUILD_MESSAGES | DiscordGateway.MESSAGE_CONTENT;
        start(intents);
        FakeConnection first = connection();
        first.receive("{\"op\":10,\"d\":{\"heartbeat_interval\":60000}}");
        assertEquals(intents, first.next(2).getAsJsonObject("d").get("intents").getAsInt());
        first.events.onClosed(4014, "Disallowed intent(s).");
        FakeConnection second = connection();
        assertTrue(contentDenied.get(), "the owner is told how to fix it");
        second.receive("{\"op\":10,\"d\":{\"heartbeat_interval\":60000}}");
        assertEquals(DiscordGateway.GUILDS | DiscordGateway.GUILD_MESSAGES,
                second.next(2).getAsJsonObject("d").get("intents").getAsInt());
    }

    @Test
    void updatesTheStatusAndDisconnectsWhenStopped() throws Exception {
        start(DiscordGateway.GUILDS);
        FakeConnection connection = ready();
        gateway.updatePresence(DiscordGateway.presence("Watching 7 players"));
        assertEquals("Watching 7 players", connection.next(3).getAsJsonObject("d").getAsJsonArray("activities")
                .get(0).getAsJsonObject().get("state").getAsString());
        gateway.stop();
        assertEquals(1000, connection.closedWith);
        assertNull(connections.poll(200, TimeUnit.MILLISECONDS), "stays disconnected");
        gateway = null;
    }
}
