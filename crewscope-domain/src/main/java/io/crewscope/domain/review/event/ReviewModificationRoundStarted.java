package io.crewscope.domain.review.event;

import io.crewscope.domain.review.ReviewModificationRound;
import io.crewscope.domain.shared.DomainEvent;
import java.util.Objects;
import java.util.UUID;

/** Event linking a CHANGES_REQUESTED decision to a continuous modification round. */
public record ReviewModificationRoundStarted(
        UUID roundId,
        UUID taskId,
        long roundNumber,
        UUID sourceReviewRequestId,
        long sourceReviewRequestRevision,
        UUID triggerDecisionId,
        String roundHash) implements DomainEvent {

    public ReviewModificationRoundStarted {
        roundId = Objects.requireNonNull(roundId, "roundId");
        taskId = Objects.requireNonNull(taskId, "taskId");
        sourceReviewRequestId = Objects.requireNonNull(
                sourceReviewRequestId, "sourceReviewRequestId");
        triggerDecisionId = Objects.requireNonNull(triggerDecisionId, "triggerDecisionId");
        if (roundNumber < 1 || sourceReviewRequestRevision < 1) {
            throw new IllegalArgumentException("Review modification counters must be positive");
        }
        roundHash = Objects.requireNonNull(roundHash, "roundHash");
    }

    public static ReviewModificationRoundStarted from(ReviewModificationRound round) {
        ReviewModificationRound value = Objects.requireNonNull(round, "round");
        return new ReviewModificationRoundStarted(
                value.id().value(),
                value.taskId().value(),
                value.roundNumber(),
                value.sourceRequest().id().value(),
                value.sourceRequest().revision(),
                value.triggerDecision().id().value(),
                value.roundHash().toString());
    }
}
