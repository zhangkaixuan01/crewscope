package io.crewscope.domain.task;

import io.crewscope.domain.shared.id.AggregateId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;
import java.util.UUID;

/** Idempotent receipt for one accepted Resume request. */
public record AgentInterruptResolution(
        UUID resumeRequestId,
        RuntimeContentHash responseHash,
        PrincipalId resolvedBy,
        UtcTimestamp resolvedAt) {

    public AgentInterruptResolution {
        resumeRequestId = Objects.requireNonNull(resumeRequestId, "resumeRequestId");
        if (AggregateId.NIL_UUID.equals(resumeRequestId)) {
            throw new IllegalArgumentException("resumeRequestId must not use the nil UUID");
        }
        responseHash = Objects.requireNonNull(responseHash, "responseHash");
        resolvedBy = Objects.requireNonNull(resolvedBy, "resolvedBy");
        resolvedAt = Objects.requireNonNull(resolvedAt, "resolvedAt");
    }

    boolean matches(UUID requestId, RuntimeContentHash hash) {
        return resumeRequestId.equals(requestId) && responseHash.equals(hash);
    }
}
