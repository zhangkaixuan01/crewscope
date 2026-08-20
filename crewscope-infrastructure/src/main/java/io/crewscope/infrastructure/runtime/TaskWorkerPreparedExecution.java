package io.crewscope.infrastructure.runtime;

import io.crewscope.application.execution.TaskExecutionRuntimeFacts;
import io.crewscope.application.task.LeaseCommandScope;
import io.crewscope.application.task.TaskTokenIssueResult;
import io.crewscope.infrastructure.workspace.repository.CodingWorkspaceExecution;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Secret-bearing in-memory preparation result; its string representation never reveals a Token. */
public record TaskWorkerPreparedExecution(
        TaskExecutionRuntimeFacts facts,
        LeaseCommandScope leaseScope,
        TaskTokenIssueResult token,
        UUID correlationId,
        Optional<CodingWorkspaceExecution> codingWorkspace) {

    public TaskWorkerPreparedExecution {
        facts = Objects.requireNonNull(facts, "facts");
        leaseScope = Objects.requireNonNull(leaseScope, "leaseScope");
        token = Objects.requireNonNull(token, "token");
        correlationId = Objects.requireNonNull(correlationId, "correlationId");
        codingWorkspace = Objects.requireNonNull(codingWorkspace, "codingWorkspace");
    }

    public TaskWorkerPreparedExecution(
            TaskExecutionRuntimeFacts facts,
            LeaseCommandScope leaseScope,
            TaskTokenIssueResult token,
            UUID correlationId) {
        this(facts, leaseScope, token, correlationId, Optional.empty());
    }

    @Override
    public String toString() {
        return "TaskWorkerPreparedExecution[taskExecutionId=" + facts.execution().id()
                + ", leaseId=" + leaseScope.leaseId()
                + ", token=[REDACTED]]";
    }
}
