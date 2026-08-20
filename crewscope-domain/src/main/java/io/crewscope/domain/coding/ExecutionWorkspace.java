package io.crewscope.domain.coding;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.InvalidStateTransitionException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.ExecutionLease;
import io.crewscope.domain.task.ExecutionLeasePhase;
import io.crewscope.domain.task.TaskExecution;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskExecutionStatus;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.workitem.WorkItemScope;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

/**
 * Durable logical identity and lifecycle of one managed Coding Worktree.
 *
 * <p>Host paths and Git filesystem details remain inside the trusted M4-I03 adapter. This
 * aggregate closes the path-independent coordinates that those resources must prove.
 */
public final class ExecutionWorkspace {

    private final ExecutionWorkspaceId id;
    private final WorkItemScope scope;
    private final TaskId taskId;
    private final TaskExecutionId taskExecutionId;
    private final int attempt;
    private final CodingTargetSnapshotReference codingTarget;
    private final RepositoryBindingId repositoryBindingId;
    private final long repositoryBindingVersion;
    private final RepositoryKey repositoryKey;
    private final RepositoryCommitId baselineCommit;
    private final ExecutionWorkspaceKey workspaceKey;
    private final ManagedWorkspaceBranch managedBranch;
    private final WorkspaceArchiveReference archiveReference;
    private final ExecutionWorkspaceOwnership ownership;
    private final ExecutionWorkspaceStatus status;
    private final Optional<ExecutionWorkspaceStatus> recoveryTargetStatus;
    private final long recoveryGeneration;
    private final Optional<ExecutionWorkspaceCompletionReason> completionReason;
    private final Optional<ExecutionWorkspaceFailure> failure;
    private final ExecutionWorkspaceRetention retention;
    private final ExecutionWorkspaceFingerprint fingerprint;
    private final long version;
    private final AuditMetadata audit;

    private ExecutionWorkspace(
            ExecutionWorkspaceId id,
            WorkItemScope scope,
            TaskId taskId,
            TaskExecutionId taskExecutionId,
            int attempt,
            CodingTargetSnapshotReference codingTarget,
            RepositoryBindingId repositoryBindingId,
            long repositoryBindingVersion,
            RepositoryKey repositoryKey,
            RepositoryCommitId baselineCommit,
            ExecutionWorkspaceKey workspaceKey,
            ManagedWorkspaceBranch managedBranch,
            WorkspaceArchiveReference archiveReference,
            ExecutionWorkspaceOwnership ownership,
            ExecutionWorkspaceStatus status,
            Optional<ExecutionWorkspaceStatus> recoveryTargetStatus,
            long recoveryGeneration,
            Optional<ExecutionWorkspaceCompletionReason> completionReason,
            Optional<ExecutionWorkspaceFailure> failure,
            ExecutionWorkspaceRetention retention,
            Optional<ExecutionWorkspaceFingerprint> expectedFingerprint,
            long version,
            AuditMetadata audit) {
        this.id = Objects.requireNonNull(id, "id");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.taskId = Objects.requireNonNull(taskId, "taskId");
        this.taskExecutionId = Objects.requireNonNull(taskExecutionId, "taskExecutionId");
        this.attempt = requireAttempt(attempt);
        this.codingTarget = Objects.requireNonNull(codingTarget, "codingTarget");
        this.repositoryBindingId = Objects.requireNonNull(
                repositoryBindingId, "repositoryBindingId");
        this.repositoryBindingVersion = requireBindingVersion(repositoryBindingVersion);
        this.repositoryKey = Objects.requireNonNull(repositoryKey, "repositoryKey");
        this.baselineCommit = Objects.requireNonNull(baselineCommit, "baselineCommit");
        this.workspaceKey = requireWorkspaceKey(id, this.attempt, workspaceKey);
        this.managedBranch = requireManagedBranch(
                taskExecutionId, this.attempt, managedBranch);
        this.archiveReference = requireArchiveReference(this.workspaceKey, archiveReference);
        this.ownership = Objects.requireNonNull(ownership, "ownership");
        this.status = Objects.requireNonNull(status, "status");
        this.recoveryTargetStatus = requireRecoveryTarget(
                this.status, recoveryTargetStatus, recoveryGeneration);
        this.recoveryGeneration = requireRecoveryGeneration(recoveryGeneration);
        this.completionReason = requireCompletionReason(
                this.status, this.recoveryTargetStatus, completionReason);
        this.failure = requireFailure(this.status, failure, this.completionReason);
        this.retention = Objects.requireNonNull(retention, "retention");
        this.version = requireVersion(version);
        this.audit = Objects.requireNonNull(audit, "audit");
        this.retention.validateAfter(this.audit.createdAt());
        ExecutionWorkspaceFingerprint calculated = calculateFingerprint();
        Objects.requireNonNull(expectedFingerprint, "expectedFingerprint").ifPresent(expected -> {
            if (!expected.equals(calculated)) {
                throw new DomainValidationException(
                        "executionWorkspace.fingerprint",
                        "must match the canonical logical Workspace facts");
            }
        });
        this.fingerprint = calculated;
    }

    /** Allocates a stable logical Workspace for one currently owned PREPARING attempt. */
    public static ExecutionWorkspace allocate(
            ExecutionWorkspaceId id,
            CodingTargetSnapshot codingTarget,
            TaskExecution preparingExecution,
            ExecutionLease prepareLease,
            ExecutionWorkspaceRetention retention,
            Principal actor,
            UtcTimestamp occurredAt) {
        CodingTargetSnapshot target = Objects.requireNonNull(codingTarget, "codingTarget");
        TaskExecution execution = Objects.requireNonNull(
                preparingExecution, "preparingExecution");
        if (!target.scope().equals(execution.scope())
                || !target.taskId().equals(execution.taskId())) {
            throw new DomainValidationException(
                    "executionWorkspace.codingTargetSnapshotId",
                    "must belong to the same Task and complete WorkProject scope");
        }
        requireExecutionStatus(execution, TaskExecutionStatus.PREPARING);
        ExecutionLease lease = requireActiveLease(
                execution, prepareLease, ExecutionLeasePhase.PREPARE, occurredAt);
        PrincipalId actorId = CodingTargetActorPolicy.requireActiveInScope(
                actor, execution.scope(), "executionWorkspace.createdByPrincipalId");
        UtcTimestamp createdAt = Objects.requireNonNull(occurredAt, "occurredAt");
        ExecutionWorkspaceKey workspaceKey = ExecutionWorkspaceKey.derive(id, execution.attempt());
        return new ExecutionWorkspace(
                id,
                execution.scope(),
                execution.taskId(),
                execution.id(),
                execution.attempt(),
                target.reference(),
                target.repositoryBindingId(),
                target.repositoryBindingVersion(),
                target.repositoryKey(),
                target.baselineCommit(),
                workspaceKey,
                ManagedWorkspaceBranch.derive(execution.id(), execution.attempt()),
                WorkspaceArchiveReference.derive(workspaceKey),
                ExecutionWorkspaceOwnership.from(lease),
                ExecutionWorkspaceStatus.PENDING,
                Optional.empty(),
                0,
                Optional.empty(),
                Optional.empty(),
                retention,
                Optional.empty(),
                0,
                AuditMetadata.createdBy(actorId, createdAt));
    }

    /** Reconstitutes persisted facts while verifying all state shape and fingerprint invariants. */
    public static ExecutionWorkspace reconstitute(
            ExecutionWorkspaceId id,
            WorkItemScope scope,
            TaskId taskId,
            TaskExecutionId taskExecutionId,
            int attempt,
            CodingTargetSnapshotReference codingTarget,
            RepositoryBindingId repositoryBindingId,
            long repositoryBindingVersion,
            RepositoryKey repositoryKey,
            RepositoryCommitId baselineCommit,
            ExecutionWorkspaceKey workspaceKey,
            ManagedWorkspaceBranch managedBranch,
            WorkspaceArchiveReference archiveReference,
            ExecutionWorkspaceOwnership ownership,
            ExecutionWorkspaceStatus status,
            Optional<ExecutionWorkspaceStatus> recoveryTargetStatus,
            long recoveryGeneration,
            Optional<ExecutionWorkspaceCompletionReason> completionReason,
            Optional<ExecutionWorkspaceFailure> failure,
            ExecutionWorkspaceRetention retention,
            ExecutionWorkspaceFingerprint fingerprint,
            long version,
            AuditMetadata audit) {
        return new ExecutionWorkspace(
                id,
                scope,
                taskId,
                taskExecutionId,
                attempt,
                codingTarget,
                repositoryBindingId,
                repositoryBindingVersion,
                repositoryKey,
                baselineCommit,
                workspaceKey,
                managedBranch,
                archiveReference,
                ownership,
                status,
                recoveryTargetStatus,
                recoveryGeneration,
                completionReason,
                failure,
                retention,
                Optional.of(Objects.requireNonNull(fingerprint, "fingerprint")),
                version,
                audit);
    }

    public ExecutionWorkspace beginProvisioning(
            TaskExecution execution,
            ExecutionLease lease,
            long expectedVersion,
            Principal actor,
            UtcTimestamp occurredAt) {
        requireExpectedVersion(expectedVersion);
        requireStatus(ExecutionWorkspaceStatus.PENDING);
        requireCurrentLease(execution, lease, ExecutionLeasePhase.PREPARE, occurredAt);
        return transition(
                ExecutionWorkspaceStatus.PROVISIONING,
                Optional.empty(),
                completionReason,
                failure,
                ownership,
                recoveryGeneration,
                actor,
                occurredAt);
    }

    /** Marks the Worktree provisioned and fingerprint-verified but not yet execution-active. */
    public ExecutionWorkspace markReady(
            TaskExecution execution,
            ExecutionLease lease,
            long expectedVersion,
            Principal actor,
            UtcTimestamp occurredAt) {
        requireExpectedVersion(expectedVersion);
        requireStatus(ExecutionWorkspaceStatus.PROVISIONING);
        requireCurrentLease(execution, lease, ExecutionLeasePhase.PREPARE, occurredAt);
        return transition(
                ExecutionWorkspaceStatus.READY,
                Optional.empty(),
                completionReason,
                failure,
                ownership,
                recoveryGeneration,
                actor,
                occurredAt);
    }

    /** Activates a READY Worktree only after the attempt and Lease have entered their run phase. */
    public ExecutionWorkspace activate(
            TaskExecution runningExecution,
            ExecutionLease runLease,
            long expectedVersion,
            Principal actor,
            UtcTimestamp occurredAt) {
        requireExpectedVersion(expectedVersion);
        requireStatus(ExecutionWorkspaceStatus.READY);
        requireExecutionStatus(runningExecution, TaskExecutionStatus.RUNNING);
        requireCurrentLease(runningExecution, runLease, ExecutionLeasePhase.RUN, occurredAt);
        return transition(
                ExecutionWorkspaceStatus.ACTIVE,
                Optional.empty(),
                completionReason,
                failure,
                ownership,
                recoveryGeneration,
                actor,
                occurredAt);
    }

    /** Pauses execution by returning to READY while retaining Worktree, branch and archive identity. */
    public ExecutionWorkspace preserveForPause(
            TaskExecution pausedExecution,
            long expectedVersion,
            Principal actor,
            UtcTimestamp occurredAt) {
        requireExecutionStatus(pausedExecution, TaskExecutionStatus.PAUSED);
        return preserveForSuspension(pausedExecution, expectedVersion, actor, occurredAt);
    }

    /** Preserves the Worktree for a durable wait that will resume under a newer fencing epoch. */
    public ExecutionWorkspace preserveForWait(
            TaskExecution waitingExecution,
            long expectedVersion,
            Principal actor,
            UtcTimestamp occurredAt) {
        requireExecutionStatus(waitingExecution, TaskExecutionStatus.WAITING);
        return preserveForSuspension(waitingExecution, expectedVersion, actor, occurredAt);
    }

    private ExecutionWorkspace preserveForSuspension(
            TaskExecution suspendedExecution,
            long expectedVersion,
            Principal actor,
            UtcTimestamp occurredAt) {
        requireExpectedVersion(expectedVersion);
        requireStatus(ExecutionWorkspaceStatus.ACTIVE);
        requireCurrentExecutionEpoch(suspendedExecution);
        return transition(
                ExecutionWorkspaceStatus.READY,
                Optional.empty(),
                completionReason,
                failure,
                ownership,
                recoveryGeneration,
                actor,
                occurredAt);
    }

    /** Rebinds a preserved paused Worktree to a strictly newer Lease and fencing epoch. */
    public ExecutionWorkspace rebindForResume(
            TaskExecution preparingExecution,
            ExecutionLease prepareLease,
            long expectedVersion,
            Principal actor,
            UtcTimestamp occurredAt) {
        requireExpectedVersion(expectedVersion);
        requireStatus(ExecutionWorkspaceStatus.READY);
        requireExecutionStatus(preparingExecution, TaskExecutionStatus.PREPARING);
        ExecutionLease lease = requireActiveLease(
                requireExecutionLineage(preparingExecution),
                prepareLease,
                ExecutionLeasePhase.PREPARE,
                occurredAt);
        ExecutionWorkspaceOwnership replacement = ExecutionWorkspaceOwnership.from(lease);
        requireNewerOwnership(replacement);
        return transition(
                ExecutionWorkspaceStatus.READY,
                Optional.empty(),
                completionReason,
                failure,
                replacement,
                recoveryGeneration,
                actor,
                occurredAt);
    }

    /** Starts delivery finalization; cancellation still preserves the final provable Diff. */
    public ExecutionWorkspace beginFinalizing(
            ExecutionWorkspaceCompletionReason reason,
            TaskExecution execution,
            long expectedVersion,
            Principal actor,
            UtcTimestamp occurredAt) {
        requireExpectedVersion(expectedVersion);
        if (status != ExecutionWorkspaceStatus.PENDING
                && status != ExecutionWorkspaceStatus.PROVISIONING
                && status != ExecutionWorkspaceStatus.READY
                && status != ExecutionWorkspaceStatus.ACTIVE
                && status != ExecutionWorkspaceStatus.RECOVERING) {
            throw new InvalidStateTransitionException(
                    "ExecutionWorkspace", id, status, ExecutionWorkspaceStatus.FINALIZING);
        }
        TaskExecution current = requireExecutionLineage(execution);
        ExecutionWorkspaceCompletionReason requiredReason = Objects.requireNonNull(reason, "reason");
        boolean compatible = switch (requiredReason) {
            case SUCCEEDED -> current.status() == TaskExecutionStatus.RUNNING
                    || current.status() == TaskExecutionStatus.PAUSE_REQUESTED
                    || current.status() == TaskExecutionStatus.COMPLETED;
            case CANCELLED -> current.status() == TaskExecutionStatus.CANCEL_REQUESTED
                    || current.status() == TaskExecutionStatus.CANCELLED;
        };
        if (!compatible) {
            throw new DomainValidationException(
                    "executionWorkspace.completionReason",
                    "must match the current TaskExecution completion path");
        }
        requireCurrentExecutionEpoch(current);
        return transition(
                ExecutionWorkspaceStatus.FINALIZING,
                Optional.empty(),
                Optional.of(requiredReason),
                Optional.empty(),
                ownership,
                recoveryGeneration,
                actor,
                occurredAt);
    }

    /** Completes finalization after the TaskExecution has committed the matching terminal fact. */
    public ExecutionWorkspace completeFinalizing(
            TaskExecution terminalExecution,
            long expectedVersion,
            Principal actor,
            UtcTimestamp occurredAt) {
        requireExpectedVersion(expectedVersion);
        requireStatus(ExecutionWorkspaceStatus.FINALIZING);
        TaskExecution execution = requireExecutionLineage(terminalExecution);
        ExecutionWorkspaceCompletionReason preparedReason = completionReason.orElseThrow();
        ExecutionWorkspaceCompletionReason terminalReason = switch (execution.status()) {
            case COMPLETED -> ExecutionWorkspaceCompletionReason.SUCCEEDED;
            case CANCELLED -> ExecutionWorkspaceCompletionReason.CANCELLED;
            default -> null;
        };
        if (terminalReason == null
                || (preparedReason == ExecutionWorkspaceCompletionReason.CANCELLED
                        && terminalReason != ExecutionWorkspaceCompletionReason.CANCELLED)) {
            throw new DomainValidationException(
                    "executionWorkspace.completionReason",
                    "must match the terminal TaskExecution status");
        }
        // A cancellation committed after successful Diff sealing remains authoritative. The
        // immutable delivery evidence is retained while the business completion reason converges.
        return transition(
                ExecutionWorkspaceStatus.COMPLETED,
                Optional.empty(),
                Optional.of(terminalReason),
                Optional.empty(),
                ownership,
                recoveryGeneration,
                actor,
                occurredAt);
    }

    /** Enters a durable recovery generation while remembering the interrupted lifecycle state. */
    public ExecutionWorkspace beginRecovery(
            TaskExecution recoveringExecution,
            long expectedVersion,
            Principal actor,
            UtcTimestamp occurredAt) {
        requireExpectedVersion(expectedVersion);
        if (status != ExecutionWorkspaceStatus.PROVISIONING
                && status != ExecutionWorkspaceStatus.READY
                && status != ExecutionWorkspaceStatus.ACTIVE
                && status != ExecutionWorkspaceStatus.FINALIZING) {
            throw new InvalidStateTransitionException(
                    "ExecutionWorkspace", id, status, ExecutionWorkspaceStatus.RECOVERING);
        }
        requireExecutionStatus(recoveringExecution, TaskExecutionStatus.RECOVERING);
        requireCurrentExecutionEpoch(recoveringExecution);
        if (recoveryGeneration == Long.MAX_VALUE) {
            throw new DomainValidationException(
                    "executionWorkspace.recoveryGeneration", "must not overflow");
        }
        return transition(
                ExecutionWorkspaceStatus.RECOVERING,
                Optional.of(status),
                completionReason,
                failure,
                ownership,
                recoveryGeneration + 1,
                actor,
                occurredAt);
    }

    /** Restores the interrupted state under a new active Lease and strictly newer fencing epoch. */
    public ExecutionWorkspace resumeRecovery(
            TaskExecution preparingExecution,
            ExecutionLease prepareLease,
            long expectedVersion,
            Principal actor,
            UtcTimestamp occurredAt) {
        requireExpectedVersion(expectedVersion);
        requireStatus(ExecutionWorkspaceStatus.RECOVERING);
        requireExecutionStatus(preparingExecution, TaskExecutionStatus.PREPARING);
        ExecutionLease lease = requireActiveLease(
                requireExecutionLineage(preparingExecution),
                prepareLease,
                ExecutionLeasePhase.PREPARE,
                occurredAt);
        ExecutionWorkspaceOwnership replacement = ExecutionWorkspaceOwnership.from(lease);
        requireNewerOwnership(replacement);
        return transition(
                recoveryTargetStatus.orElseThrow(),
                Optional.empty(),
                completionReason,
                failure,
                replacement,
                recoveryGeneration,
                actor,
                occurredAt);
    }

    /** Fails closed with a stable classification while preserving all resource coordinates. */
    public ExecutionWorkspace fail(
            ExecutionWorkspaceFailure workspaceFailure,
            long expectedVersion,
            Principal actor,
            UtcTimestamp occurredAt) {
        requireExpectedVersion(expectedVersion);
        if (status == ExecutionWorkspaceStatus.COMPLETED
                || status == ExecutionWorkspaceStatus.FAILED
                || status == ExecutionWorkspaceStatus.ARCHIVED) {
            throw new InvalidStateTransitionException(
                    "ExecutionWorkspace", id, status, ExecutionWorkspaceStatus.FAILED);
        }
        return transition(
                ExecutionWorkspaceStatus.FAILED,
                Optional.empty(),
                Optional.empty(),
                Optional.of(Objects.requireNonNull(workspaceFailure, "workspaceFailure")),
                ownership,
                recoveryGeneration,
                actor,
                occurredAt);
    }

    /** Archives retained local resources only after the immutable retention boundary is due. */
    public ExecutionWorkspace archive(
            UtcTimestamp authoritativeNow,
            long expectedVersion,
            Principal actor) {
        requireExpectedVersion(expectedVersion);
        if (!status.isRetentionTerminal()) {
            throw new InvalidStateTransitionException(
                    "ExecutionWorkspace", id, status, ExecutionWorkspaceStatus.ARCHIVED);
        }
        UtcTimestamp now = Objects.requireNonNull(authoritativeNow, "authoritativeNow");
        if (!retention.isDue(now)) {
            throw new DomainValidationException(
                    "executionWorkspace.retention.retainUntil",
                    "must be due before local Workspace resources are archived");
        }
        return transition(
                ExecutionWorkspaceStatus.ARCHIVED,
                Optional.empty(),
                completionReason,
                failure,
                ownership,
                recoveryGeneration,
                actor,
                now);
    }

    private ExecutionWorkspace transition(
            ExecutionWorkspaceStatus targetStatus,
            Optional<ExecutionWorkspaceStatus> targetRecoveryStatus,
            Optional<ExecutionWorkspaceCompletionReason> targetCompletionReason,
            Optional<ExecutionWorkspaceFailure> targetFailure,
            ExecutionWorkspaceOwnership targetOwnership,
            long targetRecoveryGeneration,
            Principal actor,
            UtcTimestamp occurredAt) {
        PrincipalId actorId = CodingTargetActorPolicy.requireActiveInScope(
                actor, scope, "executionWorkspace.updatedByPrincipalId");
        return new ExecutionWorkspace(
                id,
                scope,
                taskId,
                taskExecutionId,
                attempt,
                codingTarget,
                repositoryBindingId,
                repositoryBindingVersion,
                repositoryKey,
                baselineCommit,
                workspaceKey,
                managedBranch,
                archiveReference,
                targetOwnership,
                targetStatus,
                targetRecoveryStatus,
                targetRecoveryGeneration,
                targetCompletionReason,
                targetFailure,
                retention,
                Optional.empty(),
                version + 1,
                audit.modifiedBy(actorId, occurredAt));
    }

    private void requireCurrentLease(
            TaskExecution execution,
            ExecutionLease lease,
            ExecutionLeasePhase phase,
            UtcTimestamp authoritativeNow) {
        ExecutionWorkspaceOwnership current = ExecutionWorkspaceOwnership.from(
                requireActiveLease(
                        requireExecutionLineage(execution), lease, phase, authoritativeNow));
        if (!ownership.equals(current)) {
            throw new DomainValidationException(
                    "executionWorkspace.ownership",
                    "must match the current Runtime, Worker, Lease and fencing epoch");
        }
    }

    private void requireCurrentExecutionEpoch(TaskExecution execution) {
        TaskExecution current = requireExecutionLineage(execution);
        if (current.lastFencingToken().filter(ownership.fencingToken()::equals).isEmpty()) {
            throw new DomainValidationException(
                    "executionWorkspace.ownership.fencingToken",
                    "must match the TaskExecution committed ownership epoch");
        }
    }

    private TaskExecution requireExecutionLineage(TaskExecution execution) {
        TaskExecution required = Objects.requireNonNull(execution, "execution");
        if (!scope.equals(required.scope())
                || !taskId.equals(required.taskId())
                || !taskExecutionId.equals(required.id())
                || attempt != required.attempt()) {
            throw new DomainValidationException(
                    "executionWorkspace.taskExecutionId",
                    "must match Task, Scope, TaskExecution and attempt");
        }
        return required;
    }

    private void requireNewerOwnership(ExecutionWorkspaceOwnership replacement) {
        if (replacement.fencingToken().compareTo(ownership.fencingToken()) <= 0
                || replacement.equals(ownership)) {
            throw new DomainValidationException(
                    "executionWorkspace.ownership.fencingToken",
                    "replacement ownership must use a strictly newer fencing epoch");
        }
    }

    private void requireExpectedVersion(long expectedVersion) {
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion must not be negative");
        }
        if (version != expectedVersion) {
            throw new OptimisticLockConflictException(
                    "ExecutionWorkspace", id, expectedVersion, version);
        }
    }

    private void requireStatus(ExecutionWorkspaceStatus expected) {
        if (status != expected) {
            throw new InvalidStateTransitionException(
                    "ExecutionWorkspace", id, status, expected);
        }
    }

    private ExecutionWorkspaceFingerprint calculateFingerprint() {
        MessageDigest digest = sha256();
        update(digest, id.toString());
        update(digest, scope.organizationId().toString());
        update(digest, scope.teamId().toString());
        update(digest, scope.workspaceId().toString());
        update(digest, scope.projectId().toString());
        update(digest, taskId.toString());
        update(digest, taskExecutionId.toString());
        update(digest, Integer.toString(attempt));
        update(digest, codingTarget.snapshotId().toString());
        update(digest, Long.toString(codingTarget.revision()));
        update(digest, codingTarget.snapshotHash().toString());
        update(digest, repositoryBindingId.toString());
        update(digest, Long.toString(repositoryBindingVersion));
        update(digest, repositoryKey.value());
        update(digest, baselineCommit.value());
        update(digest, workspaceKey.value());
        update(digest, managedBranch.value());
        update(digest, archiveReference.value());
        update(digest, ownership.environment().value());
        update(digest, ownership.runtimeId().toString());
        update(digest, ownership.workerId().toString());
        update(digest, ownership.leaseId().toString());
        update(digest, Long.toString(ownership.fencingToken().value()));
        update(digest, Long.toString(recoveryGeneration));
        update(digest, retention.retainUntil().toString());
        return new ExecutionWorkspaceFingerprint(HexFormat.of().formatHex(digest.digest()));
    }

    private static ExecutionLease requireActiveLease(
            TaskExecution execution,
            ExecutionLease lease,
            ExecutionLeasePhase phase,
            UtcTimestamp authoritativeNow) {
        TaskExecution requiredExecution = Objects.requireNonNull(execution, "execution");
        ExecutionLease requiredLease = Objects.requireNonNull(lease, "lease");
        UtcTimestamp now = Objects.requireNonNull(authoritativeNow, "authoritativeNow");
        boolean matches = requiredLease.organizationId()
                        .equals(requiredExecution.scope().organizationId())
                && requiredLease.taskExecutionId().equals(requiredExecution.id())
                && requiredLease.attempt() == requiredExecution.attempt()
                && requiredLease.phase() == phase
                && requiredExecution.lastFencingToken()
                        .filter(requiredLease.fencingToken()::equals)
                        .isPresent();
        if (!matches || !requiredLease.isActiveAt(now)) {
            throw new DomainValidationException(
                    "executionWorkspace.ownership",
                    "must use the current active TaskExecution Lease and fencing epoch");
        }
        return requiredLease;
    }

    private static void requireExecutionStatus(
            TaskExecution execution, TaskExecutionStatus expected) {
        TaskExecution required = Objects.requireNonNull(execution, "execution");
        if (required.status() != expected) {
            throw new DomainValidationException(
                    "executionWorkspace.taskExecutionId",
                    "must reference a " + expected + " TaskExecution");
        }
    }

    private static int requireAttempt(int value) {
        if (value < 1 || value > TaskExecution.MAX_SUPPORTED_ATTEMPTS) {
            throw new DomainValidationException(
                    "executionWorkspace.attempt", "must be within the supported attempt range");
        }
        return value;
    }

    private static long requireBindingVersion(long value) {
        if (value < 0) {
            throw new DomainValidationException(
                    "executionWorkspace.repositoryBindingVersion", "must not be negative");
        }
        return value;
    }

    private static long requireRecoveryGeneration(long value) {
        if (value < 0) {
            throw new DomainValidationException(
                    "executionWorkspace.recoveryGeneration", "must not be negative");
        }
        return value;
    }

    private static long requireVersion(long value) {
        if (value < 0) {
            throw new DomainValidationException(
                    "executionWorkspace.version", "must not be negative");
        }
        return value;
    }

    private static ExecutionWorkspaceKey requireWorkspaceKey(
            ExecutionWorkspaceId id, int attempt, ExecutionWorkspaceKey value) {
        ExecutionWorkspaceKey required = Objects.requireNonNull(value, "workspaceKey");
        if (!required.equals(ExecutionWorkspaceKey.derive(id, attempt))) {
            throw new DomainValidationException(
                    "executionWorkspace.workspaceKey", "must derive from Workspace ID and attempt");
        }
        return required;
    }

    private static ManagedWorkspaceBranch requireManagedBranch(
            TaskExecutionId executionId, int attempt, ManagedWorkspaceBranch value) {
        ManagedWorkspaceBranch required = Objects.requireNonNull(value, "managedBranch");
        if (!required.equals(ManagedWorkspaceBranch.derive(executionId, attempt))) {
            throw new DomainValidationException(
                    "executionWorkspace.managedBranch",
                    "must derive from TaskExecution ID and attempt");
        }
        return required;
    }

    private static WorkspaceArchiveReference requireArchiveReference(
            ExecutionWorkspaceKey workspaceKey, WorkspaceArchiveReference value) {
        WorkspaceArchiveReference required = Objects.requireNonNull(value, "archiveReference");
        if (!required.equals(WorkspaceArchiveReference.derive(workspaceKey))) {
            throw new DomainValidationException(
                    "executionWorkspace.archiveReference", "must derive from Workspace key");
        }
        return required;
    }

    private static Optional<ExecutionWorkspaceStatus> requireRecoveryTarget(
            ExecutionWorkspaceStatus status,
            Optional<ExecutionWorkspaceStatus> value,
            long generation) {
        Optional<ExecutionWorkspaceStatus> required = Objects.requireNonNull(
                value, "recoveryTargetStatus");
        boolean validTarget = required.filter(target ->
                        target == ExecutionWorkspaceStatus.PROVISIONING
                                || target == ExecutionWorkspaceStatus.READY
                                || target == ExecutionWorkspaceStatus.ACTIVE
                                || target == ExecutionWorkspaceStatus.FINALIZING)
                .isPresent();
        if ((status == ExecutionWorkspaceStatus.RECOVERING) != validTarget
                || (status == ExecutionWorkspaceStatus.RECOVERING && generation < 1)) {
            throw new DomainValidationException(
                    "executionWorkspace.recoveryTargetStatus",
                    "must identify the interrupted recoverable state only while RECOVERING");
        }
        return required;
    }

    private static Optional<ExecutionWorkspaceCompletionReason> requireCompletionReason(
            ExecutionWorkspaceStatus status,
            Optional<ExecutionWorkspaceStatus> recoveryTarget,
            Optional<ExecutionWorkspaceCompletionReason> value) {
        Optional<ExecutionWorkspaceCompletionReason> required = Objects.requireNonNull(
                value, "completionReason");
        boolean requires = status == ExecutionWorkspaceStatus.FINALIZING
                || status == ExecutionWorkspaceStatus.COMPLETED
                || (status == ExecutionWorkspaceStatus.RECOVERING
                        && recoveryTarget.filter(ExecutionWorkspaceStatus.FINALIZING::equals)
                                .isPresent());
        if (status == ExecutionWorkspaceStatus.ARCHIVED) {
            return required;
        }
        if (requires != required.isPresent()) {
            throw new DomainValidationException(
                    "executionWorkspace.completionReason",
                    "must exist exactly while finalizing or retaining completed delivery");
        }
        return required;
    }

    private static Optional<ExecutionWorkspaceFailure> requireFailure(
            ExecutionWorkspaceStatus status,
            Optional<ExecutionWorkspaceFailure> value,
            Optional<ExecutionWorkspaceCompletionReason> completionReason) {
        Optional<ExecutionWorkspaceFailure> required = Objects.requireNonNull(value, "failure");
        if (status == ExecutionWorkspaceStatus.ARCHIVED) {
            if (required.isPresent() == completionReason.isPresent()) {
                throw new DomainValidationException(
                        "executionWorkspace.failure",
                        "archived Workspace must retain exactly one completion or failure fact");
            }
            return required;
        }
        if ((status == ExecutionWorkspaceStatus.FAILED) != required.isPresent()) {
            throw new DomainValidationException(
                    "executionWorkspace.failure", "must exist exactly while FAILED");
        }
        return required;
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public ExecutionWorkspaceId id() {
        return id;
    }

    public WorkItemScope scope() {
        return scope;
    }

    public TaskId taskId() {
        return taskId;
    }

    public TaskExecutionId taskExecutionId() {
        return taskExecutionId;
    }

    public int attempt() {
        return attempt;
    }

    public CodingTargetSnapshotReference codingTarget() {
        return codingTarget;
    }

    public RepositoryBindingId repositoryBindingId() {
        return repositoryBindingId;
    }

    public long repositoryBindingVersion() {
        return repositoryBindingVersion;
    }

    public RepositoryKey repositoryKey() {
        return repositoryKey;
    }

    public RepositoryCommitId baselineCommit() {
        return baselineCommit;
    }

    public ExecutionWorkspaceKey workspaceKey() {
        return workspaceKey;
    }

    public ManagedWorkspaceBranch managedBranch() {
        return managedBranch;
    }

    public WorkspaceArchiveReference archiveReference() {
        return archiveReference;
    }

    public ManagedWorktreeLocator worktreeLocator() {
        return new ManagedWorktreeLocator(repositoryKey, workspaceKey);
    }

    public ExecutionWorkspaceOwnership ownership() {
        return ownership;
    }

    public ExecutionWorkspaceStatus status() {
        return status;
    }

    public Optional<ExecutionWorkspaceStatus> recoveryTargetStatus() {
        return recoveryTargetStatus;
    }

    public long recoveryGeneration() {
        return recoveryGeneration;
    }

    public Optional<ExecutionWorkspaceCompletionReason> completionReason() {
        return completionReason;
    }

    public Optional<ExecutionWorkspaceFailure> failure() {
        return failure;
    }

    public ExecutionWorkspaceRetention retention() {
        return retention;
    }

    public ExecutionWorkspaceFingerprint fingerprint() {
        return fingerprint;
    }

    public long version() {
        return version;
    }

    public AuditMetadata audit() {
        return audit;
    }
}
