package io.crewscope.domain.task;

import io.crewscope.domain.conversation.AgentRuntimeSessionId;
import io.crewscope.domain.conversation.AgentScopeSessionKey;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.InvalidStateTransitionException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workspace.AgentProfileId;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/** Trusted PostgreSQL checkpoint metadata for AgentState bytes held by ArtifactStore. */
public final class AgentStateSnapshot {

    public static final long MAX_SNAPSHOT_SIZE = 8L * 1024L * 1024L;
    public static final String CONTENT_TYPE =
            "application/vnd.crewscope.agent-state-snapshot+json";

    private static final Pattern AGENT_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]{0,99}");

    private final AgentStateSnapshotId id;
    private final WorkItemScope scope;
    private final TaskExecutionId executionId;
    private final AgentRunId agentRunId;
    private final AgentRuntimeSessionId runtimeSessionId;
    private final AgentProfileId agentProfileId;
    private final long agentProfileVersion;
    private final PrincipalId agentPrincipalId;
    private final String agentName;
    private final AgentScopeSessionKey agentScopeKey;
    private final long snapshotSequence;
    private final long checkpointSequence;
    private final RuntimeArtifactId runtimeArtifactId;
    private final RuntimeContentHash contentHash;
    private final long size;
    private final AgentStateSnapshotStatus status;
    private final Optional<String> invalidReasonCode;
    private final long version;
    private final AuditMetadata audit;

    private AgentStateSnapshot(
            AgentStateSnapshotId id,
            WorkItemScope scope,
            TaskExecutionId executionId,
            AgentRunId agentRunId,
            AgentRuntimeSessionId runtimeSessionId,
            AgentProfileId agentProfileId,
            long agentProfileVersion,
            PrincipalId agentPrincipalId,
            String agentName,
            AgentScopeSessionKey agentScopeKey,
            long snapshotSequence,
            long checkpointSequence,
            RuntimeArtifactId runtimeArtifactId,
            RuntimeContentHash contentHash,
            long size,
            AgentStateSnapshotStatus status,
            Optional<String> invalidReasonCode,
            long version,
            AuditMetadata audit) {
        this.id = Objects.requireNonNull(id, "id");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.executionId = Objects.requireNonNull(executionId, "executionId");
        this.agentRunId = Objects.requireNonNull(agentRunId, "agentRunId");
        this.runtimeSessionId = Objects.requireNonNull(runtimeSessionId, "runtimeSessionId");
        this.agentProfileId = Objects.requireNonNull(agentProfileId, "agentProfileId");
        if (agentProfileVersion < 0) {
            throw new DomainValidationException(
                    "agentStateSnapshot.agentProfileVersion", "must not be negative");
        }
        this.agentProfileVersion = agentProfileVersion;
        this.agentPrincipalId = Objects.requireNonNull(agentPrincipalId, "agentPrincipalId");
        this.agentName = requireAgentName(agentName);
        this.agentScopeKey = Objects.requireNonNull(agentScopeKey, "agentScopeKey");
        if (snapshotSequence < 1 || checkpointSequence < 1) {
            throw new DomainValidationException(
                    "agentStateSnapshot.sequence", "snapshot and checkpoint sequences must be positive");
        }
        this.snapshotSequence = snapshotSequence;
        this.checkpointSequence = checkpointSequence;
        this.runtimeArtifactId = Objects.requireNonNull(runtimeArtifactId, "runtimeArtifactId");
        this.contentHash = Objects.requireNonNull(contentHash, "contentHash");
        if (size < 1 || size > MAX_SNAPSHOT_SIZE) {
            throw new DomainValidationException(
                    "agentStateSnapshot.size", "must be between 1 byte and 8 MiB");
        }
        this.size = size;
        this.status = Objects.requireNonNull(status, "status");
        this.invalidReasonCode = Objects.requireNonNull(invalidReasonCode, "invalidReasonCode")
                .map(AgentStateSnapshot::requireReasonCode);
        if ((status == AgentStateSnapshotStatus.INVALID) != this.invalidReasonCode.isPresent()) {
            throw new DomainValidationException(
                    "agentStateSnapshot.invalidReasonCode", "must exist exactly for INVALID");
        }
        if (version < 0) {
            throw new DomainValidationException(
                    "agentStateSnapshot.version", "must not be negative");
        }
        this.version = version;
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    /** Records metadata only after the immutable Snapshot Artifact was published successfully. */
    public static AgentStateSnapshot capture(
            AgentStateSnapshotId id,
            TaskAgentRuntimeSession session,
            AgentRun run,
            RuntimeArtifact artifact,
            String agentName,
            long snapshotSequence,
            long checkpointSequence,
            Principal actor,
            UtcTimestamp occurredAt) {
        TaskAgentRuntimeSession requiredSession = Objects.requireNonNull(session, "session");
        AgentRun requiredRun = Objects.requireNonNull(run, "run");
        RuntimeArtifact requiredArtifact = Objects.requireNonNull(artifact, "artifact");
        if (!requiredSession.scope().equals(requiredRun.scope())
                || !requiredSession.executionId().equals(requiredRun.executionId())
                || !requiredSession.id().equals(requiredRun.runtimeSessionId())
                || !requiredSession.agentProfileId().equals(requiredRun.agentProfileId())
                || requiredSession.agentProfileVersion() != requiredRun.agentProfileVersion()
                || !requiredSession.agentPrincipalId().equals(requiredRun.agentPrincipalId())
                || !requiredArtifact.scope().equals(requiredRun.scope())
                || !requiredArtifact.executionId().equals(requiredRun.executionId())
                || !requiredArtifact.agentRunId().equals(requiredRun.id())
                || !requiredArtifact.stepExecutionId().equals(requiredRun.stepExecutionId())
                || requiredArtifact.kind() != RuntimeArtifactKind.AGENT_STATE_SNAPSHOT
                || !requiredArtifact.contentType().equals(CONTENT_TYPE)) {
            throw new DomainValidationException(
                    "agentStateSnapshot.identity",
                    "must close over one Session, AgentRun and Snapshot Artifact identity");
        }
        PrincipalId actorId = TaskActorPolicy.requireActiveInScope(
                actor, requiredRun.scope(), "agentStateSnapshot.createdBy");
        if (!actorId.equals(requiredRun.agentPrincipalId())) {
            throw new DomainValidationException(
                    "agentStateSnapshot.createdBy", "must be the AgentRun Principal");
        }
        return new AgentStateSnapshot(
                id,
                requiredRun.scope(),
                requiredRun.executionId(),
                requiredRun.id(),
                requiredSession.id(),
                requiredRun.agentProfileId(),
                requiredRun.agentProfileVersion(),
                requiredRun.agentPrincipalId(),
                agentName,
                requiredSession.agentScopeKey(),
                snapshotSequence,
                checkpointSequence,
                requiredArtifact.id(),
                requiredArtifact.contentHash(),
                requiredArtifact.size(),
                AgentStateSnapshotStatus.CURRENT,
                Optional.empty(),
                0,
                AuditMetadata.createdBy(actorId, occurredAt));
    }

    public static AgentStateSnapshot reconstitute(
            AgentStateSnapshotId id,
            WorkItemScope scope,
            TaskExecutionId executionId,
            AgentRunId agentRunId,
            AgentRuntimeSessionId runtimeSessionId,
            AgentProfileId agentProfileId,
            long agentProfileVersion,
            PrincipalId agentPrincipalId,
            String agentName,
            AgentScopeSessionKey agentScopeKey,
            long snapshotSequence,
            long checkpointSequence,
            RuntimeArtifactId runtimeArtifactId,
            RuntimeContentHash contentHash,
            long size,
            AgentStateSnapshotStatus status,
            Optional<String> invalidReasonCode,
            long version,
            AuditMetadata audit) {
        return new AgentStateSnapshot(
                id,
                scope,
                executionId,
                agentRunId,
                runtimeSessionId,
                agentProfileId,
                agentProfileVersion,
                agentPrincipalId,
                agentName,
                agentScopeKey,
                snapshotSequence,
                checkpointSequence,
                runtimeArtifactId,
                contentHash,
                size,
                status,
                invalidReasonCode,
                version,
                audit);
    }

    /** Retains this complete snapshot as a fallback after a newer snapshot becomes current. */
    public AgentStateSnapshot supersedeBy(
            AgentStateSnapshot newer,
            long expectedVersion,
            Principal actor,
            UtcTimestamp occurredAt) {
        requireExpectedVersion(expectedVersion);
        requireCurrent(AgentStateSnapshotStatus.SUPERSEDED);
        AgentStateSnapshot requiredNewer = Objects.requireNonNull(newer, "newer");
        if (requiredNewer.status != AgentStateSnapshotStatus.CURRENT
                || !requiredNewer.scope.equals(scope)
                || !requiredNewer.executionId.equals(executionId)
                || !requiredNewer.runtimeSessionId.equals(runtimeSessionId)
                || requiredNewer.snapshotSequence <= snapshotSequence
                || requiredNewer.checkpointSequence <= checkpointSequence) {
            throw new DomainValidationException(
                    "agentStateSnapshot.supersededBy",
                    "must be a newer current snapshot for the same execution Session");
        }
        PrincipalId actorId = TaskActorPolicy.requireActiveInScope(
                actor, scope, "agentStateSnapshot.updatedBy");
        return copy(
                AgentStateSnapshotStatus.SUPERSEDED,
                Optional.empty(),
                version + 1,
                audit.modifiedBy(actorId, occurredAt));
    }

    /** Removes a corrupt candidate from recovery selection while preserving its evidence. */
    public AgentStateSnapshot invalidate(
            String reasonCode,
            long expectedVersion,
            Principal actor,
            UtcTimestamp occurredAt) {
        requireExpectedVersion(expectedVersion);
        if (status == AgentStateSnapshotStatus.INVALID) {
            throw new InvalidStateTransitionException(
                    "AgentStateSnapshot", id, status, AgentStateSnapshotStatus.INVALID);
        }
        PrincipalId actorId = TaskActorPolicy.requireActiveInScope(
                actor, scope, "agentStateSnapshot.updatedBy");
        return copy(
                AgentStateSnapshotStatus.INVALID,
                Optional.of(requireReasonCode(reasonCode)),
                version + 1,
                audit.modifiedBy(actorId, occurredAt));
    }

    public AgentStateSnapshotId id() {
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

    public AgentRuntimeSessionId runtimeSessionId() {
        return runtimeSessionId;
    }

    public AgentProfileId agentProfileId() {
        return agentProfileId;
    }

    public long agentProfileVersion() {
        return agentProfileVersion;
    }

    public PrincipalId agentPrincipalId() {
        return agentPrincipalId;
    }

    public String agentName() {
        return agentName;
    }

    public AgentScopeSessionKey agentScopeKey() {
        return agentScopeKey;
    }

    public long snapshotSequence() {
        return snapshotSequence;
    }

    public long checkpointSequence() {
        return checkpointSequence;
    }

    public RuntimeArtifactId runtimeArtifactId() {
        return runtimeArtifactId;
    }

    public RuntimeContentHash contentHash() {
        return contentHash;
    }

    public long size() {
        return size;
    }

    public AgentStateSnapshotStatus status() {
        return status;
    }

    public Optional<String> invalidReasonCode() {
        return invalidReasonCode;
    }

    public long version() {
        return version;
    }

    public AuditMetadata audit() {
        return audit;
    }

    private AgentStateSnapshot copy(
            AgentStateSnapshotStatus targetStatus,
            Optional<String> targetInvalidReason,
            long targetVersion,
            AuditMetadata targetAudit) {
        return new AgentStateSnapshot(
                id,
                scope,
                executionId,
                agentRunId,
                runtimeSessionId,
                agentProfileId,
                agentProfileVersion,
                agentPrincipalId,
                agentName,
                agentScopeKey,
                snapshotSequence,
                checkpointSequence,
                runtimeArtifactId,
                contentHash,
                size,
                targetStatus,
                targetInvalidReason,
                targetVersion,
                targetAudit);
    }

    private void requireCurrent(AgentStateSnapshotStatus target) {
        if (status != AgentStateSnapshotStatus.CURRENT) {
            throw new InvalidStateTransitionException("AgentStateSnapshot", id, status, target);
        }
    }

    private void requireExpectedVersion(long expectedVersion) {
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion must not be negative");
        }
        if (version != expectedVersion) {
            throw new OptimisticLockConflictException(
                    "AgentStateSnapshot", id, expectedVersion, version);
        }
    }

    private static String requireAgentName(String value) {
        if (value == null || !AGENT_NAME.matcher(value.strip()).matches()) {
            throw new DomainValidationException(
                    "agentStateSnapshot.agentName", "must be a stable bounded Agent name");
        }
        return value.strip();
    }

    private static String requireReasonCode(String value) {
        if (value == null || !value.strip().matches("[A-Z][A-Z0-9_]{0,99}")) {
            throw new DomainValidationException(
                    "agentStateSnapshot.invalidReasonCode",
                    "must be a stable uppercase reason code");
        }
        return value.strip();
    }
}
