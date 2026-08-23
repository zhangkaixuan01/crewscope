package io.crewscope.domain.agent;

import io.crewscope.domain.shared.error.DomainValidationException;

/** Positive, consecutive revision of one scoped Agent model default chain. */
public record AgentModelDefaultRevision(long value) implements Comparable<AgentModelDefaultRevision> {

    public AgentModelDefaultRevision {
        if (value < 1) {
            throw new DomainValidationException(
                    "agentModelDefault.revision", "must be positive");
        }
    }

    public AgentModelDefaultRevision next() {
        if (value == Long.MAX_VALUE) {
            throw new DomainValidationException(
                    "agentModelDefault.revision", "must not overflow");
        }
        return new AgentModelDefaultRevision(value + 1);
    }

    @Override
    public int compareTo(AgentModelDefaultRevision other) {
        return Long.compare(value, other.value);
    }
}
