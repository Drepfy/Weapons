package io.github.drepfy.vigil.moderation;

import io.github.drepfy.vigil.config.Settings;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Posts punishments (and, if enabled, anti-cheat alerts) to a Discord channel through a
 * webhook. Off until {@code discord.webhook} is set. Sending happens on a background
 * thread with a small queue; if Discord is slow or down, messages are dropped and the
 * server is never affected.
 */
public final class DiscordNotifier {

    private static final int RED = 0xE74C3C;
    private static final int DARK_RED = 0x992D22;
    private static final int ORANGE = 0xE67E22;
    private static final int YELLOW = 0xF1C40F;
    private static final int BLUE = 0x3498DB;
    private static final int GREEN = 0x2ECC71;
    private static final long ERROR_LOG_INTERVAL_MS = 60_000;

    private final Supplier<Settings> settings;
    private final Logger logger;
    private final ThreadPoolExecutor executor;
    private volatile HttpClient client;
    private volatile long lastErrorLogMs;

    public DiscordNotifier(Supplier<Settings> settings, Logger logger) {
        this.settings = settings;
        this.logger = logger;
        this.executor = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(64), runnable -> {
            Thread thread = new Thread(runnable, "Vigil-Discord");
            thread.setDaemon(true);
            return thread;
        });
        this.executor.allowCoreThreadTimeOut(true);
    }

    /** A punishment was given or lifted. */
    public void punishment(Punishment punishment) {
        Settings config = settings.get();
        Settings.Discord discord = config.discord();
        boolean autoBan = ModerationListener.ANTI_CHEAT_STAFF.equals(punishment.staff());
        if (!discord.enabled() || (autoBan ? !discord.autoBans() : !discord.punishments())) {
            return;
        }
        post(discord.webhookUrl(), punishmentJson(punishment, config.messages().get("permanent")));
    }

    /** An anti-cheat alert that staff saw in game. */
    public void alert(String player, String reason, String vl, String detail) {
        Settings.Discord discord = settings.get().discord();
        if (!discord.enabled() || !discord.alerts()) {
            return;
        }
        List<String[]> fields = new ArrayList<>();
        fields.add(new String[] {"VL", vl});
        fields.add(new String[] {"Evidence", detail});
        post(discord.webhookUrl(), json(player + " was flagged for " + reason, ORANGE, fields));
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    static String punishmentJson(Punishment p, String permanent) {
        List<String[]> fields = new ArrayList<>();
        String title;
        int color;
        boolean lifted = p.revoked();
        switch (p.type()) {
            case BAN -> {
                boolean auto = ModerationListener.ANTI_CHEAT_STAFF.equals(p.staff());
                title = lifted ? "Unbanned: " + p.name() : (auto ? "Anti-Cheat banned: " : "Banned: ") + p.name();
                color = lifted ? GREEN : auto ? DARK_RED : RED;
            }
            case MUTE -> {
                title = (lifted ? "Unmuted: " : "Muted: ") + p.name();
                color = lifted ? GREEN : ORANGE;
            }
            case WARN -> {
                title = (lifted ? "Warning removed: " : "Warned: ") + p.name();
                color = lifted ? GREEN : YELLOW;
            }
            default -> {
                title = "Kicked: " + p.name();
                color = BLUE;
            }
        }
        fields.add(new String[] {"Reason", p.reason()});
        if (p.type() == PunishmentType.BAN || p.type() == PunishmentType.MUTE
                || (p.type() == PunishmentType.WARN && p.durationMs() != 0L)) {
            fields.add(new String[] {"Length", Durations.format(p.durationMs(), permanent)});
        }
        fields.add(new String[] {"By", p.staff()});
        fields.add(new String[] {"ID", "#" + p.id()});
        if (lifted) {
            fields.add(new String[] {"Lifted by", p.revokedBy() + (p.revokeReason() != null ? ": " + p.revokeReason() : "")});
        }
        return json(title, color, fields);
    }

    static String json(String title, int color, List<String[]> fields) {
        StringBuilder json = new StringBuilder("{\"username\":\"Vigil\",\"allowed_mentions\":{\"parse\":[]},\"embeds\":[{");
        json.append("\"title\":").append(quote(title)).append(",\"color\":").append(color)
                .append(",\"timestamp\":").append(quote(Instant.now().toString())).append(",\"fields\":[");
        for (int i = 0; i < fields.size(); i++) {
            String[] field = fields.get(i);
            json.append(i == 0 ? "" : ",").append("{\"name\":").append(quote(field[0])).append(",\"value\":")
                    .append(quote(field[1] == null || field[1].isBlank() ? "-" : field[1])).append(",\"inline\":true}");
        }
        return json.append("]}]}").toString();
    }

    /** JSON string literal; colour codes are removed, text is cut to Discord's field limit. */
    static String quote(String text) {
        String clean = text.replaceAll("[&§][0-9a-fk-orA-FK-OR]", "");
        if (clean.length() > 1000) {
            clean = clean.substring(0, 1000) + "...";
        }
        StringBuilder out = new StringBuilder("\"");
        for (char c : clean.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    private void post(String url, String body) {
        try {
            executor.execute(() -> send(url, body));
        } catch (RejectedExecutionException e) {
            // Queue full or shutting down: drop it.
        }
    }

    private void send(String url, String body) {
        try {
            if (client == null) {
                client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            }
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() >= 300) {
                logError("Discord answered " + response.statusCode() + " (check discord.webhook)");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            logError("could not reach Discord: " + e.getMessage());
        }
    }

    private void logError(String message) {
        long now = System.currentTimeMillis();
        if (now - lastErrorLogMs > ERROR_LOG_INTERVAL_MS) {
            lastErrorLogMs = now;
            logger.warning("Discord: " + message);
        }
    }
}
