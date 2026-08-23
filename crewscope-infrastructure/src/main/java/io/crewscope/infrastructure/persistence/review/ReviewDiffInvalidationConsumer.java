package io.crewscope.infrastructure.persistence.review;

import io.crewscope.application.event.json.DomainEventEnvelopeJsonCodec;
import io.crewscope.application.event.publication.DomainEventConsumer;
import io.crewscope.application.event.publication.EventPublication;
import io.crewscope.application.review.ReviewEventPublisher;
import io.crewscope.application.review.ReviewRequestRepository;
import io.crewscope.domain.coding.event.FinalDiffArtifactPublished;
import io.crewscope.domain.review.ReviewInvalidationReason;
import io.crewscope.domain.review.ReviewRequest;
import io.crewscope.domain.review.ReviewRequestStatus;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.event.DomainEventEnvelope;
import io.crewscope.domain.shared.event.EventActor;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.TaskExecutionId;
import java.util.Objects;
import java.util.Optional;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Invalidates the current ReviewRequest when a later authoritative final Diff is published.
 * The class remains proxyable because {@link #consume(EventPublication)} requires a transaction.
 */
@Order(Ordered.LOWEST_PRECEDENCE - 100)
public class ReviewDiffInvalidationConsumer implements DomainEventConsumer {

    static final String EVENT_TYPE = "FINAL_DIFF_ARTIFACT_PUBLISHED";

    private final DomainEventEnvelopeJsonCodec eventCodec;
    private final ObjectMapper objectMapper;
    private final ReviewRequestRepository requests;
    private final ReviewEventPublisher events;

    public ReviewDiffInvalidationConsumer(
            ObjectMapper objectMapper,
            ReviewRequestRepository requests,
            ReviewEventPublisher events) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.eventCodec = new DomainEventEnvelopeJsonCodec(this.objectMapper);
        this.requests = Objects.requireNonNull(requests, "requests");
        this.events = Objects.requireNonNull(events, "events");
    }

    @Override
    public String consumerName() {
        return "review-diff-invalidation-v1";
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void consume(EventPublication publication) {
        EventPublication source = Objects.requireNonNull(publication, "publication");
        if (!EVENT_TYPE.equals(objectMapper.readTree(source.eventJson())
                .path("eventType").asText())) {
            return;
        }
        DomainEventEnvelope<FinalDiffArtifactPublished> envelope =
                eventCodec.decode(source.eventJson(), FinalDiffArtifactPublished.class);
        FinalDiffArtifactPublished diff = envelope.payload();
        Optional<ReviewRequest> current = requests.findCurrentByExecution(
                envelope.organizationId(),
                new TaskExecutionId(diff.taskExecutionId()),
                diff.attempt());
        if (current.isEmpty()) {
            return;
        }
        ReviewRequest request = current.orElseThrow();
        if (request.status() == ReviewRequestStatus.INVALIDATED
                || request.diff().artifact().id().value().equals(diff.diffArtifactId())
                        && request.diff().artifact().finalHash().value().equals(diff.finalHash())) {
            return;
        }

        UtcTimestamp occurredAt = envelope.occurredAt().compareTo(request.audit().updatedAt()) < 0
                ? request.audit().updatedAt()
                : envelope.occurredAt();
        PrincipalId persistenceActor = request.audit().updatedBy()
                .or(() -> request.audit().createdBy())
                .orElseThrow(() -> new IllegalStateException(
                        "ReviewRequest invalidation requires persisted actor provenance"));
        AuditMetadata audit = request.audit().modifiedBy(persistenceActor, occurredAt);
        ReviewRequest invalidated = ReviewRequest.reconstitute(
                request.id(), request.scope(), request.taskId(), request.taskExecutionId(),
                request.attempt(), request.revision(), request.predecessorRequestId(),
                request.subject(), request.contextPackage(), request.diff(), request.testEvidence(),
                request.reviewer(), request.requestHash(), ReviewRequestStatus.INVALIDATED,
                Optional.of(ReviewInvalidationReason.DIFF_CHANGED),
                Math.addExact(request.version(), 1L), audit);
        requests.update(invalidated, request.version());
        events.requestInvalidated(invalidated, EventActor.anonymousService(), envelope.correlationId());
    }
}
