package io.crewscope.application.agent;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.provider.ProviderBindingId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.ExecutionCapability;
import io.crewscope.domain.task.PolicyBudget;
import io.crewscope.domain.task.PolicySnapshotId;
import io.crewscope.domain.task.Task;
import io.crewscope.domain.task.TaskExecution;
import java.util.Objects;
import java.util.Set;

/** Closed Task and model facts used to create one immutable PolicySnapshot Schema v2. */
public record CreateResolvedPolicySnapshotRequest(
        PolicySnapshotId snapshotId,
        Task task,
        TaskExecution execution,
        Principal executor,
        ResolveAgentExecutionConfigurationRequest resolutionRequest,
        Set<ExecutionCapability> capabilities,
        Set<String> allowedTools,
        Set<ProviderBindingId> providerBindingIds,
        PolicyBudget budget,
        Principal actor,
        UtcTimestamp createdAt) {

    public CreateResolvedPolicySnapshotRequest {
        snapshotId = Objects.requireNonNull(snapshotId, "snapshotId");
        task = Objects.requireNonNull(task, "task");
        execution = Objects.requireNonNull(execution, "execution");
        executor = Objects.requireNonNull(executor, "executor");
        resolutionRequest = Objects.requireNonNull(resolutionRequest, "resolutionRequest");
        capabilities = Set.copyOf(Objects.requireNonNull(capabilities, "capabilities"));
        allowedTools = Set.copyOf(Objects.requireNonNull(allowedTools, "allowedTools"));
        providerBindingIds = Set.copyOf(
                Objects.requireNonNull(providerBindingIds, "providerBindingIds"));
        budget = Objects.requireNonNull(budget, "budget");
        actor = Objects.requireNonNull(actor, "actor");
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        if (!task.scope().organizationId().equals(resolutionRequest.organizationId())) {
            throw new IllegalArgumentException(
                    "Task and resolved Agent configuration must share an Organization");
        }
    }
}
