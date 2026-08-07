package io.crewscope.application.workitem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.CommandReceipt;
import io.crewscope.application.command.CommandReceiptStore;
import io.crewscope.application.command.CommandReservation;
import io.crewscope.application.command.CommandReservationRequest;
import io.crewscope.application.command.IdempotencyKey;
import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.event.PendingOutboxEvent;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.shared.DomainEvent;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.IdempotencyConflictException;
import io.crewscope.domain.shared.event.DomainEventEnvelope;
import io.crewscope.domain.shared.event.EventActorType;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workitem.WorkItem;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkProjectId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class WorkItemApplicationServiceTest {

    @Test
    void createsACommandScopedAndAuditedAggregateUsingTheInjectedClock() {
        CapturingRepository repository = new CapturingRepository();
        CapturingEventStore eventStore = new CapturingEventStore();
        CapturingOutboxRepository outboxRepository = new CapturingOutboxRepository();
        InMemoryCommandReceiptStore receiptStore = new InMemoryCommandReceiptStore();
        TimeProvider timeProvider = TimeProvider.from(
                Clock.fixed(Instant.parse("2026-08-06T12:34:56.123456789Z"), ZoneOffset.UTC));
        WorkItemApplicationService service = new WorkItemApplicationService(
                repository,
                eventStore,
                outboxRepository,
                receiptStore,
                new DirectTransactionExecutor(),
                timeProvider);
        WorkItemCommandContext context = commandContext();
        CreateWorkItemCommand command = new CreateWorkItemCommand(
                WorkProjectId.generate(), "CRW-42", "Repository baseline");

        CommandExecution<WorkItem> execution = service.create(context, command);
        WorkItem created = execution.result().orElseThrow();

        assertFalse(execution.replayed());
        assertEquals(created.version(), execution.receipt().committedVersion());
        assertEquals(context.organizationId(), created.scope().organizationId());
        assertEquals(context.teamId(), created.scope().teamId());
        assertEquals(context.workspaceId(), created.scope().workspaceId());
        assertEquals(command.projectId(), created.scope().projectId());
        assertEquals(context.actorId(), created.audit().createdBy().orElseThrow());
        assertEquals(
                UtcTimestamp.parse("2026-08-06T12:34:56.123456Z"),
                created.audit().createdAt());
        assertEquals(created, repository.created);
        assertEquals("WORK_ITEM_CREATED", eventStore.event.eventType().value());
        assertEquals(created.id().value(), eventStore.event.aggregate().id());
        assertEquals(created.version(), eventStore.event.aggregateVersion());
        assertEquals(context.correlationId(), eventStore.event.correlationId());
        assertEquals(eventStore.event.eventId(), outboxRepository.event.domainEventId());
        assertEquals(PendingOutboxEvent.DOMAIN_EVENTS_TOPIC, outboxRepository.event.topic());
    }

    @Test
    void rejectsAnOversizedTitleBeforeCallingTheRepository() {
        CapturingRepository repository = new CapturingRepository();
        WorkItemApplicationService service = new WorkItemApplicationService(
                repository,
                new CapturingEventStore(),
                new CapturingOutboxRepository(),
                new InMemoryCommandReceiptStore(),
                new DirectTransactionExecutor(),
                () -> UtcTimestamp.parse("2026-08-06T12:34:56Z"));
        WorkItemCommandContext context = commandContext();
        CreateWorkItemCommand command = new CreateWorkItemCommand(
                WorkProjectId.generate(),
                "CRW-43",
                "x".repeat(WorkItem.MAX_TITLE_LENGTH + 1));

        assertThrows(DomainValidationException.class, () -> service.create(context, command));
        assertNull(repository.created);
    }

    @Test
    void returnsTheOriginalReceiptForAnIdenticalIdempotentReplay() {
        CapturingRepository repository = new CapturingRepository();
        CapturingEventStore eventStore = new CapturingEventStore();
        InMemoryCommandReceiptStore receiptStore = new InMemoryCommandReceiptStore();
        WorkItemApplicationService service = new WorkItemApplicationService(
                repository,
                eventStore,
                new CapturingOutboxRepository(),
                receiptStore,
                new DirectTransactionExecutor(),
                () -> UtcTimestamp.parse("2026-08-06T12:34:56Z"));
        WorkItemCommandContext context = commandContext();
        CreateWorkItemCommand command = new CreateWorkItemCommand(
                WorkProjectId.generate(), "CRW-44", "Idempotent command");

        CommandExecution<WorkItem> first = service.create(context, command);
        CommandExecution<WorkItem> replay = service.create(context, command);

        assertFalse(first.replayed());
        assertTrue(replay.replayed());
        assertTrue(replay.result().isEmpty());
        assertEquals(first.receipt(), replay.receipt());
        assertEquals(1, repository.createCount);
        assertEquals(1, eventStore.appendCount);
    }

    @Test
    void rejectsReuseOfAnIdempotencyKeyForDifferentNormalizedCommands() {
        CapturingRepository repository = new CapturingRepository();
        WorkItemApplicationService service = new WorkItemApplicationService(
                repository,
                new CapturingEventStore(),
                new CapturingOutboxRepository(),
                new InMemoryCommandReceiptStore(),
                new DirectTransactionExecutor(),
                () -> UtcTimestamp.parse("2026-08-06T12:34:56Z"));
        WorkItemCommandContext context = commandContext();
        WorkProjectId projectId = WorkProjectId.generate();
        service.create(context, new CreateWorkItemCommand(projectId, "CRW-45", "First"));

        assertThrows(
                IdempotencyConflictException.class,
                () -> service.create(
                        context,
                        new CreateWorkItemCommand(projectId, "CRW-45", "Changed")));
        assertEquals(1, repository.createCount);
    }

    @Test
    void requiresAnIdempotencyKeyBeforeCreatingAnySideEffect() {
        CapturingRepository repository = new CapturingRepository();
        WorkItemApplicationService service = new WorkItemApplicationService(
                repository,
                new CapturingEventStore(),
                new CapturingOutboxRepository(),
                new InMemoryCommandReceiptStore(),
                new DirectTransactionExecutor(),
                () -> UtcTimestamp.parse("2026-08-06T12:34:56Z"));
        WorkItemCommandContext source = commandContext();
        WorkItemCommandContext withoutKey = new WorkItemCommandContext(
                source.organizationId(),
                source.teamId(),
                source.workspaceId(),
                source.actorType(),
                source.actorId(),
                source.correlationId(),
                source.causationId(),
                Optional.empty());

        assertThrows(
                IllegalArgumentException.class,
                () -> service.create(
                        withoutKey,
                        new CreateWorkItemCommand(
                                WorkProjectId.generate(), "CRW-46", "Missing key")));
        assertEquals(0, repository.createCount);
    }

    private static WorkItemCommandContext commandContext() {
        return new WorkItemCommandContext(
                OrganizationId.generate(),
                TeamId.generate(),
                WorkspaceId.generate(),
                EventActorType.USER,
                PrincipalId.generate(),
                UUID.randomUUID(),
                Optional.empty(),
                Optional.of("create-crw-42"));
    }

    private static final class CapturingRepository implements WorkItemRepository {

        private WorkItem created;
        private int createCount;

        @Override
        public WorkItem create(WorkItem workItem) {
            createCount++;
            created = workItem;
            return workItem;
        }

        @Override
        public WorkItem update(WorkItem workItem) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<WorkItem> findById(
                OrganizationId organizationId, WorkItemId id) {
            return Optional.empty();
        }

        @Override
        public WorkItemPage findPage(WorkItemQuery query) {
            return new WorkItemPage(java.util.List.of(), Optional.empty());
        }
    }

    private static final class CapturingEventStore implements DomainEventStore {

        private DomainEventEnvelope<? extends DomainEvent> event;
        private int appendCount;

        @Override
        public void append(DomainEventEnvelope<? extends DomainEvent> event) {
            appendCount++;
            this.event = event;
        }
    }

    private static final class InMemoryCommandReceiptStore implements CommandReceiptStore {

        private final Map<String, Entry> entries = new HashMap<>();

        @Override
        public CommandReservation reserve(CommandReservationRequest request) {
            String key = request.organizationId() + ":" + request.idempotencyKey();
            Entry existing = entries.get(key);
            if (existing == null) {
                entries.put(key, new Entry(request, null));
                return CommandReservation.newlyAcquired();
            }
            if (!existing.request.commandType().equals(request.commandType())
                    || !existing.request.requestHash().equals(request.requestHash())) {
                throw new IdempotencyConflictException(
                        request.idempotencyKey().value(),
                        existing.request.requestHash().value(),
                        request.requestHash().value());
            }
            return CommandReservation.replay(existing.receipt);
        }

        @Override
        public void complete(
                OrganizationId organizationId,
                IdempotencyKey idempotencyKey,
                CommandReceipt receipt,
                UtcTimestamp completedAt) {
            String key = organizationId + ":" + idempotencyKey;
            Entry existing = entries.get(key);
            entries.put(key, new Entry(existing.request, receipt));
        }

        private record Entry(CommandReservationRequest request, CommandReceipt receipt) {}
    }

    private static final class CapturingOutboxRepository implements OutboxRepository {

        private PendingOutboxEvent event;

        @Override
        public void enqueue(PendingOutboxEvent event) {
            this.event = event;
        }
    }

    private static final class DirectTransactionExecutor implements TransactionExecutor {

        @Override
        public <T> T required(Supplier<T> operation) {
            return operation.get();
        }
    }
}
