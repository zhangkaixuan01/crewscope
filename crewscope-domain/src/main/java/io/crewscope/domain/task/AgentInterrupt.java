package io.crewscope.domain.task;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.InvalidStateTransitionException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workitem.WorkItemScope;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Durable pending Tool or control interruption for one exact AgentRun Segment. */
public final class AgentInterrupt {

    private final AgentInterruptId id;
    private final WorkItemScope scope;
    private final TaskExecutionId executionId;
    private final AgentRunId agentRunId;
    private final long segmentSequence;
    private final AgentInterruptKind kind;
    private final RuntimeContentHash interruptTokenHash;
    private final AgentInterruptStatus status;
    private final Optional<AgentInterruptResolution> resolution;
    private final long version;
    private final AuditMetadata audit;

    private AgentInterrupt(
            AgentInterruptId id,
            WorkItemScope scope,
            TaskExecutionId executionId,
            AgentRunId agentRunId,
            long segmentSequence,
            AgentInterruptKind kind,
            RuntimeContentHash interruptTokenHash,
            AgentInterruptStatus status,
            Optional<AgentInterruptResolution> resolution,
            long version,
            AuditMetadata audit) {
        this.id = Objects.requireNonNull(id, "id");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.executionId = Objects.requireNonNull(executionId, "executionId");
        this.agentRunId = Objects.requireNonNull(agentRunId, "agentRunId");
        if (segmentSequence < 1) {
            throw new DomainValidationException(
                    "agentInterrupt.segmentSequence", "must be positive");
        }
        this.segmentSequence = segmentSequence;
        this.kind = Objects.requireNonNull(kind, "kind");
        this.interruptTokenHash = Objects.requireNonNull(
                interruptTokenHash, "interruptTokenHash");
        this.status = Objects.requireNonNull(status, "status");
        this.resolution = Objects.requireNonNull(resolution, "resolution");
        if ((status == AgentInterruptStatus.RESOLVED) != this.resolution.isPresent()) {
            throw new DomainValidationException(
                    "agentInterrupt.resolution", "must exist exactly for RESOLVED");
        }
        if (version < 0) {
            throw new DomainValidationException("agentInterrupt.version", "must not be negative");
        }
        this.version = version;
        this.audit = Objects.requireNonNull(audit, "audit");
        this.resolution.ifPresent(value -> {
            if (value.resolvedAt().compareTo(this.audit.createdAt()) < 0) {
                throw new DomainValidationException(
                        "agentInterrupt.resolution.resolvedAt", "must not be before creation");
            }
        });
    }

    /** Opens the only pending interruption allowed for the current active Segment. */
    public static AgentInterrupt open(
            AgentInterruptId id,
            AgentRun run,
            AgentInterruptKind kind,
            RuntimeContentHash interruptTokenHash,
            Principal actor,
            UtcTimestamp occurredAt) {
        AgentRun requiredRun = Objects.requireNonNull(run, "run");
        if (requiredRun.status() != AgentRunStatus.RUNNING
                || requiredRun.currentSegment().status() != AgentRunSegmentStatus.ACTIVE) {
            throw new DomainValidationException(
                    "agentInterrupt.agentRunId", "must reference the current active Segment");
        }
        PrincipalId actorId = TaskActorPolicy.requireActiveInScope(
                actor, requiredRun.scope(), "agentInterrupt.createdBy");
        return new AgentInterrupt(
                id,
                requiredRun.scope(),
                requiredRun.executionId(),
                requiredRun.id(),
                requiredRun.currentSegment().sequence(),
                kind,
                interruptTokenHash,
                AgentInterruptStatus.PENDING,
                Optional.empty(),
                0,
                AuditMetadata.createdBy(actorId, occurredAt));
    }

    public static AgentInterrupt reconstitute(
            AgentInterruptId id,
            WorkItemScope scope,
            TaskExecutionId executionId,
            AgentRunId agentRunId,
            long segmentSequence,
            AgentInterruptKind kind,
            RuntimeContentHash interruptTokenHash,
            AgentInterruptStatus status,
            Optional<AgentInterruptResolution> resolution,
            long version,
            AuditMetadata audit) {
        return new AgentInterrupt(
                id,
                scope,
                executionId,
                agentRunId,
                segmentSequence,
                kind,
                interruptTokenHash,
                status,
                resolution,
                version,
                audit);
    }

    /**
     * Resolves once. An exact retry returns the committed value even with a stale expected version;
     * the same request ID carrying different answers fails closed.
     */
    public AgentInterrupt resolve(
            UUID resumeRequestId,
            RuntimeContentHash responseHash,
            long expectedVersion,
            Principal actor,
            UtcTimestamp occurredAt) {
        UUID requiredRequestId = Objects.requireNonNull(resumeRequestId, "resumeRequestId");
        RuntimeContentHash requiredHash = Objects.requireNonNull(responseHash, "responseHash");
        PrincipalId actorId = TaskActorPolicy.requireActiveInScope(
                actor, scope, "agentInterrupt.resolvedBy");
        if (status == AgentInterruptStatus.RESOLVED) {
            if (resolution.orElseThrow().matches(requiredRequestId, requiredHash)) {
                return this;
            }
            throw new DomainValidationException(
                    "agentInterrupt.resumeRequestId",
                    "must not be reused with different response content");
        }
        requireExpectedVersion(expectedVersion);
        requirePending(AgentInterruptStatus.RESOLVED);
        AgentInterruptResolution nextResolution = new AgentInterruptResolution(
                requiredRequestId, requiredHash, actorId, occurredAt);
        return copy(
                AgentInterruptStatus.RESOLVED,
                Optional.of(nextResolution),
                version + 1,
                audit.modifiedBy(actorId, occurredAt));
    }

    public AgentInterrupt cancel(
            long expectedVersion, Principal actor, UtcTimestamp occurredAt) {
        return terminate(AgentInterruptStatus.CANCELLED, expectedVersion, actor, occurredAt);
    }

    public AgentInterrupt expire(
            long expectedVersion, Principal actor, UtcTimestamp occurredAt) {
        return terminate(AgentInterruptStatus.EXPIRED, expectedVersion, actor, occurredAt);
    }

    public boolean matchesToken(RuntimeContentHash presentedTokenHash) {
        return interruptTokenHash.equals(Objects.requireNonNull(presentedTokenHash, "presentedTokenHash"));
    }

    public AgentInterruptId id() {
        return id;
    }

    public WorkItemScope scope() {
        return scope;
    }

    public TaskExecutionId executionId() {
        return executionId;
    }

    public AgentRunId agentRunId() {
        return agentRunId;
    }

    public long segmentSequence() {
        return segmentSequence;
    }

    public AgentInterruptKind kind() {
        return kind;
    }

    public RuntimeContentHash interruptTokenHash() {
        return interruptTokenHash;
    }

    public AgentInterruptStatus status() {
        return status;
    }

    public Optional<AgentInterruptResolution> resolution() {
        return resolution;
    }

    public long version() {
        return version;
    }

    public AuditMetadata audit() {
        return audit;
    }

    private AgentInterrupt terminate(
            AgentInterruptStatus target,
            long expectedVersion,
            Principal actor,
            UtcTimestamp occurredAt) {
        requireExpectedVersion(expectedVersion);
        requirePending(target);
        PrincipalId actorId = TaskActorPolicy.requireActiveInScope(
                actor, scope, "agentInterrupt.updatedBy");
        return copy(
                target,
                Optional.empty(),
                version + 1,
                audit.modifiedBy(actorId, occurredAt));
    }

    private AgentInterrupt copy(
            AgentInterruptStatus targetStatus,
            Optional<AgentInterruptResolution> targetResolution,
            long targetVersion,
            AuditMetadata targetAudit) {
        return new AgentInterrupt(
                id,
                scope,
                executionId,
                agentRunId,
                segmentSequence,
                kind,
                interruptTokenHash,
                targetStatus,
                targetResolution,
                targetVersion,
                targetAudit);
    }

    private void requirePending(AgentInterruptStatus target) {
        if (status != AgentInterruptStatus.PENDING) {
            throw new InvalidStateTransitionException("AgentInterrupt", id, status, target);
        }
    }

    private void requireExpectedVersion(long expectedVersion) {
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion must not be negative");
        }
        if (version != expectedVersion) {
            throw new OptimisticLockConflictException(
                    "AgentInterrupt", id, expectedVersion, version);
        }
    }
}
