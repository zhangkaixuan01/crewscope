package io.crewscope.application.coding;

import io.crewscope.domain.coding.ProjectExecutionDefaults;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.Optional;

/** Durable project-level execution defaults. A missing row is the explicit version zero state. */
public interface ProjectExecutionDefaultsRepository {
    Optional<ProjectExecutionDefaults> find(
            OrganizationId organizationId, TeamId teamId, WorkProjectId projectId);

    ProjectExecutionDefaults replace(ProjectExecutionDefaults value, long expectedVersion);
}
