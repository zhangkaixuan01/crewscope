package io.crewscope.application.task;

import io.crewscope.domain.conversation.AgentRuntimeSessionId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.task.StepExecutionId;
import io.crewscope.domain.task.TaskAgentRuntimeSession;
import io.crewscope.domain.task.TaskExecutionId;
import java.util.List;
import java.util.Optional;

/** Persistence Port for TASK, STEP and SPECIALIST AgentRuntimeSession bindings. */
public interface TaskAgentRuntimeSessionRepository {

    /**
     * Resolves concurrent deterministic initializers to the same committed binding and rejects an
     * existing ID whose immutable Task, Agent, profile or AgentScope coordinates differ.
     */
    TaskAgentRuntimeSession initializeIfAbsent(TaskAgentRuntimeSession candidate);

    TaskAgentRuntimeSession update(TaskAgentRuntimeSession session);

    Optional<TaskAgentRuntimeSession> findById(
            OrganizationId organizationId, AgentRuntimeSessionId sessionId);

    /** Returns Task-level and Step-level sessions in stable creation order. */
    List<TaskAgentRuntimeSession> findByExecution(
            OrganizationId organizationId, TaskExecutionId executionId);

    List<TaskAgentRuntimeSession> findByStep(
            OrganizationId organizationId, StepExecutionId stepExecutionId);
}
