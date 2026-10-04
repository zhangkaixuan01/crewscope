package io.crewscope.application.memory;

import io.crewscope.domain.agent.AgentMemoryEntry;
import io.crewscope.domain.agent.AgentMemoryPolicy;
import io.crewscope.domain.agent.AgentMemoryPolicyReference;
import java.util.List;
import java.util.Optional;

/**
 * The member-facing projection of one Agent's assistant memory (M10-I02a). Three states are
 * derivable: {@code policyReference} empty = memory not configured on the current Agent
 * configuration (off); both present = healthy; reference present but {@code policy} empty =
 * the referenced policy version is not resolvable and the memory answers degraded.
 */
public record AgentMemoryView(
        Optional<AgentMemoryPolicyReference> policyReference,
        Optional<AgentMemoryPolicy> policy,
        long clearanceGeneration,
        List<AgentMemoryEntry> entries) {

    public AgentMemoryView {
        policyReference = Optional.ofNullable(policyReference).orElse(Optional.empty());
        policy = Optional.ofNullable(policy).orElse(Optional.empty());
        entries = List.copyOf(entries);
    }

    /** True when the configuration references a policy the catalog cannot resolve. */
    public boolean policyUnavailable() {
        return policyReference.isPresent() && policy.isEmpty();
    }
}
