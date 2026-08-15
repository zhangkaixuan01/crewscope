package io.crewscope.application.task;

import io.crewscope.domain.task.AgentInterrupt;
import io.crewscope.domain.task.AgentRun;
import io.crewscope.domain.task.AgentStateSnapshot;
import io.crewscope.domain.task.ExecutionLease;
import io.crewscope.domain.task.PlanVersion;
import io.crewscope.domain.task.StepExecution;
import io.crewscope.domain.task.Task;
import io.crewscope.domain.task.TaskAgentRuntimeSession;
import io.crewscope.domain.task.TaskExecution;
import java.util.List;
import java.util.Objects;

/** Bounded-query runtime graph for one current or historical TaskExecution attempt. */
public record TaskRuntimeFacts(
        Task task,
        TaskExecution execution,
        List<PlanVersion> planVersions,
        List<StepExecution> steps,
        List<TaskAgentRuntimeSession> sessions,
        List<AgentRun> agentRuns,
        List<AgentInterrupt> interrupts,
        List<AgentStateSnapshot> snapshots,
        List<ExecutionLease> leases) {

    public TaskRuntimeFacts {
        Task requiredTask = Objects.requireNonNull(task, "task");
        TaskExecution requiredExecution = Objects.requireNonNull(execution, "execution");
        List<PlanVersion> requiredPlans = copy(planVersions, "planVersions");
        List<StepExecution> requiredSteps = copy(steps, "steps");
        List<TaskAgentRuntimeSession> requiredSessions = copy(sessions, "sessions");
        List<AgentRun> requiredRuns = copy(agentRuns, "agentRuns");
        List<AgentInterrupt> requiredInterrupts = copy(interrupts, "interrupts");
        List<AgentStateSnapshot> requiredSnapshots = copy(snapshots, "snapshots");
        List<ExecutionLease> requiredLeases = copy(leases, "leases");
        if (!requiredExecution.taskId().equals(requiredTask.id())
                || !requiredExecution.scope().equals(requiredTask.scope())) {
            throw new IllegalArgumentException("execution must belong to the Task");
        }
        if (requiredPlans.stream().anyMatch(value -> !value.executionId().equals(requiredExecution.id()))
                || requiredSteps.stream().anyMatch(value -> !value.executionId().equals(requiredExecution.id()))
                || requiredSessions.stream().anyMatch(value -> !value.executionId().equals(requiredExecution.id()))
                || requiredRuns.stream().anyMatch(value -> !value.executionId().equals(requiredExecution.id()))
                || requiredInterrupts.stream().anyMatch(value -> !value.executionId().equals(requiredExecution.id()))
                || requiredSnapshots.stream().anyMatch(value -> !value.executionId().equals(requiredExecution.id()))
                || requiredLeases.stream().anyMatch(value -> !value.taskExecutionId().equals(requiredExecution.id()))) {
            throw new IllegalArgumentException("runtime facts must belong to the TaskExecution");
        }
        task = requiredTask;
        execution = requiredExecution;
        planVersions = requiredPlans;
        steps = requiredSteps;
        sessions = requiredSessions;
        agentRuns = requiredRuns;
        interrupts = requiredInterrupts;
        snapshots = requiredSnapshots;
        leases = requiredLeases;
    }

    private static <T> List<T> copy(List<T> values, String field) {
        return List.copyOf(Objects.requireNonNull(values, field));
    }
}
