package io.github.drepfy.vigil.discord;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscordRestTest {

    private FakeDiscord discord;
    private DiscordRest rest;

    @BeforeEach
    void setUp() throws Exception {
        discord = new FakeDiscord();
        rest = new DiscordRest(discord.url(), "secret-token");
    }

    @AfterEach
    void tearDown() {
        rest.shutdown(1000);
        discord.close();
    }

    private static JsonElement await(java.util.concurrent.CompletableFuture<JsonElement> future) throws Exception {
        return future.get(10, TimeUnit.SECONDS);
    }

    @Test
    void sendsJsonWithTheBotToken() throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("content", "Hello \"world\"");
        JsonElement answer = await(rest.request("POST", "/channels/123456789012345678/messages", body));
        assertTrue(answer.getAsJsonObject().has("id"), answer.toString());
        FakeDiscord.Request request = discord.requests().get(0);
        assertEquals("Bot secret-token", request.authorization());
        assertEquals("application/json", request.contentType());
        assertEquals("Hello \"world\"", request.json().get("content").getAsString());
        assertEquals("/channels/123456789012345678/messages", request.path());
    }

    @Test
    void waitsAndRetriesWhenRateLimited() throws Exception {
        discord.answer(request -> true, 429, "{\"message\":\"You are being rate limited.\",\"retry_after\":0.2,\"global\":false}");
        long start = System.currentTimeMillis();
        await(rest.request("POST", "/channels/1/messages", new JsonObject()));
        assertTrue(System.currentTimeMillis() - start >= 180, "waited for retry_after");
        assertEquals(2, discord.requests().size(), "sent again after the wait");
    }

    @Test
    void waitsWhenARouteHasNoRequestsLeft() throws Exception {
        discord.answer(request -> true, 200, "{}", "X-RateLimit-Remaining", "0", "X-RateLimit-Reset-After", "0.3");
        await(rest.request("POST", "/channels/1/messages", new JsonObject()));
        long start = System.currentTimeMillis();
        await(rest.request("POST", "/channels/1/messages", new JsonObject()));
        assertTrue(System.currentTimeMillis() - start >= 250, "waited until the route reset");
        // Other routes are not held up.
        start = System.currentTimeMillis();
        await(rest.request("POST", "/channels/2/messages", new JsonObject()));
        assertTrue(System.currentTimeMillis() - start < 250);
    }

    @Test
    void reportsDiscordErrorsInPlainWords() {
        discord.answer(request -> true, 403, "{\"message\":\"Missing Permissions\",\"code\":50013}");
        ExecutionException error = assertThrows(ExecutionException.class,
                () -> await(rest.request("POST", "/channels/1/messages", new JsonObject())));
        DiscordRest.DiscordException discordError = assertInstanceOf(DiscordRest.DiscordException.class, error.getCause());
        assertEquals(50013, discordError.code());
        assertEquals(403, discordError.status());
        assertTrue(DiscordBot.describe(error).contains("missing a permission"), DiscordBot.describe(error));

        discord.answer(request -> true, 401, "{\"message\":\"401: Unauthorized\",\"code\":0}");
        ExecutionException unauthorized = assertThrows(ExecutionException.class,
                () -> await(rest.request("GET", "/users/@me", null)));
        assertTrue(DiscordBot.describe(unauthorized).contains("token is wrong"));
    }

    @Test
    void retriesWhenDiscordIsBrieflyUnavailable() throws Exception {
        discord.answer(request -> true, 502, "<html>Bad Gateway</html>");
        JsonElement answer = await(rest.request("POST", "/channels/1/messages", new JsonObject()));
        assertTrue(answer.getAsJsonObject().has("id"));
        assertEquals(2, discord.requests().size());
    }

    @Test
    void uploadsATextFile() throws Exception {
        JsonObject payload = new JsonObject();
        payload.addProperty("content", "Transcript");
        await(rest.upload("/channels/1/messages", payload, "ticket-3.txt", "[12:00] Steve: hi\n"));
        FakeDiscord.Request request = discord.requests().get(0);
        assertTrue(request.contentType().startsWith("multipart/form-data; boundary="), request.contentType());
        String body = request.body();
        assertTrue(body.contains("name=\"payload_json\""), body);
        assertTrue(body.contains("\"attachments\":[{\"id\":0,\"filename\":\"ticket-3.txt\"}]"), body);
        assertTrue(body.contains("name=\"files[0]\"; filename=\"ticket-3.txt\""), body);
        assertTrue(body.contains("[12:00] Steve: hi"), body);
        assertTrue(body.trim().endsWith("--"), "closing boundary");
    }

    @Test
    void emptyAnswersAreFine() throws Exception {
        JsonElement answer = await(rest.request("DELETE", "/channels/1", null));
        assertTrue(answer.isJsonNull());
        List<FakeDiscord.Request> requests = discord.requests("DELETE", "/channels/1");
        assertEquals(1, requests.size());
    }
}
