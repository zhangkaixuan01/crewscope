package io.crewscope.server.api;

import io.crewscope.domain.task.ExecutionLease;
import java.time.Instant;
import java.util.UUID;

/** One execution lease; claim and fencing tokens never leave the server. */
public record ExecutionLeaseResponse(
        UUID id,
        String environment,
        UUID runtimeId,
        UUID workerId,
        String phase,
        String status,
        Instant acquiredAt,
        Instant lastHeartbeatAt,
        Instant expiresAt,
        String releaseReason,
        Instant releasedAt,
        long version) {
    static ExecutionLeaseResponse from(ExecutionLease value) {
        return new ExecutionLeaseResponse(
                value.id().value(),
                value.environment().value(),
                value.runtimeId().value(),
                value.workerId().value(),
                value.phase().name(),
                value.release().isPresent() ? "RELEASED" : "ACTIVE",
                value.acquiredAt().value(),
                value.lastHeartbeatAt().value(),
                value.expiresAt().value(),
                value.release().map(release -> release.reason().name()).orElse(null),
                value.release().map(release -> release.releasedAt().value()).orElse(null),
                value.version());
    }
}
