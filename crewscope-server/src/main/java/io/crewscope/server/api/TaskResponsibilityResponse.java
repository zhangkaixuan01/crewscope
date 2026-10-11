package io.crewscope.server.api;

import io.crewscope.domain.task.TaskResponsibilitySnapshotEntry;
import java.time.Instant;
import java.util.UUID;

/** One pinned responsibility pin captured when the Task was created. */
public record TaskResponsibilityResponse(
        UUID assignmentId,
        long assignmentVersion,
        String role,
        UUID principalId,
        String principalType,
        UUID memberId,
        Instant assignedAt,
        Instant acceptedAt) {
    static TaskResponsibilityResponse from(TaskResponsibilitySnapshotEntry value) {
        return new TaskResponsibilityResponse(
                value.assignmentId().value(),
                value.assignmentVersion(),
                value.role().name(),
                value.principalId().value(),
                value.principalType().name(),
                value.memberId().map(id -> id.value()).orElse(null),
                value.assignedAt().value(),
                value.acceptedAt().value());
    }
}
