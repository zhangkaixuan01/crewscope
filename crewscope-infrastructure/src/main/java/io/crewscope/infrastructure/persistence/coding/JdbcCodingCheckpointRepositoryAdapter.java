package io.crewscope.infrastructure.persistence.coding;

import io.crewscope.application.coding.CodingCheckpointRepository;
import io.crewscope.domain.coding.CodingCheckpoint;
import io.crewscope.domain.coding.CodingCheckpointId;
import io.crewscope.domain.coding.ExecutionWorkspaceId;
import io.crewscope.domain.shared.id.OrganizationId;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** Type-safe CodingCheckpoint Port facade over the shared transactional JDBC store. */
@Repository
public class JdbcCodingCheckpointRepositoryAdapter implements CodingCheckpointRepository {

    private final JdbcCodingArtifactRepositoryAdapter store;

    public JdbcCodingCheckpointRepositoryAdapter(JdbcCodingArtifactRepositoryAdapter store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    @Override
    public CodingCheckpoint append(CodingCheckpoint checkpoint) {
        return store.appendCheckpoint(checkpoint);
    }

    @Override
    public Optional<CodingCheckpoint> findById(
            OrganizationId organizationId, CodingCheckpointId checkpointId) {
        return store.findCheckpointById(organizationId, checkpointId);
    }

    @Override
    public Optional<CodingCheckpoint> findLatestByWorkspace(
            OrganizationId organizationId, ExecutionWorkspaceId executionWorkspaceId) {
        return store.findLatestCheckpointByWorkspace(organizationId, executionWorkspaceId);
    }
}
