package io.crewscope.application.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.CommandReceipt;
import io.crewscope.application.command.CommandRequestHash;
import io.crewscope.application.command.CommandReservation;
import io.crewscope.application.command.CommandReservationRequest;
import io.crewscope.application.command.CommandResult;
import io.crewscope.application.command.CommandReceiptStore;
import io.crewscope.application.command.IdempotencyKey;
import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.event.PendingOutboxEvent;
import io.crewscope.application.task.TaskEvent;
import io.crewscope.application.task.TaskEventContext;
import io.crewscope.application.task.TaskEventCursor;
import io.crewscope.application.task.TaskEventPage;
import io.crewscope.application.task.TaskEventQuery;
import io.crewscope.application.task.TaskEventRepository;
import io.crewscope.application.task.TaskExecutionRepository;
import io.crewscope.application.task.TaskRepository;
import io.crewscope.application.team.MemberRoleRepository;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamCommandContext;
import io.crewscope.application.team.TeamMembershipQuery;
import io.crewscope.application.team.TeamRepository;
import io.crewscope.application.team.TeamRoleRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.conversation.ConversationId;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.knowledge.KnowledgeCategory;
import io.crewscope.domain.knowledge.KnowledgeEntry;
import io.crewscope.domain.knowledge.KnowledgeEntryKey;
import io.crewscope.domain.knowledge.KnowledgeEntryKeyConflictException;
import io.crewscope.domain.knowledge.KnowledgeEntryStatus;
import io.crewscope.domain.model.ModelConnectionId;
import io.crewscope.domain.model.ModelId;
import io.crewscope.domain.model.ModelProviderKey;
import io.crewscope.domain.model.ModelTokenUsage;
import io.crewscope.domain.model.event.ModelUsageFactRecorded;
import io.crewscope.domain.responsibility.ResponsibilityAssignmentId;
import io.crewscope.domain.responsibility.ResponsibilityRole;
import io.crewscope.domain.shared.DomainEvent;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.IdempotencyConflictException;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.event.DomainEventEnvelope;
import io.crewscope.domain.shared.event.EventType;
import io.crewscope.domain.shared.event.RealtimeEventEnvelope;
import io.crewscope.domain.shared.event.SchemaVersion;
import io.crewscope.domain.shared.event.StreamType;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.AgentRunId;
import io.crewscope.domain.task.FencingToken;
import io.crewscope.domain.task.Task;
import io.crewscope.domain.task.TaskBrief;
import io.crewscope.domain.task.TaskExecution;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskExecutionPriority;
import io.crewscope.domain.task.TaskExecutionStatus;
import io.crewscope.domain.task.TaskExecutionTerminal;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.task.TaskResponsibilitySnapshot;
import io.crewscope.domain.task.TaskResponsibilitySnapshotEntry;
import io.crewscope.domain.task.TaskSource;
import io.crewscope.domain.task.TaskSourceType;
import io.crewscope.domain.task.TaskStatus;
import io.crewscope.domain.team.BuiltInTeamRole;
import io.crewscope.domain.team.MemberRole;
import io.crewscope.domain.team.MemberRoleId;
import io.crewscope.domain.team.Team;
import io.crewscope.domain.team.TeamInitialization;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.team.TeamRole;
import io.crewscope.domain.team.TeamRoleId;
import io.crewscope.domain.team.TeamScope;
import io.crewscope.domain.team.UninitializedTeam;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Four-stage distillation contract for A02b: the side-effect sequence, category priority,
 * receipt semantics, source validation and the independence of usage facts from the entry
 * commit — with a fake Port and in-memory collaborators only.
 */
final class KnowledgeDistillationServiceTest {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-10-02T09:00:00Z");
    private static final KnowledgeEntryKey ENTRY_KEY = new KnowledgeEntryKey("deploy-lessons");

    private final OrganizationId organizationId = OrganizationId.generate();
    private final Principal actor =
            Principal.create(
                    PrincipalId.generate(),
                    PrincipalScope.organization(organizationId),
                    PrincipalType.USER,
                    Optional.empty(),
                    "Owner",
                    Optional.empty(),
                    PrincipalVisibility.ORGANIZATION,
                    NOW);
    private final TeamInitialization initialization = TeamInitialization.create(actor, "Platform", NOW);
    private final TeamId teamId = initialization.team().id();
    private final InMemoryKnowledgeRepository repository = new InMemoryKnowledgeRepository();
    private final Store store = new Store(initialization, actor);
    private final FakeTaskStore tasks = new FakeTaskStore();
    private final FakePort port = new FakePort();
    private final FakeReadiness readiness = new FakeReadiness();

    private KnowledgeDistillationService service;
    private TaskExecution execution;

    @BeforeEach
    void setUp() {
        tasks.task = tasks.completedTask(organizationId, teamId, actor.id());
        execution = tasks.completedExecution(tasks.currentExecutionId, tasks.task);
        KnowledgeDistillationSourceAssembler assembler =
                new KnowledgeDistillationSourceAssembler(new SinglePageTaskEvents());
        service =
                new KnowledgeDistillationService(
                        repository,
                        store,
                        store,
                        store.rolesView(),
                        store,
                        tasks,
                        tasks,
                        assembler,
                        readiness,
                        port,
                        store,
                        store,
                        store,
                        new DirectTransactionExecutor(),
                        () -> NOW,
                        // Same-thread executor keeps the stage-3/4 handoff synchronous in tests.
                        Runnable::run);
    }

    // ------------------------------------------------------------------ happy path

    @Test
    void distillsACompletedCurrentAttemptIntoAnOriginCarryingDraft() {
        port.nextResult = draft("Deploy lessons", "Drain the pool first.", "RUNBOOK");

        CommandExecution<KnowledgeEntry> executionResult =
                service.distill(context("distill-1"), teamId, command(Optional.empty()))
                        .toCompletableFuture().join();

        KnowledgeEntry entry = executionResult.result().orElseThrow();
        assertEquals(KnowledgeEntryStatus.DRAFT, entry.status());
        assertEquals(KnowledgeCategory.RUNBOOK, entry.category());
        assertEquals(execution.id().value(), entry.origin().orElseThrow().taskExecutionId());
        assertEquals(execution.attempt(), entry.origin().orElseThrow().attempt());
        assertEquals(0, entry.version());
        assertEquals(Optional.of(execution.id().value()),
                port.lastRequest.map(value -> value.taskExecutionId().value()));
        assertEquals(execution.attempt(), port.lastRequest.orElseThrow().executionAttempt());

        // One created fact plus one usage fact per real attempt, both on the outbox.
        DomainEventEnvelope<? extends DomainEvent> created = store.eventOfType("KNOWLEDGE_ENTRY_CREATED");
        assertEquals(entry.id().value(), created.aggregate().id());
        DomainEventEnvelope<? extends DomainEvent> usage =
                store.eventOfType("MODEL_USAGE_FACT_RECORDED");
        ModelUsageFactRecorded payload = assertInstanceOf(
                ModelUsageFactRecorded.class, usage.payload());
        assertEquals(io.crewscope.domain.model.ModelUsageRole.DISTILLATION, payload.role());
        assertEquals(1, payload.attempt());
        assertEquals("openai", payload.providerKey().value());
        assertEquals(2, store.outbox.size());

        CommandResult result = store.findResult(organizationId, IdempotencyKey.from("distill-1"), actor.id())
                .orElseThrow();
        assertEquals(CommandResult.ResourceType.KNOWLEDGE_ENTRY, result.resourceType());
        assertEquals(0, result.resourceVersion());
    }

    @Test
    void requestedCategoryWinsAndUnusableSuggestionsDegradeToOther() {
        port.nextResult = draft("One", "Body", "DECISION");
        CommandExecution<KnowledgeEntry> explicit = service.distill(
                context("cat-explicit"), teamId, command(Optional.of(KnowledgeCategory.RUNBOOK)))
                .toCompletableFuture().join();
        assertEquals(KnowledgeCategory.RUNBOOK, explicit.result().orElseThrow().category());

        port.nextResult = draft("Two", "Body", "DECISION");
        CommandExecution<KnowledgeEntry> suggested = service.distill(
                new TeamCommandContext(access(), IdempotencyKey.from("cat-suggested"),
                        UUID.randomUUID(), Optional.empty()),
                teamId, new DistillKnowledgeEntryCommand(
                        execution.id(), new KnowledgeEntryKey("suggested-key"), Optional.empty()))
                .toCompletableFuture().join();
        assertEquals(KnowledgeCategory.DECISION, suggested.result().orElseThrow().category());

        port.nextResult = draft("Three", "Body", "MYSTERY");
        CommandExecution<KnowledgeEntry> degraded = service.distill(
                new TeamCommandContext(access(), IdempotencyKey.from("cat-degraded"),
                        UUID.randomUUID(), Optional.empty()),
                teamId, new DistillKnowledgeEntryCommand(
                        execution.id(), new KnowledgeEntryKey("degraded-key"), Optional.empty()))
                .toCompletableFuture().join();
        assertEquals(KnowledgeCategory.OTHER, degraded.result().orElseThrow().category());
    }

    @Test
    void sameIdempotencyKeyReplaysWithoutASecondModelCall() {
        port.nextResult = draft("Deploy lessons", "Drain the pool first.", "RUNBOOK");
        service.distill(context("distill-replay"), teamId, command(Optional.empty()))
                .toCompletableFuture().join();

        CommandExecution<KnowledgeEntry> replay = service.distill(
                context("distill-replay"), teamId, command(Optional.empty()))
                .toCompletableFuture().join();

        assertTrue(replay.replayed());
        assertEquals(1, port.calls.get());
        assertEquals(1, eventsOf("KNOWLEDGE_ENTRY_CREATED").size(),
                "no second entry was committed");
        assertEquals(1, eventsOf("MODEL_USAGE_FACT_RECORDED").size());
        assertEquals(1, repository.allEntries().stream()
                .filter(entry -> entry.entryKey().equals(ENTRY_KEY))
                .count());
    }

    // ------------------------------------------------------------------ failure semantics

    @Test
    void modelFailureLeavesNoReceiptAndNoEntry() {
        port.nextFailure = new IllegalStateException("provider unavailable");

        assertThrows(
                CompletionException.class,
                () -> service.distill(context("distill-llm-fail"), teamId, command(Optional.empty()))
                        .toCompletableFuture().join());

        assertTrue(store.events.isEmpty());
        assertTrue(store.outbox.isEmpty());
        assertTrue(store.findCompleted(
                organizationId, IdempotencyKey.from("distill-llm-fail"), "DISTILL_KNOWLEDGE_ENTRY",
                anyHash()).isEmpty());
        assertTrue(repository.allEntries().isEmpty());
    }

    @Test
    void usageFactsSurviveAnEntryCommitFailure() {
        port.nextResult = draft("Deploy lessons", "Drain the pool first.", "RUNBOOK");
        repository.failNextCreate = true;

        assertThrows(
                CompletionException.class,
                () -> service.distill(context("distill-commit-fail"), teamId, command(Optional.empty()))
                        .toCompletableFuture().join());

        // The model call really happened, so its tokens were already spent and recorded.
        List<DomainEventEnvelope<? extends DomainEvent>> usage =
                eventsOf("MODEL_USAGE_FACT_RECORDED");
        assertEquals(1, usage.size());
        assertTrue(store.findCompleted(
                organizationId, IdempotencyKey.from("distill-commit-fail"),
                "DISTILL_KNOWLEDGE_ENTRY", anyHash()).isEmpty());
    }

    @Test
    void multiAttemptUsageFactsCarryDistinctEventIdempotencyKeys() {
        // A provider retry makes one command append one usage fact per attempt; the event
        // store enforces one event per (organization, idempotency key), so a command-keyed
        // event dies on the second attempt (M10-Q02 real-stack finding, skill twin).
        port.nextResult = draft("Deploy lessons", "Drain the pool first.", "RUNBOOK");
        port.nextAttribution = new KnowledgeDistillationPort.CallAttribution(
                new ModelProviderKey("openai"),
                new ModelId("gpt-5"),
                ModelConnectionId.generate(),
                3,
                List.of(
                        new KnowledgeDistillationPort.AttemptUsage(
                                1, new ModelTokenUsage(1200, 80, 900, 1280)),
                        new KnowledgeDistillationPort.AttemptUsage(
                                2, new ModelTokenUsage(1332, 2279, 128, 3611))));

        service.distill(context("distill-two-attempts"), teamId, command(Optional.empty()))
                .toCompletableFuture().join();

        List<DomainEventEnvelope<? extends DomainEvent>> usage =
                eventsOf("MODEL_USAGE_FACT_RECORDED");
        assertEquals(2, usage.size());
        assertEquals(
                List.of("distill-two-attempts#usage-1", "distill-two-attempts#usage-2"),
                usage.stream()
                        .map(event -> event.idempotencyKey().orElseThrow())
                        .toList());
    }

    @Test
    void nonCompletedExecutionIsRejectedBeforeTheModelCall() {
        tasks.executionStatus = TaskExecutionStatus.RUNNING;
        port.nextResult = draft("Nope", "Body", "RUNBOOK");

        DomainValidationException failure = assertThrows(
                DomainValidationException.class,
                () -> service.distill(context("distill-running"), teamId, command(Optional.empty())));
        assertEquals("distillation.taskExecutionStatus", failure.error().details().get("field"));
        assertEquals(0, port.calls.get());
    }

    @Test
    void staleAttemptIsRejectedBeforeTheModelCall() {
        // The Task has moved on to a newer attempt; the requested one is stale.
        tasks.currentExecutionId = TaskExecutionId.generate();
        tasks.task = tasks.completedTask(organizationId, teamId, actor.id());
        port.nextResult = draft("Nope", "Body", "RUNBOOK");

        DomainValidationException failure = assertThrows(
                DomainValidationException.class,
                () -> service.distill(context("distill-stale"), teamId, command(Optional.empty())));
        assertEquals("distillation.taskExecutionId", failure.error().details().get("field"));
        assertEquals(0, port.calls.get());
    }

    @Test
    void crossTeamExecutionIsNotFound() {
        tasks.crossTeam = true;

        assertThrows(
                AggregateNotFoundException.class,
                () -> service.distill(context("distill-cross"), teamId, command(Optional.empty())));
        assertEquals(0, port.calls.get());
    }

    @Test
    void duplicateKeyFailsFastBeforeTheModelCall() {
        repository.create(KnowledgeEntry.create(
                new TeamScope(organizationId, teamId), ENTRY_KEY, KnowledgeCategory.RUNBOOK,
                "Existing", "Body", actor.id(), NOW));
        port.nextResult = draft("Deploy lessons", "Drain the pool first.", "RUNBOOK");

        assertThrows(
                KnowledgeEntryKeyConflictException.class,
                () -> service.distill(context("distill-dup"), teamId, command(Optional.empty())));
        assertEquals(0, port.calls.get());
        assertEquals(0, readiness.calls.get());
    }

    @Test
    void distillationRequiresKnowledgeManage() {
        store.revokeGrants();

        assertThrows(
                PolicyDeniedException.class,
                () -> service.distill(context("distill-denied"), teamId, command(Optional.empty())));
        assertEquals(0, port.calls.get());
    }

    @Test
    void readinessIsEnsuredExactlyOncePerSuccessfulPreparation() {
        port.nextResult = draft("Deploy lessons", "Drain the pool first.", "RUNBOOK");
        service.distill(context("distill-ready"), teamId, command(Optional.empty()))
                .toCompletableFuture().join();

        assertEquals(1, readiness.calls.get());
        assertEquals(organizationId, readiness.lastOrganizationId);
        assertEquals(teamId, readiness.lastTeamId);
    }

    // ------------------------------------------------------------------ helpers

    private static KnowledgeDistillationPort.DistilledDraft draft(
            String title, String content, String suggestedCategory) {
        return new KnowledgeDistillationPort.DistilledDraft(title, content, suggestedCategory);
    }

    private static KnowledgeDistillationPort.CallAttribution fixedAttribution() {
        return new KnowledgeDistillationPort.CallAttribution(
                new ModelProviderKey("openai"),
                new ModelId("gpt-5"),
                ModelConnectionId.generate(),
                3,
                List.of(new KnowledgeDistillationPort.AttemptUsage(
                        1, new ModelTokenUsage(1200, 80, 900, 1280))));
    }

    private DistillKnowledgeEntryCommand command(Optional<KnowledgeCategory> category) {
        return new DistillKnowledgeEntryCommand(execution.id(), ENTRY_KEY, category);
    }

    private TeamAccessContext access() {
        return new TeamAccessContext(actor, false);
    }

    private TeamCommandContext context(String idempotencyKey) {
        return new TeamCommandContext(
                access(), IdempotencyKey.from(idempotencyKey), UUID.randomUUID(), Optional.empty());
    }

    private static CommandRequestHash anyHash() {
        return CommandRequestHash.sha256("DISTILL_KNOWLEDGE_ENTRY", "any");
    }

    private List<DomainEventEnvelope<? extends DomainEvent>> eventsOf(String type) {
        return store.events.stream()
                .filter(event -> event.eventType().value().equals(type))
                .toList();
    }

    /** Returns the configured draft with a fixed attribution shape. */
    private static final class FakePort implements KnowledgeDistillationPort {
        final AtomicInteger calls = new AtomicInteger();
        Optional<KnowledgeDistillationPort.KnowledgeDistillationRequest> lastRequest =
                Optional.empty();
        KnowledgeDistillationPort.DistilledDraft nextResult;
        KnowledgeDistillationPort.CallAttribution nextAttribution = fixedAttribution();
        RuntimeException nextFailure;

        @Override
        public CompletionStage<KnowledgeDistillationResult> distill(
                KnowledgeDistillationRequest request) {
            calls.incrementAndGet();
            lastRequest = Optional.of(request);
            if (nextFailure != null) {
                return CompletableFuture.failedFuture(nextFailure);
            }
            return CompletableFuture.completedFuture(
                    new KnowledgeDistillationResult(nextResult, nextAttribution));
        }
    }

    private static final class FakeReadiness implements KnowledgeDistillerReadiness {
        final AtomicInteger calls = new AtomicInteger();
        OrganizationId lastOrganizationId;
        TeamId lastTeamId;

        @Override
        public void ensureReady(OrganizationId organizationId, TeamId teamId) {
            calls.incrementAndGet();
            lastOrganizationId = organizationId;
            lastTeamId = teamId;
        }
    }

    /** Task/TaskExecution view with toggleable validation failures. */
    private final class FakeTaskStore implements TaskRepository, TaskExecutionRepository {
        final WorkItemScope scope = new WorkItemScope(
                organizationId, teamId, WorkspaceId.generate(), WorkProjectId.generate());
        final WorkItemId workItemId = WorkItemId.generate();
        final TaskId taskId = TaskId.generate();
        TaskExecutionId currentExecutionId = TaskExecutionId.generate();
        Task task;
        TaskExecutionStatus executionStatus = TaskExecutionStatus.COMPLETED;
        boolean crossTeam;

        Task completedTask(OrganizationId organizationId, TeamId teamId, PrincipalId actor) {
            TaskResponsibilitySnapshot responsibilities = new TaskResponsibilitySnapshot(
                    scope,
                    workItemId,
                    List.of(
                            responsibility(ResponsibilityRole.OWNER, actor, PrincipalType.USER),
                            responsibility(
                                    ResponsibilityRole.EXECUTOR,
                                    PrincipalId.generate(),
                                    PrincipalType.SPECIALIST_AGENT)),
                    NOW);
            return Task.reconstitute(
                    taskId,
                    scope,
                    workItemId,
                    new TaskSource(
                            TaskSourceType.WORK_ITEM, scope, workItemId, 0,
                            Optional.empty(), Optional.empty()),
                    new TaskBrief("Ship the release", List.of("Green build")),
                    responsibilities,
                    TaskStatus.ACTIVE,
                    Optional.of(currentExecutionId),
                    Optional.empty(),
                    0,
                    AuditMetadata.createdBy(actor, NOW));
        }

        TaskExecution completedExecution(TaskExecutionId executionId, Task source) {
            // Only terminal states may carry a terminal outcome; active ones need fencing.
            Optional<TaskExecutionTerminal> terminal =
                    executionStatus == TaskExecutionStatus.COMPLETED
                            ? Optional.of(new TaskExecutionTerminal(
                                    TaskExecutionStatus.COMPLETED, actor.id(), NOW,
                                    Optional.empty()))
                            : Optional.empty();
            Optional<FencingToken> fencing =
                    executionStatus == TaskExecutionStatus.COMPLETED
                            ? Optional.empty()
                            : Optional.of(FencingToken.initial());
            return TaskExecution.reconstitute(
                    executionId,
                    source.scope(),
                    source.id(),
                    1,
                    3,
                    Optional.empty(),
                    TaskExecutionPriority.NORMAL,
                    NOW,
                    executionStatus,
                    Optional.empty(),
                    Optional.empty(),
                    terminal,
                    Optional.empty(),
                    fencing,
                    0,
                    AuditMetadata.createdBy(actor.id(), NOW));
        }

        @Override
        public Task create(Task value) {
            return value;
        }

        @Override
        public Task update(Task value) {
            return value;
        }

        @Override
        public Optional<Task> findById(OrganizationId organization, TaskId id) {
            return Optional.ofNullable(task).filter(value -> value.id().equals(id));
        }

        @Override
        public List<Task> findByWorkItem(OrganizationId organization, WorkItemId workItemId) {
            return List.of(task);
        }

        @Override
        public List<Task> findByConversation(
                OrganizationId organization, ConversationId conversationId) {
            return List.of();
        }

        @Override
        public TaskExecution create(TaskExecution value) {
            return value;
        }

        @Override
        public TaskExecution update(TaskExecution value) {
            return value;
        }

        @Override
        public Optional<TaskExecution> findById(OrganizationId organization, TaskExecutionId id) {
            if (task == null) {
                return Optional.empty();
            }
            if (crossTeam) {
                WorkItemScope stranger = new WorkItemScope(
                        organizationId, TeamId.generate(),
                        WorkspaceId.generate(), WorkProjectId.generate());
                return Optional.of(TaskExecution.reconstitute(
                        id, stranger, task.id(), 1, 3, Optional.empty(),
                        TaskExecutionPriority.NORMAL, NOW, TaskExecutionStatus.COMPLETED,
                        Optional.empty(), Optional.empty(),
                        Optional.of(new TaskExecutionTerminal(
                                TaskExecutionStatus.COMPLETED, actor.id(), NOW,
                                Optional.empty())),
                        Optional.empty(), 0,
                        AuditMetadata.createdBy(actor.id(), NOW)));
            }
            return Optional.of(completedExecution(id, task));
        }

        @Override
        public List<TaskExecution> findByTask(OrganizationId organization, TaskId id) {
            return List.of();
        }

        @Override
        public List<TaskExecution> findRecoveringForUpdate(OrganizationId organization, int limit) {
            return List.of();
        }

        private static TaskResponsibilitySnapshotEntry responsibility(
                ResponsibilityRole role, PrincipalId principalId, PrincipalType type) {
            return new TaskResponsibilitySnapshotEntry(
                    ResponsibilityAssignmentId.generate(),
                    0,
                    role,
                    principalId,
                    type,
                    type == PrincipalType.USER
                            ? Optional.of(TeamMemberId.generate())
                            : Optional.empty(),
                    NOW,
                    NOW);
        }
    }

    /** One in-stream page of sanitized events for the current execution only. */
    private final class SinglePageTaskEvents implements TaskEventRepository {
        @Override
        public void append(
                TaskEventContext context, DomainEventEnvelope<? extends DomainEvent> domainEvent) {
            throw new UnsupportedOperationException("read-only fixture");
        }

        @Override
        public TaskEventPage findPage(TaskEventQuery query, boolean taskTerminal) {
            TaskExecutionId executionId = tasks.task.currentExecutionId().orElseThrow();
            List<TaskEvent> events = List.of(
                    runEvent(query, executionId, 1, "TEXT_DELTA", "Drained the connection pool."),
                    runEvent(query, executionId, 2, "PROGRESS", "45"),
                    runEvent(query, executionId, 3, "COMPLETED", null),
                    runEvent(query, TaskExecutionId.generate(), 4, "TEXT_DELTA", "Other attempt."));
            return new TaskEventPage(events, false, false);
        }
    }

    private static TaskEvent runEvent(
            TaskEventQuery query, TaskExecutionId executionId, long position, String kind, String text) {
        TaskEventCursor cursor = new TaskEventCursor(
                query.scope().organizationId(), query.scope().teamId(), query.taskId(),
                position, UUID.randomUUID());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventKind", kind);
        if (text != null) {
            payload.put("safeText", text);
        }
        RealtimeEventEnvelope<Map<String, Object>> envelope = new RealtimeEventEnvelope<>(
                cursor.eventId(),
                Optional.of(UUID.randomUUID()),
                StreamType.TASK,
                EventType.from("AGENT_RUN_EVENT_RECORDED"),
                SchemaVersion.V1,
                Optional.empty(),
                Optional.empty(),
                UUID.randomUUID(),
                Optional.empty(),
                NOW,
                payload);
        return new TaskEvent(
                cursor,
                TaskEventContext.agentRun(query.taskId(), executionId, Optional.empty(),
                        AgentRunId.generate()),
                false,
                envelope);
    }

    /** In-memory ports for every command collaborator (mirrors KnowledgeCommandServiceTest). */
    private static final class Store
            implements TeamRepository, TeamMembershipQuery, MemberRoleRepository,
                    DomainEventStore, OutboxRepository, CommandReceiptStore {

        private final TeamInitialization initialization;
        private final Map<String, ReceiptEntry> receipts = new HashMap<>();
        final List<DomainEventEnvelope<? extends DomainEvent>> events = new ArrayList<>();
        final List<PendingOutboxEvent> outbox = new ArrayList<>();
        private final Map<String, CommandResult> results = new HashMap<>();
        private List<TeamMember> members;
        private List<TeamRole> roles;
        private List<MemberRole> grants;

        private Store(TeamInitialization initialization, Principal actor) {
            this.initialization = initialization;
            this.members = List.of(initialization.ownerMember());
            TeamRole ownerRole = TeamRole.createBuiltIn(
                    TeamRoleId.generate(), initialization.team().scope(),
                    BuiltInTeamRole.TEAM_OWNER, NOW);
            this.roles = List.of(ownerRole);
            this.grants = List.of(MemberRole.grantOwner(
                    MemberRoleId.generate(), initialization.team(),
                    initialization.ownerMember(), ownerRole, actor.id(), NOW));
        }

        TeamRoleRepository rolesView() {
            return new TeamRoleRepository() {
                @Override
                public List<TeamRole> createAll(List<TeamRole> values) {
                    roles = List.copyOf(values);
                    return roles;
                }

                @Override
                public List<TeamRole> findByTeam(OrganizationId organization, TeamId team) {
                    return roles;
                }
            };
        }

        void revokeGrants() {
            grants = List.of();
        }

        DomainEventEnvelope<? extends DomainEvent> eventOfType(String type) {
            return events.stream()
                    .filter(event -> event.eventType().value().equals(type))
                    .findFirst()
                    .orElseThrow();
        }

        @Override
        public Team create(Team team) {
            return team;
        }

        @Override
        public Optional<Team> findById(OrganizationId organizationId, TeamId id) {
            return Optional.of(initialization.team())
                    .filter(team -> team.organizationId().equals(organizationId)
                            && team.id().equals(id));
        }

        @Override
        public Optional<UninitializedTeam> findUninitializedById(OrganizationId organizationId, TeamId id) {
            return Optional.empty();
        }

        @Override
        public List<TeamMember> findByTeam(OrganizationId organization, TeamId team) {
            return members;
        }

        @Override
        public MemberRole create(MemberRole memberRole) {
            grants = new ArrayList<>(grants);
            grants.add(memberRole);
            return memberRole;
        }

        @Override
        public List<MemberRole> findByMember(OrganizationId organizationId, TeamMemberId memberId) {
            return grants;
        }

        @Override
        public void append(DomainEventEnvelope<? extends DomainEvent> event) {
            events.add(event);
        }

        @Override
        public void enqueue(PendingOutboxEvent event) {
            outbox.add(event);
        }

        @Override
        public void saveResult(CommandResult result) {
            results.put(result.organizationId() + ":" + result.idempotencyKey(), result);
        }

        @Override
        public Optional<CommandResult> findResult(
                OrganizationId organizationId, IdempotencyKey key, PrincipalId actorId) {
            return Optional.ofNullable(results.get(organizationId + ":" + key))
                    .filter(result -> result.actorId().equals(actorId));
        }

        @Override
        public Optional<CommandReceipt> findCompleted(
                OrganizationId organizationId,
                IdempotencyKey idempotencyKey,
                String commandType,
                CommandRequestHash requestHash) {
            ReceiptEntry existing = receipts.get(organizationId + ":" + idempotencyKey);
            if (existing == null || existing.receipt == null
                    || !existing.request.commandType().equals(commandType)
                    || !existing.request.requestHash().equals(requestHash)) {
                return Optional.empty();
            }
            return Optional.of(existing.receipt);
        }

        @Override
        public CommandReservation reserve(CommandReservationRequest request) {
            String key = request.organizationId() + ":" + request.idempotencyKey();
            ReceiptEntry existing = receipts.get(key);
            if (existing == null) {
                receipts.put(key, new ReceiptEntry(request, null));
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
            ReceiptEntry existing = receipts.get(key);
            receipts.put(key, new ReceiptEntry(existing.request, receipt));
        }

        private record ReceiptEntry(CommandReservationRequest request, CommandReceipt receipt) {}
    }

    private static final class DirectTransactionExecutor implements TransactionExecutor {
        @Override
        public <T> T required(Supplier<T> operation) {
            return operation.get();
        }
    }
}
