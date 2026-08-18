package io.crewscope.application.coding;

import io.crewscope.domain.coding.DiffArtifact;
import io.crewscope.domain.coding.DiffArtifactId;
import io.crewscope.domain.coding.DiffArtifactWorkspaceConflictException;
import io.crewscope.domain.coding.ExecutionWorkspaceId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.Optional;

/** Persistence Port for one immutable final DiffArtifact per ExecutionWorkspace. */
public interface DiffArtifactRepository {

    /** Atomically rejects a second final artifact with {@link DiffArtifactWorkspaceConflictException}. */
    DiffArtifact create(DiffArtifact artifact);

    Optional<DiffArtifact> findById(
            OrganizationId organizationId,
            TeamId teamId,
            WorkProjectId workProjectId,
            DiffArtifactId artifactId);

    Optional<DiffArtifact> findByWorkspace(
            OrganizationId organizationId,
            TeamId teamId,
            WorkProjectId workProjectId,
            ExecutionWorkspaceId workspaceId);

    Optional<DiffArtifact> findByTaskExecution(
            OrganizationId organizationId,
            TeamId teamId,
            WorkProjectId workProjectId,
            TaskExecutionId taskExecutionId);
}
