package io.crewscope.domain.agent;

import io.crewscope.domain.shared.error.DomainValidationException;

/** Positive, consecutive revision of one AgentProfile configuration chain. */
public record AgentConfigurationRevision(long value) implements Comparable<AgentConfigurationRevision> {

    public AgentConfigurationRevision {
        if (value < 1) {
            throw new DomainValidationException(
                    "agentConfiguration.revision", "must be positive");
        }
    }

    public AgentConfigurationRevision next() {
        if (value == Long.MAX_VALUE) {
            throw new DomainValidationException(
                    "agentConfiguration.revision", "must not overflow");
        }
        return new AgentConfigurationRevision(value + 1);
    }

    @Override
    public int compareTo(AgentConfigurationRevision other) {
        return Long.compare(value, other.value);
    }

    @Override
    public String toString() {
        return Long.toString(value);
    }
}
