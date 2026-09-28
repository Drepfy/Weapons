package dev.drepfy.moderation.model;

import dev.drepfy.moderation.util.Cooldowns;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PunishmentTest {

    private static final UUID PLAYER = UUID.randomUUID();
    private static final long NOW = 10_000L;

    private static Punishment mute(long id, Long expiresAt, boolean active) {
        return new Punishment(id, PunishmentType.MUTE, PLAYER, "Steve", Actor.system("Console"), "r", "s",
                0, expiresAt, active, false, null);
    }

    @Test
    void strongestPrefersPermanentThenLatestExpiry() {
        Punishment shortMute = mute(1, NOW + 10, true);
        Punishment longMute = mute(2, NOW + 1000, true);
        Punishment permanent = mute(3, null, true);
        assertEquals(Optional.of(longMute), Punishment.strongest(List.of(shortMute, longMute), PunishmentType.MUTE, NOW));
        assertEquals(Optional.of(permanent), Punishment.strongest(List.of(shortMute, permanent, longMute), PunishmentType.MUTE, NOW));
        assertEquals(Optional.empty(), Punishment.strongest(List.of(shortMute), PunishmentType.BAN, NOW));
    }

    @Test
    void strongestIgnoresLiftedAndExpired() {
        assertEquals(Optional.empty(), Punishment.strongest(List.of(mute(1, null, false), mute(2, NOW, true)), PunishmentType.MUTE, NOW));
    }

    @Test
    void typeKeysRoundTrip() {
        for (PunishmentType type : PunishmentType.values()) {
            assertEquals(Optional.of(type), PunishmentType.fromKey(type.key()));
        }
        assertEquals(Optional.of(PunishmentType.VOICE_MUTE), PunishmentType.fromKey("Voice_Mute"));
        assertEquals(Optional.empty(), PunishmentType.fromKey("jail"));
    }

    @Test
    void cooldownsRateLimitPerPlayerAndKey() {
        Cooldowns cooldowns = new Cooldowns();
        assertTrue(cooldowns.tryAcquire(PLAYER, "voice", 0, 1000));
        assertFalse(cooldowns.tryAcquire(PLAYER, "voice", 999, 1000));
        assertTrue(cooldowns.tryAcquire(PLAYER, "chat", 999, 1000));
        assertTrue(cooldowns.tryAcquire(PLAYER, "voice", 1000, 1000));
        cooldowns.clear(PLAYER);
        assertTrue(cooldowns.tryAcquire(PLAYER, "voice", 1001, 1000));
    }
}
