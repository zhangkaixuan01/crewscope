package io.crewscope.domain.agent;

import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * One frozen preference value inside a member's memory space (M10-I02a, S01 §3.6): a named
 * key, at most {@code 1024} UTF-8 bytes of text, the policy space it belongs to, the
 * clearance generation it was written under, and the sliding-TTL deadline. Values are plain
 * preference strings — never raw conversation copies.
 */
public record AgentMemoryEntry(
        AgentMemoryOwnerKey owner,
        AgentMemoryPolicyReference policy,
        AgentMemoryKey memoryKey,
        String value,
        long clearanceGeneration,
        long version,
        UtcTimestamp expiresAt,
        AuditMetadata audit) {

    /** Frozen upper bound on the value (UTF-8 bytes, not characters); mirrors V58's CHECK. */
    public static final int VALUE_MAX_BYTES = 1024;

    public AgentMemoryEntry {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(memoryKey, "memoryKey");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(audit, "audit");
        if (value.isBlank()) {
            throw new DomainValidationException("agentMemory.value", "must not be blank");
        }
        if (value.getBytes(StandardCharsets.UTF_8).length > VALUE_MAX_BYTES) {
            throw new DomainValidationException(
                    "agentMemory.value", "must not exceed " + VALUE_MAX_BYTES + " UTF-8 bytes");
        }
        if (clearanceGeneration < 0) {
            throw new DomainValidationException(
                    "agentMemory.clearanceGeneration", "must not be negative");
        }
        if (version < 0) {
            throw new DomainValidationException("agentMemory.version", "must not be negative");
        }
    }

    /** A first write under the current clearance generation; version starts at 0. */
    public static AgentMemoryEntry write(
            AgentMemoryOwnerKey owner,
            AgentMemoryPolicyReference policy,
            AgentMemoryKey memoryKey,
            String value,
            long clearanceGeneration,
            UtcTimestamp expiresAt,
            PrincipalId actor,
            UtcTimestamp now) {
        return new AgentMemoryEntry(
                owner, policy, memoryKey, value, clearanceGeneration, 0,
                expiresAt, AuditMetadata.createdBy(actor, now));
    }

    /** A committed value rewrite: version advances and the TTL deadline is refreshed. */
    public AgentMemoryEntry overwrite(String nextValue, UtcTimestamp newExpiresAt, PrincipalId actor, UtcTimestamp now) {
        return new AgentMemoryEntry(
                owner, policy, memoryKey, nextValue, clearanceGeneration, version + 1,
                newExpiresAt, audit.modifiedBy(actor, now));
    }

    /** A committed use-renewal (TTL sliding): value untouched, version advances. */
    public AgentMemoryEntry renewed(UtcTimestamp newExpiresAt, PrincipalId actor, UtcTimestamp now) {
        return new AgentMemoryEntry(
                owner, policy, memoryKey, value, clearanceGeneration, version + 1,
                newExpiresAt, audit.modifiedBy(actor, now));
    }

    /** Full-argument reconstruction for persistence; re-runs every invariant. */
    public static AgentMemoryEntry reconstitute(
            AgentMemoryOwnerKey owner,
            AgentMemoryPolicyReference policy,
            AgentMemoryKey memoryKey,
            String value,
            long clearanceGeneration,
            long version,
            UtcTimestamp expiresAt,
            AuditMetadata audit) {
        return new AgentMemoryEntry(
                owner, policy, memoryKey, value, clearanceGeneration, version, expiresAt, audit);
    }
}
