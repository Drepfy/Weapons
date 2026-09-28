package dev.drepfy.moderation.command;

import dev.drepfy.moderation.ModerationPlugin;
import dev.drepfy.moderation.Permissions;
import dev.drepfy.moderation.config.Settings;
import dev.drepfy.moderation.model.Length;
import dev.drepfy.moderation.model.PunishmentType;
import dev.drepfy.moderation.service.Placeholders;
import dev.drepfy.moderation.service.PunishmentService;
import dev.drepfy.moderation.util.Durations;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code /ban}, {@code /mute}, {@code /voicemute}, {@code /warn} and {@code /kick}.
 */
public final class PunishCommand extends BaseCommand {

    private static final List<String> DURATION_SUGGESTIONS = List.of("30m", "1h", "12h", "1d", "7d", "30d", "permanent");

    private final PunishmentType type;

    public PunishCommand(ModerationPlugin plugin, PunishmentType type) {
        super(plugin, type.timed() ? "<player> [duration] [reason] [-s]" : "<player> [reason] [-s]");
        this.type = type;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!require(sender, Permissions.issue(type))) {
            return true;
        }
        if (args.length == 0) {
            sendUsage(sender, label);
            return true;
        }
        Settings settings = plugin.settings();
        String target = args[0];
        PunishmentArguments parsed = PunishmentArguments.parse(Arrays.asList(args).subList(1, args.length), type.timed());
        if (type.timed()) {
            parsed = parsed.withPreset(name -> settings.preset(type, name));
        }
        if (parsed.invalidDuration() != null) {
            messages().send(sender, "general.invalid-duration", Placeholders.text("input", parsed.invalidDuration()));
            return true;
        }
        if (parsed.silent() && !sender.hasPermission(Permissions.SILENT)) {
            messages().send(sender, "general.silent-no-permission");
            return true;
        }

        String reason = parsed.reason();
        Settings.TypeSettings typeSettings = settings.type(type);
        TagResolver typeName = Placeholders.text("type", messages().typeName(type));
        if (reason.isEmpty()) {
            if (typeSettings.requireReason()) {
                messages().send(sender, "general.reason-required", typeName);
                return true;
            }
            reason = messages().plain("general.default-reason");
        }

        Length resolved = parsed.length().orElse(typeSettings.defaultLength());
        if (type.timed() && sender instanceof Player && !sender.hasPermission(Permissions.LIMITS_UNLIMITED)) {
            Optional<Duration> max = settings.limits().maxFor(type, sender::hasPermission);
            if (max.isPresent() && !resolved.fitsWithin(max.get())) {
                messages().send(sender, "general.duration-limit", typeName,
                        Placeholders.text("limit", Durations.format(max.get(), messages().units())));
                return true;
            }
        }

        plugin.service().issue(sender, new PunishmentService.IssueRequest(type, target, resolved, reason, parsed.silent()));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(Permissions.issue(type)) || args.length == 0) {
            return List.of();
        }
        if (args.length == 1) {
            return playerNames(sender, args[0]);
        }
        String current = args[args.length - 1];
        List<String> options = new ArrayList<>();
        if (args.length == 2 && type.timed()) {
            options.addAll(DURATION_SUGGESTIONS);
        }
        if (type.timed() && (args.length == 2 || (args.length == 3 && Length.parse(args[1]).isPresent()))) {
            options.addAll(plugin.settings().presets().getOrDefault(type, Map.of()).keySet());
        }
        if (sender.hasPermission(Permissions.SILENT)) {
            options.add("-s");
        }
        return filter(options, current);
    }
}
