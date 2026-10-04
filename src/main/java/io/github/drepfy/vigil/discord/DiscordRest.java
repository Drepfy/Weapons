package io.github.drepfy.vigil.discord;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Discord's HTTP API, called one request at a time on a background thread so the
 * server never waits for Discord. Follows Discord's rate limits: waits when a route
 * has no requests left and retries after a 429 ("too many requests").
 */
public final class DiscordRest {

    public static final String API = "https://discord.com/api/v10";
    private static final String USER_AGENT = "DiscordBot (https://github.com/drepfy/weapons, 2)";
    private static final int ATTEMPTS = 3;
    /** A rate limit longer than this is not waited for; the request fails instead. */
    private static final long MAX_WAIT_MS = 60_000L;

    /** Discord refused a request. {@link #code()} is Discord's error code (e.g. 50013 Missing Permissions). */
    public static final class DiscordException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final int status;
        private final int code;

        DiscordException(int status, int code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }

        public int status() {
            return status;
        }

        public int code() {
            return code;
        }
    }

    private final String base;
    private final String token;
    private final HttpClient client;
    private final ThreadPoolExecutor executor;
    /** Per route: when requests may be sent again. */
    private final Map<String, Long> resumeAt = new ConcurrentHashMap<>();

    public DiscordRest(String base, String token) {
        this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        this.token = token;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.executor = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1000), runnable -> {
            Thread thread = new Thread(runnable, "Vigil-Discord-REST");
            thread.setDaemon(true);
            return thread;
        });
        this.executor.allowCoreThreadTimeOut(true);
    }

    /**
     * Sends a request in the background.
     *
     * @param body JSON body, or {@code null}
     * @return Discord's answer ({@link JsonNull} when empty); fails with {@link DiscordException},
     *         an {@link IOException}, or {@link RejectedExecutionException} when too much is queued
     */
    public CompletableFuture<JsonElement> request(String method, String path, JsonElement body) {
        byte[] bytes = body == null ? null : body.toString().getBytes(StandardCharsets.UTF_8);
        return submit(method, path, bytes, body == null ? null : "application/json");
    }

    /** Sends a message with one text file attached (e.g. a ticket transcript). */
    public CompletableFuture<JsonElement> upload(String path, JsonObject payload, String fileName, String content) {
        String boundary = "vigil-" + UUID.randomUUID();
        JsonObject withAttachment = payload.deepCopy();
        com.google.gson.JsonArray attachments = new com.google.gson.JsonArray();
        JsonObject attachment = new JsonObject();
        attachment.addProperty("id", 0);
        attachment.addProperty("filename", fileName);
        attachments.add(attachment);
        withAttachment.add("attachments", attachments);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String safeName = fileName.replaceAll("[^A-Za-z0-9._-]", "_");
        write(out, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"payload_json\"\r\n"
                + "Content-Type: application/json\r\n\r\n" + withAttachment + "\r\n");
        write(out, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"files[0]\"; filename=\"" + safeName
                + "\"\r\nContent-Type: text/plain; charset=utf-8\r\n\r\n" + content + "\r\n");
        write(out, "--" + boundary + "--\r\n");
        return submit("POST", path, out.toByteArray(), "multipart/form-data; boundary=" + boundary);
    }

    /** Stops after the requests already queued (waits at most {@code waitMs}; 0 = finish in the background). */
    public void shutdown(long waitMs) {
        executor.shutdown();
        if (waitMs <= 0) {
            return;
        }
        try {
            if (!executor.awaitTermination(waitMs, TimeUnit.MILLISECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    private static void write(ByteArrayOutputStream out, String text) {
        out.writeBytes(text.getBytes(StandardCharsets.UTF_8));
    }

    private CompletableFuture<JsonElement> submit(String method, String path, byte[] body, String contentType) {
        CompletableFuture<JsonElement> result = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                try {
                    result.complete(send(method, path, body, contentType));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    result.completeExceptionally(e);
                } catch (Exception e) {
                    result.completeExceptionally(e);
                }
            });
        } catch (RejectedExecutionException e) {
            result.completeExceptionally(e);
        }
        return result;
    }

    /** Runs on the REST thread. */
    private JsonElement send(String method, String path, byte[] body, String contentType) throws Exception {
        String route = method + " " + path;
        for (int attempt = 1; ; attempt++) {
            Long until = resumeAt.get(route);
            if (until != null) {
                long wait = until - System.currentTimeMillis();
                if (wait > MAX_WAIT_MS) {
                    throw new DiscordException(429, 0, "rate limited for " + wait / 1000 + "s");
                }
                if (wait > 0) {
                    Thread.sleep(wait);
                }
                resumeAt.remove(route, until);
            }
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bot " + token)
                    .header("User-Agent", USER_AGENT)
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofByteArray(body));
            if (contentType != null) {
                request.header("Content-Type", contentType);
            }
            HttpResponse<String> response;
            try {
                response = client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            } catch (IOException e) {
                if (attempt >= ATTEMPTS) {
                    throw e;
                }
                Thread.sleep(1000L * attempt);
                continue;
            }
            int status = response.statusCode();
            String remaining = response.headers().firstValue("X-RateLimit-Remaining").orElse(null);
            String resetAfter = response.headers().firstValue("X-RateLimit-Reset-After").orElse(null);
            if ("0".equals(remaining) && resetAfter != null) {
                resumeAt.put(route, System.currentTimeMillis() + seconds(resetAfter));
            }
            JsonElement json = parse(response.body());
            if (status == 429) {
                long retry = retryAfter(json, response.headers().firstValue("Retry-After").orElse("1"));
                if (attempt >= ATTEMPTS || retry > MAX_WAIT_MS) {
                    throw new DiscordException(429, 0, "rate limited");
                }
                Thread.sleep(retry);
                continue;
            }
            if (status == 502 || status == 503 || status == 504) {
                if (attempt >= ATTEMPTS) {
                    throw new DiscordException(status, 0, "Discord is unavailable (" + status + ")");
                }
                Thread.sleep(1000L * attempt);
                continue;
            }
            if (status >= 400) {
                int code = 0;
                String message = "HTTP " + status;
                if (json.isJsonObject()) {
                    JsonObject error = json.getAsJsonObject();
                    if (error.has("code") && error.get("code").isJsonPrimitive()) {
                        code = error.get("code").getAsInt();
                    }
                    if (error.has("message") && error.get("message").isJsonPrimitive()) {
                        message = error.get("message").getAsString();
                    }
                }
                throw new DiscordException(status, code, message);
            }
            return json;
        }
    }

    private static JsonElement parse(String body) {
        if (body == null || body.isBlank()) {
            return JsonNull.INSTANCE;
        }
        try {
            return JsonParser.parseString(body);
        } catch (JsonParseException e) {
            return JsonNull.INSTANCE;
        }
    }

    private static long retryAfter(JsonElement json, String header) {
        if (json.isJsonObject() && json.getAsJsonObject().has("retry_after")) {
            try {
                return seconds(json.getAsJsonObject().get("retry_after").getAsString());
            } catch (RuntimeException ignored) {
                // Use the header.
            }
        }
        return seconds(header);
    }

    /** "1.5" seconds → 1500 ms (at least 100 ms). */
    private static long seconds(String text) {
        try {
            return Math.max(100L, (long) Math.ceil(Double.parseDouble(text.trim()) * 1000.0));
        } catch (NumberFormatException e) {
            return 1000L;
        }
    }
}
