package io.crewscope.server.api;

import io.crewscope.application.task.TaskRuntimeFacts;
import java.util.List;

/** The full durable runtime fact set for one execution attempt, without adjudication. */
public record TaskRuntimeFactsResponse(
        TaskExecutionResponse execution,
        List<PlanVersionResponse> planVersions,
        List<StepExecutionResponse> steps,
        List<AgentSessionResponse> sessions,
        List<AgentRunResponse> agentRuns,
        List<AgentInterruptResponse> interrupts,
        List<SnapshotSummaryResponse> snapshots,
        List<ExecutionLeaseResponse> leases) {
    static TaskRuntimeFactsResponse from(TaskRuntimeFacts facts) {
        return new TaskRuntimeFactsResponse(
                TaskExecutionResponse.withoutAvailability(facts.execution()),
                facts.planVersions().stream().map(PlanVersionResponse::from).toList(),
                facts.steps().stream().map(StepExecutionResponse::from).toList(),
                facts.sessions().stream().map(AgentSessionResponse::from).toList(),
                facts.agentRuns().stream().map(AgentRunResponse::from).toList(),
                facts.interrupts().stream().map(AgentInterruptResponse::from).toList(),
                facts.snapshots().stream().map(SnapshotSummaryResponse::from).toList(),
                facts.leases().stream().map(ExecutionLeaseResponse::from).toList());
    }
}
