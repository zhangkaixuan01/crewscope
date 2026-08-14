package io.crewscope.infrastructure.persistence.taskruntime;

import io.crewscope.application.task.RuntimeArtifactRepository;
import io.crewscope.domain.shared.id.ArtifactId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.task.AgentRunId;
import io.crewscope.domain.task.RuntimeArtifact;
import io.crewscope.domain.task.RuntimeArtifactId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** JPA adapter for immutable, scope-closed ArtifactStore metadata. */
@Repository
public class JpaRuntimeArtifactRepositoryAdapter implements RuntimeArtifactRepository {

    private final TaskRuntimeJpaSupport support;

    public JpaRuntimeArtifactRepositoryAdapter(TaskRuntimeJpaSupport support) {
        this.support = Objects.requireNonNull(support, "support");
    }

    @Override
    @Transactional
    public RuntimeArtifact create(RuntimeArtifact artifact) {
        RuntimeArtifactEntity row = support.mapper.toEntity(
                Objects.requireNonNull(artifact, "artifact"));
        support.entityManager.persist(row);
        support.entityManager.flush();
        return support.mapper.toDomain(row);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RuntimeArtifact> findById(
            OrganizationId organizationId, RuntimeArtifactId runtimeArtifactId) {
        return support.findScoped(RuntimeArtifactEntity.class, organizationId, runtimeArtifactId.value())
                .map(support.mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RuntimeArtifact> findByArtifactId(
            OrganizationId organizationId, ArtifactId artifactId) {
        return support.entityManager.createQuery(
                        """
                        SELECT row FROM RuntimeArtifactEntity row
                        WHERE row.organizationId = :organizationId AND row.artifactId = :artifactId
                        """,
                        RuntimeArtifactEntity.class)
                .setParameter("organizationId", organizationId.value())
                .setParameter("artifactId", artifactId.value())
                .getResultStream().findFirst().map(support.mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<RuntimeArtifact> findByAgentRun(
            OrganizationId organizationId, AgentRunId agentRunId) {
        return support.entityManager.createQuery(
                        """
                        SELECT row FROM RuntimeArtifactEntity row
                        WHERE row.organizationId = :organizationId AND row.agentRunId = :agentRunId
                        ORDER BY row.createdAt, row.id
                        """,
                        RuntimeArtifactEntity.class)
                .setParameter("organizationId", organizationId.value())
                .setParameter("agentRunId", agentRunId.value())
                .getResultList().stream().map(support.mapper::toDomain).toList();
    }
}
