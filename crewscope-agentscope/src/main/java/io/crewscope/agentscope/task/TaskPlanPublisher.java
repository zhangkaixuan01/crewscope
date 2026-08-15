package io.crewscope.agentscope.task;

import io.crewscope.application.execution.TaskExecutionRuntimeFacts;
import io.crewscope.domain.task.PlanVersionId;

/** Adapter-local boundary that publishes one fully validated AgentScope plan candidate. */
@FunctionalInterface
public interface TaskPlanPublisher {

    PlanVersionId publish(
            TaskExecutionRuntimeFacts facts,
            AgentScopeTaskPlanAdapter.Candidate candidate);
}
