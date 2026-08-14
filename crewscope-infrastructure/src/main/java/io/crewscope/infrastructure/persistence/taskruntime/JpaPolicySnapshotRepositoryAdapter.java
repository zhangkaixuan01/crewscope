package io.crewscope.infrastructure.persistence.taskruntime;

import io.crewscope.application.task.PolicySnapshotRepository;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.task.PolicySnapshot;
import io.crewscope.domain.task.PolicySnapshotId;
import io.crewscope.domain.task.TaskExecutionId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** JPA adapter for immutable PolicySnapshot revisions and JSONB capability sets. */
@Repository
public class JpaPolicySnapshotRepositoryAdapter implements PolicySnapshotRepository {

    private final TaskRuntimeJpaSupport support;

    public JpaPolicySnapshotRepositoryAdapter(TaskRuntimeJpaSupport support) {
        this.support = Objects.requireNonNull(support, "support");
    }

    @Override
    @Transactional
    public PolicySnapshot create(PolicySnapshot snapshot) {
        PolicySnapshotEntity row = support.mapper.toEntity(
                Objects.requireNonNull(snapshot, "snapshot"));
        support.entityManager.persist(row);
        support.entityManager.flush();
        return support.mapper.toDomain(row);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PolicySnapshot> findById(
            OrganizationId organizationId, PolicySnapshotId snapshotId) {
        return support.findScoped(PolicySnapshotEntity.class, organizationId, snapshotId.value())
                .map(support.mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PolicySnapshot> findByExecution(
            OrganizationId organizationId, TaskExecutionId executionId) {
        return support.entityManager.createQuery(
                        """
                        SELECT row FROM PolicySnapshotEntity row
                        WHERE row.organizationId = :organizationId
                          AND row.taskExecutionId = :executionId
                        ORDER BY row.revision
                        """,
                        PolicySnapshotEntity.class)
                .setParameter("organizationId", organizationId.value())
                .setParameter("executionId", executionId.value())
                .getResultList().stream().map(support.mapper::toDomain).toList();
    }
}
