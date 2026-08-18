package io.crewscope.infrastructure.persistence.coding;

import io.crewscope.application.coding.DiffArtifactRepository;
import io.crewscope.domain.coding.DiffArtifact;
import io.crewscope.domain.coding.DiffArtifactId;
import io.crewscope.domain.coding.ExecutionWorkspaceId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** Type-safe DiffArtifact Port facade over the shared transactional JDBC store. */
@Repository
public class JdbcDiffArtifactRepositoryAdapter implements DiffArtifactRepository {

    private final JdbcCodingArtifactRepositoryAdapter store;

    public JdbcDiffArtifactRepositoryAdapter(JdbcCodingArtifactRepositoryAdapter store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    @Override
    public DiffArtifact create(DiffArtifact artifact) {
        return store.createDiff(artifact);
    }

    @Override
    public Optional<DiffArtifact> findById(
            OrganizationId organizationId,
            TeamId teamId,
            WorkProjectId workProjectId,
            DiffArtifactId artifactId) {
        return store.findDiffById(organizationId, teamId, workProjectId, artifactId);
    }

    @Override
    public Optional<DiffArtifact> findByWorkspace(
            OrganizationId organizationId,
            TeamId teamId,
            WorkProjectId workProjectId,
            ExecutionWorkspaceId workspaceId) {
        return store.findDiffByWorkspace(organizationId, teamId, workProjectId, workspaceId);
    }

    @Override
    public Optional<DiffArtifact> findByTaskExecution(
            OrganizationId organizationId,
            TeamId teamId,
            WorkProjectId workProjectId,
            TaskExecutionId taskExecutionId) {
        return store.findDiffByTaskExecution(
                organizationId, teamId, workProjectId, taskExecutionId);
    }
}
