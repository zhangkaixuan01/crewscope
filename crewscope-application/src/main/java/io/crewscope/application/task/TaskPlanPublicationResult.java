package io.crewscope.application.task;

import io.crewscope.domain.task.PlanVersion;
import io.crewscope.domain.task.StepExecution;
import io.crewscope.domain.task.TaskExecution;
import java.util.List;
import java.util.Objects;

/** Atomically selected plan, updated execution pointer and its newly materialized Steps. */
public record TaskPlanPublicationResult(
        PlanVersion planVersion,
        TaskExecution taskExecution,
        List<StepExecution> stepExecutions) {

    public TaskPlanPublicationResult {
        planVersion = Objects.requireNonNull(planVersion, "planVersion");
        taskExecution = Objects.requireNonNull(taskExecution, "taskExecution");
        stepExecutions = List.copyOf(Objects.requireNonNull(stepExecutions, "stepExecutions"));
        if (stepExecutions.size() != planVersion.steps().size()) {
            throw new IllegalArgumentException("every published Plan Step requires one StepExecution");
        }
    }
}
