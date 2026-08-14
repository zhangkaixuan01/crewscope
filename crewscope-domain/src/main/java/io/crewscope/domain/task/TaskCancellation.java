package io.crewscope.domain.task;

import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;

/** Immutable user-visible cancellation fact of a business Task. */
public record TaskCancellation(
        PrincipalId cancelledByPrincipalId, UtcTimestamp cancelledAt, String reason) {

    public static final int MAX_REASON_LENGTH = 2_000;

    public TaskCancellation {
        cancelledByPrincipalId =
                Objects.requireNonNull(cancelledByPrincipalId, "cancelledByPrincipalId");
        cancelledAt = Objects.requireNonNull(cancelledAt, "cancelledAt");
        if (reason == null || reason.isBlank()) {
            throw new DomainValidationException("task.cancellation.reason", "must not be blank");
        }
        reason = reason.strip();
        if (reason.length() > MAX_REASON_LENGTH) {
            throw new DomainValidationException(
                    "task.cancellation.reason",
                    "must contain at most " + MAX_REASON_LENGTH + " characters");
        }
    }
}
