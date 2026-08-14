package io.crewscope.infrastructure.persistence.taskruntime;

import io.crewscope.application.task.SafetyEnforcementOverlayRepository;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.task.SafetyEnforcementOverlay;
import io.crewscope.domain.task.SafetyEnforcementOverlayId;
import io.crewscope.domain.task.TaskExecutionId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** JPA adapter for append-only, composite-key SafetyEnforcementOverlay versions. */
@Repository
public class JpaSafetyEnforcementOverlayRepositoryAdapter
        implements SafetyEnforcementOverlayRepository {

    private final TaskRuntimeJpaSupport support;

    public JpaSafetyEnforcementOverlayRepositoryAdapter(TaskRuntimeJpaSupport support) {
        this.support = Objects.requireNonNull(support, "support");
    }

    @Override
    @Transactional
    public SafetyEnforcementOverlay create(SafetyEnforcementOverlay overlay) {
        SafetyEnforcementOverlayEntity row = support.mapper.toEntity(
                Objects.requireNonNull(overlay, "overlay"));
        support.entityManager.persist(row);
        support.entityManager.flush();
        return support.mapper.toDomain(row);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SafetyEnforcementOverlay> findByIdAndVersion(
            OrganizationId organizationId, SafetyEnforcementOverlayId overlayId, long version) {
        return support.entityManager.createQuery(
                        """
                        SELECT row FROM SafetyEnforcementOverlayEntity row
                        WHERE row.organizationId = :organizationId
                          AND row.id = :id AND row.overlayVersion = :version
                        """,
                        SafetyEnforcementOverlayEntity.class)
                .setParameter("organizationId", organizationId.value())
                .setParameter("id", overlayId.value())
                .setParameter("version", version)
                .getResultStream().findFirst().map(support.mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SafetyEnforcementOverlay> findByExecution(
            OrganizationId organizationId, TaskExecutionId executionId) {
        return support.entityManager.createQuery(
                        """
                        SELECT row FROM SafetyEnforcementOverlayEntity row
                        WHERE row.organizationId = :organizationId
                          AND row.taskExecutionId = :executionId
                        ORDER BY row.overlayVersion
                        """,
                        SafetyEnforcementOverlayEntity.class)
                .setParameter("organizationId", organizationId.value())
                .setParameter("executionId", executionId.value())
                .getResultList().stream().map(support.mapper::toDomain).toList();
    }
}
