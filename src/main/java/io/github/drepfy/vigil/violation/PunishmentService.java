package io.github.drepfy.vigil.violation;

import io.github.drepfy.vigil.config.ActionRule;
import io.github.drepfy.vigil.config.CheckSettings;
import io.github.drepfy.vigil.config.Settings;
import io.github.drepfy.vigil.data.FlagRecord;
import io.github.drepfy.vigil.data.PlayerData;
import io.github.drepfy.vigil.util.Clock;
import io.github.drepfy.vigil.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Optional automatic commands. Disabled by default. Even when enabled, a rule only
 * fires if all of the following hold:
 * <ul>
 *   <li>the check's VL reached the rule's threshold,</li>
 *   <li>the player was flagged by that check at least {@code min-flags-in-window}
 *   times within the window (one uncertain detection is never enough),</li>
 *   <li>no rule of that check ran for this player within the cooldown,</li>
 *   <li>the rule is higher than the last one executed in this "episode" (the episode
 *   restarts once the VL decays below it).</li>
 * </ul>
 */
public final class PunishmentService {

    /** Names that are safe to paste into a console command. */
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9_.*\\-]{1,32}");

    private final Supplier<Settings> settings;
    private final Logger logger;
    private final Consumer<String> auditLog;

    public PunishmentService(Supplier<Settings> settings, Logger logger, Consumer<String> auditLog) {
        this.settings = settings;
        this.logger = logger;
        this.auditLog = auditLog;
    }

    public void onFlag(Player player, PlayerData data, FlagRecord flag, CheckSettings check) {
        Settings.Punishments config = settings.get().punishments();
        List<ActionRule> rules = check.actions();
        if (!config.enabled() || rules.isEmpty()) {
            return;
        }
        long now = Clock.now();
        Deque<Long> window = data.punishWindow.computeIfAbsent(flag.check(), key -> new ArrayDeque<>());
        window.addLast(now);
        while (!window.isEmpty() && now - window.peekFirst() > config.windowMs()) {
            window.removeFirst();
        }
        while (window.size() > 10_000) {
            window.removeFirst();
        }

        Double executed = data.highestExecutedRule.get(flag.check());
        if (executed != null && flag.vl() < executed) {
            data.highestExecutedRule.remove(flag.check());
            executed = null;
        }
        if (window.size() < config.minFlagsInWindow()) {
            return;
        }
        Long last = data.lastPunishMs.get(flag.check());
        if (last != null && now - last < config.cooldownMs()) {
            return;
        }

        ActionRule chosen = null;
        for (ActionRule rule : rules) {
            if (flag.vl() >= rule.vl() && (executed == null || rule.vl() > executed)) {
                chosen = rule;
            }
        }
        if (chosen == null) {
            return;
        }
        if (!SAFE_NAME.matcher(player.getName()).matches()) {
            logger.warning("Not running automatic commands for '" + player.getName()
                    + "': the name contains characters that are unsafe in commands.");
            return;
        }

        data.highestExecutedRule.put(flag.check(), chosen.vl());
        data.lastPunishMs.put(flag.check(), now);
        for (String template : chosen.commands()) {
            String command = Text.replace(template,
                    "player", player.getName(),
                    "uuid", player.getUniqueId(),
                    "check", flag.check().displayName(),
                    "vl", Text.num(flag.vl()));
            if (config.dryRun()) {
                String line = "[dry-run] would run: /" + command + " (" + flag.check().displayName() + " VL "
                        + Text.num(flag.vl()) + ", " + window.size() + " flags in window)";
                logger.info(line);
                auditLog.accept(line);
                continue;
            }
            String line = "Running automatic command: /" + command + " (" + flag.check().displayName() + " VL "
                    + Text.num(flag.vl()) + ", " + window.size() + " flags in window)";
            logger.info(line);
            auditLog.accept(line);
            try {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
            } catch (RuntimeException e) {
                logger.warning("Automatic command failed: /" + command + " - " + e.getMessage());
            }
        }
    }
}
