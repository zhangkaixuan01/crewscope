package io.crewscope.application.task;

import io.crewscope.domain.task.PolicySnapshot;
import io.crewscope.domain.task.SafetyEnforcementOverlay;
import io.crewscope.domain.task.Task;
import io.crewscope.domain.task.TaskExecution;
import java.util.Objects;

/** Complete first-attempt graph committed by one successful delegation command. */
public record AgentTaskCreationResult(
        Task task,
        TaskExecution execution,
        PolicySnapshot policySnapshot,
        SafetyEnforcementOverlay safetyOverlay) {

    public AgentTaskCreationResult {
        task = Objects.requireNonNull(task, "task");
        execution = Objects.requireNonNull(execution, "execution");
        policySnapshot = Objects.requireNonNull(policySnapshot, "policySnapshot");
        safetyOverlay = Objects.requireNonNull(safetyOverlay, "safetyOverlay");
    }
}
