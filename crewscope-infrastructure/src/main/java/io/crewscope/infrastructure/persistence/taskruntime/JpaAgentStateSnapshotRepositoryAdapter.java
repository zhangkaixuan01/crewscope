package io.crewscope.infrastructure.persistence.taskruntime;

import io.crewscope.application.task.AgentStateSnapshotRepository;
import io.crewscope.domain.conversation.AgentRuntimeSessionId;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.task.AgentStateSnapshot;
import io.crewscope.domain.task.AgentStateSnapshotId;
import io.crewscope.domain.task.AgentRunId;
import io.crewscope.domain.task.TaskExecutionId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** JPA adapter for versioned AgentStateSnapshot metadata and current-snapshot lookup. */
@Repository
public class JpaAgentStateSnapshotRepositoryAdapter implements AgentStateSnapshotRepository {

    private final TaskRuntimeJpaSupport support;

    public JpaAgentStateSnapshotRepositoryAdapter(TaskRuntimeJpaSupport support) {
        this.support = Objects.requireNonNull(support, "support");
    }

    @Override
    @Transactional
    public AgentStateSnapshot publish(
            Optional<AgentStateSnapshot> supersededCurrent,
            AgentStateSnapshot currentSnapshot) {
        supersededCurrent.ifPresent(this::update);
        AgentStateSnapshot value = Objects.requireNonNull(currentSnapshot, "currentSnapshot");
        java.util.UUID taskId = support.taskId(
                value.scope().organizationId(), value.executionId());
        AgentStateSnapshotEntity row = support.mapper.toEntity(value, taskId);
        support.entityManager.persist(row);
        support.entityManager.flush();
        return support.mapper.toDomain(row);
    }

    @Override
    @Transactional
    public AgentStateSnapshot update(AgentStateSnapshot snapshot) {
        AgentStateSnapshot value = Objects.requireNonNull(snapshot, "snapshot");
        long expected = TaskRuntimeJpaSupport.expected(value.version(), "agentStateSnapshot.version");
        AgentStateSnapshotEntity row = support.findScoped(
                        AgentStateSnapshotEntity.class,
                        value.scope().organizationId(), value.id().value())
                .orElseThrow(() -> new AggregateNotFoundException(
                        "AgentStateSnapshot", value.id()));
        TaskRuntimeJpaSupport.requireVersion(
                "AgentStateSnapshot", value.id(), expected, row.version);
        support.mapper.copyState(row, value);
        support.entityManager.flush();
        return support.mapper.toDomain(row);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AgentStateSnapshot> findById(
            OrganizationId organizationId, AgentStateSnapshotId snapshotId) {
        return support.findScoped(AgentStateSnapshotEntity.class, organizationId, snapshotId.value())
                .map(support.mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AgentStateSnapshot> findCurrentBySession(
            OrganizationId organizationId, AgentRuntimeSessionId runtimeSessionId) {
        return support.entityManager.createQuery(
                        """
                        SELECT row FROM AgentStateSnapshotEntity row
                        WHERE row.organizationId = :organizationId
                          AND row.runtimeSessionId = :sessionId AND row.status = 'CURRENT'
                        """,
                        AgentStateSnapshotEntity.class)
                .setParameter("organizationId", organizationId.value())
                .setParameter("sessionId", runtimeSessionId.value())
                .getResultStream().findFirst().map(support.mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AgentStateSnapshot> findLatestBySession(
            OrganizationId organizationId, AgentRuntimeSessionId runtimeSessionId) {
        return support.entityManager.createQuery(
                        """
                        SELECT row FROM AgentStateSnapshotEntity row
                        WHERE row.organizationId = :organizationId
                          AND row.runtimeSessionId = :sessionId
                        ORDER BY row.snapshotSequence DESC, row.id
                        """,
                        AgentStateSnapshotEntity.class)
                .setParameter("organizationId", organizationId.value())
                .setParameter("sessionId", runtimeSessionId.value())
                .setMaxResults(1)
                .getResultStream().findFirst().map(support.mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AgentStateSnapshot> findLatestByExecution(
            OrganizationId organizationId, TaskExecutionId executionId) {
        return support.entityManager.createQuery(
                        """
                        SELECT row FROM AgentStateSnapshotEntity row
                        WHERE row.organizationId = :organizationId
                          AND row.taskExecutionId = :executionId
                        ORDER BY row.snapshotSequence DESC, row.id
                        """,
                        AgentStateSnapshotEntity.class)
                .setParameter("organizationId", organizationId.value())
                .setParameter("executionId", executionId.value())
                .setMaxResults(1)
                .getResultStream().findFirst().map(support.mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AgentStateSnapshot> findRecoveryCandidates(
            OrganizationId organizationId, AgentRunId agentRunId, int limit) {
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("limit must be between 1 and 1000");
        }
        return support.entityManager.createQuery(
                        """
                        SELECT row FROM AgentStateSnapshotEntity row
                        WHERE row.organizationId = :organizationId
                          AND row.agentRunId = :agentRunId
                          AND row.status IN ('CURRENT', 'SUPERSEDED')
                        ORDER BY row.checkpointSequence DESC, row.snapshotSequence DESC, row.id
                        """,
                        AgentStateSnapshotEntity.class)
                .setParameter("organizationId", organizationId.value())
                .setParameter("agentRunId", agentRunId.value())
                .setMaxResults(limit)
                .getResultList().stream().map(support.mapper::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AgentStateSnapshot> findByExecution(
            OrganizationId organizationId, TaskExecutionId executionId) {
        return support.entityManager.createQuery(
                        """
                        SELECT row FROM AgentStateSnapshotEntity row
                        WHERE row.organizationId = :organizationId
                          AND row.taskExecutionId = :executionId
                        ORDER BY row.snapshotSequence, row.id
                        """,
                        AgentStateSnapshotEntity.class)
                .setParameter("organizationId", organizationId.value())
                .setParameter("executionId", executionId.value())
                .getResultList().stream().map(support.mapper::toDomain).toList();
    }
}
