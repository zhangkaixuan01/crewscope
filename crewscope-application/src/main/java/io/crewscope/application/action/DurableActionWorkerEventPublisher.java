package io.crewscope.application.action;

import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.event.PendingOutboxEvent;
import io.crewscope.application.task.TaskEventContext;
import io.crewscope.application.task.TaskEventRepository;
import io.crewscope.domain.action.ActionBundle;
import io.crewscope.domain.action.ActionDispatch;
import io.crewscope.domain.action.ActionReceipt;
import io.crewscope.domain.action.ExternalMergeOutcome;
import io.crewscope.domain.action.ExternalResult;
import io.crewscope.domain.action.event.ActionDispatchTransitioned;
import io.crewscope.domain.action.event.ActionReceiptRecorded;
import io.crewscope.domain.action.event.ExternalResultMerged;
import io.crewscope.domain.shared.DomainEvent;
import io.crewscope.domain.shared.event.AggregateReference;
import io.crewscope.domain.shared.event.DomainEventEnvelope;
import io.crewscope.domain.shared.event.EventActor;
import io.crewscope.domain.shared.event.EventType;
import io.crewscope.domain.shared.event.SchemaVersion;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Writes sanitized Action events, Task timeline facts and Outbox rows in the caller transaction. */
public final class DurableActionWorkerEventPublisher implements ActionWorkerEventPublisher {

    private final DomainEventStore events;
    private final TaskEventRepository taskEvents;
    private final OutboxRepository outbox;

    public DurableActionWorkerEventPublisher(
            DomainEventStore events,
            TaskEventRepository taskEvents,
            OutboxRepository outbox) {
        this.events = Objects.requireNonNull(events, "events");
        this.taskEvents = Objects.requireNonNull(taskEvents, "taskEvents");
        this.outbox = Objects.requireNonNull(outbox, "outbox");
    }

    @Override
    public void dispatchTransitioned(
            ActionDispatch dispatch, ActionBundle bundle, UUID correlationId) {
        ActionDispatch value = Objects.requireNonNull(dispatch, "dispatch");
        append(
                "ACTION_DISPATCH_TRANSITIONED",
                "ACTION_DISPATCH",
                value.id().value(),
                value.version() - 1,
                value.audit().updatedAt(),
                value.scope(),
                bundle,
                correlationId,
                ActionDispatchTransitioned.from(value));
    }

    @Override
    public void receiptRecorded(
            ActionReceipt receipt, ActionBundle bundle, UUID correlationId) {
        ActionReceipt value = Objects.requireNonNull(receipt, "receipt");
        append(
                "ACTION_RECEIPT_RECORDED",
                "ACTION_RECEIPT",
                value.id().value(),
                0,
                value.receivedAt(),
                value.scope(),
                bundle,
                correlationId,
                ActionReceiptRecorded.from(value));
    }

    @Override
    public void externalResultMerged(
            ExternalResult result,
            ExternalMergeOutcome outcome,
            ActionBundle bundle,
            UUID correlationId) {
        ExternalResult value = Objects.requireNonNull(result, "result");
        append(
                "EXTERNAL_RESULT_MERGED",
                "EXTERNAL_RESULT",
                value.id().value(),
                value.version(),
                value.observedAt(),
                value.scope(),
                bundle,
                correlationId,
                ExternalResultMerged.from(value, outcome));
    }

    private void append(
            String type,
            String aggregateType,
            UUID aggregateId,
            long aggregateVersion,
            UtcTimestamp occurredAt,
            io.crewscope.domain.workitem.WorkItemScope scope,
            ActionBundle bundle,
            UUID correlationId,
            DomainEvent payload) {
        ActionBundle requiredBundle = Objects.requireNonNull(bundle, "bundle");
        UUID eventId = ActionEventIds.stable(type, aggregateId, aggregateVersion);
        DomainEventEnvelope<DomainEvent> envelope = new DomainEventEnvelope<>(
                eventId,
                EventType.from(type),
                SchemaVersion.V1,
                scope.organizationId(),
                Optional.of(scope.teamId()),
                Optional.of(scope.workspaceId()),
                new AggregateReference(aggregateType, aggregateId),
                aggregateVersion,
                EventActor.anonymousService(),
                Objects.requireNonNull(correlationId, "correlationId"),
                Optional.empty(),
                Optional.of("action:" + eventId),
                occurredAt,
                payload);
        events.append(envelope);
        taskEvents.append(
                TaskEventContext.execution(
                        requiredBundle.authority().taskId(),
                        requiredBundle.authority().taskExecutionId()),
                envelope);
        outbox.enqueue(PendingOutboxEvent.fromDomain(UUID.randomUUID(), envelope));
    }

}
