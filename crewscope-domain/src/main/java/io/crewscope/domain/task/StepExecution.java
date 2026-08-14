package io.crewscope.domain.task;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.InvalidStateTransitionException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workitem.WorkItemScope;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** One durable serial Step executed under the owning TaskExecution Lease. */
public final class StepExecution {

    private static final Map<StepExecutionStatus, Set<StepExecutionStatus>> TRANSITIONS = Map.of(
            StepExecutionStatus.PENDING,
            EnumSet.of(StepExecutionStatus.READY, StepExecutionStatus.SKIPPED, StepExecutionStatus.CANCELLED),
            StepExecutionStatus.READY,
            EnumSet.of(StepExecutionStatus.RUNNING, StepExecutionStatus.SKIPPED, StepExecutionStatus.CANCELLED),
            StepExecutionStatus.RUNNING,
            EnumSet.of(StepExecutionStatus.WAITING, StepExecutionStatus.SUCCEEDED,
                    StepExecutionStatus.FAILED_RETRYABLE, StepExecutionStatus.FAILED_FINAL,
                    StepExecutionStatus.CANCELLED),
            StepExecutionStatus.WAITING,
            EnumSet.of(StepExecutionStatus.READY, StepExecutionStatus.FAILED_FINAL,
                    StepExecutionStatus.CANCELLED),
            StepExecutionStatus.FAILED_RETRYABLE,
            EnumSet.of(StepExecutionStatus.READY, StepExecutionStatus.FAILED_FINAL,
                    StepExecutionStatus.CANCELLED),
            StepExecutionStatus.SUCCEEDED, EnumSet.noneOf(StepExecutionStatus.class),
            StepExecutionStatus.FAILED_FINAL, EnumSet.noneOf(StepExecutionStatus.class),
            StepExecutionStatus.SKIPPED, EnumSet.noneOf(StepExecutionStatus.class),
            StepExecutionStatus.CANCELLED, EnumSet.noneOf(StepExecutionStatus.class));

    private final StepExecutionId id;
    private final WorkItemScope scope;
    private final TaskId taskId;
    private final TaskExecutionId executionId;
    private final PlanVersionId planVersionId;
    private final TaskFactHash planVersionHash;
    private final String planStepKey;
    private final int sequence;
    private final boolean critical;
    private final ExecutionPrincipalSnapshot executionPrincipal;
    private final PolicySnapshotId policySnapshotId;
    private final TaskFactHash policySnapshotHash;
    private final SafetyEnforcementOverlayReference safetyOverlay;
    private final int runAttempt;
    private final int maxRunAttempts;
    private final StepExecutionStatus status;
    private final Optional<StepWaitReason> waitReason;
    private final Optional<StepCheckpoint> checkpoint;
    private final Optional<TaskExecutionFailure> failure;
    private final long version;
    private final AuditMetadata audit;

    private StepExecution(
            StepExecutionId id,
            WorkItemScope scope,
            TaskId taskId,
            TaskExecutionId executionId,
            PlanVersionId planVersionId,
            TaskFactHash planVersionHash,
            String planStepKey,
            int sequence,
            boolean critical,
            ExecutionPrincipalSnapshot executionPrincipal,
            PolicySnapshotId policySnapshotId,
            TaskFactHash policySnapshotHash,
            SafetyEnforcementOverlayReference safetyOverlay,
            int runAttempt,
            int maxRunAttempts,
            StepExecutionStatus status,
            Optional<StepWaitReason> waitReason,
            Optional<StepCheckpoint> checkpoint,
            Optional<TaskExecutionFailure> failure,
            long version,
            AuditMetadata audit) {
        this.id = Objects.requireNonNull(id, "id");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.taskId = Objects.requireNonNull(taskId, "taskId");
        this.executionId = Objects.requireNonNull(executionId, "executionId");
        this.planVersionId = Objects.requireNonNull(planVersionId, "planVersionId");
        this.planVersionHash = Objects.requireNonNull(planVersionHash, "planVersionHash");
        this.planStepKey = PlanStep.requireKey(planStepKey);
        if (sequence < 1) {
            throw new DomainValidationException("stepExecution.sequence", "must be positive");
        }
        this.sequence = sequence;
        this.critical = critical;
        this.executionPrincipal = Objects.requireNonNull(executionPrincipal, "executionPrincipal");
        this.policySnapshotId = Objects.requireNonNull(policySnapshotId, "policySnapshotId");
        this.policySnapshotHash = Objects.requireNonNull(policySnapshotHash, "policySnapshotHash");
        this.safetyOverlay = Objects.requireNonNull(safetyOverlay, "safetyOverlay");
        if (runAttempt < 1 || maxRunAttempts < 1 || runAttempt > maxRunAttempts || maxRunAttempts > 100) {
            throw new DomainValidationException(
                    "stepExecution.runAttempt", "must be positive and not exceed maxRunAttempts");
        }
        this.runAttempt = runAttempt;
        this.maxRunAttempts = maxRunAttempts;
        this.status = Objects.requireNonNull(status, "status");
        this.waitReason = requireWaitReason(status, waitReason);
        this.failure = requireFailure(status, failure);
        if (version < 0) {
            throw new DomainValidationException("stepExecution.version", "must not be negative");
        }
        this.version = version;
        this.audit = Objects.requireNonNull(audit, "audit");
        this.checkpoint = requireCheckpoint(checkpoint);
    }

    /** Creates one Step from a published Plan; no Step Lease is created or referenced. */
    public static StepExecution create(
            StepExecutionId id,
            Task task,
            TaskExecution execution,
            PlanVersion plan,
            PlanStep step,
            int maxRunAttempts,
            Principal actor,
            UtcTimestamp occurredAt) {
        Task requiredTask = Objects.requireNonNull(task, "task");
        TaskExecution requiredExecution = Objects.requireNonNull(execution, "execution");
        PlanVersion requiredPlan = Objects.requireNonNull(plan, "plan");
        PlanStep requiredStep = Objects.requireNonNull(step, "step");
        TaskExecutionPlanningContext context = requiredExecution.planningContext().orElseThrow(() ->
                new DomainValidationException("stepExecution.executionId", "must have planning context"));
        if (!requiredTask.scope().equals(requiredExecution.scope())
                || !requiredTask.id().equals(requiredExecution.taskId())
                || !requiredPlan.scope().equals(requiredTask.scope())
                || !requiredPlan.taskId().equals(requiredTask.id())
                || !requiredPlan.executionId().equals(requiredExecution.id())
                || context.currentPlanVersionId().filter(requiredPlan.id()::equals).isEmpty()
                || context.currentPlanVersionHash().filter(requiredPlan.versionHash()::equals).isEmpty()
                || requiredPlan.steps().stream().noneMatch(requiredStep::equals)) {
            throw new DomainValidationException(
                    "stepExecution.planVersionId", "must reference a Step in the current published Plan");
        }
        PrincipalId actorId = TaskActorPolicy.requireActiveInScope(
                actor, requiredTask.scope(), "stepExecution.createdByPrincipalId");
        return new StepExecution(
                id, requiredTask.scope(), requiredTask.id(), requiredExecution.id(),
                requiredPlan.id(), requiredPlan.versionHash(), requiredStep.key(),
                requiredStep.sequence(), requiredStep.critical(), requiredPlan.executionPrincipal(),
                requiredPlan.policySnapshotId(), requiredPlan.policySnapshotHash(),
                requiredPlan.safetyOverlay(), 1, maxRunAttempts, StepExecutionStatus.PENDING,
                Optional.empty(), Optional.empty(), Optional.empty(), 0,
                AuditMetadata.createdBy(actorId, occurredAt));
    }

    public static StepExecution reconstitute(
            StepExecutionId id, WorkItemScope scope, TaskId taskId, TaskExecutionId executionId,
            PlanVersionId planVersionId, TaskFactHash planVersionHash, String planStepKey,
            int sequence, boolean critical, ExecutionPrincipalSnapshot executionPrincipal,
            PolicySnapshotId policySnapshotId, TaskFactHash policySnapshotHash,
            SafetyEnforcementOverlayReference safetyOverlay, int runAttempt, int maxRunAttempts,
            StepExecutionStatus status, Optional<StepWaitReason> waitReason,
            Optional<StepCheckpoint> checkpoint, Optional<TaskExecutionFailure> failure,
            long version, AuditMetadata audit) {
        return new StepExecution(
                id, scope, taskId, executionId, planVersionId, planVersionHash, planStepKey,
                sequence, critical, executionPrincipal, policySnapshotId, policySnapshotHash,
                safetyOverlay, runAttempt, maxRunAttempts, status, waitReason, checkpoint,
                failure, version, audit);
    }

    public StepExecution markReady(long expectedVersion, Principal actor, UtcTimestamp occurredAt) {
        int nextAttempt = status == StepExecutionStatus.FAILED_RETRYABLE ? runAttempt + 1 : runAttempt;
        if (nextAttempt > maxRunAttempts) {
            throw new DomainValidationException(
                    "stepExecution.runAttempt", "retry budget has been exhausted");
        }
        return transition(StepExecutionStatus.READY, Optional.empty(), Optional.empty(),
                nextAttempt, expectedVersion, actor, occurredAt);
    }

    public StepExecution beginRunning(long expectedVersion, Principal executor, UtcTimestamp occurredAt) {
        requireExecutor(executor);
        return transition(StepExecutionStatus.RUNNING, Optional.empty(), Optional.empty(),
                runAttempt, expectedVersion, executor, occurredAt);
    }

    public StepExecution waitFor(
            StepWaitReason reason, long expectedVersion, Principal executor, UtcTimestamp occurredAt) {
        requireExecutor(executor);
        return transition(StepExecutionStatus.WAITING,
                Optional.of(Objects.requireNonNull(reason, "reason")), Optional.empty(),
                runAttempt, expectedVersion, executor, occurredAt);
    }

    public StepExecution recordCheckpoint(
            String code, TaskFactHash payloadHash, long expectedVersion,
            Principal executor, UtcTimestamp occurredAt) {
        requireExpectedVersion(expectedVersion);
        requireExecutor(executor);
        if (status != StepExecutionStatus.RUNNING && status != StepExecutionStatus.WAITING) {
            throw new InvalidStateTransitionException("StepExecution", id, status, status);
        }
        long nextSequence = checkpoint.map(StepCheckpoint::sequence).orElse(0L) + 1;
        StepCheckpoint next = new StepCheckpoint(
                nextSequence, code, payloadHash, executor.id(), occurredAt);
        return copy(status, waitReason, Optional.of(next), failure, runAttempt,
                version + 1, audit.modifiedBy(executor.id(), occurredAt));
    }

    public StepExecution succeed(long expectedVersion, Principal executor, UtcTimestamp occurredAt) {
        requireExecutor(executor);
        return transition(StepExecutionStatus.SUCCEEDED, Optional.empty(), Optional.empty(),
                runAttempt, expectedVersion, executor, occurredAt);
    }

    public StepExecution fail(
            TaskExecutionFailure failure, long expectedVersion,
            Principal executor, UtcTimestamp occurredAt) {
        requireExecutor(executor);
        TaskExecutionFailure required = Objects.requireNonNull(failure, "failure");
        StepExecutionStatus target = required.isRetryable() && runAttempt < maxRunAttempts
                ? StepExecutionStatus.FAILED_RETRYABLE
                : StepExecutionStatus.FAILED_FINAL;
        return transition(target, Optional.empty(), Optional.of(required), runAttempt,
                expectedVersion, executor, occurredAt);
    }

    public StepExecution skip(long expectedVersion, Principal actor, UtcTimestamp occurredAt) {
        if (critical) {
            throw new DomainValidationException("stepExecution.critical", "a critical Step cannot be skipped");
        }
        return transition(StepExecutionStatus.SKIPPED, Optional.empty(), Optional.empty(),
                runAttempt, expectedVersion, actor, occurredAt);
    }

    public StepExecution cancel(long expectedVersion, Principal actor, UtcTimestamp occurredAt) {
        return transition(StepExecutionStatus.CANCELLED, Optional.empty(), Optional.empty(),
                runAttempt, expectedVersion, actor, occurredAt);
    }

    private StepExecution transition(
            StepExecutionStatus target, Optional<StepWaitReason> targetWait,
            Optional<TaskExecutionFailure> targetFailure, int targetRunAttempt,
            long expectedVersion, Principal actor, UtcTimestamp occurredAt) {
        requireExpectedVersion(expectedVersion);
        if (!TRANSITIONS.get(status).contains(target)) {
            throw new InvalidStateTransitionException("StepExecution", id, status, target);
        }
        PrincipalId actorId = TaskActorPolicy.requireActiveInScope(
                actor, scope, "stepExecution.updatedByPrincipalId");
        return copy(target, targetWait, checkpoint, targetFailure, targetRunAttempt,
                version + 1, audit.modifiedBy(actorId, occurredAt));
    }

    private StepExecution copy(
            StepExecutionStatus targetStatus, Optional<StepWaitReason> targetWait,
            Optional<StepCheckpoint> targetCheckpoint, Optional<TaskExecutionFailure> targetFailure,
            int targetRunAttempt, long targetVersion, AuditMetadata targetAudit) {
        return new StepExecution(
                id, scope, taskId, executionId, planVersionId, planVersionHash, planStepKey,
                sequence, critical, executionPrincipal, policySnapshotId, policySnapshotHash,
                safetyOverlay, targetRunAttempt, maxRunAttempts, targetStatus, targetWait,
                targetCheckpoint, targetFailure, targetVersion, targetAudit);
    }

    private void requireExecutor(Principal principal) {
        Principal required = Objects.requireNonNull(principal, "executor");
        TaskActorPolicy.requireActiveInScope(required, scope, "stepExecution.executionPrincipalId");
        if (!executionPrincipal.principalId().equals(required.id())) {
            throw new DomainValidationException(
                    "stepExecution.executionPrincipalId", "must match the pinned Executor");
        }
    }

    private void requireExpectedVersion(long expectedVersion) {
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion must not be negative");
        }
        if (version != expectedVersion) {
            throw new OptimisticLockConflictException("StepExecution", id, expectedVersion, version);
        }
    }

    private static Optional<StepWaitReason> requireWaitReason(
            StepExecutionStatus status, Optional<StepWaitReason> waitReason) {
        Optional<StepWaitReason> required = Objects.requireNonNull(waitReason, "waitReason");
        if ((status == StepExecutionStatus.WAITING) != required.isPresent()) {
            throw new DomainValidationException(
                    "stepExecution.waitReason", "must be present exactly while WAITING");
        }
        return required;
    }

    private static Optional<TaskExecutionFailure> requireFailure(
            StepExecutionStatus status, Optional<TaskExecutionFailure> failure) {
        Optional<TaskExecutionFailure> required = Objects.requireNonNull(failure, "failure");
        boolean failed = status == StepExecutionStatus.FAILED_RETRYABLE
                || status == StepExecutionStatus.FAILED_FINAL;
        if (failed != required.isPresent()
                || (status == StepExecutionStatus.FAILED_RETRYABLE
                        && required.filter(TaskExecutionFailure::isRetryable).isEmpty())) {
            throw new DomainValidationException(
                    "stepExecution.failure", "must match the failed Step state");
        }
        return required;
    }

    private Optional<StepCheckpoint> requireCheckpoint(Optional<StepCheckpoint> checkpoint) {
        Optional<StepCheckpoint> required = Objects.requireNonNull(checkpoint, "checkpoint");
        if (required.filter(value ->
                        !value.recordedByPrincipalId().equals(executionPrincipal.principalId())
                                || value.recordedAt().compareTo(audit.createdAt()) < 0
                                || value.recordedAt().compareTo(audit.updatedAt()) > 0
                                || value.sequence() > version)
                .isPresent()) {
            throw new DomainValidationException(
                    "stepExecution.checkpoint",
                    "must be recorded by the pinned Executor within the Step audit lifetime");
        }
        return required;
    }

    public StepExecutionId id() { return id; }
    public WorkItemScope scope() { return scope; }
    public TaskId taskId() { return taskId; }
    public TaskExecutionId executionId() { return executionId; }
    public PlanVersionId planVersionId() { return planVersionId; }
    public TaskFactHash planVersionHash() { return planVersionHash; }
    public String planStepKey() { return planStepKey; }
    public int sequence() { return sequence; }
    public boolean critical() { return critical; }
    public ExecutionPrincipalSnapshot executionPrincipal() { return executionPrincipal; }
    public PolicySnapshotId policySnapshotId() { return policySnapshotId; }
    public TaskFactHash policySnapshotHash() { return policySnapshotHash; }
    public SafetyEnforcementOverlayReference safetyOverlay() { return safetyOverlay; }
    public int runAttempt() { return runAttempt; }
    public int maxRunAttempts() { return maxRunAttempts; }
    public StepExecutionStatus status() { return status; }
    public Optional<StepWaitReason> waitReason() { return waitReason; }
    public Optional<StepCheckpoint> checkpoint() { return checkpoint; }
    public Optional<TaskExecutionFailure> failure() { return failure; }
    public long version() { return version; }
    public AuditMetadata audit() { return audit; }
}
