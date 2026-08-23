package io.crewscope.domain.review.event;

import io.crewscope.domain.review.ReviewFindingObservation;
import io.crewscope.domain.shared.DomainEvent;
import java.util.Objects;
import java.util.UUID;

/** Audit event for a later model candidate merged into the first Finding. */
public record ReviewFindingDuplicateObserved(
        UUID observationId,
        UUID findingId,
        UUID reviewRequestId,
        long observationNumber,
        String fingerprint,
        String candidateHash) implements DomainEvent {

    public ReviewFindingDuplicateObserved {
        observationId = Objects.requireNonNull(observationId, "observationId");
        findingId = Objects.requireNonNull(findingId, "findingId");
        reviewRequestId = Objects.requireNonNull(reviewRequestId, "reviewRequestId");
        if (observationNumber < 2) {
            throw new IllegalArgumentException("duplicate observation number must start at two");
        }
        fingerprint = Objects.requireNonNull(fingerprint, "fingerprint");
        candidateHash = Objects.requireNonNull(candidateHash, "candidateHash");
    }

    public static ReviewFindingDuplicateObserved from(ReviewFindingObservation observation) {
        ReviewFindingObservation value = Objects.requireNonNull(observation, "observation");
        return new ReviewFindingDuplicateObserved(
                value.id().value(),
                value.finding().id().value(),
                value.finding().reviewRequest().id().value(),
                value.observationNumber(),
                value.finding().fingerprint().toString(),
                value.candidateHash().toString());
    }
}
