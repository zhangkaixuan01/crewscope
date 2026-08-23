package io.crewscope.application.review;

import io.crewscope.domain.review.ReviewDecision;
import io.crewscope.domain.review.ReviewFinding;
import io.crewscope.domain.review.ReviewFindingObservation;
import io.crewscope.domain.review.ReviewModificationRound;
import io.crewscope.domain.review.ReviewRequest;
import io.crewscope.domain.shared.event.EventActor;
import java.util.UUID;

/** Transactional event boundary for safe Review facts, Task timeline, Outbox and Audit. */
public interface ReviewEventPublisher {

    void findingRecorded(ReviewFinding finding, EventActor actor, UUID correlationId);

    void duplicateObserved(
            ReviewFindingObservation observation, EventActor actor, UUID correlationId);

    void decisionRecorded(ReviewDecision decision, EventActor actor, UUID correlationId);

    void modificationRoundStarted(
            ReviewModificationRound round, EventActor actor, UUID correlationId);

    void requestInvalidated(ReviewRequest request, EventActor actor, UUID correlationId);
}
