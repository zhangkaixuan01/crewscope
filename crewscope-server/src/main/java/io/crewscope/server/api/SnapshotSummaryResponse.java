package io.crewscope.server.api;

import io.crewscope.domain.task.AgentStateSnapshot;
import java.time.Instant;
import java.util.UUID;

/** Agent state snapshot metadata; payload references and hashes never leave the server. */
public record SnapshotSummaryResponse(
        UUID id,
        UUID agentRunId,
        UUID runtimeSessionId,
        UUID agentProfileId,
        long agentProfileVersion,
        long snapshotSequence,
        long checkpointSequence,
        long sizeBytes,
        String status,
        String invalidReasonCode,
        long version,
        AuditResponse audit) {
    static SnapshotSummaryResponse from(AgentStateSnapshot value) {
        return new SnapshotSummaryResponse(
                value.id().value(),
                value.agentRunId().value(),
                value.runtimeSessionId().value(),
                value.agentProfileId().value(),
                value.agentProfileVersion(),
                value.snapshotSequence(),
                value.checkpointSequence(),
                value.size(),
                value.status().name(),
                value.invalidReasonCode().orElse(null),
                value.version(),
                AuditResponse.from(value.audit()));
    }
}
