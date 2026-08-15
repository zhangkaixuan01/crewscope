package io.crewscope.application.execution;

import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.event.PendingOutboxEvent;
import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.application.task.AgentInterruptRepository;
import io.crewscope.application.task.AgentRunRepository;
import io.crewscope.application.task.TaskEventContext;
import io.crewscope.application.task.TaskEventRepository;
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
import io.crewscope.domain.task.AgentRun;
import io.crewscope.domain.task.RuntimeContentHash;
import io.crewscope.domain.task.event.AgentRunResumed;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Resolves one pending AgentInterrupt and opens the next RESUME Segment in one transaction. */
public final class DurableAgentRunResumeService {

    public static final EventType AGENT_RUN_RESUMED = EventType.from("AGENT_RUN_RESUMED");

    private final AgentRunRepository runRepository;
    private final AgentInterruptRepository interruptRepository;
    private final PrincipalRepository principalRepository;
    private final DomainEventStore eventStore;
    private final TaskEventRepository taskEventRepository;
    private final OutboxRepository outboxRepository;
    private final TransactionExecutor transactionExecutor;
    private final AuthoritativeTimeProvider timeProvider;

    public DurableAgentRunResumeService(
            AgentRunRepository runRepository,
            AgentInterruptRepository interruptRepository,
            PrincipalRepository principalRepository,
            DomainEventStore eventStore,
            TaskEventRepository taskEventRepository,
            OutboxRepository outboxRepository,
            TransactionExecutor transactionExecutor,
            AuthoritativeTimeProvider timeProvider) {
        this.runRepository = Objects.requireNonNull(runRepository, "runRepository");
        this.interruptRepository = Objects.requireNonNull(interruptRepository, "interruptRepository");
        this.principalRepository = Objects.requireNonNull(principalRepository, "principalRepository");
        this.eventStore = Objects.requireNonNull(eventStore, "eventStore");
        this.taskEventRepository = Objects.requireNonNull(
                taskEventRepository, "taskEventRepository");
        this.outboxRepository = Objects.requireNonNull(outboxRepository, "outboxRepository");
        this.transactionExecutor = Objects.requireNonNull(transactionExecutor, "transactionExecutor");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
    }

    public AgentRunResumeResult resume(AgentRunResumeCommand command) {
        AgentRunResumeCommand required = Objects.requireNonNull(command, "command");
        return transactionExecutor.required(() -> resumeInTransaction(required));
    }

    private AgentRunResumeResult resumeInTransaction(AgentRunResumeCommand command) {
        Optional<AgentInterrupt> existingRequest = interruptRepository.findByResumeRequestId(
                command.organizationId(), command.resumeRequestId());
        if (existingRequest.isPresent()) {
            AgentInterrupt interrupt = existingRequest.orElseThrow();
            requireCommandInterrupt(command, interrupt);
            requireInterruptToken(command, interrupt);
            interrupt.resolve(
                    command.resumeRequestId(),
                    command.responseHash(),
                    interrupt.version(),
                    loadActor(command),
                    timeProvider.now());
            AgentRun run = loadRun(command);
            if (run.currentSegment().resumedFromInterruptId()
                    .filter(interrupt.id()::equals).isEmpty()) {
                throw invalid("committed Resume request does not match the current Run Segment");
            }
            return new AgentRunResumeResult(AgentRunResumeStatus.DUPLICATE, run, interrupt);
        }

        AgentRun run = loadRun(command);
        AgentInterrupt interrupt = interruptRepository
                .findById(command.organizationId(), command.interruptId())
                .orElseThrow(() -> new AggregateNotFoundException(
                        "AgentInterrupt", command.interruptId()));
        requireCommandInterrupt(command, interrupt);
        requireInterruptToken(command, interrupt);
        Principal actor = loadActor(command);
        UtcTimestamp now = timeProvider.now();
        AgentInterrupt resolved = interruptRepository.update(interrupt.resolve(
                command.resumeRequestId(),
                command.responseHash(),
                interrupt.version(),
                actor,
                now));
        AgentRun resumed = runRepository.update(run.resume(
                resolved, run.version(), actor, now));
        AgentRunResumed payload = new AgentRunResumed(
                resumed.executionId().value(),
                resumed.id().value(),
                resolved.id().value(),
                resumed.currentSegment().sequence(),
                command.resumeRequestId());
        UUID eventId = UUID.nameUUIDFromBytes(
                ("agent-run-resume:" + command.resumeRequestId())
                        .getBytes(StandardCharsets.UTF_8));
        DomainEventEnvelope<AgentRunResumed> event = new DomainEventEnvelope<>(
                eventId,
                AGENT_RUN_RESUMED,
                SchemaVersion.V1,
                command.organizationId(),
                Optional.of(resumed.scope().teamId()),
                Optional.of(resumed.scope().workspaceId()),
                AggregateReference.of(DurableTaskExecutionEventService.AGENT_RUN_AGGREGATE, resumed.id()),
                resumed.version(),
                EventActor.principal(EventActorType.valueOf(actor.type().name()), actor.id()),
                command.correlationId(),
                command.causationId(),
                Optional.of("agent-run-resume:" + command.resumeRequestId()),
                now,
                payload);
        eventStore.append(event);
        taskEventRepository.append(
                TaskEventContext.agentRun(
                        resumed.taskId(),
                        resumed.executionId(),
                        resumed.stepExecutionId(),
                        resumed.id()),
                event);
        outboxRepository.enqueue(PendingOutboxEvent.fromDomain(UUID.randomUUID(), event));
        return new AgentRunResumeResult(AgentRunResumeStatus.RESUMED, resumed, resolved);
    }

    private AgentRun loadRun(AgentRunResumeCommand command) {
        return runRepository.findById(command.organizationId(), command.agentRunId())
                .orElseThrow(() -> new AggregateNotFoundException(
                        "AgentRun", command.agentRunId()));
    }

    private Principal loadActor(AgentRunResumeCommand command) {
        Principal actor = principalRepository
                .findById(command.organizationId(), command.actorId())
                .orElseThrow(() -> new AggregateNotFoundException("Principal", command.actorId()));
        if (!actor.canAct()) {
            throw invalid("Resume actor must be active");
        }
        return actor;
    }

    private static void requireCommandInterrupt(
            AgentRunResumeCommand command, AgentInterrupt interrupt) {
        if (!interrupt.id().equals(command.interruptId())
                || !interrupt.agentRunId().equals(command.agentRunId())
                || !interrupt.scope().organizationId().equals(command.organizationId())) {
            throw invalid("Resume request crossed the Organization, AgentRun or Interrupt boundary");
        }
    }

    private static void requireInterruptToken(
            AgentRunResumeCommand command, AgentInterrupt interrupt) {
        if (!interrupt.interruptTokenHash().equals(
                RuntimeContentHash.sha256(command.interruptToken().value()))) {
            throw invalid("Interrupt Token does not match the AgentInterrupt");
        }
    }

    private static DomainValidationException invalid(String message) {
        return new DomainValidationException("agentRunResume", message);
    }
}
