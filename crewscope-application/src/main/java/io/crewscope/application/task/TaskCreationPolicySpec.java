package io.crewscope.application.task;

import io.crewscope.domain.policy.PolicyPackReference;
import io.crewscope.domain.task.ExecutionCapability;
import io.crewscope.domain.task.PolicyBudget;
import io.crewscope.domain.task.TaskExecution;
import io.crewscope.domain.task.TaskExecutionPriority;
import java.util.Objects;
import java.util.Set;

/** Server-owned bounded policy used to create the first TaskExecution and PolicySnapshot. */
public record TaskCreationPolicySpec(
        PolicyPackReference policyPack,
        Set<ExecutionCapability> capabilities,
        Set<String> allowedTools,
        PolicyBudget budget,
        int maxAttempts,
        TaskExecutionPriority priority) {

    public TaskCreationPolicySpec {
        policyPack = Objects.requireNonNull(policyPack, "policyPack");
        capabilities = Set.copyOf(Objects.requireNonNull(capabilities, "capabilities"));
        allowedTools = Set.copyOf(Objects.requireNonNull(allowedTools, "allowedTools"));
        budget = Objects.requireNonNull(budget, "budget");
        if (capabilities.isEmpty() || allowedTools.isEmpty()) {
            throw new IllegalArgumentException("Task creation policy must declare capabilities and tools");
        }
        if (maxAttempts < 1 || maxAttempts > TaskExecution.MAX_SUPPORTED_ATTEMPTS) {
            throw new IllegalArgumentException("maxAttempts is outside the supported range");
        }
        priority = Objects.requireNonNull(priority, "priority");
    }
}
