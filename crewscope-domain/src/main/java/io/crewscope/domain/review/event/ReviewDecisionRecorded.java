package io.crewscope.domain.review.event;

import io.crewscope.domain.review.ReviewDecision;
import io.crewscope.domain.shared.DomainEvent;
import java.util.Objects;
import java.util.UUID;

/** Safe member Gate event with eligibility mode and exact authority hashes. */
public record ReviewDecisionRecorded(
        UUID decisionId,
        UUID taskId,
        UUID reviewRequestId,
        long reviewRequestRevision,
        long decisionRevision,
        UUID reviewerPrincipalId,
        UUID reviewerMemberId,
        String eligibilityMode,
        String decisionType,
        String decisionHash) implements DomainEvent {

    public ReviewDecisionRecorded {
        decisionId = Objects.requireNonNull(decisionId, "decisionId");
        taskId = Objects.requireNonNull(taskId, "taskId");
        reviewRequestId = Objects.requireNonNull(reviewRequestId, "reviewRequestId");
        reviewerPrincipalId = Objects.requireNonNull(reviewerPrincipalId, "reviewerPrincipalId");
        reviewerMemberId = Objects.requireNonNull(reviewerMemberId, "reviewerMemberId");
        if (reviewRequestRevision < 1 || decisionRevision < 1) {
            throw new IllegalArgumentException("Review Decision revisions must be positive");
        }
        eligibilityMode = Objects.requireNonNull(eligibilityMode, "eligibilityMode");
        decisionType = Objects.requireNonNull(decisionType, "decisionType");
        decisionHash = Objects.requireNonNull(decisionHash, "decisionHash");
    }

    public static ReviewDecisionRecorded from(ReviewDecision decision) {
        ReviewDecision value = Objects.requireNonNull(decision, "decision");
        return new ReviewDecisionRecorded(
                value.id().value(),
                value.taskId().value(),
                value.reviewRequest().id().value(),
                value.reviewRequest().revision(),
                value.revision(),
                value.reviewerPrincipalId().value(),
                value.reviewerMemberId().value(),
                value.eligibility().mode().name(),
                value.type().name(),
                value.decisionHash().toString());
    }
}
