package io.github.drepfy.vigil.command;

import io.github.drepfy.vigil.VigilPlugin;
import io.github.drepfy.vigil.api.CheckCategory;
import io.github.drepfy.vigil.api.CheckType;
import io.github.drepfy.vigil.config.CheckSettings;
import io.github.drepfy.vigil.config.Settings;
import io.github.drepfy.vigil.data.FlagRecord;
import io.github.drepfy.vigil.data.PlayerData;
import io.github.drepfy.vigil.review.ReviewCase;
import io.github.drepfy.vigil.storage.PlayerRecord;
import io.github.drepfy.vigil.util.Clock;
import io.github.drepfy.vigil.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.command.CommandExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * {@code /vigil} staff command. Every sub-command checks its own permission node.
 */
public final class VigilCommand implements CommandExecutor, TabCompleter {

    private static final int PAGE_SIZE = 8;
    private static final long MAX_EXEMPT_SECONDS = 86_400;

    private record SubCommand(String name, String permission, String usage, String description) {
    }

    private static final List<SubCommand> SUBCOMMANDS = List.of(
            new SubCommand("alerts", "vigil.alerts", "alerts", "Toggle staff alerts for yourself"),
            new SubCommand("info", "vigil.info", "info <player>", "Live violation levels of an online player"),
            new SubCommand("history", "vigil.history", "history <player> [page]", "Stored flag history (works offline)"),
            new SubCommand("review", "vigil.review", "review [list [all] [page]|view|claim|resolve|open]",
                    "Manual review queue"),
            new SubCommand("note", "vigil.review", "note <player> <text>", "Add a staff note to a player's record"),
            new SubCommand("exempt", "vigil.exempt", "exempt <player> <check|category|all> <seconds>",
                    "Temporarily exempt a player"),
            new SubCommand("unexempt", "vigil.exempt", "unexempt <player>", "Remove manual exemptions"),
            new SubCommand("reset", "vigil.reset", "reset <player> [check]", "Reset violation levels"),
            new SubCommand("status", "vigil.status", "status", "Plugin health, lag state and config warnings"),
            new SubCommand("reload", "vigil.reload", "reload", "Reload config.yml"),
            new SubCommand("debug", "vigil.debug", "debug <player> [check]", "Stream live check diagnostics"));

    private final VigilPlugin plugin;

    public VigilCommand(VigilPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            help(sender, label);
            return true;
        }
        String name = args[0].toLowerCase(Locale.ROOT);
        SubCommand sub = find(name);
        if (sub == null) {
            help(sender, label);
            return true;
        }
        if (!sender.hasPermission(sub.permission())) {
            send(sender, message("no-permission"));
            return true;
        }
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        try {
            switch (name) {
                case "alerts" -> alerts(sender);
                case "info" -> info(sender, rest);
                case "history" -> history(sender, rest);
                case "review" -> review(sender, rest);
                case "note" -> note(sender, rest);
                case "exempt" -> exempt(sender, rest);
                case "unexempt" -> unexempt(sender, rest);
                case "reset" -> reset(sender, rest);
                case "status" -> status(sender);
                case "reload" -> reload(sender);
                case "debug" -> debug(sender, rest);
                default -> help(sender, label);
            }
        } catch (RuntimeException e) {
            send(sender, "&cSomething went wrong: " + e.getMessage());
            plugin.getLogger().log(java.util.logging.Level.WARNING, "Command failed: /" + label + " " + String.join(" ", args), e);
        }
        return true;
    }

    // ---- sub-commands --------------------------------------------------------------------------

    private void help(CommandSender sender, String label) {
        send(sender, "&fVigil &7" + plugin.getDescription().getVersion() + " &8- &7staff commands:");
        boolean any = false;
        for (SubCommand sub : SUBCOMMANDS) {
            if (sender.hasPermission(sub.permission())) {
                sender.sendMessage(Text.color("  &e/" + label + " " + sub.usage() + " &8- &7" + sub.description()));
                any = true;
            }
        }
        if (!any) {
            send(sender, message("no-permission"));
        }
    }

    private void alerts(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            send(sender, "&cOnly players can toggle alerts; console alerts are set in config.yml.");
            return;
        }
        boolean enabled = plugin.alerts().toggle(player.getUniqueId());
        send(sender, message(enabled ? "alerts-enabled" : "alerts-disabled"));
    }

    private void info(CommandSender sender, String[] args) {
        if (args.length < 1) {
            usage(sender, "info <player>");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            send(sender, Text.replace(message("player-not-found"), "player", args[0])
                    + " &7(offline players: /vigil history)");
            return;
        }
        PlayerData data = plugin.players().get(target);
        long now = Clock.now();
        Settings settings = plugin.settings();
        int ping = plugin.checks().recentPing(target, data, now);
        sender.sendMessage(Text.color("&8&m----&r &fVigil: &e" + target.getName() + " &8&m----"));
        sender.sendMessage(Text.color("&7Ping (recent max): &f" + ping + "ms &7TPS: &f" + Text.num(plugin.tpsMonitor().tps())
                + " &7Mode: &f" + target.getGameMode().name().toLowerCase(Locale.ROOT)));

        List<String> exemptions = new ArrayList<>();
        if (target.hasPermission("vigil.bypass")) {
            exemptions.add("vigil.bypass");
        }
        for (Map.Entry<String, Long> entry : data.activeExemptions(now).entrySet()) {
            exemptions.add(entry.getKey() + " (" + Text.duration(entry.getValue()) + ")");
        }
        sender.sendMessage(Text.color("&7Exemptions: &f" + (exemptions.isEmpty() ? "none" : String.join(", ", exemptions))));

        StringBuilder vls = new StringBuilder();
        for (CheckType type : CheckType.values()) {
            CheckSettings check = settings.check(type);
            double vl = data.violation(type).get(now, check.decayPerMinute());
            int flags = data.violation(type).totalFlags();
            if (vl > 0.01 || flags > 0) {
                vls.append("\n  &e").append(type.displayName()).append("&7: VL &f").append(Text.num(vl))
                        .append(" &8(").append(flags).append(" flag(s) this session, alert ")
                        .append(Text.num(check.alertVl())).append(", review ").append(Text.num(check.reviewVl()))
                        .append(")");
            }
        }
        sender.sendMessage(Text.color("&7Violations:" + (vls.length() == 0 ? " &anone" : vls.toString())));

        List<FlagRecord> recent = data.recentFlags();
        if (!recent.isEmpty()) {
            sender.sendMessage(Text.color("&7Recent flags:"));
            for (int i = 0; i < Math.min(5, recent.size()); i++) {
                sender.sendMessage(Text.color("  &8- &f" + recent.get(i).toLine()));
            }
        }
        ReviewCase open = plugin.review().findOpen(target.getUniqueId());
        PlayerRecord record = data.record;
        sender.sendMessage(Text.color("&7Review: &f" + (open != null ? "open case #" + open.id() : "no open case")
                + " &7Lifetime flags: &f" + (record != null ? record.totalLifetimeFlags() : "loading...")));
    }

    private void history(CommandSender sender, String[] args) {
        if (args.length < 1) {
            usage(sender, "history <player> [page]");
            return;
        }
        int page = args.length >= 2 ? parsePage(args[1]) : 1;
        withRecord(sender, args[0], record -> {
            List<String> lines = record.recentFlags();
            int pages = Math.max(1, (lines.size() + PAGE_SIZE - 1) / PAGE_SIZE);
            int current = Math.min(page, pages);
            sender.sendMessage(Text.color("&8&m----&r &fHistory: &e" + record.name() + " &7(page " + current + "/" + pages
                    + ") &8&m----"));
            StringBuilder counts = new StringBuilder();
            for (Map.Entry<CheckType, Integer> entry : record.lifetimeFlags().entrySet()) {
                counts.append(counts.length() == 0 ? "" : ", ").append(entry.getKey().displayName()).append(' ')
                        .append(entry.getValue());
            }
            sender.sendMessage(Text.color("&7Lifetime flags: &f" + (counts.length() == 0 ? "none" : counts)));
            sender.sendMessage(Text.color("&7First seen: &f" + age(record.firstSeenEpochMs()) + " ago &7Last seen: &f"
                    + age(record.lastSeenEpochMs()) + " ago"));
            if (lines.isEmpty()) {
                sender.sendMessage(Text.color("&aNo flags recorded."));
            }
            for (int i = (current - 1) * PAGE_SIZE; i < Math.min(lines.size(), current * PAGE_SIZE); i++) {
                sender.sendMessage(Text.color("  &8- &f" + lines.get(i)));
            }
            List<String> notes = record.notes();
            if (!notes.isEmpty()) {
                sender.sendMessage(Text.color("&7Staff notes:"));
                for (String note : notes.subList(Math.max(0, notes.size() - 5), notes.size())) {
                    sender.sendMessage(Text.color("  &8- &f" + note));
                }
            }
            List<ReviewCase> cases = plugin.review().casesOf(record.uuid());
            if (!cases.isEmpty()) {
                StringBuilder summary = new StringBuilder();
                for (ReviewCase reviewCase : cases) {
                    summary.append(summary.length() == 0 ? "" : ", ").append('#').append(reviewCase.id()).append(' ')
                            .append(reviewCase.status() == ReviewCase.Status.OPEN ? "open" : String.valueOf(reviewCase.verdict()));
                }
                sender.sendMessage(Text.color("&7Cases: &f" + summary));
            }
        });
    }

    private void review(CommandSender sender, String[] args) {
        String action = args.length == 0 ? "list" : args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "list" -> reviewList(sender, Arrays.copyOfRange(args, Math.min(1, args.length), args.length));
            case "view" -> reviewView(sender, args);
            case "claim" -> reviewClaim(sender, args);
            case "resolve" -> reviewResolve(sender, args);
            case "open" -> reviewOpen(sender, args);
            default -> usage(sender, "review [list [all] [page] | view <id> | claim <id> | resolve <id> "
                    + "<cheating|legit|inconclusive> [note] | open <player> [reason]]");
        }
    }

    private void reviewList(CommandSender sender, String[] args) {
        boolean all = args.length > 0 && args[0].equalsIgnoreCase("all");
        int page = parsePage(args.length > (all ? 1 : 0) ? args[all ? 1 : 0] : "1");
        List<ReviewCase> cases = plugin.review().list(!all);
        int pages = Math.max(1, (cases.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int current = Math.min(page, pages);
        sender.sendMessage(Text.color("&8&m----&r &fReview queue &7(" + (all ? "all" : "open") + ", page " + current + "/"
                + pages + ") &8&m----"));
        if (cases.isEmpty()) {
            sender.sendMessage(Text.color(all ? "&aNo cases." : "&aNo open cases."));
            return;
        }
        for (int i = (current - 1) * PAGE_SIZE; i < Math.min(cases.size(), current * PAGE_SIZE); i++) {
            ReviewCase reviewCase = cases.get(i);
            String state = reviewCase.status() == ReviewCase.Status.OPEN
                    ? (reviewCase.claimedBy() != null ? "&eclaimed by " + reviewCase.claimedBy() : "&copen")
                    : "&a" + String.valueOf(reviewCase.verdict()).toLowerCase(Locale.ROOT);
            sender.sendMessage(Text.color("  &f#" + reviewCase.id() + " &e" + reviewCase.name() + " &7" + reviewCase.reason()
                    + " &8(" + age(reviewCase.openedEpochMs()) + " ago) " + state));
        }
    }

    private void reviewView(CommandSender sender, String[] args) {
        ReviewCase reviewCase = caseArg(sender, args, "review view <id>");
        if (reviewCase == null) {
            return;
        }
        sender.sendMessage(Text.color("&8&m----&r &fCase #" + reviewCase.id() + ": &e" + reviewCase.name() + " &8&m----"));
        sender.sendMessage(Text.color("&7Reason: &f" + reviewCase.reason()));
        sender.sendMessage(Text.color("&7Opened: &f" + age(reviewCase.openedEpochMs()) + " ago &7Updated: &f"
                + age(reviewCase.updatedEpochMs()) + " ago"));
        StringBuilder peaks = new StringBuilder();
        for (Map.Entry<CheckType, Double> entry : reviewCase.peakVl().entrySet()) {
            peaks.append(peaks.length() == 0 ? "" : ", ").append(entry.getKey().displayName()).append(' ')
                    .append(Text.num(entry.getValue()));
        }
        sender.sendMessage(Text.color("&7Peak VL: &f" + (peaks.length() == 0 ? "-" : peaks)));
        if (reviewCase.status() == ReviewCase.Status.OPEN) {
            sender.sendMessage(Text.color("&7Status: &copen" + (reviewCase.claimedBy() != null
                    ? " &7(claimed by &f" + reviewCase.claimedBy() + "&7)" : "")));
        } else {
            sender.sendMessage(Text.color("&7Status: &aresolved &7as &f" + reviewCase.verdict() + " &7by &f"
                    + reviewCase.resolvedBy() + (reviewCase.note() != null ? " &7- &f" + reviewCase.note() : "")));
        }
        sender.sendMessage(Text.color("&7Evidence:"));
        for (String line : reviewCase.evidence()) {
            sender.sendMessage(Text.color("  &8- &f" + line));
        }
    }

    private void reviewClaim(CommandSender sender, String[] args) {
        ReviewCase reviewCase = caseArg(sender, args, "review claim <id>");
        if (reviewCase == null) {
            return;
        }
        if (reviewCase.status() != ReviewCase.Status.OPEN) {
            send(sender, "&cCase #" + reviewCase.id() + " is already resolved.");
            return;
        }
        reviewCase.claim(sender.getName());
        plugin.review().markDirty();
        send(sender, "&aYou claimed case #" + reviewCase.id() + ".");
    }

    private void reviewResolve(CommandSender sender, String[] args) {
        if (args.length < 3) {
            usage(sender, "review resolve <id> <cheating|legit|inconclusive> [note]");
            return;
        }
        ReviewCase reviewCase = caseArg(sender, args, "review resolve <id> <verdict> [note]");
        if (reviewCase == null) {
            return;
        }
        ReviewCase.Verdict verdict = ReviewCase.Verdict.parse(args[2]);
        if (verdict == null) {
            send(sender, "&cVerdict must be cheating, legit or inconclusive.");
            return;
        }
        String note = args.length > 3 ? String.join(" ", Arrays.copyOfRange(args, 3, args.length)) : null;
        reviewCase.resolve(verdict, sender.getName(), note);
        plugin.review().markDirty();
        send(sender, "&aCase #" + reviewCase.id() + " resolved as " + verdict.name().toLowerCase(Locale.ROOT) + ".");
        if (verdict == ReviewCase.Verdict.LEGIT) {
            send(sender, "&7Tip: if a check misjudged this player, share the evidence so thresholds can be tuned; "
                    + "&f/vigil reset " + reviewCase.name() + " &7clears their current VL.");
        }
    }

    private void reviewOpen(CommandSender sender, String[] args) {
        if (args.length < 2) {
            usage(sender, "review open <player> [reason]");
            return;
        }
        String reason = args.length > 2 ? String.join(" ", Arrays.copyOfRange(args, 2, args.length))
                : "opened manually by " + sender.getName();
        resolvePlayer(args[1], (uuid, name) -> {
            if (uuid == null) {
                send(sender, Text.replace(message("player-not-found"), "player", args[1]));
                return;
            }
            ReviewCase existing = plugin.review().findOpen(uuid);
            if (existing != null) {
                send(sender, "&e" + name + " already has open case #" + existing.id() + ".");
                return;
            }
            ReviewCase created = plugin.review().open(uuid, name, reason);
            PlayerData data = plugin.players().peek(uuid);
            if (data != null) {
                List<FlagRecord> recent = data.recentFlags();
                for (int i = Math.min(recent.size(), plugin.settings().review().maxEvidence()) - 1; i >= 0; i--) {
                    created.addEvidence(recent.get(i).toLine(), plugin.settings().review().maxEvidence());
                }
            }
            send(sender, "&aOpened case #" + created.id() + " for " + name + ".");
        });
    }

    private void note(CommandSender sender, String[] args) {
        if (args.length < 2) {
            usage(sender, "note <player> <text>");
            return;
        }
        String text = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        Player online = Bukkit.getPlayerExact(args[0]);
        if (online != null) {
            PlayerRecord record = plugin.players().get(online).record;
            if (record == null) {
                send(sender, "&cThat player's record is still loading, try again in a moment.");
                return;
            }
            record.addNote(sender.getName(), text);
            plugin.records().saveAsync(record);
            send(sender, "&aNote added to " + online.getName() + ".");
            return;
        }
        withRecord(sender, args[0], record -> {
            record.addNote(sender.getName(), text);
            plugin.records().saveAsync(record);
            send(sender, "&aNote added to " + record.name() + ".");
        });
    }

    private void exempt(CommandSender sender, String[] args) {
        if (args.length < 3) {
            usage(sender, "exempt <player> <check|movement|combat|interaction|all> <seconds>");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            send(sender, Text.replace(message("player-not-found"), "player", args[0]));
            return;
        }
        long seconds;
        try {
            seconds = Long.parseLong(args[2]);
        } catch (NumberFormatException e) {
            send(sender, "&cSeconds must be a whole number.");
            return;
        }
        if (seconds <= 0 || seconds > MAX_EXEMPT_SECONDS) {
            send(sender, "&cSeconds must be between 1 and " + MAX_EXEMPT_SECONDS + ".");
            return;
        }
        PlayerData data = plugin.players().get(target);
        long until = Clock.now() + seconds * 1000L;
        String what = args[1].toLowerCase(Locale.ROOT);
        CheckType type = CheckType.fromId(what);
        if (what.equals("all")) {
            data.exemptAll(until);
        } else if (type != null) {
            data.exempt(type, until);
            what = type.displayName();
        } else {
            CheckCategory category = category(what);
            if (category == null) {
                send(sender, "&cUnknown check or category: " + args[1]);
                return;
            }
            data.exempt(category, until);
        }
        send(sender, "&a" + target.getName() + " is exempt from " + what + " for " + Text.duration(seconds * 1000L) + ".");
        plugin.getLogger().info(sender.getName() + " exempted " + target.getName() + " from " + what + " for "
                + seconds + "s");
    }

    private void unexempt(CommandSender sender, String[] args) {
        if (args.length < 1) {
            usage(sender, "unexempt <player>");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            send(sender, Text.replace(message("player-not-found"), "player", args[0]));
            return;
        }
        plugin.players().get(target).clearExemptions();
        send(sender, "&aRemoved manual exemptions of " + target.getName() + ".");
    }

    private void reset(CommandSender sender, String[] args) {
        if (args.length < 1) {
            usage(sender, "reset <player> [check]");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            send(sender, Text.replace(message("player-not-found"), "player", args[0]));
            return;
        }
        CheckType type = null;
        if (args.length >= 2) {
            type = CheckType.fromId(args[1]);
            if (type == null) {
                send(sender, "&cUnknown check: " + args[1]);
                return;
            }
        }
        plugin.players().get(target).resetViolations(type);
        send(sender, "&aReset " + (type == null ? "all violation levels" : type.displayName() + " violations") + " of "
                + target.getName() + ".");
        plugin.getLogger().info(sender.getName() + " reset " + (type == null ? "all" : type.id()) + " VL of "
                + target.getName());
    }

    private void status(CommandSender sender) {
        Settings settings = plugin.settings();
        long now = Clock.now();
        sender.sendMessage(Text.color("&8&m----&r &fVigil " + plugin.getDescription().getVersion() + " status &8&m----"));
        sender.sendMessage(Text.color("&7Checks: " + (settings.general().enabled() ? "&aenabled" : "&cdisabled")
                + (settings.general().passiveMode() ? " &e(passive mode)" : "")
                + " &7Automatic commands: " + (settings.punishments().enabled()
                ? (settings.punishments().dryRun() ? "&edry-run" : "&cON") : "&aoff")));
        boolean lagging = plugin.tpsMonitor().tps() < settings.lag().minTps()
                || plugin.tpsMonitor().recentlySpiked(now, settings.lag().lagSpikeGraceMs());
        sender.sendMessage(Text.color("&7TPS: &f" + Text.num(plugin.tpsMonitor().tps()) + " &7Lag protection: "
                + (lagging ? "&eactive (flags suppressed)" : "&anot active")
                + " &7Tick rate normal: &f" + plugin.checks().compat().isTickRateNormal()));
        StringBuilder list = new StringBuilder();
        for (CheckType type : CheckType.values()) {
            boolean broken = plugin.checks().isBroken(type);
            boolean enabled = settings.check(type).enabled();
            list.append(list.length() == 0 ? "" : "&7, ")
                    .append(broken ? "&c" : enabled ? "&a" : "&8").append(type.displayName())
                    .append(broken ? "(error)" : "");
        }
        sender.sendMessage(Text.color("&7Checks: " + list));
        sender.sendMessage(Text.color("&7Tracked players: &f" + plugin.players().online().size()
                + " &7Open cases: &f" + plugin.review().openCount()
                + (plugin.review().isReadOnly() ? " &c(cases file read-only!)" : "")
                + " &7IO queue: &f" + plugin.io().queued() + " &7dropped: &f" + plugin.io().dropped()));
        sender.sendMessage(Text.color("&7Client tick events: &f" + plugin.checks().compat().hasClientTickEvent()));
        List<String> warnings = settings.warnings();
        if (warnings.isEmpty()) {
            sender.sendMessage(Text.color("&7Config warnings: &anone"));
        } else {
            sender.sendMessage(Text.color("&7Config warnings (&e" + warnings.size() + "&7):"));
            for (String warning : warnings.subList(0, Math.min(10, warnings.size()))) {
                sender.sendMessage(Text.color("  &e- " + warning));
            }
        }
    }

    private void reload(CommandSender sender) {
        try {
            List<String> warnings = plugin.reload();
            send(sender, Text.replace(message("reloaded"), "warnings", warnings.size()));
            for (String warning : warnings.subList(0, Math.min(10, warnings.size()))) {
                sender.sendMessage(Text.color("  &e- " + warning));
            }
        } catch (IllegalStateException e) {
            send(sender, Text.replace(message("reload-failed"), "error", e.getMessage()));
        }
    }

    private void debug(CommandSender sender, String[] args) {
        if (!(sender instanceof Player viewer)) {
            send(sender, "&cOnly players can view debug output; enable general.debug for console output.");
            return;
        }
        if (args.length < 1) {
            usage(sender, "debug <player> [check]");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            send(sender, Text.replace(message("player-not-found"), "player", args[0]));
            return;
        }
        CheckType filter = null;
        if (args.length >= 2) {
            filter = CheckType.fromId(args[1]);
            if (filter == null) {
                send(sender, "&cUnknown check: " + args[1]);
                return;
            }
        }
        boolean on = plugin.debugService().toggle(viewer.getUniqueId(), target.getUniqueId(), filter);
        send(sender, (on ? "&aNow" : "&eNo longer") + " streaming debug for " + target.getName()
                + (filter != null ? " (" + filter.displayName() + ")" : "") + ".");
    }

    // ---- helpers -------------------------------------------------------------------------------

    /** Finds a player's persistent record (online or offline) and runs the action on the main thread. */
    private void withRecord(CommandSender sender, String input, Consumer<PlayerRecord> action) {
        Player online = Bukkit.getPlayerExact(input);
        if (online != null) {
            PlayerRecord record = plugin.players().get(online).record;
            if (record != null) {
                action.accept(record);
                return;
            }
        }
        resolvePlayer(input, (uuid, name) -> {
            if (uuid == null) {
                send(sender, Text.replace(message("player-not-found"), "player", input));
                return;
            }
            CompletableFuture<PlayerRecord> future = plugin.records().loadExisting(uuid);
            future.thenAccept(record -> Bukkit.getScheduler().runTask(plugin, () -> {
                if (record == null) {
                    send(sender, "&7No Vigil record exists for &f" + name + "&7.");
                } else {
                    action.accept(record);
                }
            }));
        });
    }

    private interface PlayerCallback {
        void accept(UUID uuid, String name);
    }

    /** Resolves a name or UUID without any web lookup. */
    private void resolvePlayer(String input, PlayerCallback callback) {
        Player online = Bukkit.getPlayerExact(input);
        if (online != null) {
            callback.accept(online.getUniqueId(), online.getName());
            return;
        }
        try {
            UUID uuid = UUID.fromString(input);
            OfflinePlayer offline = Bukkit.getOfflinePlayer(uuid);
            callback.accept(uuid, offline.getName() != null ? offline.getName() : input);
            return;
        } catch (IllegalArgumentException ignored) {
            // Not a UUID; try names.
        }
        for (OfflinePlayer offline : Bukkit.getOfflinePlayers()) {
            if (offline.getName() != null && offline.getName().equalsIgnoreCase(input)) {
                callback.accept(offline.getUniqueId(), offline.getName());
                return;
            }
        }
        callback.accept(null, input);
    }

    private ReviewCase caseArg(CommandSender sender, String[] args, String usage) {
        if (args.length < 2) {
            usage(sender, usage);
            return null;
        }
        try {
            ReviewCase reviewCase = plugin.review().get(Integer.parseInt(args[1].replace("#", "")));
            if (reviewCase == null) {
                send(sender, "&cNo case #" + args[1] + ".");
            }
            return reviewCase;
        } catch (NumberFormatException e) {
            send(sender, "&cCase id must be a number.");
            return null;
        }
    }

    private static CheckCategory category(String input) {
        for (CheckCategory category : CheckCategory.values()) {
            if (category.name().equalsIgnoreCase(input)) {
                return category;
            }
        }
        return null;
    }

    private static int parsePage(String input) {
        try {
            return Math.max(1, Integer.parseInt(input));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private static String age(long epochMs) {
        return Text.duration(System.currentTimeMillis() - epochMs);
    }

    private static SubCommand find(String name) {
        for (SubCommand sub : SUBCOMMANDS) {
            if (sub.name().equals(name)) {
                return sub;
            }
        }
        return null;
    }

    private String message(String key) {
        return plugin.settings().messages().get(key);
    }

    private void send(CommandSender sender, String text) {
        sender.sendMessage(Text.color(plugin.settings().messages().get("prefix") + text));
    }

    private void usage(CommandSender sender, String usage) {
        send(sender, "&cUsage: /vigil " + usage);
    }

    // ---- tab completion ------------------------------------------------------------------------

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> options = new ArrayList<>();
        if (args.length == 1) {
            for (SubCommand sub : SUBCOMMANDS) {
                if (sender.hasPermission(sub.permission())) {
                    options.add(sub.name());
                }
            }
            options.add("help");
            return filter(options, args[0]);
        }
        SubCommand sub = find(args[0].toLowerCase(Locale.ROOT));
        if (sub == null || !sender.hasPermission(sub.permission())) {
            return List.of();
        }
        String name = sub.name();
        if (args.length == 2 && !name.equals("review") && !name.equals("alerts") && !name.equals("status")
                && !name.equals("reload")) {
            return filter(onlineNames(), args[1]);
        }
        if (args.length == 3 && (name.equals("reset") || name.equals("debug"))) {
            return filter(checkIds(), args[2]);
        }
        if (args.length == 3 && name.equals("exempt")) {
            options.addAll(checkIds());
            options.addAll(List.of("movement", "combat", "interaction", "all"));
            return filter(options, args[2]);
        }
        if (args.length == 4 && name.equals("exempt")) {
            return filter(List.of("30", "60", "300", "3600"), args[3]);
        }
        if (name.equals("review")) {
            if (args.length == 2) {
                return filter(List.of("list", "view", "claim", "resolve", "open"), args[1]);
            }
            String action = args[1].toLowerCase(Locale.ROOT);
            if (args.length == 3) {
                if (action.equals("list")) {
                    return filter(List.of("all"), args[2]);
                }
                if (action.equals("open")) {
                    return filter(onlineNames(), args[2]);
                }
                if (action.equals("view") || action.equals("claim") || action.equals("resolve")) {
                    for (ReviewCase reviewCase : plugin.review().list(!action.equals("view"))) {
                        options.add(String.valueOf(reviewCase.id()));
                    }
                    return filter(options, args[2]);
                }
            }
            if (args.length == 4 && action.equals("resolve")) {
                return filter(List.of("cheating", "legit", "inconclusive"), args[3]);
            }
        }
        return List.of();
    }

    private static List<String> onlineNames() {
        List<String> names = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            names.add(player.getName());
        }
        return names;
    }

    private static List<String> checkIds() {
        List<String> ids = new ArrayList<>();
        for (CheckType type : CheckType.values()) {
            ids.add(type.id());
        }
        return ids;
    }

    private static List<String> filter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(lower)) {
                result.add(option);
            }
        }
        return result;
    }
}
