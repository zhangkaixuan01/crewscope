package io.crewscope.domain.agent;

import io.crewscope.domain.shared.error.DomainValidationException;
import java.util.Objects;
import java.util.UUID;

/** Stable identity and immutable version of an Agent budget policy. */
public record AgentBudgetPolicyReference(UUID policyId, long version) {

    public AgentBudgetPolicyReference {
        policyId = Objects.requireNonNull(policyId, "policyId");
        if (version < 1) {
            throw new DomainValidationException(
                    "agentConfiguration.budgetPolicy.version", "must be positive");
        }
    }
}
