package io.crewscope.application.review;

import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.event.PendingOutboxEvent;
import io.crewscope.application.task.TaskEventContext;
import io.crewscope.application.task.TaskEventRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.review.ReviewDecision;
import io.crewscope.domain.review.ReviewFinding;
import io.crewscope.domain.review.ReviewFindingObservation;
import io.crewscope.domain.review.ReviewModificationRound;
import io.crewscope.domain.review.ReviewRequest;
import io.crewscope.domain.review.event.ReviewDecisionRecorded;
import io.crewscope.domain.review.event.ReviewFindingDuplicateObserved;
import io.crewscope.domain.review.event.ReviewFindingRecorded;
import io.crewscope.domain.review.event.ReviewModificationRoundStarted;
import io.crewscope.domain.review.event.ReviewRequestInvalidated;
import io.crewscope.domain.shared.DomainEvent;
import io.crewscope.domain.shared.event.AggregateReference;
import io.crewscope.domain.shared.event.DomainEventEnvelope;
import io.crewscope.domain.shared.event.EventActor;
import io.crewscope.domain.shared.event.EventType;
import io.crewscope.domain.shared.event.SchemaVersion;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskId;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Persists each Review event, Task stream entry and Outbox row in one required transaction. */
public final class DurableReviewEventPublisher implements ReviewEventPublisher {

    private final DomainEventStore eventStore;
    private final TaskEventRepository taskEvents;
    private final OutboxRepository outbox;
    private final TransactionExecutor transactions;

    public DurableReviewEventPublisher(
            DomainEventStore eventStore,
            TaskEventRepository taskEvents,
            OutboxRepository outbox,
            TransactionExecutor transactions) {
        this.eventStore = Objects.requireNonNull(eventStore, "eventStore");
        this.taskEvents = Objects.requireNonNull(taskEvents, "taskEvents");
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    @Override
    public void findingRecorded(ReviewFinding finding, EventActor actor, UUID correlationId) {
        ReviewFinding value = Objects.requireNonNull(finding, "finding");
        append(
                "REVIEW_FINDING_RECORDED",
                "REVIEW_FINDING",
                value.id().value(),
                0,
                value.scope().organizationId(),
                value.scope().teamId(),
                value.scope().workspaceId(),
                value.reviewRequest().taskId(),
                value.reviewRequest().taskExecutionId(),
                value.audit().createdAt(),
                actor,
                correlationId,
                ReviewFindingRecorded.from(value));
    }

    @Override
    public void duplicateObserved(
            ReviewFindingObservation observation, EventActor actor, UUID correlationId) {
        ReviewFindingObservation value = Objects.requireNonNull(observation, "observation");
        var request = value.finding().reviewRequest();
        append(
                "REVIEW_FINDING_DUPLICATE_OBSERVED",
                "REVIEW_FINDING",
                value.finding().id().value(),
                value.observationNumber() - 1,
                request.scope().organizationId(),
                request.scope().teamId(),
                request.scope().workspaceId(),
                request.taskId(),
                request.taskExecutionId(),
                value.audit().createdAt(),
                actor,
                correlationId,
                ReviewFindingDuplicateObserved.from(value));
    }

    @Override
    public void decisionRecorded(ReviewDecision decision, EventActor actor, UUID correlationId) {
        ReviewDecision value = Objects.requireNonNull(decision, "decision");
        append(
                "REVIEW_DECISION_RECORDED",
                "REVIEW_DECISION_CHAIN",
                value.reviewRequest().id().value(),
                value.revision() - 1,
                value.scope().organizationId(),
                value.scope().teamId(),
                value.scope().workspaceId(),
                value.taskId(),
                value.reviewRequest().taskExecutionId(),
                value.audit().createdAt(),
                actor,
                correlationId,
                ReviewDecisionRecorded.from(value));
    }

    @Override
    public void modificationRoundStarted(
            ReviewModificationRound round, EventActor actor, UUID correlationId) {
        ReviewModificationRound value = Objects.requireNonNull(round, "round");
        append(
                "REVIEW_MODIFICATION_ROUND_STARTED",
                "REVIEW_MODIFICATION_ROUND_CHAIN",
                value.taskId().value(),
                value.roundNumber() - 1,
                value.scope().organizationId(),
                value.scope().teamId(),
                value.scope().workspaceId(),
                value.taskId(),
                value.sourceRequest().taskExecutionId(),
                value.audit().createdAt(),
                actor,
                correlationId,
                ReviewModificationRoundStarted.from(value));
    }

    @Override
    public void requestInvalidated(ReviewRequest request, EventActor actor, UUID correlationId) {
        ReviewRequest value = Objects.requireNonNull(request, "request");
        append(
                "REVIEW_REQUEST_INVALIDATED",
                "REVIEW_REQUEST_INVALIDATION",
                value.id().value(),
                0,
                value.scope().organizationId(),
                value.scope().teamId(),
                value.scope().workspaceId(),
                value.taskId(),
                value.taskExecutionId(),
                value.audit().updatedAt(),
                actor,
                correlationId,
                ReviewRequestInvalidated.from(value));
    }

    private void append(
            String eventType,
            String aggregateType,
            UUID aggregateId,
            long aggregateVersion,
            OrganizationId organizationId,
            TeamId teamId,
            WorkspaceId workspaceId,
            TaskId taskId,
            TaskExecutionId executionId,
            UtcTimestamp occurredAt,
            EventActor actor,
            UUID correlationId,
            DomainEvent payload) {
        EventActor requiredActor = Objects.requireNonNull(actor, "actor");
        UUID requiredCorrelationId = Objects.requireNonNull(correlationId, "correlationId");
        UUID eventId = stableId(eventType, aggregateId, aggregateVersion);
        DomainEventEnvelope<DomainEvent> envelope = new DomainEventEnvelope<>(
                eventId,
                EventType.from(eventType),
                SchemaVersion.V1,
                organizationId,
                Optional.of(teamId),
                Optional.of(workspaceId),
                new AggregateReference(aggregateType, aggregateId),
                aggregateVersion,
                requiredActor,
                requiredCorrelationId,
                Optional.empty(),
                Optional.of("review:" + eventId),
                occurredAt,
                payload);
        transactions.required(() -> {
            eventStore.append(envelope);
            taskEvents.append(TaskEventContext.execution(taskId, executionId), envelope);
            outbox.enqueue(PendingOutboxEvent.fromDomain(UUID.randomUUID(), envelope));
            return null;
        });
    }

    private static UUID stableId(String eventType, UUID aggregateId, long aggregateVersion) {
        String source = "crewscope:review:" + eventType + ':' + aggregateId + ':' + aggregateVersion;
        return UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8));
    }
}
