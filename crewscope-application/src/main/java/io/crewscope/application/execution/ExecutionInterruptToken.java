package io.crewscope.application.execution;

import java.util.Objects;

/** Opaque server-side reference to one currently pending runtime interrupt. */
public record ExecutionInterruptToken(String value) {

    public ExecutionInterruptToken {
        value = Objects.requireNonNull(value, "value").strip();
        if (value.isEmpty()
                || value.length() > 512
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(
                    "interrupt token must contain 1 to 512 printable characters");
        }
    }

    @Override
    public String toString() {
        return "ExecutionInterruptToken[value=[REDACTED]]";
    }
}
