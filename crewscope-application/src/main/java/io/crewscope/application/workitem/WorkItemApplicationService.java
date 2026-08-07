package io.crewscope.application.workitem;

import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.CommandReceipt;
import io.crewscope.application.command.CommandReceiptStore;
import io.crewscope.application.command.CommandRequestHash;
import io.crewscope.application.command.CommandReservation;
import io.crewscope.application.command.CommandReservationRequest;
import io.crewscope.application.command.IdempotencyKey;
import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.event.PendingOutboxEvent;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.shared.event.AggregateReference;
import io.crewscope.domain.shared.event.DomainEventEnvelope;
import io.crewscope.domain.shared.event.EventActor;
import io.crewscope.domain.shared.event.EventType;
import io.crewscope.domain.shared.event.SchemaVersion;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workitem.WorkItem;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkItemKey;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workitem.event.WorkItemCreated;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Coordinates WorkItem state and its durable publication facts in one transaction. */
public final class WorkItemApplicationService {

    private static final EventType WORK_ITEM_CREATED = EventType.from("WORK_ITEM_CREATED");
    private static final String WORK_ITEM_AGGREGATE = "WORK_ITEM";
    private static final String CREATE_WORK_ITEM_COMMAND = "CREATE_WORK_ITEM";

    private final WorkItemRepository repository;
    private final DomainEventStore domainEventStore;
    private final OutboxRepository outboxRepository;
    private final CommandReceiptStore commandReceiptStore;
    private final TransactionExecutor transactionExecutor;
    private final TimeProvider timeProvider;

    public WorkItemApplicationService(
            WorkItemRepository repository,
            DomainEventStore domainEventStore,
            OutboxRepository outboxRepository,
            CommandReceiptStore commandReceiptStore,
            TransactionExecutor transactionExecutor,
            TimeProvider timeProvider) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.domainEventStore = Objects.requireNonNull(domainEventStore, "domainEventStore");
        this.outboxRepository = Objects.requireNonNull(outboxRepository, "outboxRepository");
        this.commandReceiptStore =
                Objects.requireNonNull(commandReceiptStore, "commandReceiptStore");
        this.transactionExecutor =
                Objects.requireNonNull(transactionExecutor, "transactionExecutor");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
    }

    /** Creates the aggregate, its immutable fact and its publication request atomically. */
    public CommandExecution<WorkItem> create(
            WorkItemCommandContext context, CreateWorkItemCommand command) {
        WorkItemCommandContext trustedContext = Objects.requireNonNull(context, "context");
        CreateWorkItemCommand requiredCommand = Objects.requireNonNull(command, "command");
        return transactionExecutor.required(() -> createInTransaction(trustedContext, requiredCommand));
    }

    private CommandExecution<WorkItem> createInTransaction(
            WorkItemCommandContext context, CreateWorkItemCommand command) {
        UtcTimestamp occurredAt = timeProvider.now();
        UUID commandId = UUID.randomUUID();
        WorkItem workItem = WorkItem.create(
                WorkItemId.generate(),
                new WorkItemScope(
                        context.organizationId(),
                        context.teamId(),
                        context.workspaceId(),
                        command.projectId()),
                new WorkItemKey(command.key()),
                command.title(),
                context.actorId(),
                occurredAt);
        CommandRequestHash requestHash = createRequestHash(context, workItem);
        IdempotencyKey idempotencyKey = context.idempotencyKey()
                .map(IdempotencyKey::from)
                .orElseThrow(() -> new IllegalArgumentException(
                        "WorkItem create requires an idempotency key"));
        CommandReservation reservation = commandReceiptStore.reserve(
                new CommandReservationRequest(
                        context.organizationId(),
                        idempotencyKey,
                        CREATE_WORK_ITEM_COMMAND,
                        requestHash,
                        commandId,
                        context.correlationId(),
                        occurredAt));
        if (!reservation.acquired()) {
            return CommandExecution.replayed(reservation.receipt().orElseThrow());
        }
        WorkItem committed = repository.create(workItem);
        UUID domainEventId = UUID.randomUUID();
        DomainEventEnvelope<WorkItemCreated> event = new DomainEventEnvelope<>(
                domainEventId,
                WORK_ITEM_CREATED,
                SchemaVersion.V1,
                context.organizationId(),
                Optional.of(context.teamId()),
                Optional.of(context.workspaceId()),
                AggregateReference.of(WORK_ITEM_AGGREGATE, committed.id()),
                committed.version(),
                EventActor.principal(context.actorType(), context.actorId()),
                context.correlationId(),
                context.causationId(),
                context.idempotencyKey(),
                occurredAt,
                WorkItemCreated.from(committed));
        domainEventStore.append(event);
        outboxRepository.enqueue(PendingOutboxEvent.fromDomain(UUID.randomUUID(), event));
        CommandReceipt receipt = new CommandReceipt(
                commandId, domainEventId, committed.version(), context.correlationId());
        commandReceiptStore.complete(
                context.organizationId(), idempotencyKey, receipt, occurredAt);
        return CommandExecution.completed(committed, receipt);
    }

    private static CommandRequestHash createRequestHash(
            WorkItemCommandContext context, WorkItem workItem) {
        return CommandRequestHash.sha256(
                CREATE_WORK_ITEM_COMMAND,
                context.teamId().toString(),
                context.workspaceId().toString(),
                context.actorType().name(),
                context.actorId().toString(),
                context.causationId().map(UUID::toString).orElse(""),
                workItem.scope().projectId().toString(),
                workItem.key().value(),
                workItem.title());
    }
}
