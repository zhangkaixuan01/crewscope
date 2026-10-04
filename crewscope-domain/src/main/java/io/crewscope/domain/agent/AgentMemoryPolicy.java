package io.crewscope.domain.agent;

import io.crewscope.domain.shared.error.DomainValidationException;
import java.util.Objects;
import java.util.UUID;

/**
 * Agent assistant memory policy (M10-I02a, S01 §3.6): the TTL / capacity / value-size
 * contract behind an {@link AgentMemoryPolicyReference}. Policies are a code catalog, not a
 * table — {@link #DEFAULT_POLICY_ID} pins the built-in default so configuration references
 * stay stable across restarts.
 */
public record AgentMemoryPolicy(
        UUID policyId, long version, int ttlDays, int maxEntriesPerOwner, int valueMaxBytes) {

    /** Stable identity of the built-in default policy; never regenerate or reuse. */
    public static final UUID DEFAULT_POLICY_ID =
            UUID.fromString("7f2c9d64-5b1a-4f0e-9a3d-2c8b1e6f4a20");

    /** S01 §3.6 frozen defaults: 90-day sliding TTL, 100 entries per member x Agent x
     * policy space (the capacity counts the current policy's space only), 1KB values. */
    public static AgentMemoryPolicy defaults() {
        return new AgentMemoryPolicy(DEFAULT_POLICY_ID, 1, 90, 100, 1024);
    }

    public AgentMemoryPolicy {
        policyId = Objects.requireNonNull(policyId, "policyId");
        if (version < 1) {
            throw new DomainValidationException(
                    "agentMemoryPolicy.version", "must be positive");
        }
        if (ttlDays < 1) {
            throw new DomainValidationException(
                    "agentMemoryPolicy.ttlDays", "must be positive");
        }
        if (maxEntriesPerOwner < 1) {
            throw new DomainValidationException(
                    "agentMemoryPolicy.maxEntriesPerOwner", "must be positive");
        }
        if (valueMaxBytes < 1) {
            throw new DomainValidationException(
                    "agentMemoryPolicy.valueMaxBytes", "must be positive");
        }
    }

    /** The immutable reference a configuration carries; equality is identity + version. */
    public AgentMemoryPolicyReference reference() {
        return new AgentMemoryPolicyReference(policyId, version);
    }
}
