package io.crewscope.application.task;

import io.crewscope.domain.task.AgentRunId;
import io.crewscope.domain.task.ExecutionLeaseId;
import io.crewscope.domain.task.StepExecutionId;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskId;
import java.util.Objects;
import java.util.Optional;

/** Safe relationship coordinates attached to one Task timeline event. */
public record TaskEventContext(
        TaskId taskId,
        Optional<TaskExecutionId> taskExecutionId,
        Optional<StepExecutionId> stepExecutionId,
        Optional<AgentRunId> agentRunId,
        Optional<ExecutionLeaseId> executionLeaseId) {

    public TaskEventContext {
        taskId = Objects.requireNonNull(taskId, "taskId");
        taskExecutionId = Objects.requireNonNull(taskExecutionId, "taskExecutionId");
        stepExecutionId = Objects.requireNonNull(stepExecutionId, "stepExecutionId");
        agentRunId = Objects.requireNonNull(agentRunId, "agentRunId");
        executionLeaseId = Objects.requireNonNull(executionLeaseId, "executionLeaseId");
        if ((stepExecutionId.isPresent() || agentRunId.isPresent() || executionLeaseId.isPresent())
                && taskExecutionId.isEmpty()) {
            throw new IllegalArgumentException(
                    "Step, AgentRun and Lease associations require a TaskExecution");
        }
    }

    public static TaskEventContext task(TaskId taskId) {
        return new TaskEventContext(
                taskId, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    public static TaskEventContext execution(TaskId taskId, TaskExecutionId executionId) {
        return new TaskEventContext(
                taskId,
                Optional.of(executionId),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    public static TaskEventContext lease(
            TaskId taskId, TaskExecutionId executionId, ExecutionLeaseId leaseId) {
        return new TaskEventContext(
                taskId,
                Optional.of(executionId),
                Optional.empty(),
                Optional.empty(),
                Optional.of(leaseId));
    }

    public static TaskEventContext agentRun(
            TaskId taskId,
            TaskExecutionId executionId,
            Optional<StepExecutionId> stepExecutionId,
            AgentRunId agentRunId) {
        return new TaskEventContext(
                taskId,
                Optional.of(executionId),
                stepExecutionId,
                Optional.of(agentRunId),
                Optional.empty());
    }
}
