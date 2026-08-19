package io.crewscope.agentscope.coding;

import io.crewscope.application.coding.CodingCheckpointRepository;
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
import io.crewscope.application.task.AgentStateSnapshotRepository;
import io.crewscope.application.task.StepExecutionRepository;
import io.crewscope.application.transaction.AuthoritativeTimeProvider;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.coding.CodingCheckpoint;
import io.crewscope.domain.coding.CodingCheckpointId;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.task.AgentStateSnapshot;
import io.crewscope.domain.task.StepExecution;
import io.crewscope.domain.task.StepExecutionStatus;
import io.crewscope.domain.task.StepWaitReason;
import io.crewscope.domain.task.TaskExecutionFailure;
import io.crewscope.domain.task.TaskExecutionFailureClass;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Production event-first persistence bridge from Coding safe points to M3 durable execution. */
public final class DurableCodingSpecialistExecutionStore
        implements CodingSpecialistExecutionStore {

    private final DurableTaskExecutionEventService eventService;
    private final TaskAgentStateSnapshotService snapshotService;
    private final AgentStateSnapshotRepository snapshotRepository;
    private final CodingCheckpointRepository checkpointRepository;
    private final StepExecutionRepository stepRepository;
    private final TransactionExecutor transactionExecutor;
    private final AuthoritativeTimeProvider timeProvider;

    public DurableCodingSpecialistExecutionStore(
            DurableTaskExecutionEventService eventService,
            TaskAgentStateSnapshotService snapshotService,
            AgentStateSnapshotRepository snapshotRepository,
            CodingCheckpointRepository checkpointRepository,
            StepExecutionRepository stepRepository,
            TransactionExecutor transactionExecutor,
            AuthoritativeTimeProvider timeProvider) {
        this.eventService = Objects.requireNonNull(eventService, "eventService");
        this.snapshotService = Objects.requireNonNull(snapshotService, "snapshotService");
        this.snapshotRepository = Objects.requireNonNull(snapshotRepository, "snapshotRepository");
        this.checkpointRepository = Objects.requireNonNull(
                checkpointRepository, "checkpointRepository");
        this.stepRepository = Objects.requireNonNull(stepRepository, "stepRepository");
        this.transactionExecutor = Objects.requireNonNull(
                transactionExecutor, "transactionExecutor");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
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
        CodingCheckpoint committed = transactionExecutor.required(() -> {
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
    public void succeed(
            TaskExecutionRuntimeFacts facts,
            long eventSequence,
            Principal executor,
            UUID correlationId) {
        commit(
                facts,
                eventSequence,
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
