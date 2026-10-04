package io.crewscope.application.skill;

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
import io.crewscope.application.knowledge.KnowledgeDistillationSourceAssembler;
import io.crewscope.application.task.TaskEvent;
import io.crewscope.application.task.TaskEventContext;
import io.crewscope.application.task.TaskEventCursor;
import io.crewscope.application.task.TaskEventPage;
import io.crewscope.application.task.TaskEventQuery;
import io.crewscope.application.task.TaskEventRepository;
import io.crewscope.application.task.TaskExecutionRepository;
import io.crewscope.application.task.TaskRepository;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamCommandContext;
import io.crewscope.application.team.TeamMembershipQuery;
import io.crewscope.application.team.TeamRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.conversation.ConversationId;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
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
import io.crewscope.domain.skill.TeamSkill;
import io.crewscope.domain.skill.TeamSkillKey;
import io.crewscope.domain.skill.TeamSkillKeyConflictException;
import io.crewscope.domain.skill.TeamSkillOrigin;
import io.crewscope.domain.skill.TeamSkillStatus;
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
import io.crewscope.domain.team.Team;
import io.crewscope.domain.team.TeamInitialization;
import io.crewscope.domain.team.TeamJoinMethod;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.team.TeamMemberStatus;
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
 * Four-stage skill distillation contract for M10-A03b: the own-execution selection
 * right, the DRAFT outcome with the command-owned key in server-assembled frontmatter,
 * receipt semantics and the independence of usage facts from the skill commit — with
 * a fake Port and in-memory collaborators only.
 */
final class SkillDistillationServiceTest {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-10-04T09:00:00Z");
    private static final TeamSkillKey SKILL_KEY = new TeamSkillKey("release-review-checklist");

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
    private final InMemoryTeamSkillRepository repository = new InMemoryTeamSkillRepository();
    private final Store store = new Store(initialization);
    private final FakeTaskStore tasks = new FakeTaskStore();
    private final FakePort port = new FakePort();
    private final FakeReadiness readiness = new FakeReadiness();

    private SkillDistillationService service;
    private TaskExecution execution;

    @BeforeEach
    void setUp() {
        tasks.task = tasks.completedTask(organizationId, teamId, actor.id());
        execution = tasks.completedExecution(tasks.currentExecutionId, tasks.task);
        KnowledgeDistillationSourceAssembler assembler =
                new KnowledgeDistillationSourceAssembler(new SinglePageTaskEvents());
        service = new SkillDistillationService(
                repository,
                store,
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
                Runnable::run,
                true);
    }

    // ------------------------------------------------------------------ happy path

    @Test
    void distillsACompletedCurrentAttemptIntoAnOriginCarryingDraftWithCommandOwnedKey() {
        port.nextResult = draft("Release review checklist", "Verify green build first.");

        CommandExecution<TeamSkill> executionResult =
                service.distill(context("distill-1"), teamId, command())
                        .toCompletableFuture().join();

        TeamSkill skill = executionResult.result().orElseThrow();
        assertEquals(TeamSkillStatus.DRAFT, skill.status());
        assertEquals(SKILL_KEY, skill.skillKey());
        assertEquals(Optional.of(new TeamSkillOrigin(
                execution.id().value(), execution.attempt())), skill.origin());
        assertEquals(0, skill.version());
        assertEquals(SKILL_KEY.value(), skill.draft().orElseThrow().name());
        assertTrue(skill.draft().orElseThrow().content()
                .startsWith("---\nname: " + SKILL_KEY.value() + "\ndescription: "));
        assertTrue(skill.draft().orElseThrow().content().contains("Verify green build first."));

        // One created fact plus one usage fact per real attempt, both on the outbox.
        DomainEventEnvelope<? extends DomainEvent> created =
                store.eventOfType("TEAM_SKILL_CREATED");
        assertEquals(skill.id().value(), created.aggregate().id());
        DomainEventEnvelope<? extends DomainEvent> usage =
                store.eventOfType("MODEL_USAGE_FACT_RECORDED");
        ModelUsageFactRecorded payload = assertInstanceOf(
                ModelUsageFactRecorded.class, usage.payload());
        assertEquals(io.crewscope.domain.model.ModelUsageRole.DISTILLATION, payload.role());
        assertEquals("openai", payload.providerKey().value());
        assertEquals(2, store.outbox.size());

        CommandResult result = store.findResult(
                organizationId, IdempotencyKey.from("distill-1"), actor.id())
                .orElseThrow();
        assertEquals(CommandResult.ResourceType.TEAM_SKILL, result.resourceType());
        assertEquals(0, result.resourceVersion());
    }

    @Test
    void foldsMultilineDescriptionsOntoOneFrontmatterLine() {
        port.nextResult = draft("Line one\nline\ttwo\n\nline three", "Body");

        CommandExecution<TeamSkill> executionResult =
                service.distill(context("distill-fold"), teamId, command())
                        .toCompletableFuture().join();

        String content = executionResult.result().orElseThrow()
                .draft().orElseThrow().content();
        assertEquals("Line one line two line three",
                executionResult.result().orElseThrow().draft().orElseThrow().description());
        assertTrue(content.contains("description: Line one line two line three\n"));
    }

    @Test
    void sameIdempotencyKeyReplaysWithoutASecondModelCall() {
        port.nextResult = draft("Description", "Body");
        service.distill(context("distill-replay"), teamId, command())
                .toCompletableFuture().join();

        CommandExecution<TeamSkill> replay = service.distill(
                context("distill-replay"), teamId, command())
                .toCompletableFuture().join();

        assertTrue(replay.replayed());
        assertEquals(1, port.calls.get());
        assertEquals(1, eventsOf("TEAM_SKILL_CREATED").size(),
                "no second skill was committed");
        assertEquals(1, eventsOf("MODEL_USAGE_FACT_RECORDED").size());
    }

    // ------------------------------------------------------------------ failure semantics

    @Test
    void modelFailureLeavesNoReceiptAndNoSkill() {
        port.nextFailure = new IllegalStateException("provider unavailable");

        assertThrows(
                CompletionException.class,
                () -> service.distill(context("distill-llm-fail"), teamId, command())
                        .toCompletableFuture().join());

        assertTrue(store.events.isEmpty());
        assertTrue(store.outbox.isEmpty());
        assertTrue(store.findCompleted(
                organizationId, IdempotencyKey.from("distill-llm-fail"),
                "DISTILL_TEAM_SKILL", anyHash()).isEmpty());
        assertTrue(repository.findByKey(organizationId, teamId, SKILL_KEY).isEmpty());
    }

    @Test
    void usageFactsSurviveASkillCommitFailure() {
        port.nextResult = draft("Description", "Body");
        repository.failNextCreate = true;

        assertThrows(
                CompletionException.class,
                () -> service.distill(context("distill-commit-fail"), teamId, command())
                        .toCompletableFuture().join());

        List<DomainEventEnvelope<? extends DomainEvent>> usage =
                eventsOf("MODEL_USAGE_FACT_RECORDED");
        assertEquals(1, usage.size());
        assertTrue(store.findCompleted(
                organizationId, IdempotencyKey.from("distill-commit-fail"),
                "DISTILL_TEAM_SKILL", anyHash()).isEmpty());
    }

    @Test
    void nonCompletedExecutionIsRejectedBeforeTheModelCall() {
        tasks.executionStatus = TaskExecutionStatus.RUNNING;
        port.nextResult = draft("Nope", "Body");

        DomainValidationException failure = assertThrows(
                DomainValidationException.class,
                () -> service.distill(context("distill-running"), teamId, command()));
        assertEquals("distillation.taskExecutionStatus", failure.error().details().get("field"));
        assertEquals(0, port.calls.get());
    }

    @Test
    void staleAttemptIsRejectedBeforeTheModelCall() {
        tasks.currentExecutionId = TaskExecutionId.generate();
        tasks.task = tasks.completedTask(organizationId, teamId, actor.id());
        port.nextResult = draft("Nope", "Body");

        DomainValidationException failure = assertThrows(
                DomainValidationException.class,
                () -> service.distill(context("distill-stale"), teamId, command()));
        assertEquals("distillation.taskExecutionId", failure.error().details().get("field"));
        assertEquals(0, port.calls.get());
    }

    @Test
    void crossTeamExecutionIsNotFound() {
        tasks.crossTeam = true;

        assertThrows(
                AggregateNotFoundException.class,
                () -> service.distill(context("distill-cross"), teamId, command()));
        assertEquals(0, port.calls.get());
    }

    @Test
    void duplicateKeyFailsFastBeforeTheModelCall() {
        repository.create(TeamSkill.create(
                initialization.team().scope(),
                SKILL_KEY,
                "---\nname: " + SKILL_KEY.value() + "\ndescription: existing\n---\n\nBody\n",
                Optional.empty(),
                actor.id(),
                NOW));
        port.nextResult = draft("Description", "Body");

        assertThrows(
                TeamSkillKeyConflictException.class,
                () -> service.distill(context("distill-dup"), teamId, command()));
        assertEquals(0, port.calls.get());
        assertEquals(0, readiness.calls.get());
    }

    // ------------------------------------------------------------------ own-execution gate

    @Test
    void onlyTheTaskCreatorMayDistill() {
        Principal stranger = Principal.create(
                PrincipalId.generate(),
                PrincipalScope.organization(organizationId),
                PrincipalType.USER,
                Optional.empty(),
                "Stranger",
                Optional.empty(),
                PrincipalVisibility.ORGANIZATION,
                NOW);
        store.addMember(initialization.team(), stranger);
        port.nextResult = draft("Description", "Body");

        assertThrows(
                PolicyDeniedException.class,
                () -> service.distill(
                        new TeamCommandContext(
                                new TeamAccessContext(stranger, false),
                                IdempotencyKey.from("distill-stranger"),
                                UUID.randomUUID(),
                                Optional.empty()),
                        teamId, command()));
        assertEquals(0, port.calls.get());
    }

    @Test
    void platformAdministratorBypassesTheOwnExecutionGate() {
        port.nextResult = draft("Description", "Body");

        Principal stranger = Principal.create(
                PrincipalId.generate(),
                PrincipalScope.organization(organizationId),
                PrincipalType.USER,
                Optional.empty(),
                "Admin",
                Optional.empty(),
                PrincipalVisibility.ORGANIZATION,
                NOW);
        store.addMember(initialization.team(), stranger);
        CommandExecution<TeamSkill> executionResult = service.distill(
                new TeamCommandContext(
                        new TeamAccessContext(stranger, true),
                        IdempotencyKey.from("distill-admin"),
                        UUID.randomUUID(),
                        Optional.empty()),
                teamId, command())
                .toCompletableFuture().join();

        assertEquals(SKILL_KEY, executionResult.result().orElseThrow().skillKey());
    }

    // ------------------------------------------------------------------ write switch

    @Test
    void disabledWriteSwitchRejectsDistillation() {
        SkillDistillationService switchedOff = new SkillDistillationService(
                repository, store, store, tasks, tasks,
                new KnowledgeDistillationSourceAssembler(new SinglePageTaskEvents()),
                readiness, port, store, store, store,
                new DirectTransactionExecutor(), () -> NOW, Runnable::run, false);
        port.nextResult = draft("Description", "Body");

        assertThrows(
                io.crewscope.domain.skill.TeamSkillDisabledException.class,
                () -> switchedOff.distill(context("distill-off"), teamId, command()));
        assertEquals(0, port.calls.get());
    }

    @Test
    void readinessIsEnsuredExactlyOncePerSuccessfulPreparation() {
        port.nextResult = draft("Description", "Body");
        service.distill(context("distill-ready"), teamId, command())
                .toCompletableFuture().join();

        assertEquals(1, readiness.calls.get());
        assertEquals(organizationId, readiness.lastOrganizationId);
        assertEquals(teamId, readiness.lastTeamId);
    }

    // ------------------------------------------------------------------ helpers

    private static SkillDistillationPort.DistilledSkillDraft draft(
            String description, String body) {
        return new SkillDistillationPort.DistilledSkillDraft(description, body);
    }

    private static SkillDistillationPort.CallAttribution fixedAttribution() {
        return new SkillDistillationPort.CallAttribution(
                new ModelProviderKey("openai"),
                new ModelId("gpt-5"),
                ModelConnectionId.generate(),
                3,
                List.of(new SkillDistillationPort.AttemptUsage(
                        1, new ModelTokenUsage(1200, 80, 900, 1280))));
    }

    private DistillTeamSkillCommand command() {
        return new DistillTeamSkillCommand(execution.id(), SKILL_KEY);
    }

    private TeamCommandContext context(String idempotencyKey) {
        return new TeamCommandContext(
                new TeamAccessContext(actor, false),
                IdempotencyKey.from(idempotencyKey), UUID.randomUUID(), Optional.empty());
    }

    private static CommandRequestHash anyHash() {
        return CommandRequestHash.sha256("DISTILL_TEAM_SKILL", "any");
    }

    private List<DomainEventEnvelope<? extends DomainEvent>> eventsOf(String type) {
        return store.events.stream()
                .filter(event -> event.eventType().value().equals(type))
                .toList();
    }

    /** Returns the configured draft with a fixed attribution shape. */
    private static final class FakePort implements SkillDistillationPort {
        final AtomicInteger calls = new AtomicInteger();
        Optional<SkillDistillationPort.SkillDistillationRequest> lastRequest =
                Optional.empty();
        SkillDistillationPort.DistilledSkillDraft nextResult;
        RuntimeException nextFailure;

        @Override
        public CompletionStage<SkillDistillationResult> distill(
                SkillDistillationRequest request) {
            calls.incrementAndGet();
            lastRequest = Optional.of(request);
            if (nextFailure != null) {
                return CompletableFuture.failedFuture(nextFailure);
            }
            return CompletableFuture.completedFuture(
                    new SkillDistillationResult(nextResult, fixedAttribution()));
        }
    }

    private static final class FakeReadiness implements SkillDistillerReadiness {
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

        private TaskResponsibilitySnapshotEntry responsibility(
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

    private TaskEvent runEvent(
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

    /** In-memory ports for every command collaborator (no role grants: the gate is ownership). */
    private static final class Store
            implements TeamRepository, TeamMembershipQuery,
                    DomainEventStore, OutboxRepository, CommandReceiptStore {

        private final TeamInitialization initialization;
        private final Map<String, ReceiptEntry> receipts = new HashMap<>();
        final List<DomainEventEnvelope<? extends DomainEvent>> events = new ArrayList<>();
        final List<PendingOutboxEvent> outbox = new ArrayList<>();
        private final Map<String, CommandResult> results = new HashMap<>();
        private List<TeamMember> members;

        private Store(TeamInitialization initialization) {
            this.initialization = initialization;
            this.members = List.of(initialization.ownerMember());
        }

        void addMember(Team team, Principal user) {
            List<TeamMember> expanded = new ArrayList<>(members);
            expanded.add(TeamMember.reconstitute(
                    TeamMemberId.generate(),
                    team.scope(),
                    user.id(),
                    TeamMemberStatus.ACTIVE,
                    TeamJoinMethod.IMPORT,
                    Optional.empty(),
                    Optional.of(NOW),
                    Optional.empty(),
                    0,
                    io.crewscope.domain.shared.audit.LifecycleMetadata.createdAt(NOW)));
            members = List.copyOf(expanded);
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
