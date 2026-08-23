package io.crewscope.domain.agent;

import io.crewscope.domain.shared.error.DomainValidationException;
import java.util.Objects;
import java.util.UUID;

/** Stable identity and immutable version of an Agent memory policy. */
public record AgentMemoryPolicyReference(UUID policyId, long version) {

    public AgentMemoryPolicyReference {
        policyId = Objects.requireNonNull(policyId, "policyId");
        if (version < 1) {
            throw new DomainValidationException(
                    "agentConfiguration.memoryPolicy.version", "must be positive");
        }
    }
}
