package io.crewscope.domain.review.event;

import io.crewscope.domain.review.ReviewRequest;
import io.crewscope.domain.review.ReviewRequestStatus;
import io.crewscope.domain.shared.DomainEvent;
import java.util.Objects;
import java.util.UUID;

/** Event emitted after an old ReviewRequest is irreversibly invalidated. */
public record ReviewRequestInvalidated(
        UUID reviewRequestId,
        UUID taskId,
        UUID taskExecutionId,
        int attempt,
        long reviewRequestRevision,
        long aggregateVersion,
        String reason) implements DomainEvent {

    public ReviewRequestInvalidated {
        reviewRequestId = Objects.requireNonNull(reviewRequestId, "reviewRequestId");
        taskId = Objects.requireNonNull(taskId, "taskId");
        taskExecutionId = Objects.requireNonNull(taskExecutionId, "taskExecutionId");
        if (attempt < 1 || reviewRequestRevision < 1 || aggregateVersion < 1) {
            throw new IllegalArgumentException("invalidated ReviewRequest counters are invalid");
        }
        reason = Objects.requireNonNull(reason, "reason");
    }

    public static ReviewRequestInvalidated from(ReviewRequest request) {
        ReviewRequest value = Objects.requireNonNull(request, "request");
        if (value.status() != ReviewRequestStatus.INVALIDATED) {
            throw new IllegalArgumentException("ReviewRequest must be INVALIDATED");
        }
        return new ReviewRequestInvalidated(
                value.id().value(),
                value.taskId().value(),
                value.taskExecutionId().value(),
                value.attempt(),
                value.revision(),
                value.version(),
                value.invalidationReason().orElseThrow().name());
    }
}
