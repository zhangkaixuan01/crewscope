package io.crewscope.application.execution;

import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.task.AgentRunId;
import java.util.Optional;

/** PostgreSQL serialization Port for exact Task runtime event replay. */
public interface TaskRuntimeEventReceiptRepository {

    /** Locks the AgentRun and returns both the expected next sequence and an exact-key receipt. */
    TaskRuntimeEventCommitWindow lockCommitWindow(
            OrganizationId organizationId,
            AgentRunId agentRunId,
            long segmentSequence,
            long eventSequence);

    /** Reads the exact committed coordinate used as an AgentState checkpoint prerequisite. */
    default Optional<TaskRuntimeEventReceipt> find(
            OrganizationId organizationId,
            AgentRunId agentRunId,
            long segmentSequence,
            long eventSequence) {
        return Optional.empty();
    }

    /** Creates the receipt inside the same transaction as AgentRun and DomainEvent changes. */
    TaskRuntimeEventReceipt create(TaskRuntimeEventReceipt receipt);
}
