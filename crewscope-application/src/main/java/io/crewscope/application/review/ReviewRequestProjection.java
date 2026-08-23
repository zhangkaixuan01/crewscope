package io.crewscope.application.review;

import io.crewscope.domain.review.ReviewDecisionId;
import io.crewscope.domain.review.ReviewDecisionType;
import io.crewscope.domain.review.ReviewInvalidationReason;
import io.crewscope.domain.review.ReviewRequestId;
import io.crewscope.domain.review.ReviewRequestStatus;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskFactHash;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.workitem.WorkItemScope;
import java.util.Objects;
import java.util.Optional;

/** Safe, rebuildable Review workbench summary derived only from authoritative Review facts. */
public record ReviewRequestProjection(
        ReviewRequestId reviewRequestId,
        WorkItemScope scope,
        TaskId taskId,
        TaskExecutionId taskExecutionId,
        int attempt,
        long requestRevision,
        long requestVersion,
        ReviewRequestStatus status,
        Optional<ReviewInvalidationReason> invalidationReason,
        TaskFactHash contextHash,
        int findingCount,
        int duplicateObservationCount,
        int blockerCount,
        int highCount,
        Optional<ReviewDecisionId> latestDecisionId,
        Optional<Long> latestDecisionRevision,
        Optional<ReviewDecisionType> latestDecisionType,
        long modificationRound,
        UtcTimestamp projectedAt) {

    public ReviewRequestProjection {
        reviewRequestId = Objects.requireNonNull(reviewRequestId, "reviewRequestId");
        scope = Objects.requireNonNull(scope, "scope");
        taskId = Objects.requireNonNull(taskId, "taskId");
        taskExecutionId = Objects.requireNonNull(taskExecutionId, "taskExecutionId");
        status = Objects.requireNonNull(status, "status");
        invalidationReason = Objects.requireNonNull(invalidationReason, "invalidationReason");
        contextHash = Objects.requireNonNull(contextHash, "contextHash");
        latestDecisionId = Objects.requireNonNull(latestDecisionId, "latestDecisionId");
        latestDecisionRevision = Objects.requireNonNull(
                latestDecisionRevision, "latestDecisionRevision");
        latestDecisionType = Objects.requireNonNull(latestDecisionType, "latestDecisionType");
        projectedAt = Objects.requireNonNull(projectedAt, "projectedAt");
        if (attempt < 1 || requestRevision < 1 || requestVersion < 0
                || findingCount < 0 || duplicateObservationCount < 0
                || blockerCount < 0 || highCount < 0 || modificationRound < 0) {
            throw new IllegalArgumentException("Review projection counters are invalid");
        }
        boolean hasDecision = latestDecisionId.isPresent();
        if (hasDecision != latestDecisionRevision.isPresent()
                || hasDecision != latestDecisionType.isPresent()) {
            throw new IllegalArgumentException("Latest Review Decision coordinates are incomplete");
        }
    }
}
