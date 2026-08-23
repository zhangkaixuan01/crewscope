package io.crewscope.domain.review.event;

import io.crewscope.domain.review.ReviewFinding;
import io.crewscope.domain.shared.DomainEvent;
import java.util.Objects;
import java.util.UUID;

/** Safe first-observation event without model prompts or Patch content. */
public record ReviewFindingRecorded(
        UUID findingId,
        UUID taskId,
        UUID taskExecutionId,
        int attempt,
        UUID reviewRequestId,
        long reviewRequestRevision,
        String fingerprint,
        String severity,
        String category,
        String reviewerRelationship) implements DomainEvent {

    public ReviewFindingRecorded {
        findingId = Objects.requireNonNull(findingId, "findingId");
        taskId = Objects.requireNonNull(taskId, "taskId");
        taskExecutionId = Objects.requireNonNull(taskExecutionId, "taskExecutionId");
        reviewRequestId = Objects.requireNonNull(reviewRequestId, "reviewRequestId");
        if (attempt < 1 || reviewRequestRevision < 1) {
            throw new IllegalArgumentException("Review Finding counters must be positive");
        }
        fingerprint = Objects.requireNonNull(fingerprint, "fingerprint");
        severity = Objects.requireNonNull(severity, "severity");
        category = Objects.requireNonNull(category, "category");
        reviewerRelationship = Objects.requireNonNull(
                reviewerRelationship, "reviewerRelationship");
    }

    public static ReviewFindingRecorded from(ReviewFinding finding) {
        ReviewFinding value = Objects.requireNonNull(finding, "finding");
        return new ReviewFindingRecorded(
                value.id().value(),
                value.reviewRequest().taskId().value(),
                value.reviewRequest().taskExecutionId().value(),
                value.reviewRequest().attempt(),
                value.reviewRequest().id().value(),
                value.reviewRequest().revision(),
                value.fingerprint().toString(),
                value.severity().name(),
                value.category().name(),
                value.reviewerRelationship().name());
    }
}
