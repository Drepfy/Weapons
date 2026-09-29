package io.github.drepfy.vigil.check.combat;

import io.github.drepfy.vigil.api.CheckType;
import io.github.drepfy.vigil.check.CheckContext;
import io.github.drepfy.vigil.config.CheckSettings;
import io.github.drepfy.vigil.data.PlayerData;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;

/**
 * Attacks without an arm swing. A vanilla client sends the swing together with every
 * attack (modern clients right after it, 1.8 clients through ViaVersion right before
 * it), so an attack is judged a few ticks later: a swing must have arrived within
 * {@code swing-window-ms} around it.
 */
public final class NoSwingCheck {

    private static final CheckType TYPE = CheckType.NOSWING;
    private static final long JUDGE_AFTER_MS = 150;
    private static final int MAX_PENDING = 512;

    private record Pending(UUID attacker, long attackMs) {
    }

    private final CheckContext ctx;
    private final Deque<Pending> pending = new ArrayDeque<>();

    public NoSwingCheck(CheckContext ctx) {
        this.ctx = ctx;
    }

    public void onAttack(Player attacker, PlayerData data, long now) {
        if (ctx.isActive(attacker, data, TYPE, now) && pending.size() < MAX_PENDING) {
            pending.addLast(new Pending(attacker.getUniqueId(), now));
        }
    }

    public void processPending(long now) {
        while (!pending.isEmpty() && now - pending.peekFirst().attackMs() >= JUDGE_AFTER_MS) {
            Pending entry = pending.removeFirst();
            Player attacker = Bukkit.getPlayer(entry.attacker());
            PlayerData data = attacker != null ? ctx.players().peek(attacker.getUniqueId()) : null;
            if (data != null) {
                ctx.run(TYPE, now, () -> evaluate(attacker, data, entry.attackMs(), now));
            }
        }
    }

    private void evaluate(Player attacker, PlayerData data, long attackMs, long now) {
        CheckSettings settings = ctx.settings(TYPE);
        if (data.lastSwingMs >= attackMs - settings.millis("swing-window-ms")) {
            data.buffer(TYPE).reduce(0.5);
            return;
        }
        if (!ctx.isActive(attacker, data, TYPE, now) || !ctx.canFlag(attacker, data, now)) {
            return;
        }
        double buffer = data.buffer(TYPE).add(1.0, now, 0.05);
        if (buffer < settings.bufferThreshold()) {
            return;
        }
        data.buffer(TYPE).reset();
        ctx.flag(attacker, data, TYPE, "attacked without swinging the arm");
    }

    public void clear() {
        pending.clear();
    }
}
