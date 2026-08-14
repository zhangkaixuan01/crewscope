package io.crewscope.infrastructure.persistence.taskruntime;

import io.crewscope.application.task.AgentInterruptRepository;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.task.AgentInterrupt;
import io.crewscope.domain.task.AgentInterruptId;
import io.crewscope.domain.task.AgentRunId;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** JPA adapter enforcing pending and idempotent Resume identities through V10 constraints. */
@Repository
public class JpaAgentInterruptRepositoryAdapter implements AgentInterruptRepository {

    private final TaskRuntimeJpaSupport support;

    public JpaAgentInterruptRepositoryAdapter(TaskRuntimeJpaSupport support) {
        this.support = Objects.requireNonNull(support, "support");
    }

    @Override
    @Transactional
    public AgentInterrupt createPending(AgentInterrupt interrupt) {
        AgentInterrupt value = Objects.requireNonNull(interrupt, "interrupt");
        UUID taskId = support.taskId(value.scope().organizationId(), value.executionId());
        AgentInterruptEntity row = support.mapper.toEntity(value, taskId);
        support.entityManager.persist(row);
        support.entityManager.flush();
        return support.mapper.toDomain(row);
    }

    @Override
    @Transactional
    public AgentInterrupt update(AgentInterrupt interrupt) {
        AgentInterrupt value = Objects.requireNonNull(interrupt, "interrupt");
        long expected = TaskRuntimeJpaSupport.expected(value.version(), "agentInterrupt.version");
        AgentInterruptEntity row = support.findScoped(
                        AgentInterruptEntity.class,
                        value.scope().organizationId(), value.id().value())
                .orElseThrow(() -> new AggregateNotFoundException("AgentInterrupt", value.id()));
        TaskRuntimeJpaSupport.requireVersion("AgentInterrupt", value.id(), expected, row.version);
        support.mapper.copyState(row, value);
        support.entityManager.flush();
        return support.mapper.toDomain(row);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AgentInterrupt> findById(
            OrganizationId organizationId, AgentInterruptId interruptId) {
        return support.findScoped(AgentInterruptEntity.class, organizationId, interruptId.value())
                .map(support.mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AgentInterrupt> findPendingByRun(
            OrganizationId organizationId, AgentRunId agentRunId) {
        return findOne(organizationId, "row.agentRunId", agentRunId.value(), "row.status = 'PENDING'");
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AgentInterrupt> findByResumeRequestId(
            OrganizationId organizationId, UUID resumeRequestId) {
        return findOne(organizationId, "row.resumeRequestId", resumeRequestId, "1 = 1");
    }

    private Optional<AgentInterrupt> findOne(
            OrganizationId organizationId, String field, UUID value, String extra) {
        return support.entityManager.createQuery(
                        "SELECT row FROM AgentInterruptEntity row WHERE row.organizationId"
                                + " = :organizationId AND " + field + " = :value AND " + extra,
                        AgentInterruptEntity.class)
                .setParameter("organizationId", organizationId.value())
                .setParameter("value", value)
                .getResultStream().findFirst().map(support.mapper::toDomain);
    }
}
