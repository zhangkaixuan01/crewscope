package io.crewscope.domain.agent;

import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;

/**
 * One (member x Agent) memory space (M10-I02a, S01 §3.6): the monotonic
 * {@code clearance_generation} that makes cleared memory impossible to resurrect. Clearing
 * bumps the generation; every write carries the generation it observed and a stale
 * generation is rejected, so an in-flight write racing the clear can never revive old rows.
 */
public record AgentMemoryOwner(AgentMemoryOwnerKey key, long clearanceGeneration, AuditMetadata audit) {

    public AgentMemoryOwner {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(audit, "audit");
        if (clearanceGeneration < 0) {
            throw new DomainValidationException(
                    "agentMemory.clearanceGeneration", "must not be negative");
        }
    }

    /** Advances the generation by one; the entries of every earlier generation become invisible. */
    public AgentMemoryOwner cleared(PrincipalId actor, UtcTimestamp now) {
        return new AgentMemoryOwner(
                key, clearanceGeneration + 1, audit.modifiedBy(actor, now));
    }
}
