package io.crewscope.application.task;

import io.crewscope.domain.conversation.AgentRuntimeSessionId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.task.AgentRun;
import io.crewscope.domain.task.AgentRunId;
import io.crewscope.domain.task.StepExecutionId;
import io.crewscope.domain.task.TaskExecutionId;
import java.util.List;
import java.util.Optional;

/** Atomic persistence Port for durable AgentRun sequencing and state transitions. */
public interface AgentRunRepository {

    /**
     * Creates a Run under unique {@code (taskExecutionId, runSequence)} and one-active-Run per
     * Session predicates. A Step may own multiple historical Runs with different sequences.
     */
    AgentRun createNext(AgentRun run);

    /** Commits Segment, Interrupt, Resume or terminal changes using AgentRun Version. */
    AgentRun update(AgentRun run);

    Optional<AgentRun> findById(OrganizationId organizationId, AgentRunId agentRunId);

    Optional<AgentRun> findActiveBySession(
            OrganizationId organizationId, AgentRuntimeSessionId sessionId);

    List<AgentRun> findByExecution(
            OrganizationId organizationId, TaskExecutionId executionId);

    List<AgentRun> findByStep(
            OrganizationId organizationId, StepExecutionId stepExecutionId);
}
