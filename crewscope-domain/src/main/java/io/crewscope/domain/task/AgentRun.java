package io.crewscope.domain.task;

import io.crewscope.domain.conversation.AgentRuntimeSessionId;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.InvalidStateTransitionException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workspace.AgentProfileId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Durable Agent execution fact spanning initial, Resume and recovery event-stream Segments. */
public final class AgentRun {

    private final AgentRunId id;
    private final WorkItemScope scope;
    private final TaskId taskId;
    private final TaskExecutionId executionId;
    private final Optional<StepExecutionId> stepExecutionId;
    private final AgentRuntimeSessionId runtimeSessionId;
    private final PrincipalId agentPrincipalId;
    private final AgentProfileId agentProfileId;
    private final long agentProfileVersion;
    private final long runSequence;
    private final AgentRunStatus status;
    private final List<AgentRunSegment> segments;
    private final Optional<AgentRunContinuityGap> continuityGap;
    private final Optional<AgentRunTerminal> terminal;
    private final long version;
    private final AuditMetadata audit;

    private AgentRun(
            AgentRunId id,
            WorkItemScope scope,
            TaskId taskId,
            TaskExecutionId executionId,
            Optional<StepExecutionId> stepExecutionId,
            AgentRuntimeSessionId runtimeSessionId,
            PrincipalId agentPrincipalId,
            AgentProfileId agentProfileId,
            long agentProfileVersion,
            long runSequence,
            AgentRunStatus status,
            List<AgentRunSegment> segments,
            Optional<AgentRunContinuityGap> continuityGap,
            Optional<AgentRunTerminal> terminal,
            long version,
            AuditMetadata audit) {
        this.id = Objects.requireNonNull(id, "id");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.taskId = Objects.requireNonNull(taskId, "taskId");
        this.executionId = Objects.requireNonNull(executionId, "executionId");
        this.stepExecutionId = Objects.requireNonNull(stepExecutionId, "stepExecutionId");
        this.runtimeSessionId = Objects.requireNonNull(runtimeSessionId, "runtimeSessionId");
        this.agentPrincipalId = Objects.requireNonNull(agentPrincipalId, "agentPrincipalId");
        this.agentProfileId = Objects.requireNonNull(agentProfileId, "agentProfileId");
        if (agentProfileVersion < 0) {
            throw new DomainValidationException(
                    "agentRun.agentProfileVersion", "must not be negative");
        }
        this.agentProfileVersion = agentProfileVersion;
        if (runSequence < 1) {
            throw new DomainValidationException("agentRun.runSequence", "must be positive");
        }
        this.runSequence = runSequence;
        this.status = Objects.requireNonNull(status, "status");
        this.segments = validateSegments(segments, this.status);
        this.continuityGap = Objects.requireNonNull(continuityGap, "continuityGap");
        if (this.continuityGap.isPresent()
                != (this.segments.get(0).kind() == AgentRunSegmentKind.RECOVERY)) {
            throw new DomainValidationException(
                    "agentRun.continuityGap", "must exist exactly for a RECOVERY AgentRun");
        }
        this.terminal = Objects.requireNonNull(terminal, "terminal");
        if (status.isTerminal() != this.terminal.isPresent()
                || (this.terminal.isPresent()
                        && this.terminal.orElseThrow().status() != status)) {
            throw new DomainValidationException(
                    "agentRun.terminal", "must match the AgentRun terminal status");
        }
        if (version < 0) {
            throw new DomainValidationException("agentRun.version", "must not be negative");
        }
        this.version = version;
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    /** Starts the first logical AgentRun for a Session; repositories serialize runSequence. */
    public static AgentRun start(
            AgentRunId id,
            TaskAgentRuntimeSession session,
            long runSequence,
            Principal actor,
            UtcTimestamp occurredAt) {
        return startInternal(
                id,
                session,
                runSequence,
                AgentRunSegmentKind.INVOKE,
                Optional.empty(),
                actor,
                occurredAt);
    }

    /** Starts a replacement Run and records the exact checkpoint interval that could not continue. */
    public static AgentRun recover(
            AgentRunId id,
            TaskAgentRuntimeSession session,
            AgentRun previousRun,
            AgentRunContinuityGap continuityGap,
            long runSequence,
            Principal actor,
            UtcTimestamp occurredAt) {
        AgentRun previous = Objects.requireNonNull(previousRun, "previousRun");
        AgentRunContinuityGap gap = Objects.requireNonNull(continuityGap, "continuityGap");
        if (!gap.previousRunId().equals(previous.id)
                || !previous.status.isTerminal()
                || !previous.runtimeSessionId.equals(session.id())
                || !previous.executionId.equals(session.executionId())
                || !previous.stepExecutionId.equals(session.stepExecutionId())
                || runSequence != previous.runSequence + 1
                || gap.detectedAt().compareTo(previous.audit.createdAt()) < 0) {
            throw new DomainValidationException(
                    "agentRun.continuityGap", "must close over the immediately preceding Session Run");
        }
        return startInternal(
                id,
                session,
                runSequence,
                AgentRunSegmentKind.RECOVERY,
                Optional.of(gap),
                actor,
                occurredAt);
    }

    public static AgentRun reconstitute(
            AgentRunId id,
            WorkItemScope scope,
            TaskId taskId,
            TaskExecutionId executionId,
            Optional<StepExecutionId> stepExecutionId,
            AgentRuntimeSessionId runtimeSessionId,
            PrincipalId agentPrincipalId,
            AgentProfileId agentProfileId,
            long agentProfileVersion,
            long runSequence,
            AgentRunStatus status,
            List<AgentRunSegment> segments,
            Optional<AgentRunContinuityGap> continuityGap,
            Optional<AgentRunTerminal> terminal,
            long version,
            AuditMetadata audit) {
        return new AgentRun(
                id,
                scope,
                taskId,
                executionId,
                stepExecutionId,
                runtimeSessionId,
                agentPrincipalId,
                agentProfileId,
                agentProfileVersion,
                runSequence,
                status,
                segments,
                continuityGap,
                terminal,
                version,
                audit);
    }

    /** Commits a pending Interrupt and closes only the current finite Segment. */
    public AgentRun interrupt(
            AgentInterrupt interrupt,
            long expectedVersion,
            Principal actor,
            UtcTimestamp occurredAt) {
        requireExpectedVersion(expectedVersion);
        ensureStatus(AgentRunStatus.RUNNING, AgentRunStatus.INTERRUPTED);
        AgentInterrupt required = Objects.requireNonNull(interrupt, "interrupt");
        if (required.status() != AgentInterruptStatus.PENDING
                || !required.scope().equals(scope)
                || !required.executionId().equals(executionId)
                || !required.agentRunId().equals(id)
                || required.segmentSequence() != currentSegment().sequence()) {
            throw new DomainValidationException(
                    "agentRun.interruptId", "must be the pending Interrupt for the active Segment");
        }
        PrincipalId actorId = requireActor(actor);
        return copy(
                AgentRunStatus.INTERRUPTED,
                replaceCurrent(AgentRunSegmentStatus.INTERRUPTED, occurredAt),
                terminal,
                version + 1,
                audit.modifiedBy(actorId, occurredAt));
    }

    /**
     * Opens the next Segment after an accepted Resume. Repeating the same Resume after commit is a
     * no-op, while another Interrupt cannot resume this Run.
     */
    public AgentRun resume(
            AgentInterrupt interrupt,
            long expectedVersion,
            Principal actor,
            UtcTimestamp occurredAt) {
        AgentInterrupt required = Objects.requireNonNull(interrupt, "interrupt");
        PrincipalId actorId = requireActor(actor);
        if (status == AgentRunStatus.RUNNING
                && currentSegment().resumedFromInterruptId().filter(required.id()::equals).isPresent()) {
            requireResolvedInterrupt(required);
            return this;
        }
        requireExpectedVersion(expectedVersion);
        ensureStatus(AgentRunStatus.INTERRUPTED, AgentRunStatus.RUNNING);
        requireResolvedInterrupt(required);
        List<AgentRunSegment> next = new ArrayList<>(segments);
        next.add(AgentRunSegment.open(
                currentSegment().sequence() + 1,
                AgentRunSegmentKind.RESUME,
                Optional.of(required.id()),
                occurredAt));
        return copy(
                AgentRunStatus.RUNNING,
                next,
                Optional.empty(),
                version + 1,
                audit.modifiedBy(actorId, occurredAt));
    }

    /** Completes the Run with metadata-only reference to an optional large result. */
    public AgentRun complete(
            Optional<RuntimeArtifact> result,
            long expectedVersion,
            Principal actor,
            UtcTimestamp occurredAt) {
        Optional<RuntimeArtifactId> resultId = requireResult(result);
        return terminate(
                AgentRunStatus.COMPLETED,
                Optional.empty(),
                resultId,
                expectedVersion,
                actor,
                occurredAt);
    }

    /** Fails the Run with a stable reason and optional diagnostic Artifact reference. */
    public AgentRun fail(
            String failureCode,
            Optional<RuntimeArtifact> diagnostic,
            long expectedVersion,
            Principal actor,
            UtcTimestamp occurredAt) {
        Optional<RuntimeArtifactId> artifactId = requireResult(diagnostic);
        return terminate(
                AgentRunStatus.FAILED,
                Optional.of(Objects.requireNonNull(failureCode, "failureCode")),
                artifactId,
                expectedVersion,
                actor,
                occurredAt);
    }

    public AgentRun cancel(
            long expectedVersion, Principal actor, UtcTimestamp occurredAt) {
        return terminate(
                AgentRunStatus.CANCELLED,
                Optional.empty(),
                Optional.empty(),
                expectedVersion,
                actor,
                occurredAt);
    }

    public AgentRunId id() {
        return id;
    }

    public WorkItemScope scope() {
        return scope;
    }

    public TaskId taskId() {
        return taskId;
    }

    public TaskExecutionId executionId() {
        return executionId;
    }

    public Optional<StepExecutionId> stepExecutionId() {
        return stepExecutionId;
    }

    public AgentRuntimeSessionId runtimeSessionId() {
        return runtimeSessionId;
    }

    public PrincipalId agentPrincipalId() {
        return agentPrincipalId;
    }

    public AgentProfileId agentProfileId() {
        return agentProfileId;
    }

    public long agentProfileVersion() {
        return agentProfileVersion;
    }

    public long runSequence() {
        return runSequence;
    }

    public AgentRunStatus status() {
        return status;
    }

    public List<AgentRunSegment> segments() {
        return segments;
    }

    public AgentRunSegment currentSegment() {
        return segments.get(segments.size() - 1);
    }

    public Optional<AgentRunContinuityGap> continuityGap() {
        return continuityGap;
    }

    public Optional<AgentRunTerminal> terminal() {
        return terminal;
    }

    public long version() {
        return version;
    }

    public AuditMetadata audit() {
        return audit;
    }

    private static AgentRun startInternal(
            AgentRunId id,
            TaskAgentRuntimeSession session,
            long runSequence,
            AgentRunSegmentKind segmentKind,
            Optional<AgentRunContinuityGap> gap,
            Principal actor,
            UtcTimestamp occurredAt) {
        TaskAgentRuntimeSession requiredSession = Objects.requireNonNull(session, "session");
        if (!requiredSession.canInvoke()) {
            throw new DomainValidationException(
                    "agentRun.runtimeSessionId", "must reference an ACTIVE Task Agent session");
        }
        PrincipalId actorId = TaskActorPolicy.requireActiveInScope(
                actor, requiredSession.scope(), "agentRun.createdBy");
        if (!actorId.equals(requiredSession.agentPrincipalId())) {
            throw new DomainValidationException(
                    "agentRun.agentPrincipalId", "must be started by the bound Agent Principal");
        }
        UtcTimestamp time = Objects.requireNonNull(occurredAt, "occurredAt");
        return new AgentRun(
                id,
                requiredSession.scope(),
                requiredSession.taskId(),
                requiredSession.executionId(),
                requiredSession.stepExecutionId(),
                requiredSession.id(),
                requiredSession.agentPrincipalId(),
                requiredSession.agentProfileId(),
                requiredSession.agentProfileVersion(),
                runSequence,
                AgentRunStatus.RUNNING,
                List.of(AgentRunSegment.open(1, segmentKind, Optional.empty(), time)),
                gap,
                Optional.empty(),
                0,
                AuditMetadata.createdBy(actorId, time));
    }

    private AgentRun terminate(
            AgentRunStatus target,
            Optional<String> failureCode,
            Optional<RuntimeArtifactId> resultArtifactId,
            long expectedVersion,
            Principal actor,
            UtcTimestamp occurredAt) {
        requireExpectedVersion(expectedVersion);
        ensureStatus(AgentRunStatus.RUNNING, target);
        PrincipalId actorId = requireActor(actor);
        AgentRunSegmentStatus segmentTerminal = switch (target) {
            case COMPLETED -> AgentRunSegmentStatus.COMPLETED;
            case FAILED -> AgentRunSegmentStatus.FAILED;
            case CANCELLED -> AgentRunSegmentStatus.CANCELLED;
            default -> throw new IllegalArgumentException("target must be terminal");
        };
        AgentRunTerminal nextTerminal = new AgentRunTerminal(
                target, failureCode, resultArtifactId, occurredAt);
        return copy(
                target,
                replaceCurrent(segmentTerminal, occurredAt),
                Optional.of(nextTerminal),
                version + 1,
                audit.modifiedBy(actorId, occurredAt));
    }

    private Optional<RuntimeArtifactId> requireResult(Optional<RuntimeArtifact> artifact) {
        Optional<RuntimeArtifact> required = Objects.requireNonNull(artifact, "artifact");
        required.ifPresent(value -> {
            if (!value.scope().equals(scope)
                    || !value.executionId().equals(executionId)
                    || !value.agentRunId().equals(id)
                    || !value.stepExecutionId().equals(stepExecutionId)
                    || value.kind() == RuntimeArtifactKind.AGENT_STATE_SNAPSHOT) {
                throw new DomainValidationException(
                        "agentRun.resultArtifactId",
                        "must reference a non-Snapshot Artifact produced by this Run");
            }
        });
        return required.map(RuntimeArtifact::id);
    }

    private void requireResolvedInterrupt(AgentInterrupt interrupt) {
        long expectedSegmentSequence = status == AgentRunStatus.INTERRUPTED
                ? currentSegment().sequence()
                : currentSegment().sequence() - 1;
        if (interrupt.status() != AgentInterruptStatus.RESOLVED
                || !interrupt.scope().equals(scope)
                || !interrupt.executionId().equals(executionId)
                || !interrupt.agentRunId().equals(id)
                || interrupt.segmentSequence() != expectedSegmentSequence) {
            throw new DomainValidationException(
                    "agentRun.interruptId", "must be the resolved Interrupt for this Resume Segment");
        }
    }

    private List<AgentRunSegment> replaceCurrent(
            AgentRunSegmentStatus targetStatus, UtcTimestamp occurredAt) {
        List<AgentRunSegment> next = new ArrayList<>(segments);
        next.set(next.size() - 1, currentSegment().finish(targetStatus, occurredAt));
        return next;
    }

    private AgentRun copy(
            AgentRunStatus targetStatus,
            List<AgentRunSegment> targetSegments,
            Optional<AgentRunTerminal> targetTerminal,
            long targetVersion,
            AuditMetadata targetAudit) {
        return new AgentRun(
                id,
                scope,
                taskId,
                executionId,
                stepExecutionId,
                runtimeSessionId,
                agentPrincipalId,
                agentProfileId,
                agentProfileVersion,
                runSequence,
                targetStatus,
                targetSegments,
                continuityGap,
                targetTerminal,
                targetVersion,
                targetAudit);
    }

    private PrincipalId requireActor(Principal actor) {
        return TaskActorPolicy.requireActiveInScope(actor, scope, "agentRun.updatedBy");
    }

    private void requireExpectedVersion(long expectedVersion) {
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion must not be negative");
        }
        if (version != expectedVersion) {
            throw new OptimisticLockConflictException("AgentRun", id, expectedVersion, version);
        }
    }

    private void ensureStatus(AgentRunStatus required, AgentRunStatus target) {
        if (status != required) {
            throw new InvalidStateTransitionException("AgentRun", id, status, target);
        }
    }

    private static List<AgentRunSegment> validateSegments(
            List<AgentRunSegment> values, AgentRunStatus status) {
        List<AgentRunSegment> copy = List.copyOf(Objects.requireNonNull(values, "segments"));
        if (copy.isEmpty()) {
            throw new DomainValidationException("agentRun.segments", "must not be empty");
        }
        for (int index = 0; index < copy.size(); index++) {
            AgentRunSegment segment = copy.get(index);
            if (segment.sequence() != index + 1L
                    || (index < copy.size() - 1 && !segment.status().isTerminal())) {
                throw new DomainValidationException(
                        "agentRun.segments", "must use contiguous sequences and closed prior Segments");
            }
        }
        AgentRunSegmentStatus expectedLastStatus = switch (status) {
            case RUNNING -> AgentRunSegmentStatus.ACTIVE;
            case INTERRUPTED -> AgentRunSegmentStatus.INTERRUPTED;
            case COMPLETED -> AgentRunSegmentStatus.COMPLETED;
            case FAILED -> AgentRunSegmentStatus.FAILED;
            case CANCELLED -> AgentRunSegmentStatus.CANCELLED;
        };
        if (copy.get(copy.size() - 1).status() != expectedLastStatus) {
            throw new DomainValidationException(
                    "agentRun.segments", "last Segment status must match AgentRun status");
        }
        return copy;
    }
}
