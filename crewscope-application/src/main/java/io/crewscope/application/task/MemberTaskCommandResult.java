package io.crewscope.application.task;

import io.crewscope.domain.task.PolicySnapshot;
import io.crewscope.domain.task.Task;
import io.crewscope.domain.task.TaskExecution;
import java.util.Objects;
import java.util.Optional;

/** Committed member command facts; Retry exposes the new current attempt separately. */
public record MemberTaskCommandResult(
        MemberTaskCommandOperation operation,
        Task task,
        TaskExecution targetExecution,
        Optional<TaskExecution> successorExecution,
        Optional<PolicySnapshot> successorPolicySnapshot) {

    public MemberTaskCommandResult {
        operation = Objects.requireNonNull(operation, "operation");
        task = Objects.requireNonNull(task, "task");
        targetExecution = Objects.requireNonNull(targetExecution, "targetExecution");
        successorExecution = Objects.requireNonNull(successorExecution, "successorExecution");
        successorPolicySnapshot = Objects.requireNonNull(
                successorPolicySnapshot, "successorPolicySnapshot");
        Task requiredTask = task;
        TaskExecution requiredTarget = targetExecution;
        if (!requiredTarget.taskId().equals(requiredTask.id())
                || successorExecution.stream().anyMatch(value ->
                        !value.taskId().equals(requiredTask.id())
                                || value.parentExecutionId()
                                        .filter(requiredTarget.id()::equals)
                                        .isEmpty())) {
            throw new IllegalArgumentException("command executions must belong to the Task lineage");
        }
        if ((operation == MemberTaskCommandOperation.RETRY) != successorExecution.isPresent()) {
            throw new IllegalArgumentException("successorExecution must exist exactly for Retry");
        }
        TaskExecution retrySuccessor = successorExecution.orElse(null);
        if ((successorPolicySnapshot.isPresent() && retrySuccessor == null)
                || successorPolicySnapshot.stream().anyMatch(value ->
                        !value.taskId().equals(requiredTask.id())
                                || !value.executionId().equals(retrySuccessor.id()))) {
            throw new IllegalArgumentException(
                    "successorPolicySnapshot must identify the Retry successor execution");
        }
    }

    public MemberTaskCommandResult(
            MemberTaskCommandOperation operation,
            Task task,
            TaskExecution targetExecution,
            Optional<TaskExecution> successorExecution) {
        this(operation, task, targetExecution, successorExecution, Optional.empty());
    }
}
