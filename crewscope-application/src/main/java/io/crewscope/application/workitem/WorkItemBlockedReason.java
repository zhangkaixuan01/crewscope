package io.crewscope.application.workitem;

import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskExecutionWaitReason;
import io.crewscope.domain.task.TaskId;
import java.util.Objects;
import java.util.Optional;

/**
 * One explainable reason a WorkItem is waiting, as aggregated into its summary (M9b-A06).
 *
 * <p>{@code waitingOnPrincipalId} names the member whose action unblocks the wait when the reason
 * identifies one (an active Reviewer for review-shaped waits); surfaces resolve it to a display
 * name. It is deliberately absent for machine-driven reasons such as {@code RUNTIME}.
 */
public record WorkItemBlockedReason(
        Code code,
        Optional<TaskId> taskId,
        Optional<TaskExecutionId> executionId,
        Optional<UtcTimestamp> since,
        Optional<PrincipalId> waitingOnPrincipalId) {

    public WorkItemBlockedReason {
        code = Objects.requireNonNull(code, "code");
        taskId = Objects.requireNonNull(taskId, "taskId");
        executionId = Objects.requireNonNull(executionId, "executionId");
        since = Objects.requireNonNull(since, "since");
        waitingOnPrincipalId = Objects.requireNonNull(waitingOnPrincipalId, "waitingOnPrincipalId");
    }

    /** Mirrors {@link TaskExecutionWaitReason} plus the item-level derived review wait. */
    public enum Code {
        RUNTIME,
        COLLABORATION,
        REVIEW,
        CONFIRMATION,
        USER_INPUT,
        EXTERNAL_EXECUTION,
        EVENT,
        MANUAL,
        /** Derived: at least one review request is open on this WorkItem. */
        REVIEW_PENDING;

        static Code from(TaskExecutionWaitReason reason) {
            return valueOf(Objects.requireNonNull(reason, "reason").name());
        }
    }

    /** A wait reported by a WAITING execution, bound to the task and execution that reported it. */
    public static WorkItemBlockedReason waiting(
            TaskExecutionWaitReason reason,
            TaskId taskId,
            TaskExecutionId executionId,
            Optional<UtcTimestamp> since,
            Optional<PrincipalId> waitingOnPrincipalId) {
        return new WorkItemBlockedReason(
                Code.from(reason),
                Optional.of(taskId),
                Optional.of(executionId),
                since,
                waitingOnPrincipalId);
    }

    /** The item-level "a review is pending" wait; it belongs to the item, not to one task. */
    public static WorkItemBlockedReason reviewPending() {
        return new WorkItemBlockedReason(
                Code.REVIEW_PENDING, Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty());
    }

    /**
     * The item-level "more than one task and none is chosen as the current one" wait; it belongs to
     * the runtime that must pick, so it names no person and no single execution.
     */
    public static WorkItemBlockedReason selectionRequired() {
        return new WorkItemBlockedReason(
                Code.RUNTIME, Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty());
    }
}
