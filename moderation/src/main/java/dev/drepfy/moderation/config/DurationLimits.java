package dev.drepfy.moderation.config;

import dev.drepfy.moderation.model.PunishmentType;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Caps on how long staff may punish for, granted through permission-based tiers.
 */
public final class DurationLimits {

    /**
     * @param caps longest allowed length per type; a type missing from the map isn't capped by this tier
     */
    public record Tier(String name, String permission, Map<PunishmentType, Duration> caps) {
        public Tier {
            caps = Map.copyOf(caps);
        }
    }

    public static final DurationLimits NONE = new DurationLimits(List.of());

    private final List<Tier> tiers;

    public DurationLimits(List<Tier> tiers) {
        this.tiers = List.copyOf(tiers);
    }

    public List<Tier> tiers() {
        return tiers;
    }

    /**
     * The longest punishment of this type the actor may issue, or empty if they aren't capped.
     * Actors receive the most generous limit of all the tiers they belong to; actors in no tier
     * aren't capped at all.
     */
    public Optional<Duration> maxFor(PunishmentType type, Predicate<String> hasPermission) {
        Duration best = null;
        for (Tier tier : tiers) {
            if (!hasPermission.test(tier.permission())) {
                continue;
            }
            Duration cap = tier.caps().get(type);
            if (cap == null) {
                return Optional.empty();
            }
            if (best == null || cap.compareTo(best) > 0) {
                best = cap;
            }
        }
        return Optional.ofNullable(best);
    }
}
