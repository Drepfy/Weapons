package io.github.drepfy.lifesteal.alt;

import io.github.drepfy.lifesteal.config.Settings;
import io.github.drepfy.lifesteal.data.LifestealStore;
import io.github.drepfy.lifesteal.util.Durations;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Stops players moving hearts between their own accounts. A kill is not counted when any
 * check finds a reason:
 * <ul>
 *   <li><b>linked</b>: staff linked the two accounts ({@code /lifesteal alts link});</li>
 *   <li><b>shared-ip</b>: both accounts joined from the same IP address recently (unless staff
 *   marked them as different people with {@code /lifesteal alts allow});</li>
 *   <li><b>playtime</b>: one of the accounts is brand new.</li>
 * </ul>
 * Other plugins can add checks with {@link #register}.
 */
public final class AltProtection {

    private final Supplier<Settings> settings;
    private final LifestealStore store;
    private final LongSupplier clock;
    private final Logger logger;
    private final List<AltCheck> checks = new CopyOnWriteArrayList<>();
    private boolean warnedLocal;

    public AltProtection(Supplier<Settings> settings, LifestealStore store, LongSupplier clock, Logger logger) {
        this.settings = settings;
        this.store = store;
        this.clock = clock;
        this.logger = logger;
        checks.add(new Linked());
        checks.add(new SharedIp());
        checks.add(new Playtime());
    }

    /** Adds a check (for example from another plugin). */
    public void register(AltCheck check) {
        checks.add(check);
    }

    public List<AltCheck> checks() {
        return List.copyOf(checks);
    }

    /** @return why the kill must not count, or {@code null} when it may */
    public String check(Player killer, Player victim) {
        if (!settings.get().alts().enabled()) {
            return null;
        }
        for (AltCheck check : checks) {
            String reason = check.check(killer, victim);
            if (reason != null) {
                return reason;
            }
        }
        return null;
    }

    /** Remembers where a player joined from. Local addresses (a proxy without IP forwarding) are skipped. */
    public void recordJoin(Player player, InetAddress address) {
        if (address == null) {
            return;
        }
        if (address.isLoopbackAddress() || address.isAnyLocalAddress()) {
            if (!warnedLocal) {
                warnedLocal = true;
                logger.warning("Players join from " + address.getHostAddress() + ". If you use BungeeCord or "
                        + "Velocity, turn on IP forwarding so the shared IP check can see real addresses.");
            }
            return;
        }
        store.recordIp(player.getUniqueId(), address.getHostAddress(), clock.getAsLong());
    }

    /** Accounts that share a recent IP address with this one (for staff). */
    public Set<UUID> sharingIp(UUID uuid) {
        Settings.AltProtection config = settings.get().alts();
        Set<UUID> result = new HashSet<>();
        for (String ip : store.ipsSince(uuid, clock.getAsLong() - config.rememberIpMs())) {
            Set<UUID> accounts = store.accountsOnIp(ip);
            if (accounts.size() <= config.maxAccountsPerIp()) {
                result.addAll(accounts);
            }
        }
        result.remove(uuid);
        return result;
    }

    /** Staff linked the accounts. */
    private final class Linked implements AltCheck {
        @Override
        public String id() {
            return "linked";
        }

        @Override
        public String check(Player killer, Player victim) {
            return store.linked(killer.getUniqueId(), victim.getUniqueId()) ? "accounts linked by staff" : null;
        }
    }

    /** Both accounts used the same IP address recently. */
    private final class SharedIp implements AltCheck {
        @Override
        public String id() {
            return "shared-ip";
        }

        @Override
        public String check(Player killer, Player victim) {
            Settings.AltProtection config = settings.get().alts();
            if (!config.sharedIp() || store.allowed(killer.getUniqueId(), victim.getUniqueId())) {
                return null;
            }
            long since = clock.getAsLong() - config.rememberIpMs();
            Set<String> shared = store.ipsSince(killer.getUniqueId(), since);
            shared.retainAll(store.ipsSince(victim.getUniqueId(), since));
            for (String ip : shared) {
                // An address used by many accounts is a shared network (proxy, school, public Wi-Fi).
                if (store.accountsOnIp(ip).size() <= config.maxAccountsPerIp()) {
                    return "same IP address";
                }
            }
            return null;
        }
    }

    /** One of the accounts has hardly played (fresh alts). */
    private final class Playtime implements AltCheck {
        @Override
        public String id() {
            return "playtime";
        }

        @Override
        public String check(Player killer, Player victim) {
            long min = settings.get().alts().minPlaytimeMs();
            if (min <= 0) {
                return null;
            }
            List<String> newAccounts = new ArrayList<>();
            for (Player player : List.of(killer, victim)) {
                if (playtimeMs(player) < min) {
                    newAccounts.add(player.getName());
                }
            }
            return newAccounts.isEmpty() ? null
                    : "new account (" + String.join(", ", newAccounts) + " played less than " + Durations.format(min) + ")";
        }
    }

    /** Time played on this server (Minecraft counts it in ticks). */
    static long playtimeMs(Player player) {
        try {
            return player.getStatistic(Statistic.PLAY_ONE_MINUTE) * 50L;
        } catch (RuntimeException e) {
            return Long.MAX_VALUE; // Unknown: do not block.
        }
    }
}
