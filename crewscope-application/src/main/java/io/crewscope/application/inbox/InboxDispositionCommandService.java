package io.crewscope.application.inbox;

import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.CommandReceipt;
import io.crewscope.application.command.CommandReceiptStore;
import io.crewscope.application.command.CommandRequestHash;
import io.crewscope.application.command.CommandReservation;
import io.crewscope.application.command.CommandReservationRequest;
import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.team.TeamCommandContext;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.inbox.InboxItemId;
import io.crewscope.domain.inbox.event.InboxDispositionChanged;
import io.crewscope.domain.shared.event.AggregateReference;
import io.crewscope.domain.shared.event.DomainEventEnvelope;
import io.crewscope.domain.shared.event.EventActor;
import io.crewscope.domain.shared.event.EventActorType;
import io.crewscope.domain.shared.event.EventType;
import io.crewscope.domain.shared.event.SchemaVersion;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Adds durable idempotency and receipts around the strong-ETag disposition aggregate. */
public final class InboxDispositionCommandService {

    private static final String COMMAND_TYPE = "inbox.change-disposition";
    private static final EventType FACT_TYPE = EventType.from("INBOX_DISPOSITION_CHANGED");
    private static final String AGGREGATE_TYPE = "INBOX_DISPOSITION";

    private final InboxApplicationService authorizationQueries;
    private final InboxDispositionApplicationService dispositions;
    private final CommandReceiptStore receipts;
    private final DomainEventStore eventStore;
    private final TransactionExecutor transactions;
    private final TimeProvider timeProvider;

    public InboxDispositionCommandService(
            InboxApplicationService authorizationQueries,
            InboxDispositionApplicationService dispositions,
            CommandReceiptStore receipts,
            DomainEventStore eventStore,
            TransactionExecutor transactions,
            TimeProvider timeProvider) {
        this.authorizationQueries =
                Objects.requireNonNull(authorizationQueries, "authorizationQueries");
        this.dispositions = Objects.requireNonNull(dispositions, "dispositions");
        this.receipts = Objects.requireNonNull(receipts, "receipts");
        this.eventStore = Objects.requireNonNull(eventStore, "eventStore");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
    }

    public CommandExecution<InboxDispositionOutcome> change(
            TeamCommandContext context,
            OrganizationId organizationId,
            TeamId teamId,
            InboxItemId inboxItemId,
            ChangeInboxDispositionCommand command) {
        TeamCommandContext trusted = Objects.requireNonNull(context, "context");
        ChangeInboxDispositionCommand requested = Objects.requireNonNull(command, "command");
        // Replays still revalidate current membership and exact item ownership before revealing a
        // receipt, so an actor who left the Team cannot use an old idempotency key as a bypass.
        authorizationQueries.detail(
                trusted.access(), organizationId, teamId, inboxItemId);
        CommandRequestHash hash = CommandRequestHash.sha256(
                COMMAND_TYPE,
                Objects.requireNonNull(organizationId, "organizationId").toString(),
                Objects.requireNonNull(teamId, "teamId").toString(),
                Objects.requireNonNull(inboxItemId, "inboxItemId").toString(),
                requested.targetStatus().name(),
                Long.toString(requested.expectedVersion()));
        Optional<CommandReceipt> completed = receipts.findCompleted(
                organizationId, trusted.idempotencyKey(), COMMAND_TYPE, hash);
        if (completed.isPresent()) {
            return CommandExecution.replayed(completed.orElseThrow());
        }
        return transactions.required(() -> execute(
                trusted, organizationId, teamId, inboxItemId, requested, hash));
    }

    private CommandExecution<InboxDispositionOutcome> execute(
            TeamCommandContext context,
            OrganizationId organizationId,
            TeamId teamId,
            InboxItemId inboxItemId,
            ChangeInboxDispositionCommand command,
            CommandRequestHash hash) {
        UtcTimestamp now = timeProvider.now();
        UUID commandId = UUID.randomUUID();
        CommandReservation reservation = receipts.reserve(new CommandReservationRequest(
                organizationId,
                context.idempotencyKey(),
                COMMAND_TYPE,
                hash,
                commandId,
                context.correlationId(),
                now));
        if (!reservation.acquired()) {
            return CommandExecution.replayed(reservation.receipt().orElseThrow());
        }
        InboxDispositionOutcome outcome = dispositions.change(
                context.access(), organizationId, teamId, inboxItemId, command);
        // A completed reservation must reference one committed domain fact (the V5 receipt
        // foreign key), so the Generation-independent disposition command appends its own fact
        // event. No projection subscribes to it and no outbox row is queued: unmark and restore
        // must never re-notify anyone (contract §5.1). Version 0 is the no-row UNREAD no-op and
        // keeps that same one-fact receipt identity.
        UUID eventId = UUID.randomUUID();
        eventStore.append(new DomainEventEnvelope<>(
                eventId,
                FACT_TYPE,
                SchemaVersion.V1,
                organizationId,
                Optional.of(teamId),
                Optional.empty(),
                new AggregateReference(
                        AGGREGATE_TYPE,
                        dispositionAggregateId(
                                organizationId,
                                teamId,
                                context.access().actor().id().value(),
                                inboxItemId)),
                outcome.version(),
                EventActor.principal(EventActorType.USER, context.access().actor().id()),
                context.correlationId(),
                context.causationId(),
                Optional.of(context.idempotencyKey().value()),
                now,
                new InboxDispositionChanged(
                        inboxItemId.value(), outcome.status().name(), outcome.version())));
        CommandReceipt receipt = new CommandReceipt(
                commandId, eventId, outcome.version(), context.correlationId());
        receipts.complete(organizationId, context.idempotencyKey(), receipt, now);
        return CommandExecution.completed(outcome, receipt);
    }

    /** Stable per-member-item identity of the disposition authority the command changed. */
    private static UUID dispositionAggregateId(
            OrganizationId organizationId, TeamId teamId, UUID actorId, InboxItemId inboxItemId) {
        return UUID.nameUUIDFromBytes(("crewscope:inbox-disposition:v1:"
                        + organizationId + ":" + teamId + ":" + actorId + ":" + inboxItemId)
                .getBytes(StandardCharsets.UTF_8));
    }
}
