package io.crewscope.domain.task;

import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;

/** Immutable fact explaining who terminated a Task Token grant and why. */
public record TaskCredentialGrantTermination(
        TaskCredentialGrantStatus status,
        PrincipalId terminatedByPrincipalId,
        UtcTimestamp terminatedAt,
        String reason) {

    public TaskCredentialGrantTermination {
        status = Objects.requireNonNull(status, "status");
        if (status == TaskCredentialGrantStatus.ACTIVE) {
            throw new DomainValidationException(
                    "taskCredentialGrant.termination.status", "must be terminal");
        }
        terminatedByPrincipalId = Objects.requireNonNull(
                terminatedByPrincipalId, "terminatedByPrincipalId");
        terminatedAt = Objects.requireNonNull(terminatedAt, "terminatedAt");
        if (reason == null || reason.isBlank() || reason.strip().length() > 500) {
            throw new DomainValidationException(
                    "taskCredentialGrant.termination.reason",
                    "must contain between 1 and 500 characters");
        }
        reason = reason.strip();
    }
}
