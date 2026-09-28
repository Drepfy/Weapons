package io.github.drepfy.vigil.config;

import java.util.List;

/**
 * An optional automatic command rule: once a check's VL reaches {@code vl}, the
 * commands run (subject to the global punishment safeguards).
 */
public record ActionRule(double vl, List<String> commands) {

    public ActionRule {
        commands = List.copyOf(commands);
    }
}
