package io.crewscope.application.memory;

import io.crewscope.domain.agent.AgentMemoryPolicy;
import io.crewscope.domain.agent.AgentMemoryPolicyReference;
import java.util.Optional;

/**
 * The built-in code-catalog policy (M10-I02a): exactly the frozen S01 §3.6 default
 * (90-day sliding TTL, 100 entries, 1KB values) under the stable {@link AgentMemoryPolicy#DEFAULT_POLICY_ID}.
 * This is the entity behind the previously dangling configuration reference; adding a future
 * policy version means adding it here and keeping old versions resolvable.
 */
public final class DefaultAgentMemoryPolicyCatalog implements AgentMemoryPolicyCatalog {

    @Override
    public Optional<AgentMemoryPolicy> resolve(AgentMemoryPolicyReference reference) {
        if (reference.policyId().equals(AgentMemoryPolicy.DEFAULT_POLICY_ID)
                && reference.version() == AgentMemoryPolicy.defaults().version()) {
            return Optional.of(AgentMemoryPolicy.defaults());
        }
        return Optional.empty();
    }
}
