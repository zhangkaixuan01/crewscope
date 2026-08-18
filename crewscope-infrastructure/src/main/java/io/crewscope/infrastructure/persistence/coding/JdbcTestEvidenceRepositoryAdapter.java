package io.crewscope.infrastructure.persistence.coding;

import io.crewscope.application.coding.TestEvidenceRepository;
import io.crewscope.domain.coding.ExecutionWorkspaceId;
import io.crewscope.domain.coding.TestEvidence;
import io.crewscope.domain.coding.TestEvidenceId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** Type-safe TestEvidence Port facade over the shared transactional JDBC store. */
@Repository
public class JdbcTestEvidenceRepositoryAdapter implements TestEvidenceRepository {

    private final JdbcCodingArtifactRepositoryAdapter store;

    public JdbcTestEvidenceRepositoryAdapter(JdbcCodingArtifactRepositoryAdapter store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    @Override
    public TestEvidence create(TestEvidence evidence) {
        return store.createTest(evidence);
    }

    @Override
    public Optional<TestEvidence> findById(
            OrganizationId organizationId,
            TeamId teamId,
            WorkProjectId workProjectId,
            TestEvidenceId evidenceId) {
        return store.findTestById(organizationId, teamId, workProjectId, evidenceId);
    }

    @Override
    public List<TestEvidence> findByTaskExecution(
            OrganizationId organizationId,
            TeamId teamId,
            WorkProjectId workProjectId,
            TaskExecutionId taskExecutionId) {
        return store.findTestsByTaskExecution(
                organizationId, teamId, workProjectId, taskExecutionId);
    }

    @Override
    public List<TestEvidence> findByWorkspace(
            OrganizationId organizationId,
            TeamId teamId,
            WorkProjectId workProjectId,
            ExecutionWorkspaceId workspaceId) {
        return store.findTestsByWorkspace(
                organizationId, teamId, workProjectId, workspaceId);
    }
}
