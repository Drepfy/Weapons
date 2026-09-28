package dev.drepfy.moderation.service;

import dev.drepfy.moderation.config.Messages;
import dev.drepfy.moderation.model.Punishment;
import dev.drepfy.moderation.util.Durations;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;

/**
 * Builds MiniMessage placeholders for punishment messages.
 *
 * <p>Everything typed by staff or players (names, reasons) is inserted with
 * {@link Placeholder#unparsed}, so a reason like {@code <click:run_command:/op me>} is shown as
 * literal text instead of being interpreted.
 */
public final class Placeholders {

    private Placeholders() {
    }

    public static TagResolver punishment(Punishment p, Messages messages, DateTimeFormatter dates, long now) {
        return TagResolver.resolver(
                Placeholder.unparsed("player", p.targetName()),
                Placeholder.unparsed("staff", p.actor().name()),
                Placeholder.unparsed("reason", p.reason()),
                Placeholder.unparsed("type", messages.typeName(p.type())),
                Placeholder.unparsed("id", Long.toString(p.id())),
                Placeholder.unparsed("server", p.server()),
                Placeholder.unparsed("date", date(p.createdAt(), dates)),
                Placeholder.component("duration", duration(p, messages)),
                Placeholder.component("expires", expires(p, messages, dates)),
                Placeholder.component("remaining", remaining(p, messages, now)),
                Placeholder.component("status", status(p, messages, now)),
                Placeholder.component("silent", p.silent() ? messages.render("general.silent-tag") : Component.empty()));
    }

    /**
     * Placeholders for a lifted punishment: {@code <staff>} and {@code <reason>} describe the removal
     * rather than the original punishment.
     */
    public static TagResolver lifted(Punishment p, Messages messages, boolean silent) {
        Punishment.Removal removal = p.removal();
        return TagResolver.resolver(
                Placeholder.unparsed("player", p.targetName()),
                Placeholder.unparsed("staff", removal == null ? "" : removal.actor().name()),
                Placeholder.unparsed("reason", removal == null ? "" : removal.reason()),
                Placeholder.unparsed("type", messages.typeName(p.type())),
                Placeholder.unparsed("id", Long.toString(p.id())),
                Placeholder.component("silent", silent ? messages.render("general.silent-tag") : Component.empty()));
    }

    public static TagResolver text(String name, Object value) {
        return Placeholder.unparsed(name, String.valueOf(value));
    }

    public static String date(long epochMillis, DateTimeFormatter dates) {
        return dates.format(Instant.ofEpochMilli(epochMillis));
    }

    public static Component duration(Punishment p, Messages messages) {
        if (!p.type().timed()) {
            return messages.render("status.instant");
        }
        Long length = p.lengthMillis();
        return length == null
                ? messages.render("time.permanent")
                : Component.text(Durations.format(Duration.ofMillis(length), messages.units()));
    }

    public static Component expires(Punishment p, Messages messages, DateTimeFormatter dates) {
        if (!p.type().timed() || p.expiresAt() == null) {
            return messages.render("time.never");
        }
        return Component.text(date(p.expiresAt(), dates));
    }

    public static Component remaining(Punishment p, Messages messages, long now) {
        if (!p.type().timed() || p.expiresAt() == null) {
            return messages.render("time.permanent");
        }
        return Component.text(Durations.format(Duration.ofMillis(Math.max(0, p.expiresAt() - now)), messages.units()));
    }

    public static Component status(Punishment p, Messages messages, long now) {
        if (p.removal() != null) {
            return messages.render("status.removed", Placeholder.unparsed("removed_by", p.removal().actor().name()));
        }
        if (!p.type().timed()) {
            return messages.render("status.instant");
        }
        return messages.render(p.inForce(now) ? "status.active" : "status.expired");
    }
}
