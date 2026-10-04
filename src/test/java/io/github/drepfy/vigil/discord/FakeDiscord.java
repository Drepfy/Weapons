package io.github.drepfy.vigil.discord;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

/** A local stand-in for Discord's HTTP API that records every request. */
final class FakeDiscord implements AutoCloseable {

    record Request(String method, String path, String authorization, String contentType, String body) {
        JsonObject json() {
            return JsonParser.parseString(body).getAsJsonObject();
        }

        JsonElement jsonElement() {
            return JsonParser.parseString(body);
        }
    }

    /** A prepared answer for the next request that matches. */
    record Answer(Predicate<Request> matches, int status, String body, String[] headers) {
    }

    private final HttpServer server;
    private final List<Request> requests = new ArrayList<>();
    private final Deque<Answer> answers = new ArrayDeque<>();
    private final AtomicLong ids = new AtomicLong(900_000_000_000_000_000L);

    FakeDiscord() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v10";
    }

    /** The next request matching {@code matches} gets this answer instead of the default one. */
    synchronized void answer(Predicate<Request> matches, int status, String body, String... headers) {
        answers.add(new Answer(matches, status, body, headers));
    }

    synchronized List<Request> requests() {
        return new ArrayList<>(requests);
    }

    synchronized List<Request> requests(String method, String pathPart) {
        List<Request> result = new ArrayList<>();
        for (Request request : requests) {
            if (request.method().equals(method) && request.path().contains(pathPart)) {
                result.add(request);
            }
        }
        return result;
    }

    synchronized void clear() {
        requests.clear();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String path = exchange.getRequestURI().getPath().replaceFirst("^/api/v10", "");
        Request request = new Request(exchange.getRequestMethod(), path,
                exchange.getRequestHeaders().getFirst("Authorization"),
                exchange.getRequestHeaders().getFirst("Content-Type"), body);
        Answer answer = null;
        synchronized (this) {
            requests.add(request);
            for (Answer candidate : answers) {
                if (candidate.matches().test(request)) {
                    answer = candidate;
                    break;
                }
            }
            if (answer != null) {
                answers.remove(answer);
            }
        }
        int status = 200;
        String response = "{}";
        if (answer != null) {
            status = answer.status();
            response = answer.body();
            for (int i = 0; i + 1 < answer.headers().length; i += 2) {
                exchange.getResponseHeaders().add(answer.headers()[i], answer.headers()[i + 1]);
            }
        } else if (request.method().equals("POST") && (path.matches("/guilds/\\d+/channels")
                || path.matches("/channels/\\d+/messages"))) {
            response = "{\"id\":\"" + ids.incrementAndGet() + "\"}";
        } else if (request.method().equals("DELETE") || path.endsWith("/callback")) {
            status = 204;
            response = "";
        }
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, status == 204 ? -1 : bytes.length);
        if (status != 204) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
        exchange.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
