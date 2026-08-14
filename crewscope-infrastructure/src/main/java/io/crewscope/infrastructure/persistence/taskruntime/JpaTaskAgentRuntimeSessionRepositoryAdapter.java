package io.crewscope.infrastructure.persistence.taskruntime;

import io.crewscope.application.task.TaskAgentRuntimeSessionRepository;
import io.crewscope.domain.conversation.AgentRuntimeSessionId;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.task.StepExecutionId;
import io.crewscope.domain.task.TaskAgentRuntimeSession;
import io.crewscope.domain.task.TaskExecutionId;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** JPA/JDBC adapter for deterministic TASK, STEP and SPECIALIST AgentScope sessions. */
@Repository
public class JpaTaskAgentRuntimeSessionRepositoryAdapter
        implements TaskAgentRuntimeSessionRepository {

    private final TaskRuntimeJpaSupport support;
    private final NamedParameterJdbcTemplate jdbc;

    public JpaTaskAgentRuntimeSessionRepositoryAdapter(
            TaskRuntimeJpaSupport support, NamedParameterJdbcTemplate jdbc) {
        this.support = Objects.requireNonNull(support, "support");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    @Transactional
    public TaskAgentRuntimeSession initializeIfAbsent(TaskAgentRuntimeSession candidate) {
        TaskAgentRuntimeSession value = Objects.requireNonNull(candidate, "candidate");
        TaskAgentRuntimeSessionEntity row = support.mapper.toEntity(value);
        HashMap<String, Object> parameters = new HashMap<>();
        parameters.put("id", row.id);
        parameters.put("organizationId", row.organizationId);
        parameters.put("teamId", row.teamId);
        parameters.put("workspaceId", row.workspaceId);
        parameters.put("projectId", row.projectId);
        parameters.put("taskId", row.taskId);
        parameters.put("executionId", row.taskExecutionId);
        parameters.put("stepId", row.stepExecutionId);
        parameters.put("purpose", row.sessionPurpose);
        parameters.put("agentPrincipalId", row.agentPrincipalId);
        parameters.put("agentProfileId", row.agentProfileId);
        parameters.put("agentProfileVersion", row.agentProfileVersion);
        parameters.put("userId", row.agentScopeUserId);
        parameters.put("sessionId", row.agentScopeSessionId);
        parameters.put("stateReference", row.stateReference);
        parameters.put("status", row.status);
        parameters.put("principalType", row.agentPrincipalType);
        parameters.put("profileType", row.agentProfileType);
        parameters.put("createdAt", row.createdAt.atOffset(java.time.ZoneOffset.UTC));
        parameters.put("createdBy", row.createdByPrincipalId);
        parameters.put("updatedAt", row.updatedAt.atOffset(java.time.ZoneOffset.UTC));
        parameters.put("updatedBy", row.updatedByPrincipalId);
        jdbc.update(
                """
                INSERT INTO crewscope.agent_runtime_session (
                    id, organization_id, team_id, workspace_id, project_id,
                    task_id, task_execution_id, step_execution_id, session_purpose,
                    agent_principal_id, agent_profile_id, agent_profile_version,
                    agent_scope_user_id, agent_scope_session_id, state_reference, status,
                    agent_principal_type, agent_profile_type, version,
                    created_at, created_by_principal_id, updated_at, updated_by_principal_id
                ) VALUES (
                    :id, :organizationId, :teamId, :workspaceId, :projectId,
                    :taskId, :executionId, :stepId, :purpose,
                    :agentPrincipalId, :agentProfileId, :agentProfileVersion,
                    :userId, :sessionId, :stateReference, :status,
                    :principalType, :profileType, 0,
                    :createdAt, :createdBy, :updatedAt, :updatedBy
                ) ON CONFLICT (id) DO NOTHING
                """,
                parameters);
        support.entityManager.clear();
        TaskAgentRuntimeSession committed = findById(value.scope().organizationId(), value.id())
                .orElseThrow(() -> new AggregateNotFoundException(
                        "TaskAgentRuntimeSession", value.id()));
        if (!sameImmutableIdentity(committed, value)) {
            throw new DomainValidationException(
                    "taskAgentRuntimeSession.id",
                    "must not collide with another immutable AgentRuntimeSession identity");
        }
        return committed;
    }

    @Override
    @Transactional
    public TaskAgentRuntimeSession update(TaskAgentRuntimeSession session) {
        TaskAgentRuntimeSession value = Objects.requireNonNull(session, "session");
        long expected = TaskRuntimeJpaSupport.expected(value.version(), "taskAgentSession.version");
        TaskAgentRuntimeSessionEntity row = support.findScoped(
                        TaskAgentRuntimeSessionEntity.class,
                        value.scope().organizationId(), value.id().value())
                .orElseThrow(() -> new AggregateNotFoundException(
                        "TaskAgentRuntimeSession", value.id()));
        TaskRuntimeJpaSupport.requireVersion(
                "TaskAgentRuntimeSession", value.id(), expected, row.version);
        support.mapper.copyState(row, value);
        support.entityManager.flush();
        return support.mapper.toDomain(row);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TaskAgentRuntimeSession> findById(
            OrganizationId organizationId, AgentRuntimeSessionId sessionId) {
        return support.findScoped(TaskAgentRuntimeSessionEntity.class, organizationId, sessionId.value())
                .filter(row -> !"PERSONAL".equals(row.sessionPurpose))
                .map(support.mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<TaskAgentRuntimeSession> findByExecution(
            OrganizationId organizationId, TaskExecutionId executionId) {
        return findList(organizationId, "row.taskExecutionId", executionId.value());
    }

    @Override
    @Transactional(readOnly = true)
    public List<TaskAgentRuntimeSession> findByStep(
            OrganizationId organizationId, StepExecutionId stepExecutionId) {
        return findList(organizationId, "row.stepExecutionId", stepExecutionId.value());
    }

    private List<TaskAgentRuntimeSession> findList(
            OrganizationId organizationId, String field, java.util.UUID value) {
        return support.entityManager.createQuery(
                        "SELECT row FROM TaskAgentRuntimeSessionEntity row"
                                + " WHERE row.organizationId = :organizationId AND " + field
                                + " = :value AND row.sessionPurpose <> 'PERSONAL'"
                                + " ORDER BY row.createdAt, row.id",
                        TaskAgentRuntimeSessionEntity.class)
                .setParameter("organizationId", organizationId.value())
                .setParameter("value", value)
                .getResultList().stream().map(support.mapper::toDomain).toList();
    }

    /** Compares only coordinates that must remain stable for the lifetime of a state slot. */
    private static boolean sameImmutableIdentity(
            TaskAgentRuntimeSession committed, TaskAgentRuntimeSession candidate) {
        return committed.scope().equals(candidate.scope())
                && committed.taskId().equals(candidate.taskId())
                && committed.executionId().equals(candidate.executionId())
                && committed.stepExecutionId().equals(candidate.stepExecutionId())
                && committed.purpose() == candidate.purpose()
                && committed.agentPrincipalId().equals(candidate.agentPrincipalId())
                && committed.agentProfileId().equals(candidate.agentProfileId())
                && committed.agentScopeKey().equals(candidate.agentScopeKey())
                && committed.stateReference().equals(candidate.stateReference());
    }
}
