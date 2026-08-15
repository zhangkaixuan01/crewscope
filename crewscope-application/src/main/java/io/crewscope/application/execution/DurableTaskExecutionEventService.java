package io.crewscope.application.execution;

import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.event.PendingOutboxEvent;
import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.application.task.AgentInterruptRepository;
import io.crewscope.application.task.AgentRunRepository;
import io.crewscope.application.task.ExecutionLeaseRepository;
import io.crewscope.application.task.RuntimeArtifactRepository;
import io.crewscope.application.transaction.AuthoritativeTimeProvider;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.event.AggregateReference;
import io.crewscope.domain.shared.event.DomainEventEnvelope;
import io.crewscope.domain.shared.event.EventActor;
import io.crewscope.domain.shared.event.EventActorType;
import io.crewscope.domain.shared.event.EventType;
import io.crewscope.domain.shared.event.SchemaVersion;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.AgentInterrupt;
import io.crewscope.domain.task.AgentInterruptId;
import io.crewscope.domain.task.AgentInterruptKind;
import io.crewscope.domain.task.AgentRun;
import io.crewscope.domain.task.ExecutionLease;
import io.crewscope.domain.task.LeaseOwnership;
import io.crewscope.domain.task.RuntimeArtifact;
import io.crewscope.domain.task.RuntimeArtifactId;
import io.crewscope.domain.task.RuntimeContentHash;
import io.crewscope.domain.task.event.AgentRunEventRecorded;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** Atomically consumes one finite Task runtime event into durable AgentRun facts. */
public final class DurableTaskExecutionEventService {

    public static final EventType AGENT_RUN_EVENT_RECORDED =
            EventType.from("AGENT_RUN_EVENT_RECORDED");
    public static final String AGENT_RUN_AGGREGATE = "AGENT_RUN";

    private final AgentRunRepository runRepository;
    private final AgentInterruptRepository interruptRepository;
    private final RuntimeArtifactRepository artifactRepository;
    private final TaskRuntimeEventReceiptRepository receiptRepository;
    private final ExecutionLeaseRepository leaseRepository;
    private final PrincipalRepository principalRepository;
    private final TaskExecutionEventEncoder eventEncoder;
    private final DomainEventStore eventStore;
    private final OutboxRepository outboxRepository;
    private final TransactionExecutor transactionExecutor;
    private final AuthoritativeTimeProvider timeProvider;
    private final Supplier<AgentInterruptId> interruptIdFactory;

    public DurableTaskExecutionEventService(
            AgentRunRepository runRepository,
            AgentInterruptRepository interruptRepository,
            RuntimeArtifactRepository artifactRepository,
            TaskRuntimeEventReceiptRepository receiptRepository,
            ExecutionLeaseRepository leaseRepository,
            PrincipalRepository principalRepository,
            TaskExecutionEventEncoder eventEncoder,
            DomainEventStore eventStore,
            OutboxRepository outboxRepository,
            TransactionExecutor transactionExecutor,
            AuthoritativeTimeProvider timeProvider) {
        this(
                runRepository,
                interruptRepository,
                artifactRepository,
                receiptRepository,
                leaseRepository,
                principalRepository,
                eventEncoder,
                eventStore,
                outboxRepository,
                transactionExecutor,
                timeProvider,
                AgentInterruptId::generate);
    }

    DurableTaskExecutionEventService(
            AgentRunRepository runRepository,
            AgentInterruptRepository interruptRepository,
            RuntimeArtifactRepository artifactRepository,
            TaskRuntimeEventReceiptRepository receiptRepository,
            ExecutionLeaseRepository leaseRepository,
            PrincipalRepository principalRepository,
            TaskExecutionEventEncoder eventEncoder,
            DomainEventStore eventStore,
            OutboxRepository outboxRepository,
            TransactionExecutor transactionExecutor,
            AuthoritativeTimeProvider timeProvider,
            Supplier<AgentInterruptId> interruptIdFactory) {
        this.runRepository = Objects.requireNonNull(runRepository, "runRepository");
        this.interruptRepository = Objects.requireNonNull(interruptRepository, "interruptRepository");
        this.artifactRepository = Objects.requireNonNull(artifactRepository, "artifactRepository");
        this.receiptRepository = Objects.requireNonNull(receiptRepository, "receiptRepository");
        this.leaseRepository = Objects.requireNonNull(leaseRepository, "leaseRepository");
        this.principalRepository = Objects.requireNonNull(principalRepository, "principalRepository");
        this.eventEncoder = Objects.requireNonNull(eventEncoder, "eventEncoder");
        this.eventStore = Objects.requireNonNull(eventStore, "eventStore");
        this.outboxRepository = Objects.requireNonNull(outboxRepository, "outboxRepository");
        this.transactionExecutor = Objects.requireNonNull(transactionExecutor, "transactionExecutor");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
        this.interruptIdFactory = Objects.requireNonNull(interruptIdFactory, "interruptIdFactory");
    }

    /** Serializes sequence inspection, state changes, event append, Outbox and receipt creation. */
    public TaskRuntimeEventCommitResult commit(TaskRuntimeEventCommitCommand command) {
        TaskRuntimeEventCommitCommand required = Objects.requireNonNull(command, "command");
        TaskExecutionEventEncoding encoding = eventEncoder.encode(required.event());
        return transactionExecutor.required(() -> commitInTransaction(required, encoding));
    }

    private TaskRuntimeEventCommitResult commitInTransaction(
            TaskRuntimeEventCommitCommand command, TaskExecutionEventEncoding encoding) {
        TaskExecutionRuntimeFacts facts = command.facts();
        TaskExecutionEvent runtimeEvent = command.event();
        var organizationId = facts.task().scope().organizationId();
        TaskRuntimeEventCommitWindow window = receiptRepository.lockCommitWindow(
                organizationId,
                runtimeEvent.agentRunId(),
                runtimeEvent.segmentSequence(),
                runtimeEvent.sequence());
        if (window.existingReceipt().isPresent()) {
            TaskRuntimeEventReceipt receipt = window.existingReceipt().orElseThrow();
            if (!receipt.eventHash().equals(encoding.fingerprint())) {
                throw invalid("event sequence was replayed with different content");
            }
            AgentRun committed = loadRun(organizationId, runtimeEvent);
            requireCurrentBoundary(facts, committed, runtimeEvent);
            Optional<AgentInterruptId> pendingInterrupt = interruptRepository
                    .findPendingByRun(organizationId, committed.id())
                    .map(AgentInterrupt::id);
            return new TaskRuntimeEventCommitResult(
                    TaskRuntimeEventCommitStatus.DUPLICATE,
                    receipt.domainEventId(),
                    committed,
                    pendingInterrupt);
        }
        if (runtimeEvent.sequence() != window.nextSequence()) {
            throw invalid("event sequence must equal the next durable Segment sequence");
        }

        UtcTimestamp recordedAt = timeProvider.now();
        requireCurrentLease(facts, organizationId, recordedAt);
        AgentRun run = loadRun(organizationId, runtimeEvent);
        requireCurrentBoundary(facts, run, runtimeEvent);
        Principal actor = principalRepository
                .findById(organizationId, run.agentPrincipalId())
                .orElseThrow(() -> new AggregateNotFoundException(
                        "Principal", run.agentPrincipalId()));
        if (!actor.canAct()) {
            throw invalid("AgentRun Principal must remain active while committing events");
        }
        AppliedEvent applied = apply(runtimeEvent.payload(), run, actor, recordedAt, organizationId);
        AgentRun committedRun = applied.runChanged()
                ? runRepository.update(applied.run())
                : applied.run();

        AgentRunEventRecorded publicPayload = encoding.publicEvent();
        requirePublicCoordinates(runtimeEvent, publicPayload);
        UUID domainEventId = deterministicEventId(runtimeEvent);
        DomainEventEnvelope<AgentRunEventRecorded> event = new DomainEventEnvelope<>(
                domainEventId,
                AGENT_RUN_EVENT_RECORDED,
                SchemaVersion.V1,
                organizationId,
                Optional.of(run.scope().teamId()),
                Optional.of(run.scope().workspaceId()),
                AggregateReference.of(AGENT_RUN_AGGREGATE, run.id()),
                committedRun.version(),
                EventActor.principal(EventActorType.valueOf(actor.type().name()), actor.id()),
                command.correlationId(),
                command.causationId(),
                Optional.of(idempotencyKey(runtimeEvent)),
                recordedAt,
                publicPayload);
        eventStore.append(event);
        outboxRepository.enqueue(PendingOutboxEvent.fromDomain(UUID.randomUUID(), event));
        receiptRepository.create(new TaskRuntimeEventReceipt(
                organizationId,
                run.id(),
                runtimeEvent.segmentSequence(),
                runtimeEvent.sequence(),
                encoding.fingerprint(),
                publicPayload.eventKind(),
                domainEventId,
                runtimeEvent.occurredAt(),
                recordedAt));
        return new TaskRuntimeEventCommitResult(
                TaskRuntimeEventCommitStatus.COMMITTED,
                domainEventId,
                committedRun,
                applied.interruptId());
    }

    private AppliedEvent apply(
            TaskExecutionEventPayload payload,
            AgentRun run,
            Principal actor,
            UtcTimestamp recordedAt,
            io.crewscope.domain.shared.id.OrganizationId organizationId) {
        if (payload instanceof TaskExecutionEventPayload.Started started) {
            if (started.segmentKind() != run.currentSegment().kind()) {
                throw invalid("Started event kind must match the durable AgentRun Segment");
            }
            return AppliedEvent.unchanged(run);
        }
        if (payload instanceof TaskExecutionEventPayload.ApprovalRequired approval) {
            return interrupt(
                    run,
                    mapInterruptKind(approval.kind()),
                    RuntimeContentHash.sha256(approval.token().value()),
                    actor,
                    recordedAt);
        }
        if (payload instanceof TaskExecutionEventPayload.Paused paused) {
            return interrupt(
                    run,
                    AgentInterruptKind.PAUSE,
                    RuntimeContentHash.sha256(paused.token().value()),
                    actor,
                    recordedAt);
        }
        if (payload instanceof TaskExecutionEventPayload.Completed completed) {
            Optional<RuntimeArtifact> result = completed.resultArtifactId()
                    .map(id -> loadArtifact(organizationId, run, id));
            return AppliedEvent.changed(run.complete(
                    result, run.version(), actor, recordedAt));
        }
        if (payload instanceof TaskExecutionEventPayload.Failed failed) {
            return AppliedEvent.changed(run.fail(
                    failed.failure().category().name(),
                    Optional.empty(),
                    run.version(),
                    actor,
                    recordedAt));
        }
        if (payload instanceof TaskExecutionEventPayload.Canceled) {
            return AppliedEvent.changed(run.cancel(run.version(), actor, recordedAt));
        }
        if (payload instanceof TaskExecutionEventPayload.ArtifactCreated created) {
            RuntimeArtifact artifact = loadArtifact(organizationId, run, created.artifactId());
            if (artifact.kind() != created.kind()) {
                throw invalid("Artifact event kind must match durable RuntimeArtifact metadata");
            }
        } else if (payload instanceof TaskExecutionEventPayload.ToolResult result) {
            result.artifactId().ifPresent(id -> loadArtifact(organizationId, run, id));
        }
        return AppliedEvent.unchanged(run);
    }

    private AppliedEvent interrupt(
            AgentRun run,
            AgentInterruptKind kind,
            RuntimeContentHash tokenHash,
            Principal actor,
            UtcTimestamp recordedAt) {
        AgentInterrupt interrupt = AgentInterrupt.open(
                interruptIdFactory.get(), run, kind, tokenHash, actor, recordedAt);
        AgentInterrupt committedInterrupt = interruptRepository.createPending(interrupt);
        AgentRun interrupted = run.interrupt(
                committedInterrupt, run.version(), actor, recordedAt);
        return new AppliedEvent(interrupted, true, Optional.of(committedInterrupt.id()));
    }

    private RuntimeArtifact loadArtifact(
            io.crewscope.domain.shared.id.OrganizationId organizationId,
            AgentRun run,
            RuntimeArtifactId artifactId) {
        RuntimeArtifact artifact = artifactRepository.findById(organizationId, artifactId)
                .orElseThrow(() -> new AggregateNotFoundException("RuntimeArtifact", artifactId));
        boolean current = artifact.scope().equals(run.scope())
                && artifact.taskId().equals(run.taskId())
                && artifact.executionId().equals(run.executionId())
                && artifact.stepExecutionId().equals(run.stepExecutionId())
                && artifact.agentRunId().equals(run.id());
        if (!current) {
            throw invalid("RuntimeArtifact must belong to the exact AgentRun boundary");
        }
        return artifact;
    }

    private AgentRun loadRun(
            io.crewscope.domain.shared.id.OrganizationId organizationId,
            TaskExecutionEvent event) {
        return runRepository.findById(organizationId, event.agentRunId())
                .orElseThrow(() -> new AggregateNotFoundException("AgentRun", event.agentRunId()));
    }

    private void requireCurrentLease(
            TaskExecutionRuntimeFacts facts,
            io.crewscope.domain.shared.id.OrganizationId organizationId,
            UtcTimestamp authoritativeNow) {
        ExecutionLease expected = facts.lease();
        ExecutionLease locked = leaseRepository.findByIdForUpdate(
                        organizationId, expected.environment(), expected.id())
                .orElseThrow(() -> invalid("ExecutionLease no longer exists"));
        LeaseOwnership ownership = new LeaseOwnership(
                expected.taskExecutionId(),
                expected.attempt(),
                expected.runtimeId(),
                expected.workerId(),
                expected.claimTokenHash(),
                expected.fencingToken());
        if (!locked.owns(ownership, authoritativeNow)) {
            throw invalid("event requires the current active ExecutionLease owner");
        }
    }

    private static void requireCurrentBoundary(
            TaskExecutionRuntimeFacts facts, AgentRun run, TaskExecutionEvent event) {
        boolean current = run.scope().equals(facts.task().scope())
                && run.taskId().equals(facts.task().id())
                && run.executionId().equals(facts.execution().id())
                && run.stepExecutionId().equals(facts.stepExecution().map(
                        io.crewscope.domain.task.StepExecution::id))
                && run.runtimeSessionId().equals(facts.runtimeSession().id())
                && run.agentPrincipalId().equals(facts.runtimeSession().agentPrincipalId())
                && run.agentProfileId().equals(facts.runtimeSession().agentProfileId())
                && run.agentProfileVersion() == facts.runtimeSession().agentProfileVersion()
                && run.currentSegment().sequence() == event.segmentSequence();
        if (!current) {
            throw invalid("event crossed the Task, Session, AgentRun or Segment boundary");
        }
    }

    private static void requirePublicCoordinates(
            TaskExecutionEvent event, AgentRunEventRecorded payload) {
        if (!payload.taskExecutionId().equals(event.taskExecutionId().value())
                || payload.attempt() != event.attempt()
                || !payload.agentRunId().equals(event.agentRunId().value())
                || payload.segmentSequence() != event.segmentSequence()
                || payload.eventSequence() != event.sequence()
                || !payload.runtimeOccurredAt().equals(event.occurredAt())) {
            throw invalid("event encoder changed trusted runtime coordinates");
        }
    }

    private static AgentInterruptKind mapInterruptKind(ExecutionInterruptKind kind) {
        return switch (kind) {
            case CLARIFICATION -> AgentInterruptKind.CLARIFICATION;
            case TOOL_APPROVAL, POLICY_CHECKPOINT -> AgentInterruptKind.APPROVAL;
            case EXTERNAL_EXECUTION -> AgentInterruptKind.PERMISSION;
        };
    }

    private static UUID deterministicEventId(TaskExecutionEvent event) {
        return UUID.nameUUIDFromBytes(idempotencyKey(event).getBytes(StandardCharsets.UTF_8));
    }

    private static String idempotencyKey(TaskExecutionEvent event) {
        return "agent-run:%s:%d:%d".formatted(
                event.agentRunId(), event.segmentSequence(), event.sequence());
    }

    private static DomainValidationException invalid(String message) {
        return new DomainValidationException("taskRuntimeEvent", message);
    }

    private record AppliedEvent(
            AgentRun run, boolean runChanged, Optional<AgentInterruptId> interruptId) {
        private AppliedEvent {
            run = Objects.requireNonNull(run, "run");
            interruptId = Objects.requireNonNull(interruptId, "interruptId");
        }

        private static AppliedEvent unchanged(AgentRun run) {
            return new AppliedEvent(run, false, Optional.empty());
        }

        private static AppliedEvent changed(AgentRun run) {
            return new AppliedEvent(run, true, Optional.empty());
        }
    }
}
