package io.crewscope.domain.task;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.InvalidStateTransitionException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workitem.WorkItem;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkItemScope;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Durable business execution request rooted in one WorkItem and its creation-time evidence. */
public final class Task {

    private static final Map<TaskStatus, Set<TaskStatus>> ALLOWED_STATUS_TRANSITIONS = Map.of(
            TaskStatus.CREATED,
            EnumSet.of(TaskStatus.ACTIVE, TaskStatus.CANCELLED),
            TaskStatus.ACTIVE,
            EnumSet.of(
                    TaskStatus.WAITING,
                    TaskStatus.COMPLETED,
                    TaskStatus.FAILED,
                    TaskStatus.CANCELLED),
            TaskStatus.WAITING,
            EnumSet.of(
                    TaskStatus.ACTIVE,
                    TaskStatus.COMPLETED,
                    TaskStatus.FAILED,
                    TaskStatus.CANCELLED),
            TaskStatus.COMPLETED,
            EnumSet.noneOf(TaskStatus.class),
            TaskStatus.FAILED,
            EnumSet.noneOf(TaskStatus.class),
            TaskStatus.CANCELLED,
            EnumSet.noneOf(TaskStatus.class));

    private final TaskId id;
    private final WorkItemScope scope;
    private final WorkItemId workItemId;
    private final TaskSource source;
    private final TaskBrief brief;
    private final TaskResponsibilitySnapshot responsibilitySnapshot;
    private final TaskStatus status;
    private final Optional<TaskExecutionId> currentExecutionId;
    private final Optional<TaskCancellation> cancellation;
    private final long version;
    private final AuditMetadata audit;

    private Task(
            TaskId id,
            WorkItemScope scope,
            WorkItemId workItemId,
            TaskSource source,
            TaskBrief brief,
            TaskResponsibilitySnapshot responsibilitySnapshot,
            TaskStatus status,
            Optional<TaskExecutionId> currentExecutionId,
            Optional<TaskCancellation> cancellation,
            long version,
            AuditMetadata audit) {
        this.id = Objects.requireNonNull(id, "id");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.workItemId = Objects.requireNonNull(workItemId, "workItemId");
        this.source = Objects.requireNonNull(source, "source");
        this.source.validateFor(this.workItemId, this.scope);
        this.brief = Objects.requireNonNull(brief, "brief");
        this.responsibilitySnapshot = requireSnapshot(
                this.scope, this.workItemId, responsibilitySnapshot);
        this.status = Objects.requireNonNull(status, "status");
        this.currentExecutionId = Objects.requireNonNull(currentExecutionId, "currentExecutionId");
        this.cancellation = requireCancellation(this.status, cancellation);
        this.version = requireVersion(version);
        this.audit = Objects.requireNonNull(audit, "audit");
        requireExecutionShape(this.status, this.currentExecutionId);
    }

    /** Creates a Task from already validated source and current responsibility facts. */
    public static Task create(
            TaskId id,
            WorkItem workItem,
            TaskSource source,
            TaskResponsibilitySnapshot responsibilitySnapshot,
            Principal actor,
            UtcTimestamp occurredAt) {
        return create(
                id,
                workItem,
                source,
                TaskBrief.fromWorkItem(workItem),
                responsibilitySnapshot,
                actor,
                occurredAt);
    }

    /** Creates a Task while pinning the exact user-approved objective and acceptance criteria. */
    public static Task create(
            TaskId id,
            WorkItem workItem,
            TaskSource source,
            TaskBrief brief,
            TaskResponsibilitySnapshot responsibilitySnapshot,
            Principal actor,
            UtcTimestamp occurredAt) {
        WorkItem requiredWorkItem = Objects.requireNonNull(workItem, "workItem");
        if (!requiredWorkItem.acceptsCollaboration()) {
            throw new DomainValidationException(
                    "task.workItemId", "must reference a WorkItem that accepts execution");
        }
        TaskSource requiredSource = Objects.requireNonNull(source, "source");
        requiredSource.validateFor(requiredWorkItem.id(), requiredWorkItem.scope());
        if (requiredSource.workItemVersion() != requiredWorkItem.version()) {
            throw new DomainValidationException(
                    "task.source.workItemVersion",
                    "must match the source WorkItem version at Task creation");
        }
        TaskResponsibilitySnapshot requiredSnapshot = requireSnapshot(
                requiredWorkItem.scope(), requiredWorkItem.id(), responsibilitySnapshot);
        UtcTimestamp requiredTime = Objects.requireNonNull(occurredAt, "occurredAt");
        if (requiredSnapshot.capturedAt().compareTo(requiredTime) > 0) {
            throw new DomainValidationException(
                    "task.responsibilitySnapshot.capturedAt",
                    "must not be after Task creation time");
        }
        PrincipalId actorId = TaskActorPolicy.requireActiveInScope(
                actor, requiredWorkItem.scope(), "task.createdByPrincipalId");
        return new Task(
                id,
                requiredWorkItem.scope(),
                requiredWorkItem.id(),
                requiredSource,
                brief,
                requiredSnapshot,
                TaskStatus.CREATED,
                Optional.empty(),
                Optional.empty(),
                0,
                AuditMetadata.createdBy(actorId, requiredTime));
    }

    /** Reconstitutes a committed Task while revalidating its immutable fact shape. */
    public static Task reconstitute(
            TaskId id,
            WorkItemScope scope,
            WorkItemId workItemId,
            TaskSource source,
            TaskBrief brief,
            TaskResponsibilitySnapshot responsibilitySnapshot,
            TaskStatus status,
            Optional<TaskExecutionId> currentExecutionId,
            Optional<TaskCancellation> cancellation,
            long version,
            AuditMetadata audit) {
        return new Task(
                id,
                scope,
                workItemId,
                source,
                brief,
                responsibilitySnapshot,
                status,
                currentExecutionId,
                cancellation,
                version,
                audit);
    }

    /** Binds the first or a replacement attempt using both aggregate and old-reference checks. */
    public Task switchCurrentExecution(
            Optional<TaskExecutionId> expectedCurrentExecutionId,
            TaskExecutionId replacementExecutionId,
            long expectedVersion,
            Principal actor,
            UtcTimestamp occurredAt) {
        requireExpectedVersion(expectedVersion);
        if (isClosed()) {
            throw new InvalidStateTransitionException("Task", id, status, status);
        }
        Optional<TaskExecutionId> expected =
                Objects.requireNonNull(expectedCurrentExecutionId, "expectedCurrentExecutionId");
        if (!currentExecutionId.equals(expected)) {
            throw new DomainValidationException(
                    "task.currentExecutionId", "must match the current effective attempt");
        }
        TaskExecutionId replacement =
                Objects.requireNonNull(replacementExecutionId, "replacementExecutionId");
        if (currentExecutionId.filter(replacement::equals).isPresent()) {
            throw new DomainValidationException(
                    "task.currentExecutionId", "replacement must identify a new attempt");
        }
        PrincipalId actorId = TaskActorPolicy.requireActiveInScope(
                actor, scope, "task.updatedByPrincipalId");
        // A failed attempt remains immutable; explicitly selecting a new attempt reopens the Task.
        TaskStatus nextStatus = status == TaskStatus.CREATED || status == TaskStatus.FAILED
                ? TaskStatus.ACTIVE
                : status;
        return copy(
                nextStatus,
                Optional.of(replacement),
                Optional.empty(),
                version + 1,
                audit.modifiedBy(actorId, occurredAt));
    }

    /** Synchronizes the Task business lifecycle from the current effective execution attempt. */
    public Task synchronizeStatus(
            TaskExecutionId executionId,
            TaskStatus target,
            long expectedVersion,
            Principal actor,
            UtcTimestamp occurredAt) {
        requireExpectedVersion(expectedVersion);
        TaskExecutionId requiredExecution = Objects.requireNonNull(executionId, "executionId");
        if (currentExecutionId.filter(requiredExecution::equals).isEmpty()) {
            throw new DomainValidationException(
                    "task.currentExecutionId", "must identify the current effective attempt");
        }
        TaskStatus requiredTarget = Objects.requireNonNull(target, "target");
        if (!ALLOWED_STATUS_TRANSITIONS.get(status).contains(requiredTarget)
                || requiredTarget == TaskStatus.CANCELLED) {
            throw new InvalidStateTransitionException("Task", id, status, requiredTarget);
        }
        PrincipalId actorId = TaskActorPolicy.requireActiveInScope(
                actor, scope, "task.updatedByPrincipalId");
        return copy(
                requiredTarget,
                currentExecutionId,
                Optional.empty(),
                version + 1,
                audit.modifiedBy(actorId, occurredAt));
    }

    /** Cancels a non-terminal Task and preserves the responsible Principal and reason. */
    public Task cancel(
            String reason,
            long expectedVersion,
            Principal actor,
            UtcTimestamp occurredAt) {
        requireExpectedVersion(expectedVersion);
        if (!ALLOWED_STATUS_TRANSITIONS.get(status).contains(TaskStatus.CANCELLED)) {
            throw new InvalidStateTransitionException(
                    "Task", id, status, TaskStatus.CANCELLED);
        }
        PrincipalId actorId = TaskActorPolicy.requireActiveInScope(
                actor, scope, "task.cancelledByPrincipalId");
        UtcTimestamp requiredTime = Objects.requireNonNull(occurredAt, "occurredAt");
        return copy(
                TaskStatus.CANCELLED,
                currentExecutionId,
                Optional.of(new TaskCancellation(actorId, requiredTime, reason)),
                version + 1,
                audit.modifiedBy(actorId, requiredTime));
    }

    public boolean isClosed() {
        return status == TaskStatus.COMPLETED || status == TaskStatus.CANCELLED;
    }

    public TaskId id() {
        return id;
    }

    public WorkItemScope scope() {
        return scope;
    }

    public WorkItemId workItemId() {
        return workItemId;
    }

    public TaskSource source() {
        return source;
    }

    public TaskBrief brief() {
        return brief;
    }

    public TaskResponsibilitySnapshot responsibilitySnapshot() {
        return responsibilitySnapshot;
    }

    public TaskStatus status() {
        return status;
    }

    public Optional<TaskExecutionId> currentExecutionId() {
        return currentExecutionId;
    }

    public Optional<TaskCancellation> cancellation() {
        return cancellation;
    }

    public long version() {
        return version;
    }

    public AuditMetadata audit() {
        return audit;
    }

    private Task copy(
            TaskStatus targetStatus,
            Optional<TaskExecutionId> targetExecutionId,
            Optional<TaskCancellation> targetCancellation,
            long targetVersion,
            AuditMetadata targetAudit) {
        return new Task(
                id,
                scope,
                workItemId,
                source,
                brief,
                responsibilitySnapshot,
                targetStatus,
                targetExecutionId,
                targetCancellation,
                targetVersion,
                targetAudit);
    }

    private void requireExpectedVersion(long expectedVersion) {
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion must not be negative");
        }
        if (version != expectedVersion) {
            throw new OptimisticLockConflictException("Task", id, expectedVersion, version);
        }
    }

    private static TaskResponsibilitySnapshot requireSnapshot(
            WorkItemScope scope,
            WorkItemId workItemId,
            TaskResponsibilitySnapshot snapshot) {
        TaskResponsibilitySnapshot required =
                Objects.requireNonNull(snapshot, "responsibilitySnapshot");
        if (!required.scope().equals(scope) || !required.workItemId().equals(workItemId)) {
            throw new DomainValidationException(
                    "task.responsibilitySnapshot",
                    "must belong to the Task WorkItem and complete scope");
        }
        return required;
    }

    private static Optional<TaskCancellation> requireCancellation(
            TaskStatus status, Optional<TaskCancellation> cancellation) {
        Optional<TaskCancellation> required = Objects.requireNonNull(cancellation, "cancellation");
        if ((status == TaskStatus.CANCELLED) != required.isPresent()) {
            throw new DomainValidationException(
                    "task.cancellation",
                    status == TaskStatus.CANCELLED
                            ? "is required for a cancelled Task"
                            : "is only allowed for a cancelled Task");
        }
        return required;
    }

    private static void requireExecutionShape(
            TaskStatus status, Optional<TaskExecutionId> currentExecutionId) {
        if (status == TaskStatus.CREATED && currentExecutionId.isPresent()) {
            throw new DomainValidationException(
                    "task.currentExecutionId", "must be empty while the Task is CREATED");
        }
        if (status != TaskStatus.CREATED
                && status != TaskStatus.CANCELLED
                && currentExecutionId.isEmpty()) {
            throw new DomainValidationException(
                    "task.currentExecutionId", "is required after Task execution starts");
        }
    }

    private static long requireVersion(long value) {
        if (value < 0) {
            throw new DomainValidationException("task.version", "must not be negative");
        }
        return value;
    }
}
