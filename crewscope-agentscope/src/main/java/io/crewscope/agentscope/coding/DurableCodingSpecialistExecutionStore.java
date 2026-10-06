package io.crewscope.agentscope.coding;

import io.crewscope.application.coding.CodingCheckpointRepository;
import io.crewscope.application.event.DomainEventExistenceCheck;
import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.event.PendingOutboxEvent;
import io.crewscope.application.execution.DurableTaskExecutionEventService;
import io.crewscope.application.execution.ExecutionFailure;
import io.crewscope.application.execution.ExecutionFailureCategory;
import io.crewscope.application.execution.TaskAgentStateCheckpointCommand;
import io.crewscope.application.execution.TaskAgentStateCheckpointResult;
import io.crewscope.application.execution.TaskAgentStateIdentity;
import io.crewscope.application.execution.TaskAgentStateRecoveryCommand;
import io.crewscope.application.execution.TaskAgentStateRecoveryResult;
import io.crewscope.application.execution.TaskAgentStateSafePoint;
import io.crewscope.application.execution.TaskAgentStateSnapshotService;
import io.crewscope.application.execution.TaskExecutionEvent;
import io.crewscope.application.execution.TaskExecutionEventPayload;
import io.crewscope.application.execution.TaskExecutionRuntimeFacts;
import io.crewscope.application.execution.TaskRuntimeEventCommitCommand;
import io.crewscope.application.execution.TaskRuntimeEventCommitResult;
import io.crewscope.application.coding.output.CodeChangeResultV1;
import io.crewscope.application.coding.output.CodingStructuredOutputSpecs;
import io.crewscope.application.task.AgentStateSnapshotRepository;
import io.crewscope.application.task.StepExecutionRepository;
import io.crewscope.application.transaction.AuthoritativeTimeProvider;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.coding.CodingCheckpoint;
import io.crewscope.domain.coding.CodingCheckpointId;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.model.ModelTokenUsage;
import io.crewscope.domain.model.ModelUsageFactId;
import io.crewscope.domain.model.event.ModelUsageFactRecorded;
import io.crewscope.domain.shared.DomainEvent;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.event.AggregateReference;
import io.crewscope.domain.shared.event.DomainEventEnvelope;
import io.crewscope.domain.shared.event.EventActor;
import io.crewscope.domain.shared.event.EventType;
import io.crewscope.domain.shared.event.SchemaVersion;
import io.crewscope.domain.task.AgentStateSnapshot;
import io.crewscope.domain.task.StepExecution;
import io.crewscope.domain.task.StepExecutionStatus;
import io.crewscope.domain.task.StepWaitReason;
import io.crewscope.domain.task.TaskExecutionFailure;
import io.crewscope.domain.task.TaskExecutionFailureClass;
import io.crewscope.domain.model.ModelCallAttribution;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Production event-first persistence bridge from Coding safe points to M3 durable execution. */
public final class DurableCodingSpecialistExecutionStore
        implements CodingSpecialistExecutionStore {

    /**
     * Deterministic call coordinates for chat usage facts (M10-F03): one namespace per
     * execution attempt and event slot, so replaying the same sequence rebuilds the same
     * callId and the same event id — the existence probe then skips the append, keeping
     * replay idempotent the same way the realtime emitter does. The fact's own attempt
     * stays 1 — framework retries inside one logical call are invisible here by contract.
     */
    private static final String CALL_ID_NAMESPACE = "io.crewscope/model-usage/execution/";

    private static final String USAGE_EVENT_ID_NAMESPACE =
            "io.crewscope/model-usage/event/";
    private static final String USAGE_EVENT_TYPE = "MODEL_USAGE_FACT_RECORDED";
    private static final String USAGE_AGGREGATE_TYPE = "MODEL_USAGE_FACT";

    private final DurableTaskExecutionEventService eventService;
    private final TaskAgentStateSnapshotService snapshotService;
    private final AgentStateSnapshotRepository snapshotRepository;
    private final CodingCheckpointRepository checkpointRepository;
    private final StepExecutionRepository stepRepository;
    private final TransactionExecutor transactionExecutor;
    private final AuthoritativeTimeProvider timeProvider;
    private final DomainEventStore events;
    private final DomainEventExistenceCheck existence;
    private final OutboxRepository outbox;

    public DurableCodingSpecialistExecutionStore(
            DurableTaskExecutionEventService eventService,
            TaskAgentStateSnapshotService snapshotService,
            AgentStateSnapshotRepository snapshotRepository,
            CodingCheckpointRepository checkpointRepository,
            StepExecutionRepository stepRepository,
            TransactionExecutor transactionExecutor,
            AuthoritativeTimeProvider timeProvider,
            DomainEventStore events,
            DomainEventExistenceCheck existence,
            OutboxRepository outbox) {
        this.eventService = Objects.requireNonNull(eventService, "eventService");
        this.snapshotService = Objects.requireNonNull(snapshotService, "snapshotService");
        this.snapshotRepository = Objects.requireNonNull(snapshotRepository, "snapshotRepository");
        this.checkpointRepository = Objects.requireNonNull(
                checkpointRepository, "checkpointRepository");
        this.stepRepository = Objects.requireNonNull(stepRepository, "stepRepository");
        this.transactionExecutor = Objects.requireNonNull(
                transactionExecutor, "transactionExecutor");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
        this.events = Objects.requireNonNull(events, "events");
        this.existence = Objects.requireNonNull(existence, "existence");
        this.outbox = Objects.requireNonNull(outbox, "outbox");
    }

    @Override
    public void beginStep(TaskExecutionRuntimeFacts facts, Principal executor) {
        TaskExecutionRuntimeFacts required = Objects.requireNonNull(facts, "facts");
        transactionExecutor.required(() -> {
            StepExecution step = loadStep(required);
            if (step.status() == StepExecutionStatus.WAITING) {
                step = stepRepository.update(step.markReady(
                        step.version(), executor, timeProvider.now()));
            }
            if (step.status() == StepExecutionStatus.READY) {
                stepRepository.update(step.beginRunning(
                        step.version(), executor, timeProvider.now()));
            } else if (step.status() != StepExecutionStatus.RUNNING) {
                throw new IllegalStateException("Coding Step is not executable");
            }
            return null;
        });
    }

    @Override
    public TaskAgentStateRecoveryResult recoverState(
            TaskExecutionRuntimeFacts facts, int candidateLimit) {
        return snapshotService.recover(new TaskAgentStateRecoveryCommand(
                facts, identity(facts), candidateLimit));
    }

    @Override
    public CodingSpecialistCheckpointReceipt checkpoint(
            CodingSpecialistCheckpointCommand command) {
        CodingSpecialistCheckpointCommand required = Objects.requireNonNull(command, "command");
        TaskExecutionEventPayload payload = switch (required.kind()) {
            case PROGRESS -> new TaskExecutionEventPayload.Progress(
                    required.safeSummary(), Optional.empty());
            case PAUSED -> new TaskExecutionEventPayload.Paused(
                    required.interruptToken().orElseThrow(), required.safeSummary());
            case CANCELLED -> new TaskExecutionEventPayload.Canceled(required.safeSummary());
        };
        TaskRuntimeEventCommitResult event = commit(
                required.facts(),
                required.eventSequence(),
                payload,
                required.correlationId());

        TaskAgentStateSafePoint safePoint = switch (required.kind()) {
            case PROGRESS -> TaskAgentStateSafePoint.CALL_COMPLETED;
            case PAUSED -> TaskAgentStateSafePoint.PAUSED;
            case CANCELLED -> TaskAgentStateSafePoint.INTERRUPTED;
        };
        TaskAgentStateCheckpointResult snapshot = snapshotService.checkpoint(
                new TaskAgentStateCheckpointCommand(
                        required.facts(),
                        identity(required.facts()),
                        required.facts().agentRun().currentSegment().sequence(),
                        required.eventSequence(),
                        safePoint,
                        required.state().agentStateJson(),
                        Optional.empty()));
        AgentStateSnapshot snapshotFact = snapshotRepository.findById(
                        required.facts().task().scope().organizationId(), snapshot.snapshotId())
                .orElseThrow(() -> new AggregateNotFoundException(
                        "AgentStateSnapshot", snapshot.snapshotId()));
        CodingCheckpoint committed = transactionExecutor.required(() -> {
            CodingCheckpoint checkpoint = CodingCheckpoint.capture(
                    CodingCheckpointId.generate(),
                    required.authority().target(),
                    required.authority().workspace(),
                    required.authority().policy(),
                    event.agentRun(),
                    required.facts().planVersion(),
                    required.state().workState(),
                    required.authority().diffManifest(),
                    required.authority().testEvidence(),
                    snapshotFact,
                    required.executor(),
                    timeProvider.now());
            CodingCheckpoint appended = checkpointRepository.append(checkpoint);
            StepExecution step = loadStep(required.facts());
            step = stepRepository.update(step.recordCheckpoint(
                    "CODING_SAFE_POINT",
                    appended.checkpointHash(),
                    step.version(),
                    required.executor(),
                    timeProvider.now()));
            if (required.kind() == CodingSpecialistCheckpointKind.PAUSED) {
                stepRepository.update(step.waitFor(
                        StepWaitReason.AGENT_INTERRUPT,
                        step.version(),
                        required.executor(),
                        timeProvider.now()));
            } else if (required.kind() == CodingSpecialistCheckpointKind.CANCELLED) {
                stepRepository.update(step.cancel(
                        step.version(), required.executor(), timeProvider.now()));
            }
            return appended;
        });
        return new CodingSpecialistCheckpointReceipt(
                committed, snapshot.snapshotId(), required.eventSequence());
    }

    @Override
    public long recordTelemetry(
            TaskExecutionRuntimeFacts facts,
            long eventSequence,
            CodingSpecialistTelemetry telemetry,
            UUID correlationId) {
        long next = eventSequence;
        for (CodingSpecialistModelUsage usage : telemetry.modelUsages()) {
            commitUsage(facts, next++, usage, correlationId);
        }
        int toolIndex = 0;
        for (String toolName : telemetry.toolNames()) {
            commit(
                    facts,
                    next++,
                    new TaskExecutionEventPayload.ToolStarted(
                            "coding-tool-" + eventSequence + "-" + toolIndex++, toolName),
                    correlationId);
        }
        return next;
    }

    /**
     * Commits the durable UsageReported receipt and, when the call carried pinned
     * attribution, the {@code MODEL_USAGE_FACT_RECORDED} fact plus its outbox entry in one
     * REQUIRED unit of work — the fact never survives a rolled-back Agent event. Replay of
     * an already-recorded sequence keeps the receipt path (the event service converges
     * DUPLICATE) and skips the fact append via the event-id existence probe. Unattributed
     * calls (env-slot resolution) keep the usage report but emit no fact, per contract.
     */
    private void commitUsage(
            TaskExecutionRuntimeFacts facts,
            long eventSequence,
            CodingSpecialistModelUsage usage,
            UUID correlationId) {
        transactionExecutor.required(() -> {
            commit(
                    facts,
                    eventSequence,
                    new TaskExecutionEventPayload.UsageReported(
                            usage.inputTokens(),
                            usage.outputTokens(),
                            usage.cachedTokens(),
                            usage.totalTokens()),
                    correlationId);
            usage.attribution().ifPresent(attribution -> appendUsageFact(
                    facts, attribution, usage, eventSequence, correlationId));
            return null;
        });
    }

    private void appendUsageFact(
            TaskExecutionRuntimeFacts facts,
            ModelCallAttribution attribution,
            CodingSpecialistModelUsage usage,
            long eventSequence,
            UUID correlationId) {
        String callSource = CALL_ID_NAMESPACE
                + facts.execution().id().value() + "/"
                + facts.execution().attempt() + "/"
                + eventSequence;
        ModelUsageFactId callId = new ModelUsageFactId(UUID.nameUUIDFromBytes(
                callSource.getBytes(StandardCharsets.UTF_8)));
        UUID eventId = stableUsageEventId(callId);
        if (existence.exists(eventId)) {
            // DUPLICATE replay of the usage receipt: the fact is already in the log, and a
            // bare re-append would trip the canonical log's primary key.
            return;
        }
        UtcTimestamp occurredAt = timeProvider.now();
        ModelUsageFactRecorded payload = new ModelUsageFactRecorded(
                callId,
                attribution.role(),
                1,
                attribution.providerKey(),
                attribution.modelId(),
                attribution.connectionId(),
                attribution.connectionVersion(),
                new ModelTokenUsage(
                        usage.inputTokens(),
                        usage.outputTokens(),
                        usage.cachedTokens(),
                        usage.totalTokens()),
                occurredAt);
        DomainEventEnvelope<DomainEvent> event = new DomainEventEnvelope<>(
                eventId,
                EventType.from(USAGE_EVENT_TYPE),
                SchemaVersion.V1,
                facts.task().scope().organizationId(),
                Optional.of(facts.task().scope().teamId()),
                Optional.empty(),
                AggregateReference.of(USAGE_AGGREGATE_TYPE, callId),
                1L,
                EventActor.anonymousService(),
                correlationId,
                Optional.empty(),
                Optional.empty(),
                occurredAt,
                payload);
        events.append(event);
        outbox.enqueue(PendingOutboxEvent.fromDomain(UUID.randomUUID(), event));
    }

    private static UUID stableUsageEventId(ModelUsageFactId callId) {
        String source = USAGE_EVENT_ID_NAMESPACE + callId.value();
        return UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void succeed(
            TaskExecutionRuntimeFacts facts,
            long eventSequence,
            CodeChangeResultV1 result,
            Principal executor,
            UUID correlationId) {
        commit(
                facts,
                eventSequence,
                new TaskExecutionEventPayload.StructuredOutput<>(
                        CodingStructuredOutputSpecs.CODE_CHANGE_RESULT,
                        Objects.requireNonNull(result, "result")),
                correlationId);
        commit(
                facts,
                eventSequence + 1,
                new TaskExecutionEventPayload.Completed(Optional.empty()),
                correlationId);
        transactionExecutor.required(() -> {
            StepExecution step = loadStep(facts);
            stepRepository.update(step.succeed(
                    step.version(), executor, timeProvider.now()));
            return null;
        });
    }

    @Override
    public void fail(
            TaskExecutionRuntimeFacts facts,
            long eventSequence,
            String failureCode,
            boolean retryable,
            Principal executor,
            UUID correlationId) {
        String code = Objects.requireNonNull(failureCode, "failureCode");
        commit(
                facts,
                eventSequence,
                new TaskExecutionEventPayload.Failed(new ExecutionFailure(
                        retryable
                                ? ExecutionFailureCategory.INTERNAL
                                : ExecutionFailureCategory.VALIDATION,
                        retryable,
                        "Coding Specialist execution did not complete",
                        Optional.of(code))),
                correlationId);
        transactionExecutor.required(() -> {
            StepExecution step = loadStep(facts);
            TaskExecutionFailure failure = new TaskExecutionFailure(
                    retryable
                            ? TaskExecutionFailureClass.TRANSIENT
                            : TaskExecutionFailureClass.VALIDATION,
                    code);
            stepRepository.update(step.fail(
                    failure, step.version(), executor, timeProvider.now()));
            return null;
        });
    }

    private TaskRuntimeEventCommitResult commit(
            TaskExecutionRuntimeFacts facts,
            long eventSequence,
            TaskExecutionEventPayload payload,
            UUID correlationId) {
        // PostgreSQL authoritative time deliberately requires an active transaction. Keep the
        // Agent event timestamp and its durable receipt in one REQUIRED unit of work so a finite
        // Specialist failure cannot be obscured by an out-of-transaction clock access.
        return transactionExecutor.required(() -> {
            TaskExecutionEvent event = new TaskExecutionEvent(
                    facts.execution().id(),
                    facts.execution().attempt(),
                    facts.agentRun().id(),
                    facts.agentRun().currentSegment().sequence(),
                    eventSequence,
                    timeProvider.now(),
                    payload);
            return eventService.commit(new TaskRuntimeEventCommitCommand(
                    facts, event, correlationId, Optional.empty()));
        });
    }

    private StepExecution loadStep(TaskExecutionRuntimeFacts facts) {
        var id = facts.stepExecution().orElseThrow().id();
        return stepRepository.findById(facts.task().scope().organizationId(), id)
                .orElseThrow(() -> new AggregateNotFoundException("StepExecution", id));
    }

    private static TaskAgentStateIdentity identity(TaskExecutionRuntimeFacts facts) {
        String stableId = TaskAgentStateIdentity.stableAgentId(
                facts.runtimeSession().agentProfileId(),
                facts.runtimeSession().agentProfileVersion(),
                facts.runtimeSession().purpose());
        var key = facts.runtimeSession().agentScopeKey();
        return new TaskAgentStateIdentity(
                facts.execution().id().value(),
                facts.agentRun().id().value(),
                stableId,
                stableId,
                Long.toString(facts.runtimeSession().agentProfileVersion()),
                key.userId(),
                key.sessionId());
    }
}
