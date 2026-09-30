package io.github.drepfy.vigil.violation;

import io.github.drepfy.vigil.api.CheckType;
import org.bukkit.entity.Player;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Prints check diagnostics to the console when {@code advanced.debug} is on.
 * Message text is only built when debugging is on.
 */
public final class DebugService {

    private final Logger logger;
    private final BooleanSupplier consoleDebug;

    public DebugService(Logger logger, BooleanSupplier consoleDebug) {
        this.logger = logger;
        this.consoleDebug = consoleDebug;
    }

    public boolean isWatched(java.util.UUID target) {
        return consoleDebug.getAsBoolean();
    }

    public void debug(Player target, CheckType type, Supplier<String> message) {
        if (consoleDebug.getAsBoolean()) {
            logger.info("[debug] " + target.getName() + " " + type.displayName() + ": " + message.get());
        }
    }
}
