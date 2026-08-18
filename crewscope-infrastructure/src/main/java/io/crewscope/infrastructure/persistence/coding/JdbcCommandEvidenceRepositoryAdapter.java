package io.crewscope.infrastructure.persistence.coding;

import io.crewscope.application.coding.CommandEvidenceRepository;
import io.crewscope.domain.coding.CommandEvidence;
import io.crewscope.domain.coding.CommandEvidenceId;
import io.crewscope.domain.coding.ExecutionWorkspaceId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** Type-safe CommandEvidence Port facade over the shared transactional JDBC store. */
@Repository
public class JdbcCommandEvidenceRepositoryAdapter implements CommandEvidenceRepository {

    private final JdbcCodingArtifactRepositoryAdapter store;

    public JdbcCommandEvidenceRepositoryAdapter(JdbcCodingArtifactRepositoryAdapter store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    @Override
    public CommandEvidence create(CommandEvidence evidence) {
        return store.createCommand(evidence);
    }

    @Override
    public Optional<CommandEvidence> findById(
            OrganizationId organizationId,
            TeamId teamId,
            WorkProjectId workProjectId,
            CommandEvidenceId evidenceId) {
        return store.findCommandById(organizationId, teamId, workProjectId, evidenceId);
    }

    @Override
    public List<CommandEvidence> findByTaskExecution(
            OrganizationId organizationId,
            TeamId teamId,
            WorkProjectId workProjectId,
            TaskExecutionId taskExecutionId) {
        return store.findCommandsByTaskExecution(
                organizationId, teamId, workProjectId, taskExecutionId);
    }

    @Override
    public List<CommandEvidence> findByWorkspace(
            OrganizationId organizationId,
            TeamId teamId,
            WorkProjectId workProjectId,
            ExecutionWorkspaceId workspaceId) {
        return store.findCommandsByWorkspace(
                organizationId, teamId, workProjectId, workspaceId);
    }
}
