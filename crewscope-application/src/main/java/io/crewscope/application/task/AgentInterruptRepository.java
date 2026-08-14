package io.crewscope.application.task;

import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.task.AgentInterrupt;
import io.crewscope.domain.task.AgentInterruptId;
import io.crewscope.domain.task.AgentRunId;
import java.util.Optional;
import java.util.UUID;

/** Persistence Port enforcing one pending AgentInterrupt per AgentRun. */
public interface AgentInterruptRepository {

    /** Creates under the pending partial-unique constraint for an AgentRun. */
    AgentInterrupt createPending(AgentInterrupt interrupt);

    /** Persists Resolve, Cancel or Expire using Version and unique Resume Request ID predicates. */
    AgentInterrupt update(AgentInterrupt interrupt);

    Optional<AgentInterrupt> findById(
            OrganizationId organizationId, AgentInterruptId interruptId);

    Optional<AgentInterrupt> findPendingByRun(
            OrganizationId organizationId, AgentRunId agentRunId);

    Optional<AgentInterrupt> findByResumeRequestId(
            OrganizationId organizationId, UUID resumeRequestId);
}
