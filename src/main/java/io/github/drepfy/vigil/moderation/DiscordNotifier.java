package io.github.drepfy.vigil.moderation;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.drepfy.vigil.config.Settings;
import io.github.drepfy.vigil.util.Text;

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
 * Posts punishments (and, if enabled, anti-cheat alerts) to Discord: through the bot when
 * it has a channel for them, otherwise through the webhook. Sending happens on a
 * background thread with a small queue; if Discord is slow or down, messages are dropped
 * and the server is never affected.
 */
public final class DiscordNotifier {

    /** Where the bot posts. Implemented by the Discord bot. */
    public interface BotChannel {
        /** @return false when the bot has no punishments channel (the webhook is used then) */
        boolean postPunishment(JsonObject embed);

        /** @return false when the bot has no alerts channel (the webhook is used then) */
        boolean postAlert(JsonObject embed);
    }

    public static final int RED = 0xE74C3C;
    public static final int DARK_RED = 0x992D22;
    public static final int ORANGE = 0xE67E22;
    public static final int YELLOW = 0xF1C40F;
    public static final int BLUE = 0x3498DB;
    public static final int GREEN = 0x2ECC71;
    /** Discord's limit for an embed field. */
    private static final int FIELD_LIMIT = 1000;
    private static final long ERROR_LOG_INTERVAL_MS = 60_000;

    private final Supplier<Settings> settings;
    private final Logger logger;
    private final ThreadPoolExecutor executor;
    private volatile HttpClient client;
    private volatile long lastErrorLogMs;
    private volatile BotChannel bot;

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

    public void setBot(BotChannel bot) {
        this.bot = bot;
    }

    /** A punishment was given or lifted. */
    public void punishment(Punishment punishment) {
        Settings config = settings.get();
        Settings.Discord discord = config.discord();
        boolean autoBan = ModerationListener.ANTI_CHEAT_STAFF.equals(punishment.staff());
        if (autoBan ? !discord.autoBans() : !discord.punishments()) {
            return;
        }
        JsonObject embed = punishmentEmbed(punishment, config.messages().get("permanent"));
        BotChannel channel = bot;
        if (channel != null && channel.postPunishment(embed)) {
            return;
        }
        if (discord.enabled()) {
            post(discord.webhookUrl(), webhookPayload(embed));
        }
    }

    /** An anti-cheat alert that staff saw in game. */
    public void alert(String player, String reason, String vl, String detail) {
        List<String[]> fields = new ArrayList<>();
        fields.add(new String[] {"VL", vl});
        fields.add(new String[] {"Evidence", detail});
        JsonObject embed = embed(player + " was flagged for " + reason, ORANGE, fields);
        BotChannel channel = bot;
        if (channel != null && channel.postAlert(embed)) {
            return;
        }
        Settings.Discord discord = settings.get().discord();
        if (discord.enabled() && discord.alerts()) {
            post(discord.webhookUrl(), webhookPayload(embed));
        }
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    /** The webhook message for a punishment (for tests and the webhook). */
    static String punishmentJson(Punishment p, String permanent) {
        return webhookPayload(punishmentEmbed(p, permanent)).toString();
    }

    public static JsonObject punishmentEmbed(Punishment p, String permanent) {
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
        return embed(title, color, fields);
    }

    /** An embed with inline fields; colour codes are removed and long text is cut. */
    public static JsonObject embed(String title, int color, List<String[]> fields) {
        JsonObject embed = new JsonObject();
        embed.addProperty("title", clean(title, 250));
        embed.addProperty("color", color);
        embed.addProperty("timestamp", Instant.now().toString());
        JsonArray list = new JsonArray();
        for (String[] field : fields) {
            JsonObject entry = new JsonObject();
            entry.addProperty("name", clean(field[0], 250));
            String value = clean(field[1], FIELD_LIMIT);
            entry.addProperty("value", value.isBlank() ? "-" : value);
            entry.addProperty("inline", true);
            list.add(entry);
        }
        embed.add("fields", list);
        return embed;
    }

    /** No colour codes, at most {@code max} characters. */
    public static String clean(String text, int max) {
        String clean = Text.strip(text);
        return clean.length() > max ? clean.substring(0, max) + "..." : clean;
    }

    /** No pings: a reason like "@everyone" is shown, never mentioned. */
    static JsonObject webhookPayload(JsonObject embed) {
        JsonObject payload = new JsonObject();
        payload.addProperty("username", "Vigil");
        JsonObject mentions = new JsonObject();
        mentions.add("parse", new JsonArray());
        payload.add("allowed_mentions", mentions);
        JsonArray embeds = new JsonArray();
        embeds.add(embed);
        payload.add("embeds", embeds);
        return payload;
    }

    private void post(String url, JsonObject body) {
        String text = body.toString();
        try {
            executor.execute(() -> send(url, text));
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
