package io.crewscope.domain.task;

import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;

/** Auditable pause or cancellation request currently being converged by the Worker. */
public record TaskExecutionControlRequest(
        TaskExecutionControlRequestType type,
        PrincipalId requestedByPrincipalId,
        UtcTimestamp requestedAt,
        String reason) {

    private static final int MAX_REASON_LENGTH = 1000;

    public TaskExecutionControlRequest {
        type = Objects.requireNonNull(type, "type");
        requestedByPrincipalId = Objects.requireNonNull(
                requestedByPrincipalId, "requestedByPrincipalId");
        requestedAt = Objects.requireNonNull(requestedAt, "requestedAt");
        reason = requireReason(reason);
    }

    private static String requireReason(String value) {
        if (value == null || value.isBlank()) {
            throw new DomainValidationException(
                    "taskExecution.controlRequest.reason", "must not be blank");
        }
        String normalized = value.strip();
        if (normalized.length() > MAX_REASON_LENGTH) {
            throw new DomainValidationException(
                    "taskExecution.controlRequest.reason", "must not exceed 1000 characters");
        }
        return normalized;
    }
}
