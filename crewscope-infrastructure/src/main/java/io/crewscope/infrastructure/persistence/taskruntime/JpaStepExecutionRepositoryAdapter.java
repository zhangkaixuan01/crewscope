package io.crewscope.infrastructure.persistence.taskruntime;

import io.crewscope.application.task.StepExecutionRepository;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.task.StepExecution;
import io.crewscope.domain.task.StepExecutionId;
import io.crewscope.domain.task.TaskExecutionId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** JPA adapter for mutable serial StepExecution facts. */
@Repository
public class JpaStepExecutionRepositoryAdapter implements StepExecutionRepository {

    private final TaskRuntimeJpaSupport support;

    public JpaStepExecutionRepositoryAdapter(TaskRuntimeJpaSupport support) {
        this.support = Objects.requireNonNull(support, "support");
    }

    @Override
    @Transactional
    public StepExecution create(StepExecution step) {
        StepExecutionEntity row = support.mapper.toEntity(Objects.requireNonNull(step, "step"));
        support.entityManager.persist(row);
        support.entityManager.flush();
        return support.mapper.toDomain(row);
    }

    @Override
    @Transactional
    public StepExecution update(StepExecution step) {
        StepExecution value = Objects.requireNonNull(step, "step");
        long expected = TaskRuntimeJpaSupport.expected(value.version(), "stepExecution.version");
        StepExecutionEntity row = support.findScoped(
                        StepExecutionEntity.class, value.scope().organizationId(), value.id().value())
                .orElseThrow(() -> new AggregateNotFoundException("StepExecution", value.id()));
        TaskRuntimeJpaSupport.requireVersion("StepExecution", value.id(), expected, row.version);
        support.mapper.copyState(row, value);
        support.entityManager.flush();
        return support.mapper.toDomain(row);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<StepExecution> findById(
            OrganizationId organizationId, StepExecutionId stepExecutionId) {
        return support.findScoped(StepExecutionEntity.class, organizationId, stepExecutionId.value())
                .map(support.mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<StepExecution> findByExecution(
            OrganizationId organizationId, TaskExecutionId executionId) {
        return support.entityManager.createQuery(
                        """
                        SELECT row FROM StepExecutionEntity row
                        WHERE row.organizationId = :organizationId
                          AND row.taskExecutionId = :executionId
                        ORDER BY row.sequence, row.id
                        """,
                        StepExecutionEntity.class)
                .setParameter("organizationId", organizationId.value())
                .setParameter("executionId", executionId.value())
                .getResultList().stream().map(support.mapper::toDomain).toList();
    }
}
