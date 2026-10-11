package io.crewscope.application.github;

import io.crewscope.application.command.CommandReceipt;
import io.crewscope.application.command.CommandReceiptStore;
import io.crewscope.application.command.CommandRequestHash;
import io.crewscope.application.command.CommandReservation;
import io.crewscope.application.command.CommandReservationRequest;
import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.event.PendingOutboxEvent;
import io.crewscope.application.team.TeamCommandContext;
import io.crewscope.domain.provider.Connection;
import io.crewscope.domain.provider.ProviderBinding;
import io.crewscope.domain.provider.event.ConnectionLifecycleChanged;
import io.crewscope.domain.provider.event.ProviderBindingChanged;
import io.crewscope.domain.shared.event.AggregateReference;
import io.crewscope.domain.shared.event.DomainEventEnvelope;
import io.crewscope.domain.shared.event.EventActor;
import io.crewscope.domain.shared.event.EventActorType;
import io.crewscope.domain.shared.event.EventType;
import io.crewscope.domain.shared.event.SchemaVersion;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One command journal lifecycle: idempotency reservation, domain event and
 * outbox enqueue, receipt completion. Extracted from
 * {@link GitHubConnectionApplicationService} in M11-E01; event envelopes are
 * byte-for-byte unchanged.
 */
public final class GitHubConnectionCommandJournal {

    private static final String CONNECTION_AGGREGATE = "CONNECTION";
    private static final String BINDING_AGGREGATE = "PROVIDER_BINDING";

    private final CommandReceiptStore receipts;
    private final DomainEventStore eventStore;
    private final OutboxRepository outbox;

    public GitHubConnectionCommandJournal(
            CommandReceiptStore receipts,
            DomainEventStore eventStore,
            OutboxRepository outbox) {
        this.receipts = Objects.requireNonNull(receipts, "receipts");
        this.eventStore = Objects.requireNonNull(eventStore, "eventStore");
        this.outbox = Objects.requireNonNull(outbox, "outbox");
    }

    CommandReservation reserve(
            TeamCommandContext context,
            OrganizationId organizationId,
            String commandType,
            CommandRequestHash hash,
            UUID commandId,
            UtcTimestamp now) {
        return receipts.reserve(new CommandReservationRequest(
                organizationId,
                context.idempotencyKey(),
                commandType,
                hash,
                commandId,
                context.correlationId(),
                now));
    }

    CommandReceipt appendConnectionEvent(
            TeamCommandContext context,
            UUID commandId,
            Connection connection,
            String eventType,
            UtcTimestamp now) {
        UUID eventId = UUID.randomUUID();
        DomainEventEnvelope<ConnectionLifecycleChanged> event = new DomainEventEnvelope<>(
                eventId,
                EventType.from(eventType),
                SchemaVersion.V1,
                connection.organizationId(),
                connection.owner().teamId(),
                Optional.empty(),
                AggregateReference.of(CONNECTION_AGGREGATE, connection.id()),
                connection.version(),
                EventActor.principal(EventActorType.USER, context.access().actor().id()),
                context.correlationId(),
                context.causationId(),
                Optional.of(context.idempotencyKey().value()),
                now,
                ConnectionLifecycleChanged.from(connection));
        eventStore.append(event);
        outbox.enqueue(PendingOutboxEvent.fromDomain(UUID.randomUUID(), event));
        return completeReceipt(
                context,
                connection.organizationId(),
                commandId,
                eventId,
                connection.version(),
                now);
    }

    CommandReceipt appendBindingEvent(
            TeamCommandContext context,
            UUID commandId,
            ProviderBinding binding,
            String eventType,
            UtcTimestamp now) {
        UUID eventId = UUID.randomUUID();
        DomainEventEnvelope<ProviderBindingChanged> event = new DomainEventEnvelope<>(
                eventId,
                EventType.from(eventType),
                SchemaVersion.V1,
                binding.organizationId(),
                Optional.of(binding.target().teamId()),
                Optional.of(binding.target().workspaceId()),
                AggregateReference.of(BINDING_AGGREGATE, binding.id()),
                binding.version(),
                EventActor.principal(EventActorType.USER, context.access().actor().id()),
                context.correlationId(),
                context.causationId(),
                Optional.of(context.idempotencyKey().value()),
                now,
                ProviderBindingChanged.from(binding));
        eventStore.append(event);
        outbox.enqueue(PendingOutboxEvent.fromDomain(UUID.randomUUID(), event));
        return completeReceipt(
                context,
                binding.organizationId(),
                commandId,
                eventId,
                binding.version(),
                now);
    }

    private CommandReceipt completeReceipt(
            TeamCommandContext context,
            OrganizationId organizationId,
            UUID commandId,
            UUID eventId,
            long version,
            UtcTimestamp now) {
        CommandReceipt receipt = new CommandReceipt(
                commandId, eventId, version, context.correlationId());
        receipts.complete(organizationId, context.idempotencyKey(), receipt, now);
        return receipt;
    }
}
