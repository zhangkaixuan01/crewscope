package io.crewscope.application.workitem;

import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.workitem.WorkProject;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.List;
import java.util.Optional;

/** Persistence Port for tenant-scoped WorkProject aggregates. */
public interface WorkProjectRepository {

    WorkProject create(WorkProject project);

    WorkProject update(WorkProject project);

    Optional<WorkProject> findById(OrganizationId organizationId, WorkProjectId id);

    List<WorkProject> findByTeam(OrganizationId organizationId, TeamId teamId);
}
