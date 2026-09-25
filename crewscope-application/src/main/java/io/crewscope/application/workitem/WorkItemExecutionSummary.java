package io.crewscope.application.workitem;

import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskExecutionStatus;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkItemStatus;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The execution/todo read model one WorkItem row carries wherever it is listed (M9b-A06, contract
 * §4.1).
 *
 * <p>Work status and execution facts are two dimensions: {@code workStatus} is the member-facing
 * WorkItem status, while the task/execution counters describe runtime progress underneath it. The
 * summary is assembled on read from live tables — no persistent "current Task" is written back into
 * the aggregate — so {@code projectionVersion} anchors the assembly to the WorkItem row version it
 * was derived from and {@code observedAt} to the assembling instant.
 *
 * <p>Current-attempt rule (frozen S01 D05): only a single-task WorkItem has a well-defined current
 * attempt, and it is reported only when that task has a current execution. A multi-task item sets
 * {@code selectionRequired} and reports no current execution; choosing among tasks is a selection,
 * not an ordering. Enforced invariants:
 *
 * <ul>
 *   <li>{@code selectionRequired == (taskCount > 1)}
 *   <li>the three current-attempt fields are present together or absent together
 *   <li>{@code taskCount == 0} and {@code taskCount > 1} both imply absent current fields
 *   <li>{@code currentTaskId} present implies {@code taskCount == 1} and a non-null execution
 * </ul>
 *
 * <p>{@code resultSummary} and {@code resultSourceReference} are part of the frozen shape for the
 * F03/A05 result surfaces; A06 always reports them absent.
 */
public record WorkItemExecutionSummary(
        WorkItemId workItemId,
        long workItemVersion,
        WorkItemStatus workStatus,
        int taskCount,
        int activeTaskCount,
        int pendingReviewCount,
        Optional<TaskId> currentTaskId,
        Optional<TaskExecutionId> currentExecutionId,
        Optional<TaskExecutionStatus> executionStatus,
        boolean selectionRequired,
        List<WorkItemBlockedReason> blockedReasons,
        Optional<String> resultSummary,
        Optional<String> resultSourceReference,
        long projectionVersion,
        UtcTimestamp observedAt) {

    public WorkItemExecutionSummary {
        workItemId = Objects.requireNonNull(workItemId, "workItemId");
        workStatus = Objects.requireNonNull(workStatus, "workStatus");
        currentTaskId = Objects.requireNonNull(currentTaskId, "currentTaskId");
        currentExecutionId = Objects.requireNonNull(currentExecutionId, "currentExecutionId");
        executionStatus = Objects.requireNonNull(executionStatus, "executionStatus");
        blockedReasons =
                List.copyOf(Objects.requireNonNull(blockedReasons, "blockedReasons"));
        resultSummary = Objects.requireNonNull(resultSummary, "resultSummary");
        resultSourceReference =
                Objects.requireNonNull(resultSourceReference, "resultSourceReference");
        observedAt = Objects.requireNonNull(observedAt, "observedAt");
        if (taskCount < 0 || activeTaskCount < 0 || pendingReviewCount < 0) {
            throw new IllegalArgumentException("summary counters must not be negative");
        }
        if (activeTaskCount > taskCount) {
            throw new IllegalArgumentException("activeTaskCount must not exceed taskCount");
        }
        if (selectionRequired != (taskCount > 1)) {
            throw new IllegalArgumentException(
                    "selectionRequired must be true exactly when the WorkItem has multiple tasks");
        }
        boolean currentPresent =
                currentTaskId.isPresent()
                        && currentExecutionId.isPresent()
                        && executionStatus.isPresent();
        boolean currentAbsent =
                currentTaskId.isEmpty() && currentExecutionId.isEmpty() && executionStatus.isEmpty();
        if (!currentPresent && !currentAbsent) {
            throw new IllegalArgumentException(
                    "the current-attempt fields must be present together or absent together");
        }
        if (currentPresent && taskCount != 1) {
            throw new IllegalArgumentException(
                    "only a single-task WorkItem reports a current attempt");
        }
    }

    /** The zero-task summary: no runtime facts, nothing to select, nothing blocked. */
    public static WorkItemExecutionSummary none(
            WorkItemId workItemId, long workItemVersion, WorkItemStatus workStatus,
            UtcTimestamp observedAt) {
        return new WorkItemExecutionSummary(
                workItemId, workItemVersion, workStatus,
                0, 0, 0,
                Optional.empty(), Optional.empty(), Optional.empty(),
                false, List.of(),
                Optional.empty(), Optional.empty(),
                workItemVersion, observedAt);
    }
}
