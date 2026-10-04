package io.crewscope.application.memory;

import io.crewscope.domain.agent.AgentMemoryPolicy;
import io.crewscope.domain.agent.AgentMemoryPolicyReference;
import java.util.Optional;

/**
 * Resolution Port from a configuration's {@link AgentMemoryPolicyReference} to the policy
 * contract behind it (M10-I02a). Policies are a code catalog, not a table: an empty result
 * surfaces as the explicit POLICY_UNAVAILABLE degradation, never as a silent empty memory —
 * except on the injection read, which yields zero entries with no signal by the I02b
 * switch-matrix freeze (see AgentMemoryService#list).
 */
public interface AgentMemoryPolicyCatalog {

    /** The policy contract for the referenced id@version, if the catalog still serves it. */
    Optional<AgentMemoryPolicy> resolve(AgentMemoryPolicyReference reference);
}
