package io.crewscope.application.action;

import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.event.PendingOutboxEvent;
import io.crewscope.application.task.TaskEventContext;
import io.crewscope.application.task.TaskEventRepository;
import io.crewscope.domain.action.ActionBundle;
import io.crewscope.domain.action.Confirmation;
import io.crewscope.domain.action.event.ActionBundleConfirmed;
import io.crewscope.domain.action.event.ActionBundlePlanned;
import io.crewscope.domain.action.event.ActionConfirmationCancelled;
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

/** Writes Action command events, Task timeline facts and Outbox rows in the caller transaction. */
public final class DurableActionCommandEventPublisher implements ActionCommandEventPublisher {

    private final DomainEventStore events;
    private final TaskEventRepository taskEvents;
    private final OutboxRepository outbox;

    public DurableActionCommandEventPublisher(
            DomainEventStore events,
            TaskEventRepository taskEvents,
            OutboxRepository outbox) {
        this.events = Objects.requireNonNull(events, "events");
        this.taskEvents = Objects.requireNonNull(taskEvents, "taskEvents");
        this.outbox = Objects.requireNonNull(outbox, "outbox");
    }

    @Override
    public UUID bundlePlanned(ActionBundle bundle, EventActor actor, UUID correlationId) {
        ActionBundle value = Objects.requireNonNull(bundle, "bundle");
        return append(
                "ACTION_BUNDLE_PLANNED",
                "ACTION_BUNDLE",
                value.id().value(),
                value.version(),
                value.audit().createdAt(),
                value,
                actor,
                correlationId,
                ActionBundlePlanned.from(value));
    }

    @Override
    public UUID bundleConfirmed(
            Confirmation confirmation,
            ActionBundle bundle,
            EventActor actor,
            UUID correlationId) {
        Confirmation value = Objects.requireNonNull(confirmation, "confirmation");
        return append(
                "ACTION_BUNDLE_CONFIRMED",
                "ACTION_CONFIRMATION",
                value.id().value(),
                value.version(),
                value.confirmedAt(),
                bundle,
                actor,
                correlationId,
                ActionBundleConfirmed.from(value));
    }

    @Override
    public UUID confirmationCancelled(
            Confirmation confirmation,
            ActionBundle bundle,
            EventActor actor,
            UUID correlationId) {
        Confirmation value = Objects.requireNonNull(confirmation, "confirmation");
        return append(
                "ACTION_CONFIRMATION_CANCELLED",
                "ACTION_CONFIRMATION",
                value.id().value(),
                value.version(),
                value.audit().updatedAt(),
                bundle,
                actor,
                correlationId,
                ActionConfirmationCancelled.from(value));
    }

    private UUID append(
            String type,
            String aggregateType,
            UUID aggregateId,
            long aggregateVersion,
            UtcTimestamp occurredAt,
            ActionBundle bundle,
            EventActor actor,
            UUID correlationId,
            DomainEvent payload) {
        ActionBundle requiredBundle = Objects.requireNonNull(bundle, "bundle");
        UUID eventId = UUID.randomUUID();
        var scope = requiredBundle.authority().scope();
        DomainEventEnvelope<DomainEvent> envelope = new DomainEventEnvelope<>(
                eventId,
                EventType.from(type),
                SchemaVersion.V1,
                scope.organizationId(),
                Optional.of(scope.teamId()),
                Optional.of(scope.workspaceId()),
                new AggregateReference(aggregateType, aggregateId),
                aggregateVersion,
                Objects.requireNonNull(actor, "actor"),
                Objects.requireNonNull(correlationId, "correlationId"),
                Optional.empty(),
                Optional.of("action-command:" + eventId),
                occurredAt,
                payload);
        events.append(envelope);
        taskEvents.append(
                TaskEventContext.execution(
                        requiredBundle.authority().taskId(),
                        requiredBundle.authority().taskExecutionId()),
                envelope);
        outbox.enqueue(PendingOutboxEvent.fromDomain(UUID.randomUUID(), envelope));
        return eventId;
    }
}
