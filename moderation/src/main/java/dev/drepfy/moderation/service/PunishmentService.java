package dev.drepfy.moderation.service;

import dev.drepfy.moderation.ModerationPlugin;
import dev.drepfy.moderation.Permissions;
import dev.drepfy.moderation.cache.PunishmentCache;
import dev.drepfy.moderation.config.EscalationRule;
import dev.drepfy.moderation.config.Messages;
import dev.drepfy.moderation.config.Settings;
import dev.drepfy.moderation.log.AuditLog;
import dev.drepfy.moderation.model.Actor;
import dev.drepfy.moderation.model.Length;
import dev.drepfy.moderation.model.Punishment;
import dev.drepfy.moderation.model.PunishmentType;
import dev.drepfy.moderation.service.TargetResolver.Target;
import dev.drepfy.moderation.util.Durations;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;

/**
 * Issues and lifts punishments: validation, persistence, enforcement, notifications and logging.
 * Public methods must be called on the main thread.
 */
public final class PunishmentService {

    public static final int MAX_REASON_LENGTH = 256;

    /**
     * A punishment to issue, after the command has applied presets, defaults and duration limits.
     *
     * @param length ignored for kicks
     */
    public record IssueRequest(PunishmentType type, String target, Length length, String reason, boolean silent) {
    }

    private record Outcome(Punishment punishment, boolean created) {
    }

    private final ModerationPlugin plugin;
    private final PunishmentCache cache;
    private final TargetResolver targets;

    public PunishmentService(ModerationPlugin plugin, PunishmentCache cache, TargetResolver targets) {
        this.plugin = plugin;
        this.cache = cache;
        this.targets = targets;
    }

    // ---------------------------------------------------------------------------------- issuing

    public void issue(CommandSender sender, IssueRequest request) {
        Messages messages = plugin.messages();
        Actor actor = actorOf(sender);
        boolean mayPunishExempt = !(sender instanceof Player) || sender.hasPermission(Permissions.EXEMPT_OVERRIDE);

        if (request.type() == PunishmentType.KICK && targets.online(request.target()).isEmpty()) {
            messages.send(sender, "general.player-not-online", Placeholders.text("input", request.target()));
            return;
        }
        plugin.whenComplete(targets.resolve(request.target()), (found, error) -> {
            if (error != null) {
                fail(sender, "looking up " + request.target(), error);
                return;
            }
            if (found.isEmpty()) {
                plugin.messages().send(sender, "general.player-not-found", Placeholders.text("input", request.target()));
                return;
            }
            Target target = found.get();
            if (actor.isPlayer() && actor.uniqueId().equals(target.uniqueId())) {
                plugin.messages().send(sender, "general.cannot-punish-self");
                return;
            }
            if (target.exempt() && !mayPunishExempt) {
                plugin.messages().send(sender, "general.target-exempt", Placeholders.text("player", target.name()));
                plugin.audit().record("EXEMPT", actor.name() + " tried to " + request.type().key() + " exempt player " + target.name());
                return;
            }
            execute(actor, sender, target, request.type(), request.length(), request.reason(), request.silent());
        });
    }

    /**
     * Stores and applies a punishment. The in-memory cache is updated on the database thread right
     * after the insert succeeds, so enforcement starts before control returns to the main thread.
     *
     * @param feedback who to report the result to, or {@code null} for automatic punishments
     */
    private void execute(Actor actor, Audience feedback, Target target, PunishmentType type, Length length,
                         String reason, boolean silent) {
        long now = System.currentTimeMillis();
        boolean online = Bukkit.getPlayer(target.uniqueId()) != null;
        Punishment draft = new Punishment(0, type, target.uniqueId(), target.name(), actor, clip(reason),
                plugin.settings().serverName(), now, type.timed() ? length.expiresAt(now) : null,
                type.timed(), silent, null);
        // Offline players see their warnings when they next join.
        boolean notified = type != PunishmentType.WARN || online;

        plugin.whenComplete(plugin.storage().submit(store -> {
            if (type.exclusive()) {
                Optional<Punishment> existing = Punishment.strongest(store.findInForce(target.uniqueId(), now), type, now);
                if (existing.isPresent()) {
                    return new Outcome(existing.get(), false);
                }
            }
            Punishment created = store.insert(draft, notified);
            cache.add(created, System.currentTimeMillis());
            return new Outcome(created, true);
        }), (outcome, error) -> {
            if (error != null) {
                fail(feedback, "saving a " + type.key() + " for " + target.name(), error);
                return;
            }
            Punishment punishment = outcome.punishment();
            if (!outcome.created()) {
                if (feedback != null) {
                    plugin.messages().send(feedback, type.key() + ".already", placeholders(punishment));
                } else {
                    plugin.audit().record("ESCALATION", "Skipped automatic " + type.key() + " for " + target.name()
                            + ": already has #" + punishment.id());
                }
                return;
            }
            applied(punishment, feedback);
        });
    }

    private void applied(Punishment punishment, Audience feedback) {
        Messages messages = plugin.messages();
        Settings settings = plugin.settings();
        TagResolver placeholders = placeholders(punishment);
        String key = punishment.type().key();

        Player player = Bukkit.getPlayer(punishment.targetId());
        if (player != null) {
            switch (punishment.type()) {
                case BAN -> player.kick(messages.render("ban.screen", placeholders));
                case KICK -> player.kick(messages.render("kick.screen", placeholders));
                case MUTE, VOICE_MUTE, WARN -> messages.send(player, key + ".target", placeholders);
            }
        }
        if (feedback != null) {
            messages.send(feedback, key + ".success", placeholders);
            if (punishment.type() == PunishmentType.VOICE_MUTE && !plugin.voiceChatEnforced()) {
                messages.send(feedback, "voicemute.not-enforced", placeholders);
            }
        }
        notifyStaff(key + ".staff", placeholders, punishment.actor());
        if (!punishment.silent() && settings.type(punishment.type()).broadcast()) {
            broadcast(key + ".broadcast", placeholders);
        }
        plugin.audit().record(punishment.type().name(), describe(punishment));

        if (punishment.type() == PunishmentType.WARN) {
            escalate(punishment);
        }
    }

    /** Issues the automatic punishment configured for the target's new warning count, if any. */
    private void escalate(Punishment warning) {
        UUID target = warning.targetId();
        long now = System.currentTimeMillis();
        plugin.whenComplete(plugin.storage().submit(store -> store.countInForce(target, PunishmentType.WARN, now)),
                (count, error) -> {
                    if (error != null) {
                        fail(null, "counting warnings for " + warning.targetName(), error);
                        return;
                    }
                    EscalationRule rule = plugin.settings().escalation().get(count);
                    if (rule == null) {
                        return;
                    }
                    if (rule.type() == PunishmentType.KICK && Bukkit.getPlayer(target) == null) {
                        plugin.audit().record("ESCALATION", "Skipped automatic kick for " + warning.targetName() + ": offline");
                        return;
                    }
                    Messages messages = plugin.messages();
                    TagResolver tags = TagResolver.resolver(
                            Placeholders.text("count", count),
                            Placeholders.text("player", warning.targetName()),
                            Placeholders.text("type", messages.typeName(rule.type())));
                    String reason = PlainTextComponentSerializer.plainText()
                            .serialize(messages.render("warn.escalation-reason", tags));
                    Length length = rule.length().orElse(plugin.settings().type(rule.type()).defaultLength());
                    notifyStaff("warn.escalation-staff", tags, null);
                    execute(Actor.system(messages.plain("general.automatic-name")), null,
                            new Target(target, warning.targetName(), false), rule.type(), length, reason, warning.silent());
                });
    }

    // ---------------------------------------------------------------------------------- lifting

    /** Lifts every active ban, mute or voice mute of the given type for a player. */
    public void lift(CommandSender sender, PunishmentType type, String input, String reason, boolean silent) {
        Actor actor = actorOf(sender);
        plugin.whenComplete(targets.resolve(input), (found, error) -> {
            if (error != null) {
                fail(sender, "looking up " + input, error);
                return;
            }
            if (found.isEmpty()) {
                plugin.messages().send(sender, "general.player-not-found", Placeholders.text("input", input));
                return;
            }
            Target target = found.get();
            long now = System.currentTimeMillis();
            plugin.whenComplete(plugin.storage().submit(store -> {
                List<Punishment> lifted = store.lift(target.uniqueId(), type, actor, clip(reason), now);
                for (Punishment punishment : lifted) {
                    cache.remove(target.uniqueId(), punishment.id(), System.currentTimeMillis());
                }
                return lifted;
            }), (lifted, liftError) -> {
                if (liftError != null) {
                    fail(sender, "lifting a " + type.key() + " for " + target.name(), liftError);
                    return;
                }
                if (lifted.isEmpty()) {
                    plugin.messages().send(sender, "un" + type.key() + ".not-active", Placeholders.text("player", target.name()));
                    return;
                }
                lifted(lifted, sender, silent);
            });
        });
    }

    /** Lifts one warning by its ID. */
    public void liftWarning(CommandSender sender, long id, String reason, boolean silent) {
        Actor actor = actorOf(sender);
        long now = System.currentTimeMillis();
        plugin.whenComplete(plugin.storage().submit(store -> {
            Optional<Punishment> lifted = store.liftById(id, PunishmentType.WARN, actor, clip(reason), now);
            lifted.ifPresent(p -> cache.remove(p.targetId(), p.id(), System.currentTimeMillis()));
            return lifted;
        }), (lifted, error) -> {
            if (error != null) {
                fail(sender, "removing warning #" + id, error);
                return;
            }
            if (lifted.isEmpty()) {
                plugin.messages().send(sender, "unwarn.not-active", Placeholders.text("input", id));
                return;
            }
            lifted(List.of(lifted.get()), sender, silent);
        });
    }

    private void lifted(List<Punishment> lifted, CommandSender sender, boolean silent) {
        Messages messages = plugin.messages();
        Punishment first = lifted.getFirst();
        String key = "un" + first.type().key();
        TagResolver placeholders = Placeholders.lifted(first, messages, silent);

        messages.send(sender, key + ".success", placeholders);
        Player player = Bukkit.getPlayer(first.targetId());
        if (player != null) {
            messages.send(player, key + ".target", placeholders);
        }
        notifyStaff(key + ".staff", placeholders, first.removal().actor());
        for (Punishment punishment : lifted) {
            plugin.audit().record("UN" + punishment.type().name(), "#" + punishment.id() + " " + punishment.targetName()
                    + " (" + punishment.targetId() + ") by " + describe(punishment.removal().actor())
                    + (silent ? " [silent]" : "") + " reason=\"" + punishment.removal().reason() + "\"");
        }
    }

    // ---------------------------------------------------------------------------------- helpers

    public TagResolver placeholders(Punishment punishment) {
        return Placeholders.punishment(punishment, plugin.messages(), plugin.settings().dateFormat(), System.currentTimeMillis());
    }

    /** Sends a message to staff with {@code moderation.notify}, except whoever caused it (they get their own feedback). */
    public void notifyStaff(String key, TagResolver placeholders, Actor except) {
        Messages messages = plugin.messages();
        if (!messages.enabled(key)) {
            return;
        }
        var message = messages.render(key, placeholders);
        for (Player player : Bukkit.getOnlinePlayers()) {
            boolean isActor = except != null && player.getUniqueId().equals(except.uniqueId());
            if (!isActor && player.hasPermission(Permissions.NOTIFY)) {
                player.sendMessage(message);
            }
        }
    }

    /** Sends a public announcement to non-staff players (staff get the more detailed staff notification). */
    private void broadcast(String key, TagResolver placeholders) {
        Messages messages = plugin.messages();
        if (!messages.enabled(key)) {
            return;
        }
        var message = messages.render(key, placeholders);
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!player.hasPermission(Permissions.NOTIFY)) {
                player.sendMessage(message);
            }
        }
    }

    /** Logs an unexpected failure and tells the staff member (if any) that nothing was changed. */
    public void fail(Audience feedback, String action, Throwable error) {
        Throwable cause = unwrap(error);
        plugin.getLogger().log(Level.SEVERE, "Failed " + action, cause);
        plugin.audit().error("ERROR", "Failed " + action + ": " + cause);
        if (feedback != null) {
            plugin.messages().send(feedback, "general.database-error");
        }
    }

    public Actor actorOf(CommandSender sender) {
        if (sender instanceof Player player) {
            return new Actor(player.getUniqueId(), player.getName());
        }
        if (sender instanceof ConsoleCommandSender) {
            return Actor.system(plugin.messages().plain("general.console-name"));
        }
        return Actor.system(sender.getName());
    }

    static String describe(Punishment p) {
        StringBuilder text = new StringBuilder("#").append(p.id()).append(' ')
                .append(p.targetName()).append(" (").append(p.targetId()).append(") by ").append(describe(p.actor()));
        if (p.type().timed()) {
            Long length = p.lengthMillis();
            text.append(" for ").append(length == null ? "permanent" : Durations.format(Duration.ofMillis(length)));
        }
        if (p.silent()) {
            text.append(" [silent]");
        }
        return text.append(" reason=\"").append(p.reason()).append('"').toString();
    }

    private static String describe(Actor actor) {
        return actor.isPlayer() ? actor.name() + " (" + actor.uniqueId() + ")" : actor.name();
    }

    private static String clip(String reason) {
        String text = reason.strip();
        return text.length() <= MAX_REASON_LENGTH ? text : text.substring(0, MAX_REASON_LENGTH);
    }

    private static Throwable unwrap(Throwable error) {
        Throwable cause = error;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }
}
