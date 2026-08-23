package io.crewscope.domain.agent;

import io.crewscope.domain.shared.error.DomainValidationException;
import java.util.regex.Pattern;

/** Stable Tool key exposed by the fixed runtime surface of an Agent template. */
public record AgentToolKey(String value) implements Comparable<AgentToolKey> {

    private static final Pattern FORMAT = Pattern.compile("[a-z][a-z0-9_.-]{0,127}");

    public AgentToolKey {
        if (value == null || !FORMAT.matcher(value.strip()).matches()) {
            throw new DomainValidationException(
                    "agentTemplate.toolKey", "must be a lower-case stable Tool key");
        }
        value = value.strip();
    }

    @Override
    public int compareTo(AgentToolKey other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
