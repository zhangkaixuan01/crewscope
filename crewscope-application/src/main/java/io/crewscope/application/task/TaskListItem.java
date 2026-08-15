package io.crewscope.application.task;

import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.task.TaskBrief;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskExecutionStatus;
import io.crewscope.domain.task.TaskExecutionWaitReason;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.task.TaskStatus;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkItemScope;
import java.util.Objects;
import java.util.Optional;

/** Bounded Task collection projection including only the current attempt's display state. */
public record TaskListItem(
        TaskId id,
        WorkItemScope scope,
        WorkItemId workItemId,
        TaskBrief brief,
        TaskStatus status,
        Optional<TaskExecutionId> currentExecutionId,
        Optional<Integer> currentAttempt,
        Optional<TaskExecutionStatus> currentExecutionStatus,
        Optional<TaskExecutionWaitReason> currentWaitingReason,
        long version,
        AuditMetadata audit) {

    public TaskListItem {
        id = Objects.requireNonNull(id, "id");
        scope = Objects.requireNonNull(scope, "scope");
        workItemId = Objects.requireNonNull(workItemId, "workItemId");
        brief = Objects.requireNonNull(brief, "brief");
        status = Objects.requireNonNull(status, "status");
        currentExecutionId = Objects.requireNonNull(currentExecutionId, "currentExecutionId");
        currentAttempt = Objects.requireNonNull(currentAttempt, "currentAttempt");
        currentExecutionStatus = Objects.requireNonNull(
                currentExecutionStatus, "currentExecutionStatus");
        currentWaitingReason = Objects.requireNonNull(
                currentWaitingReason, "currentWaitingReason");
        audit = Objects.requireNonNull(audit, "audit");
        boolean executionShape = currentExecutionId.isPresent() == currentAttempt.isPresent()
                && currentExecutionId.isPresent() == currentExecutionStatus.isPresent();
        if (!executionShape || (currentWaitingReason.isPresent()
                && currentExecutionStatus.filter(TaskExecutionStatus.WAITING::equals).isEmpty())) {
            throw new IllegalArgumentException("current TaskExecution summary is inconsistent");
        }
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
    }

    public TaskListCursor cursor() {
        return new TaskListCursor(audit.updatedAt(), id);
    }
}
