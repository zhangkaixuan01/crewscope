package io.crewscope.application.task;

import io.crewscope.domain.task.ExecutionLeaseId;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskProviderGrantRequest;
import java.time.Duration;
import java.util.Collection;
import java.util.Objects;
import java.util.Set;

/** Trusted Worker request for one minimum-scope short-lived Task Token. */
public record TaskTokenIssueCommand(
        TaskExecutionId taskExecutionId,
        ExecutionLeaseId executionLeaseId,
        Set<String> allowedTools,
        Collection<TaskProviderGrantRequest> providerRequests,
        Duration lifetime) {

    public TaskTokenIssueCommand {
        taskExecutionId = Objects.requireNonNull(taskExecutionId, "taskExecutionId");
        executionLeaseId = Objects.requireNonNull(executionLeaseId, "executionLeaseId");
        allowedTools = Set.copyOf(Objects.requireNonNull(allowedTools, "allowedTools"));
        providerRequests = java.util.List.copyOf(
                Objects.requireNonNull(providerRequests, "providerRequests"));
        lifetime = Objects.requireNonNull(lifetime, "lifetime");
    }
}
